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
import android.text.Editable
import android.text.TextWatcher
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Ведомости на экране доски (ТЗ «Учёт ИГС», шаг 2): создать (вид, номер, кому), собрать схемы (отбор, выделенные строки,
 * вручную — с созданием строки), передать (статусы и даты всем разом), ответы по частям (касание «Ответ» у строки, лассо —
 * всем выделенным), архив, история схемы, опись в Excel.
 */
class VedomUi(private val a: BoardActivity) {
    private val b: Board get() = a.board
    private val v: BoardView get() = a.view
    private fun dp(x: Float) = Ui.dp(a, x)

    /** Кто правит (для истории): имя и фамилия из профиля. */
    private fun who() = Profile.name(a)

    /** Дата ответа для отметок (пусто — сегодня): заказчик вернул пачку вчера — поставили дату один раз, отмечаем строки. */
    var answerDate = ""
    private fun ansDate() = answerDate.ifEmpty { IdTables.today() }

    internal var card: Dialog? = null
    internal var popup: PopupWindow? = null

    // ---------------------------------------------------------------- список (архив)

    /** «Ведомости»: новая, черновики, ждут ответа, списки, закрытые (архив). Касание — показать на доске. */
    fun listDialog() {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(6f), dp(18f), dp(8f)) }
        var dialog: AlertDialog? = null
        box.addView(Ui.pill(a, "+ Новая ведомость", Color.WHITE, Ui.ACCENT).apply {
            tag = "vlist_new"; gravity = Gravity.CENTER; minHeight = dp(48f)
            setOnClickListener { dialog?.dismiss(); create(null) }
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val today = IdTables.today()
        val groups = listOf(
            "Ждут ответа" to b.vedoms.filter { Vedoms.isOpen(b, it) }.sortedBy { it.given },
            "В сборке" to b.vedoms.filter { it.isDraft && it.kind != Vedoms.LIST },
            "Списки" to b.vedoms.filter { it.kind == Vedoms.LIST },
            "Закрыты — архив" to b.vedoms.filter { Vedoms.isClosed(b, it) }.sortedByDescending { it.given },
        )
        var n = 0
        for ((title, list) in groups) {
            if (list.isEmpty()) continue
            box.addView(Ui.text(a, title, 14f, Ui.MUTED, bold = true).apply { setPadding(0, dp(14f), 0, dp(4f)) })
            for (x in list) {
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.rounded(a, Color.WHITE, Ui.RULE, 12f)
                    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                    minimumHeight = dp(48f)
                    tag = "vlist_${n++}"; isClickable = true
                    setOnClickListener { dialog?.dismiss(); goTo(x) }
                    addView(Ui.text(a, Vedoms.title(x) + "  ·  " + Vedoms.state(b, x), 15f, Board.statusFg(Vedoms.kindColor(x.kind)), bold = true))
                    addView(Ui.text(a, listOf(x.to, Vedoms.summary(b, x, today)).filter { it.isNotBlank() }.joinToString(" · "), 13f, Ui.INK2))
                }
                box.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
            }
        }
        if (b.vedoms.isEmpty()) box.addView(Ui.text(a, "Ведомостей пока нет. Ведомость — пачка схем, которую отдают АН, заказчику или в ПТО: " +
            "собрали схемы («Сборка» — лежат на столе) → «Унесли» — всем разом статус и дата → отмечаете ответы по частям, в разные дни.", 14f, Ui.INK2).apply { setPadding(0, dp(12f), 0, 0) })
        dialog = AlertDialog.Builder(a).setTitle("Ведомости").setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton("Закрыть", null).show()
    }

    /** Показать ведомость на доске и выделить. */
    fun goTo(x: BoardVedom) {
        val r = v.vedomDraw.bounds(b, x, RectF())
        v.fitRect(r.left, r.top, r.right, r.bottom)
        v.select(listOf(x))
    }

    // ---------------------------------------------------------------- создание

    /**
     * Новая ведомость: вид → номер и кому → встаёт на доске (рядом с выделенным или посреди экрана) → сбор схем.
     * recs — схемы, которые сразу положить (выделенные строки таблицы).
     */
    fun create(recs: List<BoardRec>?) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(4f), dp(16f), dp(8f)) }
        var dialog: AlertDialog? = null
        val about = mapOf(
            Vedoms.CUST to "схемам — «На подписи у заказчика» и дата «Отдал заказчику»; ответы: подписано (→ «Сдано в ПТО») или замечания",
            Vedoms.AN to "схемам — «На согласовании АН»; ответы: согласовано (→ к заказчику), не согласовано, замечания",
            Vedoms.PTO to "схемам — «Сдано в ПТО» и дата; закрывается сразу",
            Vedoms.LIST to "просто список: «что делать завтра», «что проверить» — статусы не меняет",
        )
        for ((i, k) in Vedoms.KINDS.withIndex()) {
            val c = Vedoms.kindColor(k)
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = Ui.dpf(a, 12f); setColor(Board.statusBg(c)); setStroke(dp(1.5f), Board.statusFg(c)) }
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                minimumHeight = dp(56f)
                tag = "vnew_kind_$i"; isClickable = true
                setOnClickListener { dialog?.dismiss(); details(k, recs) }
                addView(Ui.text(a, Vedoms.kindName(k), 16f, Board.statusFg(c), bold = true))
                addView(Ui.text(a, about[k].orEmpty(), 13f, Ui.INK2))
            }
            box.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
        }
        dialog = AlertDialog.Builder(a).setTitle(if (recs.isNullOrEmpty()) "Новая ведомость" else "Новая ведомость · ${Ui.plural(recs.size, "схема", "схемы", "схем")}")
            .setView(ScrollView(a).apply { addView(box) }).setNegativeButton("Отмена", null).show()
    }

    private fun details(kind: String, recs: List<BoardRec>?) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(4f), dp(20f), 0) }
        fun label(s: String) = Ui.text(a, s, 13f, Ui.MUTED, bold = true).apply { setPadding(0, dp(10f), 0, dp(2f)) }
        val num = EditText(a).apply { setText(Vedoms.nextNum(b, kind)); setSelection(text.length); isSingleLine = true; tag = "vnew_num" }
        val to = EditText(a).apply {
            isSingleLine = true; tag = "vnew_to"
            hint = when (kind) { Vedoms.AN -> "например: ГИП Иванов"; Vedoms.CUST -> "например: технадзор заказчика"; Vedoms.PTO -> "например: ПТО"; else -> "можно не заполнять" }
            setText(b.vedoms.lastOrNull { it.kind == kind && it.to.isNotBlank() }?.to.orEmpty())
        }
        box.addView(label("Номер")); box.addView(num)
        box.addView(label(if (kind == Vedoms.LIST) "Название / для кого" else "Кому")); box.addView(to)
        val sugg = b.vedoms.filter { it.kind == kind }.map { it.to.trim() }.filter { it.isNotEmpty() }.distinct().take(6)
        if (sugg.size > 1) box.addView(Ui.FlowRow(a).apply {
            hGap = dp(6f); vGap = dp(6f); setPadding(0, dp(6f), 0, 0)
            for (s in sugg) addView(Ui.chip(a, s, Ui.INK, Ui.DESK).apply { setOnClickListener { to.setText(s) } })
        })
        AlertDialog.Builder(a).setTitle(Vedoms.kindName(kind))
            .setView(box)
            .setPositiveButton("Создать") { _, _ ->
                val numS = num.text.toString().trim().ifEmpty { Vedoms.nextNum(b, kind) }
                val toS = to.text.toString().trim()
                val s = newScale()
                val (w, h) = ghost(s, recs?.size ?: 6)
                // Место выбирает Владимир (0.25): двигает доску, держит палец или перо → «Вставить ведомость сюда».
                a.startPlacing("Ведомость $numS: двигайте доску, подержите палец или перо там, где поставить", w, h,
                    onPlace = { x, y -> makeVedom(kind, numS, toS, recs, x, y, s) })
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun makeVedom(kind: String, numS: String, toS: String, recs: List<BoardRec>?, x: Float, y: Float, s: Float) {
        a.pushUndo()
        val nv = BoardVedom(x, y, s, kind, numS)
        nv.made = IdTables.today()
        nv.to = toS
        b.vedoms += nv
        // Схемы — по номеру (пока искали место, доска могла смениться: отмена, правки коллег).
        val now = recs.orEmpty().mapNotNull { r -> b.recs.firstOrNull { it.id == r.id } }
        val n = if (now.isEmpty()) 0 else Vedoms.add(b, nv, now, who())
        a.changed()
        v.clearSelection()
        if (recs.isNullOrEmpty()) collectDialog(nv)
        else {
            a.hint("Ведомость ${nv.num}: ${Ui.plural(n, "схема", "схемы", "схем")} — «Сборка». Унесёте — «${Vedoms.giveLabel(kind)} →»", 2500)
            warnWaiting(nv, now)
        }
    }

    // ---------------------------------------------------------------- сборка из таблицы (0.25)

    /** Открытое окно «Сборка — ведомость от …?» (для проверок). */
    internal var asmAsk: AlertDialog? = null

    private fun declinedKey() = "asmNo_" + b.id

    /** «Не сегодня» сказано сегодня на этой доске — больше не спрашиваем до завтра. */
    private fun declinedToday(day: String) = a.getSharedPreferences("igs", Context.MODE_PRIVATE).getString(declinedKey(), "") == day

    /**
     * В таблице поставили «Сборку» (Владимир, 13.11.2026): есть ведомость сегодняшней сборки — схемы ложатся в неё сами;
     * нет — первая «Сборка» дня: предложить завести ведомость от сегодня и положить в неё все схемы в «Сборке», которых нет
     * в ведомостях.
     */
    fun onAssembly(recs: List<BoardRec>) {
        val list = recs.filter { r -> b.recs.any { it === r } && r.igs == IdTables.IGS_ASSEMBLY && Vedoms.waitingElsewhere(b, r, null).isEmpty() }
        if (list.isEmpty()) return
        val today = IdTables.today()
        Vedoms.assemblyOf(b, today)?.let { x ->
            // Та же правка, что и статус (снимок отмены уже сделан) — «Отменить» уберёт и статус, и позицию.
            val n = Vedoms.add(b, x, list, who())
            a.changed()
            if (n > 0) a.hint("В ведомость ${x.num} от ${IdTables.dateShort(x.made)}: +${Ui.plural(n, "схема", "схемы", "схем")} · всего ${Vedoms.items(b, x).size}", 2200)
            return
        }
        if (declinedToday(today) || asmAsk?.isShowing == true) return
        val loose = Vedoms.looseAssembly(b)
        val more = loose.size - list.size
        val anName = IdTables.status(b, BoardStatus.ID, IdTables.IGS_AN).name
        val d = AlertDialog.Builder(a)
            .setTitle("Сборка — ведомость от ${IdTables.dateLong(today)}?")
            .setMessage("Первая «Сборка» сегодня. Завести ведомость сборки от сегодняшнего дня и положить в неё " +
                (if (more > 0) "все схемы в «Сборке» без ведомости — ${loose.size}" else Ui.plural(loose.size, "схему", "схемы", "схем")) +
                "? Дальше сегодня «Сборка» в таблице — схема сама ляжет в эту ведомость. Какие идут на АН — в ведомости коснитесь " +
                "«ИГС сейчас» → «$anName». Унесли — смените состояние ведомости на «На подписи у заказчика».")
            .setPositiveButton("Создать") { _, _ -> placeAssembly(Vedoms.CUST, today) }
            .setNegativeButton("Не сегодня") { _, _ ->
                a.getSharedPreferences("igs", Context.MODE_PRIVATE).edit().putString(declinedKey(), today).apply()
                a.hint("Сегодня не спрашиваю. Ведомость можно собрать кнопкой «Ведомости»", 2200)
            }
            .create()
        asmAsk = d
        d.show()
    }

    /** Размер новой ведомости — как у таблиц и ведомостей доски. */
    private fun newScale(): Float {
        val sizes = (b.tables.map { it.scale } + b.vedoms.map { it.scale }).sorted()
        return if (sizes.isNotEmpty()) sizes[sizes.size / 2] else a.resources.displayMetrics.density / b.camZ
    }

    /** Контур новой ведомости при выборе места: примерная ширина и высота (шапка + n строк). */
    private fun ghost(s: Float, rows: Int) = (900f * s) to ((58f + 26f + 30f * rows.coerceIn(1, 8) + 30f) * s)

    /**
     * Ведомость сегодняшней сборки (0.25): место — выбирает Владимир («схемы из разных таблиц — ведомость туда, где удобно»):
     * двигает доску, держит палец или перо → «Вставить ведомость сюда». В неё — все схемы в «Сборке» без ведомости.
     */
    private fun placeAssembly(kind: String, today: String) {
        val s = newScale()
        val n = Vedoms.looseAssembly(b).size
        val (w, h) = ghost(s, n)
        a.startPlacing("Ведомость ${Vedoms.nextNum(b, kind)} от ${IdTables.dateShort(today)}: двигайте доску, подержите палец или перо там, где поставить",
            w, h, onPlace = { x, y -> createAssembly(kind, today, x, y, s) },
            onCancel = { a.hint("Ведомость не создана — схемы остались в «Сборке». Следующая «Сборка» предложит снова", 2600) })
    }

    private fun createAssembly(kind: String, today: String, x: Float, y: Float, s: Float) {
        val loose = Vedoms.looseAssembly(b)
        if (loose.isEmpty()) { a.hint("Схем в «Сборке» без ведомости уже нет", 1800); return }
        a.pushUndo()
        val nv = BoardVedom(x, y, s, kind, Vedoms.nextNum(b, kind))
        nv.made = today
        nv.to = b.vedoms.lastOrNull { it.kind == kind && it.to.isNotBlank() }?.to.orEmpty()
        b.vedoms += nv
        val n = Vedoms.add(b, nv, loose, who())
        a.changed()
        a.hint("Ведомость ${nv.num} от ${IdTables.dateShort(today)}: ${Ui.plural(n, "схема", "схемы", "схем")}. Сегодняшние «Сборки» лягут в неё сами; " +
            "унесли — нажмите «Сборка от …» в шапке ведомости → «${Vedoms.giveLabel(kind)}»", 5000)
    }

    // ---------------------------------------------------------------- касания

    /** Касание ведомости: «Ответ» — список ответов у ячейки; остальная строка: пальцем — карточка, пером — схема в таблице. */
    fun onTap(x: BoardVedom, h: VedomHit, pen: Boolean) {
        when (h.part) {
            VedomHit.ADD -> collectDialog(x)
            VedomHit.GIVE -> giveDialog(x)
            VedomHit.STATE -> stateDialog(x)
            VedomHit.CELL -> {
                val i = h.item ?: return
                if (h.col == VedomDraw.RES || h.col == VedomDraw.RD) { resultPopup(x, listOf(i)); return }
                // В сборке «ИГС сейчас»: «Сборка» (заказчику) или «На согласовании АН» (сначала на АН) — касание, список рядом.
                if (h.col == TableCol.IGS && x.isDraft && x.kind == Vedoms.CUST) { routePopup(x, i); return }
                if (pen) showInTable(x, i) else itemCard(x, i)
            }
            else -> v.select(listOf(x))
        }
    }

    /** Касание пером (инструмент рисования или письма) по ведомости. true — касание забрали. */
    fun penTap(px: Float, py: Float): Boolean {
        val x = b.vedoms.lastOrNull { v.vedomDraw.bounds(b, it, RectF()).contains(px, py) } ?: return false
        val h = v.vedomDraw.hit(b, x, px, py) ?: return false
        if (h.part == VedomHit.CELL && h.col != VedomDraw.RES && h.col != VedomDraw.RD && a.activeTool != BoardActivity.Tool.WRITE) return false
        if (h.part == VedomHit.TITLE || h.part == VedomHit.HEAD) { v.select(listOf(x)); return true }
        onTap(x, h, pen = true)
        return true
    }

    /** Подержали строку ведомости — меню позиции. */
    fun onLongPress(x: BoardVedom, h: VedomHit) {
        val i = h.item ?: return
        val rec = Vedoms.rec(b, i)
        val items = arrayListOf("Карточка", "Показать в таблице", "История схемы", "Выше", "Ниже", "Убрать из ведомости")
        AlertDialog.Builder(a).setTitle("${x.num} · ${Vedoms.labelText(b, i)}")
            .setItems(items.toTypedArray()) { _, k ->
                when (k) {
                    0 -> itemCard(x, i)
                    1 -> showInTable(x, i)
                    2 -> if (rec != null) historyDialog(rec) else a.hint("Строки в таблице уже нет", 1800)
                    3 -> move(x, i, -1)
                    4 -> move(x, i, 1)
                    5 -> removeItems(listOf(i))
                }
            }.show()
    }

    private fun move(x: BoardVedom, i: BoardVItem, d: Int) {
        val list = Vedoms.items(b, x)
        val k = list.indexOf(i)
        val j = k + d
        if (k < 0 || j !in list.indices) return
        a.pushUndo()
        val o = list[j].o
        list[j].o = i.o; i.o = o
        a.changed()
    }

    fun removeItems(items: List<BoardVItem>) {
        if (items.isEmpty()) return
        val passed = items.any { Vedoms.vedom(b, it.v)?.isDraft == false }
        fun go() {
            a.pushUndo()
            // Из сборки: «Сборка» → статус, что был до неё.
            for ((vid, its) in items.groupBy { it.v }) Vedoms.vedom(b, vid)?.let { Vedoms.unassemble(b, it, its, who()) }
            b.vitems.removeAll { i -> items.any { it === i } }
            v.clearSelection()
            a.changed()
            a.hint("Убрано из ведомости: ${items.size}" + if (passed) ". Статусы схем не менялись" else "", 2000)
        }
        if (!passed && items.size == 1) { go(); return }
        AlertDialog.Builder(a).setMessage("Убрать из ведомости ${Ui.plural(items.size, "схему", "схемы", "схем")}? " +
            (if (passed) "Статусы и даты схем не меняются." else "У схем вернётся статус, что был до «Сборки».") + " «Отменить» вернёт.")
            .setPositiveButton("Убрать") { _, _ -> go() }.setNegativeButton("Отмена", null).show()
    }

    /**
     * Кнопка состояния в шапке ведомости: «Сборка» (схемы лежат на столе) или «На подписи у заказчика» (унесли; для АН —
     * «На согласовании АН», для ПТО — «Сдано в ПТО»). Из «Сборки» — окно с датой; назад в «Сборку» — пока нет ответов.
     */
    fun stateDialog(x: BoardVedom) {
        if (x.kind == Vedoms.LIST) return
        if (Vedoms.isClosed(b, x) && x.kind != Vedoms.PTO) { a.hint("Ведомость ${x.num} закрыта — ответы получены по всем схемам", 2000); return }
        var dlg: AlertDialog? = null
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(4f), dp(16f), dp(8f)) }
        fun option(title: String, sub: String, color: Int, cur: Boolean, tag: String, f: () -> Unit) {
            val fg = Board.statusFg(color)
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = Ui.dpf(a, 12f); setColor(Board.statusBg(color)); setStroke(dp(if (cur) 3f else 1.5f), fg) }
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                minimumHeight = dp(56f)
                this.tag = tag; isClickable = true
                setOnClickListener { dlg?.dismiss(); if (!cur) f() }
                addView(Ui.text(a, title + if (cur) "  — сейчас" else "", 16f, fg, bold = true))
                addView(Ui.text(a, sub, 13f, Ui.INK2))
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
        }
        val asm = IdTables.status(b, BoardStatus.ID, IdTables.IGS_ASSEMBLY)
        val gv = IdTables.status(b, BoardStatus.ID, Vedoms.giveStatus(x.kind))
        option("Сборка", "схемы готовы и лежат на столе — у схем статус «${asm.name}»", asm.color, x.isDraft, "vstate_asm") {
            if (!Vedoms.canUngive(b, x)) { a.hint("Уже есть ответы — вернуть в сборку нельзя", 2000); return@option }
            AlertDialog.Builder(a).setTitle("Вернуть ${x.num} в сборку?")
                .setMessage("Схемы снова «${asm.name}»" + (if (x.kind == Vedoms.CUST) ", дата «Отдал заказчику» убирается" else if (x.kind == Vedoms.PTO) ", дата «Сдано в ПТО» убирается" else "") + ". «Отменить» вернёт.")
                .setPositiveButton("Вернуть") { _, _ ->
                    a.pushUndo(); Vedoms.ungive(b, x, who()); a.changed(); a.buildSelectionBar()
                    a.hint("${x.num} — снова сборка", 1500)
                }.setNegativeButton("Отмена", null).show()
        }
        option(Vedoms.giveLabel(x.kind), when (x.kind) {
            Vedoms.CUST -> "унесли заказчику — у схем «${gv.name}» и дата «Отдал заказчику»"
            Vedoms.AN -> "унесли на АН — у схем «${gv.name}»"
            else -> "отдали в ПТО — у схем «${gv.name}» и дата «Сдано в ПТО»"
        } + if (!x.isDraft) " · ${IdTables.dateLong(x.given)}" else " · дата — сегодня, ${IdTables.dateLong(IdTables.today())}", gv.color, !x.isDraft, "vstate_give") { giveNow(x, IdTables.today()) }
        if (x.isDraft) box.addView(Ui.text(a, "Унесли в другой день — выбрать дату", 14f, Ui.ACCENT, bold = true).apply {
            tag = "vstate_date"; isClickable = true; setPadding(dp(6f), dp(14f), dp(6f), dp(4f))
            setOnClickListener { dlg?.dismiss(); giveDialog(x) }
        })
        dlg = AlertDialog.Builder(a).setTitle("${Vedoms.title(x)} — состояние").setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton("Закрыть", null).show()
    }

    // ---------------------------------------------------------------- сбор

    /**
     * «+ схемы»: отбор по шифру, разделу, статусу ИГС, исполнителю, тексту → отметить галочками → «Добавить».
     * Схема уже ждёт ответа в другой открытой ведомости — не отмечена и помечена.
     */
    fun collectDialog(x: BoardVedom) {
        val tSel = LinkedHashSet<String>()
        val fSel = LinkedHashSet<String>()
        val iSel = LinkedHashSet<String>(when (x.kind) {
            Vedoms.AN, Vedoms.CUST -> listOf(IdTables.IGS_WORK, IdTables.IGS_BACK)
            else -> emptyList()
        })
        val wSel = LinkedHashSet<String>()
        val checked = HashMap<String, Boolean>()
        val have = b.vitems.filter { it.v == x.id }.map { it.r }.toSet()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(4f), dp(16f), dp(8f)) }
        val listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val count = Ui.text(a, "", 15f, Ui.INK, bold = true).apply { tag = "col_count"; setPadding(0, dp(10f), 0, dp(4f)) }
        val text = EditText(a).apply { isSingleLine = true; hint = "поиск: захватка, конструкция, вид работ"; tag = "col_text" }
        var shown: List<BoardRec> = emptyList()

        fun matches(r: BoardRec): Boolean {
            val t = IdTables.tableOf(b, r) ?: return false
            if (tSel.isNotEmpty() && t.id !in tSel) return false
            if (fSel.isNotEmpty() && b.sectionOf(t)?.id !in fSel) return false
            if (iSel.isNotEmpty() && r.igs !in iSel) return false
            if (wSel.isNotEmpty() && r.who.trim() !in wSel) return false
            val q = text.text.toString().trim().lowercase(Locale.ROOT)
            if (q.isNotEmpty()) {
                val hay = (IdTables.fullTitle(b, t) + " " + r.uch + " " + r.zah + " " + r.con + " " + r.work + " " + r.who + " " + r.note).lowercase(Locale.ROOT)
                if (!q.split(' ').filter { it.isNotBlank() }.all { it in hay }) return false
            }
            return true
        }
        fun updCount() { count.text = "Отмечено: " + shown.count { checked[it.id] == true } + " из " + shown.size }
        fun render() {
            listBox.removeAllViews()
            shown = b.tables.sortedWith { p, q -> IdTables.naturalCompare(IdTables.fullTitle(b, p), IdTables.fullTitle(b, q)) }
                .flatMap { t -> IdTables.allRows(b, t) }.filter { matches(it) }
            for ((n, r) in shown.withIndex()) {
                if (n >= 400) { listBox.addView(Ui.text(a, "…и ещё ${shown.size - 400} — уточните отбор", 13f, Ui.MUTED)); break }
                val t = IdTables.tableOf(b, r)!!
                val inThis = r.id in have
                val elsewhere = if (inThis) emptyList() else Vedoms.waitingElsewhere(b, r, x)
                if (r.id !in checked) checked[r.id] = !inThis && elsewhere.isEmpty()
                val st = IdTables.status(b, BoardStatus.ID, r.igs)
                val cb = CheckBox(a).apply {
                    tag = "col_row_$n"
                    isEnabled = !inThis
                    isChecked = checked[r.id] == true && !inThis
                    val line = listOf(IdTables.fullTitle(b, t), r.uch, t.colName(TableCol.ZAH).lowercase().take(5) + ". " + r.zah, r.con, r.work).filter { it.isNotBlank() }.joinToString(" · ")
                    this.text = line + "  [" + st.name + "]" + when {
                        inThis -> "  — уже в этой ведомости"
                        elsewhere.isNotEmpty() -> "  ⚠ уже в ведомости " + elsewhere.joinToString { it.num }
                        else -> ""
                    }
                    setTextColor(if (elsewhere.isNotEmpty()) Ui.WARN else Ui.INK)
                    setOnCheckedChangeListener { _, on -> checked[r.id] = on; updCount() }
                }
                listBox.addView(cb)
            }
            if (shown.isEmpty()) listBox.addView(Ui.text(a, if (b.tables.isEmpty()) "На доске нет таблиц ИД — впишите схемы вручную" else "По отбору схем нет", 14f, Ui.MUTED)
                .apply { setPadding(0, dp(6f), 0, dp(6f)) })
            updCount()
        }
        fun group(title: String, items: List<Pair<String, String>>, set: MutableSet<String>, tag: String) {
            if (items.isEmpty()) return
            box.addView(Ui.text(a, title, 13f, Ui.MUTED, bold = true).apply { setPadding(0, dp(8f), 0, dp(4f)) })
            val flow = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
            for ((i, p) in items.withIndex()) {
                val (id, name) = p
                val chip = Ui.pill(a, name, Ui.INK, Ui.DESK).apply { this.tag = "${tag}_$i" }
                fun paint() { val on = id in set; chip.background = Ui.rounded(a, if (on) Ui.ACCENT else Ui.DESK, null, 999f); chip.setTextColor(if (on) Color.WHITE else Ui.INK) }
                paint()
                chip.setOnClickListener { if (!set.remove(id)) set += id; paint(); render() }
                flow.addView(chip)
            }
            box.addView(flow)
        }
        box.addView(Ui.text(a, "Ничего не выбрано в группе — значит «все».", 12f, Ui.MUTED))
        group("Таблица", b.tables.map { it.id to IdTables.fullTitle(b, it).ifEmpty { "без шифра" } }.sortedWith { p, q -> IdTables.naturalCompare(p.second, q.second) }, tSel, "col_t")
        group("Раздел", b.frames.filter { f -> b.tables.any { b.sectionOf(it) === f } }.map { it.id to it.title }, fSel, "col_f")
        group("ИГС", b.statuses(BoardStatus.ID).map { it.id to it.name }, iSel, "col_igs")
        group("Исполнитель", IdTables.liveRecs(b).map { it.who.trim() }.filter { it.isNotEmpty() }.distinct().sorted().map { it to it }, wSel, "col_who")
        box.addView(text)
        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { render() }
        })
        val bulk = LinearLayout(a)
        bulk.addView(Ui.pill(a, "Отметить все", Ui.INK, Ui.DESK).apply { tag = "col_all"; setOnClickListener { for (r in shown) if (r.id !in have) checked[r.id] = true; render() } })
        bulk.addView(Ui.pill(a, "Снять все", Ui.INK, Ui.DESK).apply { tag = "col_none"; setOnClickListener { for (r in shown) checked[r.id] = false; render() } },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(6f) })
        box.addView(count)
        box.addView(bulk)
        box.addView(listBox)
        render()
        val d = AlertDialog.Builder(a).setTitle("Схемы в ведомость ${x.num}")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Добавить") { _, _ ->
                val pick = shown.filter { checked[it.id] == true && it.id !in have }
                if (pick.isEmpty()) { a.hint("Ничего не отмечено", 1500); return@setPositiveButton }
                a.pushUndo()
                val n = Vedoms.add(b, x, pick, who())
                a.changed()
                a.hint("В ведомость ${x.num} добавлено: ${Ui.plural(n, "схема", "схемы", "схем")}" + if (x.isDraft && x.kind != Vedoms.LIST) ". Сборка — унесёте: «${Vedoms.giveLabel(x.kind)} →»" else "", 2500)
            }
            .setNeutralButton("Вписать вручную", null)
            .setNegativeButton("Отмена", null)
            .create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { d.dismiss(); manualDialog(x) } }
        d.show()
    }

    /** Схемы уже ждут ответа в других открытых ведомостях — предупредить (не запрет). */
    private fun warnWaiting(x: BoardVedom, recs: List<BoardRec>) {
        val lines = recs.mapNotNull { r ->
            val o = Vedoms.waitingElsewhere(b, r, x)
            if (o.isEmpty()) null else "• " + listOf(IdTables.tableOf(b, r)?.let { IdTables.fullTitle(b, it) }.orEmpty(), r.uch, r.zah, r.work).filter { it.isNotBlank() }.joinToString(" · ") +
                " — " + o.joinToString { it.num }
        }
        if (lines.isEmpty()) return
        AlertDialog.Builder(a).setTitle("Уже в других ведомостях")
            .setMessage("Эти схемы уже в других ведомостях (в сборке или ждут ответа):\n" + lines.take(20).joinToString("\n") + if (lines.size > 20) "\n…" else "")
            .setPositiveButton("Понятно", null).show()
    }

    /**
     * Вписать вручную: шифр + (участок) + захватка + вид работ (+ тип конструкции). Схема есть — добавляется ссылкой; нет — предложим
     * создать строку в таблице шифра; нет и таблицы — предложим создать таблицу. Без подтверждения ничего не создаётся.
     */
    fun manualDialog(x: BoardVedom, cipher0: String = "", zah0: String = "") {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(4f), dp(20f), 0) }
        fun label(s: String) = Ui.text(a, s, 13f, Ui.MUTED, bold = true).apply { setPadding(0, dp(10f), 0, dp(2f)) }
        val cipher = EditText(a).apply { setText(cipher0); isSingleLine = true; hint = "например: КЖ 6.1.1"; tag = "man_cipher" }
        val uch = EditText(a).apply { isSingleLine = true; hint = "например: LM1 — можно не заполнять"; tag = "man_uch" }
        val zah = EditText(a).apply { setText(zah0); isSingleLine = true; hint = "например: 5 или Лоток №2"; tag = "man_zah" }
        val con = EditText(a).apply { isSingleLine = true; hint = "можно не заполнять"; tag = "man_con" }
        box.addView(label("Шифр")); box.addView(cipher)
        if (b.tables.isNotEmpty()) box.addView(Ui.FlowRow(a).apply {
            hGap = dp(6f); vGap = dp(6f); setPadding(0, dp(6f), 0, 0)
            for ((i, n) in b.tables.map { IdTables.fullTitle(b, it) }.sortedWith { p, q -> IdTables.naturalCompare(p, q) }.withIndex())
                addView(Ui.chip(a, n.ifEmpty { "без шифра" }, Ui.INK, Ui.DESK).apply { tag = "man_t_$i"; setOnClickListener { cipher.setText(n) } })
        })
        box.addView(label("Участок")); box.addView(uch)
        box.addView(label("Захватка")); box.addView(zah)
        box.addView(label("Тип конструкции")); box.addView(con)
        box.addView(label("Вид работ — можно несколько; ничего — все схемы захватки"))
        val works = LinkedHashSet<String>()
        val flow = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f) }
        val all = (IdTables.WORKS + IdTables.suggestions(b, TableCol.WORK)).distinct()
        fun paint(p: TextView, on: Boolean) { p.background = Ui.rounded(a, if (on) Ui.ACCENT else Ui.DESK, null, 999f); p.setTextColor(if (on) Color.WHITE else Ui.INK) }
        for ((i, w) in all.withIndex()) {
            val p = Ui.pill(a, w, Ui.INK, Ui.DESK).apply { tag = "man_work_$i" }
            paint(p, false)
            p.setOnClickListener { if (!works.remove(w)) works += w; paint(p, w in works) }
            flow.addView(p)
        }
        box.addView(flow)
        if (Input.stylusSeen(a)) box.addView(Ui.text(a, "Номера пером списком — позже (ТЗ, шаг 5).", 12f, Ui.MUTED).apply { setPadding(0, dp(8f), 0, 0) })
        fun go(then: () -> Unit) {
            val c = cipher.text.toString().trim()
            val z = zah.text.toString().trim()
            if (c.isEmpty() || z.isEmpty()) { a.hint("Впишите шифр и захватку", 1800); return }
            resolveManual(x, c, z, con.text.toString().trim(), works.toList(), uch.text.toString().trim(), then)
        }
        val d = AlertDialog.Builder(a).setTitle("Вписать схему · ${x.num}")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Добавить", null)
            .setNeutralButton("Добавить и следующую", null)
            .setNegativeButton("Готово", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { go { d.dismiss() } }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                go { zah.setText(IdTables.nextZah(zah.text.toString().trim())); zah.setSelection(zah.text.length) }
            }
        }
        d.show()
    }

    /** Найти схемы по шифру/участку/захватке/конструкции/видам работ; недостающее — создать после подтверждения. Пустой участок — любой. */
    internal fun resolveManual(x: BoardVedom, cipher: String, zah: String, con: String, works: List<String>, uch: String = "", then: () -> Unit) {
        // Шифр — раздел, «раздел · подраздел» или название таблицы: в разделе может быть несколько таблиц-подразделов.
        val key = IdTables.norm(cipher)
        val cands = b.tables.filter { IdTables.norm(IdTables.fullTitle(b, it)) == key || IdTables.norm(it.title) == key ||
            IdTables.norm(b.sectionOf(it)?.title.orEmpty()) == key }
        val t = cands.firstOrNull()
        val label = listOf(cipher, uch, "захв. $zah", con, works.joinToString(" + ")).filter { it.isNotBlank() }.joinToString(" · ")
        fun createRows(table: BoardTable, list: List<String>, newTable: Boolean) {
            a.pushUndo()
            if (newTable) b.tables += table
            var o = IdTables.endOrder(b, table)
            val made = ArrayList<BoardRec>()
            for (w in list.ifEmpty { listOf("") }) {
                val r = BoardRec(table.id, o).apply { this.uch = uch; this.zah = zah; this.con = con; this.work = w }
                b.recs += r; made += r; o += 1.0
            }
            val n = Vedoms.add(b, x, made, who())
            a.changed()
            a.hint("Создано в таблице «${IdTables.fullTitle(b, table)}»: ${Ui.plural(made.size, "строка", "строки", "строк")}, в ведомость — $n", 2500)
            then()
        }
        if (t == null) {
            AlertDialog.Builder(a).setTitle("Нет таблицы «$cipher»")
                .setMessage("Таблицы с шифром «$cipher» на доске нет. Создать таблицу рядом с ведомостью и строку «$label»?")
                .setPositiveButton("Создать") { _, _ ->
                    val r = v.vedomDraw.bounds(b, x, RectF())
                    createRows(BoardTable(r.right + 40f * x.scale, r.top, x.scale, cipher).apply { kind = TableKinds.KJ_VERT.id }, works, newTable = true)
                }
                .setNegativeButton("Отмена", null).show()
            return
        }
        val rows = cands.flatMap { IdTables.allRows(b, it) }
        val nz = IdTables.norm(zah); val nc = IdTables.norm(con); val nu = IdTables.norm(uch)
        fun base(r: BoardRec) = IdTables.norm(r.zah) == nz && (nc.isEmpty() || IdTables.norm(r.con) == nc) && (nu.isEmpty() || IdTables.norm(r.uch) == nu)
        val found = ArrayList<BoardRec>()
        val missing = ArrayList<String>()
        if (works.isEmpty()) {
            val m = rows.filter { base(it) }
            if (m.isEmpty()) missing += "" else found += m
        } else for (w in works) {
            val m = rows.filter { base(it) && IdTables.norm(it.work) == IdTables.norm(w) }
            if (m.isEmpty()) missing += w else found += m
        }
        if (found.isNotEmpty()) {
            a.pushUndo()
            val n = Vedoms.add(b, x, found, who())
            a.changed()
            a.hint(if (n == found.size) "Добавлено: ${Ui.plural(n, "схема", "схемы", "схем")}" else "Добавлено: $n (остальные уже были в ведомости)", 1800)
            warnWaiting(x, found)
        }
        if (missing.isEmpty()) { then(); return }
        val ml = missing.joinToString(" + ") { it.ifEmpty { "без вида работ" } }
        if (cands.size > 1) {
            // Несколько таблиц-подразделов — спросить, в какую добавить строку.
            AlertDialog.Builder(a).setTitle("Схемы «${IdTables.zahLabel(uch, "захватка $zah")} · $ml» нет — в какую таблицу добавить?")
                .setItems(cands.map { IdTables.fullTitle(b, it) }.toTypedArray()) { _, i -> createRows(cands[i], missing.filter { it.isNotEmpty() }, newTable = false) }
                .setNegativeButton("Не создавать") { _, _ -> then() }
                .show()
            return
        }
        AlertDialog.Builder(a).setTitle("Нет схемы в таблице «${IdTables.fullTitle(b, t)}»")
            .setMessage("В таблице «${IdTables.fullTitle(b, t)}» нет схемы: ${IdTables.zahLabel(uch, "захватка $zah")}${if (con.isNotEmpty()) " · $con" else ""} · $ml. Создать строку (остальные поля — пустые) и добавить в ведомость?")
            .setPositiveButton("Создать") { _, _ -> createRows(t, missing.filter { it.isNotEmpty() }, newTable = false) }
            .setNegativeButton("Не создавать") { _, _ -> then() }
            .show()
    }

    // ---------------------------------------------------------------- передача

    /** Из «Сборки» — «Унесли» (на подпись заказчику / на АН / в ПТО): дата (сегодня, можно поправить) → всем схемам статус и дата. */
    fun giveDialog(x: BoardVedom) {
        if (x.kind == Vedoms.LIST) { a.hint("Список не передают — это просто список схем", 1800); return }
        if (!x.isDraft) { a.hint("${x.num} уже «${Vedoms.giveLabel(x.kind)}» с ${IdTables.dateLong(x.given)}", 1800); return }
        val items = Vedoms.items(b, x)
        if (items.isEmpty()) { a.hint("Сначала добавьте схемы — «+ схемы»", 1800); return }
        var date = IdTables.today()
        val st = IdTables.status(b, BoardStatus.ID, Vedoms.giveStatus(x.kind)).name
        val what = when (x.kind) {
            Vedoms.CUST -> "получат статус «$st» и дату «Отдал заказчику»."
            Vedoms.PTO -> "получат статус «$st» и дату «Сдано в ПТО». Ведомость сразу закроется."
            else -> "получат статус «$st»."
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val viaAn = items.count { it.via == Vedoms.AN }
        box.addView(Ui.text(a, "${Ui.plural(items.size - viaAn, "схема", "схемы", "схем")} $what" +
            if (viaAn > 0) " Через АН — $viaAn: их статус не меняется." else "", 15f, Ui.INK))
        val dateBtn = Ui.pill(a, "Дата: ${IdTables.dateLong(date)}", Ui.INK, Ui.DESK).apply { tag = "give_date" }
        dateBtn.setOnClickListener { pickDate(date) { d -> if (d.isNotEmpty()) { date = d; dateBtn.text = "Дата: ${IdTables.dateLong(date)}" } } }
        box.addView(dateBtn, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(12f) })
        AlertDialog.Builder(a).setTitle("${x.num}: ${Vedoms.giveVerb(x.kind).lowercase()}?")
            .setView(box)
            .setPositiveButton(Vedoms.giveVerb(x.kind)) { _, _ ->
                a.pushUndo()
                Vedoms.give(b, x, date, who())
                a.changed()
                a.buildSelectionBar()
                a.hint("${x.num} — «${Vedoms.giveLabel(x.kind)}» с ${IdTables.dateLong(date)}: ${Ui.plural(items.size, "схема", "схемы", "схем")} — «$st»" +
                    if (Vedoms.hasAnswers(x.kind)) ". Ответы — касание «Ждём» у строки" else "", 3500)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Состояние «Сборка» → «На подписи у заказчика» (0.25, Владимир): дата — день перевода, без лишних вопросов; всем схемам
     * ведомости — статус передачи «от даты» и дата «Отдал заказчику».
     */
    fun giveNow(x: BoardVedom, date: String) {
        if (x.kind == Vedoms.LIST || !x.isDraft) return
        val items = Vedoms.items(b, x)
        if (items.isEmpty()) { a.hint("В ведомости ${x.num} пока нет схем — «+ схемы»", 1800); return }
        a.pushUndo()
        Vedoms.give(b, x, date, who())
        a.changed()
        a.buildSelectionBar()
        val st = IdTables.status(b, BoardStatus.ID, Vedoms.giveStatus(x.kind)).name
        val viaAn = items.count { it.via == Vedoms.AN }
        a.hint("${x.num} — «${Vedoms.giveLabel(x.kind)}» от ${IdTables.dateShort(date)}: ${Ui.plural(items.size - viaAn, "схема", "схемы", "схем")} — «$st от ${IdTables.dateShort(date)}»" +
            (if (viaAn > 0) "; через АН — $viaAn, их статус не меняется" else "") +
            if (Vedoms.hasAnswers(x.kind)) ". Ответы — касание «Ждём» у строки" else "", 3500)
    }

    private fun pickDate(cur0: String, done: (String) -> Unit) {
        val cur = cur0.ifEmpty { IdTables.today() }.split('-').mapNotNull { it.toIntOrNull() }
        DatePickerDialog(a, { _, yy, mm, dd -> done(IdTables.iso(yy, mm + 1, dd)) }, cur.getOrElse(0) { 2026 }, cur.getOrElse(1) { 1 } - 1, cur.getOrElse(2) { 1 }).show()
    }

    // ---------------------------------------------------------------- ответы

    /** Ответ по позициям (касание «Ответ» у строки, выделенные лассо, карточка). */
    fun setAnswer(x: BoardVedom, items: List<BoardVItem>, res: String) {
        if (x.isDraft || !Vedoms.hasAnswers(x.kind)) return
        val wasClosed = Vedoms.isClosed(b, x)
        a.pushUndo()
        for (i in items) Vedoms.answer(b, x, i, res, ansDate(), who())
        a.changed()
        a.buildSelectionBar()
        val closed = Vedoms.isClosed(b, x)
        val what = if (res.isEmpty()) "снова ждём ответа" else Vedoms.resName(Vedoms.kindOf(x, items[0]), res) + " · " + IdTables.dateLong(ansDate())
        a.hint((if (items.size == 1) what else "$what — у ${items.size} схем") + if (closed && !wasClosed) ". Ведомость ${x.num} закрыта" else "", 2500)
        if (closed && !wasClosed && x.kind == Vedoms.AN) offerToCustomer(x)
    }

    /** Ведомость АН закрыта — согласованные собрать в ведомость заказчику одной кнопкой. */
    private fun offerToCustomer(x: BoardVedom) {
        val ok = Vedoms.approvedForCustomer(b, x)
        if (ok.isEmpty()) return
        AlertDialog.Builder(a).setTitle("Согласованные — заказчику")
            .setMessage("АН согласовал ${Ui.plural(ok.size, "схему", "схемы", "схем")}. Собрать их в ведомость «На подпись заказчику», чтобы ответы заказчика отмечать уже в ней?")
            .setPositiveButton("Собрать") { _, _ -> toCustomer(x) }
            .setNegativeButton("Не сейчас", null).show()
    }

    fun toCustomer(x: BoardVedom) {
        val ok = Vedoms.approvedForCustomer(b, x)
        if (ok.isEmpty()) { a.hint("Согласованных схем, ещё не собранных заказчику, нет", 1800); return }
        val (w, h) = ghost(x.scale, ok.size)
        val xid = x.id; val xnum = x.num; val sc = x.scale
        // Место выбирает Владимир (0.25): двигает доску, держит палец или перо → «Вставить ведомость сюда».
        a.startPlacing("Ведомость ${Vedoms.nextNum(b, Vedoms.CUST)} заказчику — ${Ui.plural(ok.size, "схема", "схемы", "схем")} из $xnum: " +
            "двигайте доску, подержите палец или перо там, где поставить", w, h, onPlace = { nx, ny ->
            val src = Vedoms.vedom(b, xid)
            val now = if (src == null) emptyList() else Vedoms.approvedForCustomer(b, src)
            if (now.isEmpty()) { a.hint("Согласованных схем, ещё не собранных заказчику, нет", 1800); return@startPlacing }
            a.pushUndo()
            val nv = BoardVedom(nx, ny, sc, Vedoms.CUST, Vedoms.nextNum(b, Vedoms.CUST))
            nv.made = IdTables.today()
            nv.to = b.vedoms.lastOrNull { it.kind == Vedoms.CUST && it.to.isNotBlank() }?.to.orEmpty()
            b.vedoms += nv
            val n = Vedoms.add(b, nv, now, who())
            a.changed()
            a.hint("Ведомость ${nv.num}: ${Ui.plural(n, "схема", "схемы", "схем")} из $xnum. Унесёте заказчику — «На подписи у заказчика →»", 3000)
        })
    }

    private fun resButton(x: BoardVedom, res: String, current: String?, tag: String, kind: String = x.kind, pick: () -> Unit): TextView {
        val c = Vedoms.resColor(res)
        val fg = Board.statusFg(c)
        val cur = res == current
        return Ui.text(a, Vedoms.resName(kind, res).let { if (res.isEmpty()) "Ждём ответа" else it } + if (cur) "  — сейчас" else "", 16f, fg, bold = true).apply {
            background = GradientDrawable().apply {
                cornerRadius = Ui.dpf(a, 12f); setColor(if (res.isEmpty()) Color.WHITE else Board.statusBg(c)); setStroke(dp(if (cur) 3f else 1.5f), fg)
            }
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            minHeight = dp(48f)
            gravity = Gravity.CENTER_VERTICAL
            this.tag = tag
            isClickable = true
            setOnClickListener { pick() }
        }
    }

    /** Кнопки ответов + «Ждём» + дата ответа (общая для отметок подряд). */
    private fun answerBox(x: BoardVedom, current: String?, after: () -> Unit, kind: String = x.kind, pick: (String) -> Unit): LinearLayout {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (res in Vedoms.results(kind) + "") {
            box.addView(resButton(x, res, current, "res_" + res.ifEmpty { "wait" }, kind) { pick(res) },
                LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        }
        val dateLine = Ui.text(a, "Дата ответа: " + (if (answerDate.isEmpty()) "сегодня" else IdTables.dateLong(answerDate)) + "  ✎", 15f, Ui.INK2).apply {
            tag = "res_date"; setPadding(dp(4f), dp(12f), dp(4f), dp(6f)); minHeight = dp(44f); gravity = Gravity.CENTER_VERTICAL; isClickable = true
            setOnClickListener { pickDate(ansDate()) { d -> answerDate = if (d == IdTables.today()) "" else d; after() } }
        }
        box.addView(dateLine)
        return box
    }

    /**
     * «ИГС сейчас» в сборке (0.25, Владимир): у каждой схемы — «Сборка» (пойдёт заказчику) или «На согласовании АН» (сначала на
     * согласование АН). Список рядом с ячейкой; выбор — статус схемы в таблице тоже меняется.
     */
    fun routePopup(x: BoardVedom, item: BoardVItem) {
        popup?.dismiss()
        val r = Vedoms.rec(b, item) ?: return
        val holder = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(10f))
            background = Ui.rounded(a, Ui.PAPER, Ui.RULE, 14f)
        }
        holder.addView(Ui.text(a, "ИГС · " + Vedoms.labelText(b, item), 14f, Ui.INK2, bold = true).apply { setPadding(dp(4f), dp(4f), dp(4f), 0) })
        for ((an, sid) in listOf(false to IdTables.IGS_ASSEMBLY, true to IdTables.IGS_AN)) {
            val st = IdTables.status(b, BoardStatus.ID, sid)
            val cur = r.igs == sid
            val fg = Board.statusFg(st.color)
            holder.addView(Ui.text(a, st.name + (if (cur) "  — сейчас" else "") + if (an) "\nсначала на согласование АН" else "\nпойдёт заказчику", 15f, fg, bold = true).apply {
                background = GradientDrawable().apply { cornerRadius = Ui.dpf(a, 12f); setColor(Board.statusBg(st.color)); setStroke(dp(if (cur) 3f else 1.5f), fg) }
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                minHeight = dp(48f)
                tag = if (an) "route_an" else "route_asm"
                isClickable = true
                setOnClickListener {
                    popup?.dismiss()
                    if (cur) return@setOnClickListener
                    a.pushUndo()
                    Vedoms.route(b, x, item, an, who())
                    a.changed()
                    a.hint(if (an) "${st.name}: схема пойдёт сначала на АН — передача заказчику её не тронет" else "Сборка: схема пойдёт заказчику", 2000)
                }
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        }
        val rect = v.vedomDraw.cellRect(b, x, item, TableCol.IGS, RectF())
        val w = dp(300f)
        val pw = PopupWindow(holder, w, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = Ui.dpf(a, 10f)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }
        popup = pw
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        var px = loc[0] + dp(40f)
        var py = loc[1] + v.height / 3
        if (rect != null) {
            px = loc[0] + ((rect.left - b.camX) * b.camZ).toInt()
            py = loc[1] + ((rect.bottom - b.camY) * b.camZ).toInt() + dp(4f)
        }
        px = px.coerceIn(0, (a.resources.displayMetrics.widthPixels - w).coerceAtLeast(0))
        pw.showAtLocation(v, Gravity.NO_GRAVITY, px, py.coerceAtLeast(0))
    }

    /** Список ответов рядом с ячейкой «Ответ» (палец и перо): выбор вторым касанием, случайное касание ничего не меняет. */
    fun resultPopup(x: BoardVedom, items: List<BoardVItem>) {
        if (x.isDraft) { a.hint(if (x.kind == Vedoms.LIST) "Это список — ответов в нём нет" else "Ответы отмечают, когда унесли: «${Vedoms.giveLabel(x.kind)} →»", 1800); return }
        if (!Vedoms.hasAnswers(x.kind)) { a.hint("Ведомость в ПТО закрыта сразу при передаче", 1800); return }
        popup?.dismiss()
        val rect = items.singleOrNull()?.let { v.vedomDraw.cellRect(b, x, it, VedomDraw.RES, RectF()) }
        lateinit var show: () -> Unit
        val holder = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(10f))
            background = Ui.rounded(a, Ui.PAPER, Ui.RULE, 14f)
        }
        show = {
            holder.removeAllViews()
            holder.addView(Ui.text(a, if (items.size == 1) "Ответ · " + Vedoms.labelText(b, items[0]) else "Ответ для ${items.size} схем", 14f, Ui.INK2, bold = true)
                .apply { setPadding(dp(4f), dp(4f), dp(4f), 0) })
            holder.addView(answerBox(x, items.singleOrNull()?.res, { show() }, kind = Vedoms.kindOf(x, items[0])) { res -> popup?.dismiss(); setAnswer(x, items, res) })
        }
        show()
        val content = ScrollView(a).apply { addView(holder) }
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
        var px = loc[0] + dp(40f)
        var py = loc[1] + v.height / 3
        if (rect != null) {
            px = loc[0] + ((rect.left - b.camX) * b.camZ).toInt()
            val below = loc[1] + ((rect.bottom - b.camY) * b.camZ).toInt() + dp(4f)
            val above = loc[1] + ((rect.top - b.camY) * b.camZ).toInt() - dp(4f) - ph
            py = if (below + ph <= screenH || above < 0) below else above
        }
        px = px.coerceIn(0, (a.resources.displayMetrics.widthPixels - w).coerceAtLeast(0))
        pw.showAtLocation(v, Gravity.NO_GRAVITY, px, py.coerceAtLeast(0))
    }

    /** Карточка позиции снизу (палец, телефон): схема, её статус сейчас, ответ крупными кнопками, ▲/▼ — соседние строки. */
    fun itemCard(x: BoardVedom, item: BoardVItem) {
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
            tag = "vitem_card"
        }
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18f), dp(14f), dp(10f), dp(4f)) }
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(8f), dp(18f), dp(18f)) }
        root.addView(head)
        root.addView(ScrollView(a).apply { addView(body) })
        var cur = item
        lateinit var show: () -> Unit
        fun btn(text: String, tag: String, enabled: Boolean, f: () -> Unit) = Ui.pill(a, text, if (enabled) Ui.INK else Ui.MUTED, Ui.DESK).apply {
            gravity = Gravity.CENTER; this.tag = tag; isEnabled = enabled; setOnClickListener { f() }
        }
        show = {
            val list = Vedoms.items(b, x)
            val i = list.indexOf(cur)
            if (i < 0 || b.vedoms.none { it === x }) d.dismiss()
            else {
                head.removeAllViews()
                head.addView(Ui.text(a, "${x.num} · ${i + 1} из ${list.size}", 20f, Ui.INK, bold = true).apply { tag = "card_title" }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                head.addView(Ui.text(a, "✕", 20f, Ui.INK2).apply { setPadding(dp(12f), dp(4f), dp(8f), dp(4f)); setOnClickListener { d.dismiss() } })
                body.removeAllViews()
                body.addView(Ui.text(a, Vedoms.labelText(b, cur), 16f, Ui.INK).apply { setPadding(0, 0, 0, dp(6f)) })
                val rec = Vedoms.rec(b, cur)
                val stRow = LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = Ui.rounded(a, Color.WHITE, Ui.RULE, 14f)
                    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                    minimumHeight = dp(52f)
                    addView(Ui.text(a, "ИГС сейчас", 14f, Ui.MUTED), LinearLayout.LayoutParams(dp(110f), Ui.WRAP))
                    if (rec != null) {
                        val st = IdTables.status(b, BoardStatus.ID, rec.igs)
                        addView(Ui.text(a, st.name, 16f, Board.statusFg(st.color), bold = true).apply {
                            background = Ui.rounded(a, Board.statusBg(st.color), Board.statusFg(st.color), 12f)
                            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                        })
                    } else addView(Ui.text(a, "строки в таблице уже нет", 15f, Ui.MUTED))
                }
                body.addView(stRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
                if (!x.isDraft && Vedoms.hasAnswers(x.kind)) {
                    body.addView(Ui.text(a, "Ответ по ведомости" + if (cur.rd.isNotEmpty()) " · " + IdTables.dateLong(cur.rd) else "", 14f, Ui.MUTED, bold = true)
                        .apply { setPadding(0, dp(12f), 0, 0) })
                    body.addView(answerBox(x, cur.res, { show() }, kind = Vedoms.kindOf(x, cur)) { res -> setAnswer(x, listOf(cur), res); show() })
                } else if (x.isDraft && x.kind != Vedoms.LIST) {
                    body.addView(Ui.text(a, "Сборка: ответы — когда унесли («${Vedoms.giveLabel(x.kind)}»).", 14f, Ui.MUTED).apply { setPadding(0, dp(10f), 0, 0) })
                }
                val acts = LinearLayout(a)
                acts.addView(btn("Показать в таблице", "card_show", rec != null) { d.dismiss(); showInTable(x, cur) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                acts.addView(btn("История", "card_hist", rec != null) { rec?.let { historyDialog(it) } },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = dp(8f) })
                body.addView(acts, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(14f) })
                val nav = LinearLayout(a)
                nav.addView(btn("↑ Выше", "card_prev", i > 0) { cur = list[i - 1]; show() }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                nav.addView(btn("↓ Ниже", "card_next", i < list.size - 1) { cur = list[i + 1]; show() },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = dp(8f) })
                body.addView(nav, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
                body.addView(btn("Убрать из ведомости", "card_remove", true) { d.dismiss(); removeItems(listOf(cur)) },
                    LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })
                v.markedItems = setOf(cur)
                v.invalidate()
            }
        }
        show()
        d.setContentView(root)
        d.setOnDismissListener { if (card === d) card = null; v.markedItems = null; v.invalidate() }
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(min(a.resources.displayMetrics.widthPixels, dp(640f)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.2f)
        }
        card = d
        d.show()
    }

    /** Показать схему в её таблице (ТЗ, раздел 6.4) и вернуться к ведомости кнопкой. */
    fun showInTable(x: BoardVedom, i: BoardVItem) {
        val rec = Vedoms.rec(b, i) ?: run { a.hint("Строки этой схемы в таблице уже нет", 1800); return }
        a.tableUi.showRows(listOf(rec))
        a.showModeBar("Схема из ведомости ${x.num}", listOf(
            Ui.pill(a, "← К ведомости", Ui.INK, Ui.DESK).apply {
                tag = "back_vedom"
                setOnClickListener {
                    a.hideModeBar()
                    v.markedRecs = null
                    val r = v.vedomDraw.bounds(b, x, RectF())
                    v.fitRect(r.left, r.top, r.right, r.bottom)
                    v.markedItems = setOf(i)
                    v.invalidate()
                }
            },
            Ui.pill(a, "История", Ui.INK, Ui.DESK).apply { tag = "back_hist"; setOnClickListener { historyDialog(rec) } },
            Ui.pill(a, "Готово", Color.WHITE, Ui.ACCENT).apply { tag = "back_done"; setOnClickListener { a.hideModeBar(); v.markedRecs = null; v.invalidate() } },
        ))
    }

    // ---------------------------------------------------------------- история

    private val timeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))

    /** История схемы (ТЗ, раздел 7): в каких ведомостях была, кто, когда, что было → стало. */
    fun historyDialog(rec: BoardRec) {
        val t = IdTables.tableOf(b, rec)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(6f), dp(18f), dp(8f)); tag = "history" }
        val inV = Vedoms.itemsOf(b, rec)
        if (inV.isNotEmpty()) {
            box.addView(Ui.text(a, "В ведомостях", 14f, Ui.MUTED, bold = true).apply { setPadding(0, dp(4f), 0, dp(4f)) })
            for ((x, i) in inV) {
                val res = when {
                    x.isDraft -> "сборка"
                    !Vedoms.hasAnswers(x.kind) -> "передана " + IdTables.dateShort(x.given)
                    else -> "передана " + IdTables.dateShort(x.given) + " · " + Vedoms.resName(Vedoms.kindOf(x, i), i.res).lowercase() +
                        if (i.rd.isNotEmpty()) " " + IdTables.dateShort(i.rd) else ""
                }
                box.addView(Ui.text(a, "${x.num} · ${Vedoms.kindName(x.kind)} — $res", 14f, Ui.INK).apply { setPadding(0, dp(2f), 0, dp(2f)) })
            }
        }
        val ev = Vedoms.history(b, rec)
        box.addView(Ui.text(a, "Правки статусов и дат", 14f, Ui.MUTED, bold = true).apply { setPadding(0, dp(12f), 0, dp(4f)) })
        if (ev.isEmpty()) box.addView(Ui.text(a, "Пока не было (история ведётся с версии 0.21).", 14f, Ui.INK2))
        for ((n, e) in ev.withIndex()) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6f), 0, dp(6f))
                tag = "hist_$n"
                addView(Ui.text(a, timeFmt.format(Date(e.at)) + " · " + e.who.ifBlank { "без имени" }, 13f, Ui.MUTED, bold = true))
                addView(Ui.text(a, Vedoms.eventText(b, e) { k -> t?.colName(k) ?: TableCol.defaultName(k) } +
                    if (e.note.isNotEmpty() && e.k.isNotEmpty()) " (${e.note})" else "", 15f, Ui.INK))
            }
            box.addView(row)
        }
        val title = listOf(t?.let { IdTables.fullTitle(b, it) }.orEmpty(), rec.uch, rec.zah.takeIf { it.isNotEmpty() }?.let { "захв. $it" }.orEmpty(), rec.work).filter { it.isNotBlank() }.joinToString(" · ")
        AlertDialog.Builder(a).setTitle("История · $title").setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Закрыть", null).show()
    }

    // ---------------------------------------------------------------- выделенное

    fun selectionLabel(sel: List<Any>): String? = when {
        sel.size == 1 && sel[0] is BoardVedom -> (sel[0] as BoardVedom).let { "Ведомость ${Vedoms.title(it)} · ${Vedoms.state(b, it)}" }
        sel.isNotEmpty() && sel.all { it is BoardVItem } -> "Позиций: ${sel.size}"
        else -> null
    }

    fun selectionButtons(sel: List<Any>): List<TextView> {
        val out = ArrayList<TextView>()
        fun pill(text: String, tag: String, f: () -> Unit) = Ui.pill(a, text, Ui.INK, Ui.DESK).apply { this.tag = tag; setOnClickListener { f() } }
        if (sel.size == 1 && sel[0] is BoardVedom) {
            val x = sel[0] as BoardVedom
            if (x.isDraft) out += pill("+ схемы", "sel_vadd") { collectDialog(x) }
            if (x.isDraft && x.kind != Vedoms.LIST) out += Ui.pill(a, Vedoms.giveLabel(x.kind), Color.WHITE, Ui.ACCENT).apply { tag = "sel_give"; setOnClickListener { giveDialog(x) } }
            if (x.kind != Vedoms.LIST) out += pill("Состояние", "sel_vstate") { stateDialog(x) }
            if (Vedoms.isOpen(b, x)) out += pill("Ответ всем ждущим", "sel_vanswer") {
                val wait = Vedoms.items(b, x).filter { it.res.isEmpty() }
                if (wait.isNotEmpty()) resultPopup(x, wait)
            }
            if (x.kind == Vedoms.AN && Vedoms.approvedForCustomer(b, x).isNotEmpty()) out += pill("Согласованные → заказчику", "sel_tocust") { toCustomer(x) }
            out += pill("Изменить", "sel_vedit") { editDialog(x) }
            out += pill("В Excel", "sel_vexcel") { exportVedom(x) }
            out += pill("Все ведомости", "sel_vlist") { listDialog() }
        }
        if (sel.isNotEmpty() && sel.all { it is BoardVItem }) {
            val items = sel.map { it as BoardVItem }
            val x = items.map { it.v }.distinct().singleOrNull()?.let { Vedoms.vedom(b, it) }
            if (x != null && !x.isDraft && Vedoms.hasAnswers(x.kind)) out += Ui.pill(a, "Ответ…", Color.WHITE, Ui.ACCENT).apply { tag = "sel_vanswer"; setOnClickListener { resultPopup(x, items) } }
            out += pill("Убрать из ведомости", "sel_vremove") { removeItems(items) }
        }
        if (sel.isNotEmpty() && sel.all { it is BoardRec }) out += pill("В ведомость", "sel_tovedom") { toVedom(sel.map { it as BoardRec }) }
        return out
    }

    /** «В ведомость» для строк таблицы: в черновик или список (выбрать) или новую. */
    fun toVedom(recs: List<BoardRec>) {
        val drafts = b.vedoms.filter { it.isDraft }
        if (drafts.isEmpty()) { create(recs); return }
        val names = drafts.map { "В ${Vedoms.title(it)} (${Ui.plural(Vedoms.items(b, it).size, "схема", "схемы", "схем")})" } + "Новая ведомость…"
        AlertDialog.Builder(a).setTitle("${Ui.plural(recs.size, "схема", "схемы", "схем")} → ведомость")
            .setItems(names.toTypedArray()) { _, i ->
                if (i >= drafts.size) { create(recs); return@setItems }
                val x = drafts[i]
                a.pushUndo()
                val n = Vedoms.add(b, x, recs, who())
                a.changed()
                v.clearSelection()
                a.hint("В ${x.num} добавлено: $n" + if (n < recs.size) " (остальные уже были)" else "", 2000)
                warnWaiting(x, recs)
            }.show()
    }

    fun editDialog(x: BoardVedom) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(4f), dp(20f), 0) }
        fun label(s: String) = Ui.text(a, s, 13f, Ui.MUTED, bold = true).apply { setPadding(0, dp(10f), 0, dp(2f)) }
        val num = EditText(a).apply { setText(x.num); isSingleLine = true; tag = "vedit_num" }
        val to = EditText(a).apply { setText(x.to); isSingleLine = true; tag = "vedit_to" }
        box.addView(label("Номер")); box.addView(num)
        box.addView(label("Кому")); box.addView(to)
        var given = x.given
        if (!x.isDraft) {
            box.addView(label("Дата передачи (статусы и даты схем не меняются)"))
            val btn = Ui.pill(a, IdTables.dateLong(given), Ui.INK, Ui.DESK).apply { tag = "vedit_given" }
            btn.setOnClickListener { pickDate(given) { d -> if (d.isNotEmpty()) { given = d; btn.text = IdTables.dateLong(d) } } }
            box.addView(btn, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        }
        AlertDialog.Builder(a).setTitle(Vedoms.kindName(x.kind)).setView(box)
            .setPositiveButton("Готово") { _, _ ->
                a.pushUndo()
                x.num = num.text.toString().trim().ifEmpty { x.num }
                x.to = to.text.toString().trim()
                x.given = given
                a.changed(); a.buildSelectionBar()
            }
            .setNegativeButton("Отмена", null).show()
    }

    /** Удалить выделенные ведомости (с позициями) — после подтверждения; позиции — «Убрать из ведомости». */
    fun confirmDelete(sel: List<Any>, then: () -> Unit) {
        val vs = sel.filterIsInstance<BoardVedom>()
        val its = sel.filterIsInstance<BoardVItem>()
        if (vs.isEmpty() && its.isEmpty()) { then(); return }
        val n = vs.sumOf { x -> b.vitems.count { it.v == x.id } }
        val msg = if (vs.isNotEmpty()) "Удалить ведомость ${vs.joinToString { it.num }} (${Ui.plural(n, "позиция", "позиции", "позиций")})? " +
            (if (vs.any { it.isDraft && it.kind != Vedoms.LIST }) "У схем в сборке вернётся статус, что был до «Сборки»; у переданных статусы не меняются." else "Статусы и даты схем и их история не меняются.")
        else "Убрать из ведомости ${Ui.plural(its.size, "схему", "схемы", "схем")}? Статусы и даты схем не меняются."
        AlertDialog.Builder(a).setMessage("$msg «Отменить» вернёт.").setPositiveButton("Удалить") { _, _ -> then() }.setNegativeButton("Отмена", null).show()
    }

    fun removeSelected(sel: List<Any>) {
        // Ведомость в сборке удалили — у её схем вернётся статус, что был до «Сборки».
        for (x in sel.filterIsInstance<BoardVedom>()) { Vedoms.unassemble(b, x, Vedoms.items(b, x), who()); Vedoms.remove(b, x) }
        val its = sel.filterIsInstance<BoardVItem>()
        for ((vid, l) in its.groupBy { it.v }) Vedoms.vedom(b, vid)?.let { Vedoms.unassemble(b, it, l, who()) }
        b.vitems.removeAll { i -> sel.any { it === i } }
    }

    // ---------------------------------------------------------------- Excel

    /** Опись ведомости для заказчика (.xlsx). */
    fun exportVedom(x: BoardVedom) {
        val file = runCatching {
            val dir = File(a.cacheDir, "shares").apply { mkdirs() }
            val safe = x.num.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Ведомость" }
            val f = File(dir, "Ведомость $safe ${IdTables.dateLong(x.given.ifEmpty { IdTables.today() })}.xlsx")
            f.outputStream().buffered().use { IdExcel.writeVedom(b, x, it) }
            f
        }.onFailure { a.hint("Не получилось сохранить файл: ${it.message}", 3000) }.getOrNull() ?: return
        val uri = FileProvider.getUriForFile(a, a.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = IdExcel.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        a.startActivity(Intent.createChooser(send, "Отправить ведомость"))
    }
}
