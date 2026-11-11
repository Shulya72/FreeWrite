package party.shulya.freewrite

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Раскладка таблицы ИД в единицах при размере 1: столбцы и их границы, строки, ширина, высота, надпись в шапке. */
class TableLayout(val key: Long, val cols: List<TableCol>, val colX: FloatArray, val rows: List<BoardRec>, val w: Float, val h: Float, val info: String, val addSplit: Float,
                  /** Шифр раздела перед названием («КЖ 6.1.1 ·»), пусто — нет. */
                  val prefix: String = "",
                  /** Объёмы по видам работ (блок внизу; null — столбца объёма нет). */
                  val vol: List<IdTables.VolLine>? = null, val volTop: Float = 0f, val volH: Float = 0f, val volMissing: List<BoardRec> = emptyList(),
                  /** Цвет заливки захватки на плане (нормализованный номер → цвет) — значок у номера (0.22). */
                  val swatch: Map<String, Int> = emptyMap())

/** Что под пальцем в таблице ИД: шапка, названия столбцов, ячейка (строка + столбец), «+ строка», «+ следующая захватка». */
class TableHit(val part: Int, val rec: BoardRec?, val col: String?) {
    companion object {
        const val TITLE = 0
        const val HEAD = 1
        const val CELL = 2
        const val ADD = 3
        const val ADD_NEXT = 4
        /** Строка объёмов: col — вид работ (проект). */
        const val VOL = 5
        /** «+ проект» в шапке блока объёмов. */
        const val VOL_ADD = 6
        /** «Объём не указан у …» — показать эти строки. */
        const val VOL_NOTE = 7
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
        /** Значок цвета захватки у номера (0.22). */
        const val SWATCH = 12f
        const val VOL_HEAD = 34f
        const val VOL_ROW = 48f
        const val VOL_NOTE = 28f
        const val VOL_PAD = 8f
        /** Ширина таблицы с блоком объёмов — не уже (иначе полоска не помещается). */
        const val VOL_MIN_W = 1000f
        private val VOL_BG = Color.rgb(247, 249, 252)
        private val LEFT_FG = Color.rgb(31, 79, 160)
        private val LEFT_BG = Color.rgb(226, 234, 250)
        private val BAR_BG = Color.rgb(230, 234, 240)
        private val BAR = Color.rgb(31, 95, 209)
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
        val prefix = IdTables.sectionPrefix(b, t)
        val volOn = cols.any { it.isVol }
        val all = if (volOn) IdTables.allRows(b, t) else rows
        val swatch = IdTables.zahColors(b, t)
        var h = 1125899906842597L
        h = 31 * h + t.title.hashCode()
        h = 31 * h + prefix.hashCode()
        for ((zk, c) in swatch) { h = 31 * h + zk.hashCode(); h = 31 * h + c }
        if (volOn) {
            for (r in all) { h = 31 * h + r.vol.hashCode(); h = 31 * h + r.work.hashCode() }
            for (p in t.plan.values) { h = 31 * h + p.work.hashCode(); h = 31 * h + p.value.hashCode(); h = 31 * h + p.unit.hashCode() }
        }
        for (c in cols) { h = 31 * h + c.k.hashCode(); h = 31 * h + c.name.hashCode() }
        for (s in b.statuses) { h = 31 * h + s.id.hashCode(); h = 31 * h + s.name.hashCode(); h = 31 * h + (if (s.done) 1 else 0) }
        val f = t.filter
        h = 31 * h + (f?.hashCode() ?: 0)
        for (r in rows) {
            h = 31 * h + r.id.hashCode()
            for (c in cols) h = 31 * h + r.get(c.k).hashCode()
        }
        t.lay?.let { if (it.key == h) return it }

        val allN = if (f == null || f.isEmpty) rows.size else IdTables.allRows(b, t).size
        val done = rows.count { IdTables.status(b, BoardStatus.ID, it.igs).done }
        var info = "${Ui.plural(rows.size, "схема", "схемы", "схем")} · сдано в ПТО $done"
        if (f != null && !f.isEmpty) info = "отбор: ${rows.size} из $allN · сдано $done"
        val widths = FloatArray(cols.size)
        for ((ci, c) in cols.withIndex()) {
            var w = tw(c.title(), HEAD_FONT, true) + 2 * PAD
            if (c.isStatus) {
                val seen = HashSet<String>()
                for (r in rows) { val n = IdTables.cellText(b, r, c.k); if (seen.add(n)) w = max(w, tw(n, FONT, true) + 22f + 2 * PAD) }
                w = min(max(w, if (c.k == TableCol.IGS) 120f else 70f), 300f)
            } else {
                if (c.isDate) w = max(w, tw("00.00.00", FONT, false) + 2 * PAD)
                else if (c.isVol) for (r in rows) w = max(w, tw(IdTables.cellText(b, r, c.k), FONT, false) + 2 * PAD + 4f)
                else for (r in rows) w = max(w, tw(IdTables.cellText(b, r, c.k), FONT, c.k == TableCol.ZAH) + 2 * PAD +
                    if (c.k == TableCol.ZAH && swatch.containsKey(IdTables.norm(r.zah))) SWATCH + 5f else 0f)
                w = min(max(w, if (c.k == TableCol.ZAH) 64f else 56f), if (c.k == TableCol.NOTE) 260f else 230f)
            }
            widths[ci] = w
        }
        var total = widths.sum()
        val titleW = tw(t.title.ifEmpty { "Без шифра" }, TITLE_FONT, true) + if (prefix.isEmpty()) 0f else tw("$prefix ·", 14f, false) + 8f
        val need = PAD * 2 + titleW + 24f + tw(info, 12f, false) + PAD
        val addW = PAD + tw("+ строка", FONT, true) + PAD * 2
        val need2 = addW + tw("+ следующая захватка", FONT, true) + PAD * 2
        val vol = if (volOn && all.isNotEmpty()) IdTables.volumes(b, t, all) else null
        val needAll = max(max(need, need2), if (vol != null) VOL_MIN_W else 0f)
        if (cols.isNotEmpty() && needAll > total) { widths[widths.size - 1] += needAll - total; total = needAll }
        if (cols.isEmpty()) total = needAll
        val colX = FloatArray(cols.size + 1)
        for (i in cols.indices) colX[i + 1] = colX[i] + widths[i]
        val volTop = rowsTop() + max(1, rows.size) * ROW_H
        val missing = vol?.flatMap { it.missing } ?: emptyList()
        val volH = if (vol == null) 0f else VOL_HEAD + max(1, vol.size) * VOL_ROW + (if (missing.isNotEmpty()) VOL_NOTE else 0f) + VOL_PAD
        val height = volTop + volH + FOOT_H
        return TableLayout(h, cols, colX, rows, total, height, info, addW, prefix, vol, volTop, volH, missing, swatch).also { t.lay = it }
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
        val vol = l.vol
        if (vol != null && v < l.volTop + l.volH) {
            val dv = v - l.volTop
            if (dv < VOL_HEAD) return TableHit(TableHit.VOL_ADD, null, null)
            val i = ((dv - VOL_HEAD) / VOL_ROW).toInt()
            if (i < vol.size) return TableHit(TableHit.VOL, null, vol[i].work)
            if (vol.isEmpty() && i == 0) return TableHit(TableHit.VOL_ADD, null, null)
            return TableHit(if (l.volMissing.isNotEmpty()) TableHit.VOL_NOTE else TableHit.VOL_ADD, null, null)
        }
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
        // Шифр раздела, название таблицы (подраздел) и сводка.
        var tx = x0 + PAD * 1.4f * k
        if (l.prefix.isNotEmpty()) {
            text.isFakeBoldText = false
            text.textSize = 14f * k
            text.color = Ui.MUTED
            val pre = "${l.prefix} ·"
            canvas.drawText(pre, tx, y0 + TITLE_H / 2f * k + text.textSize * 0.36f, text)
            tx += text.measureText(pre) + 8f * k
            text.isFakeBoldText = true
        }
        text.textSize = TITLE_FONT * k
        text.color = Ui.INK
        canvas.drawText(t.title.ifEmpty { "Без шифра" }, tx, y0 + TITLE_H / 2f * k + text.textSize * 0.36f, text)
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
        if (l.vol != null) drawVolumes(canvas, b, t, l, x0, y0 + l.volTop * k, w, k)
        text.textSize = FONT * k
        text.isFakeBoldText = true
        text.color = Ui.ACCENT
        val fy = y0 + (l.volTop + l.volH) * k + FOOT_H / 2f * k + text.textSize * 0.36f
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
        if (c.isVol) {
            text.isFakeBoldText = false
            if (r.vol.isEmpty()) {
                text.textSize = FONT * k; text.color = Ui.MUTED
                val dash = "—"
                canvas.drawText(dash, cx + cw - PAD * k - text.measureText(dash), cy + text.textSize * 0.36f, text)
                return
            }
            val u = IdTables.tableOf(b, r)?.let { IdTables.unitFor(it, r.work) }.orEmpty()
            text.textSize = 11.5f * k
            val uw = if (u.isEmpty()) 0f else text.measureText(u) + 3f * k
            val right = cx + cw - PAD * k
            if (u.isNotEmpty()) { text.color = Ui.MUTED; canvas.drawText(u, right - uw + 3f * k, cy + FONT * k * 0.36f, text) }
            text.textSize = FONT * k; text.color = Ui.INK
            val n = IdTables.volText(r.vol)
            canvas.drawText(n, right - uw - text.measureText(n), cy + text.textSize * 0.36f, text)
            return
        }
        val s = IdTables.cellText(b, r, c.k)
        if (s.isEmpty()) return
        var tx = cx + PAD * k
        if (c.k == TableCol.ZAH) {
            // Захватка привязана к заливке на плане — значок её цвета перед номером.
            t0(b, r)?.lay?.swatch?.get(IdTables.norm(r.zah))?.let { col ->
                rect.set(tx, cy - SWATCH / 2f * k, tx + SWATCH * k, cy + SWATCH / 2f * k)
                fill.color = col
                canvas.drawRoundRect(rect, 3f * k, 3f * k, fill)
                line.color = Board.statusFg(col); line.strokeWidth = 1f * k
                canvas.drawRoundRect(rect, 3f * k, 3f * k, line)
                tx += (SWATCH + 5f) * k
            }
        }
        text.textSize = FONT * k
        text.color = if (c.k == TableCol.ZAH) Ui.INK else Ui.INK2
        text.isFakeBoldText = c.k == TableCol.ZAH
        canvas.drawText(fit(s, cx + cw - PAD * k - tx), tx, cy + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
    }

    /**
     * Блок «Объёмы» внизу таблицы (0.21): по каждому виду работ — сколько схем с объёмом, факт (сумма столбца
     * «Объём по схеме»), проект (рамка ✎ — вписать), разница: «осталось …» синим или «перебор +… · +… %» красным,
     * полоска выполнения и процент.
     */
    private fun drawVolumes(canvas: Canvas, b: Board, t: BoardTable, l: TableLayout, x0: Float, top: Float, w: Float, k: Float) {
        val vol = l.vol ?: return
        fill.color = VOL_BG
        canvas.drawRect(x0, top, x0 + w, top + l.volH * k, fill)
        line.color = Color.rgb(170, 182, 204)
        line.strokeWidth = 1.2f * k
        canvas.drawLine(x0, top + 1f * k, x0 + w, top + 1f * k, line)
        canvas.drawLine(x0, top + 4f * k, x0 + w, top + 4f * k, line)
        fun txt(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false): Float {
            text.textSize = size * k; text.color = color; text.isFakeBoldText = bold
            canvas.drawText(s, x, y, text)
            val ww = text.measureText(s)
            text.isFakeBoldText = false
            return ww
        }
        val L = x0 + 14f * k
        val hy = top + (VOL_HEAD / 2f + 3f) * k + 5f * k
        val hw = txt("Объёмы · ${t.title.ifEmpty { "таблица" }}", L, hy, 15f, Ui.INK, bold = true)
        txt("факт — сумма столбца «${t.colName(TableCol.VOL)}», проект — вписываете", L + hw + 12f * k, hy, 12.5f, Ui.MUTED)
        val plus = "+ проект"
        text.textSize = 13.5f * k; text.isFakeBoldText = true
        val pw = text.measureText(plus)
        txt(plus, x0 + w - 14f * k - pw, hy, 13.5f, Ui.ACCENT, bold = true)
        if (vol.isEmpty()) {
            txt("Впишите объёмы в столбце «${t.colName(TableCol.VOL)}» — здесь появятся итоги; проект — «+ проект»", L,
                top + (VOL_HEAD + VOL_ROW / 2f) * k + 4f * k, 13.5f, Ui.MUTED)
            return
        }
        val xN = x0 + 170f * k; val xF = x0 + 300f * k; val xP = x0 + 460f * k; val xD = x0 + 650f * k
        for ((i, v) in vol.withIndex()) {
            val ry = top + (VOL_HEAD + i * VOL_ROW) * k
            val lab = ry + 15f * k
            val big = ry + 35f * k
            txt(v.work, L + 10f * k, ry + 28f * k, 15f, Ui.INK, bold = true)
            txt("схем с объёмом", xN, lab, 11f, Ui.MUTED)
            txt(v.n.toString(), xN, big, 15f, Ui.INK, bold = true)
            txt("факт по схемам", xF, lab, 11f, Ui.MUTED)
            val fw = txt(IdTables.volFmt(v.fact), xF, big, 17f, Ui.INK, bold = true)
            if (v.unit.isNotEmpty()) txt(v.unit, xF + fw + 4f * k, big, 12f, Ui.MUTED)
            txt("проект", xP, lab, 11f, Ui.MUTED)
            // Рамка проекта (пунктир) — касание: вписать.
            val ptxt = if (v.plan == null) "вписать" else IdTables.volFmt(v.plan) + if (v.unit.isNotEmpty()) " " + v.unit else ""
            text.textSize = (if (v.plan == null) 14f else 16f) * k; text.isFakeBoldText = v.plan != null
            val tw0 = text.measureText(ptxt)
            text.isFakeBoldText = false
            rect.set(xP - 6f * k, ry + 19f * k, xP + tw0 + 28f * k, ry + 41f * k)
            fill.color = Color.WHITE
            canvas.drawRoundRect(rect, 7f * k, 7f * k, fill)
            line.color = Color.rgb(150, 165, 190); line.strokeWidth = 1.3f * k
            line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * k, 3f * k), 0f)
            canvas.drawRoundRect(rect, 7f * k, 7f * k, line)
            line.pathEffect = null
            txt(ptxt, xP, big, if (v.plan == null) 14f else 16f, if (v.plan == null) Ui.MUTED else Ui.INK, bold = v.plan != null)
            txt("✎", xP + tw0 + 8f * k, big, 13f, Ui.ACCENT)
            if (v.plan == null) {
                txt("проект не вписан — разница появится, когда впишете", xD, big - 4f * k, 13f, Ui.MUTED)
                continue
            }
            val over = v.over
            val diff = kotlin.math.abs(v.fact - v.plan)
            val chip = if (over) "перебор +${IdTables.volFmt(diff)} ${v.unit} · +${String.format(java.util.Locale.US, "%.1f", diff / v.plan * 100).replace('.', ',')} %".replace("  ", " ")
                else "осталось ${IdTables.volFmt(diff)} ${v.unit}".trimEnd()
            text.textSize = 13.5f * k; text.isFakeBoldText = true
            val cw = text.measureText(chip) + 22f * k
            text.isFakeBoldText = false
            rect.set(xD, ry + 13f * k, xD + cw, ry + 37f * k)
            fill.color = if (over) Ui.BAD else LEFT_BG
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
            txt(chip, xD + 11f * k, ry + 30f * k, 13.5f, if (over) Color.WHITE else LEFT_FG, bold = true)
            val pct = "${v.percent} %"
            text.textSize = 14f * k; text.isFakeBoldText = true
            val pctW = text.measureText(pct)
            text.isFakeBoldText = false
            val bx = xD + cw + 14f * k
            val bw = min(200f * k, x0 + w - 24f * k - pctW - 12f * k - bx)
            if (bw > 20f * k) {
                rect.set(bx, ry + 20f * k, bx + bw, ry + 31f * k)
                fill.color = BAR_BG
                canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
                val frac = if (over) 1f else (v.fact / v.plan).toFloat().coerceIn(0f, 1f)
                rect.set(bx, ry + 20f * k, bx + bw * frac, ry + 31f * k)
                fill.color = if (over) Ui.BAD else BAR
                if (frac > 0f) canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
                if (over) {
                    // Черта — где кончается проект.
                    val mx = bx + bw * (v.plan / v.fact).toFloat()
                    line.color = Ui.INK; line.strokeWidth = 2f * k
                    canvas.drawLine(mx, ry + 16f * k, mx, ry + 35f * k, line)
                }
                txt(pct, bx + bw + 12f * k, ry + 30f * k, 14f, if (over) Ui.BAD else Ui.INK, bold = true)
            } else txt(pct, bx, ry + 30f * k, 14f, if (over) Ui.BAD else Ui.INK, bold = true)
        }
        if (l.volMissing.isNotEmpty()) {
            val ny = top + (VOL_HEAD + vol.size * VOL_ROW + VOL_NOTE / 2f) * k + 4f * k
            val n = l.volMissing.size
            val ww = txt("⚠ Объём не указан у ${Ui.plural(n, "схемы", "схем", "схем")}", L, ny, 13f, Ui.WARN, bold = true)
            txt(" — в факт не вошли. Касание — показать эти строки.", L + ww, ny, 13f, Ui.INK2)
        }
    }

    private fun t0(b: Board, r: BoardRec): BoardTable? = IdTables.tableOf(b, r)

    /** Текст, обрезанный «…» по ширине. */
    private fun fit(s: String, w: Float): String {
        if (w <= 0f) return ""
        if (text.measureText(s) <= w * 1.04f) return s
        val ell = "…"
        val n = text.breakText(s, true, w - text.measureText(ell), null)
        return s.take(max(0, n)) + ell
    }
}
