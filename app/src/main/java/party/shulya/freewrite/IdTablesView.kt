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
                  val swatch: Map<String, Int> = emptyMap(),
                  /** План под таблицей (0.23); null — листов нет. */
                  val plan: PlanLayout? = null,
                  /** Без листов: где в нижней строке начинается «+ план». */
                  val planSplit: Float = Float.MAX_VALUE,
                  /** Высота шапки столбцов: у вида с частями объёма — в два этажа («Объём по схеме» над «Стены | Колонны»). */
                  val headH: Float = TableDraw.HEAD_H,
                  /** Итоги объёмов сеткой: вид работ × часть + «Всего» (0.24). */
                  val volParts: List<TableKinds.Part> = emptyList())

/**
 * Раскладка плана под таблицей (0.23), в единицах таблицы: шапка с кнопками, вкладки листов, лист, легенда.
 * chips — границы кнопок шапки по x: цвет, «+ лист», «Обвести захватку» (по два числа на кнопку).
 */
class PlanLayout(val top: Float, val h: Float, val tabsH: Float, val tabX: FloatArray, val sheetTop: Float, val sheetW: Float, val sheetH: Float,
                 val chips: FloatArray, val chipText: Array<String>, val legend: List<Pair<BoardStatus?, Int>>, val title: String)

/** Что под пальцем в таблице ИД: шапка, названия столбцов, ячейка (строка + столбец), «+ строка», «+ следующая захватка». */
class TableHit(val part: Int, val rec: BoardRec?, val col: String?) {
    /** Ячейка: где коснулись от её левого края (в единицах таблицы) — квадратик захватки или сам номер. */
    var cx = 0f
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
        /** План под таблицей (0.23): «+ план» / «+ лист», переключатель цвета, «Обвести захватку», вкладка (col — номер), лист. */
        const val PLAN_ADD = 8
        const val PLAN_COLOR = 9
        const val PLAN_OUTLINE = 10
        const val PLAN_TAB = 11
        const val PLAN_SHEET = 12
    }

    /** VOL у вида с частями (0.24): ключ части. */
    var vpart = ""

    /** PLAN_SHEET: точка в долях картинки листа целиком. */
    var fx = 0f
    var fy = 0f
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
        /** План под таблицей (0.23). */
        const val PLAN_HEAD = 46f
        const val PLAN_TABS = 34f
        const val PLAN_PAD = 12f
        const val PLAN_LEG = 36f
        const val PLAN_MIN_W = 900f
        private val PLAN_BG = Color.rgb(243, 246, 250)
        /** Шапка в два этажа (части объёма, 0.24). */
        const val HEAD2_H = 42f
        /** Сетка итогов по частям: строка заголовков и строка вида работ. */
        const val VOLP_HEAD = 26f
        const val VOLP_ROW = 80f
        const val VOLP_WORK = 150f
        const val VOLP_TOTAL = 210f
        private val GRP_BG = Color.rgb(232, 238, 251)
        private val GRP_FG = Color.rgb(31, 79, 160)
    }

    fun rowsTop() = TITLE_H + HEAD_H
    fun rowsTop(l: TableLayout) = TITLE_H + l.headH

    /** Плашка вида у названия таблицы (0.24); у таблицы без вида — пусто. */
    fun kindChip(t: BoardTable): String = t.kindDef()?.let { "вид: ${it.section} · ${it.name.lowercase()}" }.orEmpty()

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
        // Значок у номера захватки: цвет заливки на доске или области на плане (0.23, главнее).
        val swatch = TablePlan.zahColorsAll(b, t)
        val hasPlan = t.sheets.isNotEmpty()
        var h = 1125899906842597L
        if (hasPlan) {
            h = 31 * h + t.tab + t.zoneColor.hashCode()
            for (sh in t.sheets) { h = 31 * h + sh.id.hashCode() + sh.name.hashCode(); h = 31 * h + (sh.cl + 3 * sh.ct + 7 * sh.cr + 13 * sh.cb).toRawBits() }
            for (z in b.zones) if (z.t == t.id) h = 31 * h + z.id.hashCode() + z.zkey().hashCode()
            for (r in IdTables.allRows(b, t)) h = 31 * h + r.igs.hashCode()
        }
        h = 31 * h + t.title.hashCode()
        h = 31 * h + prefix.hashCode()
        h = 31 * h + t.kind.hashCode()
        for ((zk, c) in swatch) { h = 31 * h + zk.hashCode(); h = 31 * h + c }
        if (volOn) {
            for (r in all) { h = 31 * h + r.vol.hashCode(); h = 31 * h + r.work.hashCode(); h = 31 * h + r.vp.hashCode() }
            for (p in t.plan.values) { h = 31 * h + p.work.hashCode(); h = 31 * h + p.value.hashCode(); h = 31 * h + p.unit.hashCode(); h = 31 * h + p.part.hashCode() }
        }
        for (c in cols) { h = 31 * h + c.k.hashCode(); h = 31 * h + c.name.hashCode() }
        for (s in b.statuses) { h = 31 * h + s.id.hashCode(); h = 31 * h + s.name.hashCode(); h = 31 * h + (if (s.done) 1 else 0) }
        val f = t.filter
        h = 31 * h + (f?.hashCode() ?: 0)
        for (r in rows) {
            h = 31 * h + r.id.hashCode()
            for (c in cols) h = 31 * h + r.get(c.k).hashCode()
            h = 31 * h + r.dcust.hashCode() // «На подписи у заказчика от …» (0.25)
        }
        h = 31 * h + b.events.size
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
                else for (r in rows) w = max(w, tw(IdTables.cellText(b, r, c.k), FONT, c.k == TableCol.ZAH || c.k == TableCol.UCH) + 2 * PAD +
                    if (c.k == TableCol.ZAH && (hasPlan || swatch.containsKey(r.zkey()))) SWATCH + 5f else 0f)
                w = min(max(w, if (c.k == TableCol.ZAH || c.k == TableCol.UCH) 64f else 56f), if (c.k == TableCol.NOTE) 260f else 230f)
            }
            widths[ci] = w
        }
        // Части объёма (0.24): над ними — общая шапка «Объём по схеме»; вместе не уже её.
        val partIdx = cols.indices.filter { cols[it].part != null }
        val headH = if (partIdx.isNotEmpty()) HEAD2_H else HEAD_H
        if (partIdx.isNotEmpty()) {
            for (i in partIdx) widths[i] = max(widths[i], 70f)
            val grpW = tw("Объём по схеме", HEAD_FONT, true) + 2 * PAD
            val sum = partIdx.sumOf { widths[it].toDouble() }.toFloat()
            if (sum < grpW) widths[partIdx.last()] += grpW - sum
        }
        var total = widths.sum()
        val chip = kindChip(t)
        val titleW = tw(t.title.ifEmpty { "Без шифра" }, TITLE_FONT, true) + (if (prefix.isEmpty()) 0f else tw("$prefix ·", 14f, false) + 8f) +
            (if (chip.isEmpty()) 0f else tw(chip, 12f, true) + 32f)
        val need = PAD * 2 + titleW + 24f + tw(info, 12f, false) + PAD
        val addW = PAD + tw("+ строка", FONT, true) + PAD * 2
        val need2 = addW + tw("+ следующая захватка", FONT, true) + PAD * 2
        val vol = if (volOn && all.isNotEmpty()) IdTables.volumes(b, t, all) else null
        val planAddW = tw("+ план", FONT, true) + PAD * 2
        val chipText = arrayOf(if (t.zoneColor == TablePlan.OWN) "Цвет: свой ▾" else "Цвет: по ИГС ▾", "+ лист", "Обвести захватку")
        val chipW = chipText.map { tw(it, 13f, true) + 24f }
        val planHeadNeed = PAD * 2 + tw("План захваток", 16f, true) + 30f + chipW.sum() + 8f * chipW.size
        var needAll = max(max(need, need2 + if (hasPlan) 0f else planAddW + PAD), if (vol != null) VOL_MIN_W else 0f)
        if (hasPlan) needAll = max(needAll, max(PLAN_MIN_W, planHeadNeed))
        if (cols.isNotEmpty() && needAll > total) { widths[widths.size - 1] += needAll - total; total = needAll }
        if (cols.isEmpty()) total = needAll
        val colX = FloatArray(cols.size + 1)
        for (i in cols.indices) colX[i + 1] = colX[i] + widths[i]
        val volTop = TITLE_H + headH + max(1, rows.size) * ROW_H
        val missing = vol?.flatMap { it.missing } ?: emptyList()
        val vparts = t.parts()
        val volH = when {
            vol == null -> 0f
            vparts.isNotEmpty() -> VOL_HEAD + VOLP_HEAD + max(1, vol.map { IdTables.norm(it.work) }.distinct().size) * VOLP_ROW + (if (missing.isNotEmpty()) VOL_NOTE else 0f) + VOL_PAD
            else -> VOL_HEAD + max(1, vol.size) * VOL_ROW + (if (missing.isNotEmpty()) VOL_NOTE else 0f) + VOL_PAD
        }
        var height = volTop + volH + FOOT_H
        var plan: PlanLayout? = null
        if (hasPlan) {
            val top = height
            val tabsH = if (t.sheets.size > 1) PLAN_TABS else 0f
            val tabX = FloatArray(t.sheets.size + 1)
            tabX[0] = PLAN_PAD
            for ((i, sh) in t.sheets.withIndex()) tabX[i + 1] = tabX[i] + tw(sheetName(t, i), 12.5f, true) + 26f + 6f
            val sheetW = total - 2 * PLAN_PAD
            val cur = TablePlan.current(t)!!
            val sheetH = sheetW * cur.aspect()
            val sheetTop = top + PLAN_HEAD + tabsH
            val legend = TablePlan.legend(b, t)
            val chips = FloatArray(6)
            var x = total - PAD
            for (i in 2 downTo 0) { chips[i * 2 + 1] = x; x -= chipW[i]; chips[i * 2] = x; x -= 8f }
            val ph = PLAN_HEAD + tabsH + sheetH + PLAN_PAD + PLAN_LEG
            plan = PlanLayout(top, ph, tabsH, tabX, sheetTop, sheetW, sheetH, chips, chipText, legend, if (t.sheets.size > 1) "" else cur.name)
            height += ph
        }
        val planSplit = if (hasPlan) Float.MAX_VALUE else total - planAddW
        return TableLayout(h, cols, colX, rows, total, height, info, addW, prefix, vol, volTop, volH, missing, swatch, plan, planSplit, headH,
            if (vol != null) vparts else emptyList()).also { t.lay = it }
    }

    fun sheetName(t: BoardTable, i: Int): String = t.sheets.getOrNull(i)?.name?.ifEmpty { null } ?: "Лист ${i + 1}"

    /** Лист плана (видимая часть) на доске; null — листов нет. */
    fun sheetRect(b: Board, t: BoardTable, out: RectF): RectF? {
        val p = layout(b, t).plan ?: return null
        val k = t.scale
        out.set(t.x + PLAN_PAD * k, t.y + p.sheetTop * k, t.x + (PLAN_PAD + p.sheetW) * k, t.y + (p.sheetTop + p.sheetH) * k)
        return out
    }

    /** Точка доски → доли картинки листа целиком (с учётом обрезки). */
    fun toSheet(t: BoardTable, sh: PlanSheet, r: RectF, x: Float, y: Float, out: FloatArray) {
        out[0] = sh.cl + (x - r.left) / r.width() * (sh.cr - sh.cl)
        out[1] = sh.ct + (y - r.top) / r.height() * (sh.cb - sh.ct)
    }

    /** Доли картинки → точка доски. */
    fun fromSheet(sh: PlanSheet, r: RectF, fx: Float, fy: Float, out: FloatArray) {
        out[0] = r.left + (fx - sh.cl) / (sh.cr - sh.cl) * r.width()
        out[1] = r.top + (fy - sh.ct) / (sh.cb - sh.ct) * r.height()
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
        var colI = l.cols.size - 1
        for (i in l.cols.indices) if (u < l.colX[i + 1]) { col = l.cols[i].k; colI = i; break }
        if (v < rowsTop(l)) return TableHit(TableHit.HEAD, null, col)
        val ri = ((v - rowsTop(l)) / ROW_H).toInt()
        if (ri < l.rows.size) return TableHit(TableHit.CELL, l.rows[ri], col).also { if (colI >= 0) it.cx = u - l.colX[colI] }
        if (v < rowsTop(l) + max(1, l.rows.size) * ROW_H) return TableHit(TableHit.ADD, null, col) // пустая таблица — «+ строка»
        val vol = l.vol
        if (vol != null && v < l.volTop + l.volH && l.volParts.isNotEmpty()) {
            // Сетка по частям (0.24): ячейка вид работ × часть — проект этой части.
            val dv = v - l.volTop
            if (dv < VOL_HEAD + VOLP_HEAD) return TableHit(TableHit.VOL_ADD, null, null)
            val works = vol.map { it.work }.distinctBy { IdTables.norm(it) }
            val i = ((dv - VOL_HEAD - VOLP_HEAD) / VOLP_ROW).toInt()
            if (i >= works.size) return TableHit(if (l.volMissing.isNotEmpty()) TableHit.VOL_NOTE else TableHit.VOL_ADD, null, null)
            val pw = (l.w - VOLP_WORK - VOLP_TOTAL) / l.volParts.size
            val pi = ((u - VOLP_WORK) / pw).toInt()
            return TableHit(TableHit.VOL, null, works[i]).also { it.vpart = l.volParts.getOrNull(pi)?.key.orEmpty() }
        }
        if (vol != null && v < l.volTop + l.volH) {
            val dv = v - l.volTop
            if (dv < VOL_HEAD) return TableHit(TableHit.VOL_ADD, null, null)
            val i = ((dv - VOL_HEAD) / VOL_ROW).toInt()
            if (i < vol.size) return TableHit(TableHit.VOL, null, vol[i].work)
            if (vol.isEmpty() && i == 0) return TableHit(TableHit.VOL_ADD, null, null)
            return TableHit(if (l.volMissing.isNotEmpty()) TableHit.VOL_NOTE else TableHit.VOL_ADD, null, null)
        }
        val p = l.plan
        if (p != null && v >= p.top) {
            val dv = v - p.top
            if (dv < PLAN_HEAD) {
                for (i in 0 until 3) if (u >= p.chips[i * 2] - 4f && u <= p.chips[i * 2 + 1] + 4f)
                    return TableHit(when (i) { 0 -> TableHit.PLAN_COLOR; 1 -> TableHit.PLAN_ADD; else -> TableHit.PLAN_OUTLINE }, null, null)
                return TableHit(TableHit.TITLE, null, null)
            }
            if (dv < PLAN_HEAD + p.tabsH) {
                for (i in 0 until p.tabX.size - 1) if (u >= p.tabX[i] && u < p.tabX[i + 1]) return TableHit(TableHit.PLAN_TAB, null, i.toString())
                return TableHit(TableHit.TITLE, null, null)
            }
            val sh = TablePlan.current(t)
            if (sh != null && v >= p.sheetTop && v <= p.sheetTop + p.sheetH && u >= PLAN_PAD && u <= PLAN_PAD + p.sheetW) {
                return TableHit(TableHit.PLAN_SHEET, null, null).also {
                    it.fx = sh.cl + (u - PLAN_PAD) / p.sheetW * (sh.cr - sh.cl)
                    it.fy = sh.ct + (v - p.sheetTop) / p.sheetH * (sh.cb - sh.ct)
                }
            }
            return TableHit(TableHit.TITLE, null, null)
        }
        if (u >= l.planSplit) return TableHit(TableHit.PLAN_ADD, null, null)
        return TableHit(if (u < l.addSplit) TableHit.ADD else TableHit.ADD_NEXT, null, col)
    }

    /** Строка rec на доске (null — строки в таблице не видно). */
    fun rowRect(b: Board, t: BoardTable, rec: BoardRec, out: RectF): RectF? {
        val l = layout(b, t)
        val i = l.rows.indexOfFirst { it === rec }
        if (i < 0) return null
        val k = t.scale
        out.set(t.x, t.y + (rowsTop(l) + i * ROW_H) * k, t.x + l.w * k, t.y + (rowsTop(l) + (i + 1) * ROW_H) * k)
        return out
    }

    /** Ячейка (строка + столбец) на доске. */
    fun cellRect(b: Board, t: BoardTable, rec: BoardRec, col: String, out: RectF): RectF? {
        val l = layout(b, t)
        val i = l.rows.indexOfFirst { it === rec }
        val ci = l.cols.indexOfFirst { it.k == col }
        if (i < 0 || ci < 0) return null
        val k = t.scale
        out.set(t.x + l.colX[ci] * k, t.y + (rowsTop(l) + i * ROW_H) * k, t.x + l.colX[ci + 1] * k, t.y + (rowsTop(l) + (i + 1) * ROW_H) * k)
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
        val top = y0 + rowsTop(l) * k
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
        val pIdx = l.cols.indices.filter { l.cols[it].part != null }
        if (pIdx.isNotEmpty()) {
            // Два этажа: «Объём по схеме» над частями (0.24).
            val gl = x0 + l.colX[pIdx.first()] * k; val gr = x0 + l.colX[pIdx.last() + 1] * k
            val half = TITLE_H + l.headH / 2f
            fill.color = GRP_BG
            canvas.drawRect(gl, y0 + TITLE_H * k, gr, y0 + l.headH * k + TITLE_H * k, fill)
            line.color = Color.rgb(201, 214, 242); line.strokeWidth = 1f * k
            canvas.drawLine(gl, y0 + half * k, gr, y0 + half * k, line)
            text.color = GRP_FG
            val g = "Объём по схеме"
            canvas.drawText(fit(g, gr - gl - 2 * PAD * k), (gl + gr) / 2f - min(text.measureText(g), gr - gl - 2 * PAD * k) / 2f, y0 + (TITLE_H + l.headH / 4f) * k + text.textSize * 0.36f, text)
        }
        for ((ci, c) in l.cols.withIndex()) {
            val cw = (l.colX[ci + 1] - l.colX[ci]) * k
            if (c.part != null) {
                text.color = GRP_FG
                val tt = fit(c.title(), cw - 2 * PAD * k)
                canvas.drawText(tt, x0 + l.colX[ci + 1] * k - PAD * k - text.measureText(tt), y0 + (TITLE_H + l.headH * 0.75f) * k + text.textSize * 0.36f, text)
                text.color = Ui.INK2
                continue
            }
            canvas.drawText(fit(c.title(), cw - 2 * PAD * k), x0 + (l.colX[ci] + PAD) * k, y0 + (TITLE_H + l.headH / 2f) * k + text.textSize * 0.36f, text)
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
        val ttl = t.title.ifEmpty { "Без шифра" }
        canvas.drawText(ttl, tx, y0 + TITLE_H / 2f * k + text.textSize * 0.36f, text)
        val chip = kindChip(t)
        if (chip.isNotEmpty()) {
            // Вид таблицы (0.24) — плашкой после названия: «вид: КЖ · вертикальные элементы».
            val cx = tx + text.measureText(ttl) + 14f * k
            text.isFakeBoldText = true
            text.textSize = 12f * k
            val cw = text.measureText(chip)
            rect.set(cx, y0 + (TITLE_H / 2f - 10f) * k, cx + cw + 16f * k, y0 + (TITLE_H / 2f + 10f) * k)
            fill.color = GRP_BG
            canvas.drawRoundRect(rect, 7f * k, 7f * k, fill)
            text.color = GRP_FG
            canvas.drawText(chip, cx + 8f * k, y0 + TITLE_H / 2f * k + text.textSize * 0.36f, text)
        }
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
        if (l.vol != null) {
            if (l.volParts.isNotEmpty()) drawVolumeGrid(canvas, t, l, x0, y0 + l.volTop * k, w, k)
            else drawVolumes(canvas, b, t, l, x0, y0 + l.volTop * k, w, k)
        }
        text.textSize = FONT * k
        text.isFakeBoldText = true
        text.color = Ui.ACCENT
        val fy = y0 + (l.volTop + l.volH) * k + FOOT_H / 2f * k + text.textSize * 0.36f
        canvas.drawText("+ строка", x0 + PAD * k, fy, text)
        canvas.drawText("+ следующая захватка", x0 + (l.addSplit + PAD) * k, fy, text)
        if (l.planSplit < l.w) canvas.drawText("+ план", x0 + (l.planSplit + PAD) * k, fy, text)
        text.isFakeBoldText = false
        l.plan?.let { drawPlan(canvas, t, l, it, x0, y0, w, k) }
        outline(canvas, x0, y0, w, h, k)
    }

    /** Шапка плана, вкладки, подложка листа (картинку и области рисует доска), легенда. */
    private fun drawPlan(canvas: Canvas, t: BoardTable, l: TableLayout, p: PlanLayout, x0: Float, y0: Float, w: Float, k: Float) {
        val top = y0 + p.top * k
        fill.color = PLAN_BG
        canvas.drawRect(x0, top, x0 + w, top + PLAN_HEAD * k, fill)
        line.color = Ui.RULE; line.strokeWidth = 1f * k
        canvas.drawLine(x0, top, x0 + w, top, line)
        text.textSize = 16f * k; text.isFakeBoldText = true; text.color = Ui.INK
        val ty = top + PLAN_HEAD / 2f * k + 16f * k * 0.36f
        canvas.drawText("План захваток", x0 + PAD * 1.4f * k, ty, text)
        val tw0 = text.measureText("План захваток")
        text.isFakeBoldText = false; text.textSize = 12.5f * k; text.color = Ui.MUTED
        if (p.title.isNotEmpty()) canvas.drawText(fit(p.title, p.chips[0] * k - tw0 - 40f * k), x0 + PAD * 1.4f * k + tw0 + 12f * k, ty, text)
        for (i in 0 until 3) {
            rect.set(x0 + p.chips[i * 2] * k, top + 8f * k, x0 + p.chips[i * 2 + 1] * k, top + (PLAN_HEAD - 8f) * k)
            fill.color = if (i == 2) Ui.ACCENT else Color.rgb(226, 231, 239)
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
            text.textSize = 13f * k; text.isFakeBoldText = true; text.color = if (i == 2) Color.WHITE else Ui.INK
            canvas.drawText(p.chipText[i], rect.left + 12f * k, rect.centerY() + 13f * k * 0.36f, text)
        }
        text.isFakeBoldText = false
        // Вкладки листов.
        if (p.tabsH > 0f) {
            val ty0 = top + PLAN_HEAD * k
            for (i in 0 until p.tabX.size - 1) {
                val on = i == t.tab.coerceIn(0, t.sheets.size - 1)
                rect.set(x0 + p.tabX[i] * k, ty0 + 5f * k, x0 + (p.tabX[i + 1] - 6f) * k, ty0 + p.tabsH * k)
                fill.color = if (on) Color.WHITE else Color.rgb(234, 238, 244)
                canvas.drawRoundRect(rect, 7f * k, 7f * k, fill)
                if (on) { fill.color = Ui.ACCENT; canvas.drawRect(rect.left + 4f * k, rect.top, rect.right - 4f * k, rect.top + 2.5f * k, fill) }
                text.textSize = 12.5f * k; text.isFakeBoldText = on; text.color = if (on) Ui.INK else Ui.INK2
                canvas.drawText(sheetName(t, i), rect.left + 13f * k, rect.centerY() + 12.5f * k * 0.36f + 2f * k, text)
            }
            text.isFakeBoldText = false
        }
        // Подложка листа (пока картинка грузится).
        val sy = y0 + p.sheetTop * k
        fill.color = Ui.DESK
        canvas.drawRect(x0 + PLAN_PAD * k, sy, x0 + (PLAN_PAD + p.sheetW) * k, sy + p.sheetH * k, fill)
        // Легенда.
        val ly = sy + (p.sheetH + PLAN_PAD + PLAN_LEG / 2f) * k
        var lx = x0 + PAD * 1.4f * k
        text.textSize = 12.5f * k
        val cy = ly + 12.5f * k * 0.36f
        if (t.zoneColor == TablePlan.OWN) {
            text.color = Ui.MUTED
            canvas.drawText("Цвет — свой у каждой захватки. Касание области — её строки в таблице", lx, cy, text)
            return
        }
        if (p.legend.isEmpty()) {
            text.color = Ui.MUTED
            canvas.drawText("Обведите захватки на плане — «Обвести захватку» или касание номера захватки в таблице", lx, cy, text)
            return
        }
        for ((st, n) in p.legend) {
            val c = st?.color ?: TablePlan.NO_ROWS
            rect.set(lx, ly - 7f * k, lx + 14f * k, ly + 7f * k)
            fill.color = (c and 0x00FFFFFF) or (0x5A shl 24)
            canvas.drawRoundRect(rect, 3f * k, 3f * k, fill)
            line.color = c; line.strokeWidth = 1.5f * k
            canvas.drawRoundRect(rect, 3f * k, 3f * k, line)
            text.color = Ui.INK2
            val s = "${st?.name ?: "строк нет"} — $n"
            canvas.drawText(s, lx + 20f * k, cy, text)
            lx += 20f * k + text.measureText(s) + 18f * k
        }
        text.color = Ui.MUTED
        val note = "цвет — по самой отстающей схеме захватки"
        val nw = text.measureText(note)
        if (lx + nw < x0 + w - PAD * k) canvas.drawText(note, x0 + w - PAD * 1.4f * k - nw, cy, text)
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
            val name = fit(if (c.k == TableCol.IGS) IdTables.igsText(b, r) else st.name, cw - 2 * PAD * k - 22f * k)
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
            // Часть объёма (0.24) — своё поле строки; у таблицы с частями общий «Объём по схеме» не считается.
            val tb = IdTables.tableOf(b, r)
            val raw = if (c.part != null) r.get(c.k) else if (tb != null && tb.parts().isNotEmpty()) "" else r.vol
            if (raw.isEmpty()) {
                text.textSize = FONT * k; text.color = Ui.MUTED
                val dash = "—"
                canvas.drawText(dash, cx + cw - PAD * k - text.measureText(dash), cy + text.textSize * 0.36f, text)
                return
            }
            val u = tb?.let { IdTables.unitFor(it, r.work) }.orEmpty()
            text.textSize = 11.5f * k
            val uw = if (u.isEmpty()) 0f else text.measureText(u) + 3f * k
            val right = cx + cw - PAD * k
            if (u.isNotEmpty()) { text.color = Ui.MUTED; canvas.drawText(u, right - uw + 3f * k, cy + FONT * k * 0.36f, text) }
            text.textSize = FONT * k; text.color = Ui.INK
            val n = IdTables.volText(raw)
            canvas.drawText(n, right - uw - text.measureText(n), cy + text.textSize * 0.36f, text)
            return
        }
        val s = IdTables.cellText(b, r, c.k)
        if (s.isEmpty()) return
        var tx = cx + PAD * k
        if (c.k == TableCol.ZAH) {
            // Захватка привязана к заливке на плане — значок её цвета перед номером.
            val lay = t0(b, r)?.lay
            val col = lay?.swatch?.get(r.zkey())
            if (col != null) {
                rect.set(tx, cy - SWATCH / 2f * k, tx + SWATCH * k, cy + SWATCH / 2f * k)
                fill.color = col
                canvas.drawRoundRect(rect, 3f * k, 3f * k, fill)
                line.color = Board.statusFg(col); line.strokeWidth = 1f * k
                canvas.drawRoundRect(rect, 3f * k, 3f * k, line)
                tx += (SWATCH + 5f) * k
            } else if (lay?.plan != null) {
                // Есть план, а захватка на нём не обведена — пунктирный квадратик (касание — обвести).
                rect.set(tx + 0.75f * k, cy - SWATCH / 2f * k + 0.75f * k, tx + SWATCH * k - 0.75f * k, cy + SWATCH / 2f * k - 0.75f * k)
                line.color = Ui.ACCENT; line.strokeWidth = 1.3f * k
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2.5f * k, 2f * k), 0f)
                canvas.drawRoundRect(rect, 3f * k, 3f * k, line)
                line.pathEffect = null
                tx += (SWATCH + 5f) * k
            }
        }
        text.textSize = FONT * k
        text.color = if (c.k == TableCol.ZAH || c.k == TableCol.UCH) Ui.INK else Ui.INK2
        text.isFakeBoldText = c.k == TableCol.ZAH || c.k == TableCol.UCH
        canvas.drawText(fit(s, cx + cw - PAD * k - tx), tx, cy + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
    }

    /**
     * Блок «Объёмы» внизу таблицы (0.21): по каждому виду работ — сколько схем с объёмом, факт (сумма столбца
     * «Объём по схеме»), проект (рамка ✎ — вписать), разница: «осталось …» синим или «перебор +… · +… %» красным,
     * полоска выполнения и процент.
     */
    /**
     * Итоги объёмов сеткой (0.24, вид с частями): строки — виды работ, столбцы — части («Стены», «Колонны») и «Всего».
     * В ячейке: факт, проект (рамка ✎ — вписать), полоска, «осталось …» или «перебор …», процент.
     */
    private fun drawVolumeGrid(canvas: Canvas, t: BoardTable, l: TableLayout, x0: Float, top: Float, w: Float, k: Float) {
        val vol = l.vol ?: return
        val parts = l.volParts
        fill.color = VOL_BG
        canvas.drawRect(x0, top, x0 + w, top + l.volH * k, fill)
        line.color = Color.rgb(170, 182, 204); line.strokeWidth = 1.2f * k
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
        txt("факт — сумма по схемам; проект — для каждой части (рамка ✎)", L + hw + 12f * k, hy, 12.5f, Ui.MUTED)
        val plus = "+ проект"
        text.textSize = 13.5f * k; text.isFakeBoldText = true
        val pw0 = text.measureText(plus)
        txt(plus, x0 + w - 14f * k - pw0, hy, 13.5f, Ui.ACCENT, bold = true)
        val partW = (l.w - VOLP_WORK - VOLP_TOTAL) / parts.size
        fun colL(i: Int) = x0 + (VOLP_WORK + i * partW) * k
        val totL = x0 + (l.w - VOLP_TOTAL) * k
        val gy = top + (VOL_HEAD + VOLP_HEAD * 0.65f) * k
        for ((i, p) in parts.withIndex()) txt(p.name, colL(i) + 10f * k, gy, 12.5f, GRP_FG, bold = true)
        txt("Всего", totL + 10f * k, gy, 12.5f, GRP_FG, bold = true)
        val works = vol.map { it.work }.distinctBy { IdTables.norm(it) }
        if (works.isEmpty()) {
            txt("Впишите объёмы в столбцах ${parts.joinToString(" и ") { "«${it.name}»" }} — здесь появятся итоги; проект — «+ проект»", L,
                top + (VOL_HEAD + VOLP_HEAD + VOLP_ROW / 2f) * k, 13.5f, Ui.MUTED)
        }
        for ((wi, wk) in works.withIndex()) {
            val ry = top + (VOL_HEAD + VOLP_HEAD + wi * VOLP_ROW) * k
            line.color = Color.rgb(227, 232, 239); line.strokeWidth = 1f * k
            canvas.drawLine(x0 + 10f * k, ry, x0 + w - 10f * k, ry, line)
            txt(wk, L + 6f * k, ry + 26f * k, 15f, Ui.INK, bold = true)
            var sumF = 0.0; var sumP = 0.0; var allP = true; var unit = ""
            for ((pi, p) in parts.withIndex()) {
                val v = vol.firstOrNull { IdTables.norm(it.work) == IdTables.norm(wk) && it.part == p.key } ?: continue
                unit = v.unit
                sumF += v.fact
                if (v.plan != null) sumP += v.plan else allP = false
                val cx = colL(pi) + 10f * k
                val cwid = partW * k - 20f * k
                var x = cx
                x += txt(IdTables.volFmt(v.fact), x, ry + 26f * k, 16f, Ui.INK, bold = true) + 4f * k
                if (v.unit.isNotEmpty()) x += txt(v.unit, x, ry + 26f * k, 11.5f, Ui.MUTED) + 6f * k
                x += txt("из", x, ry + 26f * k, 11.5f, Ui.MUTED) + 12f * k
                val pt = if (v.plan == null) "вписать" else IdTables.volFmt(v.plan) + if (v.unit.isNotEmpty()) " " + v.unit else ""
                text.textSize = 13.5f * k; text.isFakeBoldText = v.plan != null
                val ptw = text.measureText(pt)
                text.isFakeBoldText = false
                rect.set(x - 5f * k, ry + 11f * k, x + ptw + 22f * k, ry + 32f * k)
                fill.color = Color.WHITE
                canvas.drawRoundRect(rect, 6f * k, 6f * k, fill)
                line.color = Color.rgb(150, 165, 190); line.strokeWidth = 1.2f * k
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * k, 3f * k), 0f)
                canvas.drawRoundRect(rect, 6f * k, 6f * k, line)
                line.pathEffect = null
                txt(pt, x, ry + 26f * k, 13.5f, if (v.plan == null) Ui.MUTED else Ui.INK, bold = v.plan != null)
                txt("✎", x + ptw + 6f * k, ry + 26f * k, 12f, Ui.ACCENT)
                if (v.plan == null) continue
                // Полоска выполнения и «осталось»/«перебор».
                val frac = if (v.over) 1f else (v.fact / v.plan).toFloat().coerceIn(0f, 1f)
                val bw = min(cwid, 260f * k)
                rect.set(cx, ry + 40f * k, cx + bw, ry + 49f * k)
                fill.color = BAR_BG
                canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
                rect.set(cx, ry + 40f * k, cx + bw * frac, ry + 49f * k)
                fill.color = if (v.over) Ui.BAD else BAR
                if (frac > 0f) canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
                val diff = kotlin.math.abs(v.fact - v.plan)
                val chip = if (v.over) "перебор +${IdTables.volFmt(diff)} ${v.unit}".trimEnd() else "осталось ${IdTables.volFmt(diff)} ${v.unit}".trimEnd()
                text.textSize = 12f * k; text.isFakeBoldText = true
                val chw = text.measureText(chip) + 18f * k
                text.isFakeBoldText = false
                rect.set(cx, ry + 55f * k, cx + chw, ry + 73f * k)
                fill.color = if (v.over) Ui.BAD else LEFT_BG
                canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
                txt(chip, cx + 9f * k, ry + 68f * k, 12f, if (v.over) Color.WHITE else LEFT_FG, bold = true)
                txt("${v.percent} %", cx + chw + 8f * k, ry + 68f * k, 12.5f, if (v.over) Ui.BAD else Ui.INK, bold = true)
            }
            // Всего — сумма частей.
            val tx0 = totL + 10f * k
            var x = tx0
            x += txt(IdTables.volFmt(sumF), x, ry + 26f * k, 16f, Ui.INK, bold = true) + 4f * k
            if (allP && sumP > 0.0) txt("из ${IdTables.volFmt(sumP)} $unit".trimEnd(), x, ry + 26f * k, 12f, Ui.MUTED)
            else if (unit.isNotEmpty()) txt(unit, x, ry + 26f * k, 11.5f, Ui.MUTED)
            txt(if (allP && sumP > 0.0) "сумма частей · ${Math.round(sumF / sumP * 100)} %" else "сумма частей", tx0, ry + 46f * k, 12f, Ui.MUTED)
        }
        if (l.volMissing.isNotEmpty()) {
            val ny = top + (VOL_HEAD + VOLP_HEAD + max(1, works.size) * VOLP_ROW + VOL_NOTE / 2f) * k + 4f * k
            val n = l.volMissing.size
            val ww = txt("⚠ Объём не указан у ${Ui.plural(n, "схемы", "схем", "схем")}", L, ny, 13f, Ui.WARN, bold = true)
            txt(" — в факт не вошли. Касание — показать эти строки.", L + ww, ny, 13f, Ui.INK2)
        }
    }

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
