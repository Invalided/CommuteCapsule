package com.busnotice.commute

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 按记录（每行 / 每个值）加密的存储格式，与 Android 无关，可在 JVM 上单测。
 *
 * 格式：`e1:` + Base64(IV(12 字节) | 密文 | GCM 标签(16 字节))，AES-256-GCM，每条记录独立随机 IV。
 * 不带前缀的内容按旧版本明文读取。
 */
object RecordFormat {
    const val PREFIX = "e1:"
    const val IV_LEN = 12
    const val TAG_BITS = 128
    const val KEY_BYTES = 32

    fun isEncrypted(s: String) = s.startsWith(PREFIX)
}

/** 用一把 AES 密钥加解密单条记录。内部复用 Cipher 实例，方法加锁，可跨线程调用。 */
class RecordCipher(private val key: SecretKey, private val random: SecureRandom = SecureRandom()) {
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")

    @Synchronized
    fun encrypt(plain: String): String {
        val iv = ByteArray(RecordFormat.IV_LEN).also(random::nextBytes)
        enc.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(RecordFormat.TAG_BITS, iv))
        val ct = enc.doFinal(plain.toByteArray(Charsets.UTF_8))
        return RecordFormat.PREFIX + Base64.getEncoder().encodeToString(iv + ct)
    }

    /** 解密一条带前缀的记录；格式错误、密钥不符或内容被改动时返回 null */
    @Synchronized
    fun decrypt(record: String): String? = runCatching {
        if (!RecordFormat.isEncrypted(record)) return null
        val b = Base64.getDecoder().decode(record.substring(RecordFormat.PREFIX.length).trim())
        if (b.size < RecordFormat.IV_LEN + RecordFormat.TAG_BITS / 8) return null
        dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(RecordFormat.TAG_BITS, b, 0, RecordFormat.IV_LEN))
        String(dec.doFinal(b, RecordFormat.IV_LEN, b.size - RecordFormat.IV_LEN), Charsets.UTF_8)
    }.getOrNull()
}

/** 读取结果：[text] 为可用内容；[unreadable] 为无法解密而跳过的记录数 */
data class Decoded(val text: List<String>, val unreadable: Int)

object RecordCodec {
    /**
     * 逐行解码：密文行解密，旧版明文行原样保留，空行忽略；无法解密的行（[cipher] 为 null 或密钥不符）跳过并计数。
     */
    fun decodeLines(lines: List<String>, cipher: RecordCipher?): Decoded {
        val out = ArrayList<String>(lines.size)
        var bad = 0
        for (l in lines) {
            if (l.isEmpty()) continue
            if (!RecordFormat.isEncrypted(l)) { out += l; continue }
            val p = cipher?.decrypt(l)
            if (p == null) bad++ else out += p
        }
        return Decoded(out, bad)
    }
}

/**
 * 数据密钥的封装：主密钥只在系统密钥库内使用、不可导出；数据密钥只以被主密钥加密后的形式落盘。
 * Android 实现见 [KeystoreWrapper]，单测使用软件实现。
 */
interface KeyWrapper {
    /** 是否已有主密钥 */
    fun hasMaster(): Boolean
    /** 用主密钥加密数据密钥（主密钥不存在时先生成） */
    fun wrap(raw: ByteArray): ByteArray
    /** 解开数据密钥；主密钥不存在、不符或损坏时抛出异常 */
    fun unwrap(blob: ByteArray): ByteArray
    /** 删除主密钥（下次 [wrap] 时重新生成） */
    fun deleteMaster()
}

/** 封装后数据密钥的持久化位置 */
interface BlobStore {
    fun read(): ByteArray?
    /** 原子写入并落盘；失败返回 false */
    fun write(b: ByteArray): Boolean
}

/** 本机数据密钥的加载与重建 */
class KeyRing(private val wrapper: KeyWrapper, private val store: BlobStore, private val random: SecureRandom = SecureRandom()) {
    /** [reset] 为 true 表示原有密钥已丢失或损坏、本次重新生成，旧密文将无法解密 */
    class Loaded(val key: SecretKey, val reset: Boolean)

    /**
     * 先解开已保存的数据密钥（失败时重试一次）；没有保存、主密钥丢失或无法解开时删除主密钥并重新生成两把密钥。
     * 新密钥只有在封装结果成功落盘后才返回；无法生成或保存时返回 null（调用方不写入记录）。
     */
    fun load(): Loaded? {
        val blob = store.read()
        // 密钥库本身不可用时不重建，避免把暂时性故障当成密钥丢失
        val hadMaster = runCatching { wrapper.hasMaster() }.getOrElse { return null }
        if (blob != null && hadMaster) {
            repeat(2) {
                runCatching { wrapper.unwrap(blob) }.getOrNull()
                    ?.takeIf { it.size == RecordFormat.KEY_BYTES }
                    ?.let { return Loaded(SecretKeySpec(it, "AES"), false) }
            }
        }
        if (hadMaster) runCatching { wrapper.deleteMaster() }
        val raw = ByteArray(RecordFormat.KEY_BYTES).also(random::nextBytes)
        val wrapped = runCatching { wrapper.wrap(raw) }.getOrNull() ?: return null
        if (!store.write(wrapped)) return null
        return Loaded(SecretKeySpec(raw, "AES"), reset = blob != null)
    }
}

/** 文件形式的 [BlobStore]：先写临时文件并同步落盘，再整体替换 */
class FileBlobStore(private val file: File) : BlobStore {
    override fun read(): ByteArray? = runCatching { if (file.exists()) file.readBytes() else null }.getOrNull()
    override fun write(b: ByteArray): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(b); it.flush(); it.fd.sync() }
        tmp.renameTo(file)
    }.getOrDefault(false)
}

/** 旧版明文记录文件就地转为密文 */
object RecordMigration {
    const val TMP_SUFFIX = ".tmp"

    /** 文件中是否还有明文行 */
    fun hasPlain(f: File): Boolean = f.useLines { s -> s.any { it.isNotEmpty() && !RecordFormat.isEncrypted(it) } }

    /**
     * 把 [f] 中的明文行逐行加密：完整内容先写入同目录临时文件并同步落盘，再整体替换原文件。
     * 中途任何失败都删除临时文件、保留原文件不变，下次可重新执行。已加密的行原样保留，空行丢弃。
     * 返回转换的行数（没有明文行时为 0）；失败时抛出 [IOException]。
     */
    fun migrateFile(f: File, encrypt: (String) -> String?): Int {
        if (!hasPlain(f)) return 0
        val tmp = File(f.parentFile, f.name + TMP_SUFFIX)
        var n = 0
        try {
            FileOutputStream(tmp).use { out ->
                val w = out.bufferedWriter(Charsets.UTF_8)
                f.useLines { lines ->
                    for (l in lines) {
                        if (l.isEmpty()) continue
                        val e = if (RecordFormat.isEncrypted(l)) l else (encrypt(l) ?: throw IOException("encryption unavailable")).also { n++ }
                        w.write(e); w.write("\n")
                    }
                }
                w.flush(); out.fd.sync()
            }
            if (!tmp.renameTo(f)) throw IOException("rename failed: ${f.name}")
        } catch (e: Exception) {
            tmp.delete()
            throw e as? IOException ?: IOException(e)
        }
        return n
    }

    /** 删除上次中断留下的临时文件 */
    fun cleanup(dir: File) {
        dir.listFiles { x -> x.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
    }
}
