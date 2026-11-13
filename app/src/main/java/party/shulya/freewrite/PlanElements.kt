package party.shulya.freewrite

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Поиск элементов на листе плана по линиям PDF (0.26, Владимир: «размечаю этаж заранее, потом только отмечаю, что залили»).
 *
 * Правила (проверены на КЖ 7.1.3 и КЖ 7.2.2 Владимира):
 *  - несущие стены и пилоны — замкнутые жирные контуры слоя стен (+ чёрные заливки — «Р.Ш.Б.» части пилонов); в проёмах
 *    стена на чертеже обрывается — две параллельные линии, оборванные напротив друг друга, замыкаются поперёк;
 *  - ненесущие — тонкие контуры того же слоя со штриховкой внутри (без несущих);
 *  - плита — внешний край слоя плиты, минус отверстия (маленькие замкнутые контуры слоя плиты; проёмы, помеченные
 *    залитым треугольником), режется по швам-зигзагам чертежа (продлённым до края плиты).
 * Текст, размеры, оси, выноски, невидимые линии и проёмы (по названию слоя) в поиск не идут.
 *
 * Считается растром (пиксели — квадратные, лист до [MAX_SIDE] по большей стороне), контуры — обход пикселей + упрощение.
 * Результат — в долях картинки листа целиком (как у областей захваток).
 */
object PlanElements {
    const val BEARING = 0
    const val NONBEARING = 1
    const val SLAB = 2
    const val MAX_SIDE = 3200

    enum class Role { CUT, HATCH, SLAB, OTHER, ANNOT, HIDDEN, OPENING }

    private val R_ANNOT = Regex("DIM|РАЗМ|GRID|ОСИ|ОСЬ|IDEN|TEXT|ТЕКСТ|ANNO|NOTE|ПОДП|SYMB|ВЫНОС|ОТМЕТ|TITL|ШТАМП|FRAME|РАМК|LEAD|MARK|ТАБЛ", RegexOption.IGNORE_CASE)
    private val R_HIDDEN = Regex("HDLN|HIDD|НЕВИД|BELOW|ABOVE|OVHD", RegexOption.IGNORE_CASE)
    private val R_OPEN = Regex("DOOR|OPNG|WIND|ПРО[ЕЁ]М|ОКН|ДВЕР", RegexOption.IGNORE_CASE)
    private val R_HATCH = Regex("PATT|HATCH|ШТРИХ", RegexOption.IGNORE_CASE)
    private val R_SLAB = Regex("FLOR|SLAB|ПЛИТ|ПЕРЕКР|DECK|ПОКРЫТ", RegexOption.IGNORE_CASE)
    private val R_CUT = Regex("WALL|COLM|COLUMN|СТЕН|ПИЛОН|КОЛОН|MCUT|КОНСТР|БЕТОН|CONC", RegexOption.IGNORE_CASE)

    /** Роль слоя по названию (AutoCAD: «A-WALL-____-MCUT» — контур стены, «…-PATT» — штриховка, «…-DIMS» — размеры). */
    fun roleOf(name: String): Role = when {
        name.isEmpty() -> Role.OTHER
        R_ANNOT.containsMatchIn(name) -> Role.ANNOT
        R_HIDDEN.containsMatchIn(name) -> Role.HIDDEN
        R_OPEN.containsMatchIn(name) -> Role.OPENING
        R_HATCH.containsMatchIn(name) -> Role.HATCH
        R_SLAB.containsMatchIn(name) -> Role.SLAB
        R_CUT.containsMatchIn(name) -> Role.CUT
        else -> Role.OTHER
    }

    /** Слой для окна «Слои»: название, сколько линий, роль, участвует ли по умолчанию. */
    class Layer(val name: String, val count: Int, val role: Role) {
        val auto get() = role != Role.ANNOT && role != Role.HIDDEN && role != Role.OPENING
    }

    fun layers(page: PdfVector.Page): List<Layer> =
        page.layers.map { (n, c) -> Layer(n, c, roleOf(n)) }.sortedWith(compareBy({ it.role.ordinal }, { it.name }))

    /** Найденный элемент: внешний контур и дыры (пары x, y — доли картинки листа). */
    class Element(val outer: FloatArray, val holes: List<FloatArray> = emptyList())

    /** Итог поиска: элементы, швы (отрезки x0, y0, x1, y1 — доли), что сказать человеку. */
    class Found(val elements: List<Element>, val seams: List<FloatArray>, val message: String)

    // ---------------------------------------------------------------- поиск

    /**
     * Найти элементы листа. [crop] — видимая часть листа (доли: l, t, r, b); [use] — свои галочки слоёв (имя → участвует),
     * остальные — по названию.
     */
    fun find(page: PdfVector.Page, mode: Int, crop: FloatArray, use: Map<String, Boolean> = emptyMap(), maxSide: Int = MAX_SIDE): Found {
        val roles = HashMap<String, Role?>()
        for (n in page.layers.keys) {
            val r = roleOf(n)
            roles[n] = when (use[n]) {
                false -> null
                true -> if (r == Role.ANNOT || r == Role.HIDDEN || r == Role.OPENING) Role.OTHER else r
                else -> if (r == Role.ANNOT || r == Role.HIDDEN || r == Role.OPENING) null else r
            }
        }
        val x0 = crop[0] * page.w; val y0 = crop[1] * page.h
        val x1 = crop[2] * page.w; val y1 = crop[3] * page.h
        val k = min(maxSide / max(x1 - x0, y1 - y0), 8f)
        val ras = Ras(max(1, ceil((x1 - x0) * k).toInt()), max(1, ceil((y1 - y0) * k).toInt()), k, x0, y0)
        val paths = page.paths.filter { roles[it.layer] != null && inBox(it, x0, y0, x1, y1) }
        val hasCut = paths.any { roles[it.layer] == Role.CUT }
        val cutPaths = if (hasCut) paths.filter { roles[it.layer] == Role.CUT } else paths.filter { roles[it.layer] != Role.HATCH }
        val res = when (mode) {
            SLAB -> slab(page, ras, paths, roles)
            else -> walls(page, ras, paths, cutPaths, roles, mode == NONBEARING)
        }
        return res
    }

    private fun inBox(p: PdfVector.Path, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        for (r in p.rings) {
            var i = 0
            while (i + 1 < r.size) { if (r[i] >= x0 && r[i] <= x1 && r[i + 1] >= y0 && r[i + 1] <= y1) return true; i += 2 }
        }
        return false
    }

    // ---- стены

    private fun walls(page: PdfVector.Page, ras: Ras, paths: List<PdfVector.Path>, cut: List<PdfVector.Path>,
                      roles: Map<String, Role?>, nonBearing: Boolean): Found {
        val w = ras.w; val h = ras.h
        val strokes = cut.filter { it.stroke }
        if (strokes.isEmpty()) return Found(emptyList(), emptyList(), "На листе не нашлось контуров стен — проверьте слои («Слои…»)")
        val maxW = strokes.maxOf { it.width }
        val boldMin = max(0.25f, 0.55f * maxW)
        val bold = strokes.filter { it.width >= boldMin }
        val boldRings = bold.flatMap { it.rings }
        // Несущие: жирные контуры + заглушки в проёмах + чёрные заливки.
        val m = BooleanArray(w * h)
        for (p in bold) for (r in p.rings) ras.polyline(m, r, max(1f, p.width * ras.k / 2f))
        for (s in seal(boldRings)) ras.line(m, s[0], s[1], s[2], s[3], max(1f, maxW * ras.k / 2f))
        for (p in paths) if (p.fill && !p.white) ras.poly(m, p.rings)
        val closed = MagicFill.erode(MagicFill.dilate(m, w, h, 1), w, h, 1)
        val filled = fillHoles(closed, w, h)
        val minArea = max(12f, (1.5f * ras.k) * (1.5f * ras.k)).toInt()
        val thinR = max(1, (0.6f * ras.k).roundToInt()).coerceAtMost(3)
        val bearing = BooleanArray(w * h)
        val out = ArrayList<Element>()
        components(filled, w, h) { px, n, box ->
            if (n < minArea) return@components
            val sub = Sub(px, n, w, box)
            if (sub.solidRatio(thinR) < 0.15f) return@components
            for (i in 0 until n) bearing[px[i]] = true
            if (!nonBearing) sub.element(ras, page)?.let { out += it }
        }
        if (!nonBearing) {
            return Found(out, emptyList(), if (out.isEmpty()) "Несущих стен не нашлось — проверьте слои («Слои…»)" else "Найдено: ${Ui.plural(out.size, "элемент", "элемента", "элементов")}")
        }
        // Ненесущие: все контуры слоя стен (и тонкие), минус несущие, со штриховкой внутри.
        val m2 = BooleanArray(w * h)
        val allRings = strokes.flatMap { it.rings }
        for (p in strokes) for (r in p.rings) ras.polyline(m2, r, max(1f, p.width * ras.k / 2f))
        for (s in seal(allRings)) ras.line(m2, s[0], s[1], s[2], s[3], 1f)
        val f2 = fillHoles(MagicFill.erode(MagicFill.dilate(m2, w, h, 1), w, h, 1), w, h)
        val near = MagicFill.dilate(bearing, w, h, 3)
        for (i in f2.indices) if (near[i]) f2[i] = false
        val opened = MagicFill.dilate(MagicFill.erode(f2, w, h, 2), w, h, 2)
        for (i in opened.indices) if (!f2[i]) opened[i] = false
        val hatchPaths = paths.filter { roles[it.layer] == Role.HATCH && it.stroke }
        val hatch = BooleanArray(w * h)
        for (p in hatchPaths) for (r in p.rings) ras.polyline(hatch, r, 0.71f)
        components(opened, w, h) { px, n, box ->
            if (n < minArea) return@components
            if (hatchPaths.isNotEmpty()) {
                var c = 0
                for (i in 0 until n) if (hatch[px[i]]) c++
                if (c < 0.03f * n) return@components
            }
            Sub(px, n, w, box).element(ras, page)?.let { out += it }
        }
        return Found(out, emptyList(), if (out.isEmpty()) "Ненесущих стен не нашлось — проверьте слои («Слои…»)" else "Найдено: ${Ui.plural(out.size, "элемент", "элемента", "элементов")}")
    }

    // ---- плита

    private fun slab(page: PdfVector.Page, ras: Ras, paths: List<PdfVector.Path>, roles: Map<String, Role?>): Found {
        val w = ras.w; val h = ras.h
        val hasSlab = paths.any { roles[it.layer] == Role.SLAB }
        val edgeRoles = if (hasSlab) setOf(Role.SLAB, Role.HATCH, Role.CUT) else setOf(Role.SLAB, Role.HATCH, Role.CUT, Role.OTHER)
        val b = BooleanArray(w * h)
        for (p in paths) if (p.stroke && roles[p.layer] in edgeRoles) for (r in p.rings) ras.polyline(b, r, 1f)
        val filled = fillHoles(MagicFill.erode(MagicFill.dilate(b, w, h, 1), w, h, 1), w, h)
        // Плита — самый большой кусок.
        var best: IntArray? = null; var bestN = 0
        components(filled, w, h) { px, n, _ -> if (n > bestN) { bestN = n; best = px.copyOf(n) } }
        if (best == null || bestN < 0.03f * w * h) return Found(emptyList(), emptyList(), "Контур плиты не найден — проверьте слои («Слои…»)")
        val s = BooleanArray(w * h)
        for (i in best!!) s[i] = true
        val area = bestN
        // Внешний контур плиты (с отверстиями внутри) — для продления швов сквозь проёмы и шахты.
        val outerMask = s.copyOf()
        // Отверстия: маленькие замкнутые контуры слоя плиты.
        val holes = BooleanArray(w * h)
        for (p in paths) if (p.stroke && roles[p.layer] == Role.SLAB) for ((ri, r) in p.rings.withIndex()) {
            if (!p.closed[ri] || r.size < 8) continue
            val a = abs(polyArea(r)) * ras.k * ras.k
            if (a < 0.05f * area && a > 4f) ras.poly(holes, listOf(r))
        }
        for (i in s.indices) if (holes[i]) s[i] = false
        // Проёмы со значком — залитый треугольник внутри небольшой замкнутой области.
        val all = BooleanArray(w * h)
        for (p in paths) if (p.stroke) for (r in p.rings) ras.polyline(all, r, 1f)
        val seen = BooleanArray(w * h)
        val tri = FloatArray(2)
        for (p in paths) {
            if (!p.fill || p.white) continue
            for (r in p.rings) {
                val n = r.size / 2 - if (r.size >= 8 && abs(r[0] - r[r.size - 2]) < 0.05f && abs(r[1] - r[r.size - 1]) < 0.05f) 1 else 0
                if (n != 3) continue
                tri[0] = (r[0] + r[2] + r[4]) / 3f; tri[1] = (r[1] + r[3] + r[5]) / 3f
                val cx = ras.px(tri[0]).toInt(); val cy = ras.py(tri[1]).toInt()
                if (cx !in 0 until w || cy !in 0 until h) continue
                val i0 = cy * w + cx
                if (!s[i0] || all[i0] || seen[i0]) continue
                val reg = flood(w, h, i0, seen) { !all[it] }
                val triPx = abs(polyArea(r.copyOf(6))) * ras.k * ras.k
                if (reg.size < 0.03f * area && triPx >= 0.08f * reg.size) {
                    for (q in reg) s[q] = false
                    // Значок проёма — прямоугольник с диагональю, залита половина: вторая половина — за длинной стороной.
                    var bi = 0; var bl = -1f
                    for (e in 0 until 3) { val a = e * 2; val b2 = ((e + 1) % 3) * 2; val l2 = hypot(r[b2] - r[a], r[b2 + 1] - r[a + 1]); if (l2 > bl) { bl = l2; bi = e } }
                    val ea = bi * 2; val eb = ((bi + 1) % 3) * 2
                    val mxp = (r[ea] + r[eb]) / 2f; val myp = (r[ea + 1] + r[eb + 1]) / 2f
                    val ox = mxp - tri[0]; val oy = myp - tri[1]; val ol = hypot(ox, oy)
                    if (ol > 1e-3f) {
                        val qx = ras.px(mxp + ox / ol * 3f / ras.k).toInt(); val qy = ras.py(myp + oy / ol * 3f / ras.k).toInt()
                        if (qx in 0 until w && qy in 0 until h) {
                            val other = flood(w, h, qy * w + qx, seen) { !all[it] }
                            if (other.isNotEmpty() && other.size < 0.03f * area && other.size < reg.size * 3) for (q in other) s[q] = false
                        }
                    }
                    // и его край (линии контура проёма)
                    for (q in reg) { val x = q % w; val y = q / w
                        for (dy in -2..2) for (dx in -2..2) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h && all[yy * w + xx]) s[yy * w + xx] = false } }
                }
            }
        }
        // Швы-зигзаги: длинная частая ломаная — по средней линии, продлённая до края плиты.
        val seams = ArrayList<FloatArray>()
        val cut = BooleanArray(w * h)
        for (p in paths) {
            if (!p.stroke) continue
            for (r in p.rings) {
                val sm = zigzag(r) ?: continue
                val ext = extend(ras, outerMask, sm)
                ras.line(cut, ext[0], ext[1], ext[2], ext[3], 1.2f)
                seams += floatArrayOf(ext[0] / page.w, ext[1] / page.h, ext[2] / page.w, ext[3] / page.h)
            }
        }
        for (i in s.indices) if (cut[i]) s[i] = false
        val out = ArrayList<Element>()
        val minPiece = max(16, (0.002f * area).toInt())
        components(s, w, h) { px, n, box -> if (n >= minPiece) Sub(px, n, w, box).element(ras, page)?.let { out += it } }
        val msg = when {
            out.isEmpty() -> "Контур плиты не найден — проверьте слои («Слои…»)"
            seams.isEmpty() -> "Плита найдена. Разделите её на захватки: «Шов» — провести поперёк"
            else -> "Плита найдена и разделена по швам чертежа: ${Ui.plural(out.size, "часть", "части", "частей")}. Проверьте швы"
        }
        return Found(out, seams, msg)
    }

    /** Зигзаг (шов на чертеже): средняя линия x0, y0, x1, y1 в пунктах или null. */
    private fun zigzag(r: FloatArray): FloatArray? {
        val n = r.size / 2
        if (n < 12) return null
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += r[2 * i]; my += r[2 * i + 1] }
        mx /= n; my /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) { val dx = r[2 * i] - mx; val dy = r[2 * i + 1] - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val ang = 0.5 * Math.atan2(2 * sxy, sxx - syy)
        val ux = Math.cos(ang); val uy = Math.sin(ang)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE; var plo = Double.MAX_VALUE; var phi = -Double.MAX_VALUE
        val perp = DoubleArray(n)
        for (i in 0 until n) {
            val dx = r[2 * i] - mx; val dy = r[2 * i + 1] - my
            val a = dx * ux + dy * uy; val b = -dx * uy + dy * ux
            perp[i] = b
            lo = min(lo, a); hi = max(hi, a); plo = min(plo, b); phi = max(phi, b)
        }
        val len = hi - lo; val wid = phi - plo
        if (len < 25.0 || wid > max(5.0, 0.1 * len) || wid < 0.3) return null
        var flips = 0; var last = 0
        val mid = (plo + phi) / 2; val tol = wid * 0.2
        for (b in perp) {
            val sgn = if (b > mid + tol) 1 else if (b < mid - tol) -1 else 0
            if (sgn != 0 && sgn != last) { if (last != 0) flips++; last = sgn }
        }
        if (flips < 6) return null
        val cx = mx - mid * uy; val cy = my + mid * ux
        return floatArrayOf((cx + lo * ux).toFloat(), (cy + lo * uy).toFloat(), (cx + hi * ux).toFloat(), (cy + hi * uy).toFloat())
    }

    /** Продлить шов (пункты) в обе стороны, пока под ним плита. */
    private fun extend(ras: Ras, s: BooleanArray, seg: FloatArray): FloatArray {
        val dx = seg[2] - seg[0]; val dy = seg[3] - seg[1]
        val len = hypot(dx, dy)
        if (len < 1e-3f) return seg
        val ux = dx / len; val uy = dy / len
        val step = 0.5f / ras.k
        fun on(x: Float, y: Float): Boolean {
            val cx = ras.px(x).toInt(); val cy = ras.py(y).toInt()
            for (oy in -2..2) for (ox in -2..2) { val xx = cx + ox; val yy = cy + oy; if (xx in 0 until ras.w && yy in 0 until ras.h && s[yy * ras.w + xx]) return true }
            return false
        }
        var ax = seg[0]; var ay = seg[1]; var bx = seg[2]; var by = seg[3]
        var g = 0
        while (g++ < 20000 && on(ax - ux * step, ay - uy * step)) { ax -= ux * step; ay -= uy * step }
        g = 0
        while (g++ < 20000 && on(bx + ux * step, by + uy * step)) { bx += ux * step; by += uy * step }
        return floatArrayOf(ax - ux * 2 * step, ay - uy * 2 * step, bx + ux * 2 * step, by + uy * 2 * step)
    }

    // ---- ножницы

    /**
     * Разрезать область линией (ножницы, шов): [outer], [holes] и [line] (x0, y0, x1, y1) — доли картинки, [aspect] —
     * высота картинки к ширине. Куски по порядку площади; null — линия область не разделила.
     */
    fun split(outer: FloatArray, holes: List<FloatArray>, aspect: Float, line: FloatArray): List<Element>? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < outer.size) { l = min(l, outer[i]); r = max(r, outer[i]); t = min(t, outer[i + 1] * aspect); b = max(b, outer[i + 1] * aspect); i += 2 }
        val side = max(r - l, b - t)
        if (side <= 0f) return null
        val k = 1400f / side
        val pad = 3f / k
        val ras = Ras(ceil((r - l) * k + 6).toInt(), ceil((b - t) * k + 6).toInt(), k, l - pad, t - pad)
        fun sc(a: FloatArray) = FloatArray(a.size) { if (it % 2 == 0) a[it] else a[it] * aspect }
        val m = BooleanArray(ras.w * ras.h)
        ras.poly(m, listOf(sc(outer)) + holes.map { sc(it) })
        val before = m.count { it }
        val c = BooleanArray(m.size)
        ras.line(c, line[0], line[1] * aspect, line[2], line[3] * aspect, 1.2f)
        for (q in m.indices) if (c[q]) m[q] = false
        val pieces = ArrayList<Pair<Int, Element>>()
        val fake = PdfVector.Page(1f, 1f, emptyList(), emptyMap(), 1)
        components(m, ras.w, ras.h) { px, n, box ->
            if (n < max(6, before / 2000)) return@components
            Sub(px, n, ras.w, box).element(ras, fake)?.let { e ->
                fun un(a: FloatArray) = FloatArray(a.size) { if (it % 2 == 0) a[it] else a[it] / aspect }
                pieces += n to Element(un(e.outer), e.holes.map { un(it) })
            }
        }
        if (pieces.size < 2) return null
        return pieces.sortedByDescending { it.first }.map { it.second }
    }

    // ---------------------------------------------------------------- заглушки в проёмах

    /**
     * Свободные концы ломаных напротив друг друга (две линии стены, оборванные в проёме) — отрезки-заглушки
     * x0, y0, x1, y1 (пункты). Поперёк стены, линии параллельны и смотрят в одну сторону, ближе [dmax].
     */
    fun seal(rings: List<FloatArray>, eps: Float = 0.6f, dmax: Float = 9f, gapMax: Float = 40f): List<FloatArray> {
        // Сетка отрезков — быстро найти, есть ли рядом чужая линия.
        val cell = 4f
        val grid = HashMap<Long, MutableList<Int>>()
        val segs = ArrayList<FloatArray>()
        val owner = ArrayList<Int>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        for ((pi, r) in rings.withIndex()) {
            var i = 0
            while (i + 3 < r.size) {
                val s = floatArrayOf(r[i], r[i + 1], r[i + 2], r[i + 3])
                val id = segs.size
                segs += s; owner += pi
                // Клетки вдоль отрезка (с соседями) — и для длинных линий.
                val sl = hypot(s[2] - s[0], s[3] - s[1])
                val steps = max(1, ceil(sl / (cell / 2f)).toInt())
                val cells = HashSet<Long>()
                for (q in 0..steps) {
                    val x = s[0] + (s[2] - s[0]) * q / steps; val y = s[1] + (s[3] - s[1]) * q / steps
                    val gx = floor(x / cell).toInt(); val gy = floor(y / cell).toInt()
                    for (ox in -1..1) for (oy in -1..1) cells += key(gx + ox, gy + oy)
                }
                for (c in cells) grid.getOrPut(c) { ArrayList(4) } += id
                i += 2
            }
        }
        class End(val x: Float, val y: Float, val dx: Float, val dy: Float, val ring: Int)
        val ends = ArrayList<End>()
        for ((pi, r) in rings.withIndex()) {
            if (r.size < 4) continue
            if (hypot(r[0] - r[r.size - 2], r[1] - r[r.size - 1]) < eps) continue
            for (side in 0..1) {
                val px = if (side == 0) r[0] else r[r.size - 2]; val py = if (side == 0) r[1] else r[r.size - 1]
                val qx = if (side == 0) r[2] else r[r.size - 4]; val qy = if (side == 0) r[3] else r[r.size - 3]
                var touch = false
                val cand = grid[key(floor(px / cell).toInt(), floor(py / cell).toInt())] ?: emptyList()
                for (id in cand) { if (owner[id] == pi) continue; val s = segs[id]; if (dSeg(px, py, s) < eps) { touch = true; break } }
                if (touch) continue
                val dx = px - qx; val dy = py - qy; val L = hypot(dx, dy)
                if (L < 1e-4f) continue
                ends += End(px, py, dx / L, dy / L, pi)
            }
        }
        class Pair3(val d: Float, val i: Int, val j: Int)
        val pairs = ArrayList<Pair3>()
        // Концы по сетке — пары только среди соседей.
        val eg = HashMap<Long, MutableList<Int>>()
        val ec = dmax
        for ((i, e) in ends.withIndex()) eg.getOrPut(key(floor(e.x / ec).toInt(), floor(e.y / ec).toInt())) { ArrayList(2) } += i
        for ((i, e) in ends.withIndex()) {
            val gx = floor(e.x / ec).toInt(); val gy = floor(e.y / ec).toInt()
            for (ox in -1..1) for (oy in -1..1) for (j in eg[key(gx + ox, gy + oy)] ?: continue) {
                if (j <= i) continue
                val f = ends[j]
                if (f.ring == e.ring) continue
                val vx = f.x - e.x; val vy = f.y - e.y; val dist = hypot(vx, vy)
                if (dist < 0.3f || dist > dmax) continue
                if (abs(vx * e.dx + vy * e.dy) / dist > 0.25f) continue
                if (e.dx * f.dx + e.dy * f.dy < 0.9f) continue
                pairs += Pair3(dist, i, j)
            }
        }
        pairs.sortBy { it.d }
        val used = BooleanArray(ends.size)
        val out = ArrayList<FloatArray>()
        for (p in pairs) {
            if (used[p.i] || used[p.j]) continue
            used[p.i] = true; used[p.j] = true
            out += floatArrayOf(ends[p.i].x, ends[p.i].y, ends[p.j].x, ends[p.j].y)
        }
        // Разрыв одной линии (к стене примыкает проём или другая стена, вторая линия идёт дальше): концы смотрят друг
        // на друга по одной прямой — заделать.
        val rest = ends.indices.filter { !used[it] }
        val gaps = ArrayList<Pair3>()
        for (a in rest.indices) for (b in a + 1 until rest.size) {
            val e = ends[rest[a]]; val f = ends[rest[b]]
            if (e.ring == f.ring) continue
            val vx = f.x - e.x; val vy = f.y - e.y; val dist = hypot(vx, vy)
            if (dist < 0.3f || dist > gapMax) continue
            if (e.dx * f.dx + e.dy * f.dy > -0.95f) continue
            if ((vx * e.dx + vy * e.dy) / dist < 0.97f) continue
            gaps += Pair3(dist, rest[a], rest[b])
        }
        gaps.sortBy { it.d }
        for (p in gaps) {
            if (used[p.i] || used[p.j]) continue
            used[p.i] = true; used[p.j] = true
            out += floatArrayOf(ends[p.i].x, ends[p.i].y, ends[p.j].x, ends[p.j].y)
        }
        return out
    }

    private fun dSeg(px: Float, py: Float, s: FloatArray): Float {
        val dx = s[2] - s[0]; val dy = s[3] - s[1]
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((px - s[0]) * dx + (py - s[1]) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - s[0] - t * dx, py - s[1] - t * dy)
    }

    fun polyArea(r: FloatArray): Float {
        var a = 0f
        val n = r.size / 2
        for (i in 0 until n) { val j = (i + 1) % n; a += r[2 * i] * r[2 * j + 1] - r[2 * j] * r[2 * i + 1] }
        return a / 2f
    }

    // ---------------------------------------------------------------- растр

    /** Растр: пункты (ox, oy) — левый верхний угол, k пикселей на пункт. */
    private class Ras(val w: Int, val h: Int, val k: Float, val ox: Float, val oy: Float) {
        fun px(x: Float) = (x - ox) * k
        fun py(y: Float) = (y - oy) * k

        fun polyline(m: BooleanArray, r: FloatArray, half: Float) {
            var i = 0
            if (r.size == 2) { line(m, r[0], r[1], r[0], r[1], half); return }
            while (i + 3 < r.size) { line(m, r[i], r[i + 1], r[i + 2], r[i + 3], half); i += 2 }
        }

        /** Отрезок полушириной [half] пикселей (квадратные концы). */
        fun line(m: BooleanArray, ax0: Float, ay0: Float, bx0: Float, by0: Float, half: Float) {
            val ax = px(ax0); val ay = py(ay0); val bx = px(bx0); val by = py(by0)
            val hw = max(half, 0.71f)
            var dx = bx - ax; var dy = by - ay
            val len = hypot(dx, dy)
            if (len < 1e-3f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
            val nx = -dy * hw; val ny = dx * hw
            val ex = dx * hw; val ey = dy * hw
            val q = floatArrayOf(ax - ex + nx, ay - ey + ny, bx + ex + nx, by + ey + ny, bx + ex - nx, by + ey - ny, ax - ex - nx, ay - ey - ny)
            fillPx(m, listOf(q))
        }

        /** Многоугольник (кольца в пунктах, чёт-нечет). */
        fun poly(m: BooleanArray, rings: List<FloatArray>) {
            fillPx(m, rings.filter { it.size >= 6 }.map { r -> FloatArray(r.size) { if (it % 2 == 0) px(r[it]) else py(r[it]) } })
        }

        /** Заливка колец в пикселях по строкам (чёт-нечет), центр пикселя — x + 0.5. */
        fun fillPx(m: BooleanArray, rings: List<FloatArray>) {
            if (rings.isEmpty()) return
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var n = 0
            for (r in rings) { var i = 1; while (i < r.size) { minY = min(minY, r[i]); maxY = max(maxY, r[i]); i += 2 }; n += r.size / 2 }
            val y0 = max(0, floor(minY - 0.5f).toInt()); val y1 = min(h - 1, ceil(maxY - 0.5f).toInt())
            if (y1 < y0) return
            val xs = FloatArray(n + 2)
            for (y in y0..y1) {
                val yc = y + 0.5f
                var c = 0
                for (r in rings) {
                    val cnt = r.size / 2
                    var j = cnt - 1
                    for (i in 0 until cnt) {
                        val yi = r[2 * i + 1]; val yj = r[2 * j + 1]
                        if ((yi > yc) != (yj > yc)) {
                            val xi = r[2 * i]; val xj = r[2 * j]
                            if (c < xs.size) xs[c++] = xi + (yc - yi) / (yj - yi) * (xj - xi)
                        }
                        j = i
                    }
                }
                if (c < 2) continue
                java.util.Arrays.sort(xs, 0, c)
                var q = 0
                while (q + 1 < c) {
                    val xa = max(0, ceil(xs[q] - 0.5f).toInt()); val xb = min(w - 1, floor(xs[q + 1] - 0.5f).toInt())
                    if (xb >= xa) { val o = y * w; for (x in xa..xb) m[o + x] = true }
                    else { val x = ((xs[q] + xs[q + 1]) / 2f).toInt(); if (x in 0 until w) m[y * w + x] = true }
                    q += 2
                }
            }
        }
    }

    /** Закрасить замкнутое: всё, до чего нельзя дойти от края по пустому. */
    private fun fillHoles(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(m.size)
        val stack = IntArrayStack()
        fun push(i: Int) { if (!m[i] && !out[i]) { out[i] = true; stack.push(i) } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (stack.size > 0) {
            val i = stack.pop()
            val x = i % w; val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        for (i in out.indices) out[i] = !out[i]
        return out
    }

    /** Заливка от i0 (4 соседа) по пикселям, где ok; [seen] — отмечает пройденное (области не пересекаются). */
    private inline fun flood(w: Int, h: Int, i0: Int, seen: BooleanArray, ok: (Int) -> Boolean): IntArray {
        val st = IntArrayStack()
        val out = IntArrayStack()
        if (!ok(i0) || seen[i0]) return IntArray(0)
        seen[i0] = true; st.push(i0)
        while (st.size > 0) {
            val i = st.pop()
            out.push(i)
            val x = i % w; val y = i / w
            if (x > 0 && !seen[i - 1] && ok(i - 1)) { seen[i - 1] = true; st.push(i - 1) }
            if (x < w - 1 && !seen[i + 1] && ok(i + 1)) { seen[i + 1] = true; st.push(i + 1) }
            if (y > 0 && !seen[i - w] && ok(i - w)) { seen[i - w] = true; st.push(i - w) }
            if (y < h - 1 && !seen[i + w] && ok(i + w)) { seen[i + w] = true; st.push(i + w) }
        }
        return out.toArray()
    }

    /** Связные куски маски (4 соседа): пиксели (индексы), сколько, рамка l, t, r, b. */
    private inline fun components(m: BooleanArray, w: Int, h: Int, each: (IntArray, Int, IntArray) -> Unit) {
        val seen = BooleanArray(m.size)
        val st = IntArrayStack()
        var px = IntArray(1024)
        for (s in m.indices) {
            if (!m[s] || seen[s]) continue
            var n = 0
            var l = w; var t = h; var r = -1; var b = -1
            seen[s] = true; st.push(s)
            while (st.size > 0) {
                val i = st.pop()
                if (n >= px.size) px = px.copyOf(px.size * 2)
                px[n++] = i
                val x = i % w; val y = i / w
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                if (x > 0 && m[i - 1] && !seen[i - 1]) { seen[i - 1] = true; st.push(i - 1) }
                if (x < w - 1 && m[i + 1] && !seen[i + 1]) { seen[i + 1] = true; st.push(i + 1) }
                if (y > 0 && m[i - w] && !seen[i - w]) { seen[i - w] = true; st.push(i - w) }
                if (y < h - 1 && m[i + w] && !seen[i + w]) { seen[i + w] = true; st.push(i + w) }
            }
            each(px, n, intArrayOf(l, t, r, b))
        }
    }

    /** Кусок в своей рамке (с полем 1 px): толщина, контур с дырами. */
    private class Sub(px: IntArray, n: Int, w: Int, box: IntArray) {
        val l = box[0] - 1
        val t = box[1] - 1
        val sw = box[2] - box[0] + 3
        val sh = box[3] - box[1] + 3
        val m = BooleanArray(sw * sh)

        init { for (q in 0 until n) { val i = px[q]; m[(i / w - t) * sw + (i % w - l)] = true } }

        /** Доля пикселей, оставшихся после сжатия на r (у линий — почти ноль). */
        fun solidRatio(r: Int): Float {
            val e = MagicFill.erode(m, sw, sh, r)
            var a = 0; var c = 0
            for (i in m.indices) { if (m[i]) a++; if (e[i]) c++ }
            return if (a == 0) 0f else c.toFloat() / a
        }

        fun element(ras: Ras, page: PdfVector.Page): Element? {
            val outer = MagicFill.contour(m, sw, sh) ?: return null
            val holes = ArrayList<FloatArray>()
            // Дыры: пустое внутри, куда не дойти от края рамки.
            val outside = BooleanArray(m.size)
            val st = IntArrayStack()
            outside[0] = true; st.push(0)
            while (st.size > 0) {
                val i = st.pop(); val x = i % sw; val y = i / sw
                fun go(j: Int) { if (!m[j] && !outside[j]) { outside[j] = true; st.push(j) } }
                if (x > 0) go(i - 1); if (x < sw - 1) go(i + 1); if (y > 0) go(i - sw); if (y < sh - 1) go(i + sw)
            }
            val hole = BooleanArray(m.size) { !m[it] && !outside[it] }
            components(hole, sw, sh) { hp, hn, hb ->
                if (hn < 6) return@components
                val hs = Sub(hp, hn, sw, hb)
                MagicFill.contour(hs.m, hs.sw, hs.sh)?.let { c ->
                    for (q in c.indices step 2) { c[q] += hs.l; c[q + 1] += hs.t }
                    holes += c
                }
            }
            fun conv(c: FloatArray): FloatArray {
                val s = MagicFill.simplify(c, 0.9f)
                return FloatArray(s.size) { q -> if (q % 2 == 0) (ras.ox + (s[q] + l) / ras.k) / page.w else (ras.oy + (s[q] + t) / ras.k) / page.h }
            }
            val o = conv(outer)
            if (o.size < 6) return null
            return Element(o, holes.map { conv(it) }.filter { it.size >= 6 })
        }
    }

    private class IntArrayStack {
        private var a = IntArray(4096)
        var size = 0
            private set
        fun push(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun pop(): Int = a[--size]
        fun toArray(): IntArray = a.copyOf(size)
    }

    /** Площадь области (внешний минус дыры) в долях картинки, y — в масштабе [aspect]. */
    fun area(outer: FloatArray, holes: List<FloatArray>, aspect: Float): Float {
        fun a(r: FloatArray): Float { var s = 0f; val n = r.size / 2; for (i in 0 until n) { val j = (i + 1) % n; s += r[2 * i] * r[2 * j + 1] * aspect - r[2 * j] * r[2 * i + 1] * aspect }; return abs(s) / 2f }
        return max(0f, a(outer) - holes.sumOf { a(it).toDouble() }.toFloat())
    }

    @Suppress("unused")
    private fun len(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)
}
