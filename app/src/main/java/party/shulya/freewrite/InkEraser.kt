package party.shulya.freewrite

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ластик «как в рисовалке» (0.27.6, Владимир: «провёл длинную линию, хочу стереть ластиком кусочек — удаляется вся; было бы
 * хорошо, если бы удалялся только участок, куда ластик попадает»). Из штриха вырезается то, что под ластиком: круг радиуса r
 * вокруг пера (с учётом толщины линии — круглые концы оставшихся кусков не залезают под ластик). Куски по обе стороны остаются
 * отдельными штрихами того же вида, цвета и толщины; в точках разреза нажим — по середине между соседними точками.
 */
object InkEraser {

    /** Кусок короче этого (в долях толщины линии) не оставляем — это точка-огрызок у края ластика. */
    private const val MIN_PIECE = 0.35f

    /**
     * Что останется от штриха [d], если ластик радиуса [r] стоит в ([cx], [cy]): null — ластик штрих не задел (он как был);
     * пустой список — стёрт целиком; иначе — куски (новые штрихи, новые номера).
     */
    fun cut(d: Drawing, cx: Float, cy: Float, r: Float): List<Drawing>? {
        val pts = d.pts
        val n = pts.size / 3
        if (n == 0) return null
        val rr = r + d.width / 2f
        val bb = d.bounds()
        if (cx < bb.left - rr || cx > bb.right + rr || cy < bb.top - rr || cy > bb.bottom + rr) return null
        val r2 = rr * rr
        fun inside(i: Int): Boolean {
            val dx = pts[i * 3] - cx; val dy = pts[i * 3 + 1] - cy
            return dx * dx + dy * dy < r2
        }
        if (n == 1) return if (inside(0)) emptyList() else null

        val pieces = ArrayList<FloatArray>()
        val cur = Builder()
        var touched = false
        if (!inside(0)) cur.add(pts[0], pts[1], pts[2])
        for (i in 0 until n - 1) {
            val ax = pts[i * 3]; val ay = pts[i * 3 + 1]; val ap = pts[i * 3 + 2]
            val bx = pts[i * 3 + 3]; val by = pts[i * 3 + 4]; val bp = pts[i * 3 + 5]
            // Где отрезок a→b внутри круга: |a + t(b − a) − c|² < R², t в [0, 1].
            val dx = bx - ax; val dy = by - ay
            val fx = ax - cx; val fy = ay - cy
            val qa = dx * dx + dy * dy
            val qb = 2f * (fx * dx + fy * dy)
            val qc = fx * fx + fy * fy - r2
            var t1 = 2f; var t2 = -1f
            if (qa < 1e-12f) {
                if (qc < 0f) { t1 = 0f; t2 = 1f }
            } else {
                val disc = qb * qb - 4f * qa * qc
                if (disc > 0f) {
                    val s = sqrt(disc)
                    t1 = (-qb - s) / (2f * qa); t2 = (-qb + s) / (2f * qa)
                }
            }
            val lo = max(t1, 0f); val hi = min(t2, 1f)
            if (lo >= hi) {
                // Отрезок мимо ластика.
                if (cur.isEmpty) cur.add(ax, ay, ap)
                cur.add(bx, by, bp)
                continue
            }
            touched = true
            if (lo > 0f) {
                // Вошли в круг посреди отрезка: кусок до края ластика.
                if (cur.isEmpty) cur.add(ax, ay, ap)
                cur.add(ax + dx * lo, ay + dy * lo, ap + (bp - ap) * lo)
            }
            cur.takeTo(pieces)
            if (hi < 1f) {
                // Вышли из круга: новый кусок от края ластика.
                cur.add(ax + dx * hi, ay + dy * hi, ap + (bp - ap) * hi)
                cur.add(bx, by, bp)
            }
        }
        if (!touched) return null
        cur.takeTo(pieces)
        val minLen = d.width * MIN_PIECE
        return pieces.filter { length(it) >= minLen }.map { Drawing(d.kind, d.color, d.width, it) }
    }

    private fun length(p: FloatArray): Float {
        var s = 0f
        var i = 3
        while (i + 1 < p.size) { s += hypot(p[i] - p[i - 3], p[i + 1] - p[i - 2]); i += 3 }
        return s
    }

    /**
     * Ластик в ([cx], [cy]) по списку штрихов: новый список (стёртое вырезано, куски на месте исходного штриха — порядок
     * рисования тот же) или null — ничего не задели.
     */
    fun eraseIn(strokes: List<Drawing>, cx: Float, cy: Float, r: Float): ArrayList<Drawing>? {
        var out: ArrayList<Drawing>? = null
        for ((i, d) in strokes.withIndex()) {
            val res = cut(d, cx, cy, r)
            if (res == null) { out?.add(d); continue }
            if (out == null) { out = ArrayList(strokes.size + 4); for (k in 0 until i) out.add(strokes[k]) }
            out.addAll(res)
        }
        return out
    }

    /** Точки куска (x, y, нажим). */
    private class Builder {
        private var a = FloatArray(48)
        private var n = 0
        val isEmpty get() = n == 0
        fun add(x: Float, y: Float, p: Float) {
            if (n + 3 > a.size) a = a.copyOf(a.size * 2)
            // Совпадает с предыдущей — не добавлять (у края ластика бывают двойные точки).
            if (n >= 3 && a[n - 3] == x && a[n - 2] == y) return
            a[n] = x; a[n + 1] = y; a[n + 2] = p; n += 3
        }
        fun takeTo(out: MutableList<FloatArray>) {
            if (n >= 6) out += a.copyOf(n)
            n = 0
        }
    }
}
