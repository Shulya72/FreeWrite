package party.shulya.freewrite

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Распознавание фигур по рисунку пера: нарисовали от руки — получили ровную фигуру.
 *
 * Работает без интернета и без ML Kit — чистая геометрия:
 * - открытый штрих: прямой — «линия»; длинный прямой с «усиками» у одного конца — «стрелка»;
 * - замкнутый штрих: сравниваем, на что он больше похож — на овал или на многоугольник
 *   (3 угла — треугольник, 4 прямых угла — прямоугольник/квадрат, углы на серединах сторон — ромб);
 * - стрелку можно рисовать в 1–3 штриха (древко + наконечник), прямоугольник — в 2–4 штриха,
 *   если концы штрихов сходятся;
 * - древко стрелки может быть дугой или волной: оно сглаживается, а наконечник ставится ровно
 *   по направлению линии у конца.
 *
 * Вход — штрихи (x, y подряд). Выход — ровная ломаная (x, y подряд) с частыми точками,
 * чтобы ластик цеплял фигуру в любом месте, или null, если это не фигура.
 */
object ShapeRecognizer {

    enum class Kind(val title: String) {
        LINE("Линия"),
        ARROW("Стрелка"),
        DOUBLE_ARROW("Двойная стрелка"),
        RECTANGLE("Прямоугольник"),
        SQUARE("Квадрат"),
        ELLIPSE("Овал"),
        CIRCLE("Круг"),
        TRIANGLE("Треугольник"),
        RHOMBUS("Ромб"),
        POLYGON("Многоугольник"),
    }

    /** Ровная фигура: вид и линия (x, y подряд). */
    class Shape(val kind: Kind, val pts: FloatArray) {
        val isClosed: Boolean get() = kind != Kind.LINE && kind != Kind.ARROW && kind != Kind.DOUBLE_ARROW
    }

    /**
     * @param strokes штрихи, у каждого точки x, y подряд
     * @param minSize меньше этого (по диагонали рамки) — не фигура
     * @param step расстояние между точками готовой фигуры
     */
    fun recognize(strokes: List<FloatArray>, minSize: Float, step: Float): Shape? {
        val paths = strokes.map { toPoints(it) }.filter { it.size >= 2 }
        if (paths.isEmpty() || paths.size > 4) return null
        val all = paths.flatten()
        if (diag(all) < minSize) return null
        val raw = if (paths.size == 1) {
            single(paths[0])
        } else {
            arrow(paths) ?: curvedArrowMulti(paths) ?: chain(paths)?.let { closed(it) }
        } ?: return null
        return Shape(raw.kind, densify(raw.pts, max(step, 0.5f), raw.kind))
    }

    // ------------------------------------------------------------------ общие помощники

    private class V(val x: Float, val y: Float)
    private class Raw(val kind: Kind, val pts: List<V>)

    private fun dist(a: V, b: V) = hypot(a.x - b.x, a.y - b.y)

    private fun toPoints(s: FloatArray): List<V> {
        val out = ArrayList<V>(s.size / 2)
        var i = 0
        while (i + 1 < s.size) {
            val v = V(s[i], s[i + 1])
            if (out.isEmpty() || dist(out.last(), v) > 0.01f) out += v
            i += 2
        }
        return out
    }

    private fun length(p: List<V>): Float {
        var l = 0f
        for (i in 1 until p.size) l += dist(p[i - 1], p[i])
        return l
    }

    /** [left, top, right, bottom] */
    private fun box(p: List<V>): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (v in p) { l = min(l, v.x); t = min(t, v.y); r = max(r, v.x); b = max(b, v.y) }
        return floatArrayOf(l, t, r, b)
    }

    private fun diag(p: List<V>): Float { val b = box(p); return hypot(b[2] - b[0], b[3] - b[1]) }

    /** n точек на равных расстояниях вдоль линии. */
    private fun resample(p: List<V>, n: Int): List<V> {
        val total = length(p)
        if (total <= 0f || p.size < 2) return List(n) { p[0] }
        val stepLen = total / (n - 1)
        val out = ArrayList<V>(n)
        out += p[0]
        var acc = 0f
        var prev = p[0]
        var i = 1
        while (i < p.size && out.size < n - 1) {
            val cur = p[i]
            val d = dist(prev, cur)
            if (d > 0f && acc + d >= stepLen) {
                val t = (stepLen - acc) / d
                val q = V(prev.x + (cur.x - prev.x) * t, prev.y + (cur.y - prev.y) * t)
                out += q
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        while (out.size < n) out += p.last()
        return out
    }

    /** Расстояние от точки до отрезка ab. */
    private fun segDist(p: V, a: V, b: V): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 <= 1e-6f) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Упрощение линии (Douglas–Peucker): индексы оставленных точек, с первой и последней. */
    private fun simplify(p: List<V>, from: Int, to: Int, eps: Float): List<Int> {
        val keep = BooleanArray(p.size)
        keep[from] = true; keep[to] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(from to to)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1
            var bestD = eps
            for (i in a + 1 until b) {
                val d = segDist(p[i], p[a], p[b])
                if (d > bestD) { bestD = d; best = i }
            }
            if (best >= 0) {
                keep[best] = true
                stack.addLast(a to best)
                stack.addLast(best to b)
            }
        }
        return (from..to).filter { keep[it] }
    }

    /** Угол в вершине b (между направлениями на a и на c), в градусах: 180 — прямая. */
    private fun angleAt(a: V, b: V, c: V): Float {
        val ux = a.x - b.x; val uy = a.y - b.y
        val vx = c.x - b.x; val vy = c.y - b.y
        val lu = hypot(ux, uy); val lv = hypot(vx, vy)
        if (lu <= 1e-4f || lv <= 1e-4f) return 180f
        val cosA = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cosA).toDouble()).toFloat()
    }

    /** Угол между векторами (в градусах, 0..180). */
    private fun angleBetween(ux: Float, uy: Float, vx: Float, vy: Float): Float {
        val lu = hypot(ux, uy); val lv = hypot(vx, vy)
        if (lu <= 1e-4f || lv <= 1e-4f) return 180f
        val c = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(c).toDouble()).toFloat()
    }

    // ------------------------------------------------------------------ один штрих

    private fun single(p: List<V>): Raw? = closed(p) ?: line(p) ?: arrow(listOf(p)) ?: curvedArrowSingle(p)

    /** Прямая линия. Почти горизонтальная/вертикальная — выравнивается. */
    private fun line(p: List<V>): Raw? {
        val total = length(p)
        val a = p.first(); val b = p.last()
        val chord = dist(a, b)
        if (chord <= 0f || chord < 0.93f * total) return null
        var dev = 0f
        for (v in p) dev = max(dev, segDist(v, a, b))
        if (dev > 0.07f * chord) return null
        val (s, e) = snapAxis(a, b)
        return Raw(Kind.LINE, listOf(s, e))
    }

    /** Отрезок, близкий к горизонтали или вертикали, выровнять (середина остаётся на месте). */
    private fun snapAxis(a: V, b: V): Pair<V, V> {
        val ang = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
        val mx = (a.x + b.x) / 2f; val my = (a.y + b.y) / 2f
        val len = dist(a, b)
        val mod = ((ang % 90f) + 90f) % 90f
        if (mod > 6f && mod < 84f) return a to b
        val horizontal = abs(cos(Math.toRadians(ang.toDouble()))) > 0.7
        return if (horizontal) {
            val sgn = if (b.x >= a.x) 1f else -1f
            V(mx - sgn * len / 2f, my) to V(mx + sgn * len / 2f, my)
        } else {
            val sgn = if (b.y >= a.y) 1f else -1f
            V(mx, my - sgn * len / 2f) to V(mx, my + sgn * len / 2f)
        }
    }

    // ------------------------------------------------------------------ замкнутые фигуры

    /**
     * Замкнутый штрих: конец вернулся к началу (небольшой перехлёст или недоход допустим).
     * Сравниваем, что ближе — овал или многоугольник.
     */
    private fun closed(p0: List<V>): Raw? {
        val d0 = diag(p0)
        if (d0 <= 0f) return null
        val p = resample(p0, 160)
        // Где линия ближе всего вернулась к началу (во второй половине пути).
        var k = -1
        var best = Float.MAX_VALUE
        for (i in (p.size * 0.55f).toInt() until p.size) {
            val d = dist(p[i], p[0])
            if (d < best) { best = d; k = i }
        }
        if (k < 0 || best > 0.17f * d0) return null
        val loop = ArrayList(p.subList(0, k + 1))
        loop += p[0]
        val q = resample(loop, 128)
        val d = diag(q)
        if (d <= 0f) return null

        val ell = fitEllipse(q)
        val poly = fitPolygon(q, d)
        val errE = ell?.err ?: Float.MAX_VALUE
        val errP = poly?.second ?: Float.MAX_VALUE
        if (poly != null && errP <= 0.032f * d && errP <= errE) return polygonShape(poly.first, q)
        if (ell != null && errE <= 0.036f * d) return ellipseShape(ell, d)
        return null
    }

    private class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float, val err: Float)

    /** Овал по главным осям точек; радиусы подогнаны по медиане. err — среднее отклонение. */
    private fun fitEllipse(q: List<V>): Ellipse? {
        val pts = q.subList(0, q.size - 1)
        var cx = 0f; var cy = 0f
        for (v in pts) { cx += v.x; cy += v.y }
        cx /= pts.size; cy /= pts.size
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (v in pts) {
            val dx = v.x - cx; val dy = v.y - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val th = 0.5f * atan2(2f * sxy, sxx - syy)
        val c = cos(th); val s = sin(th)
        var umin = Float.MAX_VALUE; var umax = -Float.MAX_VALUE; var vmin = Float.MAX_VALUE; var vmax = -Float.MAX_VALUE
        for (v in pts) {
            val u = (v.x - cx) * c + (v.y - cy) * s
            val w = -(v.x - cx) * s + (v.y - cy) * c
            umin = min(umin, u); umax = max(umax, u); vmin = min(vmin, w); vmax = max(vmax, w)
        }
        val cu = (umin + umax) / 2f; val cv = (vmin + vmax) / 2f
        var rx = (umax - umin) / 2f; var ry = (vmax - vmin) / 2f
        if (rx <= 0f || ry <= 0f || min(rx, ry) / max(rx, ry) < 0.12f) return null
        val ex = cx + cu * c - cv * s
        val ey = cy + cu * s + cv * c
        fun ratios(): FloatArray = FloatArray(pts.size) { i ->
            val v = pts[i]
            val u = (v.x - ex) * c + (v.y - ey) * s
            val w = -(v.x - ex) * s + (v.y - ey) * c
            val rho = hypot(u, w)
            val phi = atan2(w, u)
            val r = rx * ry / sqrt((ry * cos(phi)) * (ry * cos(phi)) + (rx * sin(phi)) * (rx * sin(phi)))
            rho / r
        }
        val med = ratios().sorted()[pts.size / 2]
        rx *= med; ry *= med
        var err = 0f
        for (v in pts) {
            val u = (v.x - ex) * c + (v.y - ey) * s
            val w = -(v.x - ex) * s + (v.y - ey) * c
            val rho = hypot(u, w)
            val phi = atan2(w, u)
            val r = rx * ry / sqrt((ry * cos(phi)) * (ry * cos(phi)) + (rx * sin(phi)) * (rx * sin(phi)))
            err += abs(rho - r)
        }
        return Ellipse(ex, ey, rx, ry, th, err / pts.size)
    }

    /** Многоугольник: углы замкнутой линии (3–6 штук) и среднее отклонение линии от его сторон. */
    private fun fitPolygon(q: List<V>, d: Float): Pair<List<V>, Float>? {
        val n = q.size - 1 // последняя точка = первая
        var far = 0
        for (i in 1 until n) if (dist(q[i], q[0]) > dist(q[far], q[0])) far = i
        if (far == 0) return null
        val eps = 0.045f * d
        val idx = (simplify(q, 0, far, eps) + simplify(q, far, n, eps).drop(1)).dropLast(1)
        val verts = ArrayList(idx.map { q[it] })
        // Убираем «углы», которые на самом деле прямые участки, и слипшиеся вершины.
        while (verts.size > 3) {
            var worst = -1
            var worstAng = 152f
            for (i in verts.indices) {
                val a = verts[(i - 1 + verts.size) % verts.size]
                val b = verts[i]
                val c = verts[(i + 1) % verts.size]
                val ang = if (dist(a, b) < 0.07f * d || dist(b, c) < 0.07f * d) 179f else angleAt(a, b, c)
                if (ang > worstAng) { worstAng = ang; worst = i }
            }
            if (worst < 0) break
            verts.removeAt(worst)
        }
        if (verts.size < 3 || verts.size > 6) return null
        var err = 0f
        for (i in 0 until n) {
            var m = Float.MAX_VALUE
            for (j in verts.indices) m = min(m, segDist(q[i], verts[j], verts[(j + 1) % verts.size]))
            err += m
        }
        return verts to err / n
    }

    private fun polygonShape(v: List<V>, q: List<V>): Raw {
        if (v.size == 4) {
            val angles = v.indices.map { angleAt(v[(it + 3) % 4], v[it], v[(it + 1) % 4]) }
            if (angles.all { abs(it - 90f) <= 20f }) {
                // Прямоугольник «на углу» (стороны под 45°) — это ромб, как его и рисуют.
                val deg = abs(Math.toDegrees(orientation(v).toDouble()))
                if (deg > 33.0) rhombus(v, q, 0.12f)?.let { return it }
                return rectangle(v, q)
            }
            rhombus(v, q, 0.14f)?.let { return it }
        }
        if (v.size == 3) return Raw(Kind.TRIANGLE, closeLoop(snapTriangle(v)))
        return Raw(Kind.POLYGON, closeLoop(v))
    }

    /** Прямоугольник по направлению сторон и размаху рисунка; почти квадрат — квадрат. */
    /** Средний наклон сторон по модулю 90°, в радианах (−45°…45°). */
    private fun orientation(v: List<V>): Float {
        var sx = 0.0; var sy = 0.0
        for (i in v.indices) {
            val a = v[i]; val b = v[(i + 1) % v.size]
            val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()) * 4.0
            val w = dist(a, b).toDouble()
            sx += cos(ang) * w; sy += sin(ang) * w
        }
        return (atan2(sy, sx) / 4.0).toFloat()
    }

    private fun rectangle(v: List<V>, q: List<V>): Raw {
        var th = orientation(v)
        if (abs(Math.toDegrees(th.toDouble())) < 12.0) th = 0f
        val c = cos(th); val s = sin(th)
        var umin = Float.MAX_VALUE; var umax = -Float.MAX_VALUE; var wmin = Float.MAX_VALUE; var wmax = -Float.MAX_VALUE
        for (p in q) {
            val u = p.x * c + p.y * s
            val w = -p.x * s + p.y * c
            umin = min(umin, u); umax = max(umax, u); wmin = min(wmin, w); wmax = max(wmax, w)
        }
        var kind = Kind.RECTANGLE
        val wU = umax - umin; val hW = wmax - wmin
        if (min(wU, hW) / max(wU, hW) >= 0.86f) {
            kind = Kind.SQUARE
            val side = (wU + hW) / 2f
            val cu = (umin + umax) / 2f; val cw = (wmin + wmax) / 2f
            umin = cu - side / 2f; umax = cu + side / 2f; wmin = cw - side / 2f; wmax = cw + side / 2f
        }
        fun back(u: Float, w: Float) = V(u * c - w * s, u * s + w * c)
        return Raw(kind, listOf(back(umin, wmin), back(umax, wmin), back(umax, wmax), back(umin, wmax), back(umin, wmin)))
    }

    /** Ромб: вершины стоят у середин сторон рамки рисунка. */
    private fun rhombus(v: List<V>, q: List<V>, tol: Float): Raw? {
        val b = box(q)
        val d = hypot(b[2] - b[0], b[3] - b[1])
        val mids = listOf(
            V((b[0] + b[2]) / 2f, b[1]), V(b[2], (b[1] + b[3]) / 2f),
            V((b[0] + b[2]) / 2f, b[3]), V(b[0], (b[1] + b[3]) / 2f),
        )
        for (m in mids) if (v.none { dist(it, m) <= tol * d }) return null
        return Raw(Kind.RHOMBUS, closeLoop(mids))
    }

    /** Почти горизонтальное основание треугольника — сделать горизонтальным. */
    private fun snapTriangle(v: List<V>): List<V> {
        val out = v.toMutableList()
        for (i in 0 until 3) {
            val a = out[i]; val b = out[(i + 1) % 3]
            val ang = abs(Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())))
            val tilt = min(ang, 180.0 - ang)
            if (tilt < 7.0) {
                val y = (a.y + b.y) / 2f
                out[i] = V(a.x, y); out[(i + 1) % 3] = V(b.x, y)
                break
            }
        }
        return out
    }

    private fun closeLoop(v: List<V>): List<V> = v + v[0]

    private fun ellipseShape(e: Ellipse, d: Float): Raw {
        var rx = e.rx; var ry = e.ry
        var th = e.angle
        var kind = Kind.ELLIPSE
        if (min(rx, ry) / max(rx, ry) >= 0.85f) {
            kind = Kind.CIRCLE
            rx = (rx + ry) / 2f; ry = rx; th = 0f
        } else {
            // Почти ровно лежащий овал — выровнять.
            val deg = Math.toDegrees(th.toDouble())
            val near = Math.round(deg / 90.0) * 90.0
            if (abs(deg - near) < 10.0) th = Math.toRadians(near).toFloat()
        }
        val n = 96
        val c = cos(th); val s = sin(th)
        val out = ArrayList<V>(n + 1)
        for (i in 0..n) {
            val t = (2.0 * Math.PI * i / n).toFloat()
            val u = rx * cos(t); val w = ry * sin(t)
            out += V(e.cx + u * c - w * s, e.cy + u * s + w * c)
        }
        return Raw(kind, out)
    }

    // ------------------------------------------------------------------ стрелки

    /**
     * Стрелка из 1–3 штрихов. Древко — самый длинный прямой участок; у его конца должны быть
     * «усики» по обе стороны, направленные назад вдоль древка (под углом до 55°, не длиннее половины древка).
     * Всё остальное в рисунке должно быть частью наконечника — иначе это не стрелка (буква, знак).
     */
    private fun arrow(paths: List<List<V>>): Raw? {
        class Seg(val path: Int, val a: Int, val b: Int)
        val simplified = paths.map { p ->
            val e = 0.05f * max(diag(p), 1f)
            val idx = simplify(p, 0, p.size - 1, e)
            idx.map { p[it] }
        }
        // Самый длинный отрезок — древко; он должен быть крайним в своём штрихе.
        var shaft: Seg? = null
        var shaftLen = 0f
        for ((pi, v) in simplified.withIndex()) {
            for (i in 0 until v.size - 1) {
                val l = dist(v[i], v[i + 1])
                if (l > shaftLen) { shaftLen = l; shaft = Seg(pi, i, i + 1) }
            }
        }
        val sh = shaft ?: return null
        val sv = simplified[sh.path]
        if (sh.a != 0 && sh.b != sv.size - 1) return null
        val total = paths.sumOf { length(it).toDouble() }.toFloat()
        if (shaftLen < 0.38f * total) return null
        val p1 = sv[sh.a]
        val p2 = sv[sh.b]
        // Остальные вершины: у конца древка (кончик), «усики» или — не стрелка.
        val sidesAt = arrayOf(IntArray(2), IntArray(2)) // [конец][сторона]
        val barbLen = arrayOf(ArrayList<Float>(), ArrayList<Float>())
        for ((pi, v) in simplified.withIndex()) {
            for ((i, w) in v.withIndex()) {
                if (pi == sh.path && (i == sh.a || i == sh.b)) continue
                val d1 = dist(w, p1); val d2 = dist(w, p2)
                val end = if (d1 <= d2) 0 else 1
                val e = if (end == 0) p1 else p2
                val o = if (end == 0) p2 else p1
                val dd = min(d1, d2)
                if (dd < 0.1f * shaftLen) continue // вершина у самого кончика
                if (dd > 0.5f * shaftLen) return null // далеко от концов — это не стрелка
                val ang = angleBetween(w.x - e.x, w.y - e.y, o.x - e.x, o.y - e.y)
                if (ang > 55f) return null // торчит вбок или вперёд — это не усик
                val cross = (o.x - e.x) * (w.y - e.y) - (o.y - e.y) * (w.x - e.x)
                sidesAt[end][if (cross >= 0) 0 else 1]++
                barbLen[end] += dd
            }
        }
        val headAt = BooleanArray(2) { sidesAt[it][0] > 0 && sidesAt[it][1] > 0 }
        if (!headAt[0] && !headAt[1]) return null
        // Наконечник ровный: усики одной длины под 28° к древку.
        fun avg(l: List<Float>) = if (l.isEmpty()) 0.22f * shaftLen else l.average().toFloat()
        val (a0, b0) = snapAxis(p1, p2)
        fun head(tip: V, from: V, len: Float): Pair<V, V> {
            val dx = (from.x - tip.x) / dist(tip, from)
            val dy = (from.y - tip.y) / dist(tip, from)
            val h = len.coerceIn(0.1f * dist(tip, from), 0.35f * dist(tip, from))
            val r = Math.toRadians(28.0)
            val cr = cos(r).toFloat(); val sr = sin(r).toFloat()
            val l = V(tip.x + h * (dx * cr - dy * sr), tip.y + h * (dx * sr + dy * cr))
            val rr = V(tip.x + h * (dx * cr + dy * sr), tip.y + h * (-dx * sr + dy * cr))
            return l to rr
        }
        return when {
            headAt[0] && headAt[1] -> {
                val (l1, r1) = head(a0, b0, avg(barbLen[0]))
                val (l2, r2) = head(b0, a0, avg(barbLen[1]))
                Raw(Kind.DOUBLE_ARROW, listOf(l1, a0, r1, a0, b0, l2, b0, r2))
            }
            headAt[1] -> {
                val (l, r) = head(b0, a0, avg(barbLen[1]))
                Raw(Kind.ARROW, listOf(a0, b0, l, b0, r))
            }
            else -> {
                val (l, r) = head(a0, b0, avg(barbLen[0]))
                Raw(Kind.ARROW, listOf(b0, a0, l, a0, r))
            }
        }
    }

    // ------------------------------------------------------------------ кривые стрелки

    /** Единичное направление у конца линии (наружу): от точки на расстоянии back вдоль линии — к концу. */
    private fun endDir(p: List<V>, atEnd: Boolean, back: Float): V {
        val n = p.size
        val tip = if (atEnd) p[n - 1] else p[0]
        var acc = 0f
        var prev = tip
        var q = tip
        val order = if (atEnd) (n - 2 downTo 0) else (1 until n)
        for (i in order) {
            val c = p[i]
            acc += dist(prev, c)
            prev = c
            q = c
            if (acc >= back) break
        }
        val dx = tip.x - q.x; val dy = tip.y - q.y
        val l = hypot(dx, dy)
        return if (l <= 1e-4f) V(1f, 0f) else V(dx / l, dy / l)
    }

    /** Самый резкий поворот линии, в градусах (0 — прямо, 180 — назад); p — с равным шагом. */
    private fun maxTurn(p: List<V>, k: Int): Float {
        var m = 0f
        for (i in k until p.size - k) {
            val t = 180f - angleAt(p[i - k], p[i], p[i + k])
            if (t > m) m = t
        }
        return m
    }

    private class Head(val ok: Boolean, val len: Float)

    /**
     * Наконечник у конца tip (dir — направление линии наружу). Вершины verts: у самого кончика —
     * пропускаем, остальные должны быть усиками (назад вдоль линии, до 60°), по обе стороны.
     * null — в рисунке есть что-то лишнее (это не стрелка).
     */
    private fun headAt(tip: V, dir: V, verts: List<V>, bodyLen: Float): Head? {
        val far = verts.maxOfOrNull { dist(it, tip) } ?: return Head(false, 0f)
        if (far > 0.5f * bodyLen) return null
        if (far < 0.03f * bodyLen) return Head(false, 0f)
        var left = 0
        var right = 0
        val lens = ArrayList<Float>()
        for (w in verts) {
            val d = dist(w, tip)
            if (d < 0.35f * far) continue
            if (angleBetween(w.x - tip.x, w.y - tip.y, -dir.x, -dir.y) > 60f) return null
            val cross = dir.x * (w.y - tip.y) - dir.y * (w.x - tip.x)
            if (cross >= 0) left++ else right++
            lens += d
        }
        return Head(left > 0 && right > 0, if (lens.isEmpty()) 0f else lens.average().toFloat())
    }

    /** Кривая стрелка одним штрихом: плавная линия, резкий разворот у конца и усики. */
    private fun curvedArrowSingle(p0: List<V>): Raw? {
        val total = length(p0)
        if (total <= 0f) return null
        val n = 200
        val p = resample(p0, n)
        val stepLen = total / (n - 1)
        val k = max(2, (0.03f * total / stepLen).toInt())
        // Кончик — первый резкий разворот во второй части пути.
        var tip = -1
        for (i in (n * 0.4f).toInt() until n - k) {
            if (180f - angleAt(p[i - k], p[i], p[i + k]) >= 105f) { tip = i; break }
        }
        if (tip < 0) return null
        var best = 0f
        for (i in tip until min(n - k, tip + k + 1)) {
            val t = 180f - angleAt(p[i - k], p[i], p[i + k])
            if (t > best) { best = t; tip = i }
        }
        val body = p.subList(0, tip + 1)
        val bodyLen = stepLen * tip
        if (bodyLen < 0.55f * total || body.size < 2 * k + 2) return null
        if (maxTurn(body, k) >= 100f) return null // древко плавное, без изломов
        val rest = p.subList(tip, n)
        val restVerts = simplify(rest, 0, rest.size - 1, 0.03f * bodyLen).map { rest[it] }
        val dir = endDir(body, true, 0.12f * bodyLen)
        val head = headAt(p[tip], dir, restVerts, bodyLen) ?: return null
        if (!head.ok) return null
        return curved(body, bodyLen, head.len, null)
    }

    /** Кривая стрелка из 2–3 штрихов: плавное древко + наконечник(и) отдельными штрихами. */
    private fun curvedArrowMulti(paths: List<List<V>>): Raw? {
        if (paths.size > 3) return null
        val bi = paths.indices.maxByOrNull { length(paths[it]) } ?: return null
        val bodyLen = length(paths[bi])
        if (bodyLen <= 0f) return null
        val body = resample(paths[bi], 200)
        val k = max(2, (0.03f * 199).toInt())
        if (maxTurn(body, k) >= 100f) return null
        val endVerts = ArrayList<V>()
        val startVerts = ArrayList<V>()
        for ((i, o) in paths.withIndex()) {
            if (i == bi) continue
            if (diag(o) > 0.6f * bodyLen) return null
            val dEnd = o.minOf { dist(it, body.last()) }
            val dStart = o.minOf { dist(it, body.first()) }
            if (min(dEnd, dStart) > 0.25f * bodyLen) return null
            val vs = simplify(o, 0, o.size - 1, 0.05f * max(diag(o), 1f)).map { o[it] }
            if (dEnd <= dStart) endVerts += vs else startVerts += vs
        }
        val back = 0.12f * bodyLen
        val hEnd = if (endVerts.isEmpty()) null else (headAt(body.last(), endDir(body, true, back), endVerts, bodyLen) ?: return null)
        val hStart = if (startVerts.isEmpty()) null else (headAt(body.first(), endDir(body, false, back), startVerts, bodyLen) ?: return null)
        val endOk = hEnd?.ok == true
        val startOk = hStart?.ok == true
        if (!endOk && !startOk) return null
        return curved(body, bodyLen, if (endOk) hEnd!!.len else null, if (startOk) hStart!!.len else null)
    }

    /** Сгладить древко и поставить ровные наконечники по направлению линии у концов. */
    private fun curved(body: List<V>, bodyLen: Float, headEnd: Float?, headStart: Float?): Raw {
        var s = resample(body, 120)
        repeat(2) { s = smooth(s, 4) }
        val back = 0.12f * bodyLen
        fun barbs(tip: V, dir: V, len: Float): Pair<V, V> {
            val h = len.coerceIn(0.05f * bodyLen, 0.3f * bodyLen)
            val r = Math.toRadians(28.0)
            val cr = cos(r).toFloat(); val sr = sin(r).toFloat()
            val bx = -dir.x; val by = -dir.y // назад вдоль линии
            return V(tip.x + h * (bx * cr - by * sr), tip.y + h * (bx * sr + by * cr)) to
                V(tip.x + h * (bx * cr + by * sr), tip.y + h * (-bx * sr + by * cr))
        }
        val out = ArrayList<V>()
        if (headStart != null) {
            val (l, r) = barbs(s.first(), endDir(s, false, back), headStart)
            out += l; out += s.first(); out += r
        }
        out += s
        if (headEnd != null) {
            val (l, r) = barbs(s.last(), endDir(s, true, back), headEnd)
            out += l; out += s.last(); out += r
        }
        return Raw(Kind.ARROW, out)
    }

    /** Скользящее среднее (концы на месте) — убирает дрожь руки. */
    private fun smooth(p: List<V>, r: Int): List<V> {
        if (p.size < 3) return p
        val out = ArrayList<V>(p.size)
        out += p.first()
        for (i in 1 until p.size - 1) {
            val a = max(0, i - r)
            val b = min(p.size - 1, i + r)
            var x = 0f; var y = 0f
            for (j in a..b) { x += p[j].x; y += p[j].y }
            val c = (b - a + 1).toFloat()
            out += V(x / c, y / c)
        }
        out += p.last()
        return out
    }

    // ------------------------------------------------------------------ фигура из нескольких штрихов

    /** Сцепить 2–4 штриха в одну линию, если их концы сходятся. Результат должен замкнуться. */
    private fun chain(paths: List<List<V>>): List<V>? {
        val d = diag(paths.flatten())
        val tol = 0.16f * d
        val sorted = paths.sortedByDescending { length(it) }
        return chainFrom(sorted[0], sorted.drop(1), tol) ?: chainFrom(sorted[0].reversed(), sorted.drop(1), tol)
    }

    private fun chainFrom(first: List<V>, rest: List<List<V>>, tol: Float): List<V>? {
        val left = rest.toMutableList()
        val out = ArrayList(first)
        while (left.isNotEmpty()) {
            val end = out.last()
            var bestI = -1
            var bestRev = false
            var bestD = Float.MAX_VALUE
            for ((i, p) in left.withIndex()) {
                val ds = dist(end, p.first())
                val de = dist(end, p.last())
                if (ds < bestD) { bestD = ds; bestI = i; bestRev = false }
                if (de < bestD) { bestD = de; bestI = i; bestRev = true }
            }
            if (bestI < 0 || bestD > tol) return null
            val p = left.removeAt(bestI)
            out.addAll(if (bestRev) p.reversed() else p)
        }
        if (dist(out.first(), out.last()) > tol) return null
        return out
    }

    // ------------------------------------------------------------------ готовая линия

    /** Ломаная с точками через каждые step (у углов — сами углы), x, y подряд. */
    private fun densify(v: List<V>, step: Float, kind: Kind): FloatArray {
        val out = ArrayList<Float>()
        fun add(p: V) { out += p.x; out += p.y }
        add(v[0])
        for (i in 1 until v.size) {
            val a = v[i - 1]; val b = v[i]
            val l = dist(a, b)
            // У овала точки и так частые — не дробим.
            val n = if (kind == Kind.ELLIPSE || kind == Kind.CIRCLE) 1 else max(1, (l / step).toInt())
            for (k in 1..n) {
                val t = k / n.toFloat()
                add(V(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        return out.toFloatArray()
    }
}
