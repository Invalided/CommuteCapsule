package com.busnotice.commute

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 行程记录压缩块的无损编解码，与 Android 无关，可在 JVM 上单测。
 *
 * 编码结果首字节为编码方式：
 * - [CODEC_COLUMNAR]：每行拆成「骨架」与数值记号。骨架为去掉数值后的文字，按出现顺序编号成字典；
 *   数值按（骨架、位置）分列，能以定点整数原样还原的记为（小数位数、尾数与同列上一值之差），
 *   不能原样还原的（指数写法、前导零、负零、位数过多等）按原文字存放。各段依次排列后整体 Deflate。
 * - [CODEC_TEXT]：行数与以换行连接的全文整体 Deflate，作为列式编码自检未通过时的兜底。
 *
 * 两种方式都保证解码结果与输入逐行、逐字符一致；[encode] 在返回前先解码一遍核对。
 */
object TraceBlock {
    const val CODEC_COLUMNAR: Byte = 1
    const val CODEC_TEXT: Byte = 2

    private const val SKEL_TEMPLATE = 0
    private const val SKEL_LITERAL = 1
    private const val SLOT = '\u0000'
    private const val RAW_SCALE = 0xFF
    private const val MAX_DIGITS = 18

    /** 编码一组行；行内不得含换行符 */
    fun encode(lines: List<String>): ByteArray {
        require(lines.none { it.indexOf('\n') >= 0 || it.indexOf('\r') >= 0 }) { "line break inside a record" }
        val columnar = runCatching { byteArrayOf(CODEC_COLUMNAR) + deflate(columnar(lines)) }.getOrNull()
        if (columnar != null && decode(columnar) == lines) return columnar
        val text = encodeText(lines)
        if (decode(text) != lines) throw IOException("block verification failed")
        return text
    }

    /** 以 [CODEC_TEXT] 编码（列式编码自检未通过时使用） */
    internal fun encodeText(lines: List<String>): ByteArray {
        val body = Bytes().apply { varint(lines.size.toLong()); string(lines.joinToString("\n")) }.toByteArray()
        return byteArrayOf(CODEC_TEXT) + deflate(body)
    }

    /** 解码；内容损坏或编码方式未知时返回 null */
    fun decode(block: ByteArray): List<String>? = runCatching {
        if (block.isEmpty()) return null
        val body = inflate(block, 1)
        when (block[0]) {
            CODEC_COLUMNAR -> uncolumnar(body)
            CODEC_TEXT -> {
                val r = Reader(body, 0, body.size)
                val n = r.int()
                val text = r.string()
                require(r.done())
                if (n == 0) { require(text.isEmpty()); emptyList() } else text.split('\n').also { require(it.size == n) }
            }
            else -> null
        }
    }.getOrNull()

    // ---------- 列式编码 ----------

    private class Skeleton(val literal: Boolean, val text: String) {
        val segments: List<String> = if (literal) listOf(text) else text.split(SLOT)
        val slots get() = segments.size - 1
    }

    /** 数值记号的位置：前一字符为 `:` `[` `,`，后一字符为 `,` `]` `}` 或行尾 */
    private fun tokens(line: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == ':' || c == '[' || c == ',') {
                var j = i + 1
                if (j < n && line[j] == '-') j++
                val digitsFrom = j
                while (j < n && line[j].isAsciiDigit()) j++
                if (j > digitsFrom) {
                    if (j < n && line[j] == '.') {
                        val f = j + 1; var k = f
                        while (k < n && line[k].isAsciiDigit()) k++
                        if (k > f) j = k
                    }
                    if (j < n && (line[j] == 'e' || line[j] == 'E')) {
                        var k = j + 1
                        if (k < n && (line[k] == '+' || line[k] == '-')) k++
                        val f = k
                        while (k < n && line[k].isAsciiDigit()) k++
                        if (k > f) j = k
                    }
                    if (j == n || line[j] == ',' || line[j] == ']' || line[j] == '}') {
                        out += (i + 1) until j
                        i = j
                        continue
                    }
                }
            }
            i++
        }
        return out
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** 能以（尾数、小数位数）原样还原时返回二者，否则返回 null */
    private fun fixedPoint(t: String): Pair<Long, Int>? {
        var p = 0
        val neg = t.startsWith('-')
        if (neg) p = 1
        val dot = t.indexOf('.')
        val intPart = if (dot < 0) t.substring(p) else t.substring(p, dot)
        val frac = if (dot < 0) "" else t.substring(dot + 1)
        if (intPart.isEmpty() || intPart.any { !it.isAsciiDigit() } || frac.any { !it.isAsciiDigit() }) return null
        if (dot >= 0 && frac.isEmpty()) return null
        if (intPart.length + frac.length > MAX_DIGITS) return null
        val m = (intPart + frac).toLong().let { if (neg) -it else it }
        return if (format(m, frac.length) == t) m to frac.length else null
    }

    private fun format(m: Long, scale: Int): String {
        if (scale == 0) return m.toString()
        val neg = m < 0
        val a = (if (neg) -m else m).toString().padStart(scale + 1, '0')
        return (if (neg) "-" else "") + a.substring(0, a.length - scale) + "." + a.substring(a.length - scale)
    }

    private fun columnar(lines: List<String>): ByteArray {
        val dict = LinkedHashMap<String, Int>()
        val skels = ArrayList<Skeleton>()
        val lineSkel = IntArray(lines.size)
        val lineTokens = ArrayList<List<String>>(lines.size)
        for ((i, l) in lines.withIndex()) {
            val literal = l.indexOf(SLOT) >= 0
            val toks = if (literal) emptyList() else tokens(l)
            val key: String
            val values: List<String>
            if (literal) {
                key = "L$l"; values = emptyList()
            } else {
                val sb = StringBuilder(l.length)
                var last = 0
                for (r in toks) { sb.append(l, last, r.first).append(SLOT); last = r.last + 1 }
                sb.append(l, last, l.length)
                key = "T$sb"; values = toks.map { l.substring(it) }
            }
            val id = dict.getOrPut(key) { skels += Skeleton(literal, key.substring(1)); skels.size - 1 }
            lineSkel[i] = id
            lineTokens += values
        }

        val skelIds = Bytes(); val dictOut = Bytes(); val scales = Bytes(); val mants = Bytes(); val raws = Bytes()
        lineSkel.forEach(skelIds::varint)
        for (s in skels) {
            dictOut.write(if (s.literal) SKEL_LITERAL else SKEL_TEMPLATE)
            dictOut.string(s.text)
        }
        val byStruct = Array(skels.size) { ArrayList<Int>() }
        lineSkel.forEachIndexed { i, s -> byStruct[s] += i }
        for (s in skels.indices) {
            for (slot in 0 until skels[s].slots) {
                var prev = 0L
                for (i in byStruct[s]) {
                    val t = lineTokens[i][slot]
                    val fp = fixedPoint(t)
                    if (fp == null) {
                        scales.write(RAW_SCALE); raws.string(t)
                    } else {
                        scales.write(fp.second); mants.varint(zigzag(fp.first - prev)); prev = fp.first
                    }
                }
            }
        }
        val out = Bytes()
        out.varint(lines.size.toLong()); out.varint(skels.size.toLong())
        for (part in listOf(skelIds, dictOut, scales, mants, raws)) { out.varint(part.size().toLong()); out.write(part.bytes(), 0, part.size()) }
        return out.toByteArray()
    }

    private fun uncolumnar(b: ByteArray): List<String> {
        val r = Reader(b, 0, b.size)
        val n = r.int(); val nSkel = r.int()
        val parts = Array(5) { val len = r.int(); val p = Reader(b, r.pos, len); r.skip(len); p }
        val (skelIds, dictIn, scales, mants, raws) = parts
        val lineSkel = IntArray(n) { skelIds.int().also { require(it < nSkel) } }
        val skels = List(nSkel) {
            val literal = when (dictIn.byte()) { SKEL_LITERAL -> true; SKEL_TEMPLATE -> false; else -> error("bad skeleton") }
            Skeleton(literal, dictIn.string())
        }
        val byStruct = Array(nSkel) { ArrayList<Int>() }
        lineSkel.forEachIndexed { i, s -> byStruct[s] += i }
        val values = arrayOfNulls<Array<String?>>(n)
        for (s in 0 until nSkel) {
            val slots = skels[s].slots
            for (i in byStruct[s]) values[i] = arrayOfNulls(slots)
            for (slot in 0 until slots) {
                var prev = 0L
                for (i in byStruct[s]) {
                    val sc = scales.byte()
                    values[i]!![slot] = if (sc == RAW_SCALE) raws.string() else {
                        require(sc <= MAX_DIGITS)
                        prev += unzigzag(mants.long()); format(prev, sc)
                    }
                }
            }
        }
        require(skelIds.done() && dictIn.done() && scales.done() && mants.done() && raws.done() && r.done())
        return List(n) { i ->
            val s = skels[lineSkel[i]]
            if (s.literal) s.text else {
                val v = values[i]!!
                val sb = StringBuilder()
                for ((k, seg) in s.segments.withIndex()) { if (k > 0) sb.append(v[k - 1]); sb.append(seg) }
                sb.toString()
            }
        }
    }

    // ---------- 字节工具 ----------

    private fun zigzag(v: Long) = (v shl 1) xor (v shr 63)
    private fun unzigzag(v: Long) = (v ushr 1) xor -(v and 1)

    private class Bytes : ByteArrayOutputStream() {
        /** 内部缓冲区（长度可能大于 [size]），只用于按 [size] 截取写出 */
        fun bytes(): ByteArray = buf
        fun varint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) { write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            write(x.toInt())
        }
        fun varint(v: Int) = varint(v.toLong())
        fun string(s: String) { val b = s.toByteArray(Charsets.UTF_8); varint(b.size.toLong()); write(b, 0, b.size) }
    }

    private class Reader(val b: ByteArray, var pos: Int, len: Int) {
        private val end = pos + len
        init { require(len >= 0 && end <= b.size) }
        fun done() = pos == end
        fun skip(n: Int) { require(n >= 0 && pos + n <= end); pos += n }
        fun byte(): Int { require(pos < end); return b[pos++].toInt() and 0xFF }
        fun long(): Long {
            var shift = 0; var v = 0L
            while (true) {
                require(shift < 64)
                val x = byte()
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7
            }
        }
        fun int(): Int = long().also { require(it in 0..Int.MAX_VALUE) }.toInt()
        fun string(): String { val len = int(); require(pos + len <= end); return String(b, pos, len, Charsets.UTF_8).also { pos += len } }
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        try {
            d.setInput(b); d.finish()
            val out = ByteArrayOutputStream(maxOf(64, b.size / 4))
            val buf = ByteArray(16 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally { d.end() }
    }

    private fun inflate(b: ByteArray, off: Int): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(b, off, b.size - off)
            val out = ByteArrayOutputStream(maxOf(64, (b.size - off) * 4))
            val buf = ByteArray(16 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw IOException("truncated block")
                out.write(buf, 0, n)
            }
            if (inf.remaining != 0) throw IOException("trailing data")
            return out.toByteArray()
        } finally { inf.end() }
    }
}

/**
 * 把一个行程记录文件整理为单个压缩块（`e3` 行），与 Android 无关。
 *
 * 流程：读出全部记录并解密 → 编码、压缩、整块加密 → 写入同目录临时文件并同步落盘 →
 * 从临时文件重新解密解码，与原记录逐行核对 → 原文件长度与修改时间未变时整体替换。
 * 任何一步失败都删除临时文件、保留原文件；中途进程被杀时原文件不受影响，残留的临时文件在下次整理前清除。
 */
object TraceCompaction {
    const val TMP_SUFFIX = ".cmp" + RecordMigration.TMP_SUFFIX

    enum class Result {
        /** 已整理为压缩块 */
        COMPACTED,
        /** 已是单个压缩块，或没有任何记录 */
        ALREADY,
        /** 含暂时无法读取或不可恢复的记录，不整理 */
        LOCKED,
        /** 整理期间文件被改动，本次放弃 */
        CHANGED,
        /** 写入或核对失败，原文件保持不变 */
        FAILED,
    }

    /**
     * 整理 [f]。[keys] 按密钥标识取可读密钥；[current] 为加密压缩块所用的密钥；
     * [commit] 在替换前调用，参数为替换动作，调用方可在其中加锁（默认直接执行）。
     */
    fun compactFile(
        f: File,
        keys: (String) -> RecordCipher?,
        current: RecordCipher,
        commit: (() -> Boolean) -> Boolean = { it() },
    ): Result {
        val len = f.length(); val mtime = f.lastModified()
        val raw = try { f.readLines().filter { it.isNotEmpty() } } catch (e: IOException) { return Result.FAILED }
        if (raw.isEmpty() || (raw.size == 1 && RecordFormat.isBlock(raw[0]))) return Result.ALREADY
        val decoded = RecordCodec.decodeLines(raw, keys)
        if (decoded.unreadable > 0) return Result.LOCKED
        if (decoded.text.isEmpty()) return Result.ALREADY
        val tmp = File(f.parentFile, f.name + TMP_SUFFIX)
        try {
            val record = current.encryptBlock(TraceBlock.encode(decoded.text))
            FileOutputStream(tmp).use { out ->
                out.write(record.toByteArray(Charsets.UTF_8)); out.write('\n'.code)
                out.flush(); out.fd.sync()
            }
            val check = RecordCodec.decodeLines(tmp.readLines(), keys)
            if (check.unreadable != 0 || check.text != decoded.text) { tmp.delete(); return Result.FAILED }
            var changed = false
            val ok = commit {
                if (f.length() != len || f.lastModified() != mtime) { changed = true; false } else tmp.renameTo(f)
            }
            if (!ok) tmp.delete()
            return when { ok -> Result.COMPACTED; changed -> Result.CHANGED; else -> Result.FAILED }
        } catch (e: Exception) {
            tmp.delete()
            return Result.FAILED
        }
    }

    /** 删除上次中断留下的整理临时文件 */
    fun cleanup(dir: File) {
        dir.listFiles { x -> x.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
    }
}
