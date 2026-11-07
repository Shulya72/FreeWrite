package party.shulya.freewrite

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Рисование штрихов — общее для листа записи и доски.
 * Ручка — толщина по нажиму пера; маркер и «письмо в текст» — ровная сглаженная линия.
 */
class InkPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** Живой или ещё не распознанный штрих. */
    fun stroke(canvas: Canvas, s: InkStroke, style: NoteInkView.StrokeStyle, alpha: Int) {
        val pts = s.points
        if (pts.isEmpty()) return
        paint.color = style.color
        paint.alpha = if (style.kind == "marker") alpha * 80 / 255 else alpha
        if (pts.size == 1 || style.kind != "pen") {
            paint.strokeWidth = style.width
            if (pts.size == 1) {
                dot(canvas, pts[0].x, pts[0].y, style.width)
                return
            }
            path.reset()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                path.quadTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
            }
            path.lineTo(pts[pts.size - 1].x, pts[pts.size - 1].y)
            canvas.drawPath(path, paint)
            return
        }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            paint.strokeWidth = style.width * (0.45f + 1.1f * (a.pressure + b.pressure) / 2f)
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }

    /**
     * Сохранённый рисунок (точки x, y, нажим подряд).
     * Контур строится один раз и хранится в рисунке. Ручка с разным нажимом рисуется отрезками
     * разной толщины; fast = true (доска издалека) — одним контуром ровной толщины, это намного быстрее.
     */
    fun drawing(canvas: Canvas, d: Drawing, alpha: Int = 255, fast: Boolean = false) {
        val pts = d.pts
        val n = pts.size / 3
        if (n == 0) return
        paint.color = d.color
        paint.alpha = if (d.kind == "marker") alpha * 80 / 255 else alpha
        if (n == 1) {
            dot(canvas, pts[0], pts[1], d.width)
            return
        }
        if (d.kind == "pen" && !fast && !d.uniform) {
            for (i in 1 until n) {
                paint.strokeWidth = d.width * (0.45f + 1.1f * (pts[i * 3 - 1] + pts[i * 3 + 2]) / 2f)
                canvas.drawLine(pts[i * 3 - 3], pts[i * 3 - 2], pts[i * 3], pts[i * 3 + 1], paint)
            }
            return
        }
        val p = d.path ?: buildPath(d).also { d.path = it }
        paint.strokeWidth = if (d.kind == "pen" && d.uniform) d.width * (0.45f + 1.1f * pts[2]) else d.width
        canvas.drawPath(p, paint)
    }

    private fun buildPath(d: Drawing): Path {
        val pts = d.pts
        val n = pts.size / 3
        val p = Path()
        p.moveTo(pts[0], pts[1])
        if (d.kind == "pen" && d.uniform) {
            // Ровные фигуры — прямые отрезки, углы остаются углами.
            for (i in 1 until n) p.lineTo(pts[i * 3], pts[i * 3 + 1])
            return p
        }
        for (i in 1 until n) {
            val ax = pts[i * 3 - 3]; val ay = pts[i * 3 - 2]
            val bx = pts[i * 3]; val by = pts[i * 3 + 1]
            p.quadTo(ax, ay, (ax + bx) / 2f, (ay + by) / 2f)
        }
        p.lineTo(pts[(n - 1) * 3], pts[(n - 1) * 3 + 1])
        return p
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, width: Float) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, width * 0.6f, paint)
        paint.style = Paint.Style.STROKE
    }
}
