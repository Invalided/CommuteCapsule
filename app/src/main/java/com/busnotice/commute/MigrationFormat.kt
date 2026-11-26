package com.busnotice.commute

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
 * 文件结构：
 * ```
 * 魔数 "CCMIG"(5) | 格式版本(1) | 密钥派生算法(1) | 盐长度(1) | 盐(16) | IV(12) | AES-256-GCM(zip 内容) | 标签(16)
 * ```
 * 文件头（魔数到 IV）作为 GCM 附加认证数据，任何改动都会导致校验失败。
 * 密钥由恢复码经 HKDF-SHA256 派生：恢复码为 24 位 Crockford Base32（120 位随机数），本身已是高熵密钥材料，
 * 不需要 PBKDF2 一类的慢速口令派生。
 */
object MigrationCrypto {
    const val VERSION = 1
    const val KDF_HKDF_SHA256 = 1
    const val EXTENSION = "ccmig"
    /** 迁移文件大小上限，超过时不读取 */
    const val MAX_BYTES = 256L * 1024 * 1024
    private val MAGIC = "CCMIG".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val INFO = "CommuteCapsule migration v1".toByteArray(Charsets.US_ASCII)
    private const val HEADER_LEN = 5 + 1 + 1 + 1 + SALT_LEN + IV_LEN

    fun seal(plain: ByteArray, code: String, random: SecureRandom = SecureRandom()): ByteArray {
        val norm = RecoveryCode.normalize(code) ?: throw IllegalArgumentException("invalid recovery code")
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val header = MAGIC + byteArrayOf(VERSION.toByte(), KDF_HKDF_SHA256.toByte(), SALT_LEN.toByte()) + salt + iv
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(norm, salt), "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(header)
        return header + c.doFinal(plain)
    }

    /** 只看文件头：是否为迁移文件、格式版本是否受支持（不需要恢复码） */
    fun checkHeader(data: ByteArray) {
        if (data.size < MAGIC.size || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
            throw MigrationException(MigrationException.Reason.NOT_MIGRATION_FILE)
        if (data.size < MAGIC.size + 2) throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
        val ver = data[MAGIC.size].toInt() and 0xff
        val kdf = data[MAGIC.size + 1].toInt() and 0xff
        if (ver > VERSION || kdf != KDF_HKDF_SHA256) throw MigrationException(MigrationException.Reason.NEWER_VERSION)
        if (ver < 1) throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
        if (data.size < HEADER_LEN + TAG_BITS / 8 || (data[MAGIC.size + 2].toInt() and 0xff) != SALT_LEN)
            throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
    }

    /** 解密迁移文件，返回 zip 内容；恢复码不对或文件被改动时抛出 [MigrationException] */
    fun open(data: ByteArray, code: String): ByteArray {
        checkHeader(data)
        val norm = RecoveryCode.normalize(code) ?: throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
        val p = MAGIC.size + 3
        val salt = data.copyOfRange(p, p + SALT_LEN)
        val iv = data.copyOfRange(p + SALT_LEN, p + SALT_LEN + IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(norm, salt), "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(data, 0, HEADER_LEN)
        return try {
            c.doFinal(data, HEADER_LEN, data.size - HEADER_LEN)
        } catch (e: AEADBadTagException) {
            throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
        } catch (e: Exception) {
            throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
        }
    }

    private fun deriveKey(normCode: String, salt: ByteArray) = hkdf(normCode.toByteArray(Charsets.US_ASCII), salt, INFO, 32)

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

/** 迁移包的 zip 编解码 */
object MigrationPackage {
    const val FORMAT = 1
    const val SAMPLE_PREFS = "place_samples"
    const val TRIPS_PREFS = "trips"

    /** 随迁移文件导出的 SharedPreferences，与系统备份白名单（res/xml/data_extraction_rules.xml）一致 */
    val PREFS: List<String> = listOf(
        "trips", "transit", "places", "learn", "learn_ride2", "learn_bus_speed", "learn_metro",
        "walk_learn", "leave_wifi", "keepalive", "onboarding", "backup",
    )

    /** 只对本机有意义、不随迁移文件导出的键（例如下一次定位采样的闹钟时刻） */
    val DEVICE_KEYS: Map<String, Set<String>> = mapOf("places" to setOf("nextSample"))

    /** 去掉只对本机有意义的键与空值 */
    fun portable(name: String, entries: Map<String, Any?>): Map<String, Any> =
        entries.filter { (k, v) -> v != null && k !in DEVICE_KEYS[name].orEmpty() }.mapValues { it.value!! }

    /** 允许的记录文件名：不含路径分隔符 */
    private val TRACE_NAME = Regex("""^[0-9A-Za-z_.\-]{1,96}\.jsonl$""")

    fun validTraceName(n: String) = TRACE_NAME.matches(n) && !n.startsWith(".")

    fun tripCount(prefs: Map<String, Map<String, Any>>): Int =
        (prefs[TRIPS_PREFS]?.get("list") as? String)?.let { runCatching { JSONArray(it).length() }.getOrNull() } ?: 0

    fun traceDays(names: Collection<String>) = names.map { it.take(10) }.distinct().size

    fun write(c: MigrationContent): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, b: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(b); z.closeEntry() }
            put("manifest.json", metaJson(c.meta).toString(2).toByteArray())
            c.prefs.toSortedMap().forEach { (n, e) -> if (n in PREFS) put("prefs/$n.json", encodePrefs(e).toString().toByteArray()) }
            c.samplePrefs?.let { put("samples/$SAMPLE_PREFS.json", encodePrefs(it).toString().toByteArray()) }
            c.traces?.toSortedMap()?.forEach { (n, lines) ->
                require(validTraceName(n)) { "invalid trace name" }
                val sb = StringBuilder(); lines.forEach { sb.append(it).append('\n') }
                put("traces/$n", sb.toString().toByteArray(Charsets.UTF_8))
            }
            if (c.traces != null) put("traces/.present", ByteArray(0))
        }
        return bos.toByteArray()
    }

    /** 解析 zip；不在白名单中的条目和不合规的文件名一律忽略 */
    fun read(zip: ByteArray): MigrationContent {
        var meta: PackageMeta? = null
        val prefs = LinkedHashMap<String, Map<String, Any>>()
        var traces: LinkedHashMap<String, List<String>>? = null
        var samples: Map<String, Any>? = null
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    val bytes = z.readBytes()
                    when {
                        name == "manifest.json" -> meta = parseMeta(JSONObject(String(bytes, Charsets.UTF_8)))
                        name.startsWith("prefs/") && name.endsWith(".json") -> {
                            val p = name.removePrefix("prefs/").removeSuffix(".json")
                            if (p in PREFS) prefs[p] = decodePrefs(JSONObject(String(bytes, Charsets.UTF_8)))
                        }
                        name == "samples/$SAMPLE_PREFS.json" -> samples = decodePrefs(JSONObject(String(bytes, Charsets.UTF_8)))
                        name == "traces/.present" -> if (traces == null) traces = LinkedHashMap()
                        name.startsWith("traces/") -> {
                            val n = name.removePrefix("traces/")
                            if (validTraceName(n)) {
                                if (traces == null) traces = LinkedHashMap()
                                traces!![n] = String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
                            }
                        }
                    }
                }
            }
        } catch (e: MigrationException) {
            throw e
        } catch (e: Exception) {
            throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
        }
        val m = meta ?: throw MigrationException(MigrationException.Reason.WRONG_CODE_OR_CORRUPT)
        if (m.format > FORMAT) throw MigrationException(MigrationException.Reason.NEWER_VERSION)
        return MigrationContent(m, prefs, traces, samples)
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
}

/**
 * 导入（替换）与撤销。导入前把本机当前数据打成快照（记录按落盘原样，不解密），导入失败时自动用快照回滚；
 * 成功后快照保留，供用户撤销一次。
 */
object Migrator {
    enum class Outcome { OK, KEY_UNAVAILABLE, UNDO_SAVE_FAILED, FAILED }

    /** 本机快照；[withRecords] 为 true 时包含行程记录与原始定位采样 */
    fun snapshot(local: LocalData, withRecords: Boolean, now: Long): MigrationContent {
        val prefs = MigrationPackage.PREFS.associateWith { n -> local.readPrefs(n).filterValues { it != null }.mapValues { it.value!! } }
        val traces = if (withRecords) local.traceNames().filter(MigrationPackage::validTraceName).associateWith { local.readTraceRaw(it) } else null
        val samples = if (withRecords) local.readPrefs(MigrationPackage.SAMPLE_PREFS).filterValues { it != null }.mapValues { it.value!! } else null
        val meta = PackageMeta(MigrationPackage.FORMAT, now, "", MigrationPackage.tripCount(prefs), withRecords,
            traces?.keys?.let(MigrationPackage::traceDays) ?: 0, 0, raw = true)
        return MigrationContent(meta, prefs, traces, samples)
    }

    /**
     * 用迁移包替换本机数据：白名单中的设置与学习结果全部替换（迁移包中没有的文件清空）；
     * 迁移包含行程记录时，本机行程记录与原始定位采样一并替换，并用本机密钥重新加密；不含时本机记录保持不动。
     * [saveUndo] 保存撤销快照，失败时不做任何改动。
     */
    fun importReplace(content: MigrationContent, local: LocalData, now: Long, saveUndo: (ByteArray) -> Boolean): Outcome {
        val withRecords = content.traces != null || content.samplePrefs != null
        // 先全部加密好，密钥不可用时不做任何改动
        val encTraces = content.traces?.mapValues { (_, lines) ->
            lines.map { l -> if (RecordFormat.isEncrypted(l)) return Outcome.FAILED else local.encrypt(l) ?: return Outcome.KEY_UNAVAILABLE }
        }
        val encSamples: Map<String, Any>? = if (!withRecords) null else {
            val s = content.samplePrefs?.get("samples") as? String
            if (s == null) emptyMap() else mapOf("samples" to (local.encrypt(s) ?: return Outcome.KEY_UNAVAILABLE))
        }
        val undo = snapshot(local, withRecords, now)
        if (!saveUndo(MigrationPackage.write(undo))) return Outcome.UNDO_SAVE_FAILED
        val target = MigrationContent(content.meta, content.prefs, encTraces ?: if (withRecords) emptyMap() else null, encSamples)
        if (apply(target, local)) return Outcome.OK
        apply(undo, local)
        return Outcome.FAILED
    }

    /** 用撤销快照恢复导入前的数据 */
    fun restore(snapshot: MigrationContent, local: LocalData): Boolean = apply(snapshot, local)

    /** 写入：设置与学习结果整体替换；[MigrationContent.traces] / [MigrationContent.samplePrefs] 为 null 时不动本机记录 */
    private fun apply(c: MigrationContent, local: LocalData): Boolean {
        var ok = true
        MigrationPackage.PREFS.forEach { n -> ok = local.writePrefs(n, c.prefs[n].orEmpty()) && ok }
        c.traces?.let { t ->
            local.traceNames().filter { it !in t.keys }.forEach { ok = local.deleteTrace(it) && ok }
            t.forEach { (n, lines) -> ok = local.writeTraceRaw(n, lines) && ok }
        }
        c.samplePrefs?.let { ok = local.writePrefs(MigrationPackage.SAMPLE_PREFS, it) && ok }
        return ok
    }

    /** 迁移包内容摘要中用的数字，便于预览：行程数与记录天数 */
    fun preview(c: MigrationContent): PackageMeta = c.meta.copy(
        trips = MigrationPackage.tripCount(c.prefs),
        includesRecords = c.traces != null,
        traceDays = c.traces?.keys?.let(MigrationPackage::traceDays) ?: 0,
    )
}
