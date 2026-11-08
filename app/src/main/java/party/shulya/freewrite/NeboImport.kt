package party.shulya.freewrite

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Импорт доски из MyScript Nebo / Notes (`.nebo`) — задача FW-007.
 *
 * Формат закрытый, разобран по байтам — описание в `docs/NEBO-ФОРМАТ.md`, образец на Python — `tools/nebo/`.
 * Переносится то, что видно на доске Nebo: все штрихи пера (цвет, толщина, сдвиги), выровненные линии и
 * дуги со стрелками, картинки на своих местах. Распознанный Nebo текст не нужен — на доске остаётся рукопись.
 *
 * Координаты Nebo — в миллиметрах; на доске FreeWrite 1 мм = [U] единиц.
 */
object NeboImport {

    /** Единиц доски FreeWrite на 1 мм доски Nebo. */
    const val U = 4f

    /** Шаг разностей в сжатых штрихах, мм (подобран по PDF-экспорту той же доски: отклонение 0). */
    private const val DELTA = 0.002f

    /** Насколько можно сгладить точки штриха (мм): на глаз не видно, а точек впятеро меньше. */
    private const val SIMPLIFY_MM = 0.02f

    /** Толщина пера Nebo по умолчанию («0.35»), мм. */
    private const val DEFAULT_WIDTH = 0.625f

    // ------------------------------------------------------------------ модель (в мм)

    class Stroke(
        /** Точки x, y подряд, мм. */
        val pts: FloatArray,
        val color: Int,
        val widthMm: Float,
    )

    class Image(val entry: String, val x: Float, val y: Float, val w: Float, val h: Float)

    class Page(val title: String, val strokes: List<Stroke>, val images: List<Image>)

    class Result(val boardIds: List<String>, val title: String, val strokes: Int, val images: Int, val pages: Int) {
        fun message(): String = buildString {
            append("Доска «$title» перенесена из Nebo: ")
            append(Ui.plural(strokes, "штрих", "штриха", "штрихов"))
            if (images > 0) append(", ").append(Ui.plural(images, "картинка", "картинки", "картинок"))
            append('.')
            if (pages > 1) append(" Страниц в файле — $pages, каждая — отдельной доской.")
        }
    }

    // ------------------------------------------------------------------ проверка файла

    /** Это файл Nebo: zip со страницами `pages/<id>/page.bdom` и `ink.bink`. */
    fun isNebo(file: File): Boolean = runCatching {
        ZipFile(file).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            names.any { it.startsWith("pages/") && it.endsWith("/page.bdom") } &&
                names.any { it.startsWith("pages/") && it.endsWith("/ink.bink") }
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------------ чтение

    /** Прочитать все страницы файла (без Android — годится и для проверок на компьютере). */
    fun read(file: File): List<Page> = ZipFile(file).use { z ->
        val pageIds = z.entries().asSequence().map { it.name }
            .filter { it.startsWith("pages/") && it.endsWith("/page.bdom") }
            .map { it.removePrefix("pages/").removeSuffix("/page.bdom") }
            .toList()
        // Порядок страниц — как в index.bdom не разбираем: по rel.json, иначе по имени.
        val order = runCatching {
            val rel = JSONObject(String(z.getInputStream(z.getEntry("rel.json")).readBytes()))
            rel.optJSONObject("pages")?.keys()?.asSequence()?.toList()
        }.getOrNull().orEmpty()
        val ids = (order.filter { it in pageIds } + pageIds.filter { it !in order }.sorted())
        ids.map { id ->
            val bdom = z.getInputStream(z.getEntry("pages/$id/page.bdom")).readBytes()
            val bink = z.getInputStream(z.getEntry("pages/$id/ink.bink")).readBytes()
            val title = runCatching {
                JSONObject(String(z.getInputStream(z.getEntry("pages/$id/meta.json")).readBytes())).optString("pageTitle")
            }.getOrNull().orEmpty()
            readPage(title, bdom, bink)
        }
    }

    fun readPage(title: String, bdomBytes: ByteArray, binkBytes: ByteArray): Page {
        val root = Bdom.parse(bdomBytes)
        val ink = Bink.parse(binkBytes)

        // Стили по меткам: цвет (.STYLE), толщина (pen-025/035/070), заглушки картинок (не рисовать).
        val color = HashMap<Int, Int>()
        val width = HashMap<Int, Float>()
        val fake = HashSet<Int>()
        val colorRe = Regex("color:\\s*#([0-9a-fA-F]{8})")
        val penRe = Regex("pen-(\\d{3})")
        val byId = HashMap<Long, Bink.Tag>()
        for (t in ink.tags) {
            byId[t.id] = t
            when {
                t.name == ".STYLE" -> colorRe.find(t.payload)?.let { m ->
                    val c = cssColor(m.groupValues[1])
                    for (r in t.ranges) for (s in r.s1..r.s2) color[s] = c
                }
                penRe.matches(t.name) -> {
                    val w = t.name.substring(4).toInt() / 100f * DEFAULT_WIDTH / 0.35f
                    for (r in t.ranges) for (s in r.s1..r.s2) width[s] = w
                }
                t.name == "imageFakeContent" -> for (r in t.ranges) for (s in r.s1..r.s2) fake += s
            }
        }
        // Сдвиги групп штрихов (передвинутые надписи): у штриха — не больше одного.
        val mat = HashMap<Int, DoubleArray>()
        for (tr in ink.transforms) for (r in tr.ranges) for (s in r.s1..r.s2) mat[s] = tr.m

        // Наконечники стрелок у выровненных дуг и линий: фигура в page.bdom ссылается на метку штрихов (tag).
        val arrows = HashMap<Int, Pair<Boolean, Boolean>>() // штрих → (стрелка в начале, в конце)
        root.walk { n ->
            if (n.name == "decoratedEllipticArc" || n.name == "decoratedLine") {
                val first = (n.attrs["firstDecoration"] ?: n.attrs["p1Decoration"]) == "arrow-head"
                val last = (n.attrs["lastDecoration"] ?: n.attrs["p2Decoration"]) == "arrow-head"
                val tag = byId[(n.attrs["tag"] as? Number)?.toLong() ?: -1L]
                if ((first || last) && tag != null) for (r in tag.ranges) for (s in r.s1..r.s2) arrows[s] = first to last
            }
        }

        val layout = root.children.firstOrNull { it.name == "layout" } ?: return Page(title, emptyList(), emptyList())
        val layoutTransforms = HashMap<String, DoubleArray>()
        layout.children.firstOrNull { it.name == "transforms" }?.children?.forEach { t ->
            val id = t.attrs["id"] as? String ?: return@forEach
            layoutTransforms[id] = DoubleArray(6) { k -> t.d("abcdef"[k].toString()) }
        }

        val strokes = ArrayList<Stroke>()
        val images = ArrayList<Image>()
        layout.walk { n ->
            when (n.name) {
                "object" -> {
                    val url = n.attrs["url"] as? String ?: return@walk
                    val m = layoutTransforms[n.attrs["transform"] as? String] ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
                    val x = n.f("x"); val y = n.f("y"); val w = n.f("width"); val h = n.f("height")
                    // Картинка — её прямоугольник через матрицу (рамка, если вдруг с поворотом).
                    val xs = floatArrayOf(x, x + w, x, x + w); val ys = floatArrayOf(y, y, y + h, y + h)
                    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                    for (i in 0..3) {
                        val px = (m[0] * xs[i] + m[1] * ys[i] + m[2]).toFloat()
                        val py = (m[3] * xs[i] + m[4] * ys[i] + m[5]).toFloat()
                        l = min(l, px); r = max(r, px); t = min(t, py); b = max(b, py)
                    }
                    if (r > l && b > t) images += Image("objects/$url", l, t, r - l, b - t)
                }
                "strokes" -> {
                    val tag = byId[(n.attrs["tag"] as? Number)?.toLong() ?: return@walk] ?: return@walk
                    for (r in tag.ranges) for (s in r.s1..r.s2) {
                        if (s in fake) continue
                        val rec = ink.strokes[s] ?: continue
                        val c = color[s] ?: 0xFF000000.toInt()
                        val w = width[s] ?: DEFAULT_WIDTH
                        // Видимая часть: диапазон может начинаться и кончаться посреди штриха (стёртое).
                        val from = if (s == r.s1) r.p1 else 0
                        val to = if (s == r.s2 && !r.toEnd) r.p2 - 1 else Int.MAX_VALUE
                        var pts = rec.points(from, to) ?: continue
                        val m = mat[s]
                        if (m != null) pts = transform(pts, m)
                        strokes += Stroke(simplify(pts, SIMPLIFY_MM), c, w)
                        // Стрелки у выровненных фигур (уже на месте: после сдвига).
                        if (rec.kind != Bink.Kind.INK) arrows[s]?.let { (first, last) ->
                            if (last) arrowHead(pts, atEnd = true, w)?.let { strokes += Stroke(it, c, w) }
                            if (first) arrowHead(pts, atEnd = false, w)?.let { strokes += Stroke(it, c, w) }
                        }
                    }
                }
            }
        }
        return Page(title, strokes, images)
    }

    private fun cssColor(rrggbbaa: String): Int {
        val v = rrggbbaa.toLong(16)
        val r = (v shr 24) and 0xFF; val g = (v shr 16) and 0xFF; val b = (v shr 8) and 0xFF; val a = v and 0xFF
        return ((a shl 24) or (r shl 16) or (g shl 8) or b).toInt()
    }

    private fun transform(p: FloatArray, m: DoubleArray): FloatArray {
        val out = FloatArray(p.size)
        var i = 0
        while (i + 1 < p.size) {
            val x = p[i].toDouble(); val y = p[i + 1].toDouble()
            out[i] = (m[0] * x + m[1] * y + m[2]).toFloat()
            out[i + 1] = (m[3] * x + m[4] * y + m[5]).toFloat()
            i += 2
        }
        return out
    }

    /** Наконечник «галочкой»: усы ~8 толщин пера (у Nebo — около 5 мм) под 30° к линии. */
    private fun arrowHead(p: FloatArray, atEnd: Boolean, widthMm: Float): FloatArray? {
        val n = p.size / 2
        if (n < 2) return null
        val tipI = if (atEnd) n - 1 else 0
        val tx = p[tipI * 2]; val ty = p[tipI * 2 + 1]
        // Направление — по точке на ~2 мм от конца (на дуге — касательная).
        var j = if (atEnd) n - 2 else 1
        while (j in 1 until n - 1 && hypot(p[j * 2] - tx, p[j * 2 + 1] - ty) < 2f) j += if (atEnd) -1 else 1
        val bx = p[j * 2] - tx; val by = p[j * 2 + 1] - ty
        val len = hypot(bx, by)
        if (len < 1e-4f) return null
        val ang = atan2(by, bx)
        val l = widthMm * 8f
        val a1 = ang + Math.toRadians(30.0).toFloat(); val a2 = ang - Math.toRadians(30.0).toFloat()
        return floatArrayOf(tx + l * cos(a1), ty + l * sin(a1), tx, ty, tx + l * cos(a2), ty + l * sin(a2))
    }

    /** Упрощение ломаной (Дуглас — Пекер): точки ближе tol к прямой выбрасываются. */
    fun simplify(p: FloatArray, tol: Float): FloatArray {
        val n = p.size / 2
        if (n < 3) return p
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<Int>()
        stack.addLast(0); stack.addLast(n - 1)
        while (stack.isNotEmpty()) {
            val b = stack.removeLast(); val a = stack.removeLast()
            val ax = p[a * 2]; val ay = p[a * 2 + 1]; val dx = p[b * 2] - ax; val dy = p[b * 2 + 1] - ay
            val l = sqrt(dx * dx + dy * dy)
            var best = -1f; var bi = -1
            for (i in a + 1 until b) {
                val px = p[i * 2] - ax; val py = p[i * 2 + 1] - ay
                val d = if (l > 1e-6f) abs(dy * px - dx * py) / l else hypot(px, py)
                if (d > best) { best = d; bi = i }
            }
            if (best > tol && bi > 0) { keep[bi] = true; stack.addLast(a); stack.addLast(bi); stack.addLast(bi); stack.addLast(b) }
        }
        val out = FloatArray(keep.count { it } * 2)
        var k = 0
        for (i in 0 until n) if (keep[i]) { out[k++] = p[i * 2]; out[k++] = p[i * 2 + 1] }
        return out
    }

    // ------------------------------------------------------------------ в доску FreeWrite

    /**
     * Перенести файл Nebo в FreeWrite: каждая страница — новая доска. progress — что сейчас делается, для
     * экрана приёма (картинки — самое долгое).
     */
    fun import(context: Context, file: File, progress: (String) -> Unit = {}): Result {
        progress("Читаю доску Nebo…")
        val pages = read(file)
        require(pages.isNotEmpty()) { "в файле Nebo нет страниц" }
        val store = BoardStore(context)
        val ids = ArrayList<String>()
        var strokes = 0; var images = 0
        val totalImages = pages.sumOf { it.images.size }
        var doneImages = 0
        ZipFile(file).use { z ->
            for ((pi, page) in pages.withIndex()) {
                val title = page.title.ifBlank { if (pages.size > 1) "Nebo, страница ${pi + 1}" else "Доска из Nebo" }
                val b = Board.create(title)
                // Картинки — из архива во временный файл и обычным путём (копии для быстрой доски).
                val tmpDir = File(context.cacheDir, "nebo").apply { mkdirs() }
                for (img in page.images) {
                    doneImages++
                    progress("Картинки: $doneImages из $totalImages…")
                    val e = z.getEntry(img.entry) ?: continue
                    val tmp = File(tmpDir, "img" + System.nanoTime() + ".jpg")
                    try {
                        z.getInputStream(e).use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                        val got = BoardImages.import(context, Uri.fromFile(tmp)) ?: continue
                        b.images += BoardImage(got.first, img.x * U, img.y * U, img.w * U, img.h * U, got.second, got.third)
                        images++
                    } finally {
                        tmp.delete()
                    }
                }
                progress("Штрихи: ${page.strokes.size}…")
                for (s in page.strokes) {
                    val n = s.pts.size / 2
                    if (n == 0) continue
                    val pts = FloatArray(n * 3)
                    for (i in 0 until n) {
                        pts[i * 3] = s.pts[i * 2] * U
                        pts[i * 3 + 1] = s.pts[i * 2 + 1] * U
                        pts[i * 3 + 2] = 0.5f // ровная толщина, как перо Nebo (нажим не меняет толщину)
                    }
                    b.strokes += Drawing("pen", s.color, s.widthMm * U, pts)
                    strokes++
                }
                fitCamera(b)
                progress("Сохраняю доску…")
                store.save(b)
                ids += b.id
            }
        }
        return Result(ids, pages[0].title.ifBlank { "Доска из Nebo" }, strokes, images, pages.size)
    }

    /** Камера — на всю доску (экран планшета ~1600×2400). Доска откроется целиком. */
    private fun fitCamera(b: Board) {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bt = -Float.MAX_VALUE
        for (m in b.images) { l = min(l, m.x); t = min(t, m.y); r = max(r, m.x + m.w); bt = max(bt, m.y + m.h) }
        for (d in b.strokes) { val bb = d.bounds(); l = min(l, bb.left); t = min(t, bb.top); r = max(r, bb.right); bt = max(bt, bb.bottom) }
        if (r <= l || bt <= t) return
        val z = min(1500f / (r - l), 2300f / (bt - t)).coerceIn(BoardView.MIN_ZOOM, 2f)
        b.camZ = z
        b.camX = (l + r) / 2f - 800f / z
        b.camY = (t + bt) / 2f - 1200f / z
    }

    // ------------------------------------------------------------------ page.bdom

    /** Двоичное дерево страницы (как XML): таблица строк, элементы с атрибутами, текст. */
    class Node(val name: String) {
        val attrs = HashMap<String, Any?>()
        val children = ArrayList<Node>()

        fun f(key: String): Float = (attrs[key] as? Number)?.toFloat() ?: 0f
        /** Число, записанное как u64 с битами double (матрицы в layout). */
        fun d(key: String): Double = when (val v = attrs[key]) {
            is Long -> java.lang.Double.longBitsToDouble(v)
            is Number -> v.toDouble()
            else -> 0.0
        }

        fun walk(f: (Node) -> Unit) {
            f(this)
            for (c in children) c.walk(f)
        }
    }

    object Bdom {
        fun parse(b: ByteArray): Node {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            require(b.size > 10 && String(b, 0, 4, Charsets.ISO_8859_1) == "BDOM") { "это не файл страницы Nebo" }
            buf.position(6)
            val strs = ArrayList<String>()
            while (true) {
                val n = buf.int
                if (n == 0xFF) break
                strs += str(buf, n)
            }
            return element(buf, strs)
        }

        private fun str(buf: ByteBuffer, n: Int): String {
            val a = ByteArray(n); buf.get(a); return String(a, Charsets.UTF_8)
        }

        private fun rstr(buf: ByteBuffer): String? {
            val n = buf.int
            return if (n == 0xFF) null else str(buf, n)
        }

        private fun element(buf: ByteBuffer, strs: List<String>): Node {
            var k = buf.get().toInt() and 0xFF
            val ns = k == 4
            if (ns) k = buf.get().toInt() and 0xFF
            require(k == 3) { "страница Nebo: неизвестный элемент $k" }
            val node = Node(strs[buf.int])
            if (ns) { rstr(buf); rstr(buf) }
            rstr(buf) // пространство имён элемента (0xFF — как у родителя)
            while (true) {
                val t = buf.get().toInt() and 0xFF
                if (t == 0xFF) break
                require(t == 0) { "страница Nebo: неизвестный атрибут $t" }
                val name = strs[buf.int]
                node.attrs[name] = value(buf, strs, buf.get().toInt() and 0xFF)
            }
            while (true) {
                val c = buf.get(buf.position()).toInt() and 0xFF
                if (c == 0xFF) { buf.get(); break }
                when (c) {
                    3, 4 -> node.children += element(buf, strs)
                    1, 2 -> { buf.get(); rstr(buf) } // текст (распознанное) — не нужен
                    else -> error("страница Nebo: неизвестный узел $c")
                }
            }
            return node
        }

        private fun value(buf: ByteBuffer, strs: List<String>, t: Int): Any? = when (t) {
            1, 2 -> rstr(buf)
            3 -> strs[buf.int]
            4 -> buf.long
            5 -> buf.long // u64: в матрицах это биты double
            6 -> buf.get().toInt() != 0
            7 -> buf.int.toLong()
            8 -> buf.int.toLong() and 0xFFFFFFFFL
            9 -> buf.double
            10 -> buf.float.toDouble()
            else -> error("страница Nebo: неизвестный тип значения $t")
        }
    }

    // ------------------------------------------------------------------ ink.bink

    object Bink {
        enum class Kind { INK, LINE, ARC }

        /** Диапазон штрихов: от (s1, точка p1) до (s2, точка p2 — не включая) или до конца s2 (toEnd). */
        class Range(val s1: Int, val p1: Int, val s2: Int, val p2: Int, val toEnd: Boolean)

        class Tag(val name: String, val ranges: List<Range>, val payload: String, val id: Long)

        class Transform(val ranges: List<Range>, val m: DoubleArray)

        class Rec(val kind: Kind, val xy: FloatArray, val geom: FloatArray) {
            /** Точки x, y (мм) с from по to включительно (для фигур — вся фигура). */
            fun points(from: Int, to: Int): FloatArray? = when (kind) {
                Kind.INK -> {
                    val n = xy.size / 2
                    val a = from.coerceIn(0, n - 1); val b = min(to, n - 1)
                    if (b < a) null else xy.copyOfRange(a * 2, b * 2 + 2)
                }
                Kind.LINE -> geom.copyOf()
                Kind.ARC -> arcPoints(geom)
            }
        }

        class Ink(val strokes: Map<Int, Rec>, val transforms: List<Transform>, val tags: List<Tag>)

        fun parse(b: ByteArray): Ink {
            require(b.size > 16 && String(b, 0, 4, Charsets.ISO_8859_1) == "BINK") { "это не файл штрихов Nebo" }
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4)
            buf.short; buf.int // версия
            val formats = buf.int
            repeat(formats) {
                val nch = buf.int
                repeat(nch) {
                    skipStr(buf)        // имя канала (X, Y, F, T)
                    buf.int             // тип
                    if (buf.int != 0) skipStr(buf) // единица (mm, ms)
                }
                val layout = buf.int
                buf.position(buf.position() + layout + 13)
            }
            val slots = buf.int
            val strokes = HashMap<Int, Rec>(slots)
            var slot = 0
            while (slot < slots) {
                if (b[buf.position()] == 0xFF.toByte() && buf.getInt(buf.position()) == -1) {
                    buf.int; slot++; continue // удалённый штрих
                }
                val flags = buf.int
                buf.long // время, мкс
                when {
                    flags and 0x80000000.toInt() != 0 -> {
                        // Сжатый: начало и разности по 0,002 мм.
                        val x0 = buf.float; val y0 = buf.float
                        buf.short // промежуток между точками, мкс
                        buf.int   // формат
                        val n = buf.int
                        require(n in 1..2_000_000) { "штрихи Nebo: странное число точек $n" }
                        val xy = FloatArray(n * 2)
                        var x = x0; var y = y0
                        val base = buf.position()
                        for (i in 0 until n) {
                            x += buf.getShort(base + i * 2) * DELTA
                            y += buf.getShort(base + n * 2 + i * 2) * DELTA
                            xy[i * 2] = x; xy[i * 2 + 1] = y
                        }
                        buf.position(base + n * 5) // dx, dy (i16) и нажим (u8)
                        strokes[slot] = Rec(Kind.INK, xy, FloatArray(0))
                    }
                    flags == 1 -> {
                        // Выровненная фигура: линия (3111) или дуга (3110).
                        val fmt = buf.int; buf.int; buf.int
                        val nf = when (fmt) { 3111 -> 4; 3110 -> 7; else -> error("штрихи Nebo: неизвестная фигура $fmt") }
                        val g = FloatArray(nf) { buf.float }
                        buf.int
                        strokes[slot] = Rec(if (fmt == 3111) Kind.LINE else Kind.ARC, FloatArray(0), g)
                    }
                    else -> {
                        // Как есть: x, y (float), нажим (float), время (u32).
                        buf.int // формат
                        val n = buf.int
                        require(n in 1..2_000_000) { "штрихи Nebo: странное число точек $n" }
                        val xy = FloatArray(n * 2) { buf.float }
                        buf.position(buf.position() + n * 8)
                        strokes[slot] = Rec(Kind.INK, xy, FloatArray(0))
                    }
                }
                slot++
            }
            // Сдвиги групп штрихов: u32 n, n диапазонов, матрица 6 × f64; конец — n = 0.
            val transforms = ArrayList<Transform>()
            while (buf.remaining() >= 4) {
                val n = buf.int
                if (n == 0) break
                val rs = List(n) { range(buf) }
                val m = DoubleArray(6) { buf.double }
                transforms += Transform(rs, m)
            }
            // Метки: число, байт 0, затем каждая: имя, диапазоны, значение, вид, номер, 0.
            val tags = ArrayList<Tag>()
            if (buf.remaining() >= 5) {
                val count = buf.int
                buf.get()
                for (i in 0 until count) {
                    if (buf.remaining() < 8) break
                    val name = readStr(buf)
                    val n = buf.int
                    val rs = List(n) { range(buf) }
                    val payload = readStr(buf)
                    buf.int
                    val id = buf.int.toLong() and 0xFFFFFFFFL
                    buf.int
                    tags += Tag(name, rs, payload, id)
                }
            }
            return Ink(strokes, transforms, tags)
        }

        private fun range(buf: ByteBuffer): Range {
            val a = buf.int
            val s1 = buf.int
            val flag = buf.short.toInt() and 0xFFFF
            val p2 = buf.short.toInt() and 0xFFFF
            val s2 = buf.int
            // 0xFF05 — до конца штриха s2; 1 — до точки p2 (следующий диапазон начнётся с неё же).
            return Range(s1, a ushr 16, s2, p2, toEnd = flag != 1)
        }

        private fun skipStr(buf: ByteBuffer) { val n = buf.int; buf.position(buf.position() + n) }

        private fun readStr(buf: ByteBuffer): String {
            val n = buf.int
            val a = ByteArray(n); buf.get(a)
            return String(a, Charsets.UTF_8)
        }

        /** Дуга эллипса (cx, cy, rx, ry, phi, начало, угол) — ломаной через ~1 мм. */
        fun arcPoints(g: FloatArray): FloatArray {
            val cx = g[0]; val cy = g[1]; val rx = g[2]; val ry = g[3]; val phi = g[4]; val a0 = g[5]; val sw = g[6]
            val m = max(8, (abs(sw) * max(rx, ry)).toInt())
            val out = FloatArray((m + 1) * 2)
            val cp = cos(phi); val sp = sin(phi)
            for (i in 0..m) {
                val a = a0 + sw * i / m
                val x = rx * cos(a); val y = ry * sin(a)
                out[i * 2] = cx + x * cp - y * sp
                out[i * 2 + 1] = cy + x * sp + y * cp
            }
            return out
        }
    }
}
