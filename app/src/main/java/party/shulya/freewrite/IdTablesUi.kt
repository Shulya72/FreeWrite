package party.shulya.freewrite

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.min

/**
 * Таблицы ИД на экране доски (ТЗ «Учёт ИГС», шаг 1): создать таблицу, заполнить строки (форма «+ строка», «+ следующая
 * захватка»), править ячейки (палец — карточка строки снизу, перо — список статусов у ячейки или запись в ячейку),
 * столбцы, отбор, «В таблицу» для старых строк ИД, сводка и контроль, выгрузка в Excel.
 */
class TableUi(private val a: BoardActivity) {
    private val b: Board get() = a.board
    private val v: BoardView get() = a.view
    private fun dp(x: Float) = Ui.dp(a, x)

    /** Кто правит (для истории схемы). */
    private fun who() = Profile.name(a)

    /** Поставить значение ячейки; статусы и даты — с записью в историю схемы (ТЗ, раздел 7). */
    private fun put(r: BoardRec, col: String, value: String) = Vedoms.set(b, r, col, value, who())

    /** Срок «долго на подписи» в днях (настраивается в сводке; хранится на планшете). */
    var longDays: Int
        get() = a.getSharedPreferences("igs", Context.MODE_PRIVATE).getInt("longDays", 7)
        set(v) { a.getSharedPreferences("igs", Context.MODE_PRIVATE).edit().putInt("longDays", v.coerceIn(1, 90)).apply() }

    /** Открытая карточка строки (для проверок) и окно-список у ячейки. */
    internal var card: Dialog? = null
    internal var popup: PopupWindow? = null

    // Перо пишет в ячейку несколько слов подряд — дописываем, а не заменяем.
    private var penCell: String? = null
    private var penCellTime = 0L

    // ---------------------------------------------------------------- создание

    /** «Таблица ИД»: касание пустого места — новая таблица с шифром (предложен раздел, где коснулись). */
    fun create(x: Float, y: Float) {
        // Раздел = шифр (0.21): в разделе таблица — подраздел («Несущие стены»), шифр берётся у раздела.
        val sec = b.sectionAt(x, y)?.title?.trim().orEmpty()
        val edit = EditText(a).apply {
            isSingleLine = true; tag = "table_title"
            hint = if (sec.isNotEmpty()) "например: Несущие стены" else "например: КЖ 6.1.1"
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(Ui.text(a, if (sec.isNotEmpty()) "Шифр — «$sec» (раздел). Таблица — подраздел со своей нумерацией захваток и своими объёмами. " +
                "Пусто — одна таблица на весь шифр." else "Все схемы одного шифра — в одной таблице. Строки ровные, размер — как у других таблиц доски.", 13f, Ui.MUTED))
            addView(edit)
        }
        val d = AlertDialog.Builder(a)
            .setTitle(if (sec.isNotEmpty()) "Новая таблица в разделе «$sec»" else "Новая таблица ИД — шифр")
            .setView(box)
            .setPositiveButton("Создать") { _, _ ->
                val title = edit.text.toString().trim().ifEmpty { sec }
                a.pushUndo()
                val t = BoardTable(x, y, IdTables.defaultScale(b, a.resources.displayMetrics.density), title)
                b.tables += t
                a.changed()
                a.backToHand()
                addForm(t, next = false)
            }
            .setNegativeButton("Отмена", null)
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        edit.requestFocus()
    }

    // ---------------------------------------------------------------- касания

    /**
     * Касание таблицы. Статус (цветная «таблетка») — и пером, и пальцем — список рядом: выбор вторым касанием, случайное
     * касание ничего не меняет. Остальная строка: пером — поле ячейки, пальцем — карточка строки.
     */
    fun onTap(t: BoardTable, h: TableHit, pen: Boolean) {
        when (h.part) {
            TableHit.ADD -> addForm(t, next = false)
            TableHit.ADD_NEXT -> addForm(t, next = true)
            TableHit.VOL -> projectDialog(t, h.col)
            TableHit.VOL_ADD -> projectDialog(t, null)
            TableHit.VOL_NOTE -> showRows(v.tableDraw.layout(b, t).volMissing)
            TableHit.CELL -> {
                val r = h.rec ?: return
                val col = h.col
                val c = col?.let { k -> t.allCols().firstOrNull { it.k == k } }
                if (col != null && c != null && c.isStatus) { cellAction(t, r, col); return }
                if (!pen || col == null) { rowCard(r); return }
                cellAction(t, r, col)
            }
            else -> v.select(listOf(t))
        }
    }

    /** Действие с ячейкой: статус — список у ячейки, дата — календарь, текст — поле с подсказками. */
    fun cellAction(t: BoardTable, r: BoardRec, col: String) {
        val c = t.allCols().firstOrNull { it.k == col } ?: TableCol(col)
        when {
            c.isStatus -> {
                val rect = v.tableDraw.cellRect(b, t, r, col, RectF())
                statusPopup(IdTables.groupOf(col), r.get(col), rect) { sid -> setCell(r, col, sid) }
            }
            c.isDate -> datePicker(r, col)
            else -> textDialog(t, r, col)
        }
    }

    /**
     * Касание пером по таблице в инструменте рисования/письма: на статусе — список у ячейки; в «В текст» на тексте или
     * дате — ячейка становится активной: дальше пишут где угодно, текст встаёт в неё. Касание пером мимо таблиц — снять
     * активную ячейку. true — касание забрали (точку не рисуем).
     */
    fun penTap(x: Float, y: Float): Boolean {
        val t = b.tables.lastOrNull { v.tableDraw.bounds(b, it, RectF()).contains(x, y) }
        if (t == null) {
            if (active != null) { deactivate(); return true }
            return false
        }
        val h = v.tableDraw.hit(b, t, x, y) ?: return false
        if (h.part == TableHit.CELL && h.rec != null && h.col != null) {
            val c = t.allCols().firstOrNull { it.k == h.col }
            if (c != null && c.isStatus) { cellAction(t, h.rec, h.col); return true }
            if (a.activeTool == BoardActivity.Tool.WRITE) { activate(h.rec, h.col); return true }
            return false // ручка, маркер — точка, пусть рисуют
        }
        onTap(t, h, pen = true)
        return true
    }

    // ---------------------------------------------------------------- активная ячейка (перо пишет где угодно)

    /** Ячейка, в которую идёт написанное пером (строка, столбец). */
    internal var active: Pair<BoardRec, String>? = null
        private set
    private var activeFresh = false

    /** Сделать ячейку активной: первая надпись заменит её текст, следующие допишутся через пробел. */
    fun activate(r: BoardRec, col: String) {
        active = r to col
        activeFresh = true
        penCell = null
        v.activeCell = active
        v.invalidate()
        showCellBar()
    }

    fun deactivate() {
        if (active == null) return
        active = null
        v.activeCell = null
        v.invalidate()
        a.hideModeBar()
    }

    /** Столбцы, куда пишут пером: текст и даты (статусы — касанием). */
    private fun writable(t: BoardTable) = t.visibleCols().filter { !it.isStatus }

    private fun showCellBar() {
        val (r, col) = active ?: return
        val t = IdTables.tableOf(b, r) ?: run { deactivate(); return }
        val rows = IdTables.rowsOf(b, t)
        val label = "Пишите где угодно → «${t.colName(col)}», ${rowTitle(t, r)}"
        a.showModeBar(label, listOf(
            Ui.pill(a, "→", Ui.INK, Ui.DESK).apply { tag = "cell_next"; contentDescription = "Следующая ячейка"; setOnClickListener { step(t, rows, 0, 1) } },
            Ui.pill(a, "↓", Ui.INK, Ui.DESK).apply { tag = "cell_down"; contentDescription = "Строка ниже"; setOnClickListener { step(t, rows, 1, 0) } },
            Ui.pill(a, "Готово", Color.WHITE, Ui.ACCENT).apply { tag = "cell_done"; setOnClickListener { deactivate() } },
        ))
    }

    /** → — следующий столбец для письма (в конце строки — первый столбец следующей строки); ↓ — тот же столбец ниже. */
    private fun step(t: BoardTable, rows: List<BoardRec>, dr: Int, dc: Int) {
        val (r, col) = active ?: return
        val cols = writable(t).map { it.k }
        var ri = rows.indexOf(r)
        var ci = cols.indexOf(col)
        if (ri < 0 || ci < 0) { deactivate(); return }
        ci += dc
        if (ci >= cols.size) { ci = 0; ri++ }
        ri += dr
        if (ri >= rows.size) { a.hint("Это последняя строка — «+ строка» внизу таблицы", 2000); return }
        activate(rows[ri], cols[ci])
    }

    /** Подержали строку — меню строки. */
    fun onLongPress(t: BoardTable, h: TableHit) {
        val r = h.rec
        if (r == null) { addForm(t, next = h.part == TableHit.ADD_NEXT); return }
        val items = arrayOf("Карточка строки", "История", "В ведомость", "Строка ниже", "Выше", "Ниже", "Конструкция не выполнена", "Удалить строку")
        AlertDialog.Builder(a)
            .setTitle(rowTitle(t, r))
            .setItems(items) { _, i ->
                when (i) {
                    0 -> rowCard(r)
                    1 -> a.vedomUi.historyDialog(r)
                    2 -> a.vedomUi.toVedom(listOf(r))
                    3 -> addForm(t, next = true, after = r)
                    4 -> move(r, -1)
                    5 -> move(r, 1)
                    6 -> notDone(listOf(r))
                    7 -> { a.pushUndo(); b.recs.removeAll { it === r }; a.changed(); a.hint("Строка удалена — «Отменить» вернёт", 1800) }
                }
            }
            .show()
    }

    private fun rowTitle(t: BoardTable, r: BoardRec) =
        listOf(t.colName(TableCol.ZAH) + " " + r.zah.ifEmpty { "—" }, r.work).filter { it.isNotBlank() }.joinToString(" · ")

    private fun move(r: BoardRec, d: Int) {
        val t = IdTables.tableOf(b, r) ?: return
        val rows = IdTables.allRows(b, t)
        val i = rows.indexOf(r)
        val j = i + d
        if (i < 0 || j !in rows.indices) return
        a.pushUndo()
        val o = rows[j].o
        rows[j].o = r.o; r.o = o
        a.changed()
    }

    fun notDone(rows: List<BoardRec>) {
        a.pushUndo()
        for (r in rows) { put(r, TableCol.IGS, IdTables.IGS_NONE); put(r, TableCol.CK, IdTables.CK_NONE); put(r, TableCol.DWG, IdTables.DWG_NONE) }
        a.changed()
        a.hint(if (rows.size == 1) "Конструкция не выполнена — ИГС, Чек-лист и DWG «Не выполнено»" else "«Не выполнено» — у ${rows.size} строк", 1800)
    }

    private fun setCell(r: BoardRec, col: String, value: String) {
        if (r.get(col) == value) return
        a.pushUndo()
        put(r, col, value)
        a.changed()
        val c = IdTables.tableOf(b, r)?.colName(col) ?: TableCol.defaultName(col)
        a.hint("$c: ${IdTables.cellText(b, r, col).ifEmpty { "—" }}", 1200)
    }

    // ---------------------------------------------------------------- перо в ячейку

    /**
     * Распознанная надпись над таблицей (инструмент «В текст»): в текстовую ячейку — её значение (ТЗ, раздел 14.3),
     * несколько слов подряд (пауза до 6 с) — дописываются. Над статусом — статус по началу названия. true — надпись забрали.
     */
    fun penIntoCell(x: Float, y: Float, word: String): Boolean {
        // Есть активная ячейка — написанное где угодно идёт в неё.
        active?.let { (r, col) ->
            if (b.recs.none { it === r } || IdTables.tableOf(b, r) == null) { deactivate(); return@let }
            val w = word.trim()
            if (w.isEmpty()) { a.hint("Не разобрал — напишите ещё раз", 1800); return true }
            val t = IdTables.tableOf(b, r)!!
            val name = t.colName(col)
            if (col == TableCol.DATE || col == TableCol.DCUST || col == TableCol.DPTO) {
                val d = IdTables.parseDate(w)
                if (d.isEmpty()) { a.hint("Дату — в виде 26.08.26", 1800); return true }
                put(r, col, d)
                a.hint("$name: ${IdTables.dateShort(d)}", 1200)
            } else if (col == TableCol.VOL) {
                val vv = IdTables.parseVol(w)
                if (vv.isEmpty()) { a.hint("Объём — числом, например 42,3", 1800); return true }
                put(r, col, vv)
                a.hint("$name: ${IdTables.cellText(b, r, col)}", 1200)
            } else {
                val nv = if (activeFresh || r.get(col).isEmpty()) w else r.get(col) + " " + w
                r.set(col, nv)
                a.hint("$name: $nv", 1200)
            }
            activeFresh = false
            v.invalidate()
            return true
        }
        val t = b.tables.lastOrNull { v.tableDraw.bounds(b, it, RectF()).contains(x, y) } ?: return false
        val h = v.tableDraw.hit(b, t, x, y)
        val w = word.trim()
        if (h == null || h.part != TableHit.CELL || h.rec == null || h.col == null) {
            if (h?.part == TableHit.TITLE && w.isNotEmpty()) { t.title = w; a.hint("Название таблицы: $w", 1500); return true }
            a.hint("Пишите прямо в ячейку строки", 1500)
            return true
        }
        val r = h.rec; val col = h.col
        if (w.isEmpty()) { a.hint("Не разобрал — напишите крупнее прямо в ячейке", 1800); return true }
        val c = t.allCols().firstOrNull { it.k == col } ?: TableCol(col)
        when {
            c.isStatus -> {
                val q = w.lowercase()
                val st = b.statuses(IdTables.groupOf(col)).firstOrNull { it.name.lowercase().startsWith(q) }
                    ?: b.statuses(IdTables.groupOf(col)).firstOrNull { q.length >= 3 && it.name.lowercase().contains(q) }
                if (st != null) { put(r, col, st.id); a.hint("${c.title()}: ${st.name}", 1200) }
                else a.hint("Статус — касанием: откроется список", 1800)
            }
            c.isDate -> {
                val d = IdTables.parseDate(w)
                if (d.isNotEmpty()) { put(r, col, d); a.hint("${c.title()}: ${IdTables.dateShort(d)}", 1200) }
                else a.hint("Дату — в виде 26.08.26 или касанием", 1800)
            }
            c.isVol -> {
                val vv = IdTables.parseVol(w)
                if (vv.isNotEmpty()) { put(r, col, vv); a.hint("${c.title()}: ${IdTables.cellText(b, r, col)}", 1200) }
                else a.hint("Объём — числом, например 42,3", 1800)
            }
            else -> {
                val key = r.id + "|" + col
                val now = android.os.SystemClock.uptimeMillis()
                val v0 = r.get(col)
                val nv = if (penCell == key && now - penCellTime < 6000 && v0.isNotEmpty()) "$v0 $w" else w
                r.set(col, nv)
                penCell = key; penCellTime = now
                a.hint("${c.title()}: $nv", 1200)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- карточка строки (палец)

    private fun statusChip(group: String, id: String, big: Boolean = false): TextView {
        val st = IdTables.status(b, group, id)
        val fg = Board.statusFg(st.color)
        return Ui.text(a, st.name, if (big) 16f else 14f, fg, bold = true).apply {
            background = Ui.rounded(a, Board.statusBg(st.color), fg, 12f)
            setPadding(dp(12f), dp(if (big) 8f else 4f), dp(12f), dp(if (big) 8f else 4f))
        }
    }

    /** Список статусов крупными кнопками; текущий — с толстой рамкой и пометкой «сейчас». */
    private fun statusButtons(group: String, current: String?, tagPrefix: String, pick: (String) -> Unit): LinearLayout {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for ((i, st) in b.statuses(group).withIndex()) {
            val fg = Board.statusFg(st.color)
            val cur = st.id == current
            val btn = Ui.text(a, st.name + if (cur) "  — сейчас" else "", 16f, fg, bold = true).apply {
                background = GradientDrawable().apply {
                    cornerRadius = Ui.dpf(a, 12f); setColor(Board.statusBg(st.color)); setStroke(dp(if (cur) 3f else 1.5f), fg)
                }
                setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
                minHeight = dp(48f)
                gravity = Gravity.CENTER_VERTICAL
                tag = "${tagPrefix}_$i"
                isClickable = true
                setOnClickListener { pick(st.id) }
            }
            box.addView(btn, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        }
        return box
    }

    /** Окно-список статусов рядом с ячейкой (перо, планшет). rect — ячейка на доске. */
    fun statusPopup(group: String, current: String, rect: RectF?, pick: (String) -> Unit) {
        popup?.dismiss()
        val list = statusButtons(group, current, "pop_status") { sid -> popup?.dismiss(); pick(sid) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(10f))
            background = Ui.rounded(a, Ui.PAPER, Ui.RULE, 14f)
            addView(list)
        }
        val content = ScrollView(a).apply { addView(box) }
        val w = dp(300f)
        val pw = PopupWindow(content, w, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = Ui.dpf(a, 10f)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }
        popup = pw
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val ph = content.measuredHeight
        val screenH = a.resources.displayMetrics.heightPixels
        var x = loc[0] + dp(40f)
        var y = loc[1] + v.height / 3
        if (rect != null) {
            x = loc[0] + ((rect.left - b.camX) * b.camZ).toInt()
            val below = loc[1] + ((rect.bottom - b.camY) * b.camZ).toInt() + dp(4f)
            val above = loc[1] + ((rect.top - b.camY) * b.camZ).toInt() - dp(4f) - ph
            y = if (below + ph <= screenH || above < 0) below else above
        }
        x = x.coerceIn(0, (a.resources.displayMetrics.widthPixels - w).coerceAtLeast(0))
        pw.showAtLocation(v, Gravity.NO_GRAVITY, x, y.coerceAtLeast(0))
    }

    /** Карточка строки снизу (палец, телефон): всё крупно, правка без приближения доски (ТЗ, раздел 14.2). */
    fun rowCard(rec: BoardRec) {
        card?.dismiss()
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.PAPER)
                val r = Ui.dpf(a, 22f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            tag = "row_card"
        }
        val scroll = ScrollView(a)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(8f), dp(18f), dp(18f)) }
        scroll.addView(body)
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18f), dp(14f), dp(10f), dp(4f)) }
        root.addView(head)
        root.addView(scroll)
        var cur = rec

        lateinit var show: () -> Unit
        fun line(label: String, value: String, tag: String, onTap: () -> Unit): View = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(a, Color.WHITE, Ui.RULE, 12f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            minimumHeight = dp(48f)
            this.tag = tag
            isClickable = true
            setOnClickListener { onTap() }
            addView(Ui.text(a, label, 14f, Ui.MUTED), LinearLayout.LayoutParams(dp(118f), Ui.WRAP))
            addView(Ui.text(a, value.ifEmpty { "—" }, 16f, Ui.INK).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        fun pickStatus(col: String) {
            body.removeAllViews()
            head.removeAllViews()
            head.addView(Ui.text(a, "←", 22f, Ui.INK).apply { setPadding(0, 0, dp(14f), 0); tag = "card_back"; setOnClickListener { show() } })
            val t = IdTables.tableOf(b, cur)
            head.addView(Ui.text(a, (t?.colName(col) ?: TableCol.defaultName(col)) + " · " + rowTitle(t ?: BoardTable(0f, 0f, 1f, ""), cur), 18f, Ui.INK, bold = true),
                LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            body.addView(statusButtons(IdTables.groupOf(col), cur.get(col), "card_status") { sid -> setCell(cur, col, sid); show() })
        }
        show = {
            val t = IdTables.tableOf(b, cur)
            if (t == null || b.recs.none { it === cur }) d.dismiss()
            else {
                head.removeAllViews()
                head.addView(Ui.text(a, rowTitle(t, cur), 20f, Ui.INK, bold = true).apply { tag = "card_title" }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                head.addView(Ui.text(a, "✕", 20f, Ui.INK2).apply { setPadding(dp(12f), dp(4f), dp(8f), dp(4f)); setOnClickListener { d.dismiss() } })
                body.removeAllViews()
                body.addView(Ui.text(a, listOf(IdTables.fullTitle(b, t), cur.con).filter { it.isNotBlank() }.joinToString(" · "), 14f, Ui.INK2)
                    .apply { setPadding(0, 0, 0, dp(6f)) })
                val cols = t.allCols()
                // Сначала статусы — крупно, касание — список (2 касания на смену).
                for (k in listOf(TableCol.IGS, TableCol.CK, TableCol.DWG)) {
                    val row = LinearLayout(a).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = Ui.rounded(a, Color.WHITE, Ui.RULE, 14f)
                        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                        minimumHeight = dp(56f)
                        tag = "card_$k"
                        isClickable = true
                        setOnClickListener { pickStatus(k) }
                        addView(Ui.text(a, t.colName(k), 14f, Ui.MUTED), LinearLayout.LayoutParams(dp(110f), Ui.WRAP))
                        addView(statusChip(IdTables.groupOf(k), cur.get(k), big = true))
                    }
                    body.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
                }
                for (c in cols) {
                    if (c.isStatus || c.hidden && !c.isDate) continue
                    val view = if (c.isDate) line(c.title(), IdTables.dateLong(cur.get(c.k)), "card_${c.k}") { datePicker(cur, c.k) { show() } }
                    else line(c.title(), if (c.isVol) IdTables.cellText(b, cur, c.k) else cur.get(c.k), "card_${c.k}") { textDialog(t, cur, c.k) { show() } }
                    body.addView(view, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
                }
                val rows = IdTables.rowsOf(b, t)
                val i = rows.indexOf(cur)
                val nav = LinearLayout(a)
                fun btn(text: String, tag: String, enabled: Boolean, f: () -> Unit) = Ui.pill(a, text, if (enabled) Ui.INK else Ui.MUTED, Ui.DESK).apply {
                    gravity = Gravity.CENTER; this.tag = tag; isEnabled = enabled; setOnClickListener { f() }
                }
                nav.addView(btn("↑ Выше", "card_prev", i > 0) { cur = rows[i - 1]; v.markedRecs = setOf(cur); show() }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                nav.addView(btn("↓ Ниже", "card_next", i in 0 until rows.size - 1) { cur = rows[i + 1]; v.markedRecs = setOf(cur); show() },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = dp(8f) })
                body.addView(nav, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(14f) })
                val more = LinearLayout(a)
                more.addView(btn("Конструкция не выполнена", "card_notdone", true) { notDone(listOf(cur)); show() }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                more.addView(btn("+ строка ниже", "card_add", true) { d.dismiss(); addForm(t, next = true, after = cur) },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = dp(8f) })
                body.addView(more, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
                // История и ведомости схемы (шаг 2).
                val inV = Vedoms.itemsOf(b, cur)
                if (inV.isNotEmpty()) body.addView(Ui.text(a, "В ведомостях: " + inV.joinToString { (x, i) ->
                    x.num + if (!x.isDraft && Vedoms.hasAnswers(x.kind)) " — " + Vedoms.resName(x.kind, i.res).lowercase() else "" }, 14f, Ui.INK2)
                    .apply { setPadding(0, dp(10f), 0, 0); tag = "card_vedoms" })
                val more2 = LinearLayout(a)
                val rr = cur
                more2.addView(btn("История", "card_hist", true) { a.vedomUi.historyDialog(rr) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                more2.addView(btn("В ведомость", "card_tovedom", true) { d.dismiss(); a.vedomUi.toVedom(listOf(rr)) },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = dp(8f) })
                body.addView(more2, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
                v.markedRecs = setOf(cur)
                v.invalidate()
            }
        }
        show()
        d.setContentView(root)
        d.setOnDismissListener { if (card === d) card = null; v.markedRecs = null; v.invalidate() }
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(min(a.resources.displayMetrics.widthPixels, dp(640f)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.2f)
        }
        card = d
        d.show()
    }

    // ---------------------------------------------------------------- текст и дата

    /** Поле с подсказками из уже введённого в этом столбце (частые — кнопками). */
    fun textDialog(t: BoardTable, r: BoardRec, col: String, done: () -> Unit = {}) {
        if (col == TableCol.VOL) { volDialog(t, r, done); return }
        val name = t.colName(col)
        val edit = EditText(a).apply { setText(r.get(col)); setSelection(text.length); isSingleLine = true; tag = "cell_text" }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        val sugg = (if (col == TableCol.NSCH || col == TableCol.AOSR) IdTables.nextNumbers(b, t, col) else IdTables.suggestions(b, col)).filter { it != r.get(col) }
        if (sugg.isNotEmpty()) {
            val flow = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
            for ((i, s) in sugg.withIndex()) flow.addView(Ui.pill(a, s, Ui.INK, Ui.DESK).apply {
                tag = "cell_sugg_$i"
                setOnClickListener { edit.setText(s); edit.setSelection(s.length) }
            })
            box.addView(flow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10f) })
        }
        if (Input.stylusSeen(a)) box.addView(Ui.text(a, "Можно писать пером прямо в ячейке — инструмент «В текст».", 13f, Ui.MUTED)
            .apply { setPadding(0, dp(8f), 0, 0) })
        val d = AlertDialog.Builder(a)
            .setTitle(name + " · " + rowTitle(t, r))
            .setView(box)
            .setPositiveButton("Готово") { _, _ -> setCell(r, col, edit.text.toString().trim()); done() }
            .setNegativeButton("Отмена", null)
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        edit.requestFocus()
    }

    /** Число для объёма: поле с цифрами (запятая или точка), единица — по виду работ. */
    private fun numberField(value: String, tag0: String) = EditText(a).apply {
        setText(if (value.isEmpty()) "" else value.replace('.', ','))
        setSelection(text.length); isSingleLine = true; tag = tag0
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
        textSize = 22f
    }

    /** «Объём по схеме» у строки: число; пусто — не указан. */
    fun volDialog(t: BoardTable, r: BoardRec, done: () -> Unit = {}) {
        val unit = IdTables.unitFor(t, r.work)
        val edit = numberField(r.vol, "cell_text")
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(edit, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            if (unit.isNotEmpty()) addView(Ui.text(a, unit, 18f, Ui.MUTED).apply { setPadding(dp(8f), 0, 0, 0) })
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(row)
            addView(Ui.text(a, "Объём из исполнительной схемы. Итог и разница с проектом — внизу таблицы." +
                if (Input.stylusSeen(a)) " Можно писать пером прямо в ячейку." else "", 13f, Ui.MUTED).apply { setPadding(0, dp(8f), 0, 0) })
        }
        val d = AlertDialog.Builder(a)
            .setTitle(t.colName(TableCol.VOL) + " · " + rowTitle(t, r))
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val txt = edit.text.toString().trim()
                val vv = IdTables.parseVol(txt)
                if (txt.isNotEmpty() && vv.isEmpty()) { a.hint("Объём — числом, например 42,3", 1800); return@setPositiveButton }
                setCell(r, TableCol.VOL, vv); done()
            }
            .setNeutralButton("Без объёма") { _, _ -> setCell(r, TableCol.VOL, ""); done() }
            .setNegativeButton("Отмена", null)
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        edit.requestFocus()
    }

    /**
     * Проектный объём вида работ в таблице (касание строки «Объёмы» или «+ проект»): число и единица; work = null —
     * сначала выбрать вид работ.
     */
    fun projectDialog(t: BoardTable, work: String?) {
        if (work == null) {
            val have = IdTables.volumes(b, t).map { it.work }
            val works = (have + IdTables.allRows(b, t).map { it.work }.filter { it.isNotBlank() } + IdTables.WORKS)
                .distinctBy { IdTables.norm(it) }
            AlertDialog.Builder(a).setTitle("Проект — какой вид работ? · ${IdTables.fullTitle(b, t)}")
                .setItems(works.toTypedArray()) { _, i -> projectDialog(t, works[i]) }
                .setNegativeButton("Отмена", null).show()
            return
        }
        val key = IdTables.norm(work)
        val cur = t.plan[key]
        var unit = IdTables.unitFor(t, work)
        val edit = numberField(cur?.value.orEmpty(), "proj_value")
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        box.addView(edit)
        box.addView(Ui.text(a, "Единица", 13f, Ui.MUTED, bold = true).apply { setPadding(0, dp(10f), 0, dp(4f)) })
        val units = listOf("м³", "м²", "т", "шт", "м", "п.м.")
        val flow = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
        val chips = ArrayList<TextView>()
        fun paint() { for ((i, c) in chips.withIndex()) { val on = units[i] == unit; c.background = Ui.rounded(a, if (on) Ui.ACCENT else Ui.DESK, null, 999f); c.setTextColor(if (on) Color.WHITE else Ui.INK) } }
        for ((i, u) in units.withIndex()) chips += Ui.pill(a, u, Ui.INK, Ui.DESK).apply { tag = "proj_unit_$i"; setOnClickListener { unit = u; paint() } }.also { flow.addView(it) }
        paint()
        box.addView(flow)
        box.addView(Ui.text(a, "Факт — сумма столбца «${t.colName(TableCol.VOL)}» строк «$work». Разница и процент — внизу таблицы.", 13f, Ui.MUTED)
            .apply { setPadding(0, dp(10f), 0, 0) })
        val d = AlertDialog.Builder(a)
            .setTitle("Объём по проекту · $work · ${IdTables.fullTitle(b, t)}")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val vv = IdTables.parseVol(edit.text.toString())
                if (vv.isEmpty()) { a.hint("Проект — числом, например 310", 1800); return@setPositiveButton }
                a.pushUndo()
                t.plan[key] = VolPlan(cur?.work ?: work, vv, unit)
                a.changed()
                a.hint("Проект $work: ${IdTables.volText(vv)} $unit", 1500)
            }
            .also { if (cur != null) it.setNeutralButton("Убрать проект") { _, _ -> a.pushUndo(); t.plan.remove(key); a.changed() } }
            .setNegativeButton("Отмена", null)
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        edit.requestFocus()
    }

    fun datePicker(r: BoardRec, col: String, done: () -> Unit = {}) {
        val cur = r.get(col).ifEmpty { IdTables.today() }.split('-').mapNotNull { it.toIntOrNull() }
        val y = cur.getOrElse(0) { 2026 }; val m = cur.getOrElse(1) { 1 } - 1; val dd = cur.getOrElse(2) { 1 }
        val dlg = DatePickerDialog(a, { _, yy, mm, ddd -> setCell(r, col, IdTables.iso(yy, mm + 1, ddd)); done() }, y, m, dd)
        dlg.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Без даты") { _, _ -> setCell(r, col, ""); done() }
        dlg.show()
    }

    // ---------------------------------------------------------------- новая строка

    /**
     * «+ строка» / «+ следующая захватка»: захватка (следующая по номеру), конструкция, виды работ — можно несколько
     * (по строке на каждый), дата, исполнитель. Статусы новой строки — «Не выполнено».
     */
    fun addForm(t: BoardTable, next: Boolean, after: BoardRec? = null) {
        val rows = IdTables.allRows(b, t)
        val last = after ?: rows.lastOrNull()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(4f), dp(20f), 0) }
        fun label(s: String) = Ui.text(a, s, 13f, Ui.MUTED, bold = true).apply { setPadding(0, dp(10f), 0, dp(2f)) }
        val zah = EditText(a).apply {
            setText(if (next && last != null) IdTables.nextZah(last.zah) else ""); setSelection(text.length); isSingleLine = true
            hint = "например: 7 или Лоток №2"; tag = "add_zah"
        }
        val con = EditText(a).apply { setText(last?.con.orEmpty()); isSingleLine = true; hint = "например: Стены и колонны"; tag = "add_con" }
        val who = EditText(a).apply { setText(last?.who.orEmpty()); isSingleLine = true; hint = "подрядчик"; tag = "add_who" }
        val other = EditText(a).apply { isSingleLine = true; hint = "другой вид работ"; tag = "add_work_other" }
        val works = LinkedHashSet<String>()
        if (last != null) rows.filter { it.zah == last.zah && it.work.isNotEmpty() }.forEach { works += it.work }
        if (works.isEmpty()) { works += "Опалубка"; works += "Бетон" }
        var date = ""
        box.addView(label(t.colName(TableCol.ZAH))); box.addView(zah)
        box.addView(label(t.colName(TableCol.CON))); box.addView(con)
        box.addView(label(t.colName(TableCol.WORK) + " — можно несколько, по строке на каждый"))
        val flow = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
        val allWorks = (IdTables.WORKS + IdTables.suggestions(b, TableCol.WORK)).distinct()
        fun paint(p: TextView, on: Boolean) { p.background = Ui.rounded(a, if (on) Ui.ACCENT else Ui.DESK, null, 999f); p.setTextColor(if (on) Color.WHITE else Ui.INK) }
        for ((i, w) in allWorks.withIndex()) {
            val p = Ui.pill(a, w, Ui.INK, Ui.DESK).apply { tag = "add_work_$i" }
            paint(p, w in works)
            p.setOnClickListener { if (!works.remove(w)) works += w; paint(p, w in works) }
            flow.addView(p)
        }
        box.addView(flow)
        box.addView(other)
        box.addView(label(t.colName(TableCol.DATE)))
        val dateBtn = Ui.pill(a, "выбрать", Ui.INK, Ui.DESK).apply { tag = "add_date" }
        dateBtn.setOnClickListener {
            val c = java.util.Calendar.getInstance()
            DatePickerDialog(a, { _, yy, mm, dd -> date = IdTables.iso(yy, mm + 1, dd); dateBtn.text = IdTables.dateLong(date) },
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        box.addView(dateBtn, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        box.addView(label(t.colName(TableCol.WHO))); box.addView(who)
        val whoSugg = IdTables.suggestions(b, TableCol.WHO, 6)
        if (whoSugg.isNotEmpty()) box.addView(Ui.FlowRow(a).apply {
            hGap = dp(6f); vGap = dp(6f); setPadding(0, dp(6f), 0, 0)
            for (s in whoSugg) addView(Ui.chip(a, s, Ui.INK, Ui.DESK).apply { setOnClickListener { who.setText(s) } })
        })
        fun create(): Int {
            val z = zah.text.toString().trim()
            if (z.isEmpty()) { a.hint("Впишите номер захватки или участка"); return -1 }
            val list = ArrayList<String>(works.filter { it in allWorks })
            other.text.toString().trim().takeIf { it.isNotEmpty() }?.let { if (it !in list) list += it }
            if (list.isEmpty()) list += ""
            a.pushUndo()
            var o = after?.let { IdTables.orderAfter(b, it) } ?: IdTables.endOrder(b, t)
            val step = after?.let { (IdTables.orderAfter(b, it) - it.o) / (list.size + 1) }
            if (after != null && step != null) o = after.o + step
            var n = 0
            for (w in list) {
                if (b.recs.any { it.t == t.id && it.key() == IdTables.key(z, con.text.toString(), w) }) continue
                b.recs += BoardRec(t.id, o).apply {
                    this.zah = z; this.con = con.text.toString().trim(); this.work = w; this.date = date; this.who = who.text.toString().trim()
                }
                o += step ?: 1.0
                n++
            }
            a.changed()
            a.hint(if (n == list.size) "Добавлено строк: $n" else "Добавлено: $n (такие строки уже были)", 1600)
            return n
        }
        val d = AlertDialog.Builder(a)
            .setTitle((if (next) "Следующая захватка" else "Новая строка") + " · " + IdTables.fullTitle(b, t).ifEmpty { "таблица" })
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Добавить") { _, _ -> create() }
            .setNeutralButton("Добавить и следующую", null)
            .setNegativeButton("Отмена", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (create() >= 0) { d.dismiss(); addForm(t, next = true) }
            }
        }
        d.show()
    }

    // ---------------------------------------------------------------- столбцы и отбор

    /** Столбцы таблицы: показать/скрыть, переименовать («Захватка» → «Участок»), свой столбец, «как у таблицы…». */
    fun columnsDialog(t: BoardTable) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(8f), dp(16f), 0) }
        val dialog = AlertDialog.Builder(a).setTitle("Столбцы · " + IdTables.fullTitle(b, t)).setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Готово", null).create()
        fun ensureOwn() { if (t.cols.isEmpty()) t.cols.addAll(TableCol.defaults()) }
        lateinit var render: () -> Unit
        render = {
            box.removeAllViews()
            for ((i, c) in t.allCols().withIndex()) {
                val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; tag = "col_row_$i" }
                val cb = CheckBox(a).apply { isChecked = !c.hidden; tag = "col_show_$i" }
                cb.setOnCheckedChangeListener { _, on -> a.pushUndo(); ensureOwn(); t.cols[i].hidden = !on; a.changed() }
                row.addView(cb)
                row.addView(Ui.text(a, c.title() + if (c.name.isNotEmpty() && !c.custom) "  (${TableCol.defaultName(c.k)})" else "", 16f, Ui.INK),
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                fun small(s: String, tag: String, f: () -> Unit) = Ui.text(a, s, 18f, Ui.INK2).apply {
                    setPadding(dp(10f), dp(6f), dp(10f), dp(6f)); this.tag = tag; setOnClickListener { f() }
                }
                row.addView(small("✎", "col_rename_$i") {
                    val e = EditText(a).apply { setText(c.title()); setSelection(text.length); isSingleLine = true }
                    AlertDialog.Builder(a).setTitle("Название столбца").setView(LinearLayout(a).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(e, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)) })
                        .setPositiveButton("Готово") { _, _ ->
                            val n = e.text.toString().trim()
                            a.pushUndo(); ensureOwn()
                            t.cols[i].name = if (n == TableCol.defaultName(c.k) && !c.custom) "" else n
                            a.changed(); render()
                        }.setNegativeButton("Отмена", null).show()
                })
                row.addView(small("▲", "col_up_$i") { if (i > 0) { a.pushUndo(); ensureOwn(); val x = t.cols[i]; t.cols[i] = t.cols[i - 1]; t.cols[i - 1] = x; a.changed(); render() } })
                if (c.custom) row.addView(small("✕", "col_del_$i") {
                    a.pushUndo(); ensureOwn(); t.cols.removeAt(i)
                    for (r in b.recs) if (r.t == t.id) r.extra.remove(c.k)
                    a.changed(); render()
                })
                box.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            }
            box.addView(Ui.pill(a, "+ Свой столбец", Color.WHITE, Ui.ACCENT).apply {
                tag = "col_add"
                setOnClickListener {
                    val e = EditText(a).apply { isSingleLine = true; hint = "например: Секция, Этаж, Оси" }
                    AlertDialog.Builder(a).setTitle("Новый столбец").setView(LinearLayout(a).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(e, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)) })
                        .setPositiveButton("Добавить") { _, _ ->
                            val n = e.text.toString().trim()
                            if (n.isNotEmpty()) { a.pushUndo(); ensureOwn(); t.cols.add(2.coerceAtMost(t.cols.size), TableCol(TableCol.newCustomKey(), n)); a.changed(); render() }
                        }.setNegativeButton("Отмена", null).show()
                }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(10f) })
            val others = b.tables.filter { it !== t }
            if (others.isNotEmpty()) box.addView(Ui.pill(a, "Как у таблицы…", Ui.INK, Ui.DESK).apply {
                tag = "col_copy"
                setOnClickListener {
                    AlertDialog.Builder(a).setTitle("Столбцы как у таблицы").setItems(others.map { IdTables.fullTitle(b, it).ifEmpty { "без шифра" } }.toTypedArray()) { _, k ->
                        a.pushUndo(); t.cols.clear(); t.cols.addAll(others[k].allCols().map { it.copy() }); a.changed(); render()
                    }.show()
                }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(8f); bottomMargin = dp(8f) })
        }
        render()
        dialog.show()
    }

    /** Отбор строк таблицы (на этом планшете): статусы ИГС, исполнители, текст. Остальные строки скрыты до «Показать всё». */
    fun filterDialog(t: BoardTable) {
        val f = t.filter
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(8f), dp(16f), 0) }
        box.addView(Ui.text(a, "ИГС", 14f, Ui.MUTED, bold = true))
        val igsBoxes = b.statuses(BoardStatus.ID).mapIndexed { i, st ->
            CheckBox(a).apply { text = st.name; isChecked = f?.igs?.contains(st.id) == true; tag = "flt_igs_$i" }.also { box.addView(it) } to st.id
        }
        val whos = IdTables.allRows(b, t).map { it.who.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        val whoBoxes = ArrayList<Pair<CheckBox, String>>()
        if (whos.isNotEmpty()) {
            box.addView(Ui.text(a, t.colName(TableCol.WHO), 14f, Ui.MUTED, bold = true).apply { setPadding(0, dp(10f), 0, 0) })
            for (w in whos) whoBoxes += CheckBox(a).apply { text = w; isChecked = f?.who?.contains(w) == true }.also { box.addView(it) } to w
        }
        val text = EditText(a).apply { setText(f?.text.orEmpty()); isSingleLine = true; hint = "текст: захватка, конструкция…"; tag = "flt_text" }
        box.addView(text)
        AlertDialog.Builder(a).setTitle("Отбор · " + IdTables.fullTitle(b, t)).setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Показать") { _, _ ->
                val nf = RowFilter(igsBoxes.filter { it.first.isChecked }.map { it.second }.toSet(),
                    whoBoxes.filter { it.first.isChecked }.map { it.second }.toSet(), text.text.toString().trim())
                t.filter = if (nf.isEmpty) null else nf
                v.invalidate(); a.buildSelectionBar()
            }
            .setNeutralButton("Показать всё") { _, _ -> t.filter = null; v.invalidate(); a.buildSelectionBar() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun renameDialog(t: BoardTable) {
        val e = EditText(a).apply { setText(t.title); setSelection(text.length); isSingleLine = true; tag = "table_title" }
        AlertDialog.Builder(a).setTitle(if (b.sectionOf(t) != null) "Название таблицы (подраздел)" else "Шифр таблицы").setView(LinearLayout(a).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(e, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)) })
            .setPositiveButton("Готово") { _, _ -> a.pushUndo(); t.title = e.text.toString().trim(); a.changed(); a.buildSelectionBar() }
            .setNegativeButton("Отмена", null).show()
    }

    // ---------------------------------------------------------------- выделенное

    fun selectionLabel(sel: List<Any>): String? = when {
        sel.size == 1 && sel[0] is BoardTable -> "Таблица ИД «${IdTables.fullTitle(b, sel[0] as BoardTable)}»"
        sel.isNotEmpty() && sel.all { it is BoardRec } -> "Строк: ${sel.size}"
        else -> null
    }

    fun selectionButtons(sel: List<Any>): List<TextView> {
        val out = ArrayList<TextView>()
        fun pill(text: String, tag: String, f: () -> Unit) = Ui.pill(a, text, Ui.INK, Ui.DESK).apply { this.tag = tag; setOnClickListener { f() } }
        if (sel.size == 1 && sel[0] is BoardTable) {
            val t = sel[0] as BoardTable
            out += pill(if (b.sectionOf(t) != null) "Название" else "Шифр", "sel_edit") { renameDialog(t) }
            if (t.visibleCols().any { it.isVol }) out += pill("Проект", "sel_proj") { projectDialog(t, null) }
            out += pill("+ строка", "sel_add") { addForm(t, next = true) }
            out += pill("Столбцы", "sel_cols") { columnsDialog(t) }
            out += if (t.filter == null) pill("Отбор", "sel_filter") { filterDialog(t) }
            else pill("Показать всё", "sel_filter") { t.filter = null; v.invalidate(); a.buildSelectionBar() }
            out += pill("Сводка", "sel_summary") { summaryDialog(only = t) }
        }
        if (sel.isNotEmpty() && sel.all { it is BoardRec }) {
            val rows = sel.map { it as BoardRec }
            for ((k, name) in listOf(TableCol.IGS to "ИГС", TableCol.CK to "Чек-лист", TableCol.DWG to "DWG")) {
                out += pill(name, "sel_$k") {
                    var dlg: AlertDialog? = null
                    val list = statusButtons(IdTables.groupOf(k), null, "bulk_status") { sid ->
                        dlg?.dismiss()
                        a.pushUndo(); for (r in rows) put(r, k, sid); a.changed()
                        a.hint("$name «${IdTables.status(b, IdTables.groupOf(k), sid).name}» — у ${rows.size} строк", 1600)
                    }
                    dlg = AlertDialog.Builder(a).setTitle("$name для ${rows.size} строк")
                        .setView(ScrollView(a).apply { addView(LinearLayout(a).apply { setPadding(dp(16f), 0, dp(16f), dp(8f)); addView(list) }) })
                        .setNegativeButton("Отмена", null).show()
                }
            }
            out += pill("Не выполнено", "sel_notdone") { notDone(rows) }
        }
        if (sel.isNotEmpty() && sel.all { it is BoardRow }) out += pill("В таблицу", "sel_totable") { rowsToTable(sel.map { it as BoardRow }) }
        return out
    }

    /** Удалить выделенные таблицы (со строками) и строки — после подтверждения. */
    fun confirmDelete(sel: List<Any>, then: () -> Unit) {
        val tables = sel.filterIsInstance<BoardTable>()
        val recs = sel.filterIsInstance<BoardRec>()
        if (tables.isEmpty() && recs.size <= 1) { then(); return }
        val n = tables.sumOf { t -> b.recs.count { it.t == t.id } }
        val msg = if (tables.isNotEmpty()) "Удалить таблицу ${tables.joinToString { "«${IdTables.fullTitle(b, it)}»" }} и ${Ui.plural(n, "строку", "строки", "строк")}?"
        else "Удалить ${Ui.plural(recs.size, "строку", "строки", "строк")}?"
        AlertDialog.Builder(a).setMessage("$msg «Отменить» вернёт.").setPositiveButton("Удалить") { _, _ -> then() }.setNegativeButton("Отмена", null).show()
    }

    /** Убрать из доски таблицы и строки из выделенного (вызывается из удаления выделенного). */
    fun removeSelected(sel: List<Any>) {
        val tables = sel.filterIsInstance<BoardTable>()
        val ids = tables.map { it.id }.toSet()
        b.tables.removeAll { t -> tables.any { it === t } }
        b.recs.removeAll { r -> r.t in ids || sel.any { it === r } }
    }

    // ---------------------------------------------------------------- старые строки ИД

    /** «В таблицу»: строки ИД 0.18 («Захватка 1 — Опалубка», галочки, ИГС) → строки таблицы (новой или выбранной). */
    fun rowsToTable(rows: List<BoardRow>) {
        val sorted = rows.sortedWith(compareBy<BoardRow>({ it.y }, { it.x }))
        fun convert(t: BoardTable) {
            a.pushUndo()
            if (b.tables.none { it === t }) b.tables += t
            var o = IdTables.endOrder(b, t)
            for (r in sorted) {
                val parts = r.title.split(Regex("\\s+[—–-]\\s+"), 2)
                b.recs += BoardRec(t.id, o).apply {
                    zah = parts[0].replace(Regex("^захватка\\s*", RegexOption.IGNORE_CASE), "").trim()
                    work = parts.getOrElse(1) { "" }.trim()
                    ck = if (r.check) IdTables.CK_DONE else "k1"
                    dwg = if (r.dwg) IdTables.DWG_DONE else "d1"
                    igs = if (b.statuses(BoardStatus.ID).any { it.id == r.sid }) r.sid else IdTables.IGS_NONE
                }
                o += 1.0
            }
            b.rows.removeAll { x -> rows.any { it === x } }
            v.clearSelection()
            a.changed()
            a.hint("Перенесено в таблицу «${IdTables.fullTitle(b, t)}»: ${Ui.plural(rows.size, "строка", "строки", "строк")}", 2000)
        }
        val first = sorted.first()
        val names = b.tables.map { "В «${IdTables.fullTitle(b, it).ifEmpty { "без шифра" }}»" } + "Новая таблица"
        AlertDialog.Builder(a).setTitle("Строки ИД → таблица")
            .setItems(names.toTypedArray()) { _, i ->
                if (i < b.tables.size) { convert(b.tables[i]); return@setItems }
                val sec = b.sectionOf(first)?.title.orEmpty()
                val e = EditText(a).apply { setText(sec); setSelection(text.length); isSingleLine = true; hint = "Шифр, например КЖ 6.1.1" }
                AlertDialog.Builder(a).setTitle("Шифр новой таблицы").setView(LinearLayout(a).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(e, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)) })
                    .setPositiveButton("Перенести") { _, _ ->
                        convert(BoardTable(sorted.minOf { it.x }, first.y, IdTables.defaultScale(b, a.resources.displayMetrics.density), e.text.toString().trim()))
                    }.setNegativeButton("Отмена", null).show()
            }.show()
    }

    // ---------------------------------------------------------------- сводка и контроль

    /** Сводка ИГС (ТЗ, раздел 9): где считать — вся доска, раздел или таблица; цифры, по шифрам, контроль, выгрузка. */
    fun summaryDialog(only: BoardTable? = null, fr: BoardFrame? = null) {
        val s = IgsSummary(b, fr, only, longDays)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(6f), dp(18f), dp(8f)) }
        var dialog: AlertDialog? = null
        fun reopen(o: BoardTable?, f: BoardFrame?) { dialog?.dismiss(); summaryDialog(o, f) }
        // Где считать.
        val where = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
        fun chip(text: String, on: Boolean, tag: String, f: () -> Unit) = Ui.pill(a, text, if (on) Color.WHITE else Ui.INK, if (on) Ui.ACCENT else Ui.DESK)
            .apply { this.tag = tag; setOnClickListener { f() } }
        where.addView(chip("Вся доска", only == null && fr == null, "sum_all") { reopen(null, null) })
        for ((i, f) in b.frames.filter { f -> b.tables.any { b.sectionOf(it) === f } }.withIndex()) where.addView(chip(f.title, fr === f, "sum_sec_$i") { reopen(null, f) })
        for ((i, t) in b.tables.withIndex()) where.addView(chip(IdTables.fullTitle(b, t).ifEmpty { "без шифра" }, only === t, "sum_tbl_$i") { reopen(t, null) })
        box.addView(where)
        // Главные цифры.
        val grid = android.widget.GridLayout(a).apply { columnCount = 2; tag = "sum_kpi" }
        fun kpi(n: Int, label: String, color: Int) {
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(a, Color.WHITE, Ui.RULE, 12f)
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                addView(Ui.text(a, n.toString(), 26f, color, bold = true))
                addView(Ui.text(a, label, 13f, Ui.INK2))
            }
            grid.addView(cell, android.widget.GridLayout.LayoutParams().apply {
                width = 0; columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f); setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
            })
        }
        kpi(s.total, "схем всего", Ui.INK)
        kpi(s.doneCount, "сдано в ПТО", Ui.OK)
        kpi(s.signing, "на подписи (АН и заказчик)", Color.rgb(110, 83, 0))
        kpi(s.ctrlLong.size, "на подписи дольше ${s.longDays} дн.", Color.rgb(154, 69, 5))
        box.addView(grid, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
        fun head(text: String) = box.addView(Ui.text(a, text, 14f, Ui.MUTED, bold = true).apply { setPadding(0, dp(14f), 0, dp(4f)) })
        // По шифрам — полоса по статусам ИГС.
        if (s.tables.isNotEmpty()) {
            head("По шифрам")
            for ((i, p) in s.tables.withIndex()) {
                val (t, rows) = p
                val row = LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4f), 0, dp(4f)); tag = "sum_row_$i"; isClickable = true
                    setOnClickListener { dialog?.dismiss(); val r = v.tableDraw.bounds(b, t, RectF()); v.fitRect(r.left, r.top, r.right, r.bottom) }
                }
                row.addView(Ui.text(a, IdTables.fullTitle(b, t).ifEmpty { "без шифра" }, 14f, Ui.INK, bold = true), LinearLayout.LayoutParams(dp(150f), Ui.WRAP))
                val bar = LinearLayout(a)
                for (st in s.igs.reversed()) {
                    val n = s.count(rows, st)
                    if (n > 0) bar.addView(View(a).apply { setBackgroundColor(st.color) }, LinearLayout.LayoutParams(0, dp(14f), n.toFloat()))
                }
                if (rows.isEmpty()) bar.addView(View(a).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(0, dp(14f), 1f))
                row.addView(bar, LinearLayout.LayoutParams(0, dp(14f), 1f))
                row.addView(Ui.text(a, "сдано ${rows.count { s.done(it) }} из ${rows.size}", 13f, Ui.INK2).apply { setPadding(dp(8f), 0, 0, 0) })
                box.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            }
        }
        // Статусы ИГС, Чек-лист, DWG — счётчики.
        fun counts(title: String, list: List<BoardStatus>, k: String) {
            head(title)
            val flow = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
            for (st in list) flow.addView(statusChip(st.group, st.id).apply { text = "${st.name} · ${s.count(s.recs, st, k)}" })
            box.addView(flow)
        }
        counts("ИГС", s.igs, TableCol.IGS)
        counts("Чек-лист", s.ck, TableCol.CK)
        counts("DWG", s.dwg, TableCol.DWG)
        // Контроль.
        head("Контроль — касание покажет строки")
        fun ctrl(list: List<BoardRec>, text: String, tag: String) {
            val row = LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(a, Color.WHITE, Ui.RULE, 12f)
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                this.tag = tag; isClickable = true
                setOnClickListener { dialog?.dismiss(); showRows(list) }
                addView(Ui.text(a, list.size.toString(), 22f, if (list.isEmpty()) Ui.MUTED else Ui.BAD, bold = true), LinearLayout.LayoutParams(dp(44f), Ui.WRAP))
                addView(Ui.text(a, text, 14f, Ui.INK), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            }
            box.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        }
        ctrl(s.ctrlNotGiven, "чек-лист предъявлен, а схема «Не предоставлена»", "sum_ctrl_1")
        ctrl(s.ctrlNoChecklist, "схема в работе и дальше, а чек-лист не «Предъявлено»", "sum_ctrl_2")
        ctrl(s.ctrlLong, "на подписи (АН или заказчик) дольше ${s.longDays} дн.", "sum_ctrl_3")
        val days = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f), 0, 0) }
        days.addView(Ui.text(a, "Срок «долго на подписи»: ${s.longDays} дн.", 14f, Ui.INK2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        days.addView(Ui.pill(a, "−", Ui.INK, Ui.DESK).apply { tag = "sum_days_minus"; setOnClickListener { longDays = s.longDays - 1; reopen(only, fr) } })
        days.addView(Ui.pill(a, "+", Ui.INK, Ui.DESK).apply { tag = "sum_days_plus"; setOnClickListener { longDays = s.longDays + 1; reopen(only, fr) } },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(6f) })
        box.addView(days)
        // Открытые ведомости (шаг 2): сколько схем ждут ответа и сколько дней.
        if (s.openVedoms.isNotEmpty()) {
            head("Открытые ведомости — ждут ответа")
            for ((i, p) in s.openVedoms.withIndex()) {
                val (x, n, days0) = p
                val row = LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = Ui.rounded(a, Color.WHITE, Ui.RULE, 12f)
                    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                    tag = "sum_vedom_$i"; isClickable = true
                    setOnClickListener { dialog?.dismiss(); a.vedomUi.goTo(x) }
                    addView(Ui.text(a, "${x.num} · ${Vedoms.kindName(x.kind)}", 14f, Board.statusFg(Vedoms.kindColor(x.kind)), bold = true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                    addView(Ui.text(a, "ждут ${Ui.plural(n, "схема", "схемы", "схем")} · $days0 дн.", 14f, if (days0 > s.longDays) Ui.BAD else Ui.INK2))
                }
                box.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
            }
        }
        // Объёмы (0.21): по таблицам-подразделам и всего по шифру (разделу).
        val volT = s.tables.map { (t, rows) -> t to IdTables.volumes(b, t, rows) }.filter { it.second.isNotEmpty() }
        if (volT.isNotEmpty()) {
            head("Объёмы: факт по схемам / проект")
            fun valText(f: Double, p: Double?, u: String, partial: Boolean = false): Pair<String, Int> = when {
                partial -> "${IdTables.volFmt(f)} $u · проект вписан не у всех таблиц".replace("  ", " ") to Ui.INK2
                p == null -> "${IdTables.volFmt(f)} $u · проект не вписан".replace("  ", " ") to Ui.INK2
                f > p + 1e-9 -> "${IdTables.volFmt(f)} из ${IdTables.volFmt(p)} $u · перебор +${IdTables.volFmt(f - p)} $u" to Ui.BAD
                else -> "${IdTables.volFmt(f)} из ${IdTables.volFmt(p)} $u · ${Math.round(if (p > 0) f / p * 100 else 0.0)} %" to Color.rgb(31, 79, 160)
            }
            fun vline(label: String, f: Double, p: Double?, u: String, tag: String, bold: Boolean = false, partial: Boolean = false) {
                val (txt, col) = valText(f, p, u, partial)
                val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3f), 0, dp(3f)); this.tag = tag }
                row.addView(Ui.text(a, label, 14f, Ui.INK, bold = bold), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                row.addView(Ui.text(a, txt, 14f, col, bold = true))
                box.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            }
            var n = 0
            for ((sec, list) in volT.groupBy { b.sectionOf(it.first) }) {
                box.addView(Ui.text(a, sec?.title ?: "Вне разделов", 14f, Ui.INK2, bold = true).apply { setPadding(0, dp(8f), 0, dp(2f)) })
                for ((t, lines) in list) for (vl in lines) vline("${t.title.ifEmpty { "таблица" }} · ${vl.work.lowercase()}", vl.fact, vl.plan, vl.unit, "sum_vol_${n++}")
                if (sec != null) {
                    // Всего по шифру: факт — все таблицы раздела, проект — сумма вписанных.
                    val works = LinkedHashMap<String, Triple<String, String, MutableList<IdTables.VolLine>>>()
                    for ((_, lines) in list) for (vl in lines) works.getOrPut(IdTables.norm(vl.work)) { Triple(vl.work, vl.unit, ArrayList()) }.third += vl
                    for ((_, w) in works) {
                        // Проект по шифру — только если вписан у всех таблиц с этим видом работ (иначе процент обманет).
                        val plans = w.third.mapNotNull { it.plan }
                        val partial = plans.isNotEmpty() && plans.size < w.third.size
                        vline("Всего по шифру ${sec.title} · ${w.first.lowercase()}", w.third.sumOf { it.fact },
                            if (plans.isEmpty() || partial) null else plans.sum(), w.second, "sum_vol_total_${n++}", bold = true, partial = partial)
                    }
                }
            }
        }
        if (s.whos.isNotEmpty()) {
            head("По исполнителям")
            for (w in s.whos) {
                val rows = s.recs.filter { it.who.trim() == w }
                box.addView(Ui.text(a, "${s.whoName(w)} — ${Ui.plural(rows.size, "схема", "схемы", "схем")}, сдано ${rows.count { s.done(it) }}", 14f, Ui.INK2))
            }
        }
        dialog = AlertDialog.Builder(a)
            .setTitle(when { only != null -> "Сводка · ${only.title}"; fr != null -> "Сводка · ${fr.title}"; else -> "Сводка ИГС" })
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Выгрузить в Excel…") { _, _ -> exportDialog(only) }
            .setNeutralButton("Скопировать для Excel") { _, _ ->
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Сводка ИГС", s.tsv()))
                a.hint("Скопировано — вставьте в Excel", 1800)
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Показать строки на доске: подсветить и переехать к первой (касание пустого места снимет подсветку). */
    fun showRows(list: List<BoardRec>) {
        v.markedRecs = list.toSet()
        val first = list.firstOrNull()
        val t = first?.let { IdTables.tableOf(b, it) }
        if (first != null && t != null) {
            val r = v.tableDraw.rowRect(b, t, first, RectF())
            if (r != null) {
                val tb = v.tableDraw.bounds(b, t, RectF())
                v.fitRect(tb.left, r.top - r.height() * 3, tb.right, r.bottom + r.height() * 6)
            }
        }
        v.invalidate()
        a.hint(if (list.isEmpty()) "Таких строк нет" else "Подсвечено строк: ${list.size} — касание пустого места снимет", 2500)
    }

    // ---------------------------------------------------------------- Excel

    /** Выгрузка в Excel с отбором: шифры, статусы ИГС, исполнители; листы — сводка, все схемы, по листу на шифр. */
    fun exportDialog(only: BoardTable? = null) {
        val all = IdExcel.allFilter(b)
        val tSel = LinkedHashSet(if (only != null) setOf(only.id) else all.tables)
        val iSel = LinkedHashSet(all.igs)
        val wSel = LinkedHashSet(all.who)
        val sheets = linkedSetOf(IdExcel.SHEET_SUMMARY, IdExcel.SHEET_ALL, IdExcel.SHEET_PER)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(6f), dp(18f), dp(8f)) }
        val count = Ui.text(a, "", 15f, Ui.INK, bold = true).apply { tag = "exp_count"; setPadding(0, dp(12f), 0, 0) }
        fun upd() { count.text = "Будет выгружено: " + Ui.plural(IdExcel.rows(b, IdExcel.Filter(tSel, iSel, wSel)).size, "схема", "схемы", "схем") }
        fun group(title: String, items: List<Pair<String, String>>, set: MutableSet<String>, tag: String) {
            box.addView(Ui.text(a, title, 14f, Ui.MUTED, bold = true).apply { setPadding(0, dp(12f), 0, dp(4f)) })
            val flow = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
            for ((i, p) in items.withIndex()) {
                val (id, name) = p
                val chip = Ui.pill(a, name, Ui.INK, Ui.DESK).apply { this.tag = "${tag}_$i" }
                fun paint() { val on = id in set; chip.background = Ui.rounded(a, if (on) Ui.ACCENT else Ui.DESK, null, 999f); chip.setTextColor(if (on) Color.WHITE else Ui.INK) }
                paint()
                chip.setOnClickListener { if (!set.remove(id)) set += id; paint(); upd() }
                flow.addView(chip)
            }
            box.addView(flow)
        }
        group("Таблицы", b.tables.map { it.id to IdTables.fullTitle(b, it).ifEmpty { "без шифра" } }, tSel, "exp_t")
        group("ИГС", b.statuses(BoardStatus.ID).map { it.id to it.name }, iSel, "exp_igs")
        group("Исполнители", all.who.sorted().map { it to it.ifEmpty { "не указан" } }, wSel, "exp_who")
        group("Листы в файле", listOf(IdExcel.SHEET_SUMMARY to "Сводка", IdExcel.SHEET_ALL to "Все схемы", IdExcel.SHEET_PER to "По листу на шифр"), sheets, "exp_sheet")
        box.addView(count)
        upd()
        AlertDialog.Builder(a).setTitle("Выгрузка в Excel")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Выгрузить") { _, _ ->
                val f = exportFile(IdExcel.Filter(tSel, iSel, wSel), sheets)
                if (f != null) send(f)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Файл .xlsx в cache/shares (его же отдаём через FileProvider). */
    internal fun exportFile(f: IdExcel.Filter, sheets: Set<String>): File? = runCatching {
        val dir = File(a.cacheDir, "shares").apply { mkdirs() }
        val safe = b.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Доска" }.take(40)
        val file = File(dir, "ИГС $safe ${IdTables.dateLong(IdTables.today())}.xlsx")
        file.outputStream().buffered().use { IdExcel.write(b, f, sheets, it) }
        file
    }.onFailure { a.hint("Не получилось сохранить файл: ${it.message}", 3000) }.getOrNull()

    private fun send(file: File) {
        val uri = FileProvider.getUriForFile(a, a.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = IdExcel.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        a.startActivity(Intent.createChooser(send, "Отправить таблицу Excel"))
    }
}
