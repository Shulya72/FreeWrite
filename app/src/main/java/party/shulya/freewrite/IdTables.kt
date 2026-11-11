package party.shulya.freewrite

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Таблица ИД на доске (0.20, ТЗ «Учёт ИГС», шаг 1): все схемы одного шифра проекта («КЖ 6.1.1»), стоит у чертежа.
 *
 * Сами строки — отдельные предметы доски ([BoardRec]): правки разных строк одной таблицы с разных планшетов не мешают
 * друг другу. Таблица хранит место, размер (один на всю таблицу — все строки одного размера и ровно по столбцам),
 * шифр и столбцы ([cols]; пусто — столбцы по умолчанию).
 */
class BoardTable(var x: Float, var y: Float, var scale: Float, var title: String) {
    var id: String = Ids.next()

    /** Столбцы по порядку (пусто — [TableCol.defaults]). Скрытые тоже здесь — с [TableCol.hidden]. */
    val cols = ArrayList<TableCol>()

    /** Отбор строк на этом планшете (не сохраняется и не уходит коллегам). */
    @Transient var filter: RowFilter? = null

    /** Раскладка (считает [TableDraw]): пересчитывается, когда меняются строки, столбцы, статусы. */
    internal var lay: TableLayout? = null

    /** Все столбцы (со скрытыми) — свои или по умолчанию. */
    fun allCols(): List<TableCol> = if (cols.isEmpty()) TableCol.defaults() else cols

    /** Видимые столбцы по порядку. */
    fun visibleCols(): List<TableCol> = allCols().filter { !it.hidden }

    fun colName(k: String): String = allCols().firstOrNull { it.k == k }?.title() ?: TableCol.defaultName(k)
}

/** Столбец таблицы: встроенный ([TableCol.ZAH]…) или свой текстовый (ключ «c…»). name — своё название (пусто — обычное). */
class TableCol(val k: String, var name: String = "", var hidden: Boolean = false) {
    val custom: Boolean get() = k.startsWith("c")
    val isStatus: Boolean get() = k == IGS || k == CK || k == DWG
    val isDate: Boolean get() = k == DATE || k == DCUST || k == DPTO

    fun title(): String = name.ifEmpty { defaultName(k) }
    fun copy() = TableCol(k, name, hidden)

    companion object {
        const val ZAH = "zah"
        const val CON = "con"
        const val WORK = "work"
        const val DATE = "date"
        const val CK = "ck"
        const val DWG = "dwg"
        const val IGS = "igs"
        const val DCUST = "dcust"
        const val DPTO = "dpto"
        const val WHO = "who"
        const val NOTE = "note"
        /** Порядок — как в таблице Excel Владимира (ТЗ, раздел 3.2). */
        val ORDER = listOf(ZAH, CON, WORK, DATE, CK, DWG, IGS, DCUST, DPTO, WHO, NOTE)

        fun defaultName(k: String): String = when (k) {
            ZAH -> "Захватка"; CON -> "Конструкция"; WORK -> "Вид работ"; DATE -> "Дата работ"
            CK -> "Чек-лист"; DWG -> "DWG"; IGS -> "ИГС"; DCUST -> "Отдал заказчику"; DPTO -> "Сдано в ПТО"
            WHO -> "Исполнитель"; NOTE -> "Примечание"; else -> "Столбец"
        }

        fun defaults(): List<TableCol> = ORDER.map { TableCol(it) }
        fun newCustomKey(): String = "c" + Ids.next().take(6)
    }
}

/** Отбор строк таблицы: по статусам ИГС, исполнителю, тексту. Пусто — всё. */
class RowFilter(val igs: Set<String> = emptySet(), val who: Set<String> = emptySet(), val text: String = "") {
    val isEmpty: Boolean get() = igs.isEmpty() && who.isEmpty() && text.isBlank()

    fun matches(r: BoardRec): Boolean {
        if (igs.isNotEmpty() && r.igs !in igs) return false
        if (who.isNotEmpty() && r.who.trim() !in who) return false
        if (text.isNotBlank()) {
            val hay = (r.zah + " " + r.con + " " + r.work + " " + r.who + " " + r.note + " " + r.extra.values.joinToString(" ")).lowercase(Locale.ROOT)
            if (!text.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }.all { it in hay }) return false
        }
        return true
    }
}

/**
 * Строка таблицы ИД — одна исполнительная схема (ТЗ, раздел 2): захватка/участок, конструкция, вид работ, дата работ,
 * Чек-лист, DWG, ИГС (номера статусов из своих списков доски), даты «Отдал заказчику» и «Сдано в ПТО», исполнитель,
 * примечание, свои столбцы ([extra]). [t] — таблица; [o] — порядок в ней.
 */
class BoardRec(var t: String, var o: Double) {
    var id: String = Ids.next()
    var zah = ""
    var con = ""
    var work = ""
    /** Даты — «2026-08-26», пусто — нет. */
    var date = ""
    var ck = IdTables.CK_NONE
    var dwg = IdTables.DWG_NONE
    var igs = IdTables.IGS_NONE
    var dcust = ""
    var dpto = ""
    var who = ""
    var note = ""
    val extra = LinkedHashMap<String, String>()

    fun copy(): BoardRec = BoardRec(t, o).also { c ->
        c.zah = zah; c.con = con; c.work = work; c.date = date; c.ck = ck; c.dwg = dwg; c.igs = igs
        c.dcust = dcust; c.dpto = dpto; c.who = who; c.note = note; c.extra.putAll(extra)
    }

    /** Значение столбца как текст (статусы — номер статуса, даты — «2026-08-26»). */
    fun get(k: String): String = when (k) {
        TableCol.ZAH -> zah; TableCol.CON -> con; TableCol.WORK -> work; TableCol.DATE -> date
        TableCol.CK -> ck; TableCol.DWG -> dwg; TableCol.IGS -> igs; TableCol.DCUST -> dcust; TableCol.DPTO -> dpto
        TableCol.WHO -> who; TableCol.NOTE -> note; else -> extra[k] ?: ""
    }

    fun set(k: String, v: String) {
        when (k) {
            TableCol.ZAH -> zah = v; TableCol.CON -> con = v; TableCol.WORK -> work = v; TableCol.DATE -> date = v
            TableCol.CK -> ck = v; TableCol.DWG -> dwg = v; TableCol.IGS -> igs = v; TableCol.DCUST -> dcust = v; TableCol.DPTO -> dpto = v
            TableCol.WHO -> who = v; TableCol.NOTE -> note = v
            else -> if (v.isEmpty()) extra.remove(k) else extra[k] = v
        }
    }

    fun key(): String = IdTables.key(zah, con, work)
}

object IdTables {
    // Номера статусов по умолчанию — одинаковые на всех планшетах (общая доска сходится). ИГС — список ИД доски:
    // у досок 0.18 его номера i0–i3 сохраняются, меняются названия (ТЗ, раздел 4).
    const val IGS_NONE = "i0"       // Не выполнено
    const val IGS_NOT_GIVEN = "i5"  // Не предоставлена
    const val IGS_WORK = "i1"       // В работе
    const val IGS_AN = "i6"         // На подписи АН
    const val IGS_AN_NO = "i7"      // Не согласована
    const val IGS_CUST = "i2"       // На подписи у заказчика
    const val IGS_BACK = "i8"       // Возврат на корректировку
    const val IGS_PTO = "i3"        // Сдано в ПТО
    const val CK_NONE = "k0"
    const val CK_DONE = "k3"
    const val DWG_NONE = "d0"
    const val DWG_DONE = "d2"

    /** Виды работ для быстрого выбора (у одной захватки обычно «Опалубка» и «Бетон»). */
    val WORKS = listOf("Опалубка", "Армирование", "Бетон", "Разработка", "Обратная засыпка", "Кладка", "Монтаж")

    fun igsStatuses() = listOf(
        BoardStatus("i0", "Не выполнено", Color.rgb(138, 147, 163)),
        BoardStatus("i5", "Не предоставлена", Color.rgb(198, 40, 40)),
        BoardStatus("i1", "В работе", Color.rgb(31, 95, 209)),
        BoardStatus("i6", "На подписи АН", Color.rgb(123, 63, 196)),
        BoardStatus("i7", "Не согласована", Color.rgb(142, 27, 47)),
        BoardStatus("i2", "На подписи у заказчика", Color.rgb(184, 138, 0)),
        BoardStatus("i8", "Возврат на корректировку", Color.rgb(212, 100, 12)),
        BoardStatus("i3", "Сдано в ПТО", Color.rgb(35, 128, 58)).also { it.done = true },
    )

    fun ckStatuses() = listOf(
        BoardStatus("k0", "Не выполнено", Color.rgb(138, 147, 163), BoardStatus.CK),
        BoardStatus("k1", "Не предъявлено", Color.rgb(198, 40, 40), BoardStatus.CK),
        BoardStatus("k2", "Нет возможности предъявить", Color.rgb(212, 100, 12), BoardStatus.CK),
        BoardStatus("k3", "Предъявлено", Color.rgb(35, 128, 58), BoardStatus.CK).also { it.done = true },
    )

    fun dwgStatuses() = listOf(
        BoardStatus("d0", "Не выполнено", Color.rgb(138, 147, 163), BoardStatus.DWG),
        BoardStatus("d1", "Нет", Color.rgb(198, 40, 40), BoardStatus.DWG),
        BoardStatus("d2", "Есть", Color.rgb(35, 128, 58), BoardStatus.DWG).also { it.done = true },
    )

    /** Список ИД 0.18 по умолчанию (номер → название) — у нетронутого переименовываем в ИГС. */
    private val OLD_ID = listOf("i0" to "Конструкция не принята", "i1" to "В оформлении", "i2" to "На подписи у Заказчика",
        "i3" to "Передано в ПТО", "i4" to "Скан в архиве")

    /**
     * Статусы доски под таблицы ИД (вызывается при чтении доски): нетронутый список ИД 0.18 становится списком ИГС
     * (номера i0–i3 те же, «Скан в архиве» → «Сдано в ПТО»), добавляются списки Чек-листа и DWG. Всё по одинаковым номерам —
     * у коллег получается то же самое.
     */
    fun upgrade(b: Board) {
        val ids = b.statuses.filter { it.group == BoardStatus.ID }
        val untouched = ids.size == OLD_ID.size && ids.zip(OLD_ID).all { (s, o) -> s.id == o.first && s.name == o.second }
        if (untouched) {
            val rest = b.statuses.filter { it.group != BoardStatus.ID }
            b.statuses.clear()
            b.statuses.addAll(igsStatuses())
            b.statuses.addAll(rest)
            for (s in b.stickers) if (s.sid == "i4") s.sid = IGS_PTO
            for (r in b.rows) if (r.sid == "i4") r.sid = IGS_PTO
        }
        if (b.statuses.none { it.group == BoardStatus.CK }) b.statuses.addAll(ckStatuses())
        if (b.statuses.none { it.group == BoardStatus.DWG }) b.statuses.addAll(dwgStatuses())
    }

    // ---------------------------------------------------------------- JSON

    fun tableJson(t: BoardTable): String {
        val sb = StringBuilder(160)
        sb.append("{\"id\":").append(JSONObject.quote(t.id)).append(",\"x\":").append(num(t.x)).append(",\"y\":").append(num(t.y))
        sb.append(",\"scale\":").append(t.scale.toDouble()).append(",\"title\":").append(JSONObject.quote(t.title))
        if (t.cols.isNotEmpty()) {
            sb.append(",\"cols\":[")
            for ((i, c) in t.cols.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append("{\"k\":").append(JSONObject.quote(c.k))
                if (c.name.isNotEmpty()) sb.append(",\"n\":").append(JSONObject.quote(c.name))
                if (c.hidden) sb.append(",\"h\":1")
                sb.append('}')
            }
            sb.append(']')
        }
        sb.append('}')
        return sb.toString()
    }

    fun parseTable(j: JSONObject): BoardTable {
        val t = BoardTable(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(),
            j.optDouble("scale", 1.0).toFloat().takeIf { it > 0f && it.isFinite() } ?: 1f, j.optString("title"))
        t.id = j.optString("id", "")
        j.optJSONArray("cols")?.let { a ->
            val seen = HashSet<String>()
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                val k = c.optString("k")
                if (k.isEmpty() || !seen.add(k) || !(k in TableCol.ORDER || k.startsWith("c"))) continue
                t.cols += TableCol(k, c.optString("n"), c.optInt("h") == 1)
            }
            // Встроенный столбец, которого нет в списке (список из будущей версии), — в конец, скрытым не делаем.
            if (t.cols.isNotEmpty()) for (k in TableCol.ORDER) if (k !in seen) t.cols += TableCol(k)
        }
        return t
    }

    fun recJson(r: BoardRec): String {
        val sb = StringBuilder(200)
        sb.append("{\"id\":").append(JSONObject.quote(r.id)).append(",\"t\":").append(JSONObject.quote(r.t))
        sb.append(",\"o\":").append(JSONObject.numberToString(r.o))
        fun s(k: String, v: String) { if (v.isNotEmpty()) sb.append(",\"").append(k).append("\":").append(JSONObject.quote(v)) }
        s("z", r.zah); s("c", r.con); s("w", r.work); s("d", r.date)
        s("ck", r.ck); s("dwg", r.dwg); s("sid", r.igs); s("dc", r.dcust); s("dp", r.dpto); s("who", r.who); s("n", r.note)
        if (r.extra.isNotEmpty()) sb.append(",\"x\":").append(JSONObject(r.extra as Map<*, *>).toString())
        sb.append('}')
        return sb.toString()
    }

    fun parseRec(j: JSONObject): BoardRec = BoardRec(j.optString("t"), j.optDouble("o", 0.0).takeIf { it.isFinite() } ?: 0.0).also { r ->
        r.id = j.optString("id", "")
        r.zah = j.optString("z"); r.con = j.optString("c"); r.work = j.optString("w"); r.date = j.optString("d")
        r.ck = j.optString("ck", CK_NONE).ifEmpty { CK_NONE }
        r.dwg = j.optString("dwg", DWG_NONE).ifEmpty { DWG_NONE }
        r.igs = j.optString("sid", IGS_NONE).ifEmpty { IGS_NONE }
        r.dcust = j.optString("dc"); r.dpto = j.optString("dp"); r.who = j.optString("who"); r.note = j.optString("n")
        j.optJSONObject("x")?.let { x -> for (k in x.keys()) x.optString(k).takeIf { it.isNotEmpty() }?.let { r.extra[k] = it } }
    }

    private fun num(v: Float): String { val sb = StringBuilder(); JsonNum.num(sb, v); return sb.toString() }

    // ---------------------------------------------------------------- поиск и порядок

    /** Для сравнения: строчные, «ё» → «е», без пробелов и «№», без слов «захватка»/«участок». */
    fun norm(s: String): String {
        var v = s.lowercase(Locale.ROOT).replace('ё', 'е')
        v = v.replace("захватка", "").replace("захв.", "").replace("участок", "")
        val sb = StringBuilder(v.length)
        for (ch in v) if (!ch.isWhitespace() && ch != '№' && ch != '"' && ch != '«' && ch != '»') sb.append(ch)
        return sb.toString().trimEnd('.', ',', ';', ':')
    }

    fun key(zah: String, con: String, work: String) = norm(zah) + "|" + norm(con) + "|" + norm(work)

    fun table(b: Board, id: String): BoardTable? = b.tables.firstOrNull { it.id == id }

    /** Таблица строки (null — таблицы уже нет: строку не показываем и не считаем). */
    fun tableOf(b: Board, r: BoardRec): BoardTable? = b.tables.firstOrNull { it.id == r.t }

    /** Все строки таблицы по порядку (без отбора). */
    fun allRows(b: Board, t: BoardTable): List<BoardRec> = b.recs.filter { it.t == t.id }.sortedWith(compareBy<BoardRec>({ it.o }, { it.id }))

    /** Строки таблицы, видимые с учётом отбора. */
    fun rowsOf(b: Board, t: BoardTable): List<BoardRec> {
        val all = allRows(b, t)
        val f = t.filter ?: return all
        return if (f.isEmpty) all else all.filter { f.matches(it) }
    }

    /** Все строки, у которых есть таблица (для сводки). */
    fun liveRecs(b: Board): List<BoardRec> {
        val ids = HashSet<String>()
        for (x in b.tables) ids += x.id
        return b.recs.filter { it.t in ids }
    }

    /** Порядок для новой строки в конце таблицы. */
    fun endOrder(b: Board, t: BoardTable): Double = (b.recs.filter { it.t == t.id }.maxOfOrNull { it.o } ?: 0.0) + 1.0

    /** Порядок для строки сразу после r (между ней и следующей). */
    fun orderAfter(b: Board, r: BoardRec): Double {
        val next = b.recs.filter { it.t == r.t && it.o > r.o }.minOfOrNull { it.o }
        return if (next == null) r.o + 1.0 else (r.o + next) / 2.0
    }

    /** Сравнение «по-человечески»: КЖ6.1.2 раньше КЖ6.1.10, «Захватка 2» раньше «Захватка 10». */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i; while (ie < a.length && a[ie].isDigit()) ie++
                var je = j; while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0'); val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie; j = je
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** Следующая захватка: первое число +1 («4.1» → «5», «Захватка№2 (6-7/А-Е)» → «Захватка№3 (6-7/А-Е)»). */
    fun nextZah(z: String): String {
        val m = Regex("\\d+").find(z) ?: return z
        val n = m.value.toLongOrNull() ?: return z
        var rest = z.substring(m.range.last + 1)
        if (rest.startsWith(".")) rest = rest.replaceFirst(Regex("^\\.\\d+"), "")
        return z.substring(0, m.range.first) + (n + 1) + rest
    }

    /** Размер новой таблицы: как у таблиц, что уже есть на доске (все одного размера); первой — «в рост» экрана. */
    fun defaultScale(b: Board, density: Float): Float {
        val s = b.tables.map { it.scale }.sorted()
        return if (s.isNotEmpty()) s[s.size / 2] else density / b.camZ
    }

    /** Статус своего списка по номеру (нет такого — первый в списке). */
    fun status(b: Board, group: String, id: String): BoardStatus {
        val list = b.statuses(group)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: b.status(id)
    }

    fun groupOf(k: String): String = when (k) { TableCol.CK -> BoardStatus.CK; TableCol.DWG -> BoardStatus.DWG; else -> BoardStatus.ID }

    // ---------------------------------------------------------------- даты

    /** «2026-08-26» → «26.08.26». */
    fun dateShort(iso: String): String {
        val p = iso.split('-')
        if (p.size != 3) return iso
        return p[2] + "." + p[1] + "." + p[0].takeLast(2)
    }

    /** «2026-08-26» → «26.08.2026». */
    fun dateLong(iso: String): String {
        val p = iso.split('-')
        if (p.size != 3) return iso
        return p[2] + "." + p[1] + "." + p[0]
    }

    /** Разобрать дату: «26.08.2026», «26.08.26», «2026-08-26» → «2026-08-26»; не дата — пусто. */
    fun parseDate(s: String): String {
        val t = s.trim()
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(t)?.let { m ->
            return iso(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("^(\\d{1,2})[./](\\d{1,2})[./](\\d{2,4})").find(t)?.let { m ->
            var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            return iso(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        return ""
    }

    fun iso(y: Int, m: Int, d: Int): String =
        if (m in 1..12 && d in 1..31 && y in 1900..2200) String.format(Locale.US, "%04d-%02d-%02d", y, m, d) else ""

    fun today(): String {
        val c = java.util.Calendar.getInstance()
        return iso(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    /** Сколько дней прошло с даты (по календарю этого планшета). */
    fun daysSince(iso: String, today: String = today()): Int {
        val a = excelDay(iso) ?: return 0
        val b = excelDay(today) ?: return 0
        return b - a
    }

    /** «2026-08-26» → дни Excel (от 30.12.1899) — для выгрузки датой. */
    fun excelDay(iso: String): Int? {
        val p = iso.split('-').mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return null
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val base = java.util.GregorianCalendar(utc).apply { clear(); set(1899, 11, 30) }
        val c = java.util.GregorianCalendar(utc).apply { clear(); set(p[0], p[1] - 1, p[2]) }
        return ((c.timeInMillis - base.timeInMillis) / 86_400_000L).toInt()
    }

    // ---------------------------------------------------------------- значение ячейки

    fun cellText(b: Board, r: BoardRec, k: String): String = when (k) {
        TableCol.CK, TableCol.DWG, TableCol.IGS -> status(b, groupOf(k), r.get(k)).name
        TableCol.DATE, TableCol.DCUST, TableCol.DPTO -> dateShort(r.get(k))
        else -> r.get(k)
    }

    /** Подсказки для текстового столбца: что уже встречается на доске, частые первыми. */
    fun suggestions(b: Board, k: String, limit: Int = 12): List<String> {
        val m = HashMap<String, Int>()
        for (r in b.recs) { val v = r.get(k).trim(); if (v.isNotEmpty()) m[v] = (m[v] ?: 0) + 1 }
        if (k == TableCol.WORK) for (w in WORKS) if (w !in m) m[w] = 0
        return m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(limit).map { it.key }
    }
}

/**
 * Сводка по таблицам ИД (как лист «Статистика» Владимира, ТЗ, раздел 9): всего схем, сдано в ПТО / не сдано, на подписи,
 * долго на подписи, по статусам, по шифрам, по исполнителям, Чек-лист и DWG; контроль. Где считать: вся доска, раздел
 * ([fr]) или одна таблица ([only]).
 */
class IgsSummary(val b: Board, val fr: BoardFrame? = null, val only: BoardTable? = null, val longDays: Int = 7, val today: String = IdTables.today()) {
    val tables: List<Pair<BoardTable, List<BoardRec>>> = b.tables
        .filter { (only == null || it === only) && (fr == null || b.sectionOf(it) === fr) }
        .sortedWith { p, q -> IdTables.naturalCompare(p.title, q.title) }
        .map { it to IdTables.allRows(b, it) }
    val recs: List<BoardRec> = tables.flatMap { it.second }
    val igs: List<BoardStatus> = b.statuses(BoardStatus.ID)
    val ck: List<BoardStatus> = b.statuses(BoardStatus.CK)
    val dwg: List<BoardStatus> = b.statuses(BoardStatus.DWG)

    fun igsOf(r: BoardRec) = IdTables.status(b, BoardStatus.ID, r.igs)
    fun done(r: BoardRec) = igsOf(r).done
    fun ckDone(r: BoardRec) = IdTables.status(b, BoardStatus.CK, r.ck).done

    val total get() = recs.size
    val doneCount get() = recs.count { done(it) }
    val signing get() = recs.count { it.igs == IdTables.IGS_CUST || it.igs == IdTables.IGS_AN }

    /** Долго на подписи у заказчика (с даты «Отдал заказчику»). */
    fun isLong(r: BoardRec) = r.igs == IdTables.IGS_CUST && r.dcust.isNotEmpty() && IdTables.daysSince(r.dcust, today) > longDays

    /** Контроль: чек-лист предъявлен, а схема «Не предоставлена». */
    val ctrlNotGiven: List<BoardRec> get() = recs.filter { ckDone(it) && it.igs == IdTables.IGS_NOT_GIVEN }
    /** Контроль: схема «В работе» и дальше, а чек-лист не «Предъявлено». */
    val ctrlNoChecklist: List<BoardRec> get() = recs.filter { it.igs != IdTables.IGS_NONE && it.igs != IdTables.IGS_NOT_GIVEN && !ckDone(it) }
    val ctrlLong: List<BoardRec> get() = recs.filter { isLong(it) }

    /** Исполнители по алфавиту (пусто — «Не указан» в конце). */
    val whos: List<String> = recs.map { it.who.trim() }.distinct().sortedWith { p, q ->
        if (p.isEmpty() != q.isEmpty()) (if (p.isEmpty()) 1 else -1) else IdTables.naturalCompare(p, q)
    }

    fun whoName(w: String) = w.ifEmpty { "Не указан" }

    fun count(rows: List<BoardRec>, s: BoardStatus, k: String = TableCol.IGS) =
        rows.count { IdTables.status(b, IdTables.groupOf(k), it.get(k)).id == s.id }

    /** Сводка для Excel через табуляцию: шифры × статусы ИГС, затем исполнители. */
    fun tsv(): String {
        val sb = StringBuilder()
        fun head(first: String) {
            sb.append(first).append("\tВсего схем\tСдано в ПТО\tНе сдано")
            for (s in igs) sb.append('\t').append(s.name)
            sb.append('\n')
        }
        fun line(name: String, rows: List<BoardRec>) {
            sb.append(name).append('\t').append(rows.size).append('\t').append(rows.count { done(it) }).append('\t').append(rows.count { !done(it) })
            for (s in igs) sb.append('\t').append(count(rows, s))
            sb.append('\n')
        }
        head("Шифр")
        for ((t, rows) in tables) line(t.title, rows)
        line("Итого", recs)
        if (whos.isNotEmpty()) {
            sb.append('\n')
            head("Исполнитель")
            for (w in whos) line(whoName(w), recs.filter { it.who.trim() == w })
        }
        return sb.toString()
    }
}
