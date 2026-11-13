package party.shulya.freewrite

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Привязка к углам чертежа (0.26.1, Владимир: «чтоб у лупы работала привязка как в автокаде — навожусь на угол, и он
 * привязывает»). По линиям PDF листа: концы и изломы линий (углы стен, колонн, отверстий) и пересечения линий рядом с
 * пальцем. Размеры, текст, оси и штриховка в привязку не идут (как и в поиске элементов).
 *
 * Координаты запроса и ответа — доли картинки листа целиком (как у областей); внутри — пункты листа PDF.
 */
class PlanSnap(page: PdfVector.Page) {
    private val pw = page.w
    private val ph = page.h
    private var segs = FloatArray(4096)
    private var n = 0
    private val grid = HashMap<Long, IntArrayList>()

    /** Что нашлось: точка (доли листа) и вид — [END] угол/конец линии или [CROSS] пересечение. */
    class Hit(val fx: Float, val fy: Float, val kind: Int)

    companion object {
        const val END = 1
        const val CROSS = 2
        private const val CELL = 6f
    }

    private class IntArrayList {
        var a = IntArray(4)
        var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    }

    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    init {
        for (p in page.paths) {
            val role = PlanElements.roleOf(p.layer)
            if (role == PlanElements.Role.ANNOT || role == PlanElements.Role.HATCH) continue
            for (r in p.rings) {
                var i = 0
                while (i + 3 < r.size) {
                    val x0 = r[i]; val y0 = r[i + 1]; val x1 = r[i + 2]; val y1 = r[i + 3]
                    i += 2
                    if (hypot(x1 - x0, y1 - y0) < 0.05f) continue
                    add(x0, y0, x1, y1)
                }
            }
        }
    }

    private fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
        if ((n + 1) * 4 > segs.size) segs = segs.copyOf(segs.size * 2)
        val id = n++
        val o = id * 4
        segs[o] = x0; segs[o + 1] = y0; segs[o + 2] = x1; segs[o + 3] = y1
        // Клетки вдоль отрезка.
        val len = hypot(x1 - x0, y1 - y0)
        val steps = max(1, ceil(len / (CELL / 2f)).toInt())
        var lastKey = Long.MIN_VALUE
        for (q in 0..steps) {
            val x = x0 + (x1 - x0) * q / steps; val y = y0 + (y1 - y0) * q / steps
            val k = key(floor(x / CELL).toInt(), floor(y / CELL).toInt())
            if (k == lastKey) continue
            lastKey = k
            val l = grid.getOrPut(k) { IntArrayList() }
            if (l.size == 0 || l.a[l.size - 1] != id) l.add(id)
        }
    }

    val size get() = n

    /**
     * Ближайший угол или пересечение к точке (доли листа) в радиусе [rf] (доли ширины листа). Угол (конец линии)
     * при равном расстоянии главнее пересечения.
     */
    fun near(fx: Float, fy: Float, rf: Float): Hit? {
        val x = fx * pw; val y = fy * ph; val rad = rf * pw
        if (rad <= 0f) return null
        val gx0 = floor((x - rad) / CELL).toInt(); val gx1 = floor((x + rad) / CELL).toInt()
        val gy0 = floor((y - rad) / CELL).toInt(); val gy1 = floor((y + rad) / CELL).toInt()
        if ((gx1 - gx0 + 1).toLong() * (gy1 - gy0 + 1) > 4000) return null
        val ids = LinkedHashSet<Int>()
        for (gx in gx0..gx1) for (gy in gy0..gy1) grid[key(gx, gy)]?.let { l -> for (k in 0 until l.size) ids += l.a[k] }
        if (ids.isEmpty()) return null
        var best = rad; var bx = 0f; var by = 0f; var kind = 0
        fun cand(px: Float, py: Float, k: Int) {
            // Пересечение уступает углу на равном месте (небольшая фора углу).
            val d = hypot(px - x, py - y) * (if (k == CROSS) 1.08f else 1f)
            if (d < best) { best = d; bx = px; by = py; kind = k }
        }
        val near = ArrayList<Int>()
        for (id in ids) {
            val o = id * 4
            cand(segs[o], segs[o + 1], END); cand(segs[o + 2], segs[o + 3], END)
            if (distToSeg(x, y, o) <= rad) near += id
        }
        // Пересечения линий рядом (не больше 80 линий — дальше дорого и не нужно).
        val m = min(near.size, 80)
        for (a in 0 until m) for (b in a + 1 until m) {
            val p = cross(near[a] * 4, near[b] * 4) ?: continue
            cand(p[0], p[1], CROSS)
        }
        if (kind == 0) return null
        return Hit(bx / pw, by / ph, kind)
    }

    private fun distToSeg(px: Float, py: Float, o: Int): Float {
        val ax = segs[o]; val ay = segs[o + 1]; val dx = segs[o + 2] - ax; val dy = segs[o + 3] - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - ax - t * dx, py - ay - t * dy)
    }

    /** Точка пересечения двух отрезков (с допуском на концах), null — параллельны или не пересекаются. */
    private fun cross(o1: Int, o2: Int): FloatArray? {
        val x1 = segs[o1]; val y1 = segs[o1 + 1]; val x2 = segs[o1 + 2]; val y2 = segs[o1 + 3]
        val x3 = segs[o2]; val y3 = segs[o2 + 1]; val x4 = segs[o2 + 2]; val y4 = segs[o2 + 3]
        val d = (x2 - x1) * (y4 - y3) - (y2 - y1) * (x4 - x3)
        val l1 = hypot(x2 - x1, y2 - y1); val l2 = hypot(x4 - x3, y4 - y3)
        if (abs(d) < 1e-6f * max(1f, l1 * l2) || abs(d) / (l1 * l2) < 0.05f) return null // почти параллельны
        val t = ((x3 - x1) * (y4 - y3) - (y3 - y1) * (x4 - x3)) / d
        val u = ((x3 - x1) * (y2 - y1) - (y3 - y1) * (x2 - x1)) / d
        val et = 0.3f / l1; val eu = 0.3f / l2
        if (t < -et || t > 1 + et || u < -eu || u > 1 + eu) return null
        return floatArrayOf(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    }
}
