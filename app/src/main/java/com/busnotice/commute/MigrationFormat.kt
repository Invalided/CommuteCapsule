package com.busnotice.commute

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 迁移文件（.ccmig）的格式、加密与内容编解码。与 Android 无关，可在 JVM 上单测。
 *
 * 格式版本 2（当前写入）：分块加密，读写均为流式，内存占用与内容总量无关。
 * ```
 * 文件头：魔数 "CCMIG"(5) | 格式版本 2(1) | 密钥派生算法(1) | 盐长度(1) | 盐(16) | 随机数前缀(8) | 分块明文长度(4)
 * 之后依次为各块：是否末块(1) | 密文长度(4) | AES-256-GCM 密文与标签
 * ```
 * 每块独立加密：IV 为随机数前缀与块序号（4 字节）；附加认证数据为完整文件头、块序号（8 字节）与是否末块标志。
 * 除末块外每块明文长度均等于分块明文长度；必须以末块结束且末块之后不得再有数据。
 * 因此块被改动、删除、调换顺序，文件被截断或在末尾追加内容，都会导致校验失败。
 *
 * 格式版本 1（只读兼容）：
 * ```
 * 魔数 "CCMIG"(5) | 格式版本 1(1) | 密钥派生算法(1) | 盐长度(1) | 盐(16) | IV(12) | AES-256-GCM(zip 内容) | 标签(16)
 * ```
 * 文件头（魔数到 IV）作为附加认证数据，整体一次加密，读取时须整体载入内存。
 *
 * 两个版本加密的都是同一种 zip 内容（见 [MigrationPackage]）。密钥由恢复码经 HKDF-SHA256 派生：
 * 恢复码为 24 位 Crockford Base32（120 位随机数），本身已是高熵密钥材料，不需要 PBKDF2 一类的慢速口令派生。
 */
object MigrationCrypto {
    /** 当前写入的格式版本 */
    const val VERSION = 2
    /** 整体一次加密的旧格式版本 */
    const val VERSION_SINGLE = 1
    const val KDF_HKDF_SHA256 = 1
    const val EXTENSION = "ccmig"
    /** 格式版本 1 的文件大小上限（须整体载入内存），超过时不读取 */
    const val MAX_BYTES = 256L * 1024 * 1024
    /** 格式版本 2 的文件大小上限（流式读取，只受存储空间限制） */
    const val MAX_STREAM_BYTES = 4L * 1024 * 1024 * 1024
    /** 写入时的分块明文长度 */
    const val CHUNK_SIZE = 64 * 1024
    /** 读取时接受的分块明文长度范围 */
    private const val MIN_CHUNK = 1024
    private const val MAX_CHUNK = 1024 * 1024
    /** [checkHeader] 判断所需的文件开头字节数 */
    const val PREFIX_LEN = 64

    private val MAGIC = "CCMIG".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val NONCE_PREFIX_LEN = 8
    private const val TAG_LEN = 16
    private const val TAG_BITS = TAG_LEN * 8
    private val INFO_V1 = "CommuteCapsule migration v1".toByteArray(Charsets.US_ASCII)
    private val INFO_V2 = "CommuteCapsule migration v2".toByteArray(Charsets.US_ASCII)
    private const val COMMON_LEN = 5 + 1 + 1 + 1
    private const val HEADER_V1_LEN = COMMON_LEN + SALT_LEN + IV_LEN
    private const val HEADER_V2_LEN = COMMON_LEN + SALT_LEN + NONCE_PREFIX_LEN + 4
    /** 块头：是否末块(1) | 密文长度(4) */
    private const val FRAME_HEAD_LEN = 5

    /** 把 [plain] 整体加密为当前格式（内容较小时使用；导出走 [sealStream]） */
    fun seal(plain: ByteArray, code: String, random: SecureRandom = SecureRandom()): ByteArray {
        val bos = ByteArrayOutputStream()
        sealStream(bos, code, random).use { it.write(plain) }
        return bos.toByteArray()
    }

    /**
     * 返回分块加密输出流：写入的明文按 [chunkSize] 分块加密后写到 [out]。文件头立即写出；
     * [SealingOutputStream.close] 写出末块并刷新 [out]，但不关闭 [out]（便于调用方同步落盘后再关闭）。
     */
    fun sealStream(out: OutputStream, code: String, random: SecureRandom = SecureRandom(), chunkSize: Int = CHUNK_SIZE): SealingOutputStream {
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "invalid chunk size" }
        val norm = RecoveryCode.normalize(code) ?: throw IllegalArgumentException("invalid recovery code")
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_LEN).also(random::nextBytes)
        val header = MAGIC + byteArrayOf(VERSION.toByte(), KDF_HKDF_SHA256.toByte(), SALT_LEN.toByte()) + salt + prefix + int32(chunkSize)
        out.write(header)
        return SealingOutputStream(out, SecretKeySpec(deriveKey(norm, salt, INFO_V2), "AES"), header, prefix, chunkSize)
    }

    /**
     * 只看文件开头（至少 [PREFIX_LEN] 字节，文件更短时为整个文件）：是否为迁移文件、格式版本是否受支持。
     * 不需要恢复码。返回格式版本。
     */
    fun checkHeader(data: ByteArray): Int {
        val ver = checkCommon(data)
        when (ver) {
            VERSION_SINGLE -> if (data.size < HEADER_V1_LEN + TAG_LEN) corrupt()
            else -> {
                if (data.size < HEADER_V2_LEN + FRAME_HEAD_LEN + TAG_LEN) corrupt()
                if (readInt32(data, HEADER_V2_LEN - 4) !in MIN_CHUNK..MAX_CHUNK) corrupt()
            }
        }
        return ver
    }

    /** 魔数、格式版本、密钥派生算法与盐长度（文件头前 8 字节） */
    private fun checkCommon(data: ByteArray): Int {
        if (data.size < MAGIC.size || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
            throw MigrationException(MigrationException.Reason.NOT_MIGRATION_FILE)
        if (data.size < MAGIC.size + 2) corrupt()
        val ver = data[MAGIC.size].toInt() and 0xff
        val kdf = data[MAGIC.size + 1].toInt() and 0xff
        if (ver > VERSION || kdf != KDF_HKDF_SHA256) throw MigrationException(MigrationException.Reason.NEWER_VERSION)
        if (ver < 1 || data.size < COMMON_LEN || (data[MAGIC.size + 2].toInt() and 0xff) != SALT_LEN) corrupt()
        return ver
    }

    /** 对应格式版本的文件大小上限 */
    fun maxBytes(version: Int): Long = if (version == VERSION_SINGLE) MAX_BYTES else MAX_STREAM_BYTES

    /** 解密整个迁移文件，返回 zip 内容；恢复码不对或文件被改动时抛出 [MigrationException] */
    fun open(data: ByteArray, code: String): ByteArray = try {
        openStream(ByteArrayInputStream(data), code).use { it.readBytes() }
    } catch (e: MigrationException) {
        throw e
    } catch (e: Exception) {
        corrupt()
    }

    /**
     * 返回解密后的 zip 内容流；关闭它会关闭 [input]。
     * 格式版本 2 逐块读取：每块通过认证后才交出其明文，读到末块时确认其后没有多余数据；
     * 截断、篡改、调换块顺序或恢复码不对时，读取过程中抛出 [MigrationException]。
     * 格式版本 1 须先整体认证，因此整体读入内存（受 [MAX_BYTES] 限制）。
     */
    fun openStream(input: InputStream, code: String): InputStream {
        val common = readUpTo(input, COMMON_LEN)
        val ver = checkCommon(common)
        val norm = RecoveryCode.normalize(code) ?: corrupt()
        return if (ver == VERSION_SINGLE) {
            val rest = readLimited(input, MAX_BYTES - COMMON_LEN)
            input.close()
            ByteArrayInputStream(openSingle(common + rest, norm))
        } else {
            val header = common + readUpTo(input, HEADER_V2_LEN - COMMON_LEN)
            if (header.size < HEADER_V2_LEN) corrupt()
            val chunkSize = readInt32(header, HEADER_V2_LEN - 4)
            if (chunkSize !in MIN_CHUNK..MAX_CHUNK) corrupt()
            val salt = header.copyOfRange(COMMON_LEN, COMMON_LEN + SALT_LEN)
            val prefix = header.copyOfRange(COMMON_LEN + SALT_LEN, COMMON_LEN + SALT_LEN + NONCE_PREFIX_LEN)
            OpeningInputStream(input, SecretKeySpec(deriveKey(norm, salt, INFO_V2), "AES"), header, prefix, chunkSize)
        }
    }

    private fun openSingle(data: ByteArray, norm: String): ByteArray {
        checkHeader(data)
        val p = COMMON_LEN
        val salt = data.copyOfRange(p, p + SALT_LEN)
        val iv = data.copyOfRange(p + SALT_LEN, p + SALT_LEN + IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(norm, salt, INFO_V1), "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(data, 0, HEADER_V1_LEN)
        return try {
            c.doFinal(data, HEADER_V1_LEN, data.size - HEADER_V1_LEN)
        } catch (e: AEADBadTagException) {
            corrupt()
        } catch (e: Exception) {
            corrupt()
        }
    }

    /** 分块加密输出流，见 [sealStream] */
    class SealingOutputStream internal constructor(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
        /** 分块明文长度，也是本流持有的明文缓冲区大小 */
        val chunkSize: Int,
    ) : OutputStream() {
        private val buf = ByteArray(chunkSize)
        private var n = 0
        private var index = 0L
        private var closed = false
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        /** 已写出的块数 */
        var chunks = 0L
            private set
        /** 已写入的明文字节数 */
        var plainBytes = 0L
            private set
        /** 单次写到底层输出流的最大字节数 */
        var maxWrite = 0
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            var o = off
            var r = len
            while (r > 0) {
                // 缓冲区满且还有后续数据时才写出，使最后一块总能标记为末块
                if (n == chunkSize) emit(false)
                val k = minOf(r, chunkSize - n)
                System.arraycopy(b, o, buf, n, k)
                n += k; o += k; r -= k
                plainBytes += k
            }
        }

        override fun flush() = out.flush()

        /** 写出末块并刷新底层输出流；不关闭底层输出流 */
        override fun close() {
            if (closed) return
            emit(true)
            closed = true
            out.flush()
        }

        private fun emit(last: Boolean) {
            if (index > 0xFFFF_FFFFL) throw java.io.IOException("too many chunks")
            val flag: Byte = if (last) 1 else 0
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, prefix + int32(index.toInt())))
            cipher.updateAAD(header)
            cipher.updateAAD(int64(index))
            cipher.updateAAD(byteArrayOf(flag))
            val ct = cipher.doFinal(buf, 0, n)
            out.write(byteArrayOf(flag) + int32(ct.size))
            out.write(ct)
            maxWrite = maxOf(maxWrite, ct.size)
            index++; chunks++; n = 0
        }
    }

    /** 分块解密输入流，见 [openStream] */
    private class OpeningInputStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        private val ct = ByteArray(chunkSize + TAG_LEN)
        private val plain = ByteArray(chunkSize + TAG_LEN)
        private var pos = 0
        private var len = 0
        private var index = 0L
        private var finished = false
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, n: Int): Int {
            if (n == 0) return 0
            while (pos == len) {
                if (finished) return -1
                next()
            }
            val k = minOf(n, len - pos)
            System.arraycopy(plain, pos, b, off, k)
            pos += k
            return k
        }

        override fun available() = len - pos

        override fun close() = input.close()

        private fun next() {
            val head = try { readExact(input, FRAME_HEAD_LEN) } catch (e: EOFException) { corrupt() }
            val flag = head[0].toInt()
            val size = readInt32(head, 1)
            if (flag !in 0..1 || size < TAG_LEN || size > chunkSize + TAG_LEN || index > 0xFFFF_FFFFL) corrupt()
            try { readExact(input, size, ct) } catch (e: EOFException) { corrupt() }
            val out = try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, prefix + int32(index.toInt())))
                cipher.updateAAD(header)
                cipher.updateAAD(int64(index))
                cipher.updateAAD(byteArrayOf(flag.toByte()))
                cipher.doFinal(ct, 0, size, plain, 0)
            } catch (e: Exception) {
                corrupt()
            }
            if (flag == 0 && out != chunkSize) corrupt()
            index++
            pos = 0; len = out
            if (flag == 1) {
                finished = true
                if (input.read() != -1) corrupt()
            }
        }
    }

    private fun deriveKey(normCode: String, salt: ByteArray, info: ByteArray) = hkdf(normCode.toByteArray(Charsets.US_ASCII), salt, info, 32)

    /** HKDF-SHA256（RFC 5869） */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < len) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal(); out.write(t); i++
        }
        return out.toByteArray().copyOf(len)
    }

    private fun corrupt(): Nothing = throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun int64(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    private fun readInt32(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    /** 读满 [n] 字节；不足时抛出 [EOFException] */
    private fun readExact(input: InputStream, n: Int, into: ByteArray = ByteArray(n)): ByteArray {
        var got = 0
        while (got < n) {
            val k = input.read(into, got, n - got)
            if (k < 0) throw EOFException()
            got += k
        }
        return into
    }

    /** 最多读 [n] 字节；输入提前结束时返回已读到的部分 */
    private fun readUpTo(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val k = input.read(b, got, n - got)
            if (k < 0) break
            got += k
        }
        return if (got == n) b else b.copyOf(got)
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val k = input.read(buf); if (k < 0) break
            total += k
            if (total > limit) throw MigrationException(MigrationException.Reason.TOO_LARGE)
            bos.write(buf, 0, k)
        }
        return bos.toByteArray()
    }
}

class MigrationException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** 不是迁移文件 */
        NOT_MIGRATION_FILE,
        /** 由更新版本的 App 生成 */
        NEWER_VERSION,
        /** 恢复码不对或文件已损坏 */
        WRONG_CODE_OR_CORRUPT,
        /** 文件过大 */
        TOO_LARGE,
    }

    val userMessage: String
        get() = when (reason) {
            Reason.NOT_MIGRATION_FILE -> "这不是通勤胶囊的迁移文件"
            Reason.NEWER_VERSION -> "迁移文件来自更新版本的通勤胶囊，请先升级 App 再导入"
            Reason.WRONG_CODE_OR_CORRUPT -> "恢复码不对或文件已损坏"
            Reason.TOO_LARGE -> "迁移文件过大，无法导入"
        }
}

/**
 * 恢复码：24 位 Crockford Base32（不含 I、L、O、U），120 位随机数，按 4 位一组显示。
 * 输入时忽略大小写、空格和连字符，并把容易混淆的 O、I、L 当作 0、1、1。
 */
object RecoveryCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 24
    /** 二维码内容前缀，用于区分其他二维码 */
    const val QR_PREFIX = "ccmig:"

    fun generate(random: SecureRandom = SecureRandom()): String {
        val b = ByteArray(LENGTH * 5 / 8).also(random::nextBytes)   // 15 字节 = 120 位
        val sb = StringBuilder()
        var acc = 0; var bits = 0
        for (x in b) {
            acc = (acc shl 8) or (x.toInt() and 0xff); bits += 8
            while (bits >= 5) { bits -= 5; sb.append(ALPHABET[(acc shr bits) and 31]) }
        }
        return sb.toString()
    }

    /** 规范化输入；长度或字符不对时返回 null。也接受二维码内容（带前缀） */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.startsWith(QR_PREFIX, ignoreCase = true)) s = s.substring(QR_PREFIX.length)
        val out = StringBuilder()
        for (ch in s.uppercase()) {
            when (ch) {
                ' ', '-', '\n', '\t' -> continue
                'O' -> out.append('0')
                'I', 'L' -> out.append('1')
                else -> if (ch in ALPHABET) out.append(ch) else return null
            }
        }
        return out.toString().takeIf { it.length == LENGTH }
    }

    /** 分组显示：XXXX-XXXX-XXXX-XXXX-XXXX-XXXX */
    fun format(code: String) = code.chunked(4).joinToString("-")

    fun qrPayload(code: String) = QR_PREFIX + code

    /** 从二维码内容取出恢复码；不是恢复码二维码时返回 null */
    fun fromQr(text: String): String? =
        if (text.trim().startsWith(QR_PREFIX, ignoreCase = true)) normalize(text) else null
}

/** 迁移包的说明信息（预览用） */
data class PackageMeta(
    val format: Int,
    val createdAt: Long,
    val appVersion: String,
    val trips: Int,
    val includesRecords: Boolean,
    val traceDays: Int,
    /** 导出时因无法解密而未放入的记录条数 */
    val skippedRecords: Int,
    /** 本机快照（撤销用）：记录按落盘原样保存，不是明文 */
    val raw: Boolean = false,
)

/**
 * 迁移包内容。
 * - [prefs]：备份白名单中的 SharedPreferences（行程、设置、学习结果、家和公司快照、出门 Wi-Fi 等）；
 * - [traces]：行程记录文件名 → 各行（迁移包中为明文；本机快照中为落盘原样）；为 null 表示不含行程记录；
 * - [samplePrefs]：原始定位采样（place_samples）的键值（迁移包中 samples 为明文；本机快照中为落盘原样）；为 null 表示不含。
 */
data class MigrationContent(
    val meta: PackageMeta,
    val prefs: Map<String, Map<String, Any>>,
    val traces: Map<String, List<String>>?,
    val samplePrefs: Map<String, Any>?,
)

/**
 * 迁移包的 zip 编解码。条目依次为：`manifest.json`、`prefs/<名>.json`、`samples/place_samples.json`、
 * `traces/<文件名>`（每行一条记录）、`traces/.present`（含行程记录的标记）、`summary.json`（导出完成后的统计）。
 * 读写均逐条目进行，任何时候只持有一个条目的内容。
 */
object MigrationPackage {
    const val FORMAT = 1
    const val SAMPLE_PREFS = "place_samples"
    const val TRIPS_PREFS = "trips"

    /** 随迁移文件导出的 SharedPreferences，与系统备份白名单（res/xml/data_extraction_rules.xml）一致 */
    val PREFS: List<String> = listOf(
        "trips", "routes", "transit", "places", "learn", "learn_ride2", "learn_bus_speed", "learn_metro", "learn_route",
        "walk_learn", "leave_wifi", "keepalive", "onboarding", "backup",
    )

    /** 只对本机有意义、不随迁移文件导出的键（例如下一次定位采样的闹钟时刻） */
    val DEVICE_KEYS: Map<String, Set<String>> = mapOf("places" to setOf("nextSample"))

    private const val MANIFEST = "manifest.json"
    private const val SUMMARY = "summary.json"
    private const val TRACES_DIR = "traces/"
    private const val TRACES_PRESENT = "traces/.present"

    /** 去掉只对本机有意义的键与空值 */
    fun portable(name: String, entries: Map<String, Any?>): Map<String, Any> =
        entries.filter { (k, v) -> v != null && k !in DEVICE_KEYS[name].orEmpty() }.mapValues { it.value!! }

    /** 允许的记录文件名：不含路径分隔符 */
    private val TRACE_NAME = Regex("""^[0-9A-Za-z_.\-]{1,96}\.jsonl$""")

    fun validTraceName(n: String) = TRACE_NAME.matches(n) && !n.startsWith(".")

    fun tripCount(prefs: Map<String, Map<String, Any>>): Int =
        (prefs[TRIPS_PREFS]?.get("list") as? String)?.let { runCatching { JSONArray(it).length() }.getOrNull() } ?: 0

    fun traceDays(names: Collection<String>) = names.map { it.take(10) }.distinct().size

    /**
     * 逐条目写出迁移包。调用顺序：[manifest]、[prefs]、[samples]、[trace]、[tracesPresent]、[summary]，最后 [finish]。
     */
    class Writer(out: OutputStream) {
        private val zip = ZipOutputStream(object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = flush()
        })
        private val sink = BufferedOutputStream(zip, 16 * 1024)

        /** 单个记录条目的最大行数（写入时只持有一个文件的记录） */
        var maxTraceLines = 0
            private set

        private fun put(name: String, b: ByteArray) {
            zip.putNextEntry(ZipEntry(name)); zip.write(b); zip.closeEntry()
        }

        fun manifest(m: PackageMeta) = put(MANIFEST, metaJson(m).toString(2).toByteArray())

        fun prefs(name: String, entries: Map<String, Any>) {
            if (name in PREFS) put("prefs/$name.json", encodePrefs(entries).toString().toByteArray())
        }

        fun samples(entries: Map<String, Any>) = put("samples/$SAMPLE_PREFS.json", encodePrefs(entries).toString().toByteArray())

        fun trace(name: String, lines: List<String>) {
            require(validTraceName(name)) { "invalid trace name" }
            zip.putNextEntry(ZipEntry(TRACES_DIR + name))
            for (l in lines) { sink.write(l.toByteArray(Charsets.UTF_8)); sink.write('\n'.code) }
            sink.flush()
            zip.closeEntry()
            maxTraceLines = maxOf(maxTraceLines, lines.size)
        }

        fun tracesPresent() = put(TRACES_PRESENT, ByteArray(0))

        fun summary(m: PackageMeta) = put(SUMMARY, JSONObject()
            .put("traceDays", m.traceDays).put("skippedRecords", m.skippedRecords).toString().toByteArray())

        /** 写出 zip 目录、释放压缩器并刷新；不关闭底层输出流 */
        fun finish() = zip.close()
    }

    /** 在内存中生成完整迁移包（内容较小时使用） */
    fun write(c: MigrationContent): ByteArray {
        val bos = ByteArrayOutputStream()
        Writer(bos).apply {
            manifest(c.meta)
            c.prefs.toSortedMap().forEach { (n, e) -> prefs(n, e) }
            c.samplePrefs?.let { samples(it) }
            c.traces?.toSortedMap()?.forEach { (n, lines) -> trace(n, lines) }
            if (c.traces != null) tracesPresent()
            finish()
        }
        return bos.toByteArray()
    }

    /**
     * 逐条目读取的结果：清单、设置与原始定位采样；[traceNames] 为读到的记录文件名，为 null 表示不含行程记录。
     * 只读开头（[scan] 的 headOnly）时 [traceNames] 恒为 null，是否含行程记录以清单为准。
     */
    class Scan(
        val meta: PackageMeta,
        val prefs: Map<String, Map<String, Any>>,
        val samplePrefs: Map<String, Any>?,
        val traceNames: List<String>?,
    )

    /** [scan] 的回调用来中止读取并带出原因；不会被改写为文件损坏 */
    open class Abort : RuntimeException()

    /**
     * 逐条目读取 [input]（明文 zip）。每个记录文件读完即交给 [onTrace]，不在内存中累积。
     * [headOnly] 为 true 时读到第一个记录条目即停止（预览用）；否则读到 zip 末尾后再把 [input] 读完，
     * 使分块解密流确认末块存在（防截断）。不在白名单中的条目和不合规的文件名一律忽略。
     */
    fun scan(input: InputStream, headOnly: Boolean = false, onTrace: (String, List<String>) -> Unit = { _, _ -> }): Scan {
        var meta: PackageMeta? = null
        val prefs = LinkedHashMap<String, Map<String, Any>>()
        var traces: LinkedHashSet<String>? = null
        var samples: Map<String, Any>? = null
        var summary: JSONObject? = null
        val z = ZipInputStream(input)
        try {
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (headOnly && (name.startsWith(TRACES_DIR) || name == SUMMARY)) break
                val bytes = z.readBytes()
                when {
                    name == MANIFEST -> meta = parseMeta(JSONObject(String(bytes, Charsets.UTF_8)))
                    name.startsWith("prefs/") && name.endsWith(".json") -> {
                        val p = name.removePrefix("prefs/").removeSuffix(".json")
                        if (p in PREFS) prefs[p] = decodePrefs(JSONObject(String(bytes, Charsets.UTF_8)))
                    }
                    name == "samples/$SAMPLE_PREFS.json" -> samples = decodePrefs(JSONObject(String(bytes, Charsets.UTF_8)))
                    name == SUMMARY -> summary = JSONObject(String(bytes, Charsets.UTF_8))
                    name == TRACES_PRESENT -> if (traces == null) traces = LinkedHashSet()
                    name.startsWith(TRACES_DIR) -> {
                        val n = name.removePrefix(TRACES_DIR)
                        if (validTraceName(n)) {
                            if (meta == null) throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
                            val set = traces ?: LinkedHashSet<String>().also { traces = it }
                            onTrace(n, String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() })
                            set += n
                        }
                    }
                }
            }
            if (!headOnly) {
                val buf = ByteArray(8 * 1024)
                while (input.read(buf) >= 0) Unit
            }
        } catch (e: MigrationException) {
            throw e
        } catch (e: Abort) {
            throw e
        } catch (e: Exception) {
            throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
        } finally {
            runCatching { z.close() }
        }
        var m = meta ?: throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
        if (m.format > FORMAT) throw MigrationException(MigrationException.Reason.NEWER_VERSION)
        summary?.let { s ->
            m = m.copy(traceDays = s.optInt("traceDays", m.traceDays), skippedRecords = s.optInt("skippedRecords", m.skippedRecords))
        }
        return Scan(m, prefs, samples, traces?.toList())
    }

    /** 解析内存中的完整迁移包（内容较小时使用） */
    fun read(zip: ByteArray): MigrationContent {
        val traces = LinkedHashMap<String, List<String>>()
        val s = scan(ByteArrayInputStream(zip)) { n, lines -> traces[n] = lines }
        return MigrationContent(s.meta, s.prefs, if (s.traceNames == null) null else traces, s.samplePrefs)
    }

    private fun metaJson(m: PackageMeta) = JSONObject()
        .put("format", m.format).put("createdAt", m.createdAt).put("appVersion", m.appVersion)
        .put("trips", m.trips).put("includesRecords", m.includesRecords).put("traceDays", m.traceDays)
        .put("skippedRecords", m.skippedRecords).put("raw", m.raw)

    private fun parseMeta(o: JSONObject): PackageMeta {
        val f = o.getInt("format")
        if (f > FORMAT) throw MigrationException(MigrationException.Reason.NEWER_VERSION)
        return PackageMeta(f, o.optLong("createdAt"), o.optString("appVersion"), o.optInt("trips"),
            o.optBoolean("includesRecords"), o.optInt("traceDays"), o.optInt("skippedRecords"), o.optBoolean("raw"))
    }

    /** SharedPreferences 键值 → JSON，保留类型（字符串、布尔、整数、长整数、浮点、字符串集合） */
    fun encodePrefs(entries: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        entries.toSortedMap().forEach { (k, v) ->
            val t = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v.toString())
                is Float -> JSONObject().put("t", "f").put("v", v.toString())
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>().sorted()))
                else -> null
            }
            if (t != null) o.put(k, t)
        }
        return o
    }

    fun decodePrefs(o: JSONObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        o.keys().forEach { k ->
            val e = o.optJSONObject(k) ?: return@forEach
            val v: Any? = when (e.optString("t")) {
                "s" -> e.getString("v")
                "b" -> e.getBoolean("v")
                "i" -> e.getInt("v")
                "l" -> e.getString("v").toLong()
                "f" -> e.getString("v").toFloat()
                "ss" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> null
            }
            if (v != null) out[k] = v
        }
        return out
    }
}

/**
 * 导出迁移文件：逐个记录文件读取、解密并写入加密输出流，同一时刻只持有一个记录文件的内容；
 * zip 压缩与分块加密同样按流进行，内存占用与记录总量无关。
 */
object MigrationExport {
    /**
     * 一个待导出的记录文件。[hasData] 为文件是否非空（用于在清单中预先统计天数）；
     * [read] 读取并解码该文件，返回明文各行与无法读取的条数。
     */
    class Trace(val name: String, val hasData: Boolean, val read: () -> Decoded)

    class Result(
        /** 导出完成后的准确说明信息（天数、未放入的记录数） */
        val meta: PackageMeta,
        /** 写出的加密块数 */
        val chunks: Long,
        /** 写入的 zip 明文字节数 */
        val plainBytes: Long,
        /** 加密流持有的明文缓冲区大小 */
        val bufferBytes: Int,
        /** 单次写到输出的最大字节数 */
        val maxWrite: Int,
        /** 同一时刻持有的最大记录行数（即单个记录文件的最大行数） */
        val maxTraceLines: Int,
    )

    /**
     * 把迁移包加密写到 [out]（写完后刷新，不关闭）。[traces] 为 null 时不含行程记录；[samples] 为原始定位采样，
     * 含行程记录时为非 null。清单中的天数按非空记录文件名预先统计，导出完成后的准确值写在包末的统计条目中并作为结果返回。
     */
    fun write(
        out: OutputStream, code: String, createdAt: Long, appVersion: String,
        prefs: Map<String, Map<String, Any>>, traces: List<Trace>?, samples: Map<String, Any>?,
        random: SecureRandom = SecureRandom(), chunkSize: Int = MigrationCrypto.CHUNK_SIZE,
    ): Result {
        val sorted = traces?.sortedBy { it.name }
        val estimate = sorted?.filter { it.hasData && MigrationPackage.validTraceName(it.name) }?.map { it.name }
        val head = PackageMeta(
            format = MigrationPackage.FORMAT, createdAt = createdAt, appVersion = appVersion,
            trips = MigrationPackage.tripCount(prefs), includesRecords = traces != null,
            traceDays = estimate?.let(MigrationPackage::traceDays) ?: 0, skippedRecords = 0,
        )
        val sealer = MigrationCrypto.sealStream(out, code, random, chunkSize)
        val w = MigrationPackage.Writer(sealer)
        w.manifest(head)
        prefs.toSortedMap().forEach { (n, e) -> w.prefs(n, e) }
        if (traces != null) w.samples(samples ?: emptyMap())
        var skipped = 0
        val written = ArrayList<String>()
        sorted?.forEach { t ->
            val d = t.read()
            skipped += d.unreadable
            if (MigrationPackage.validTraceName(t.name) && d.text.isNotEmpty()) {
                w.trace(t.name, d.text)
                written += t.name
            }
        }
        val meta = head.copy(traceDays = if (traces == null) 0 else MigrationPackage.traceDays(written), skippedRecords = skipped)
        if (traces != null) w.tracesPresent()
        w.summary(meta)
        w.finish()
        sealer.close()
        return Result(meta, sealer.chunks, sealer.plainBytes, sealer.chunkSize, sealer.maxWrite, w.maxTraceLines)
    }
}

/** 本机数据的读写（Android 实现见 AndroidLocalData，单测使用内存实现） */
interface LocalData {
    fun readPrefs(name: String): Map<String, Any?>
    /** 整体替换一个 SharedPreferences 文件的内容并同步提交；失败返回 false */
    fun writePrefs(name: String, entries: Map<String, Any?>): Boolean
    fun traceNames(): List<String>
    /** 记录文件按落盘原样的各行 */
    fun readTraceRaw(name: String): List<String>
    /** 原子写入记录文件 */
    fun writeTraceRaw(name: String, lines: List<String>): Boolean
    fun deleteTrace(name: String): Boolean
    /** 用本机当前密钥加密一条记录；密钥不可用时返回 null */
    fun encrypt(plain: String): String?
    /** 把一个记录文件（落盘格式）原子写入暂存区，暂不影响现有记录 */
    fun stageTrace(name: String, lines: List<String>): Boolean
    /** 用暂存区中的文件原子替换同名记录文件 */
    fun commitStaged(name: String): Boolean
    /** 清空暂存区 */
    fun clearStaged()
}

/** 迁移包的明文 zip 来源；每次调用打开一个新的流（迁移文件为解密流） */
fun interface PackageSource {
    fun open(): InputStream
}

/** 撤销快照的存放：[save] 把快照写到临时文件后原子替换，失败时不留半成品；[open] 读取已保存的快照 */
interface UndoStore {
    fun save(write: (OutputStream) -> Unit): Boolean
    fun open(): InputStream
}

/**
 * 导入（替换）与撤销。导入分三步：先完整读取并校验迁移包，把记录用本机密钥重新加密后写入暂存区；
 * 再把本机当前数据写成快照（记录按落盘原样，不解密）；最后替换设置并把暂存的记录换入。
 * 前两步失败时本机数据不做任何改动；替换失败时自动用快照回滚。成功后快照保留，供用户撤销一次。
 * 全程逐个记录文件处理，内存占用与记录总量无关。
 */
object Migrator {
    /**
     * 导入结果。[REJECTED]、[KEY_UNAVAILABLE]、[STAGING_FAILED]、[UNDO_SAVE_FAILED] 均发生在改动本机数据之前，本机数据未改动；
     * [ROLLED_BACK] 表示替换过程中失败，已用快照恢复导入前的数据。
     */
    enum class Outcome {
        OK,
        /** 迁移包不完整、被改动、恢复码不对、含密文记录或为本机快照 */
        REJECTED,
        KEY_UNAVAILABLE,
        /** 写入暂存区失败 */
        STAGING_FAILED,
        UNDO_SAVE_FAILED,
        /** 替换失败，已回滚 */
        ROLLED_BACK,
    }

    private class Stop(val outcome: Outcome) : MigrationPackage.Abort()

    /** 把本机快照写到 [out]；[withRecords] 为 true 时包含行程记录与原始定位采样 */
    fun writeSnapshot(local: LocalData, withRecords: Boolean, now: Long, out: OutputStream) {
        val prefs = MigrationPackage.PREFS.associateWith { n -> local.readPrefs(n).filterValues { it != null }.mapValues { it.value!! } }
        val names = if (withRecords) local.traceNames().filter(MigrationPackage::validTraceName).sorted() else null
        val samples = if (withRecords) local.readPrefs(MigrationPackage.SAMPLE_PREFS).filterValues { it != null }.mapValues { it.value!! } else null
        val meta = PackageMeta(MigrationPackage.FORMAT, now, "", MigrationPackage.tripCount(prefs), withRecords,
            names?.let(MigrationPackage::traceDays) ?: 0, 0, raw = true)
        val w = MigrationPackage.Writer(out)
        w.manifest(meta)
        prefs.toSortedMap().forEach { (n, e) -> w.prefs(n, e) }
        samples?.let { w.samples(it) }
        names?.forEach { w.trace(it, local.readTraceRaw(it)) }
        if (names != null) w.tracesPresent()
        w.finish()
    }

    /**
     * 用迁移包替换本机数据：白名单中的设置与学习结果全部替换（迁移包中没有的文件清空）；
     * 迁移包含行程记录时，本机行程记录与原始定位采样一并替换，并用本机密钥重新加密；不含时本机记录保持不动。
     * 迁移包不完整、被改动、含密文记录或密钥不可用时不做任何改动；快照保存失败时同样不做任何改动。
     */
    fun importReplace(source: PackageSource, local: LocalData, now: Long, undo: UndoStore): Outcome {
        local.clearStaged()
        val staged = LinkedHashSet<String>()
        val scan = try {
            source.open().use { input ->
                MigrationPackage.scan(input) { name, lines ->
                    val enc = ArrayList<String>(lines.size)
                    for (l in lines) {
                        if (RecordFormat.isEncrypted(l)) throw Stop(Outcome.REJECTED)
                        enc += local.encrypt(l) ?: throw Stop(Outcome.KEY_UNAVAILABLE)
                    }
                    if (!local.stageTrace(name, enc)) throw Stop(Outcome.STAGING_FAILED)
                    staged += name
                }
            }
        } catch (e: Stop) {
            local.clearStaged(); return e.outcome
        } catch (e: Exception) {
            local.clearStaged(); return Outcome.REJECTED
        }
        if (scan.meta.raw) { local.clearStaged(); return Outcome.REJECTED }
        val withRecords = scan.traceNames != null || scan.samplePrefs != null
        val encSamples: Map<String, Any>? = if (!withRecords) null else {
            val s = scan.samplePrefs?.get("samples") as? String
            if (s == null) emptyMap() else mapOf("samples" to (local.encrypt(s) ?: run { local.clearStaged(); return Outcome.KEY_UNAVAILABLE }))
        }
        if (!undo.save { writeSnapshot(local, withRecords, now, it) }) { local.clearStaged(); return Outcome.UNDO_SAVE_FAILED }
        val ok = apply(scan.prefs, if (withRecords) staged else null, encSamples, local)
        local.clearStaged()
        if (ok) return Outcome.OK
        restore(PackageSource { undo.open() }, local)
        return Outcome.ROLLED_BACK
    }

    /** 用撤销快照恢复导入前的数据；快照读取不完整时不做任何改动 */
    fun restore(snapshot: PackageSource, local: LocalData): Boolean {
        local.clearStaged()
        val staged = LinkedHashSet<String>()
        val scan = try {
            snapshot.open().use { input ->
                MigrationPackage.scan(input) { name, lines ->
                    if (!local.stageTrace(name, lines)) throw MigrationPackage.Abort()
                    staged += name
                }
            }
        } catch (e: Exception) {
            local.clearStaged(); return false
        }
        val ok = apply(scan.prefs, if (scan.traceNames != null) staged else null, scan.samplePrefs, local)
        local.clearStaged()
        return ok
    }

    /** 写入：设置与学习结果整体替换；[traces] / [samples] 为 null 时不动本机记录 */
    private fun apply(prefs: Map<String, Map<String, Any>>, traces: Set<String>?, samples: Map<String, Any>?, local: LocalData): Boolean {
        var ok = true
        MigrationPackage.PREFS.forEach { n -> ok = local.writePrefs(n, prefs[n].orEmpty()) && ok }
        traces?.let { t ->
            local.traceNames().filter { it !in t }.forEach { ok = local.deleteTrace(it) && ok }
            t.forEach { ok = local.commitStaged(it) && ok }
        }
        samples?.let { ok = local.writePrefs(MigrationPackage.SAMPLE_PREFS, it) && ok }
        return ok
    }

    /** 迁移包内容摘要中用的数字，便于预览：行程数与记录天数 */
    fun preview(c: MigrationContent): PackageMeta = c.meta.copy(
        trips = MigrationPackage.tripCount(c.prefs),
        includesRecords = c.traces != null,
        traceDays = c.traces?.keys?.let(MigrationPackage::traceDays) ?: 0,
    )

    /** 只读了迁移包开头时的预览：行程数按设置统计，是否含行程记录与天数以清单为准 */
    fun preview(s: MigrationPackage.Scan): PackageMeta = s.meta.copy(
        trips = MigrationPackage.tripCount(s.prefs),
        traceDays = if (s.meta.includesRecords) s.meta.traceDays else 0,
    )
}
