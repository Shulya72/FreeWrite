package party.shulya.freewrite

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Раскладка таблицы ИД в единицах при размере 1: границы столбцов, строки, ширина, высота. */
class TableLayout(val key: Long, val cols: IntArray, val colX: FloatArray, val rows: List<BoardRec>, val w: Float, val h: Float, val info: String)

/** Что под пальцем в таблице ИД. */
class TableHit(val part: Int, val rec: BoardRec?, val col: Int) {
    companion object {
        const val TITLE = 0
        const val HEAD = 1
        const val CELL = 2
        const val ADD = 3
    }
}

/**
 * Рисование таблиц ИД (0.20, FW-012). Все строки одной высоты, столбцы одной ширины на всю таблицу
 * (по самому длинному значению), размер — один на таблицу. Издалека — только рамка и цветные полоски ИГС.
 */
class TableDraw {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    companion object {
        const val TITLE_H = 40f
        const val HEAD_H = 26f
        const val ROW_H = 30f
        const val FOOT_H = 30f
        const val PAD = 9f
        const val FONT = 13f
        const val HEAD_FONT = 11f
        const val TITLE_FONT = 19f
        const val BOX = 17f
        private val TITLE_REG = Color.rgb(234, 239, 247)
        private val TITLE_LIST = Color.rgb(253, 236, 214)
        private val HEAD_BG = Color.rgb(246, 248, 251)
        private val ZEBRA = Color.rgb(250, 251, 253)
    }

    fun rowsTop() = TITLE_H + HEAD_H

    fun layout(b: Board, t: BoardTable): TableLayout {
        val rows = IdTables.rowsOf(b, t)
        val cols = if (t.isList) Col.LIST else Col.REG
        var h = 1125899906842597L
        h = 31 * h + t.title.hashCode(); h = 31 * h + t.type.hashCode()
        for (s in b.statuses) { h = 31 * h + s.id.hashCode(); h = 31 * h + s.name.hashCode(); h = 31 * h + (if (s.done) 1 else 0) }
        for (r in rows) {
            h = 31 * h + r.id.hashCode(); h = 31 * h + r.zah.hashCode(); h = 31 * h + r.con.hashCode(); h = 31 * h + r.work.hashCode()
            h = 31 * h + r.date.hashCode(); h = 31 * h + r.sid.hashCode(); h = 31 * h + r.note.hashCode(); h = 31 * h + r.who.hashCode()
            h = 31 * h + (if (r.ck) 1 else 0) + (if (r.dwg) 2 else 0)
            if (t.isList) h = 31 * h + (IdTables.registerOf(b, r)?.title?.hashCode() ?: 0)
        }
        t.lay?.let { if (it.key == h) return it }

        val n = rows.size
        val done = rows.count { b.status(it.sid).done }
        val info = when {
            t.isList -> "подборка · ${Ui.plural(n, "строка", "строки", "строк")}"
            b.statuses(BoardStatus.ID).any { it.done } -> "${Ui.plural(n, "схема", "схемы", "схем")} · сдано $done"
            else -> Ui.plural(n, "схема", "схемы", "схем")
        }
        val widths = FloatArray(cols.size)
        for ((ci, c) in cols.withIndex()) {
            text.isFakeBoldText = true
            text.textSize = HEAD_FONT
            var w = text.measureText(Col.NAMES[c]) + 2 * PAD
            text.isFakeBoldText = false
            text.textSize = FONT
            when (c) {
                Col.CK, Col.DWG -> w = max(w, BOX + 2 * PAD)
                Col.DATE -> w = max(w, text.measureText("00.00.00") + 2 * PAD)
                Col.IGS -> {
                    text.isFakeBoldText = true
                    for (r in rows) w = max(w, text.measureText(b.status(r.sid).name) + 22f + 2 * PAD)
                    text.isFakeBoldText = false
                    w = min(max(w, 120f), 280f)
                }
                else -> {
                    for (r in rows) w = max(w, text.measureText(IdTables.cellText(b, r, c)) + 2 * PAD)
                    w = min(max(w, if (c == Col.ZAH) 64f else 56f), if (c == Col.NOTE) 260f else 230f)
                }
            }
            widths[ci] = w
        }
        var total = widths.sum()
        text.isFakeBoldText = true
        text.textSize = TITLE_FONT
        val titleW = text.measureText(t.title.ifEmpty { "Без названия" })
        text.isFakeBoldText = false
        text.textSize = 12f
        val need = PAD * 2 + titleW + 24f + text.measureText(info) + PAD
        if (need > total) { widths[widths.size - 1] += need - total; total = need }
        val colX = FloatArray(cols.size + 1)
        for (i in cols.indices) colX[i + 1] = colX[i] + widths[i]
        val height = rowsTop() + max(1, n) * ROW_H + FOOT_H
        return TableLayout(h, cols, colX, rows, total, height, info).also { t.lay = it }
    }

    fun bounds(b: Board, t: BoardTable, out: RectF): RectF {
        val l = layout(b, t)
        out.set(t.x, t.y, t.x + l.w * t.scale, t.y + l.h * t.scale)
        return out
    }

    fun hit(b: Board, t: BoardTable, x: Float, y: Float): TableHit? {
        val l = layout(b, t)
        val u = (x - t.x) / t.scale
        val v = (y - t.y) / t.scale
        if (u < 0f || u > l.w || v < 0f || v > l.h) return null
        if (v < TITLE_H) return TableHit(TableHit.TITLE, null, -1)
        var col = l.cols.last()
        for (i in l.cols.indices) if (u < l.colX[i + 1]) { col = l.cols[i]; break }
        if (v < rowsTop()) return TableHit(TableHit.HEAD, null, col)
        val ri = ((v - rowsTop()) / ROW_H).toInt()
        if (ri < l.rows.size) return TableHit(TableHit.CELL, l.rows[ri], col)
        return TableHit(TableHit.ADD, null, col)
    }

    /** Верх строки rec на доске (null — строки в таблице нет). */
    fun rowRect(b: Board, t: BoardTable, rec: BoardRec, out: RectF): RectF? {
        val l = layout(b, t)
        val i = l.rows.indexOfFirst { it === rec }
        if (i < 0) return null
        val k = t.scale
        out.set(t.x, t.y + (rowsTop() + i * ROW_H) * k, t.x + l.w * k, t.y + (rowsTop() + (i + 1) * ROW_H) * k)
        return out
    }

    /** vis — видимая часть доски (строки вне её не рисуются); hl — подсвеченный статус: остальные строки притушены. */
    fun draw(canvas: Canvas, b: Board, t: BoardTable, z: Float, vis: RectF, hl: String? = null, marked: Set<BoardRec>? = null) {
        val l = layout(b, t)
        val k = t.scale
        val x0 = t.x; val y0 = t.y
        val w = l.w * k; val h = l.h * k
        rect.set(x0, y0, x0 + w, y0 + h)
        fill.color = Color.WHITE
        canvas.drawRoundRect(rect, 8f * k, 8f * k, fill)
        // Шапка с названием.
        fill.color = if (t.isList) TITLE_LIST else TITLE_REG
        canvas.save()
        canvas.clipRect(x0, y0, x0 + w, y0 + TITLE_H * k)
        canvas.drawRoundRect(rect, 8f * k, 8f * k, fill)
        canvas.restore()
        val far = FONT * k * z < 4f
        val top = y0 + rowsTop() * k
        // Строки (только видимые).
        val first = max(0, ((vis.top - top) / (ROW_H * k)).toInt())
        val last = min(l.rows.size - 1, ((vis.bottom - top) / (ROW_H * k)).toInt())
        val igsI = l.cols.indexOf(Col.IGS)
        if (far) {
            // Издалека: цветные полоски ИГС — видно, где что, без надписей.
            if (ROW_H * k * z >= 1f && igsI >= 0) for (i in first..last) {
                val r = l.rows[i]
                val c = b.status(r.sid).color
                fill.color = if (hl != null && r.sid != hl) Color.rgb(225, 228, 234) else c
                val ry = top + i * ROW_H * k
                rect.set(x0 + l.colX[igsI] * k, ry + 3f * k, x0 + l.colX[igsI + 1] * k, ry + (ROW_H - 3f) * k)
                canvas.drawRect(rect, fill)
            }
            outline(canvas, x0, y0, w, h, k, t)
            return
        }
        fill.color = HEAD_BG
        canvas.drawRect(x0, y0 + TITLE_H * k, x0 + w, top, fill)
        for (i in first..last) {
            val r = l.rows[i]
            val ry = top + i * ROW_H * k
            if (i % 2 == 1) { fill.color = ZEBRA; canvas.drawRect(x0, ry, x0 + w, ry + ROW_H * k, fill) }
            if (marked != null && r in marked) { fill.color = Color.argb(60, 212, 71, 12); canvas.drawRect(x0, ry, x0 + w, ry + ROW_H * k, fill) }
            for ((ci, c) in l.cols.withIndex()) cell(canvas, b, r, c, x0 + l.colX[ci] * k, ry, (l.colX[ci + 1] - l.colX[ci]) * k, k)
            if (hl != null && r.sid != hl) {
                fill.color = Ui.PAPER; fill.alpha = 200
                canvas.drawRect(x0, ry, x0 + w, ry + ROW_H * k, fill)
                fill.alpha = 255
            }
        }
        // Сетка.
        line.color = Ui.RULE
        line.strokeWidth = 1f * k
        val bottom = top + max(1, l.rows.size) * ROW_H * k
        for (i in 1 until l.cols.size) canvas.drawLine(x0 + l.colX[i] * k, y0 + TITLE_H * k, x0 + l.colX[i] * k, bottom, line)
        canvas.drawLine(x0, y0 + TITLE_H * k, x0 + w, y0 + TITLE_H * k, line)
        canvas.drawLine(x0, top, x0 + w, top, line)
        line.color = Color.rgb(232, 236, 242)
        for (i in max(first, 1)..last) { val ry = top + i * ROW_H * k; canvas.drawLine(x0, ry, x0 + w, ry, line) }
        line.color = Ui.RULE
        canvas.drawLine(x0, bottom, x0 + w, bottom, line)
        // Названия столбцов.
        text.textSize = HEAD_FONT * k
        text.isFakeBoldText = true
        text.color = Ui.INK2
        for ((ci, c) in l.cols.withIndex()) canvas.drawText(Col.NAMES[c], x0 + (l.colX[ci] + PAD) * k, y0 + (TITLE_H + HEAD_H / 2f) * k + text.textSize * 0.36f, text)
        // Название и сводка.
        text.textSize = TITLE_FONT * k
        text.color = Ui.INK
        canvas.drawText(t.title.ifEmpty { "Без названия" }, x0 + PAD * 1.4f * k, y0 + TITLE_H / 2f * k + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
        text.textSize = 12f * k
        text.color = Ui.INK2
        val iw = text.measureText(l.info)
        canvas.drawText(l.info, x0 + w - PAD * 1.4f * k - iw, y0 + TITLE_H / 2f * k + text.textSize * 0.36f, text)
        // Пустая таблица — подсказка.
        if (l.rows.isEmpty()) {
            text.textSize = FONT * k
            text.color = Ui.MUTED
            canvas.drawText("Строк пока нет — нажмите «+ строка»", x0 + PAD * k, top + ROW_H / 2f * k + text.textSize * 0.36f, text)
        }
        // «+ строка».
        text.textSize = FONT * k
        text.isFakeBoldText = true
        text.color = Ui.ACCENT
        canvas.drawText(if (t.isList) "+ строки" else "+ строка", x0 + PAD * k, bottom + FOOT_H / 2f * k + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
        outline(canvas, x0, y0, w, h, k, t)
    }

    private fun outline(canvas: Canvas, x0: Float, y0: Float, w: Float, h: Float, k: Float, t: BoardTable) {
        rect.set(x0, y0, x0 + w, y0 + h)
        line.color = if (t.isList) Color.rgb(226, 160, 110) else Color.rgb(170, 182, 204)
        line.strokeWidth = 1.5f * k
        canvas.drawRoundRect(rect, 8f * k, 8f * k, line)
    }

    private fun cell(canvas: Canvas, b: Board, r: BoardRec, c: Int, cx: Float, ry: Float, cw: Float, k: Float) {
        val cy = ry + ROW_H / 2f * k
        when (c) {
            Col.CK, Col.DWG -> {
                val on = if (c == Col.CK) r.ck else r.dwg
                val s = BOX * k
                val bx = cx + PAD * k
                rect.set(bx, cy - s / 2f, bx + s, cy + s / 2f)
                val col = if (on) Ui.OK else Ui.BAD
                fill.color = Board.statusBg(col)
                canvas.drawRoundRect(rect, 4f * k, 4f * k, fill)
                line.color = col
                line.strokeWidth = 1.3f * k
                canvas.drawRoundRect(rect, 4f * k, 4f * k, line)
                line.strokeWidth = 2.2f * k
                line.strokeCap = Paint.Cap.ROUND
                if (on) {
                    canvas.drawLine(bx + s * 0.22f, cy, bx + s * 0.42f, cy + s * 0.22f, line)
                    canvas.drawLine(bx + s * 0.42f, cy + s * 0.22f, bx + s * 0.8f, cy - s * 0.24f, line)
                } else {
                    canvas.drawLine(bx + s * 0.3f, cy - s * 0.2f, bx + s * 0.7f, cy + s * 0.2f, line)
                    canvas.drawLine(bx + s * 0.7f, cy - s * 0.2f, bx + s * 0.3f, cy + s * 0.2f, line)
                }
                line.strokeCap = Paint.Cap.BUTT
            }
            Col.IGS -> {
                val st = b.status(r.sid)
                text.textSize = FONT * k
                text.isFakeBoldText = true
                val name = fit(st.name, cw - 2 * PAD * k - 22f * k)
                val pw = text.measureText(name) + 22f * k
                rect.set(cx + PAD * k, ry + 4.5f * k, cx + PAD * k + pw, ry + (ROW_H - 4.5f) * k)
                val fg = Board.statusFg(st.color)
                fill.color = Board.statusBg(st.color)
                canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
                line.color = fg
                line.strokeWidth = 1.2f * k
                canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, line)
                text.color = fg
                canvas.drawText(name, cx + (PAD + 11f) * k, cy + text.textSize * 0.36f, text)
                text.isFakeBoldText = false
            }
            else -> {
                val s = IdTables.cellText(b, r, c)
                if (s.isEmpty()) return
                text.textSize = FONT * k
                text.color = if (c == Col.ZAH || c == Col.CIPHER) Ui.INK else Ui.INK2
                text.isFakeBoldText = c == Col.ZAH || c == Col.CIPHER
                canvas.drawText(fit(s, cw - 2 * PAD * k), cx + PAD * k, cy + text.textSize * 0.36f, text)
                text.isFakeBoldText = false
            }
        }
    }

    /** Текст, обрезанный «…» по ширине. */
    private fun fit(s: String, w: Float): String {
        if (w <= 0f) return ""
        if (text.measureText(s) <= w) return s
        val ell = "…"
        val n = text.breakText(s, true, w - text.measureText(ell), null)
        return s.take(max(0, n)) + ell
    }
}
