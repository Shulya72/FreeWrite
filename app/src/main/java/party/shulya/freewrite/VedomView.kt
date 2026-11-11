package party.shulya.freewrite

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Раскладка ведомости в единицах при размере 1. */
class VedomLayout(val key: Long, val cols: List<String>, val colX: FloatArray, val items: List<BoardVItem>, val w: Float, val h: Float,
                  val title: String, val state: String, val info: String, val foot: Boolean, val giveX: Float,
                  /** Тексты ячеек [строка][столбец] и цвет статуса ИГС строки (0 — строки таблицы нет). */
                  val texts: Array<Array<String>>, val igsColor: IntArray)

/** Что под пальцем в ведомости: шапка, названия столбцов, ячейка (позиция + столбец), «+ схемы», «Передать». */
class VedomHit(val part: Int, val item: BoardVItem?, val col: String?) {
    companion object {
        const val TITLE = 0
        const val HEAD = 1
        const val CELL = 2
        const val ADD = 3
        const val GIVE = 4
    }
}

/**
 * Ведомость на доске (ТЗ «Учёт ИГС», раздел 6.4): шапка (вид, номер, кому, дата передачи, сводка ответов), строки —
 * №, шифр, захватка, конструкция, вид работ, текущий статус ИГС, ответ по этой ведомости и его дата.
 */
class VedomDraw {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    companion object {
        const val HEAD_H = 58f
        const val COLS_H = 26f
        const val ROW_H = 30f
        const val FOOT_H = 30f
        const val PAD = 9f
        const val FONT = 13f
        const val HEAD_FONT = 11f
        const val TITLE_FONT = 19f
        const val N = "n"
        const val CIPHER = "cipher"
        const val RES = "res"
        const val RD = "rd"
        private val HEAD_BG = Color.rgb(241, 244, 248)
        private val ZEBRA = Color.rgb(250, 251, 253)
        private val SEL = Color.rgb(227, 236, 252)
        private val MARK = Color.rgb(255, 241, 224)

        fun colName(k: String) = when (k) {
            N -> "№"; CIPHER -> "Шифр"; TableCol.IGS -> "ИГС сейчас"; RES -> "Ответ"; RD -> "Дата ответа"
            else -> TableCol.defaultName(k)
        }
    }

    fun rowsTop() = HEAD_H + COLS_H

    private fun tw(s: String, size: Float, bold: Boolean): Float {
        text.textSize = 100f
        text.isFakeBoldText = bold
        val w = text.measureText(s) * size / 100f * 1.06f + 2f
        text.isFakeBoldText = false
        return w
    }

    fun layout(b: Board, v: BoardVedom, today: String = IdTables.today()): VedomLayout {
        val items = Vedoms.items(b, v)
        val answers = Vedoms.hasAnswers(v.kind) && !v.isDraft
        val cols = arrayListOf(N, CIPHER, TableCol.ZAH, TableCol.CON, TableCol.WORK, TableCol.IGS)
        if (answers) { cols += RES; cols += RD }
        val title = Vedoms.title(v)
        val state = Vedoms.state(b, v)
        val info = listOf(v.to.takeIf { it.isNotBlank() }?.let { "Кому: $it" }.orEmpty(), Vedoms.summary(b, v, today)).filter { it.isNotEmpty() }.joinToString(" · ")
        // Строки таблиц — по номеру один раз (позиций может быть много, строк — тысячи).
        val recs = HashMap<String, BoardRec>(b.recs.size * 2 + 4)
        for (r in b.recs) recs[r.id] = r
        val tables = HashMap<String, BoardTable>()
        for (t in b.tables) tables[t.id] = t
        val texts = Array(items.size) { idx ->
            val i = items[idx]
            val r = recs[i.r]
            val t = r?.let { tables[it.t] }
            Array(cols.size) { ci ->
                when (cols[ci]) {
                    N -> (idx + 1).toString()
                    CIPHER -> if (t != null) t.title else i.lt
                    TableCol.ZAH -> if (t != null) r.zah else i.lz
                    TableCol.CON -> if (t != null) r.con else i.lc
                    TableCol.WORK -> if (t != null) r.work else i.lw
                    TableCol.IGS -> if (t != null) IdTables.status(b, BoardStatus.ID, r.igs).name else "строка удалена"
                    RES -> Vedoms.resName(v.kind, i.res)
                    RD -> IdTables.dateShort(i.rd)
                    else -> ""
                }
            }
        }
        val igsColor = IntArray(items.size) { idx -> recs[items[idx].r]?.takeIf { tables[it.t] != null }?.let { IdTables.status(b, BoardStatus.ID, it.igs).color } ?: 0 }
        var h = 1125899906842597L
        h = 31 * h + title.hashCode(); h = 31 * h + state.hashCode(); h = 31 * h + info.hashCode()
        for (s in b.statuses) { h = 31 * h + s.id.hashCode(); h = 31 * h + s.color }
        for ((idx, i) in items.withIndex()) {
            h = 31 * h + i.id.hashCode() + System.identityHashCode(i)
            h = 31 * h + igsColor[idx]
            for (c in texts[idx]) h = 31 * h + c.hashCode()
        }
        v.lay?.let { if (it.key == h) return it }

        val widths = FloatArray(cols.size)
        for ((ci, c) in cols.withIndex()) {
            var w = tw(colName(c), HEAD_FONT, true) + 2 * PAD
            val pill = c == TableCol.IGS || c == RES
            for (row in texts) w = max(w, tw(row[ci], FONT, pill || c == TableCol.ZAH || c == N) + 2 * PAD + if (pill) 22f else 0f)
            w = when (c) {
                N -> max(w, 34f)
                RD -> max(w, tw("00.00.00", FONT, false) + 2 * PAD)
                TableCol.IGS -> min(max(w, 120f), 300f)
                RES -> min(max(w, 100f), 220f)
                else -> min(max(w, 56f), 230f)
            }
            widths[ci] = w
        }
        var total = widths.sum()
        val need = max(PAD * 2 + tw(title, TITLE_FONT, true) + 24f + tw(state, 12f, true) + PAD, PAD * 2 + tw(info, 12f, false))
        val foot = v.isDraft
        val addW = PAD + tw("+ схемы", FONT, true) + PAD * 2
        val need2 = if (foot && v.kind != Vedoms.LIST) addW + tw("Передать →", FONT, true) + PAD * 2 else addW
        val needAll = max(need, need2)
        if (needAll > total) { widths[widths.size - 1] += needAll - total; total = needAll }
        val colX = FloatArray(cols.size + 1)
        for (i in cols.indices) colX[i + 1] = colX[i] + widths[i]
        val height = rowsTop() + max(1, items.size) * ROW_H + if (foot) FOOT_H else 0f
        return VedomLayout(h, cols, colX, items, total, height, title, state, info, foot, addW, texts, igsColor).also { v.lay = it }
    }

    fun bounds(b: Board, v: BoardVedom, out: RectF): RectF {
        val l = layout(b, v)
        out.set(v.x, v.y, v.x + l.w * v.scale, v.y + l.h * v.scale)
        return out
    }

    fun hit(b: Board, v: BoardVedom, x: Float, y: Float): VedomHit? {
        val l = layout(b, v)
        val u = (x - v.x) / v.scale
        val w = (y - v.y) / v.scale
        if (u < 0f || u > l.w || w < 0f || w > l.h) return null
        if (w < HEAD_H) return VedomHit(VedomHit.TITLE, null, null)
        var col: String? = l.cols.lastOrNull()
        for (i in l.cols.indices) if (u < l.colX[i + 1]) { col = l.cols[i]; break }
        if (w < rowsTop()) return VedomHit(VedomHit.HEAD, null, col)
        val ri = ((w - rowsTop()) / ROW_H).toInt()
        if (ri < l.items.size) return VedomHit(VedomHit.CELL, l.items[ri], col)
        if (!l.foot) return VedomHit(VedomHit.HEAD, null, col)
        if (w < rowsTop() + max(1, l.items.size) * ROW_H) return VedomHit(VedomHit.ADD, null, col)
        return VedomHit(if (u < l.giveX || v.kind == Vedoms.LIST) VedomHit.ADD else VedomHit.GIVE, null, col)
    }

    fun rowRect(b: Board, v: BoardVedom, item: BoardVItem, out: RectF): RectF? {
        val l = layout(b, v)
        val i = l.items.indexOfFirst { it === item }
        if (i < 0) return null
        val k = v.scale
        out.set(v.x, v.y + (rowsTop() + i * ROW_H) * k, v.x + l.w * k, v.y + (rowsTop() + (i + 1) * ROW_H) * k)
        return out
    }

    fun cellRect(b: Board, v: BoardVedom, item: BoardVItem, col: String, out: RectF): RectF? {
        val l = layout(b, v)
        val i = l.items.indexOfFirst { it === item }
        val ci = l.cols.indexOf(col)
        if (i < 0 || ci < 0) return null
        val k = v.scale
        out.set(v.x + l.colX[ci] * k, v.y + (rowsTop() + i * ROW_H) * k, v.x + l.colX[ci + 1] * k, v.y + (rowsTop() + (i + 1) * ROW_H) * k)
        return out
    }

    fun draw(canvas: Canvas, b: Board, v: BoardVedom, z: Float, vis: RectF, selected: Collection<Any>? = null, marked: Set<BoardVItem>? = null) {
        val l = layout(b, v)
        val k = v.scale
        val x0 = v.x; val y0 = v.y
        val w = l.w * k; val h = l.h * k
        val kc = Vedoms.kindColor(v.kind)
        rect.set(x0, y0, x0 + w, y0 + h)
        fill.color = Color.WHITE
        canvas.drawRoundRect(rect, 8f * k, 8f * k, fill)
        fill.color = Board.statusBg(kc)
        canvas.save()
        canvas.clipRect(x0, y0, x0 + w, y0 + HEAD_H * k)
        canvas.drawRoundRect(rect, 8f * k, 8f * k, fill)
        canvas.restore()
        val far = FONT * k * z < 4f
        val top = y0 + rowsTop() * k
        val first = max(0, ((vis.top - top) / (ROW_H * k)).toInt())
        val last = min(l.items.size - 1, ((vis.bottom - top) / (ROW_H * k)).toInt())
        if (far) {
            // Издалека: полоски ответов (черновик — статусы ИГС).
            if (ROW_H * k * z >= 1f) for (i in first..last) {
                val it = l.items[i]
                fill.color = if (Vedoms.hasAnswers(v.kind) && !v.isDraft) Vedoms.resColor(it.res)
                else l.igsColor[i].takeIf { c -> c != 0 } ?: Ui.RULE
                val ry = top + i * ROW_H * k
                rect.set(x0 + w * 0.55f, ry + 3f * k, x0 + w - 6f * k, ry + (ROW_H - 3f) * k)
                canvas.drawRect(rect, fill)
            }
            outline(canvas, x0, y0, w, h, k, kc)
            return
        }
        fill.color = HEAD_BG
        canvas.drawRect(x0, y0 + HEAD_H * k, x0 + w, top, fill)
        for (i in first..last) {
            val it = l.items[i]
            val ry = top + i * ROW_H * k
            val bg = when {
                selected != null && selected.any { s -> s === it } -> SEL
                marked != null && it in marked -> MARK
                i % 2 == 1 -> ZEBRA
                else -> 0
            }
            if (bg != 0) { fill.color = bg; canvas.drawRect(x0, ry, x0 + w, ry + ROW_H * k, fill) }
            for ((ci, c) in l.cols.withIndex()) cell(canvas, l, i, ci, it, c, v, x0 + l.colX[ci] * k, ry, (l.colX[ci + 1] - l.colX[ci]) * k, k)
        }
        line.color = Ui.RULE
        line.strokeWidth = 1f * k
        val bottom = top + max(1, l.items.size) * ROW_H * k
        for (i in 1 until l.cols.size) canvas.drawLine(x0 + l.colX[i] * k, y0 + HEAD_H * k, x0 + l.colX[i] * k, bottom, line)
        canvas.drawLine(x0, y0 + HEAD_H * k, x0 + w, y0 + HEAD_H * k, line)
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
            canvas.drawText(fit(colName(c), cw - 2 * PAD * k), x0 + (l.colX[ci] + PAD) * k, y0 + (HEAD_H + COLS_H / 2f) * k + text.textSize * 0.36f, text)
        }
        // Шапка: номер и вид, состояние справа, ниже — кому и сводка ответов.
        text.textSize = TITLE_FONT * k
        text.color = Ui.INK
        canvas.drawText(l.title, x0 + PAD * 1.4f * k, y0 + 22f * k + text.textSize * 0.36f, text)
        text.textSize = 12f * k
        text.color = Board.statusFg(kc)
        val sw = text.measureText(l.state)
        canvas.drawText(l.state, x0 + w - PAD * 1.4f * k - sw, y0 + 22f * k + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
        text.color = Ui.INK2
        canvas.drawText(fit(l.info, w - PAD * 2.8f * k), x0 + PAD * 1.4f * k, y0 + 45f * k + text.textSize * 0.36f, text)
        if (l.items.isEmpty()) {
            text.textSize = FONT * k
            text.color = Ui.MUTED
            canvas.drawText("Схем пока нет — «+ схемы»", x0 + PAD * k, top + ROW_H / 2f * k + text.textSize * 0.36f, text)
        }
        if (l.foot) {
            text.textSize = FONT * k
            text.isFakeBoldText = true
            text.color = Ui.ACCENT
            val fy = bottom + FOOT_H / 2f * k + text.textSize * 0.36f
            canvas.drawText("+ схемы", x0 + PAD * k, fy, text)
            if (v.kind != Vedoms.LIST) canvas.drawText("Передать →", x0 + (l.giveX + PAD) * k, fy, text)
            text.isFakeBoldText = false
        }
        outline(canvas, x0, y0, w, h, k, kc)
    }

    private fun outline(canvas: Canvas, x0: Float, y0: Float, w: Float, h: Float, k: Float, kc: Int) {
        rect.set(x0, y0, x0 + w, y0 + h)
        line.color = kc
        line.strokeWidth = 2f * k
        canvas.drawRoundRect(rect, 8f * k, 8f * k, line)
    }

    private fun pill(canvas: Canvas, name0: String, color: Int, cx: Float, ry: Float, cw: Float, k: Float, dashed: Boolean = false) {
        text.textSize = FONT * k
        text.isFakeBoldText = true
        val name = fit(name0, cw - 2 * PAD * k - 22f * k)
        val pw = text.measureText(name) + 22f * k
        rect.set(cx + PAD * k, ry + 4.5f * k, cx + PAD * k + pw, ry + (ROW_H - 4.5f) * k)
        val fg = Board.statusFg(color)
        fill.color = if (dashed) Color.WHITE else Board.statusBg(color)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
        line.color = fg
        line.strokeWidth = 1.2f * k
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, line)
        text.color = fg
        canvas.drawText(name, cx + (PAD + 11f) * k, ry + ROW_H / 2f * k + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
    }

    private fun cell(canvas: Canvas, l: VedomLayout, idx: Int, ci: Int, i: BoardVItem, c: String, v: BoardVedom, cx: Float, ry: Float, cw: Float, k: Float) {
        val s = l.texts[idx][ci]
        when (c) {
            TableCol.IGS -> if (l.igsColor[idx] != 0) { pill(canvas, s, l.igsColor[idx], cx, ry, cw, k); return }
            RES -> { pill(canvas, s, Vedoms.resColor(i.res), cx, ry, cw, k, dashed = i.res.isEmpty()); return }
        }
        if (s.isEmpty()) return
        text.textSize = FONT * k
        text.color = when (c) { TableCol.ZAH -> Ui.INK; N, TableCol.IGS -> Ui.MUTED; else -> Ui.INK2 }
        text.isFakeBoldText = c == TableCol.ZAH
        canvas.drawText(fit(s, cw - 2 * PAD * k), cx + PAD * k, ry + ROW_H / 2f * k + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
    }

    private fun fit(s: String, w: Float): String {
        if (w <= 0f) return ""
        if (text.measureText(s) <= w * 1.04f) return s
        val ell = "…"
        val n = text.breakText(s, true, w - text.measureText(ell), null)
        return s.take(max(0, n)) + ell
    }
}
