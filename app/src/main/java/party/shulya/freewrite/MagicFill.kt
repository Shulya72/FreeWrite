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

    /**
     * Что получилось: контур (x, y в пикселях), площадь, вытекло наружу; [kind] — помещение, колонна или стены;
     * [error] — почему не вышло.
     */
    class Result(val pts: FloatArray, val area: Int, val leaked: Boolean, val onInk: Boolean, val error: String = "", val kind: Int = ROOM) {
        val ok: Boolean get() = error.isEmpty() && pts.size >= 6
    }

    const val ERR_LINE = "line"
    const val ERR_WALL = "wall"
    const val ERR_EMPTY = "empty"
    const val ROOM = 0
    const val COLUMN = 1
    const val WALL = 2

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
     * «Толстое» на чертеже (0.23.1): стены, колонны, пилоны — сплошные или заштрихованные. Штриховку закрываем
     * (щели до 6 px), тонкие линии, размеры и текст убираем (уже 5 px). Считается один раз на лист.
     */
    fun solidMask(ink: BooleanArray, w: Int, h: Int): BooleanArray {
        val closed = erode(dilate(ink, w, h, 3), w, h, 3)
        return dilate(erode(closed, w, h, 2), w, h, 2)
    }

    /**
     * Найти область под точкой (x, y). [ink] — маска линий ([inkMask]), [closed] — она же с закрытым зазором
     * ([dilate] на gap), [solid] — толстое ([solidMask]; null — посчитать в окне вокруг касания).
     *
     * Что под пальцем, решает сама линия (без зазора): касание рядом с текстом или размерами — всё равно помещение.
     * По толстому — колонна (небольшое пятно) или стены (всё соединённое); по тонкой линии или тексту — помещение рядом.
     */
    fun find(ink: BooleanArray, closed: BooleanArray, w: Int, h: Int, x: Int, y: Int, gap: Int, solid: BooleanArray? = null): Result {
        if (x !in 0 until w || y !in 0 until h) return Result(FloatArray(0), 0, false, false, ERR_EMPTY)
        if (inkNear(ink, w, h, x, y, 1)) {
            val r = findSolid(ink, solid, w, h, x, y)
            if (r != null) return r
        }
        // Помещение: заливка по «бумаге» до линий (с закрытыми щелями). Палец у самой линии — шаг в сторону от неё.
        val seed = nearest(w, h, x, y, gap + 8) { !closed[it] } ?: return Result(FloatArray(0), 0, false, true, ERR_LINE)
        val region = BooleanArray(w * h)
        val res = flood(w, h, seed, region) { !closed[it] }
        // Дошла до края листа — помещение не замкнуто (или кликнули снаружи здания).
        val leaked = res.border
        // Вернуть отступ от линий: на зазор + 1 пиксель — контур ложится на линии чертежа.
        val grown = if (gap + 1 > 0) dilate(region, w, h, gap + 1) else region
        val pts = contour(grown, w, h) ?: return Result(FloatArray(0), 0, leaked, false, ERR_EMPTY)
        return Result(simplify(pts, 1.2f), res.area, leaked, false)
    }

    private fun inkNear(ink: BooleanArray, w: Int, h: Int, x: Int, y: Int, r: Int): Boolean {
        for (dy in -r..r) for (dx in -r..r) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until h && ink[yy * w + xx]) return true
        }
        return false
    }

    /** Ближайший пиксель (по расстоянию) в радиусе r, где ok. */
    private inline fun nearest(w: Int, h: Int, x: Int, y: Int, r: Int, ok: (Int) -> Boolean): Int? {
        var best = -1; var bd = Int.MAX_VALUE
        for (dy in -r..r) for (dx in -r..r) {
            val d = dx * dx + dy * dy
            if (d >= bd || d > r * r) continue
            val xx = x + dx; val yy = y + dy
            if (xx !in 0 until w || yy !in 0 until h || !ok(yy * w + xx)) continue
            bd = d; best = yy * w + xx
        }
        return if (best >= 0) best else null
    }

    /**
     * Касание по линии: толстое пятно под пальцем — колонна (небольшое) или стены (соединённое — целиком).
     * null — под пальцем тонкая линия или текст (тогда заливаем помещение рядом).
     */
    private fun findSolid(ink: BooleanArray, solid0: BooleanArray?, w: Int, h: Int, x: Int, y: Int): Result? {
        // Без готовой маски — считаем в окне вокруг касания (проверки, маленькие картинки).
        val half = 320
        val full = solid0 != null
        val wx0 = if (full) 0 else max(0, x - half); val wy0 = if (full) 0 else max(0, y - half)
        val wx1 = if (full) w else min(w, x + half); val wy1 = if (full) h else min(h, y + half)
        val ww = wx1 - wx0; val wh = wy1 - wy0
        val solid = solid0 ?: run {
            val m = BooleanArray(ww * wh)
            for (yy in 0 until wh) System.arraycopy(ink, (wy0 + yy) * w + wx0, m, yy * ww, ww)
            solidMask(m, ww, wh)
        }
        val start = nearest(ww, wh, x - wx0, y - wy0, 4) { solid[it] } ?: return null
        val region = BooleanArray(ww * wh)
        val res = flood(ww, wh, start, region) { solid[it] }
        val box = bbox(region, ww, wh)
        val bw = box[2] - box[0] + 1; val bh = box[3] - box[1] + 1
        // Мелкое и тонкое (слово, цифра размера) — не колонна. Залитый пилон (почти сплошной) — колонна при любой форме.
        if (min(bw, bh) < 6 || res.area < 50) return null
        if (min(bw, bh) < 10 && max(bw, bh) >= 2.2f * min(bw, bh) && density(ink, w, region, ww, wh, wx0, wy0) < 0.6f) return null
        val small = res.area <= w * h * 0.03 && !(res.border && !full)
        val pts = contourIn(region, ww, wh, box) ?: return null
        val s = simplify(pts, 1.0f)
        for (k in s.indices step 2) { s[k] += wx0.toFloat(); s[k + 1] += wy0.toFloat() }
        return Result(s, res.area, false, true, kind = if (small) COLUMN else WALL)
    }

    /** Доля пикселей линий (без закрытия) внутри пятна: залитое — около 1, текст и штриховка — меньше. */
    private fun density(ink: BooleanArray, w: Int, region: BooleanArray, ww: Int, wh: Int, wx0: Int, wy0: Int): Float {
        var n = 0; var k = 0
        for (yy in 0 until wh) for (xx in 0 until ww) if (region[yy * ww + xx]) { n++; if (ink[(wy0 + yy) * w + wx0 + xx]) k++ }
        return if (n == 0) 0f else k.toFloat() / n
    }

    private fun bbox(m: BooleanArray, w: Int, h: Int): IntArray {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) { val o = y * w; for (x in 0 until w) if (m[o + x]) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y } }
        return intArrayOf(l, t, max(l, r), max(t, b))
    }

    /** Контур маски внутри рамки box (без копии всего листа) — в координатах маски. */
    private fun contourIn(m: BooleanArray, w: Int, h: Int, box: IntArray): FloatArray? {
        val l = max(0, box[0] - 1); val t = max(0, box[1] - 1)
        val r = min(w - 1, box[2] + 1); val b = min(h - 1, box[3] + 1)
        val sw = r - l + 1; val sh = b - t + 1
        val sub = BooleanArray(sw * sh)
        for (yy in 0 until sh) System.arraycopy(m, (t + yy) * w + l, sub, yy * sw, sw)
        val c = contour(sub, sw, sh) ?: return null
        for (k in c.indices step 2) { c[k] += l.toFloat(); c[k + 1] += t.toFloat() }
        return c
    }

    /**
     * «Кисть по стенам» (0.23.1): провели пальцем вдоль стен — берутся толстые места чертежа под кистью (стены,
     * колонны) — каждое своим куском; если под кистью толстого почти нет — просто след кисти.
     * [path] — точки x, y в пикселях, [radius] — полуширина кисти.
     */
    fun brush(solid: BooleanArray, w: Int, h: Int, path: FloatArray, radius: Float): List<FloatArray> {
        if (path.size < 2) return emptyList()
        val r = max(2f, radius)
        var l = w; var t = h; var rr = 0; var bt = 0
        for (k in path.indices step 2) {
            l = min(l, (path[k] - r).toInt()); rr = max(rr, (path[k] + r).toInt() + 1)
            t = min(t, (path[k + 1] - r).toInt()); bt = max(bt, (path[k + 1] + r).toInt() + 1)
        }
        l = max(0, l); t = max(0, t); rr = min(w, rr); bt = min(h, bt)
        if (rr <= l || bt <= t) return emptyList()
        val sw = rr - l; val sh = bt - t
        val stroke = BooleanArray(sw * sh)
        // След кисти: кружки вдоль пути с шагом в полрадиуса.
        val step = max(1f, r / 2f)
        fun stamp(cx: Float, cy: Float) {
            val x0 = max(l, (cx - r).toInt()); val x1 = min(rr - 1, (cx + r).toInt())
            val y0 = max(t, (cy - r).toInt()); val y1 = min(bt - 1, (cy + r).toInt())
            for (yy in y0..y1) for (xx in x0..x1) if ((xx - cx) * (xx - cx) + (yy - cy) * (yy - cy) <= r * r) stroke[(yy - t) * sw + (xx - l)] = true
        }
        stamp(path[0], path[1])
        for (k in 2 until path.size step 2) {
            val ax = path[k - 2]; val ay = path[k - 1]; val bx = path[k]; val by = path[k + 1]
            val len = kotlin.math.hypot(bx - ax, by - ay)
            val n = max(1, (len / step).toInt())
            for (i in 1..n) stamp(ax + (bx - ax) * i / n, ay + (by - ay) * i / n)
        }
        var strokeArea = 0; var hit = 0
        val m = BooleanArray(sw * sh)
        for (yy in 0 until sh) for (xx in 0 until sw) {
            val i = yy * sw + xx
            if (!stroke[i]) continue
            strokeArea++
            if (solid[(t + yy) * w + l + xx]) { m[i] = true; hit++ }
        }
        // Под кистью почти нет стен — красим след кисти.
        val mask = if (hit < strokeArea * 0.12f) stroke else m
        val out = ArrayList<FloatArray>()
        val seen = BooleanArray(sw * sh)
        for (i in mask.indices) {
            if (!mask[i] || seen[i]) continue
            val comp = BooleanArray(sw * sh)
            val f = flood(sw, sh, i, comp) { mask[it] && !seen[it] }
            for (j in comp.indices) if (comp[j]) seen[j] = true
            if (f.area < 30) continue // крошки
            val c = contourIn(comp, sw, sh, bbox(comp, sw, sh)) ?: continue
            val sp = simplify(c, 1.0f)
            for (k in sp.indices step 2) { sp[k] += l.toFloat(); sp[k + 1] += t.toFloat() }
            out += sp
        }
        return out
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
