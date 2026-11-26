package com.busnotice.commute

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 主密钥：Android Keystore 中的 AES-256-GCM 密钥，不可导出，只用于封装数据密钥。
 * 封装格式：版本字节 1 | IV(12) | 密文 + 标签。
 */
class KeystoreWrapper(private val alias: String = ALIAS) : KeyWrapper {
    private val ks: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun hasMaster() = ks.containsAlias(alias)

    private fun master(): SecretKey = (ks.getKey(alias, null) as? SecretKey) ?: run {
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        g.generateKey()
    }

    override fun wrap(raw: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, master())   // IV 由密钥库生成
        val ct = c.doFinal(raw)
        return byteArrayOf(1) + c.iv + ct
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > 1 + RecordFormat.IV_LEN && blob[0] == 1.toByte()) { "unsupported key blob" }
        val key = ks.getKey(alias, null) as? SecretKey ?: error("master key missing")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(RecordFormat.TAG_BITS, blob, 1, RecordFormat.IV_LEN))
        return c.doFinal(blob, 1 + RecordFormat.IV_LEN, blob.size - 1 - RecordFormat.IV_LEN)
    }

    override fun deleteMaster() = ks.deleteEntry(alias)

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "commute_local_records"
    }
}

/**
 * 本机原始定位与行程记录的加密入口。
 *
 * 每个进程第一次使用时向系统密钥库解开一次数据密钥（noBackupFilesDir/local_key.bin，只存封装后的密文），
 * 之后在内存中缓存密钥和 Cipher，逐条记录用软件 AES-GCM 加解密，不再访问密钥库。
 * 密钥丢失或损坏时重新生成并记一次诊断，旧密文跳过不读。
 */
object LocalCrypto {
    private const val KEY_FILE = "local_key.bin"
    private const val RETRY_MS = 60_000L

    @Volatile private var cipher: RecordCipher? = null
    private var failedAt = 0L
    @Volatile private var unreadableNoted = false

    @Synchronized
    private fun cipher(ctx: Context): RecordCipher? {
        cipher?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (failedAt != 0L && now - failedAt < RETRY_MS) return null
        val c = ctx.applicationContext
        val loaded = runCatching {
            KeyRing(KeystoreWrapper(), FileBlobStore(File(c.noBackupFilesDir, KEY_FILE))).load()
        }.getOrNull()
        if (loaded == null) {
            if (failedAt == 0L) Diag.note(c, "本机加密密钥暂不可用，行程记录暂停写入")
            failedAt = now
            return null
        }
        if (loaded.reset) Diag.note(c, "本机加密密钥已丢失，已重新生成；无法解密的旧记录将被跳过")
        return RecordCipher(loaded.key).also { cipher = it; failedAt = 0L }
    }

    /** 提前加载密钥（后台线程调用） */
    fun warm(ctx: Context) { cipher(ctx) }

    /** 密钥是否可用 */
    fun ready(ctx: Context) = cipher(ctx) != null

    /** 加密一条记录；密钥不可用时返回 null，调用方不写入（不以明文落盘） */
    fun encrypt(ctx: Context, plain: String): String? = runCatching { cipher(ctx)?.encrypt(plain) }.getOrNull()

    /** 读取一个值：密文解密、旧版明文原样返回；无法解密时返回 null 并记一次诊断 */
    fun decode(ctx: Context, raw: String): String? {
        if (!RecordFormat.isEncrypted(raw)) return raw
        return cipher(ctx)?.decrypt(raw) ?: null.also { noteUnreadable(ctx) }
    }

    /** 逐行读取：密文解密、旧版明文保留，无法解密的行跳过并记一次诊断 */
    fun decodeLines(ctx: Context, lines: List<String>): List<String> {
        val d = RecordCodec.decodeLines(lines, if (lines.any { RecordFormat.isEncrypted(it) }) cipher(ctx) else null)
        if (d.unreadable > 0) noteUnreadable(ctx)
        return d.text
    }

    private fun noteUnreadable(ctx: Context) {
        if (unreadableNoted) return
        unreadableNoted = true
        Diag.note(ctx, "部分本机记录无法解密（密钥已更换），已跳过")
    }
}

/**
 * traces 目录（行程记录、公交到站采样等）的读写：所有写入逐行加密，读取时兼容旧版明文行。
 * 追加与迁移共用一把锁，迁移不会和写入交错；正在由 [TraceLog] 持续写入的文件不参与后台迁移。
 */
object TraceStore {
    val lock = Any()
    private const val DONE_FILE = "traces_encrypted_v1"
    private var openFile: File? = null

    fun dir(ctx: Context) = File(ctx.filesDir, "traces").apply { mkdirs() }

    /** 追加一条记录（每次打开、写入、关闭） */
    fun append(ctx: Context, file: File, plain: String) {
        val line = LocalCrypto.encrypt(ctx, plain) ?: return
        synchronized(lock) { file.parentFile?.mkdirs(); file.appendText(line + "\n") }
    }

    /** 读出文件全部记录（已解密） */
    fun readLines(ctx: Context, f: File): List<String> {
        val raw = synchronized(lock) { if (f.exists()) f.readLines() else emptyList() }
        return LocalCrypto.decodeLines(ctx, raw)
    }

    /** [TraceLog] 打开文件前调用：先把该文件的旧明文转成密文，再标记为正在写入 */
    fun beginAppend(ctx: Context, f: File) = synchronized(lock) {
        if (f.exists()) runCatching { RecordMigration.migrateFile(f) { LocalCrypto.encrypt(ctx, it) } }
        openFile = f
    }

    fun endAppend() = synchronized(lock) { openFile = null }

    /**
     * 首次启动把 traces 下的旧明文记录就地转为密文（后台线程调用）。
     * 全部完成后写入完成标记；有文件失败或正在写入时不写标记，下次启动继续。
     */
    fun migrateAll(ctx: Context) {
        val c = ctx.applicationContext
        val done = File(c.noBackupFilesDir, DONE_FILE)
        if (done.exists()) return
        val d = dir(c)
        var complete = true
        synchronized(lock) { RecordMigration.cleanup(d) }
        d.listFiles { x -> !x.name.endsWith(RecordMigration.TMP_SUFFIX) && x.isFile }?.forEach { f ->
            synchronized(lock) {
                if (f == openFile) { complete = false; return@synchronized }
                runCatching { RecordMigration.migrateFile(f) { LocalCrypto.encrypt(c, it) } }
                    .onFailure { complete = false }
            }
        }
        if (complete) runCatching { done.createNewFile() }
    }
}
