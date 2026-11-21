package party.shulya.freewrite

import android.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * «Редактор таблиц» (0.30.1, Владимир: «чтобы не просить каждый раз новую таблицу под конструкцию — заходил бы в редактор,
 * составлял свою и сохранял; при создании таблицы выбирал бы её»). Окно шаблона: название, раздел проводника, «на основе»,
 * столбцы (показать / скрыть, переименовать, порядок; свои — текст, число, дата, отметка ✓, список с цветами), виды работ,
 * части объёма. Сохранили — шаблон в проводнике «Таблица ИД».
 *
 * Где хранится (0.32.2, Владимир): новый вид при сохранении — выбор «Сохранить как шаблон» (общий список команды на сервере,
 * есть в выборе таблиц на всех досках) или «Только для этой доски» (хранится в доске, список шаблонов не засоряется).
 * Шаблон правится и сохраняется как шаблон; вид доски при сохранении можно сделать шаблоном.
 */
class TableEditor(private val a: BoardActivity) {
    private fun dp(x: Float) = Ui.dp(a, x)
    private val b: Board get() = a.board

    /** Открытое окно (для проверок). */
    internal var dialog: AlertDialog? = null
    internal var colDialog: AlertDialog? = null
    internal var scopeDialog: AlertDialog? = null

    /**
     * Открыть шаблон [tpl] на правку. [fresh] — новый (есть «На основе»). [onSaved] — после «Сохранить» (шаблон уже в
     * библиотеке; таблицы этого вида на доске обновлены, если отметили).
     */
    fun open(tpl: TableTemplate, fresh: Boolean, onSaved: (TableTemplate) -> Unit = {}) {
        dialog?.dismiss()
        last = this
        val t = tpl.copy()
        val library = TableTemplates(a)
        val inLibrary = library.get(t.id) != null
        val boardOnly = !inLibrary && b.kinds.any { it.tpl.id == t.id }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(16f), dp(20f), dp(8f)); tag = "tpl_editor" }
        box.addView(Ui.text(a, if (fresh) "Своя таблица" else "Таблица «${t.name}»", 20f, Ui.INK, bold = true))
        box.addView(Ui.text(a, "Столбцы, виды работ и объём — как нужно для этой конструкции. Сохраните — таблица появится в «Таблица ИД».",
            13f, Ui.MUTED).apply { setPadding(0, dp(4f), 0, dp(6f)) })
        if (inLibrary || boardOnly) box.addView(Ui.chip(a, if (inLibrary) "Шаблон — в выборе таблиц на всех досках, у всей команды"
            else "Только для этой доски", Ui.ACCENT, Ui.ACCENT_SOFT).apply { tag = "tpl_where" })
        fun label(s: String) = box.addView(Ui.text(a, s, 13f, Ui.MUTED, bold = true).apply { setPadding(0, dp(14f), 0, dp(4f)) })
        fun field(value: String, hint0: String, tag0: String) = EditText(a).apply {
            setText(value); setSelection(text.length); isSingleLine = true; hint = hint0; tag = tag0; textSize = 16f
        }
        label("Название")
        val name = field(t.name, "например: Наружные сети К1", "tpl_name")
        box.addView(name)
        label("Раздел в проводнике")
        val section = field(t.section, "например: Сети", "tpl_section")
        box.addView(section)
        val secs = LinkedHashSet<String>()
        TableKinds.SECTIONS.forEach { secs += it.code }
        library.list().forEach { if (it.section.isNotBlank()) secs += it.section.trim() }
        secs += listOf("Сети", "Земляные работы")
        val secRow = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f); setPadding(0, dp(6f), 0, 0) }
        for ((i, sName) in secs.withIndex()) secRow.addView(Ui.pill(a, sName, Ui.INK, Ui.DESK).apply {
            tag = "tpl_sec_$i"; setOnClickListener { section.setText(sName); section.setSelection(sName.length) }
        })
        box.addView(secRow)

        val colsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val worksBox = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
        val partsBox = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
        lateinit var renderAll: () -> Unit

        if (fresh) {
            label("На основе")
            val base = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
            val bases = ArrayList<Pair<String, () -> TableTemplate>>()
            for (k in TableKinds.ALL) bases += k.name to { TableTemplate.fromKind(k, t.id) }
            for (o in library.list() + b.kinds.map { it.tpl }) if (o.id != t.id) bases += o.name to { o.copy(t.id) }
            bases += "Пустая" to { TableTemplate(t.id, "", "", TableTemplate.defaultCols(setOf(TableCol.CON, TableCol.UCH)), ArrayList(), ArrayList()) }
            for ((i, p) in bases.withIndex()) base.addView(Ui.pill(a, p.first, Ui.INK, Ui.DESK).apply {
                tag = "tpl_base_$i"
                setOnClickListener {
                    val src = p.second()
                    t.cols.clear(); t.cols.addAll(src.cols)
                    t.works.clear(); t.works.addAll(src.works)
                    t.parts.clear(); t.parts.addAll(src.parts.map { TableKinds.Part(it.key, it.name) })
                    if (name.text.isBlank() && src.name.isNotBlank()) name.setText(src.name)
                    if (section.text.isBlank() && src.section.isNotBlank()) section.setText(src.section)
                    renderAll()
                }
            })
            box.addView(base)
        }

        label("Столбцы — галочка: показывать; название можно поменять (например, «Участок» → «Месяц»)")
        box.addView(colsBox)
        box.addView(Ui.pill(a, "+ Свой столбец", Color.WHITE, Ui.ACCENT).apply {
            tag = "tpl_col_add"; setOnClickListener { colForm(null) { c -> t.cols.add(insertAt(t), c); renderAll() } }
        }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(8f) })

        label("Виды работ — на выбор в строке; по каждому свой объём и проект")
        box.addView(worksBox)
        val workNew = field("", "например: Монтаж трубопровода", "tpl_work_new")
        box.addView(addRow(workNew, "tpl_work_add") { s -> if (t.works.none { IdTables.norm(it) == IdTables.norm(s) }) t.works += s; renderAll() })

        label("Объём — одной цифрой или по частям (например: Стены · Колонны)")
        box.addView(partsBox)
        val partNew = field("", "например: Колодцы", "tpl_part_new")
        box.addView(addRow(partNew, "tpl_part_add") { s -> if (t.parts.none { it.name.equals(s, true) }) t.parts += TableKinds.Part(TableKinds.newPartKey(), s); renderAll() })

        val onBoard = b.tables.count { it.kind == t.id }
        val apply = CheckBox(a).apply {
            text = "Обновить таблицы этого вида на доске ($onBoard)"
            isChecked = true; tag = "tpl_apply"; buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            visibility = if (onBoard > 0) View.VISIBLE else View.GONE
        }
        box.addView(apply, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(12f) })

        fun renderCols() {
            colsBox.removeAllViews()
            for ((i, c) in t.cols.withIndex()) {
                val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2f), 0, dp(2f)) }
                row.addView(CheckBox(a).apply {
                    isChecked = !c.hidden; tag = "tpl_col_show_$i"; buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
                    setOnCheckedChangeListener { _, on -> c.hidden = !on }
                })
                val isVol = c.k == TableCol.VOL
                if (isVol) {
                    row.addView(Ui.text(a, if (t.parts.isEmpty()) "Объём по схеме" else "Объём: " + t.parts.joinToString(" · ") { it.name },
                        15f, Ui.INK).apply { setPadding(dp(4f), 0, 0, 0) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                } else row.addView(EditText(a).apply {
                    setText(c.title()); isSingleLine = true; textSize = 15f; tag = "tpl_col_name_$i"
                    hint = TableCol.defaultName(c.k)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, st: Int, n: Int, af: Int) {}
                        override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, n: Int) {}
                        override fun afterTextChanged(e: Editable?) {
                            val v = e?.toString()?.trim().orEmpty()
                            c.name = if (!c.custom && (v.isEmpty() || v == TableCol.defaultName(c.k))) "" else v
                        }
                    })
                }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                row.addView(Ui.text(a, kindLabel(c), 12f, if (c.custom) Ui.ACCENT else Ui.MUTED, bold = c.custom).apply {
                    setPadding(dp(6f), dp(6f), dp(6f), dp(6f)); tag = "tpl_col_type_$i"
                    if (c.custom) setOnClickListener { colForm(c) { renderAll() } }
                })
                fun small(s: String, tag0: String, f: () -> Unit) = row.addView(Ui.text(a, s, 16f, Ui.INK2, bold = true).apply {
                    tag = tag0; gravity = Gravity.CENTER; setPadding(dp(8f), dp(6f), dp(8f), dp(6f)); setOnClickListener { f() }
                })
                small("▲", "tpl_col_up_$i") { if (i > 0) { val x = t.cols[i]; t.cols[i] = t.cols[i - 1]; t.cols[i - 1] = x; renderAll() } }
                small("▼", "tpl_col_down_$i") { if (i + 1 < t.cols.size) { val x = t.cols[i]; t.cols[i] = t.cols[i + 1]; t.cols[i + 1] = x; renderAll() } }
                if (c.custom) small("✕", "tpl_col_del_$i") { t.cols.removeAt(i); renderAll() }
                else row.addView(View(a), LinearLayout.LayoutParams(dp(30f), 1))
                colsBox.addView(row)
            }
        }

        fun chips(box0: Ui.FlowRow, items: List<String>, tagBase: String, remove: (Int) -> Unit) {
            box0.removeAllViews()
            if (items.isEmpty()) box0.addView(Ui.text(a, if (tagBase == "tpl_part") "одной цифрой" else "пока нет", 13f, Ui.MUTED))
            for ((i, w) in items.withIndex()) box0.addView(Ui.pill(a, "$w  ✕", Ui.INK, Ui.DESK).apply {
                tag = "${tagBase}_$i"; setOnClickListener { remove(i) }
            })
        }

        renderAll = {
            renderCols()
            chips(worksBox, t.works, "tpl_work") { t.works.removeAt(it); renderAll() }
            chips(partsBox, t.parts.map { it.name }, "tpl_part") { t.parts.removeAt(it); renderAll() }
        }
        renderAll()

        val d = AlertDialog.Builder(a)
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .apply { if (!fresh) setNeutralButton("Удалить", null) }
            .create()
        dialog = d
        d.setOnDismissListener { if (dialog === d) dialog = null }
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        d.show()
        d.window?.setLayout(min(dp(680f), (a.resources.displayMetrics.widthPixels * 0.96f).toInt()), Ui.WRAP)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { name.error = "Впишите название"; name.requestFocus(); return@setOnClickListener }
            t.name = n
            t.section = section.text.toString().trim().ifEmpty { "Мои таблицы" }
            val update = apply.isChecked && onBoard > 0
            fun saved(asTemplate: Boolean) {
                d.dismiss()
                a.hint(if (asTemplate) "«${t.name}» — шаблон: она в выборе таблиц на всех досках, у всей команды (раздел «${t.section}»)"
                    else "«${t.name}» сохранена только для этой доски — раздел «${t.section}» в «Таблица ИД»", 3200)
                onSaved(t)
            }
            if (inLibrary) { saveTemplate(t, library, update); saved(true); return@setOnClickListener }
            askScope(t) { asTemplate ->
                if (asTemplate) saveTemplate(t, library, update) else saveForBoard(t, update)
                saved(asTemplate)
            }
        }
        if (!fresh) d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AlertDialog.Builder(a)
                .setMessage(if (boardOnly) "Убрать «${t.name}» из видов таблиц этой доски? Таблицы этого вида останутся как есть."
                    else "Убрать шаблон «${t.name}»? Он пропадёт из выбора таблиц у всей команды. Таблицы этого вида на досках останутся как есть.")
                .setPositiveButton("Убрать") { _, _ ->
                    if (boardOnly) {
                        a.pushUndo()
                        b.kinds.removeAll { it.tpl.id == t.id }
                        a.changed()
                    } else library.delete(t.id)
                    d.dismiss()
                    onSaved(t)
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    /**
     * Новый вид (или вид этой доски) — куда сохранить: шаблон для всех досок или только эта доска (Владимир: «чтобы база
     * шаблонов не забивалась»). [done] — true: шаблон.
     */
    private fun askScope(t: TableTemplate, done: (Boolean) -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(6f), dp(20f), dp(4f)) }
        lateinit var d: AlertDialog
        fun option(tag0: String, title: String, sub: String, fg: Int, bg: Int, asTemplate: Boolean) {
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; tag = tag0; isClickable = true
                background = Ui.rounded(a, bg, null, 14f)
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                addView(Ui.text(a, title, 16.5f, fg, bold = true))
                addView(Ui.text(a, sub, 13f, if (fg == Color.WHITE) Color.WHITE else Ui.INK2).apply { setPadding(0, dp(3f), 0, 0) })
                setOnClickListener { d.dismiss(); done(asTemplate) }
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
        }
        option("tpl_scope_lib", "Сохранить как шаблон",
            "Будет в выборе таблиц на всех досках — и на новых, у вас и у коллег.", Color.WHITE, Ui.ACCENT, true)
        option("tpl_scope_board", "Только для этой доски",
            "Будет только здесь — список шаблонов не забивается. На общей доске её увидят и коллеги.", Ui.INK, Ui.DESK, false)
        d = AlertDialog.Builder(a)
            .setTitle("Где сохранить «${t.name}»?")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .create()
        scopeDialog = d
        d.show()
    }

    /** В общий список шаблонов (на сервер); был видом этой доски — оттуда убирается (теперь он шаблон). */
    private fun saveTemplate(t: TableTemplate, library: TableTemplates, update: Boolean) {
        library.save(t)
        val wasBoard = b.kinds.any { it.tpl.id == t.id }
        if (wasBoard || update) {
            a.pushUndo()
            b.kinds.removeAll { it.tpl.id == t.id }
            if (update) applyToBoard(t, undo = false)
            a.changed()
        }
    }

    /** Вид только этой доски: хранится в доске (едет к коллегам вместе с ней). Один шаг «Отменить». */
    private fun saveForBoard(t: TableTemplate, update: Boolean) {
        a.pushUndo()
        val k = b.kinds.firstOrNull { it.tpl.id == t.id }
        if (k != null) k.tpl = t.copy() else b.kinds += BoardKind(BoardKind.idFor(t.id), t.copy())
        TableKinds.register(t.toKind())
        if (update) applyToBoard(t, undo = false)
        a.changed()
    }

    /** Свой столбец встаёт после «Вида работ» (как раньше в «Столбцах»), нет его — в конец. */
    private fun insertAt(t: TableTemplate): Int {
        val i = t.cols.indexOfFirst { it.k == TableCol.VOL }.takeIf { it >= 0 } ?: t.cols.indexOfFirst { it.k == TableCol.WORK }
        return if (i >= 0) i + 1 else t.cols.size
    }

    private fun kindLabel(c: TableCol): String = when {
        c.custom -> TableCol.typeName(c.type) + if (c.isList) ": " + c.opts.joinToString(", ") { it.name } else ""
        c.isStatus -> "статус"
        c.isDate -> "дата"
        c.isVol -> "число"
        else -> "текст"
    }

    private fun addRow(edit: EditText, tag0: String, add: (String) -> Unit): View = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(edit, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        addView(Ui.pill(a, "Добавить", Ui.ACCENT, Ui.ACCENT_SOFT).apply {
            tag = tag0
            setOnClickListener {
                val s = edit.text.toString().trim()
                if (s.isNotEmpty()) { add(s); edit.setText("") }
            }
        }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(8f) })
    }

    /**
     * Свой столбец: название и вид. У списка — варианты через запятую («Нет, Есть, Проверен»), цвета по порядку: красный,
     * жёлтый, зелёный, синий… [c] — правка (null — новый).
     */
    fun colForm(c: TableCol?, done: (TableCol) -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val name = EditText(a).apply { setText(c?.name.orEmpty()); isSingleLine = true; hint = "например: PDF, Месяц, Длина"; tag = "tplc_name" }
        box.addView(name)
        var type = c?.type.orEmpty()
        val types = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f); setPadding(0, dp(10f), 0, 0) }
        val opts = EditText(a).apply {
            setText(c?.opts?.joinToString(", ") { it.name }.orEmpty()); hint = "варианты через запятую: Нет, Есть, Проверен"; tag = "tplc_opts"
        }
        val pills = ArrayList<TextView>()
        fun paint() {
            for ((i, p) in pills.withIndex()) {
                val on = TableCol.TYPES[i] == type
                p.background = Ui.rounded(a, if (on) Ui.INK else Ui.DESK, null, 999f); p.setTextColor(if (on) Color.WHITE else Ui.INK)
            }
            opts.visibility = if (type == TableCol.LIST) View.VISIBLE else View.GONE
        }
        for ((i, ty) in TableCol.TYPES.withIndex()) types.addView(Ui.pill(a, TableCol.typeName(ty), Ui.INK, Ui.DESK).apply {
            tag = "tplc_type_$i"; setOnClickListener { type = ty; paint() }
        }.also { pills += it })
        box.addView(types)
        box.addView(opts)
        box.addView(Ui.text(a, "Отметка — касание ставит или снимает ✓. Список — касание открывает варианты, каждый своим цветом.", 13f, Ui.MUTED)
            .apply { setPadding(0, dp(8f), 0, 0) })
        paint()
        val d = AlertDialog.Builder(a)
            .setTitle(if (c == null) "Свой столбец" else "Столбец «${c.title()}»")
            .setView(box)
            .setPositiveButton(if (c == null) "Добавить" else "Готово", null)
            .setNegativeButton("Отмена", null)
            .create()
        colDialog = d
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { name.error = "Впишите название"; return@setOnClickListener }
            val col = c ?: TableCol(TableCol.newCustomKey())
            col.name = n
            col.type = type
            val old = col.opts.associate { it.name to it.color }
            col.opts.clear()
            if (type == TableCol.LIST) opts.text.toString().split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                .forEachIndexed { i, o -> col.opts += ColOpt(o, old[o] ?: OPT_COLORS[i % OPT_COLORS.size]) }
            d.dismiss()
            done(col)
        }
    }

    /** Таблицы этого вида на доске — по шаблону: устройство вида и столбцы (данные строк не трогаются). Один шаг «Отменить». */
    private fun applyToBoard(t: TableTemplate, undo: Boolean = true) {
        if (undo) a.pushUndo()
        val k = t.toKind()
        for (tb in b.tables) if (tb.kind == t.id) {
            tb.ownKind = k
            tb.cols.clear(); tb.cols.addAll(t.tableCols())
            tb.lay = null
        }
        if (undo) a.changed()
    }

    companion object {
        /** Последний открытый редактор (для проверок). */
        internal var last: TableEditor? = null

        /** Цвета вариантов списка по порядку: красный, жёлтый, зелёный, синий, фиолетовый, серый. */
        val OPT_COLORS = intArrayOf(
            Color.rgb(190, 42, 42), Color.rgb(214, 150, 20), Color.rgb(35, 128, 58), Color.rgb(47, 111, 224),
            Color.rgb(155, 89, 214), Color.rgb(120, 132, 144),
        )
    }
}
