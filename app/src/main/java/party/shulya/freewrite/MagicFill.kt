package party.shulya.freewrite

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * «Волшебная заливка» (0.23, как Dynamic Fill в Bluebeam Revu и «закрыть зазор» в Clip Studio Paint): касание внутри
 * помещения или плиты на чертеже — контур находится сам по линиям чертежа; касание по колонне или пилону (тёмные или
 * заштрихованные) — выделяется сама колонна.
 *
 * Чистая геометрия по яркости пикселей (без Android): [lum] — яркость 0…255 по строкам, w × h.
 * [gap] — «зазор», пикселей: щели в линиях (дверные проёмы, разрывы) уже 2·gap закрываются, чтобы заливка не вытекала.
 */
object MagicFill {

    /** Что получилось: контур (x, y в пикселях), площадь, вытекло наружу; [error] — почему не вышло. */
    class Result(val pts: FloatArray, val area: Int, val leaked: Boolean, val onInk: Boolean, val error: String = "") {
        val ok: Boolean get() = error.isEmpty() && pts.size >= 6
    }

    const val ERR_LINE = "line"
    const val ERR_WALL = "wall"
    const val ERR_EMPTY = "empty"

    /** Порог «линия чертежа»: темнее 78 % яркости бумаги (бумага — 90-й процентиль яркости). */
    fun threshold(lum: ByteArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[v.toInt() and 0xFF]++
        val want = (lum.size * 0.9).toLong()
        var acc = 0L
        var paper = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= want) { paper = i; break } }
        return (paper * 0.78f).toInt().coerceIn(90, 215)
    }

    fun inkMask(lum: ByteArray, t: Int = threshold(lum)): BooleanArray = BooleanArray(lum.size) { (lum[it].toInt() and 0xFF) < t }

    /**
     * Найти область под точкой (x, y). [ink] — маска линий ([inkMask]), [closed] — она же с закрытым зазором
     * ([dilate] на gap; при gap = 0 — сама [ink]): их удобно считать один раз на лист и зазор.
     */
    fun find(ink: BooleanArray, closed: BooleanArray, w: Int, h: Int, x: Int, y: Int, gap: Int): Result {
        if (x !in 0 until w || y !in 0 until h) return Result(FloatArray(0), 0, false, false, ERR_EMPTY)
        val i0 = y * w + x
        if (closed[i0]) return findInk(ink, w, h, x, y, gap)
        // Помещение: заливка по «бумаге» до линий (с закрытыми щелями).
        val region = BooleanArray(w * h)
        val res = flood(w, h, i0, region) { !closed[it] }
        // Дошла до края листа — помещение не замкнуто (или кликнули снаружи здания).
        val leaked = res.border
        // Вернуть отступ от линий: на зазор + 1 пиксель — контур ложится на линии чертежа.
        val grown = if (gap + 1 > 0) dilate(region, w, h, gap + 1) else region
        val pts = contour(grown, w, h) ?: return Result(FloatArray(0), 0, leaked, false, ERR_EMPTY)
        return Result(simplify(pts, 1.2f), res.area, leaked, false)
    }

    /** Касание по линии: колонна/пилон (толстое тёмное или заштрихованное пятно) — само пятно; тонкая линия — нет. */
    private fun findInk(ink: BooleanArray, w: Int, h: Int, x: Int, y: Int, gap: Int): Result {
        // Окно вокруг касания: колонны небольшие, а считать весь лист для них не нужно.
        val half = 320
        val wx0 = max(0, x - half); val wy0 = max(0, y - half)
        val wx1 = min(w, x + half); val wy1 = min(h, y + half)
        val ww = wx1 - wx0; val wh = wy1 - wy0
        val m = BooleanArray(ww * wh)
        for (yy in 0 until wh) System.arraycopy(ink, (wy0 + yy) * w + wx0, m, yy * ww, ww)
        // Штриховку — в сплошное (закрыть), тонкие линии — убрать (открыть): остаются толстые пятна.
        val rc = max(2, gap)
        val solid = erode(dilate(m, ww, wh, rc), ww, wh, rc)
        val e = 2
        val opened = dilate(erode(solid, ww, wh, e), ww, wh, e)
        var lx = x - wx0; var ly = y - wy0
        if (!opened[ly * ww + lx]) {
            // Палец чуть мимо — ближайшее пятно в 6 пикселях.
            var best = -1; var bd = Int.MAX_VALUE
            for (dy in -6..6) for (dx in -6..6) {
                val xx = lx + dx; val yy = ly + dy
                if (xx !in 0 until ww || yy !in 0 until wh || !opened[yy * ww + xx]) continue
                val d = dx * dx + dy * dy
                if (d < bd) { bd = d; best = yy * ww + xx }
            }
            if (best < 0) return Result(FloatArray(0), 0, false, true, ERR_LINE)
            lx = best % ww; ly = best / ww
        }
        val region = BooleanArray(ww * wh)
        val res = flood(ww, wh, ly * ww + lx, region) { opened[it] }
        // Пятно упёрлось в край окна или огромное — это стены, а не колонна.
        if (res.border && (wx0 > 0 || wy0 > 0 || wx1 < w || wy1 < h) || res.area > w * h * 0.03) return Result(FloatArray(0), res.area, false, true, ERR_WALL)
        val pts = contour(region, ww, wh) ?: return Result(FloatArray(0), 0, false, true, ERR_EMPTY)
        val s = simplify(pts, 1.0f)
        for (k in s.indices step 2) { s[k] += wx0.toFloat(); s[k + 1] += wy0.toFloat() }
        return Result(s, res.area, false, true)
    }

    private class Flood(val area: Int, val border: Boolean)

    /** Заливка от i0 по пикселям, где ok (4 соседа), — в region. */
    private inline fun flood(w: Int, h: Int, i0: Int, region: BooleanArray, ok: (Int) -> Boolean): Flood {
        if (!ok(i0)) return Flood(0, false)
        var stack = IntArray(4096)
        var sp = 0
        stack[sp++] = i0
        region[i0] = true
        var area = 0
        var border = false
        while (sp > 0) {
            val i = stack[--sp]
            area++
            val x = i % w; val y = i / w
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
            if (sp + 4 >= stack.size) stack = stack.copyOf(stack.size * 2)
            if (x > 0) { val j = i - 1; if (!region[j] && ok(j)) { region[j] = true; stack[sp++] = j } }
            if (x < w - 1) { val j = i + 1; if (!region[j] && ok(j)) { region[j] = true; stack[sp++] = j } }
            if (y > 0) { val j = i - w; if (!region[j] && ok(j)) { region[j] = true; stack[sp++] = j } }
            if (y < h - 1) { val j = i + w; if (!region[j] && ok(j)) { region[j] = true; stack[sp++] = j } }
        }
        return Flood(area, border)
    }

    /** Расширить маску квадратом радиуса r (два прохода: по строкам и по столбцам). */
    fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return m.copyOf()
        val tmp = BooleanArray(m.size)
        for (y in 0 until h) {
            val o = y * w
            var cnt = 0
            for (x in 0 until min(r, w)) if (m[o + x]) cnt++
            for (x in 0 until w) {
                val add = x + r; if (add < w && m[o + add]) cnt++
                tmp[o + x] = cnt > 0
                val rem = x - r; if (rem >= 0 && m[o + rem]) cnt--
            }
        }
        val out = BooleanArray(m.size)
        for (x in 0 until w) {
            var cnt = 0
            for (y in 0 until min(r, h)) if (tmp[y * w + x]) cnt++
            for (y in 0 until h) {
                val add = y + r; if (add < h && tmp[add * w + x]) cnt++
                out[y * w + x] = cnt > 0
                val rem = y - r; if (rem >= 0 && tmp[rem * w + x]) cnt--
            }
        }
        return out
    }

    fun erode(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return m.copyOf()
        val inv = BooleanArray(m.size) { !m[it] }
        val d = dilate(inv, w, h, r)
        return BooleanArray(m.size) { !d[it] }
    }

    private val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    private fun dirOf(dx: Int, dy: Int): Int {
        for (i in 0 until 8) if (DX[i] == dx && DY[i] == dy) return i
        return 4
    }

    /** Внешний контур маски (обход соседей Мура, по часовой на экране); точки — центры пикселей. */
    fun contour(m: BooleanArray, w: Int, h: Int): FloatArray? {
        var s = -1
        for (i in m.indices) if (m[i]) { s = i; break }
        if (s < 0) return null
        val sx = s % w; val sy = s / w
        fun fg(x: Int, y: Int) = x in 0 until w && y in 0 until h && m[y * w + x]
        val out = ArrayList<Float>()
        out += sx + 0.5f; out += sy + 0.5f
        var px = sx; var py = sy
        var bdir = 4 // пришли «с запада»: левее верхнего левого пикселя — пусто
        val limit = 8 * m.size
        var steps = 0
        while (steps++ < limit) {
            var found = -1
            for (k in 1..8) { val nd = (bdir + k) % 8; if (fg(px + DX[nd], py + DY[nd])) { found = nd; break } }
            if (found < 0) break
            val prev = (found + 7) % 8
            val bx = px + DX[prev]; val by = py + DY[prev]
            px += DX[found]; py += DY[found]
            bdir = dirOf(bx - px, by - py)
            if (px == sx && py == sy && bdir == 4) break
            out += px + 0.5f; out += py + 0.5f
        }
        if (out.size < 6) {
            // Один-два пикселя — квадратик вокруг.
            return floatArrayOf(sx.toFloat(), sy.toFloat(), sx + 1f, sy.toFloat(), sx + 1f, sy + 1f, sx.toFloat(), sy + 1f)
        }
        return out.toFloatArray()
    }

    /** Упростить замкнутый контур (Дуглас — Пекер): точки ближе eps к линии выбрасываются. */
    fun simplify(p: FloatArray, eps: Float): FloatArray {
        val n = p.size / 2
        if (n <= 4) return p
        // Две опоры: первая точка и самая дальняя от неё.
        var far = 0; var fd = -1f
        for (i in 1 until n) { val d = sq(p[2 * i] - p[0]) + sq(p[2 * i + 1] - p[1]); if (d > fd) { fd = d; far = i } }
        val keep = BooleanArray(n)
        keep[0] = true; keep[far] = true
        dp(p, 0, far, eps, keep)
        dp(p, far, n, eps, keep)
        val out = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) { out += p[2 * i]; out += p[2 * i + 1] }
        return if (out.size >= 6) out.toFloatArray() else p
    }

    private fun sq(v: Float) = v * v

    /** Отрезок a..b (b может быть n — это снова точка 0). */
    private fun dp(p: FloatArray, a: Int, b: Int, eps: Float, keep: BooleanArray) {
        val n = p.size / 2
        val stack = ArrayDeque<IntArray>()
        stack.add(intArrayOf(a, b))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            if (e - s < 2) continue
            val ax = p[2 * (s % n)]; val ay = p[2 * (s % n) + 1]
            val bx = p[2 * (e % n)]; val by = p[2 * (e % n) + 1]
            val dx = bx - ax; val dy = by - ay
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            var best = -1; var bd = eps
            for (i in s + 1 until e) {
                val x = p[2 * i]; val y = p[2 * i + 1]
                val d = if (len < 1e-6f) kotlin.math.sqrt(sq(x - ax) + sq(y - ay)) else abs(dy * x - dx * y + bx * ay - by * ax) / len
                if (d > bd) { bd = d; best = i }
            }
            if (best >= 0) { keep[best] = true; stack.add(intArrayOf(s, best)); stack.add(intArrayOf(best, e)) }
        }
    }

    /** Площадь многоугольника (пикселей²) — для проверок. */
    fun area(p: FloatArray): Float {
        var a = 0f
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return abs(a) / 2f
    }

    /** Эллипс в рамке (доли картинки) — инструмент «Круг». */
    fun ellipse(l: Float, t: Float, r: Float, b: Float, n: Int = 48): FloatArray {
        val cx = (l + r) / 2f; val cy = (t + b) / 2f; val rx = abs(r - l) / 2f; val ry = abs(b - t) / 2f
        return FloatArray(n * 2) { k ->
            val a = (k / 2) * 2.0 * Math.PI / n
            if (k % 2 == 0) (cx + rx * kotlin.math.cos(a)).toFloat() else (cy + ry * kotlin.math.sin(a)).toFloat()
        }
    }

    fun rect(l: Float, t: Float, r: Float, b: Float): FloatArray =
        floatArrayOf(min(l, r), min(t, b), max(l, r), min(t, b), max(l, r), max(t, b), min(l, r), max(t, b))
}
