package com.busnotice.commute

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 主密钥：Android Keystore 中的 AES-256-GCM 密钥，不可导出，只用于封装数据密钥。每把数据密钥对应一个别名。
 * 封装格式：版本字节 1 | IV(12) | 密文 + 标签。
 */
class KeystoreWrapper : KeyWrapper {
    private val ks: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun hasMaster(alias: String) = ks.containsAlias(alias)

    private fun master(alias: String): SecretKey = (ks.getKey(alias, null) as? SecretKey) ?: run {
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        g.generateKey()
    }

    override fun wrap(alias: String, raw: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, master(alias))   // IV 由密钥库生成
        val ct = c.doFinal(raw)
        return byteArrayOf(1) + c.iv + ct
    }

    override fun unwrap(alias: String, blob: ByteArray): ByteArray {
        if (blob.size <= 1 + RecordFormat.IV_LEN + RecordFormat.TAG_BITS / 8 || blob[0] != 1.toByte()) throw KeyBlobInvalid("unsupported key blob")
        val key = ks.getKey(alias, null) as? SecretKey ?: error("master key not available")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(RecordFormat.TAG_BITS, blob, 1, RecordFormat.IV_LEN))
        return try {
            c.doFinal(blob, 1 + RecordFormat.IV_LEN, blob.size - 1 - RecordFormat.IV_LEN)
        } catch (e: AEADBadTagException) {
            throw KeyBlobInvalid("authentication failed", e)
        }
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}

/**
 * 本机原始定位与行程记录的加密入口。
 *
 * 每个进程第一次使用时向系统密钥库解开数据密钥（noBackupFilesDir/local_key*.bin，只存封装后的密文），
 * 之后在内存中缓存密钥和 Cipher，逐条记录用软件 AES-GCM 加解密，不再访问密钥库。
 * 密钥无法使用时不删除任何密钥或记录：旧密钥改名保留并在之后重试，新记录改用新密钥（见 [KeyRing]）；
 * 密钥库暂不可用时暂停写入并按退避间隔重试，不生成新密钥。
 */
object LocalCrypto {
    private const val RETRY_MS = 60_000L
    private const val RETRY_MAX_MS = 30 * 60_000L
    private const val PENDING_MAX_MS = 60 * 60_000L
    /** 每个进程内对待重试旧密钥的额外尝试次数（每次启动加载时还会各试一次） */
    private const val PENDING_TRIES = 6

    data class Status(val readable: Set<String>, val pending: Set<String>, val lost: Set<String>)

    private var ring: KeyRing? = null
    @Volatile private var current: RecordCipher? = null
    private val readers = ConcurrentHashMap<String, RecordCipher>()
    @Volatile private var pending: Set<String> = emptySet()
    @Volatile private var lost: Set<String> = emptySet()
    private var nextTry = 0L
    private var backoff = RETRY_MS
    private var deferNoted = false
    @Volatile private var pendingNext = 0L
    private var pendingBackoff = RETRY_MS
    private var pendingTries = 0
    @Volatile private var unreadableNoted = false

    /** 可读密钥集合每变化一次加 1（例如旧密钥恢复可读），读取方据此刷新缓存 */
    @Volatile var keysVersion = 0
        private set

    private fun cipher(ctx: Context): RecordCipher? {
        current?.let { c ->
            if (pending.isNotEmpty() && SystemClock.elapsedRealtime() >= pendingNext) retryPending(ctx)
            return c
        }
        return load(ctx)
    }

    @Synchronized
    private fun load(ctx: Context): RecordCipher? {
        current?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (nextTry != 0L && now < nextTry) return null
        val c = ctx.applicationContext
        val r = runCatching { KeyRing(KeystoreWrapper(), FileKeyFiles(c.noBackupFilesDir)) }.getOrNull()
        val st = r?.let { runCatching { it.load() }.getOrNull() }
        val cur = st?.current
        if (st == null || cur == null) {
            if (!deferNoted) { deferNoted = true; Diag.note(c, "本机加密密钥暂不可用，行程记录暂停写入，稍后自动重试") }
            nextTry = now + backoff; backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
            return null
        }
        ring = r
        readers.clear(); readers.putAll(st.readable)
        pending = st.pending; lost = st.lost
        nextTry = 0L; backoff = RETRY_MS
        pendingNext = now + RETRY_MS; pendingBackoff = RETRY_MS; pendingTries = 0
        when (st.rotation) {
            KeyRing.Rotation.LOST -> Diag.note(c, "系统已清除本机加密密钥，新记录改用新密钥；无法读取的旧记录保留在本机，可在设置检查中处理")
            KeyRing.Rotation.INVALID -> Diag.note(c, "本机加密密钥暂时无法解开，新记录改用新密钥；旧密钥已保留，之后会再次尝试读取旧记录")
            KeyRing.Rotation.MISSING -> Diag.note(c, "本机加密密钥文件缺失，新记录改用新密钥")
            else -> Unit
        }
        current = cur
        keysVersion++
        return cur
    }

    @Synchronized
    private fun retryPending(ctx: Context) {
        val r = ring ?: return
        val now = SystemClock.elapsedRealtime()
        if (pending.isEmpty() || now < pendingNext) return
        if (pendingTries >= PENDING_TRIES) { pendingNext = Long.MAX_VALUE; return }
        pendingTries++
        val lostNow = mutableSetOf<String>()
        val got = runCatching { r.retryPending(lostNow) }.getOrDefault(emptyMap())
        readers.putAll(got)
        pending = pending - got.keys - lostNow
        lost = lost + lostNow
        pendingNext = now + pendingBackoff; pendingBackoff = (pendingBackoff * 2).coerceAtMost(PENDING_MAX_MS)
        if (got.isNotEmpty() || lostNow.isNotEmpty()) {
            keysVersion++
            if (got.isNotEmpty()) { unreadableNoted = false; Diag.note(ctx.applicationContext, "此前无法解密的本机记录已恢复可读") }
        }
    }

    /** 提前加载密钥（后台线程调用） */
    fun warm(ctx: Context) { cipher(ctx) }

    /** 密钥是否可用 */
    fun ready(ctx: Context) = cipher(ctx) != null

    /** 各密钥当前状态；密钥暂不可用时返回 null */
    fun status(ctx: Context): Status? {
        cipher(ctx) ?: return null
        return Status(readers.keys.toSet(), pending, lost)
    }

    /** 加密一条记录；密钥不可用时返回 null，调用方不写入（不以明文落盘） */
    fun encrypt(ctx: Context, plain: String): String? = runCatching { cipher(ctx)?.encrypt(plain) }.getOrNull()

    /** 读取一个值：密文按 keyId 选密钥解密、旧版明文原样返回；无法解密时返回 null 并记一次诊断 */
    fun decode(ctx: Context, raw: String): String? {
        if (!RecordFormat.isEncrypted(raw)) return raw
        cipher(ctx) ?: return null
        val p = RecordFormat.keyIdOf(raw)?.let { readers[it] }?.decrypt(raw)
        if (p == null) noteUnreadable(ctx)
        return p
    }

    /** 某条密文现在能否解密所用的密钥（不解密） */
    fun canRead(ctx: Context, raw: String): Boolean {
        if (!RecordFormat.isEncrypted(raw)) return true
        cipher(ctx) ?: return false
        return RecordFormat.keyIdOf(raw)?.let { readers.containsKey(it) } == true
    }

    /** 逐行读取：密文按 keyId 解密、旧版明文保留，无法解密的行跳过（文件不改动）并记一次诊断 */
    fun decodeLines(ctx: Context, lines: List<String>): List<String> {
        if (lines.any { RecordFormat.isEncrypted(it) }) cipher(ctx)
        val d = RecordCodec.decodeLines(lines) { readers[it] }
        if (d.unreadable > 0) noteUnreadable(ctx)
        return d.text
    }

    /** 逐行读取并返回跳过的条数（导出迁移文件用） */
    fun decodeCounted(ctx: Context, lines: List<String>): Decoded {
        if (lines.any { RecordFormat.isEncrypted(it) }) cipher(ctx)
        return RecordCodec.decodeLines(lines) { readers[it] }
    }

    private fun noteUnreadable(ctx: Context) {
        if (unreadableNoted) return
        unreadableNoted = true
        Diag.note(ctx, "部分本机记录暂时无法解密，已跳过读取；记录本身保留在本机")
    }
}

/**
 * traces 目录（行程记录、公交到站采样等）的读写：所有写入逐行加密，读取时兼容旧版明文行。
 * 追加与迁移共用一把锁，迁移不会和写入交错；正在由 [TraceLog] 持续写入的文件不参与后台迁移。
 */
object TraceStore {
    val lock = Any()
    private const val DONE_FILE = "traces_encrypted_v1"
    private const val INDEX_FILE = "trace_key_index.json"
    private var openFile: File? = null

    /** 文件名 → （长度、修改时间、出现的密钥标识） */
    private class IdEntry(val len: Long, val mtime: Long, val ids: Set<String>)
    private val index = ConcurrentHashMap<String, IdEntry>()
    @Volatile private var indexLoaded = false
    @Volatile private var indexDirty = false

    /** 最近一次统计的无法读取记录（后台刷新，界面只读这个结果） */
    @Volatile var locked: LockedRecords.Summary? = null
        private set
    @Volatile private var lockedVersion = -1

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

    /** 记录文件（不含临时文件） */
    fun files(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && !f.name.endsWith(RecordMigration.TMP_SUFFIX) }.orEmpty().sortedBy { it.name }

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

    // ---------- 密钥标识索引与无法读取的记录 ----------

    private fun loadIndex(ctx: Context) {
        if (indexLoaded) return
        synchronized(index) {
            if (indexLoaded) return
            runCatching {
                val f = File(ctx.noBackupFilesDir, INDEX_FILE)
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    o.keys().forEach { k ->
                        val e = o.getJSONObject(k); val a = e.getJSONArray("ids")
                        index[k] = IdEntry(e.getLong("len"), e.getLong("mtime"), (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
            }
            indexLoaded = true
        }
    }

    private fun saveIndex(ctx: Context, names: Set<String>) {
        if (!indexDirty) return
        index.keys.retainAll(names)
        val o = JSONObject()
        index.forEach { (k, e) -> o.put(k, JSONObject().put("len", e.len).put("mtime", e.mtime).put("ids", JSONArray(e.ids.toList()))) }
        runCatching { FileKeyFiles.atomicWrite(File(ctx.noBackupFilesDir, INDEX_FILE), o.toString().toByteArray()) }
        indexDirty = false
    }

    /** 文件中出现的密钥标识（按长度和修改时间缓存，文件不变时不重读） */
    fun keyIds(ctx: Context, f: File): Set<String> {
        loadIndex(ctx)
        val len = f.length(); val mt = f.lastModified()
        index[f.name]?.takeIf { it.len == len && it.mtime == mt }?.let { return it.ids }
        val ids = synchronized(lock) { runCatching { f.useLines { RecordCodec.keyIds(it) } }.getOrDefault(emptySet()) }
        index[f.name] = IdEntry(len, mt, ids); indexDirty = true
        return ids
    }

    /** 文件能否按保留期删除：其中每条密文现在都能读（暂时读不了、或不可恢复的记录由用户决定，不自动删除） */
    fun prunable(ctx: Context, f: File): Boolean {
        val st = LocalCrypto.status(ctx) ?: return false
        return keyIds(ctx, f).all { it in st.readable }
    }

    /** 重新统计无法读取的记录（后台线程调用）；密钥暂不可用时不更新 */
    fun refreshLocked(ctx: Context): LockedRecords.Summary? {
        val c = ctx.applicationContext
        val st = LocalCrypto.status(c) ?: return locked
        val fs = files(c)
        val ids = fs.associate { it.name to keyIds(c, it) }
        saveIndex(c, fs.map { it.name }.toSet())
        return LockedRecords.summarize(ids, st.readable, st.pending, st.lost).also { locked = it; lockedVersion = LocalCrypto.keysVersion }
    }

    /** 可读密钥变化后（例如旧密钥恢复）需要重新统计 */
    fun lockedStale() = lockedVersion != LocalCrypto.keysVersion

    /**
     * 删除不可恢复的记录（只由用户在设置检查中确认后调用）：只删除所用密钥已不可恢复的行，
     * 同一文件中的其他记录保留；整个文件都不可恢复时删除文件。正在写入的文件跳过。返回处理的文件数。
     */
    fun purgeLost(ctx: Context): Int {
        val c = ctx.applicationContext
        val st = LocalCrypto.status(c) ?: return 0
        val isLost = { id: String -> LockedRecords.isLost(id, st.readable, st.pending, st.lost) }
        var n = 0
        files(c).forEach { f ->
            if (keyIds(c, f).none(isLost)) return@forEach
            synchronized(lock) {
                if (f == openFile) return@synchronized
                runCatching {
                    RecordMigration.rewrite(f) { l -> if (RecordFormat.keyIdOf(l)?.let(isLost) == true) null else l }
                    n++
                }
            }
        }
        Places.purgeLostSamples(c, isLost)
        refreshLocked(c)
        return n
    }
}
