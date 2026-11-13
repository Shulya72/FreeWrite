package party.shulya.freewrite

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Линии листа PDF со слоями (0.26) — для поиска элементов на плане (стены, пилоны, плита).
 *
 * Чертёж из AutoCAD хранит каждую линию со своим слоем («A-WALL-MCUT» — контур стены, «A-WALL-PATT» — штриховка,
 * оси, размеры, текст — отдельно). Картинку листа рисует Android (PdfRenderer), а здесь — свой небольшой разбор файла:
 * таблица объектов (обычная и сжатая), потоки (Flate, ASCIIHex, ASCII85), страницы, содержимое (пути, толщина,
 * преобразования, слои, вложенные блоки — Form XObject). Текст пропускается.
 *
 * Координаты — пункты листа, ось y вниз, с учётом обрезки страницы (CropBox) и поворота (/Rotate): так же, как лист
 * нарисован картинкой, поэтому доля по ширине и высоте совпадает с долей картинки.
 */
object PdfVector {

    /**
     * Линия или заливка: слой («» — без слоя), толщина линии в пунктах листа, обводка/заливка, заливка белая (маска —
     * не бетон), кольца — x, y в пунктах; [closed] — кольцо замкнуто (h, re, или конец совпал с началом).
     */
    class Path(val layer: String, val width: Float, val stroke: Boolean, val fill: Boolean, val white: Boolean,
               val rings: List<FloatArray>, val closed: BooleanArray)

    /** Лист: размер в пунктах (после поворота), пути, слои (имя → сколько путей). */
    class Page(val w: Float, val h: Float, val paths: List<Path>, val layers: Map<String, Int>, val count: Int)

    fun read(file: File, index: Int): Page = read(file.readBytes(), index)

    fun read(bytes: ByteArray, index: Int): Page = Reader(bytes).page(index)

    /** Сколько листов (для проверок и выбора листа). */
    fun pageCount(bytes: ByteArray): Int = runCatching { Reader(bytes).pages().size }.getOrDefault(0)

    // ---------------------------------------------------------------- объекты PDF

    private class Name(val s: String) { override fun toString() = "/$s" }
    private class PStr(val b: ByteArray)
    private class Ref(val num: Int, val gen: Int)
    private class Stream(val dict: Map<String, Any?>, val data: ByteArray)
    private object Kw { val ARR_END = Any(); val DICT_END = Any() }
    private class Op(val s: String)

    /** Разбор лексем PDF (и файла, и содержимого страницы). */
    private class Lex(val d: ByteArray, var p: Int = 0) {
        fun ws(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        fun delim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code || c == ']'.code ||
            c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        fun skip() {
            while (p < d.size) {
                val c = d[p].toInt() and 0xFF
                if (ws(c)) { p++; continue }
                if (c == '%'.code) { while (p < d.size && d[p].toInt() != 10 && d[p].toInt() != 13) p++; continue }
                break
            }
        }

        val eof get() = run { skip(); p >= d.size }

        /** Следующая лексема: число (Double), Name, PStr, '[' → List, '<<' → Map, ']' / '>>' — маркеры, иначе Op. */
        fun token(): Any? {
            skip()
            if (p >= d.size) return null
            val c = d[p].toInt() and 0xFF
            when (c) {
                '/'.code -> {
                    p++
                    val sb = StringBuilder()
                    while (p < d.size) {
                        val x = d[p].toInt() and 0xFF
                        if (ws(x) || delim(x)) break
                        if (x == '#'.code && p + 2 < d.size) {
                            val h = hex(d[p + 1].toInt()) * 16 + hex(d[p + 2].toInt())
                            if (h >= 0) { sb.append(h.toChar()); p += 3; continue }
                        }
                        sb.append(x.toChar()); p++
                    }
                    return Name(sb.toString())
                }
                '('.code -> return PStr(literal())
                '<'.code -> {
                    if (p + 1 < d.size && d[p + 1].toInt() == '<'.code) { p += 2; return dict() }
                    p++
                    val out = ByteArrayOutputStream()
                    var hi = -1
                    while (p < d.size && d[p].toInt() != '>'.code) {
                        val v = hex(d[p].toInt()); p++
                        if (v < 0) continue
                        if (hi < 0) hi = v else { out.write(hi * 16 + v); hi = -1 }
                    }
                    if (hi >= 0) out.write(hi * 16)
                    p++
                    return PStr(out.toByteArray())
                }
                '>'.code -> { p += if (p + 1 < d.size && d[p + 1].toInt() == '>'.code) 2 else 1; return Kw.DICT_END }
                '['.code -> { p++; return array() }
                ']'.code -> { p++; return Kw.ARR_END }
                '{'.code, '}'.code -> { p++; return Op(c.toChar().toString()) }
            }
            val st = p
            while (p < d.size) {
                val x = d[p].toInt() and 0xFF
                if (ws(x) || delim(x)) break
                p++
            }
            if (p == st) { p++; return Op("?") }
            val s = String(d, st, p - st, Charsets.ISO_8859_1)
            val c0 = s[0]
            if (c0 == '+' || c0 == '-' || c0 == '.' || c0.isDigit()) s.toDoubleOrNull()?.let { return it }
            return Op(s)
        }

        private fun hex(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }

        private fun literal(): ByteArray {
            p++ // (
            val out = ByteArrayOutputStream()
            var depth = 1
            while (p < d.size) {
                val c = d[p].toInt() and 0xFF
                p++
                when (c) {
                    '('.code -> { depth++; out.write(c) }
                    ')'.code -> { depth--; if (depth == 0) return out.toByteArray(); out.write(c) }
                    '\\'.code -> {
                        if (p >= d.size) break
                        val e = d[p].toInt() and 0xFF
                        p++
                        when (e) {
                            'n'.code -> out.write(10); 'r'.code -> out.write(13); 't'.code -> out.write(9)
                            'b'.code -> out.write(8); 'f'.code -> out.write(12)
                            10 -> {}
                            13 -> { if (p < d.size && d[p].toInt() == 10) p++ }
                            in '0'.code..'7'.code -> {
                                var v = e - '0'.code
                                var k = 0
                                while (k < 2 && p < d.size && d[p].toInt() in '0'.code..'7'.code) { v = v * 8 + (d[p].toInt() - '0'.code); p++; k++ }
                                out.write(v and 0xFF)
                            }
                            else -> out.write(e)
                        }
                    }
                    else -> out.write(c)
                }
            }
            return out.toByteArray()
        }

        fun array(): List<Any?> {
            val a = ArrayList<Any?>()
            while (true) {
                val t = value() ?: break
                if (t === Kw.ARR_END) break
                if (t === Kw.DICT_END) continue
                a += t
            }
            return a
        }

        fun dict(): Map<String, Any?> {
            val m = HashMap<String, Any?>()
            while (true) {
                val k = value() ?: break
                if (k === Kw.DICT_END) break
                if (k !is Name) continue
                val v = value()
                if (v === Kw.DICT_END) { m[k.s] = null; break }
                m[k.s] = v
            }
            return m
        }

        /** Значение с ссылками «N G R». */
        fun value(): Any? {
            val t = token()
            if (t is Double && t == Math.floor(t) && t >= 0) {
                val save = p
                val t2 = token()
                if (t2 is Double && t2 == Math.floor(t2) && t2 >= 0) {
                    val save2 = p
                    val t3 = token()
                    if (t3 is Op && t3.s == "R") return Ref(t.toInt(), t2.toInt())
                    p = save2
                }
                p = save
            }
            if (t is Op) when (t.s) {
                "true" -> return true
                "false" -> return false
                "null" -> return null
            }
            return t
        }
    }

    // ---------------------------------------------------------------- файл

    private class Reader(val d: ByteArray) {
        val offsets = HashMap<Int, Int>()
        val inStm = HashMap<Int, IntArray>()
        val cache = HashMap<Int, Any?>()
        var trailer: Map<String, Any?> = emptyMap()

        init {
            val ok = runCatching { readXref() && resolve(trailer["Root"]) is Map<*, *> }.getOrDefault(false)
            if (!ok) runCatching { scan() }
        }

        private fun readXref(): Boolean {
            val tail = String(d, max(0, d.size - 2048), min(2048, d.size), Charsets.ISO_8859_1)
            val k = tail.lastIndexOf("startxref")
            if (k < 0) return false
            var pos = Lex(d, max(0, d.size - 2048) + k + 9).token() as? Double ?: return false
            val seen = HashSet<Int>()
            var first = true
            while (pos.toInt() in d.indices && seen.add(pos.toInt())) {
                val lx = Lex(d, pos.toInt())
                val t = lx.token()
                val tr: Map<String, Any?>
                if (t is Op && t.s == "xref") {
                    while (true) {
                        val a = lx.token()
                        if (a is Op && a.s == "trailer") break
                        val start = (a as? Double)?.toInt() ?: return false
                        val count = (lx.token() as? Double)?.toInt() ?: return false
                        for (i in 0 until count) {
                            val off = (lx.token() as? Double)?.toInt() ?: return false
                            lx.token()
                            val kind = lx.token() as? Op ?: return false
                            val num = start + i
                            if (kind.s == "n" && off > 0 && num !in offsets && num !in inStm) offsets[num] = off
                        }
                    }
                    tr = lx.value() as? Map<String, Any?> ?: return false
                    (tr["XRefStm"] as? Double)?.let { xrefStream(it.toInt()) }
                } else {
                    tr = xrefStream(pos.toInt()) ?: return false
                }
                if (first) { trailer = tr; first = false }
                pos = tr["Prev"] as? Double ?: break
            }
            return trailer.isNotEmpty()
        }

        /** Сжатая таблица объектов (/Type /XRef) по смещению; вернёт её словарь. */
        private fun xrefStream(off: Int): Map<String, Any?>? {
            val (_, obj) = parseAt(off) ?: return null
            val s = obj as? Stream ?: return null
            if ((s.dict["Type"] as? Name)?.s != "XRef") return null
            val data = decode(s) ?: return null
            val w = (s.dict["W"] as? List<*>)?.map { (it as? Double)?.toInt() ?: 0 } ?: return null
            if (w.size < 3) return null
            val size = (s.dict["Size"] as? Double)?.toInt() ?: 0
            val index = (s.dict["Index"] as? List<*>)?.map { (it as? Double)?.toInt() ?: 0 } ?: listOf(0, size)
            val rec = w[0] + w[1] + w[2]
            if (rec <= 0) return null
            var p = 0
            var i = 0
            while (i + 1 < index.size) {
                val start = index[i]; val cnt = index[i + 1]
                for (n in 0 until cnt) {
                    if (p + rec > data.size) break
                    fun f(len: Int): Long { var v = 0L; repeat(len) { v = (v shl 8) or (data[p++].toLong() and 0xFF) }; return v }
                    val type = if (w[0] == 0) 1L else f(w[0])
                    val a = f(w[1]); val b = f(w[2])
                    val num = start + n
                    if (num in offsets || num in inStm) continue
                    when (type) {
                        1L -> if (a > 0) offsets[num] = a.toInt()
                        2L -> inStm[num] = intArrayOf(a.toInt(), b.toInt())
                    }
                }
                i += 2
            }
            return s.dict
        }

        /** Запасной путь: найти все «N G obj» по файлу (повреждённая таблица). */
        private fun scan() {
            offsets.clear(); inStm.clear(); cache.clear()
            var i = 0
            val n = d.size
            while (i < n - 4) {
                if (d[i] == 'o'.code.toByte() && d[i + 1] == 'b'.code.toByte() && d[i + 2] == 'j'.code.toByte()) {
                    // назад: пробелы, gen, пробелы, num
                    var j = i - 1
                    while (j >= 0 && (d[j].toInt() == 32 || d[j].toInt() == 10 || d[j].toInt() == 13)) j--
                    val ge = j
                    while (j >= 0 && d[j] in '0'.code.toByte()..'9'.code.toByte()) j--
                    if (j < ge) {
                        val gs = j + 1
                        while (j >= 0 && (d[j].toInt() == 32 || d[j].toInt() == 10 || d[j].toInt() == 13)) j--
                        val ne = j
                        while (j >= 0 && d[j] in '0'.code.toByte()..'9'.code.toByte()) j--
                        if (j < ne && gs > ne) {
                            val num = String(d, j + 1, ne - j, Charsets.ISO_8859_1).toIntOrNull()
                            if (num != null) offsets[num] = j + 1
                        }
                    }
                }
                i++
            }
            // Сжатые объекты в найденных потоках.
            for ((num, _) in offsets.toMap()) {
                val o = runCatching { obj(num) }.getOrNull() as? Stream ?: continue
                when ((o.dict["Type"] as? Name)?.s) {
                    "ObjStm" -> runCatching {
                        val data = decode(o) ?: return@runCatching
                        val cnt = (o.dict["N"] as? Double)?.toInt() ?: 0
                        val lx = Lex(data)
                        for (k in 0 until cnt) { val on = (lx.token() as? Double)?.toInt() ?: break; lx.token(); if (on !in offsets) inStm[on] = intArrayOf(num, k) }
                    }
                    "XRef" -> if (trailer.isEmpty() || trailer["Root"] == null) trailer = o.dict
                }
            }
            if (trailer["Root"] == null) {
                val k = String(d, Charsets.ISO_8859_1).lastIndexOf("trailer")
                if (k >= 0) (Lex(d, k + 7).value() as? Map<String, Any?>)?.let { trailer = it }
            }
            if (resolve(trailer["Root"]) !is Map<*, *>) {
                for (num in offsets.keys) {
                    val o = runCatching { obj(num) }.getOrNull() as? Map<*, *> ?: continue
                    if ((o["Type"] as? Name)?.s == "Catalog") { trailer = mapOf("Root" to Ref(num, 0)); break }
                }
            }
        }

        /** «N G obj значение [stream]» по смещению. */
        private fun parseAt(off: Int): Pair<Int, Any?>? {
            val lx = Lex(d, off)
            val num = (lx.token() as? Double)?.toInt() ?: return null
            lx.token()
            val kw = lx.token() as? Op ?: return null
            if (kw.s != "obj") return null
            val v = lx.value()
            val save = lx.p
            val t = lx.token()
            if (t is Op && t.s == "stream" && v is Map<*, *>) {
                @Suppress("UNCHECKED_CAST") val dict = v as Map<String, Any?>
                var p = lx.p
                if (p < d.size && d[p].toInt() == 13) p++
                if (p < d.size && d[p].toInt() == 10) p++
                val len = (resolve(dict["Length"]) as? Double)?.toInt() ?: -1
                val end = if (len >= 0 && p + len <= d.size && endsStream(p + len)) p + len else findEndStream(p)
                return num to Stream(dict, d.copyOfRange(p, max(p, end)))
            }
            lx.p = save
            return num to v
        }

        private fun endsStream(at: Int): Boolean {
            var i = at
            while (i < d.size && (d[i].toInt() == 10 || d[i].toInt() == 13 || d[i].toInt() == 32)) i++
            return i + 9 <= d.size && String(d, i, 9, Charsets.ISO_8859_1) == "endstream"
        }

        private fun findEndStream(from: Int): Int {
            val key = "endstream".toByteArray()
            var i = from
            while (i + key.size <= d.size) {
                var ok = true
                for (k in key.indices) if (d[i + k] != key[k]) { ok = false; break }
                if (ok) {
                    var e = i
                    if (e > from && d[e - 1].toInt() == 10) e--
                    if (e > from && d[e - 1].toInt() == 13) e--
                    return e
                }
                i++
            }
            return d.size
        }

        fun obj(num: Int): Any? {
            if (cache.containsKey(num)) return cache[num]
            cache[num] = null // от зацикливания
            val v: Any? = offsets[num]?.let { off -> parseAt(off)?.second } ?: inStm[num]?.let { (sn, idx) -> fromObjStm(sn, idx, num) }
            cache[num] = v
            return v
        }

        private val stmCache = HashMap<Int, Pair<ByteArray, IntArray>>()

        private fun fromObjStm(sn: Int, idx: Int, num: Int): Any? {
            val (data, offs) = stmCache.getOrPut(sn) {
                val s = obj(sn) as? Stream ?: return null
                val data = decode(s) ?: return null
                val n = (s.dict["N"] as? Double)?.toInt() ?: 0
                val first = (s.dict["First"] as? Double)?.toInt() ?: 0
                val lx = Lex(data)
                val o = IntArray(n * 2)
                for (k in 0 until n) { o[2 * k] = (lx.token() as? Double)?.toInt() ?: -1; o[2 * k + 1] = first + ((lx.token() as? Double)?.toInt() ?: 0) }
                data to o
            }
            var k = idx
            if (k * 2 + 1 >= offs.size || offs[k * 2] != num) {
                k = -1
                for (q in 0 until offs.size / 2) if (offs[q * 2] == num) { k = q; break }
                if (k < 0) return null
            }
            return Lex(data, offs[k * 2 + 1]).value()
        }

        fun resolve(v: Any?): Any? {
            var x = v
            var guard = 0
            while (x is Ref && guard++ < 32) x = obj(x.num)
            return x
        }

        @Suppress("UNCHECKED_CAST")
        fun dictOf(v: Any?): Map<String, Any?>? = when (val r = resolve(v)) {
            is Map<*, *> -> r as Map<String, Any?>
            is Stream -> r.dict
            else -> null
        }

        // ---- потоки

        fun decode(s: Stream): ByteArray? {
            var data = s.data
            val f = resolve(s.dict["Filter"])
            val filters = when (f) { is Name -> listOf(f.s); is List<*> -> f.mapNotNull { (resolve(it) as? Name)?.s }; else -> emptyList() }
            val parms = resolve(s.dict["DecodeParms"])
            for ((i, name) in filters.withIndex()) {
                val parm = dictOf(if (parms is List<*>) parms.getOrNull(i) else parms)
                data = when (name) {
                    "FlateDecode", "Fl" -> predictor(inflate(data), parm)
                    "ASCIIHexDecode", "AHx" -> asciiHex(data)
                    "ASCII85Decode", "A85" -> ascii85(data)
                    else -> return null
                }
            }
            return data
        }

        private fun inflate(src: ByteArray): ByteArray {
            fun run(nowrap: Boolean): ByteArray {
                val inf = Inflater(nowrap)
                inf.setInput(src)
                val out = ByteArrayOutputStream(src.size * 4)
                val buf = ByteArray(64 * 1024)
                try {
                    while (!inf.finished()) {
                        val n = inf.inflate(buf)
                        if (n > 0) out.write(buf, 0, n)
                        else if (inf.needsInput() || inf.needsDictionary()) break
                    }
                } finally { inf.end() }
                return out.toByteArray()
            }
            val a = runCatching { run(false) }.getOrNull()
            if (a != null && a.isNotEmpty()) return a
            return runCatching { run(true) }.getOrDefault(ByteArray(0))
        }

        private fun predictor(data: ByteArray, parm: Map<String, Any?>?): ByteArray {
            val pred = (parm?.get("Predictor") as? Double)?.toInt() ?: 1
            if (pred < 10) return data
            val colors = (parm?.get("Colors") as? Double)?.toInt() ?: 1
            val bpc = (parm?.get("BitsPerComponent") as? Double)?.toInt() ?: 8
            val cols = (parm?.get("Columns") as? Double)?.toInt() ?: 1
            val bpp = max(1, colors * bpc / 8)
            val row = (cols * colors * bpc + 7) / 8
            val out = ByteArrayOutputStream(data.size)
            val prev = ByteArray(row)
            val cur = ByteArray(row)
            var p = 0
            while (p + 1 + row <= data.size) {
                val type = data[p].toInt() and 0xFF
                System.arraycopy(data, p + 1, cur, 0, row)
                p += row + 1
                for (i in 0 until row) {
                    val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                    val b = prev[i].toInt() and 0xFF
                    val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                    val x = cur[i].toInt() and 0xFF
                    val v = when (type) {
                        1 -> x + a
                        2 -> x + b
                        3 -> x + (a + b) / 2
                        4 -> { val pp = a + b - c; val pa = abs(pp - a); val pb = abs(pp - b); val pc = abs(pp - c)
                            x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                        else -> x
                    }
                    cur[i] = v.toByte()
                }
                out.write(cur)
                System.arraycopy(cur, 0, prev, 0, row)
            }
            return out.toByteArray()
        }

        private fun asciiHex(src: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var hi = -1
            for (b in src) {
                val c = b.toInt() and 0xFF
                if (c == '>'.code) break
                val v = when (c) { in 48..57 -> c - 48; in 97..102 -> c - 87; in 65..70 -> c - 55; else -> -1 }
                if (v < 0) continue
                if (hi < 0) hi = v else { out.write(hi * 16 + v); hi = -1 }
            }
            if (hi >= 0) out.write(hi * 16)
            return out.toByteArray()
        }

        private fun ascii85(src: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            val tuple = IntArray(5)
            var n = 0
            var i = 0
            if (src.size >= 2 && src[0].toInt() == '<'.code && src[1].toInt() == '~'.code) i = 2
            while (i < src.size) {
                val c = src[i++].toInt() and 0xFF
                if (c == '~'.code) break
                if (c <= 32) continue
                if (c == 'z'.code && n == 0) { repeat(4) { out.write(0) }; continue }
                if (c < 33 || c > 117) continue
                tuple[n++] = c - 33
                if (n == 5) {
                    var v = 0L
                    for (k in 0 until 5) v = v * 85 + tuple[k]
                    for (k in 3 downTo 0) out.write(((v shr (8 * k)) and 0xFF).toInt())
                    n = 0
                }
            }
            if (n > 1) {
                for (k in n until 5) tuple[k] = 84
                var v = 0L
                for (k in 0 until 5) v = v * 85 + tuple[k]
                for (k in 3 downTo 4 - (n - 1)) out.write(((v shr (8 * k)) and 0xFF).toInt())
            }
            return out.toByteArray()
        }

        // ---- страницы

        class PageRef(val dict: Map<String, Any?>, val res: Map<String, Any?>?, val box: FloatArray, val rotate: Int)

        fun pages(): List<PageRef> {
            val root = dictOf(trailer["Root"]) ?: return emptyList()
            val out = ArrayList<PageRef>()
            val seen = HashSet<Any>()
            fun walk(node: Any?, res: Map<String, Any?>?, box: FloatArray?, crop: FloatArray?, rot: Int) {
                val key = node ?: return
                if (out.size > 2000) return
                val nd = dictOf(node) ?: return
                if (!seen.add(if (key is Ref) key.num else nd)) return
                val r2 = dictOf(nd["Resources"]) ?: res
                val b2 = rect(nd["MediaBox"]) ?: box
                val c2 = rect(nd["CropBox"]) ?: crop
                val rt = (resolve(nd["Rotate"]) as? Double)?.toInt() ?: rot
                val kids = resolve(nd["Kids"]) as? List<*>
                if (kids != null && (nd["Type"] as? Name)?.s != "Page") for (k in kids) walk(k, r2, b2, c2, rt)
                else {
                    val mb = b2 ?: floatArrayOf(0f, 0f, 612f, 792f)
                    val cb = c2?.let { c -> floatArrayOf(max(c[0], mb[0]), max(c[1], mb[1]), min(c[2], mb[2]), min(c[3], mb[3])) }
                        ?.takeIf { it[2] > it[0] && it[3] > it[1] } ?: mb
                    out += PageRef(nd, r2, cb, ((rt % 360) + 360) % 360)
                }
            }
            walk(root["Pages"], null, null, null, 0)
            return out
        }

        private fun rect(v: Any?): FloatArray? {
            val a = resolve(v) as? List<*> ?: return null
            if (a.size < 4) return null
            val n = a.take(4).map { (resolve(it) as? Double)?.toFloat() ?: return null }
            return floatArrayOf(min(n[0], n[2]), min(n[1], n[3]), max(n[0], n[2]), max(n[1], n[3]))
        }

        fun page(index: Int): Page {
            val ps = pages()
            val pr = ps.getOrNull(index) ?: throw IllegalArgumentException("нет листа ${index + 1}")
            val bx = pr.box
            val bw = bx[2] - bx[0]; val bh = bx[3] - bx[1]
            val rot = pr.rotate
            val pw = if (rot == 90 || rot == 270) bh else bw
            val ph = if (rot == 90 || rot == 270) bw else bh
            val run = Run(this, bx, rot)
            runCatching { run.content(pr.dict["Contents"], pr.res, IDENTITY, 0) }
            val layers = LinkedHashMap<String, Int>()
            for (p in run.out) layers[p.layer] = (layers[p.layer] ?: 0) + 1
            return Page(pw, ph, run.out, layers, ps.size)
        }
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    /** a × b для матриц PDF [a b c d e f]: сначала a, потом b. */
    private fun mul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
        a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5],
    )

    // ---------------------------------------------------------------- содержимое страницы

    private class Gs(var ctm: FloatArray, var lw: Float, var fillWhite: Boolean) {
        fun copy() = Gs(ctm, lw, fillWhite)
    }

    private class Run(val r: Reader, val box: FloatArray, val rot: Int) {
        val out = ArrayList<Path>()
        val bw = box[2] - box[0]
        val bh = box[3] - box[1]

        /** Пользовательские координаты → пункты листа (y вниз, с поворотом). */
        fun page(x: Float, y: Float, o: FloatArray, i: Int) {
            val px = x - box[0]; val py = box[3] - y
            when (rot) {
                90 -> { o[i] = bh - py; o[i + 1] = px }
                180 -> { o[i] = bw - px; o[i + 1] = bh - py }
                270 -> { o[i] = py; o[i + 1] = bw - px }
                else -> { o[i] = px; o[i + 1] = py }
            }
        }

        fun content(contents: Any?, res: Map<String, Any?>?, ctm0: FloatArray, depth: Int) {
            val c = r.resolve(contents)
            val parts = when (c) { is List<*> -> c.mapNotNull { r.resolve(it) as? Stream }; is Stream -> listOf(c); else -> emptyList() }
            val bytes = ByteArrayOutputStream()
            for (s in parts) { r.decode(s)?.let { bytes.write(it) }; bytes.write(10) }
            exec(bytes.toByteArray(), res, ctm0, depth)
        }

        private fun num(v: Any?) = (v as? Double)?.toFloat() ?: 0f

        fun exec(data: ByteArray, res: Map<String, Any?>?, ctm0: FloatArray, depth: Int) {
            val lx = Lex(data)
            val ops = ArrayList<Any?>()
            val stack = ArrayList<Gs>()
            var gs = Gs(ctm0, 1f, false)
            val layers = ArrayList<String>()
            val rings = ArrayList<FloatArray>()
            val closed = ArrayList<Boolean>()
            var cur = FloatArrayBuilder()
            var curClosed = false
            var cx = 0f; var cy = 0f
            var sx = 0f; var sy = 0f
            val tmp = FloatArray(2)
            fun dev(x: Float, y: Float) {
                val m = gs.ctm
                page(m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5], tmp, 0)
            }
            fun flushSub() {
                if (cur.size >= 4) { rings += cur.toArray(); closed += curClosed }
                cur = FloatArrayBuilder(); curClosed = false
            }
            fun moveTo(x: Float, y: Float) { flushSub(); cx = x; cy = y; sx = x; sy = y; dev(x, y); cur.add(tmp[0], tmp[1]) }
            fun lineTo(x: Float, y: Float) {
                if (cur.size == 0) { dev(cx, cy); cur.add(tmp[0], tmp[1]) }
                cx = x; cy = y; dev(x, y); cur.add(tmp[0], tmp[1])
            }
            fun curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                if (cur.size == 0) { dev(cx, cy); cur.add(tmp[0], tmp[1]) }
                val p0x = cx; val p0y = cy
                // Сколько кусков: по длине ломаной опорных точек в пунктах листа.
                dev(p0x, p0y); val ax = tmp[0]; val ay = tmp[1]
                dev(x1, y1); val bx = tmp[0]; val by = tmp[1]
                dev(x2, y2); val ccx = tmp[0]; val ccy = tmp[1]
                dev(x3, y3); val dx = tmp[0]; val dy = tmp[1]
                val len = hypot(bx - ax, by - ay) + hypot(ccx - bx, ccy - by) + hypot(dx - ccx, dy - ccy)
                val n = (len / 1.5f).toInt().coerceIn(2, 24)
                for (k in 1..n) {
                    val t = k.toFloat() / n; val u = 1 - t
                    val w0 = u * u * u; val w1 = 3 * u * u * t; val w2 = 3 * u * t * t; val w3 = t * t * t
                    cur.add(w0 * ax + w1 * bx + w2 * ccx + w3 * dx, w0 * ay + w1 * by + w2 * ccy + w3 * dy)
                }
                cx = x3; cy = y3
            }
            fun paint(stroke: Boolean, fill: Boolean, close: Boolean) {
                if (close && cur.size >= 4) { lineTo(sx, sy); curClosed = true }
                flushSub()
                if ((stroke || fill) && rings.isNotEmpty()) {
                    val m = gs.ctm
                    val scale = sqrt(abs(m[0] * m[3] - m[1] * m[2]))
                    val cl = BooleanArray(rings.size) { i ->
                        closed[i] || run { val a = rings[i]; a.size >= 6 && abs(a[0] - a[a.size - 2]) < 0.05f && abs(a[1] - a[a.size - 1]) < 0.05f }
                    }
                    out += Path(layers.lastOrNull() ?: "", gs.lw * scale, stroke, fill, fill && gs.fillWhite, ArrayList(rings), cl)
                }
                rings.clear(); closed.clear()
            }
            while (true) {
                // Содержимое страницы: ссылок «N G R» в нём нет — лексемы без поиска ссылок (быстрее).
                val t = lx.token() ?: break
                if (t !is Op || t.s == "true" || t.s == "false" || t.s == "null") { if (t !== Kw.ARR_END && t !== Kw.DICT_END) ops += t; continue }
                try {
                    when (t.s) {
                        "q" -> { stack += gs.copy(); gs = gs.copy() }
                        "Q" -> if (stack.isNotEmpty()) gs = stack.removeAt(stack.size - 1)
                        "cm" -> if (ops.size >= 6) {
                            val m = FloatArray(6) { num(ops[ops.size - 6 + it]) }
                            gs.ctm = mul(m, gs.ctm)
                        }
                        "w" -> if (ops.isNotEmpty()) gs.lw = num(ops.last())
                        "g" -> if (ops.isNotEmpty()) gs.fillWhite = num(ops.last()) > 0.95f
                        "rg" -> if (ops.size >= 3) gs.fillWhite = (0..2).all { num(ops[ops.size - 3 + it]) > 0.95f }
                        "k" -> if (ops.size >= 4) gs.fillWhite = (0..3).all { num(ops[ops.size - 4 + it]) < 0.05f }
                        "sc", "scn" -> {
                            val nums = ops.filterIsInstance<Double>()
                            gs.fillWhite = when (nums.size) {
                                1 -> nums[0] > 0.95; 3 -> nums.all { it > 0.95 }; 4 -> nums.all { it < 0.05 }; else -> false
                            }
                        }
                        "m" -> if (ops.size >= 2) moveTo(num(ops[ops.size - 2]), num(ops[ops.size - 1]))
                        "l" -> if (ops.size >= 2) lineTo(num(ops[ops.size - 2]), num(ops[ops.size - 1]))
                        "c" -> if (ops.size >= 6) { val o = ops.size - 6; curve(num(ops[o]), num(ops[o + 1]), num(ops[o + 2]), num(ops[o + 3]), num(ops[o + 4]), num(ops[o + 5])) }
                        "v" -> if (ops.size >= 4) { val o = ops.size - 4; curve(cx, cy, num(ops[o]), num(ops[o + 1]), num(ops[o + 2]), num(ops[o + 3])) }
                        "y" -> if (ops.size >= 4) { val o = ops.size - 4; curve(num(ops[o]), num(ops[o + 1]), num(ops[o + 2]), num(ops[o + 3]), num(ops[o + 2]), num(ops[o + 3])) }
                        "h" -> if (cur.size >= 4) { lineTo(sx, sy); curClosed = true }
                        "re" -> if (ops.size >= 4) {
                            val o = ops.size - 4
                            val x = num(ops[o]); val y = num(ops[o + 1]); val w = num(ops[o + 2]); val h = num(ops[o + 3])
                            moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); lineTo(x, y); curClosed = true
                            flushSub(); cx = x; cy = y
                        }
                        "S" -> paint(true, false, false)
                        "s" -> paint(true, false, true)
                        "f", "F", "f*" -> paint(false, true, true)
                        "B", "B*" -> paint(true, true, true)
                        "b", "b*" -> paint(true, true, true)
                        "n" -> { flushSub(); rings.clear(); closed.clear() }
                        "BDC" -> {
                            val tag = ops.getOrNull(ops.size - 2) as? Name
                            val prop = ops.lastOrNull()
                            val name = if (tag?.s == "OC") layerName(prop, res) else null
                            layers += name ?: (layers.lastOrNull() ?: "")
                        }
                        "BMC" -> layers += layers.lastOrNull() ?: ""
                        "EMC" -> if (layers.isNotEmpty()) layers.removeAt(layers.size - 1)
                        "Do" -> if (depth < 12) (ops.lastOrNull() as? Name)?.let { nm ->
                            val xo = r.dictOf(res?.get("XObject"))?.get(nm.s)
                            val s = r.resolve(xo) as? Stream
                            if (s != null && (s.dict["Subtype"] as? Name)?.s == "Form") {
                                val mm = (r.resolve(s.dict["Matrix"]) as? List<*>)?.map { (r.resolve(it) as? Double)?.toFloat() ?: 0f }
                                val m = if (mm != null && mm.size == 6) mul(mm.toFloatArray(), gs.ctm) else gs.ctm
                                val fres = r.dictOf(s.dict["Resources"]) ?: res
                                val save = layers.size
                                val inner = Run(r, box, rot)
                                inner.exec(r.decode(s) ?: ByteArray(0), fres, m, depth + 1)
                                val lay = layers.lastOrNull()
                                for (p in inner.out) out += if (lay != null && p.layer.isEmpty()) Path(lay, p.width, p.stroke, p.fill, p.white, p.rings, p.closed) else p
                                while (layers.size > save) layers.removeAt(layers.size - 1)
                            }
                        }
                        "BI" -> skipInlineImage(lx)
                    }
                } catch (_: Throwable) { }
                ops.clear()
            }
        }

        /** Слой по /OC: имя группы (OCG) или первой группы набора (OCMD). */
        private fun layerName(prop: Any?, res: Map<String, Any?>?): String? {
            val d = when (prop) {
                is Name -> r.dictOf(r.dictOf(res?.get("Properties"))?.get(prop.s))
                is Map<*, *> -> r.dictOf(prop)
                else -> null
            } ?: return null
            val ocg = if ((d["Type"] as? Name)?.s == "OCMD") {
                when (val g = r.resolve(d["OCGs"])) { is List<*> -> r.dictOf(g.firstOrNull()); else -> r.dictOf(g) }
            } else d
            val n = r.resolve(ocg?.get("Name")) as? PStr ?: return null
            return text(n.b)
        }

        private fun skipInlineImage(lx: Lex) {
            // словарь до ID, потом данные до « EI »
            while (true) {
                val t = lx.token() ?: return
                if (t is Op && t.s == "ID") break
            }
            val d = lx.d
            var i = lx.p + 1
            while (i + 2 < d.size) {
                if (d[i].toInt() == 'E'.code && d[i + 1].toInt() == 'I'.code && lx.ws(d[i - 1].toInt() and 0xFF) &&
                    (i + 2 >= d.size || lx.ws(d[i + 2].toInt() and 0xFF))) { lx.p = i + 2; return }
                i++
            }
            lx.p = d.size
        }
    }

    /** Текст PDF: UTF-16BE с меткой или однобайтный. */
    private fun text(b: ByteArray): String =
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) String(b, 3, b.size - 3, Charsets.UTF_8)
        else String(b, Charsets.ISO_8859_1)

    private class FloatArrayBuilder {
        private var a = FloatArray(16)
        var size = 0
            private set
        fun add(x: Float, y: Float) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x; a[size++] = y
        }
        fun toArray(): FloatArray = a.copyOf(size)
    }
}
