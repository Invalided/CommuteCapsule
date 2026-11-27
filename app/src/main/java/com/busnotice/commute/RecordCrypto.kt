package com.busnotice.commute

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 按记录（每行 / 每个值）加密的存储格式，与 Android 无关，可在 JVM 上单测。
 *
 * - 当前格式：`e2:<keyId>:` + Base64(IV(12 字节) | 密文 | GCM 标签(16 字节))，AES-256-GCM，每条记录独立随机 IV；
 *   keyId 标明这条记录由哪一把数据密钥加密，读取时按 keyId 选择密钥。
 * - 压缩块：`e3:<keyId>:` + Base64(IV(12 字节) | 密文 | GCM 标签(16 字节))，AES-256-GCM，
 *   以前缀 `e3:<keyId>:` 为附加认证数据；明文为 [TraceBlock] 编码的一组记录（首字节为编码方式）。
 *   一行压缩块解开后得到多条记录，位置与顺序同其他行一样按行序排列。
 * - 旧格式：`e1:` + 同样的 Base64 内容，视为由初始密钥（keyId 为 [LEGACY_ID]）加密。
 * - 不带前缀的内容按旧版本明文读取。
 */
object RecordFormat {
    const val PREFIX_V1 = "e1:"
    const val PREFIX_V2 = "e2:"
    const val PREFIX_BLOCK = "e3:"
    /** 初始密钥的标识：e1 记录与升级前生成的数据密钥 */
    const val LEGACY_ID = "0"
    const val IV_LEN = 12
    const val TAG_BITS = 128
    const val KEY_BYTES = 32
    private val ID_RE = Regex("^[0-9a-z]{1,16}$")

    fun isEncrypted(s: String) = s.startsWith(PREFIX_V1) || s.startsWith(PREFIX_V2) || s.startsWith(PREFIX_BLOCK)

    /** 是否为压缩块（一行包含多条记录） */
    fun isBlock(s: String) = s.startsWith(PREFIX_BLOCK)

    private fun prefixed(s: String) = when {
        s.startsWith(PREFIX_V2) -> PREFIX_V2
        s.startsWith(PREFIX_BLOCK) -> PREFIX_BLOCK
        else -> null
    }

    fun validId(id: String) = ID_RE.matches(id)

    /** 记录所用密钥的标识；明文或格式错误时返回 null */
    fun keyIdOf(s: String): String? = when {
        s.startsWith(PREFIX_V1) -> LEGACY_ID
        else -> prefixed(s)?.let { p ->
            val end = s.indexOf(':', p.length)
            if (end < 0) null else s.substring(p.length, end).takeIf(::validId)
        }
    }

    /** 记录中 Base64 部分；明文或格式错误时返回 null */
    fun payloadOf(s: String): String? = when {
        s.startsWith(PREFIX_V1) -> s.substring(PREFIX_V1.length)
        else -> prefixed(s)?.let { p -> s.indexOf(':', p.length).takeIf { it >= 0 }?.let { s.substring(it + 1) } }
    }
}

/** 用一把 AES 密钥加解密单条记录。[id] 写入记录前缀。内部复用 Cipher 实例，方法加锁，可跨线程调用。 */
class RecordCipher(
    private val key: SecretKey,
    val id: String = RecordFormat.LEGACY_ID,
    private val random: SecureRandom = SecureRandom(),
) {
    init { require(RecordFormat.validId(id)) { "invalid key id" } }

    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")

    /** 压缩块的加解密（独立的 Cipher 实例与锁） */
    internal val blockCipher by lazy { BlockCipher(key, id, random) }

    @Synchronized
    fun encrypt(plain: String): String {
        val iv = ByteArray(RecordFormat.IV_LEN).also(random::nextBytes)
        enc.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(RecordFormat.TAG_BITS, iv))
        val ct = enc.doFinal(plain.toByteArray(Charsets.UTF_8))
        return RecordFormat.PREFIX_V2 + id + ":" + Base64.getEncoder().encodeToString(iv + ct)
    }

    /** 解密一条带前缀的记录；格式错误、密钥不符或内容被改动时返回 null */
    @Synchronized
    fun decrypt(record: String): String? = runCatching {
        if (RecordFormat.isBlock(record)) return null
        val p = RecordFormat.payloadOf(record) ?: return null
        val b = Base64.getDecoder().decode(p.trim())
        if (b.size < RecordFormat.IV_LEN + RecordFormat.TAG_BITS / 8) return null
        dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(RecordFormat.TAG_BITS, b, 0, RecordFormat.IV_LEN))
        String(dec.doFinal(b, RecordFormat.IV_LEN, b.size - RecordFormat.IV_LEN), Charsets.UTF_8)
    }.getOrNull()
}

/** 用一把 AES 密钥加解密压缩块（`e3` 行）；与 [RecordCipher] 共用密钥与标识 */
fun RecordCipher.encryptBlock(payload: ByteArray): String = blockCipher.encrypt(payload)

/** 解密一行压缩块，返回 [TraceBlock] 编码内容；格式错误、密钥不符或内容被改动时返回 null */
fun RecordCipher.decryptBlock(record: String): ByteArray? = blockCipher.decrypt(record)

/** 压缩块的整块 AES-GCM 加解密，附加认证数据为行前缀 `e3:<keyId>:` */
internal class BlockCipher(private val key: SecretKey, private val id: String, private val random: SecureRandom) {
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")
    private val prefix = RecordFormat.PREFIX_BLOCK + id + ":"
    private val aad = prefix.toByteArray(Charsets.US_ASCII)

    @Synchronized
    fun encrypt(payload: ByteArray): String {
        val iv = ByteArray(RecordFormat.IV_LEN).also(random::nextBytes)
        enc.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(RecordFormat.TAG_BITS, iv))
        enc.updateAAD(aad)
        val ct = enc.doFinal(payload)
        return prefix + Base64.getEncoder().encodeToString(iv + ct)
    }

    @Synchronized
    fun decrypt(record: String): ByteArray? = runCatching {
        if (!record.startsWith(prefix)) return null
        val b = Base64.getDecoder().decode(record.substring(prefix.length).trim())
        if (b.size < RecordFormat.IV_LEN + RecordFormat.TAG_BITS / 8) return null
        dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(RecordFormat.TAG_BITS, b, 0, RecordFormat.IV_LEN))
        dec.updateAAD(aad)
        dec.doFinal(b, RecordFormat.IV_LEN, b.size - RecordFormat.IV_LEN)
    }.getOrNull()
}

/** 读取结果：[text] 为可用内容；[unreadable] 为无法解密而跳过的记录数（一个压缩块计 1） */
data class Decoded(val text: List<String>, val unreadable: Int)

object RecordCodec {
    /**
     * 逐行解码：密文行按其 keyId 取密钥解密，压缩块展开为其中的各条记录，旧版明文行原样保留，空行忽略；
     * 没有对应密钥或解密、解码失败的行跳过并计数（文件本身不改动）。
     */
    fun decodeLines(lines: List<String>, keys: (String) -> RecordCipher?): Decoded {
        val out = ArrayList<String>(lines.size)
        var bad = 0
        for (l in lines) {
            if (l.isEmpty()) continue
            if (!RecordFormat.isEncrypted(l)) { out += l; continue }
            if (RecordFormat.isBlock(l)) {
                val block = RecordFormat.keyIdOf(l)?.let(keys)?.decryptBlock(l)?.let(TraceBlock::decode)
                if (block == null) bad++ else out += block
                continue
            }
            val p = RecordFormat.keyIdOf(l)?.let(keys)?.decrypt(l)
            if (p == null) bad++ else out += p
        }
        return Decoded(out, bad)
    }

    /** 只有一把密钥时的便捷写法（按 keyId 匹配） */
    fun decodeLines(lines: List<String>, cipher: RecordCipher?): Decoded =
        decodeLines(lines) { id -> cipher?.takeIf { it.id == id } }

    /** 一组记录中出现的密钥标识 */
    fun keyIds(lines: Sequence<String>): Set<String> = lines.mapNotNull { RecordFormat.keyIdOf(it) }.toSet()
}

/**
 * 主密钥只在系统密钥库内使用、不可导出；数据密钥只以被主密钥加密后的形式落盘。
 * 每把数据密钥对应一个独立的主密钥别名，因此可以按别名是否存在判断某把数据密钥能否恢复。
 * Android 实现见 KeystoreWrapper，单测使用软件实现。
 */
interface KeyWrapper {
    /** 系统密钥库中是否有该别名的主密钥；密钥库暂不可用时抛出异常 */
    fun hasMaster(alias: String): Boolean
    /** 用主密钥加密数据密钥（主密钥不存在时先生成） */
    fun wrap(alias: String, raw: ByteArray): ByteArray
    /**
     * 解开数据密钥。封装内容与主密钥确定不匹配（认证失败、格式错误）时抛出 [KeyBlobInvalid]；
     * 其他异常（密钥库暂不可用等）视为暂时性故障。
     */
    fun unwrap(alias: String, blob: ByteArray): ByteArray
}

/** 封装内容与主密钥确定不匹配（不是暂时性故障） */
class KeyBlobInvalid(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 一把数据密钥的落盘内容：标识、主密钥别名与封装结果。
 * 新格式以 `CK2` 开头；升级前的文件直接是封装结果（首字节为 1），视为初始密钥。
 */
class KeyEnvelope(val id: String, val alias: String, val wrapped: ByteArray) {
    fun encode(): ByteArray {
        val i = id.toByteArray(Charsets.US_ASCII); val a = alias.toByteArray(Charsets.US_ASCII)
        return MAGIC + byteArrayOf(i.size.toByte()) + i + byteArrayOf(a.size.toByte()) + a + wrapped
    }

    companion object {
        const val LEGACY_ALIAS = "commute_local_records"
        private val MAGIC = byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), '2'.code.toByte())

        fun aliasFor(id: String) = "${LEGACY_ALIAS}_$id"

        /** 解析落盘内容；无法识别时返回 null */
        fun decode(b: ByteArray): KeyEnvelope? = runCatching {
            if (b.isNotEmpty() && b[0] == 1.toByte()) return KeyEnvelope(RecordFormat.LEGACY_ID, LEGACY_ALIAS, b)
            if (b.size < 6 || !b.copyOfRange(0, 3).contentEquals(MAGIC)) return null
            var p = 3
            val il = b[p++].toInt() and 0xff
            val id = String(b, p, il, Charsets.US_ASCII); p += il
            val al = b[p++].toInt() and 0xff
            val alias = String(b, p, al, Charsets.US_ASCII); p += al
            if (!RecordFormat.validId(id) || alias.isEmpty() || p >= b.size) return null
            KeyEnvelope(id, alias, b.copyOfRange(p, b.size))
        }.getOrNull()
    }
}

/** 数据密钥文件：当前密钥一份，因无法使用而停用的旧密钥改名保留 */
interface KeyFiles {
    fun readCurrent(): ByteArray?
    /** 写入当前密钥（原子写并落盘）；已有当前密钥文件时拒绝写入，返回 false */
    fun writeCurrent(b: ByteArray): Boolean
    /** 把当前密钥文件改名保留（不删除）；返回保留后的文件名，没有当前密钥时返回空串，失败返回 null */
    fun retireCurrent(): String?
    /** 保留的旧密钥：文件名 → 内容 */
    fun retired(): Map<String, ByteArray>
}

/** 文件形式的 [KeyFiles]：当前密钥为 local_key.bin，保留的旧密钥为 local_key.<时间戳>.bin */
class FileKeyFiles(
    private val dir: File,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) : KeyFiles {
    private val current = File(dir, CURRENT)

    override fun readCurrent(): ByteArray? = runCatching { if (current.exists()) current.readBytes() else null }.getOrNull()

    override fun writeCurrent(b: ByteArray): Boolean = runCatching {
        if (current.exists()) return false
        atomicWrite(current, b)
    }.getOrDefault(false)

    override fun retireCurrent(): String? {
        if (!current.exists()) return ""
        val stamp = now().format(STAMP)
        var n = 0
        var target: File
        do {
            target = File(dir, "local_key.$stamp${if (n == 0) "" else "-$n"}.bin"); n++
        } while (target.exists())
        return if (current.renameTo(target)) target.name else null
    }

    override fun retired(): Map<String, ByteArray> =
        dir.listFiles { f -> RETIRED.matches(f.name) }.orEmpty().sortedBy { it.name }
            .mapNotNull { f -> runCatching { f.name to f.readBytes() }.getOrNull() }.toMap()

    companion object {
        const val CURRENT = "local_key.bin"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val RETIRED = Regex("""^local_key\.\d{8}-\d{6}(-\d+)?\.bin$""")

        /** 先写同目录临时文件并同步落盘，再整体替换 [f]；失败时 [f] 不变 */
        fun atomicWrite(f: File, b: ByteArray): Boolean {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            return runCatching {
                FileOutputStream(tmp).use { it.write(b); it.flush(); it.fd.sync() }
                tmp.renameTo(f) || run { tmp.delete(); false }
            }.getOrElse { tmp.delete(); false }
        }
    }
}

/**
 * 本机数据密钥的加载、停用与恢复。永不删除密钥文件或主密钥：
 * - 当前密钥的主密钥别名在密钥库中确实不存在：判定不可恢复，旧密钥文件改名保留，新记录改用新密钥；
 * - 连续两次认证失败（[KeyBlobInvalid]）：旧密钥改名保留并在之后重试，新记录改用新密钥；
 * - 密钥库暂不可用或其他暂时性异常：不生成新密钥，返回 [State.deferred]，由调用方稍后重试。
 * 保留的旧密钥每次加载都会再解一次，成功后对应的旧记录恢复可读。
 */
class KeyRing(
    private val wrapper: KeyWrapper,
    private val files: KeyFiles,
    private val random: SecureRandom = SecureRandom(),
) {
    enum class Rotation {
        /** 首次生成（之前没有任何密钥文件） */
        CREATED,
        /** 当前密钥文件不见了（之前保留过旧密钥），新生成一把 */
        MISSING,
        /** 当前密钥的主密钥已被系统清除，不可恢复 */
        LOST,
        /** 当前密钥无法解开（认证失败或文件损坏），已保留待重试 */
        INVALID,
    }

    class State(
        /** 新记录使用的密钥；为 null 表示暂不可用（[deferred]） */
        val current: RecordCipher?,
        /** 可读的全部密钥（含当前），按标识索引 */
        val readable: Map<String, RecordCipher>,
        /** 已保留、主密钥仍在但暂时没能解开的密钥标识，之后再试 */
        val pending: Set<String>,
        /** 主密钥已不存在、不可恢复的密钥标识 */
        val lost: Set<String>,
        /** 本次加载是否换用了新密钥及原因 */
        val rotation: Rotation?,
    ) {
        val deferred get() = current == null
    }

    private sealed interface Attempt {
        class Ok(val raw: ByteArray) : Attempt
        object Invalid : Attempt
        object Transient : Attempt
    }

    private val pendingEnvelopes = LinkedHashMap<String, KeyEnvelope>()

    private fun unwrap(env: KeyEnvelope, tries: Int): Attempt {
        var last: Attempt = Attempt.Transient
        repeat(tries) {
            last = try {
                val raw = wrapper.unwrap(env.alias, env.wrapped)
                if (raw.size == RecordFormat.KEY_BYTES) return Attempt.Ok(raw) else Attempt.Invalid
            } catch (e: KeyBlobInvalid) {
                Attempt.Invalid
            } catch (e: Exception) {
                return Attempt.Transient
            }
        }
        return last
    }

    private fun cipher(id: String, raw: ByteArray) = RecordCipher(SecretKeySpec(raw, "AES"), id, random)

    private fun deferred(readable: Map<String, RecordCipher> = emptyMap()) =
        State(null, readable, pendingEnvelopes.keys.toSet(), emptySet(), null)

    fun load(): State {
        pendingEnvelopes.clear()
        val readable = LinkedHashMap<String, RecordCipher>()
        val lost = LinkedHashSet<String>()
        var current: RecordCipher? = null
        var rotation: Rotation? = null

        val curBytes = files.readCurrent()
        if (curBytes != null) {
            val env = KeyEnvelope.decode(curBytes)
            if (env == null) {
                rotation = Rotation.INVALID
            } else {
                val has = try { wrapper.hasMaster(env.alias) } catch (e: Exception) { return deferred() }
                if (!has) {
                    rotation = Rotation.LOST
                } else when (val a = unwrap(env, 2)) {
                    is Attempt.Ok -> current = cipher(env.id, a.raw).also { readable[env.id] = it }
                    Attempt.Invalid -> rotation = Rotation.INVALID
                    Attempt.Transient -> return deferred()
                }
            }
        }

        // 保留的旧密钥：主密钥不存在 = 不可恢复；能解开 = 可读；其余稍后再试
        for ((_, bytes) in files.retired()) {
            val env = KeyEnvelope.decode(bytes) ?: continue
            if (env.id in readable || env.id in lost || env.id in pendingEnvelopes) continue
            when (runCatching { wrapper.hasMaster(env.alias) }.getOrNull()) {
                false -> lost += env.id
                true -> when (val a = unwrap(env, 1)) {
                    is Attempt.Ok -> readable[env.id] = cipher(env.id, a.raw)
                    else -> pendingEnvelopes[env.id] = env
                }
                null -> pendingEnvelopes[env.id] = env
            }
        }

        if (current == null) {
            val curEnv = curBytes?.let(KeyEnvelope::decode)
            // 先把无法使用的当前密钥改名保留；保留失败时不生成新密钥，以免覆盖
            if (curBytes != null) {
                files.retireCurrent() ?: return deferred(readable)
                curEnv?.let { if (rotation == Rotation.LOST) lost += it.id else pendingEnvelopes[it.id] = it }
            } else {
                rotation = if (files.retired().isEmpty()) Rotation.CREATED else Rotation.MISSING
            }
            val known = readable.keys + lost + pendingEnvelopes.keys
            var id: String
            do { id = newId() } while (id in known)
            val raw = ByteArray(RecordFormat.KEY_BYTES).also(random::nextBytes)
            val alias = KeyEnvelope.aliasFor(id)
            val wrapped = runCatching { wrapper.wrap(alias, raw) }.getOrNull() ?: return deferred(readable)
            if (!files.writeCurrent(KeyEnvelope(id, alias, wrapped).encode())) return deferred(readable)
            current = cipher(id, raw).also { readable[id] = it }
        }
        return State(current, readable, pendingEnvelopes.keys.toSet(), lost, rotation)
    }

    /**
     * 再试一次解开暂未解开的旧密钥；返回本次新解开的密钥。主密钥此时已确实不存在的，归入 [lostNow]。
     */
    fun retryPending(lostNow: MutableSet<String> = mutableSetOf()): Map<String, RecordCipher> {
        val out = LinkedHashMap<String, RecordCipher>()
        val it = pendingEnvelopes.entries.iterator()
        while (it.hasNext()) {
            val (id, env) = it.next()
            when (runCatching { wrapper.hasMaster(env.alias) }.getOrNull()) {
                false -> { lostNow += id; it.remove() }
                true -> (unwrap(env, 1) as? Attempt.Ok)?.let { a -> out[id] = cipher(id, a.raw); it.remove() }
                null -> Unit
            }
        }
        return out
    }

    private fun newId(): String {
        val b = ByteArray(4).also(random::nextBytes)
        return b.joinToString("") { "%02x".format(it) }
    }
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
        var n = 0
        rewrite(f) { l ->
            if (RecordFormat.isEncrypted(l)) l else (encrypt(l) ?: throw IOException("encryption unavailable")).also { n++ }
        }
        return n
    }

    /**
     * 逐行改写文件：[map] 返回 null 表示删掉该行；先写临时文件并同步落盘，再整体替换。
     * 改写后没有任何行时删除文件。失败时原文件不变并抛出 [IOException]。
     */
    fun rewrite(f: File, map: (String) -> String?) {
        val tmp = File(f.parentFile, f.name + TMP_SUFFIX)
        var kept = 0
        try {
            FileOutputStream(tmp).use { out ->
                val w = out.bufferedWriter(Charsets.UTF_8)
                f.useLines { lines ->
                    for (l in lines) {
                        if (l.isEmpty()) continue
                        val e = map(l) ?: continue
                        w.write(e); w.write("\n"); kept++
                    }
                }
                w.flush(); out.fd.sync()
            }
            if (kept == 0) {
                tmp.delete()
                if (!f.delete()) throw IOException("delete failed: ${f.name}")
            } else if (!tmp.renameTo(f)) throw IOException("rename failed: ${f.name}")
        } catch (e: Exception) {
            tmp.delete()
            throw e as? IOException ?: IOException(e)
        }
    }

    /** 删除上次中断留下的临时文件 */
    fun cleanup(dir: File) {
        dir.listFiles { x -> x.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
    }
}

/**
 * 无法读取的旧记录的统计与清理（纯逻辑）。
 * 密钥标识分三类：可读、待重试（旧密钥保留着、主密钥仍在）、不可恢复（主密钥已被系统清除，或已没有任何密钥文件）。
 */
object LockedRecords {
    /** 某个密钥标识是否不可恢复 */
    fun isLost(id: String, readable: Set<String>, pending: Set<String>, lost: Set<String>) =
        id in lost || (id !in readable && id !in pending)

    /** 统计结果：[lostDays]、[pendingDays] 为含有对应记录的日期（文件名前 10 位） */
    data class Summary(val lostDays: Set<String>, val pendingDays: Set<String>, val lostFiles: List<String>)

    /** [ids] 为「文件名 → 该文件出现的密钥标识」 */
    fun summarize(ids: Map<String, Set<String>>, readable: Set<String>, pending: Set<String>, lost: Set<String>): Summary {
        val lostDays = sortedSetOf<String>(); val pendingDays = sortedSetOf<String>(); val lostFiles = mutableListOf<String>()
        ids.forEach { (name, s) ->
            if (s.any { isLost(it, readable, pending, lost) }) { lostDays += name.take(10); lostFiles += name }
            if (s.any { it in pending && it !in lost }) pendingDays += name.take(10)
        }
        return Summary(lostDays, pendingDays, lostFiles.sorted())
    }
}
