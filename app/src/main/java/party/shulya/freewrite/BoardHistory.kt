package party.shulya.freewrite

import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/**
 * «Журнал изменений» общей доски (0.28.3, Владимир: «журнал на сервере делай»). Сервер хранит 30 дней каждую версию
 * предмета, которую заменила более поздняя правка или удаление. Здесь — список по отправкам: когда, кто, что изменил или
 * удалил; касание — подробно и «Вернуть как было»: прежние версии ставятся на доску обычной правкой (уходят коллегам,
 * «Отменить» убирает).
 */
class BoardHistory(private val a: BoardActivity) {

    /** Одна отправка правок: одно время на сервере и один автор. */
    class Group(val at: Long, val by: String, val entries: List<Remote.HEntry>)

    internal var listDlg: AlertDialog? = null
    internal var detailDlg: AlertDialog? = null
    internal var groups: List<Group> = emptyList()

    fun open(withStrokes: Boolean = false) {
        val b = a.board()
        if (!b.sync.isShared) { a.hint("Журнал есть у общей доски: «Совместно» → «Сделать общей»", 3500); return }
        val t = RemoteSync.transport() ?: run { a.hint("Сервер не настроен в этой сборке", 3000); return }
        val wait = AlertDialog.Builder(a).setTitle("Журнал изменений").setMessage("Загружаю с сервера…")
            .setNegativeButton("Отмена", null).show()
        val boardId = b.id
        val key = b.sync.remoteKey
        Thread {
            val r = runCatching { Remote.history(t, boardId, key, skipStrokes = !withStrokes) }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                val shown = wait.isShowing
                wait.dismiss()
                if (!shown) return@runOnUiThread
                r.onSuccess { show(group(it), withStrokes) }.onFailure { e ->
                    val msg = when {
                        e is SyncException && e.code == "bad_op" -> "Журнал появится, когда обновится сервер."
                        e is SyncException && e.code == "no_history" -> "Журнал на сервере ещё не готов."
                        e is SyncException -> e.message ?: "Ошибка сервера"
                        else -> "Нет связи с сервером. Проверьте интернет."
                    }
                    AlertDialog.Builder(a).setTitle("Журнал изменений").setMessage(msg).setPositiveButton("Понятно", null).show()
                }
            }
        }.start()
    }

    private fun group(list: List<Remote.HEntry>): List<Group> {
        val out = ArrayList<Group>()
        var cur = ArrayList<Remote.HEntry>()
        for (e in list) {
            val last = cur.lastOrNull()
            if (last != null && (last.at != e.at || last.by != e.by)) { out += Group(last.at, last.by, cur); cur = ArrayList() }
            cur += e
        }
        cur.lastOrNull()?.let { out += Group(it.at, it.by, cur) }
        return out
    }

    private fun show(gs: List<Group>, withStrokes: Boolean) {
        groups = gs
        val ctx = a
        val pad = Ui.dp(ctx, 16f)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, Ui.dp(ctx, 4f), pad, pad) }
        col.addView(Ui.text(ctx, "Здесь — что меняли и удаляли за последние 30 дней. Нажмите на строку, чтобы посмотреть подробно " +
            "и вернуть как было.", 13f, Ui.MUTED).apply { setPadding(0, 0, 0, Ui.dp(ctx, 8f)) })
        col.addView(CheckBox(ctx).apply {
            text = "Показывать и штрихи (рисунки ручкой)"
            isChecked = withStrokes
            tag = "hist_strokes"
            setOnCheckedChangeListener { _, on -> listDlg?.dismiss(); open(withStrokes = on) }
        })
        if (gs.isEmpty()) col.addView(Ui.text(ctx, "За 30 дней ничего не меняли и не удаляли.", 15f, Ui.INK2).apply {
            setPadding(0, Ui.dp(ctx, 16f), 0, 0)
        })
        var day = ""
        for ((i, g) in gs.withIndex()) {
            val d = Ui.dayTitle(g.at)
            if (d != day) {
                day = d
                col.addView(Ui.text(ctx, d, 13f, Ui.MUTED, bold = true).apply { setPadding(0, Ui.dp(ctx, 14f), 0, Ui.dp(ctx, 4f)) })
            }
            col.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                tag = "hist_$i"
                background = Ui.rounded(ctx, Ui.PAPER, Ui.RULE, 10f)
                setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 8f), Ui.dp(ctx, 12f), Ui.dp(ctx, 8f))
                layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(ctx, 6f) }
                addView(Ui.text(ctx, Ui.time(g.at) + " · " + g.by.ifBlank { "без имени" }, 14f, Ui.INK, bold = true))
                addView(Ui.text(ctx, summary(a.board(), g), 14f, Ui.INK2))
                isClickable = true
                setOnClickListener { details(g) }
            })
        }
        val scroll = ScrollView(ctx).apply { addView(col) }
        listDlg = AlertDialog.Builder(ctx).setTitle("Журнал изменений").setView(scroll)
            .setNegativeButton("Закрыть", null).show()
    }

    private fun details(g: Group) {
        val b = a.board()
        val sorted = g.entries.sortedBy { e -> ORDER.indexOf(e.kind).let { if (it < 0) 99 else it } }
        val lines = sorted.take(60).joinToString("\n") { "• " + (if (it.byDeleted) "удалено — " else "изменено — ") + label(b, it) }
        val more = if (g.entries.size > 60) "\n… и ещё ${g.entries.size - 60}" else ""
        val restorable = g.entries.count { it.data.isNotEmpty() }
        detailDlg = AlertDialog.Builder(a)
            .setTitle(Ui.dayTitle(g.at) + ", " + Ui.time(g.at) + " · " + g.by.ifBlank { "без имени" })
            .setMessage("Как было до этой правки:\n\n$lines$more\n\n«Вернуть как было» поставит эти версии на доску — у всех. " +
                "Если передумаете — «Отменить».")
            .setPositiveButton("Вернуть как было") { _, _ -> restore(g) }
            .setNegativeButton("Закрыть", null)
            .show()
        detailDlg?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = restorable > 0
    }

    internal fun restore(g: Group) {
        a.pushUndo()
        val n = Remote.restore(a.board(), g.entries)
        a.changed()
        listDlg?.dismiss()
        a.hint(if (n > 0) "Вернул как было: ${Ui.plural(n, "предмет", "предмета", "предметов")}. «Отменить» уберёт" else "Нечего возвращать", 4000)
        runCatching { Trail.add("Журнал: вернули $n из ${g.entries.size} (${Ui.time(g.at)})") }
    }

    companion object {
        private val NOUNS = mapOf(
            "vedom" to arrayOf("ведомость", "ведомости", "ведомостей"),
            "vitem" to arrayOf("схема в ведомости", "схемы в ведомостях", "схем в ведомостях"),
            "table" to arrayOf("таблица", "таблицы", "таблиц"),
            "rec" to arrayOf("строка таблицы", "строки таблиц", "строк таблиц"),
            "event" to arrayOf("запись истории", "записи истории", "записей истории"),
            "zone" to arrayOf("область плана", "области плана", "областей плана"),
            "stroke" to arrayOf("штрих", "штриха", "штрихов"),
            "text" to arrayOf("надпись", "надписи", "надписей"),
            "image" to arrayOf("картинка", "картинки", "картинок"),
            "fill" to arrayOf("заливка", "заливки", "заливок"),
            "sticker" to arrayOf("метка", "метки", "меток"),
            "callout" to arrayOf("выноска", "выноски", "выносок"),
            "frame" to arrayOf("раздел", "раздела", "разделов"),
            "row" to arrayOf("строка ИД", "строки ИД", "строк ИД"),
            "status" to arrayOf("статус", "статуса", "статусов"),
        )
        internal val ORDER = listOf("vedom", "table", "rec", "vitem", "frame", "callout", "text", "zone", "fill", "image", "sticker", "row", "status", "event", "stroke")

        /** «удалено: ведомость «З-14» (На подписи у заказчика от 18.11.26), 25 схем в ведомостях; изменено: 3 строки таблиц». */
        fun summary(b: Board, g: Group): String {
            fun part(list: List<Remote.HEntry>): String {
                val byKind = list.groupBy { it.kind }.toList().sortedBy { (k, _) -> ORDER.indexOf(k).let { if (it < 0) 99 else it } }
                val words = byKind.take(3).map { (k, es) ->
                    if (es.size == 1) label(b, es[0]).replaceFirstChar { it.lowercase() }
                    else { val n = NOUNS[k] ?: arrayOf("предмет", "предмета", "предметов"); Ui.plural(es.size, n[0], n[1], n[2]) }
                }
                val rest = byKind.drop(3).sumOf { it.second.size }
                return words.joinToString(", ") + if (rest > 0) " и ещё $rest" else ""
            }
            val del = g.entries.filter { it.byDeleted }
            val chg = g.entries.filter { !it.byDeleted }
            return listOfNotNull(
                if (del.isNotEmpty()) "удалено: " + part(del) else null,
                if (chg.isNotEmpty()) "изменено: " + part(chg) else null,
            ).joinToString("; ")
        }

        /** Что это было — простыми словами, по прежней версии. */
        fun label(b: Board, e: Remote.HEntry): String = runCatching {
            val j = JSONObject(e.data)
            when (e.kind) {
                "vedom" -> {
                    val v = Vedoms.parseVedom(j)
                    "Ведомость «${v.num}» (" + (if (v.given.isEmpty()) "Сборка" + (if (v.made.isNotEmpty()) " от " + IdTables.dateShort(v.made) else "")
                        else Vedoms.giveLabel(v.kind) + " от " + IdTables.dateShort(v.given)) + ")"
                }
                "vitem" -> {
                    val i = Vedoms.parseItem(j)
                    val num = b.vedoms.firstOrNull { it.id == i.v }?.num
                    val r = b.recs.firstOrNull { it.id == i.r }
                    val what = r?.let { recText(it) } ?: listOf(i.lt, i.lu, i.lz, i.lw).filter { it.isNotEmpty() }.joinToString(" · ")
                    "Схема" + (if (num != null) " в ведомости «$num»" else " в ведомости") + (if (what.isNotEmpty()) ": $what" else "")
                }
                "table" -> "Таблица «${IdTables.parseTable(j).title}»"
                "rec" -> "Строка «${recText(IdTables.parseRec(j))}»"
                "event" -> "Запись в истории схемы"
                "zone" -> "Область на плане"
                "stroke" -> "Штрих"
                "text" -> "Надпись «${Board.parseText(j).text.take(40)}»"
                "image" -> "Картинка"
                "fill" -> "Заливка"
                "sticker" -> "Метка"
                "callout" -> "Выноска «${j.optString("title").take(40)}»"
                "frame" -> "Раздел «${Board.parseFrame(j).title}»"
                "row" -> "Строка ИД «${j.optString("title").take(40)}»"
                "status" -> "Статус «${j.optString("name")}»"
                else -> when (e.id) { Board.TITLE_ID -> "Название доски «${j.optString("title")}»"; Board.ORDER_ID -> "Порядок статусов"; else -> "Предмет" }
            }
        }.getOrDefault("Предмет")

        private fun recText(r: BoardRec) = listOf(r.uch, r.zah, r.con, r.work).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "без названия" }
    }
}
