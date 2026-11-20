package party.shulya.freewrite

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Вкладка «Задачи» (0.29) — календарь как на айфоне, по макету ViaDesk: месяцы с точками, день по часам с неделей сверху,
 * поиск по словам, шторка «Новое событие / Новая задача», «Подробно». Записи — [PlanStore], оповещения — [PlanAlarms].
 */
class PlannerPage(private val a: HomeActivity) {
    val store = PlanStore(a)
    var sel: LocalDate = today()
    internal var mode = MONTH
    private var dayFrom = MONTH

    val root = FrameLayout(a)
    private lateinit var monthBox: LinearLayout
    private lateinit var dayBox: LinearLayout
    private lateinit var searchBox: LinearLayout
    internal lateinit var months: PlanMonthsView
    private lateinit var monthScroll: ScrollView
    private lateinit var yearLabel: TextView
    private lateinit var strip: LinearLayout
    private lateinit var dayBack: TextView
    internal lateinit var dayHead: TextView
    private lateinit var allDay: Ui.FlowRow
    internal lateinit var timeline: PlanDayView
    private lateinit var dayScroll: ScrollView
    internal lateinit var qInput: EditText
    internal lateinit var qResults: LinearLayout

    internal var editor: AlertDialog? = null
    internal var editorDraft: PlanItem? = null
    internal var details: AlertDialog? = null
    private val prefs get() = a.getSharedPreferences("plan", Context.MODE_PRIVATE)
    val hidden: MutableSet<String> get() = (prefs.getStringSet("hidden", emptySet()) ?: emptySet()).toMutableSet()

    private fun dp(v: Float) = Ui.dp(a, v)
    fun today(): LocalDate = Instant.ofEpochMilli(PlanAlarms.clock()).atZone(PlanAlarms.zone).toLocalDate()

    init { build(); show(MONTH) }

    // ---------------------------------------------------------------- каркас

    private fun build() {
        // Шапка календаря — белая, как шапка главного экрана над ней (0.29.3, «Светлая целиком»), граница — под днями недели.
        monthBox = column()
        monthBox.addView(nav(MONTH))
        monthBox.addView(weekHeader())
        monthBox.addView(Ui.barLine(a), LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
        months = PlanMonthsView(a, this).apply { tag = "plan_months" }
        monthScroll = ScrollView(a).apply { isFillViewport = true; addView(months) }
        monthBox.addView(monthScroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        monthBox.addView(bottomBar())

        dayBox = column()
        dayBox.addView(nav(DAY))
        strip = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = "plan_strip"
            setPadding(dp(4f), dp(2f), dp(4f), dp(4f))
        }
        dayBox.addView(strip)
        dayHead = Ui.text(a, "", 15f, Ui.INK, bold = true).apply {
            tag = "plan_dayhead"; gravity = Gravity.CENTER; setPadding(0, dp(4f), 0, dp(6f))
        }
        dayBox.addView(dayHead)
        allDay = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f); setPadding(dp(16f), 0, dp(16f), dp(6f)); tag = "plan_allday" }
        dayBox.addView(allDay)
        strip.setBackgroundColor(Ui.BAR); dayHead.setBackgroundColor(Ui.BAR); allDay.setBackgroundColor(Ui.BAR)
        dayBox.addView(Ui.barLine(a), LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
        timeline = PlanDayView(a, this).apply { tag = "plan_timeline" }
        dayScroll = ScrollView(a).apply { addView(timeline) }
        dayBox.addView(dayScroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        dayBox.addView(bottomBar())

        searchBox = column()
        val qbar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(6f), dp(8f), dp(10f))
            setBackgroundColor(Ui.BAR)
        }
        val field = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(a, Ui.DESK, null, 12f)
            setPadding(dp(10f), 0, dp(10f), 0)
        }
        field.addView(ImageView(a).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(Ui.MUTED) },
            LinearLayout.LayoutParams(dp(20f), dp(20f)))
        qInput = EditText(a).apply {
            tag = "q_input"; hint = "Поиск: ВСК, котлован, акт…"; textSize = 16f; background = null; isSingleLine = true
            setTextColor(Ui.INK); setHintTextColor(Ui.MUTED); setPadding(dp(8f), dp(10f), dp(4f), dp(10f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { renderSearch() }
            })
        }
        field.addView(qInput, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        qbar.addView(field, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        qbar.addView(Ui.text(a, "Отменить", 15f, Ui.ACCENT, bold = true).apply {
            tag = "q_cancel"; setPadding(dp(12f), dp(10f), dp(8f), dp(10f)); isClickable = true
            setOnClickListener { qInput.setText(""); hideKeyboard(); show(MONTH) }
        })
        searchBox.addView(qbar)
        searchBox.addView(Ui.barLine(a), LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
        qResults = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; tag = "q_results"; setPadding(dp(16f), 0, dp(16f), dp(96f)) }
        searchBox.addView(ScrollView(a).apply { addView(qResults) }, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        for (b in listOf(monthBox, dayBox, searchBox)) root.addView(b, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
    }

    private fun column() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    private fun nav(which: String): LinearLayout {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(2f), dp(18f), dp(2f))
            setBackgroundColor(Ui.BAR)
        }
        if (which == MONTH) {
            yearLabel = Ui.text(a, "", 17f, Ui.ACCENT, bold = true).apply { setPadding(dp(8f), dp(8f), dp(8f), dp(8f)) }
            row.addView(yearLabel)
        } else {
            dayBack = Ui.text(a, "‹ Месяц", 17f, Ui.ACCENT, bold = true).apply {
                tag = "plan_back"; setPadding(dp(6f), dp(8f), dp(8f), dp(8f)); isClickable = true
                setOnClickListener { back() }
            }
            row.addView(dayBack)
        }
        row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(round(R.drawable.ic_search, "Поиск", if (which == MONTH) "plan_search" else "plan_search_d", Ui.DESK, Ui.INK2) { openSearch() })
        row.addView(round(R.drawable.ic_plus, "Добавить", if (which == MONTH) "plan_add" else "plan_add_d", Ui.TEAL, Ui.ON_TEAL) {
            val base = if (mode == DAY) sel else today()
            val t = LocalTime.now().let { n -> LocalTime.of(minOf(22, n.hour + 1), 0) }
            openEditor(null, base.atTime(t))
        })
        return row
    }

    /** Круглая кнопка шапки: поиск — серая, «+» — бирюзовая с тёмным значком (как в макете темы). */
    private fun round(icon: Int, label: String, tag: String, fill: Int, tint: Int, click: () -> Unit) = ImageView(a).apply {
        this.tag = tag; contentDescription = label
        setImageResource(icon); imageTintList = ColorStateList.valueOf(tint)
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fill) }
        setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
        isClickable = true
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginStart = dp(8f) }
    }

    private fun weekHeader() = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Ui.BAR)
        setPadding(dp(12f), dp(2f), dp(12f), dp(6f))
        for ((k, w) in Plan.WEEKDAYS.withIndex()) addView(Ui.text(a, w, 12f, if (k >= 5) Ui.MUTED else Ui.INK2, bold = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
    }

    private fun bottomBar() = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
        setBackgroundColor(Ui.BG)
        addView(Ui.text(a, "Сегодня", 15f, Ui.ACCENT, bold = true).apply {
            tag = "plan_today"; setPadding(dp(4f), dp(8f), dp(12f), dp(8f)); isClickable = true
            setOnClickListener { goToday() }
        })
        addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        addView(Ui.text(a, "Календари", 15f, Ui.ACCENT, bold = true).apply {
            tag = "plan_cals"; setPadding(dp(12f), dp(8f), dp(4f), dp(8f)); isClickable = true
            setOnClickListener { calendarsDialog() }
        })
    }

    // ---------------------------------------------------------------- переходы

    fun show(m: String) {
        mode = m
        monthBox.visibility = if (m == MONTH) View.VISIBLE else View.GONE
        dayBox.visibility = if (m == DAY) View.VISIBLE else View.GONE
        searchBox.visibility = if (m == SEARCH) View.VISIBLE else View.GONE
        refresh()
    }

    fun refresh() {
        when (mode) {
            MONTH -> renderMonths()
            DAY -> renderDay()
            SEARCH -> renderSearch()
        }
    }

    /** «Назад» системы: день → месяц (или поиск), поиск → месяц. false — уже наверху. */
    fun back(): Boolean = when (mode) {
        DAY -> { show(if (dayFrom == SEARCH) SEARCH else MONTH); true }
        SEARCH -> { qInput.setText(""); hideKeyboard(); show(MONTH); true }
        else -> false
    }

    fun openDay(d: LocalDate, flash: String? = null, from: String = mode) {
        sel = d
        dayFrom = if (from == SEARCH) SEARCH else MONTH
        timeline.flashId = flash
        show(DAY)
    }

    fun goToday() {
        sel = today()
        if (mode == SEARCH) show(MONTH) else refresh()
        if (mode == MONTH) scrollToMonth()
    }

    private fun openSearch() {
        show(SEARCH)
        qInput.requestFocus()
        val im = a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        qInput.post { im.showSoftInput(qInput, 0) }
    }

    private fun hideKeyboard() {
        val im = a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        im.hideSoftInputFromWindow(qInput.windowToken, 0)
    }

    // ---------------------------------------------------------------- месяцы

    private fun renderMonths() {
        yearLabel.text = sel.year.toString()
        months.setRange(YearMonth.from(sel).minusMonths(6), 19)
        scrollToMonth()
    }

    private fun scrollToMonth() {
        monthScroll.post { monthScroll.scrollTo(0, months.monthTop(YearMonth.from(sel))) }
    }

    /** Цвета точек дня (не больше трёх). */
    internal fun dots(d: LocalDate, hide: Set<String>): IntArray =
        Plan.on(store.items, d, hide).take(3).map { Plan.cal(it.item.cal).color }.toIntArray()

    // ---------------------------------------------------------------- день

    private fun renderDay() {
        dayBack.text = if (dayFrom == SEARCH) "‹ Поиск" else "‹ " + Plan.MONTHS[sel.monthValue - 1]
        strip.removeAllViews()
        strip.addView(arrow("‹", "strip_prev") { sel = sel.minusWeeks(1); renderDay() })
        val mon = sel.minusDays((sel.dayOfWeek.value - 1).toLong())
        val today = today()
        for (k in 0 until 7) {
            val d = mon.plusDays(k.toLong())
            val on = d == sel
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; tag = "strip_$k"; isClickable = true
                setOnClickListener { sel = d; renderDay() }
            }
            cell.addView(Ui.text(a, Plan.WEEKDAYS[k], 11f, if (k >= 5) Ui.MUTED else Ui.INK2).apply { gravity = Gravity.CENTER })
            // Выбранный день — тёмно-синий кружок, сегодня — янтарный ободок (и у выбранного).
            cell.addView(Ui.text(a, d.dayOfMonth.toString(), 17f, if (on) Color.WHITE else Ui.INK, bold = on || d == today).apply {
                gravity = Gravity.CENTER
                if (on || d == today) background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (on) Ui.INK else Color.TRANSPARENT)
                    if (d == today) setStroke(dp(2f), Ui.AMBER)
                }
            }, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            strip.addView(cell, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        strip.addView(arrow("›", "strip_next") { sel = sel.plusWeeks(1); renderDay() })
        dayHead.text = Plan.dayTitle(sel) + " г."
        val occs = Plan.on(store.items, sel, hidden)
        allDay.removeAllViews()
        val ad = occs.filter { it.item.allDay }
        allDay.visibility = if (ad.isEmpty()) View.GONE else View.VISIBLE
        if (ad.isNotEmpty()) allDay.addView(Ui.text(a, "весь день", 12f, Ui.MUTED))
        for ((k, o) in ad.withIndex()) {
            val c = Plan.cal(o.item.cal).color
            allDay.addView(Ui.text(a, (if (o.item.isTask) (if (o.done) "☑ " else "☐ ") else "") + o.item.title, 14f, Ui.INK, bold = true).apply {
                tag = "allday_$k"
                background = Ui.rounded(a, (c and 0x00FFFFFF) or 0x30000000, null, 8f)
                setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                if (o.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                isClickable = true
                setOnClickListener { showDetails(o) }
            })
        }
        timeline.set(sel, occs.filter { !it.item.allDay }, sel == today)
        val focus = timeline.flashId?.let { id -> occs.firstOrNull { it.item.id == id && !it.item.allDay } }?.let { minutesOf(it.start.toLocalTime()) }
            ?: occs.firstOrNull { !it.item.allDay }?.let { minutesOf(it.start.toLocalTime()) }
            ?: if (sel == today) LocalTime.now().hour * 60 else 8 * 60
        val start = if (sel == today && timeline.flashId == null) minOf(focus, LocalTime.now().hour * 60) else focus
        dayScroll.post { dayScroll.scrollTo(0, maxOf(0, timeline.yOf(start) - dp(40f))) }
    }

    private fun minutesOf(t: LocalTime) = t.hour * 60 + t.minute

    private fun arrow(s: String, tag: String, click: () -> Unit) = Ui.text(a, s, 24f, Ui.ACCENT, bold = true).apply {
        this.tag = tag; gravity = Gravity.CENTER; setPadding(dp(8f), 0, dp(8f), 0); isClickable = true
        setOnClickListener { click() }
    }

    /** Касание блока в «дне». box — по квадратику задачи: отметить выполненной. */
    internal fun onBlock(o: PlanOcc, box: Boolean) {
        if (box && o.item.isTask) toggleDone(o) else showDetails(o)
    }

    /** Касание пустого часа: новое событие на этот получас. */
    internal fun onEmpty(minutes: Int) {
        val m = (minutes / 30 * 30).coerceIn(0, 23 * 60 + 30)
        openEditor(null, sel.atTime(m / 60, m % 60))
    }

    private fun toggleDone(o: PlanOcc) {
        val it = o.item.copy()
        val k = Plan.iso(o.day)
        if (!it.done.remove(k)) it.done += k
        store.put(it)
        PlanAlarms.reschedule(a, store.items)
        refresh()
    }

    // ---------------------------------------------------------------- поиск

    internal fun renderSearch() {
        qResults.removeAllViews()
        val q = qInput.text.toString().trim()
        if (q.isEmpty()) {
            qResults.addView(Ui.text(a, "Ищет слово в названиях, местах и заметках событий и задач — за всё время. Например, «ВСК» или «котлован».",
                14f, Ui.MUTED).apply { setPadding(0, dp(16f), 0, 0) })
            return
        }
        val found = Plan.search(store.items, q)
        if (found.isEmpty()) {
            qResults.addView(Ui.text(a, "Ничего не нашлось по «$q»", 15f, Ui.MUTED).apply { setPadding(0, dp(16f), 0, 0) })
            return
        }
        val days = found.map { it.start.toLocalDate() }.toSet().size
        qResults.addView(Ui.text(a, "Найдено: ${found.size} · дней: $days", 13f, Ui.MUTED).apply { tag = "q_count"; setPadding(0, dp(8f), 0, dp(4f)) })
        val today = today()
        var lastDay: LocalDate? = null
        var group: LinearLayout? = null
        for ((k, it) in found.withIndex()) {
            val d = it.start.toLocalDate()
            if (d != lastDay) {
                lastDay = d
                val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4f), dp(14f), dp(4f), dp(6f)) }
                head.addView(Ui.text(a, Plan.dayTitle(d), 14f, if (d == today) Ui.ACCENT else Ui.INK, bold = true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                head.addView(Ui.text(a, Plan.relDays(d, today), 13f, Ui.MUTED))
                qResults.addView(head)
                group = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.rounded(a, Ui.CARD, Ui.RULE, 14f) }
                qResults.addView(group)
            }
            val o = PlanOcc(it, d)
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; tag = "q_row_$k"; isClickable = true
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                setOnClickListener { _ -> hideKeyboard(); openDay(d, it.id, SEARCH) }
            }
            row.addView(View(a).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Plan.cal(it.cal).color) }
            }, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { topMargin = dp(6f); marginEnd = dp(10f) })
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(a, (if (it.isTask) "☐ " else "") + it.title, 15f, Ui.INK, bold = true))
            val where = listOf(Plan.timeText(o) + (if (it.repeat != Plan.NONE) " · " + Plan.repeatText(it).lowercase() else ""), it.place)
                .filter { s -> s.isNotEmpty() }.joinToString(" · ")
            texts.addView(Ui.text(a, where, 13f, Ui.INK2))
            if (it.notes.isNotEmpty()) texts.addView(Ui.text(a, it.notes, 13f, Ui.MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            group!!.addView(row)
        }
    }

    // ---------------------------------------------------------------- календари

    private fun calendarsDialog() {
        val hide = hidden
        val names = Plan.CALS.map { "● " + it.name }.toTypedArray()
        val checked = Plan.CALS.map { it.id !in hide }.toBooleanArray()
        AlertDialog.Builder(a).setTitle("Показывать календари")
            .setMultiChoiceItems(names, checked) { _, k, on -> checked[k] = on }
            .setPositiveButton("Готово") { _, _ ->
                val h = Plan.CALS.filterIndexed { k, _ -> !checked[k] }.map { it.id }.toSet()
                prefs.edit().putStringSet("hidden", h).apply()
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- подробно

    internal fun showDetails(o: PlanOcc) {
        val it = o.item
        val pad = dp(20f)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8f), pad, 0) }
        fun line(s: String, color: Int = Ui.INK2, size: Float = 15f) {
            if (s.isNotEmpty()) col.addView(Ui.text(a, s, size, color).apply { setPadding(0, dp(3f), 0, dp(3f)) })
        }
        line(it.place)
        line(Plan.dayTitle(o.day) + " · " + Plan.timeText(o), Ui.INK)
        if (it.repeat != Plan.NONE) line("Повтор: " + Plan.repeatText(it).lowercase() + (it.until?.let { u -> " до " + Plan.dayTitle(u) } ?: ""))
        line("Календарь: " + Plan.cal(it.cal).name)
        val al = it.alerts().map { x -> Plan.alertName(it, x).lowercase() }
        line("Оповещение: " + if (al.isEmpty()) "нет" else al.joinToString(", "))
        if (it.notes.isNotEmpty()) line(it.notes, Ui.INK, 15f)
        if (it.isTask) col.addView(CheckBox(a).apply {
            text = "Выполнено"; isChecked = o.done; tag = "det_done"
            setOnCheckedChangeListener { _, _ -> details?.dismiss(); toggleDone(o) }
        })
        details = AlertDialog.Builder(a)
            .setTitle(it.title.ifBlank { if (it.isTask) "Задача" else "Событие" })
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("Изменить") { _, _ -> openEditor(it, null) }
            .setNeutralButton(if (it.isTask) "Удалить задачу" else "Удалить") { _, _ -> delete(o) }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun delete(o: PlanOcc) {
        val it = o.item
        fun done() { PlanAlarms.reschedule(a, store.items); refresh(); android.widget.Toast.makeText(a, if (it.isTask) "Задача удалена" else "Событие удалено", android.widget.Toast.LENGTH_SHORT).show() }
        if (it.repeat != Plan.NONE) {
            AlertDialog.Builder(a).setTitle("Удалить повторяющееся")
                .setItems(arrayOf("Только " + o.day.dayOfMonth + " " + Plan.MONTHS_GEN[o.day.monthValue - 1], "Все повторы")) { _, k ->
                    if (k == 0) { val c = it.copy(); c.skip += Plan.iso(o.day); store.put(c) } else store.remove(it.id)
                    done()
                }.setNegativeButton("Отмена", null).show()
        } else {
            AlertDialog.Builder(a).setTitle("Удалить «${it.title}»?")
                .setPositiveButton("Удалить") { _, _ -> store.remove(it.id); done() }
                .setNegativeButton("Отмена", null).show()
        }
    }

    // ---------------------------------------------------------------- шторка события

    /** Новое ([src] = null, начало — [at]) или правка записи. */
    fun openEditor(src: PlanItem?, at: java.time.LocalDateTime?) {
        val d = src?.copy() ?: PlanItem().apply {
            start = at ?: today().atTime(9, 0); end = start.plusHours(1)
        }
        editorDraft = d
        val pad = dp(18f)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(6f), pad, dp(8f)) }

        // Событие / Задача
        val seg = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; background = Ui.rounded(a, Ui.DESK, null, 10f); setPadding(dp(3f), dp(3f), dp(3f), dp(3f)) }
        val segEvent = Ui.text(a, "Событие", 15f, Ui.INK, bold = true).apply { tag = "ev_kind_event"; gravity = Gravity.CENTER; setPadding(0, dp(7f), 0, dp(7f)) }
        val segTask = Ui.text(a, "Задача", 15f, Ui.INK, bold = true).apply { tag = "ev_kind_task"; gravity = Gravity.CENTER; setPadding(0, dp(7f), 0, dp(7f)) }
        seg.addView(segEvent, LinearLayout.LayoutParams(0, Ui.WRAP, 1f)); seg.addView(segTask, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        col.addView(seg)

        fun field(hint: String, tag: String, value: String, multi: Boolean = false) = EditText(a).apply {
            this.tag = tag; this.hint = hint; setText(value); textSize = 16f
            setTextColor(Ui.INK); setHintTextColor(Ui.MUTED)
            background = Ui.rounded(a, Ui.PAPER, Ui.RULE, 10f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            if (multi) { minLines = 2; gravity = Gravity.TOP or Gravity.START }
        }
        fun lp(top: Float = 8f) = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(top) }
        val title = field("Название", "ev_title", d.title)
        col.addView(title, lp(12f))
        val place = field("Место", "ev_place", d.place)
        col.addView(place, lp())

        fun row(label: String): Pair<LinearLayout, LinearLayout> {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f), 0, dp(6f)) }
            r.addView(Ui.text(a, label, 15f, Ui.INK2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            val vals = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            r.addView(vals)
            return r to vals
        }
        fun value(tag: String) = Ui.text(a, "", 15f, Ui.ACCENT, bold = true).apply {
            this.tag = tag; background = Ui.rounded(a, Ui.ACCENT_SOFT, null, 8f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f)); isClickable = true
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.rounded(a, Ui.PAPER, Ui.RULE, 10f); setPadding(dp(12f), dp(4f), dp(12f), dp(4f)) }
        col.addView(box, lp(12f))

        val (allRow, allVals) = row("Весь день")
        val allBox = CheckBox(a).apply { tag = "ev_allday"; isChecked = d.allDay }
        allVals.addView(allBox); box.addView(allRow)
        val (startRow, startVals) = row("Начало")
        val startLabel = startRow.getChildAt(0) as TextView
        val sDate = value("ev_start_date"); val sTime = value("ev_start_time")
        startVals.addView(sDate); startVals.addView(sTime, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(6f) })
        box.addView(startRow)
        val (endRow, endVals) = row("Конец")
        val eDate = value("ev_end_date"); val eTime = value("ev_end_time")
        endVals.addView(eDate); endVals.addView(eTime, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(6f) })
        box.addView(endRow)
        val (repRow, repVals) = row("Повтор"); val rep = value("ev_repeat"); repVals.addView(rep); box.addView(repRow)
        // Дни недели повтора (0.29.1): несколько дней — «пн, ср, пт».
        val wdRow = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2f), 0, dp(8f)) }
        wdRow.addView(Ui.text(a, "Дни недели", 15f, Ui.INK2).apply { setPadding(0, 0, 0, dp(6f)) })
        val wdChips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val wdViews = (1..7).map { k ->
            Ui.text(a, Plan.WEEKDAYS[k - 1], 14f, Ui.ACCENT, bold = true).apply {
                tag = "ev_wd_$k"; gravity = Gravity.CENTER; isClickable = true
            }.also { v -> wdChips.addView(v, LinearLayout.LayoutParams(0, dp(36f), 1f).apply { marginEnd = if (k < 7) dp(4f) else 0 }) }
        }
        wdRow.addView(wdChips)
        box.addView(wdRow)
        val (untilRow, untilVals) = row("Повторять до"); val until = value("ev_until"); untilVals.addView(until); box.addView(untilRow)

        val box2 = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.rounded(a, Ui.PAPER, Ui.RULE, 10f); setPadding(dp(12f), dp(4f), dp(12f), dp(4f)) }
        col.addView(box2, lp(12f))
        val (calRow, calVals) = row("Календарь"); val cal = value("ev_cal"); calVals.addView(cal); box2.addView(calRow)
        val (alRow, alVals) = row("Оповещение"); val al = value("ev_alert"); alVals.addView(al); box2.addView(alRow)
        val (al2Row, al2Vals) = row("Второе оповещение"); val al2 = value("ev_alert2"); al2Vals.addView(al2); box2.addView(al2Row)
        val notes = field("Заметки", "ev_notes", d.notes, multi = true)
        col.addView(notes, lp(12f))

        fun dateText(x: LocalDate) = x.dayOfMonth.toString() + " " + Plan.MONTHS_GEN[x.monthValue - 1].take(3) + ". " + x.year
        fun refreshForm() {
            val task = d.isTask
            segEvent.background = if (!task) Ui.rounded(a, Ui.PAPER, null, 8f) else null
            segTask.background = if (task) Ui.rounded(a, Ui.PAPER, null, 8f) else null
            place.visibility = if (task) View.GONE else View.VISIBLE
            endRow.visibility = if (task) View.GONE else View.VISIBLE
            startLabel.text = if (task) "Когда" else "Начало"
            sDate.text = dateText(d.start.toLocalDate()); sTime.text = Plan.hm(d.start.toLocalTime())
            eDate.text = dateText(d.end.toLocalDate()); eTime.text = Plan.hm(d.end.toLocalTime())
            sTime.visibility = if (d.allDay) View.GONE else View.VISIBLE
            eTime.visibility = if (d.allDay) View.GONE else View.VISIBLE
            rep.text = if (d.repeat == Plan.WEEK && Plan.weekDays(d) == Plan.WORKDAYS) "По будням" else Plan.REPEATS[d.repeat]
            val weekly = d.repeat == Plan.WEEK || d.repeat == Plan.WEEK2
            wdRow.visibility = if (weekly) View.VISIBLE else View.GONE
            val on = Plan.weekDays(d)
            for ((k, v) in wdViews.withIndex()) {
                val sel = (k + 1) in on
                v.setTextColor(if (sel) Color.WHITE else Ui.ACCENT)
                v.background = Ui.rounded(a, if (sel) Ui.ACCENT else Ui.ACCENT_SOFT, null, 10f)
            }
            untilRow.visibility = if (d.repeat == Plan.NONE) View.GONE else View.VISIBLE
            until.text = d.until?.let { dateText(it) } ?: "Без конца"
            cal.text = "● " + Plan.cal(d.cal).name; cal.setTextColor(Plan.cal(d.cal).color)
            al.text = Plan.alertName(d, d.alert); al2.text = Plan.alertName(d, d.alert2)
            editor?.setTitle(if (src != null) "Изменить" else if (task) "Новая задача" else "Новое событие")
        }
        segEvent.setOnClickListener { d.kind = Plan.EVENT; if (!d.end.isAfter(d.start)) d.end = d.start.plusHours(1); refreshForm() }
        segTask.setOnClickListener { d.kind = Plan.TASK; refreshForm() }
        allBox.setOnCheckedChangeListener { _, on ->
            d.allDay = on
            if (on) {
                d.start = d.start.toLocalDate().atStartOfDay(); d.end = maxOf(d.end.toLocalDate(), d.start.toLocalDate()).atStartOfDay()
                if (d.alert > 0 && d.alert < 1440) d.alert = 1440
                if (d.alert2 > 0 && d.alert2 < 1440) d.alert2 = Plan.NO_ALERT
            } else {
                d.start = d.start.toLocalDate().atTime(9, 0); d.end = d.end.toLocalDate().atTime(10, 0)
                if (!d.end.isAfter(d.start)) d.end = d.start.plusHours(1)
                if (d.alert == 1440) d.alert = 60
            }
            refreshForm()
        }
        sDate.setOnClickListener { pickDate(d.start.toLocalDate()) { x ->
            val shift = java.time.temporal.ChronoUnit.DAYS.between(d.start.toLocalDate(), x)
            d.start = d.start.plusDays(shift); d.end = d.end.plusDays(shift); refreshForm()
        } }
        sTime.setOnClickListener { pickTime(d.start.toLocalTime()) { t ->
            val dur = Plan.durationMin(d); d.start = d.start.toLocalDate().atTime(t); d.end = d.start.plusMinutes(maxOf(dur, 15)); refreshForm()
        } }
        eDate.setOnClickListener { pickDate(d.end.toLocalDate()) { x ->
            d.end = x.atTime(d.end.toLocalTime()); if (d.end.isBefore(d.start)) d.end = if (d.allDay) d.start else d.start.plusHours(1); refreshForm()
        } }
        eTime.setOnClickListener { pickTime(d.end.toLocalTime()) { t ->
            d.end = d.end.toLocalDate().atTime(t); if (!d.end.isAfter(d.start)) d.end = d.end.plusDays(1); refreshForm()
        } }
        // Повтор: «По будням» — каждую неделю пн–пт; у «каждую неделю» и «каждые 2 недели» — строка дней недели.
        val repKeys = listOf(Plan.NONE, Plan.DAY, WORKDAYS_KEY, Plan.WEEK, Plan.WEEK2, Plan.MONTH, Plan.YEAR)
        val repNames = repKeys.map { k -> if (k == WORKDAYS_KEY) "По будням (пн–пт)" else Plan.REPEATS[k]!! }
        rep.setOnClickListener {
            val cur = if (d.repeat == Plan.WEEK && Plan.weekDays(d) == Plan.WORKDAYS) WORKDAYS_KEY else d.repeat
            choose("Повтор", repNames, repKeys.indexOf(cur)) { k ->
                val key = repKeys[k]
                when (key) {
                    WORKDAYS_KEY -> { d.repeat = Plan.WEEK; d.days.clear(); d.days.addAll(Plan.WORKDAYS) }
                    Plan.WEEK, Plan.WEEK2 -> { if (Plan.weekDays(d) == Plan.WORKDAYS && d.repeat == Plan.WEEK && key == Plan.WEEK) d.days.clear(); d.repeat = key }
                    else -> { d.repeat = key; d.days.clear() }
                }
                if (d.repeat == Plan.NONE) d.until = null
                refreshForm()
            }
        }
        for ((k, v) in wdViews.withIndex()) v.setOnClickListener {
            val cur = Plan.weekDays(d).toMutableSet()
            val day = k + 1
            if (day in cur) { if (cur.size > 1) cur.remove(day) } else cur += day
            d.days.clear(); d.days.addAll(cur)
            refreshForm()
        }
        until.setOnClickListener {
            AlertDialog.Builder(a).setTitle("Повторять до")
                .setItems(arrayOf("Без конца", "Выбрать дату…")) { _, k ->
                    if (k == 0) { d.until = null; refreshForm() }
                    else pickDate(d.until ?: d.start.toLocalDate().plusMonths(1)) { x -> d.until = maxOf(x, d.start.toLocalDate()); refreshForm() }
                }.show()
        }
        cal.setOnClickListener { choose("Календарь", Plan.CALS.map { "● " + it.name }, Plan.CALS.indexOfFirst { it.id == d.cal }) { k ->
            d.cal = Plan.CALS[k].id; refreshForm()
        } }
        fun alertChooser(cur: Int, set: (Int) -> Unit) {
            val m = if (d.allDay) Plan.ALERTS_ALL_DAY else Plan.ALERTS
            choose("Оповещение", m.values.toList(), m.keys.indexOf(cur)) { k -> set(m.keys.toList()[k]); refreshForm() }
        }
        al.setOnClickListener { alertChooser(d.alert) { v -> d.alert = v } }
        al2.setOnClickListener { alertChooser(d.alert2) { v -> d.alert2 = v } }

        val dlg = AlertDialog.Builder(a)
            .setTitle(if (src != null) "Изменить" else "Новое событие")
            .setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton(if (src != null) "Готово" else "Добавить", null)
            .setNegativeButton("Отменить", null)
            .create()
        editor = dlg
        dlg.setOnShowListener {
            val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.tag = "ev_save"
            ok.isEnabled = d.title.isNotBlank()
            title.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { ok.isEnabled = !s.isNullOrBlank() }
            })
            ok.setOnClickListener {
                d.title = title.text.toString().trim()
                if (d.title.isEmpty()) return@setOnClickListener
                d.place = if (d.isTask) "" else place.text.toString().trim()
                d.notes = notes.text.toString().trim()
                if (d.isTask) d.end = d.start
                else if (d.end.isBefore(d.start)) d.end = d.start
                save(d)
                dlg.dismiss()
            }
        }
        dlg.show()
        refreshForm()
        if (src == null) { title.requestFocus(); dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE) }
    }

    private fun save(d: PlanItem) {
        store.put(d)
        PlanAlarms.reschedule(a, store.items)
        if (d.alerts().isNotEmpty()) checkNotifications()
        sel = d.start.toLocalDate()
        if (mode == DAY) refresh() else openDay(sel, d.id, MONTH)
    }

    private fun choose(title: String, items: List<String>, cur: Int, pick: (Int) -> Unit) {
        AlertDialog.Builder(a).setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), cur) { dlg, k -> dlg.dismiss(); pick(k) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun pickDate(cur: LocalDate, done: (LocalDate) -> Unit) {
        DatePickerDialog(a, { _, y, m, dd -> done(LocalDate.of(y, m + 1, dd)) }, cur.year, cur.monthValue - 1, cur.dayOfMonth).apply {
            datePicker.firstDayOfWeek = java.util.Calendar.MONDAY
        }.show()
    }

    private fun pickTime(cur: LocalTime, done: (LocalTime) -> Unit) {
        TimePickerDialog(a, { _, h, m -> done(LocalTime.of(h, m)) }, cur.hour, cur.minute, true).show()
    }

    /** Оповещения без разрешения не придут: Android 13+ — спросить; выключены (HarmonyOS) — подсказать, где включить. */
    private fun checkNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7302)
            return
        }
        val nm = a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.areNotificationsEnabled() || prefs.getBoolean("notifWarned", false)) return
        prefs.edit().putBoolean("notifWarned", true).apply()
        AlertDialog.Builder(a).setTitle("Уведомления выключены")
            .setMessage("Чтобы оповещения о событиях приходили, разрешите ViaDesk уведомления.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                runCatching {
                    a.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, a.packageName))
                }
            }
            .setNegativeButton("Позже", null).show()
    }

    companion object {
        private const val WORKDAYS_KEY = "workdays"
        const val MONTH = "month"
        const val DAY = "day"
        const val SEARCH = "search"
    }
}

/** Месяцы подряд, как в календаре айфона: дни с цветными точками записей, сегодня — кружок, выбранный — обводка. */
class PlanMonthsView(ctx: Context, private val page: PlannerPage) : View(ctx) {
    private var first: YearMonth = YearMonth.now()
    private var count = 19
    private val headH = Ui.dp(ctx, 46f)
    private val cellH = Ui.dp(ctx, 50f)
    private val padX = Ui.dpf(ctx, 12f)
    private val num = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.dpf(ctx, 16f); textAlign = Paint.Align.CENTER }
    private val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.dpf(ctx, 20f); typeface = Typeface.DEFAULT_BOLD }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dpf(ctx, 1.5f) }
    private var dots = HashMap<LocalDate, IntArray>()

    fun setRange(from: YearMonth, n: Int) {
        first = from; count = n
        val hide = page.hidden
        dots = HashMap()
        var d = from.atDay(1)
        val end = from.plusMonths(n.toLong()).atDay(1)
        while (d < end) { page.dots(d, hide).takeIf { it.isNotEmpty() }?.let { c -> dots[d] = c }; d = d.plusDays(1) }
        requestLayout(); invalidate()
    }

    private fun rows(m: YearMonth): Int {
        val lead = m.atDay(1).dayOfWeek.value - 1
        return (lead + m.lengthOfMonth() + 6) / 7
    }

    fun monthTop(m: YearMonth): Int {
        var y = 0
        var cur = first
        while (cur < m && cur < first.plusMonths(count.toLong())) { y += headH + rows(cur) * cellH; cur = cur.plusMonths(1) }
        return y
    }

    override fun onMeasure(w: Int, h: Int) {
        var total = 0
        for (k in 0 until count) total += headH + rows(first.plusMonths(k.toLong())) * cellH
        setMeasuredDimension(MeasureSpec.getSize(w), total + Ui.dp(context, 24f))
    }

    fun rectOf(d: LocalDate): RectF? {
        val m = YearMonth.from(d)
        if (m < first || m >= first.plusMonths(count.toLong())) return null
        val cw = (width - 2 * padX) / 7f
        val idx = m.atDay(1).dayOfWeek.value - 1 + d.dayOfMonth - 1
        val top = monthTop(m) + headH + (idx / 7) * cellH
        val left = padX + (idx % 7) * cw
        return RectF(left, top.toFloat(), left + cw, (top + cellH).toFloat())
    }

    fun dayAt(x: Float, y: Float): LocalDate? {
        var top = 0
        for (k in 0 until count) {
            val m = first.plusMonths(k.toLong())
            val h = headH + rows(m) * cellH
            if (y < top + h) {
                val yy = y - top - headH
                if (yy < 0) return null
                val cw = (width - 2 * padX) / 7f
                val col = ((x - padX) / cw).toInt()
                if (col !in 0..6) return null
                val idx = (yy / cellH).toInt() * 7 + col - (m.atDay(1).dayOfWeek.value - 1)
                return if (idx in 0 until m.lengthOfMonth()) m.atDay(idx + 1) else null
            }
            top += h
        }
        return null
    }

    override fun onDraw(c: Canvas) {
        val today = page.today()
        val cw = (width - 2 * padX) / 7f
        var top = 0f
        for (k in 0 until count) {
            val m = first.plusMonths(k.toLong())
            val cur = m == YearMonth.from(today)
            head.color = if (cur) Ui.ACCENT else Ui.INK
            c.drawText(Plan.MONTHS[m.monthValue - 1] + if (m.year != today.year) " " + m.year else "", padX + Ui.dpf(context, 4f),
                top + headH - Ui.dpf(context, 12f), head)
            val lead = m.atDay(1).dayOfWeek.value - 1
            for (dd in 1..m.lengthOfMonth()) {
                val idx = lead + dd - 1
                val cx = padX + (idx % 7) * cw + cw / 2
                val y0 = top + headH + (idx / 7) * cellH
                val d = m.atDay(dd)
                val r = Ui.dpf(context, 15f)
                val cy = y0 + Ui.dpf(context, 18f)
                val isToday = d == today
                val isSel = d == page.sel
                // Сегодня — тёмно-синий кружок с янтарным ободком (макет темы «Светлая целиком»).
                if (isToday) {
                    fill.color = Ui.INK; c.drawCircle(cx, cy, r, fill)
                    ring.color = Ui.AMBER; val w = ring.strokeWidth; ring.strokeWidth = Ui.dpf(context, 2f)
                    c.drawCircle(cx, cy, r + Ui.dpf(context, 1.5f), ring); ring.strokeWidth = w
                }
                else if (isSel) { ring.color = Ui.INK; c.drawCircle(cx, cy, r, ring) }
                num.color = when { isToday -> Color.WHITE; idx % 7 >= 5 -> Ui.MUTED; else -> Ui.INK }
                num.typeface = if (isToday || isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                c.drawText(dd.toString(), cx, cy - (num.ascent() + num.descent()) / 2, num)
                dots[d]?.let { cols ->
                    val dr = Ui.dpf(context, 2.8f); val gap = Ui.dpf(context, 8f)
                    var x = cx - gap * (cols.size - 1) / 2
                    for (col in cols) { fill.color = col; c.drawCircle(x, y0 + Ui.dpf(context, 40f), dr, fill); x += gap }
                }
            }
            top += headH + rows(m) * cellH
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; moved = false }
            MotionEvent.ACTION_MOVE -> if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
            MotionEvent.ACTION_UP -> if (!moved) dayAt(e.x, e.y)?.let { page.openDay(it); performClick() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** День по часам: линии часов, записи блоками (пересекающиеся — рядом), линия «сейчас». */
class PlanDayView(ctx: Context, private val page: PlannerPage) : View(ctx) {
    val hourH = Ui.dp(ctx, 56f)
    private val topPad = Ui.dp(ctx, 12f)
    private val left = Ui.dpf(ctx, 58f)
    private var day: LocalDate = LocalDate.now()
    private var isToday = false
    private class Block(val o: PlanOcc, val a: Int, val b: Int) { var col = 0; var n = 1; val r = RectF() }
    private var blocks = ArrayList<Block>()
    var flashId: String? = null
    private var flashUntil = 0L

    private val line = Paint().apply { color = Ui.RULE; strokeWidth = 1f }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.dpf(ctx, 12f); color = Ui.MUTED; textAlign = Paint.Align.RIGHT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.dpf(ctx, 14f); typeface = Typeface.DEFAULT_BOLD }
    private val smallP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.dpf(ctx, 12f) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dpf(ctx, 2f) }

    fun yOf(minutes: Int) = topPad + minutes * hourH / 60

    fun set(d: LocalDate, occs: List<PlanOcc>, today: Boolean) {
        day = d; isToday = today
        val list = occs.map {
            val a = it.start.toLocalTime().let { t -> t.hour * 60 + t.minute }
            // Событие на несколько дней: в днях после первого — с начала дня; до конца дня, если тянется дальше.
            val sMin = if (it.day == d) a else 0
            val endDt = it.end
            val eMin = when {
                it.item.isTask -> sMin + 30
                endDt.toLocalDate() > d -> 24 * 60
                else -> endDt.toLocalTime().let { t -> t.hour * 60 + t.minute }
            }
            Block(it, sMin, maxOf(eMin, sMin + 20))
        }.sortedWith(compareBy<Block>({ it.a }, { -it.b }))
        // Пересекающиеся — по колонкам.
        val cols = ArrayList<Int>()
        var cluster = ArrayList<Block>()
        var clusterEnd = -1
        fun place() { val n = (cluster.maxOfOrNull { it.col } ?: 0) + 1; cluster.forEach { it.n = n }; cluster = ArrayList() }
        for (b in list) {
            if (b.a >= clusterEnd && cluster.isNotEmpty()) { place(); cols.clear() }
            var c = cols.indexOfFirst { it <= b.a }
            if (c < 0) { c = cols.size; cols += 0 }
            cols[c] = b.b; b.col = c; cluster += b; clusterEnd = maxOf(clusterEnd, b.b)
        }
        if (cluster.isNotEmpty()) place()
        blocks = ArrayList(list)
        if (flashId != null) flashUntil = System.currentTimeMillis() + 1800
        requestLayout(); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), yOf(24 * 60) + topPad)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); layoutBlocks() }

    private fun layoutBlocks() {
        val avail = width - left - Ui.dpf(context, 10f)
        for (b in blocks) {
            val w = avail / b.n
            b.r.set(left + Ui.dpf(context, 4f) + w * b.col, yOf(b.a).toFloat() + 1, left + Ui.dpf(context, 4f) + w * (b.col + 1) - Ui.dpf(context, 3f),
                maxOf(yOf(b.b).toFloat() - 1, yOf(b.a) + Ui.dpf(context, 22f)))
        }
    }

    fun blockRect(id: String): RectF? = blocks.firstOrNull { it.o.item.id == id }?.r?.let { RectF(it) }

    override fun onDraw(c: Canvas) {
        if (blocks.isNotEmpty() && blocks[0].r.isEmpty) layoutBlocks()
        for (h in 0..24) {
            val y = yOf(h * 60).toFloat()
            c.drawLine(left, y, width.toFloat(), y, line)
            c.drawText(if (h == 24) "0:00" else "$h:00", left - Ui.dpf(context, 8f), y + label.textSize / 3, label)
        }
        val pad = Ui.dpf(context, 6f)
        for (b in blocks) {
            val o = b.o
            val col = Plan.cal(o.item.cal).color
            val r = b.r
            fill.color = (col and 0x00FFFFFF) or (if (o.done) 0x18000000 else 0x33000000)
            c.drawRoundRect(r, Ui.dpf(context, 6f), Ui.dpf(context, 6f), fill)
            fill.color = col
            c.drawRect(r.left, r.top, r.left + Ui.dpf(context, 3.5f), r.bottom, fill)
            var x = r.left + Ui.dpf(context, 3.5f) + pad
            if (o.item.isTask) {
                val s = Ui.dpf(context, 14f)
                val bx = RectF(x, r.top + pad, x + s, r.top + pad + s)
                stroke.color = col
                c.drawRoundRect(bx, 3f, 3f, stroke)
                if (o.done) { c.drawLine(bx.left + 3, bx.centerY(), bx.centerX() - 1, bx.bottom - 3, stroke); c.drawLine(bx.centerX() - 1, bx.bottom - 3, bx.right - 2, bx.top + 2, stroke) }
                x += s + pad
            }
            val tw = r.right - x - pad
            if (tw <= 0) continue
            titleP.color = if (o.done) Ui.MUTED else Ui.INK
            titleP.isStrikeThruText = o.done
            val time = Plan.timeText(o) + if (o.item.place.isNotEmpty()) " · " + o.item.place else ""
            val short = r.height() < Ui.dpf(context, 40f)
            val first = if (short) o.item.title + "  " + time else o.item.title
            c.drawText(TextUtils.ellipsize(first, titleP, tw, TextUtils.TruncateAt.END).toString(), x, r.top + pad + titleP.textSize * 0.95f, titleP)
            if (!short) {
                smallP.color = Ui.INK2
                c.drawText(TextUtils.ellipsize(time, smallP, tw, TextUtils.TruncateAt.END).toString(), x, r.top + pad + titleP.textSize + smallP.textSize * 1.2f, smallP)
            }
            if (o.item.id == flashId && System.currentTimeMillis() < flashUntil) {
                stroke.color = Ui.AMBER
                c.drawRoundRect(r, Ui.dpf(context, 6f), Ui.dpf(context, 6f), stroke)
                postInvalidateDelayed(300)
            }
        }
        if (isToday) {
            val n = LocalTime.now()
            val y = yOf(n.hour * 60 + n.minute).toFloat()
            fill.color = Ui.PEN_RED
            c.drawLine(left, y, width.toFloat(), y, fill.apply { strokeWidth = Ui.dpf(context, 1.5f) })
            c.drawCircle(left, y, Ui.dpf(context, 4f), fill)
        }
        if (flashId != null && System.currentTimeMillis() >= flashUntil) flashId = null
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; moved = false }
            MotionEvent.ACTION_MOVE -> if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
            MotionEvent.ACTION_UP -> if (!moved) {
                performClick()
                val hit = blocks.lastOrNull { it.r.contains(e.x, e.y) }
                if (hit != null) {
                    val box = hit.o.item.isTask && e.x < hit.r.left + Ui.dpf(context, 30f)
                    page.onBlock(hit.o, box)
                } else if (e.x > left) page.onEmpty(((e.y - topPad) * 60 / hourH).toInt())
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
