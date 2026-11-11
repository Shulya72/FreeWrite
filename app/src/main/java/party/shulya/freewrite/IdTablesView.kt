package party.shulya.freewrite

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Раскладка таблицы ИД в единицах при размере 1: столбцы и их границы, строки, ширина, высота, надпись в шапке. */
class TableLayout(val key: Long, val cols: List<TableCol>, val colX: FloatArray, val rows: List<BoardRec>, val w: Float, val h: Float, val info: String, val addSplit: Float)

/** Что под пальцем в таблице ИД: шапка, названия столбцов, ячейка (строка + столбец), «+ строка», «+ следующая захватка». */
class TableHit(val part: Int, val rec: BoardRec?, val col: String?) {
    companion object {
        const val TITLE = 0
        const val HEAD = 1
        const val CELL = 2
        const val ADD = 3
        const val ADD_NEXT = 4
    }
}

/**
 * Рисование таблиц ИД (ТЗ «Учёт ИГС», раздел 3.1): все строки одной высоты, столбец шириной по самому длинному
 * значению, размер один на всю таблицу. Статусы — цветные «таблетки» с подписью. Издалека — рамка и цветные полоски ИГС.
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
        private val TITLE_BG = Color.rgb(227, 234, 244)
        private val HEAD_BG = Color.rgb(241, 244, 248)
        private val ZEBRA = Color.rgb(250, 251, 253)
        private val MARK = Color.rgb(255, 241, 224)
        private val SEL = Color.rgb(227, 236, 252)
    }

    fun rowsTop() = TITLE_H + HEAD_H

    /**
     * Ширина текста при размере size — мерим крупно и уменьшаем, с запасом 6 %: на экране шрифт другого размера,
     * а ширина букв меняется не строго пропорционально — без запаса подписи обрезались бы «…».
     */
    private fun tw(s: String, size: Float, bold: Boolean): Float {
        text.textSize = 100f
        text.isFakeBoldText = bold
        val w = text.measureText(s) * size / 100f * 1.06f + 2f
        text.isFakeBoldText = false
        return w
    }

    fun layout(b: Board, t: BoardTable): TableLayout {
        val rows = IdTables.rowsOf(b, t)
        val cols = t.visibleCols()
        var h = 1125899906842597L
        h = 31 * h + t.title.hashCode()
        for (c in cols) { h = 31 * h + c.k.hashCode(); h = 31 * h + c.name.hashCode() }
        for (s in b.statuses) { h = 31 * h + s.id.hashCode(); h = 31 * h + s.name.hashCode(); h = 31 * h + (if (s.done) 1 else 0) }
        val f = t.filter
        h = 31 * h + (f?.hashCode() ?: 0)
        for (r in rows) {
            h = 31 * h + r.id.hashCode()
            for (c in cols) h = 31 * h + r.get(c.k).hashCode()
        }
        t.lay?.let { if (it.key == h) return it }

        val all = if (f == null || f.isEmpty) rows.size else IdTables.allRows(b, t).size
        val done = rows.count { IdTables.status(b, BoardStatus.ID, it.igs).done }
        var info = "${Ui.plural(rows.size, "схема", "схемы", "схем")} · сдано в ПТО $done"
        if (f != null && !f.isEmpty) info = "отбор: ${rows.size} из $all · сдано $done"
        val widths = FloatArray(cols.size)
        for ((ci, c) in cols.withIndex()) {
            var w = tw(c.title(), HEAD_FONT, true) + 2 * PAD
            if (c.isStatus) {
                val seen = HashSet<String>()
                for (r in rows) { val n = IdTables.cellText(b, r, c.k); if (seen.add(n)) w = max(w, tw(n, FONT, true) + 22f + 2 * PAD) }
                w = min(max(w, if (c.k == TableCol.IGS) 120f else 70f), 300f)
            } else {
                if (c.isDate) w = max(w, tw("00.00.00", FONT, false) + 2 * PAD)
                else for (r in rows) w = max(w, tw(IdTables.cellText(b, r, c.k), FONT, c.k == TableCol.ZAH) + 2 * PAD)
                w = min(max(w, if (c.k == TableCol.ZAH) 64f else 56f), if (c.k == TableCol.NOTE) 260f else 230f)
            }
            widths[ci] = w
        }
        var total = widths.sum()
        val titleW = tw(t.title.ifEmpty { "Без шифра" }, TITLE_FONT, true)
        val need = PAD * 2 + titleW + 24f + tw(info, 12f, false) + PAD
        val addW = PAD + tw("+ строка", FONT, true) + PAD * 2
        val need2 = addW + tw("+ следующая захватка", FONT, true) + PAD * 2
        val needAll = max(need, need2)
        if (cols.isNotEmpty() && needAll > total) { widths[widths.size - 1] += needAll - total; total = needAll }
        if (cols.isEmpty()) total = needAll
        val colX = FloatArray(cols.size + 1)
        for (i in cols.indices) colX[i + 1] = colX[i] + widths[i]
        val height = rowsTop() + max(1, rows.size) * ROW_H + FOOT_H
        return TableLayout(h, cols, colX, rows, total, height, info, addW).also { t.lay = it }
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
        if (v < TITLE_H) return TableHit(TableHit.TITLE, null, null)
        var col: String? = l.cols.lastOrNull()?.k
        for (i in l.cols.indices) if (u < l.colX[i + 1]) { col = l.cols[i].k; break }
        if (v < rowsTop()) return TableHit(TableHit.HEAD, null, col)
        val ri = ((v - rowsTop()) / ROW_H).toInt()
        if (ri < l.rows.size) return TableHit(TableHit.CELL, l.rows[ri], col)
        if (v < rowsTop() + max(1, l.rows.size) * ROW_H) return TableHit(TableHit.ADD, null, col) // пустая таблица — «+ строка»
        return TableHit(if (u < l.addSplit) TableHit.ADD else TableHit.ADD_NEXT, null, col)
    }

    /** Строка rec на доске (null — строки в таблице не видно). */
    fun rowRect(b: Board, t: BoardTable, rec: BoardRec, out: RectF): RectF? {
        val l = layout(b, t)
        val i = l.rows.indexOfFirst { it === rec }
        if (i < 0) return null
        val k = t.scale
        out.set(t.x, t.y + (rowsTop() + i * ROW_H) * k, t.x + l.w * k, t.y + (rowsTop() + (i + 1) * ROW_H) * k)
        return out
    }

    /** Ячейка (строка + столбец) на доске. */
    fun cellRect(b: Board, t: BoardTable, rec: BoardRec, col: String, out: RectF): RectF? {
        val l = layout(b, t)
        val i = l.rows.indexOfFirst { it === rec }
        val ci = l.cols.indexOfFirst { it.k == col }
        if (i < 0 || ci < 0) return null
        val k = t.scale
        out.set(t.x + l.colX[ci] * k, t.y + (rowsTop() + i * ROW_H) * k, t.x + l.colX[ci + 1] * k, t.y + (rowsTop() + (i + 1) * ROW_H) * k)
        return out
    }

    /**
     * vis — видимая часть доски (строки вне её не рисуются); hl — подсвеченный статус ИГС: остальные строки притушены;
     * marked — строки, показанные «Контролем» или поиском; selected — выделенные (лассо).
     */
    fun draw(canvas: Canvas, b: Board, t: BoardTable, z: Float, vis: RectF, hl: String? = null,
             marked: Set<BoardRec>? = null, selected: Collection<Any>? = null) {
        val l = layout(b, t)
        val k = t.scale
        val x0 = t.x; val y0 = t.y
        val w = l.w * k; val h = l.h * k
        rect.set(x0, y0, x0 + w, y0 + h)
        fill.color = Color.WHITE
        canvas.drawRoundRect(rect, 8f * k, 8f * k, fill)
        fill.color = TITLE_BG
        canvas.save()
        canvas.clipRect(x0, y0, x0 + w, y0 + TITLE_H * k)
        canvas.drawRoundRect(rect, 8f * k, 8f * k, fill)
        canvas.restore()
        val far = FONT * k * z < 4f
        val top = y0 + rowsTop() * k
        val first = max(0, ((vis.top - top) / (ROW_H * k)).toInt())
        val last = min(l.rows.size - 1, ((vis.bottom - top) / (ROW_H * k)).toInt())
        val igsI = l.cols.indexOfFirst { it.k == TableCol.IGS }
        if (far) {
            // Издалека: цветные полоски ИГС — видно, где что, без надписей.
            if (ROW_H * k * z >= 1f && igsI >= 0) for (i in first..last) {
                val r = l.rows[i]
                fill.color = if (hl != null && r.igs != hl) Color.rgb(225, 228, 234) else IdTables.status(b, BoardStatus.ID, r.igs).color
                val ry = top + i * ROW_H * k
                rect.set(x0 + l.colX[igsI] * k, ry + 3f * k, x0 + l.colX[igsI + 1] * k, ry + (ROW_H - 3f) * k)
                canvas.drawRect(rect, fill)
            }
            outline(canvas, x0, y0, w, h, k)
            return
        }
        fill.color = HEAD_BG
        canvas.drawRect(x0, y0 + TITLE_H * k, x0 + w, top, fill)
        for (i in first..last) {
            val r = l.rows[i]
            val ry = top + i * ROW_H * k
            val bg = when {
                selected != null && selected.any { it === r } -> SEL
                marked != null && r in marked -> MARK
                i % 2 == 1 -> ZEBRA
                else -> 0
            }
            if (bg != 0) { fill.color = bg; canvas.drawRect(x0, ry, x0 + w, ry + ROW_H * k, fill) }
            for ((ci, c) in l.cols.withIndex()) cell(canvas, b, r, c, x0 + l.colX[ci] * k, ry, (l.colX[ci + 1] - l.colX[ci]) * k, k)
            if (hl != null && r.igs != hl) {
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
        for ((ci, c) in l.cols.withIndex()) {
            val cw = (l.colX[ci + 1] - l.colX[ci]) * k
            canvas.drawText(fit(c.title(), cw - 2 * PAD * k), x0 + (l.colX[ci] + PAD) * k, y0 + (TITLE_H + HEAD_H / 2f) * k + text.textSize * 0.36f, text)
        }
        // Шифр и сводка.
        text.textSize = TITLE_FONT * k
        text.color = Ui.INK
        canvas.drawText(t.title.ifEmpty { "Без шифра" }, x0 + PAD * 1.4f * k, y0 + TITLE_H / 2f * k + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
        text.textSize = 12f * k
        text.color = if (t.filter?.isEmpty == false) Ui.ACCENT else Ui.INK2
        val iw = text.measureText(l.info)
        canvas.drawText(l.info, x0 + w - PAD * 1.4f * k - iw, y0 + TITLE_H / 2f * k + text.textSize * 0.36f, text)
        if (l.rows.isEmpty()) {
            text.textSize = FONT * k
            text.color = Ui.MUTED
            val msg = if (t.filter?.isEmpty == false) "По отбору строк нет" else "Строк пока нет — нажмите «+ строка»"
            canvas.drawText(msg, x0 + PAD * k, top + ROW_H / 2f * k + text.textSize * 0.36f, text)
        }
        text.textSize = FONT * k
        text.isFakeBoldText = true
        text.color = Ui.ACCENT
        val fy = bottom + FOOT_H / 2f * k + text.textSize * 0.36f
        canvas.drawText("+ строка", x0 + PAD * k, fy, text)
        canvas.drawText("+ следующая захватка", x0 + (l.addSplit + PAD) * k, fy, text)
        text.isFakeBoldText = false
        outline(canvas, x0, y0, w, h, k)
    }

    private fun outline(canvas: Canvas, x0: Float, y0: Float, w: Float, h: Float, k: Float) {
        rect.set(x0, y0, x0 + w, y0 + h)
        line.color = Color.rgb(170, 182, 204)
        line.strokeWidth = 1.5f * k
        canvas.drawRoundRect(rect, 8f * k, 8f * k, line)
    }

    private fun cell(canvas: Canvas, b: Board, r: BoardRec, c: TableCol, cx: Float, ry: Float, cw: Float, k: Float) {
        val cy = ry + ROW_H / 2f * k
        if (c.isStatus) {
            val st = IdTables.status(b, IdTables.groupOf(c.k), r.get(c.k))
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
            return
        }
        val s = IdTables.cellText(b, r, c.k)
        if (s.isEmpty()) return
        text.textSize = FONT * k
        text.color = if (c.k == TableCol.ZAH) Ui.INK else Ui.INK2
        text.isFakeBoldText = c.k == TableCol.ZAH
        canvas.drawText(fit(s, cw - 2 * PAD * k), cx + PAD * k, cy + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
    }

    /** Текст, обрезанный «…» по ширине. */
    private fun fit(s: String, w: Float): String {
        if (w <= 0f) return ""
        if (text.measureText(s) <= w * 1.04f) return s
        val ell = "…"
        val n = text.breakText(s, true, w - text.measureText(ell), null)
        return s.take(max(0, n)) + ell
    }
}
