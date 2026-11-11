package party.shulya.freewrite

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Таблица ИД на доске (0.20, FW-012).
 *
 * Два вида:
 *  - [REG] — реестр по шифру проекта («КЖ 6.1.1»): стоит у чертежа, в нём все схемы ИГС этого шифра;
 *  - [LIST] — подборка («На подпись 11.11»): ставится где удобно, строки в ней — те же строки реестров
 *    (статус, поменянный в подборке, сразу виден в реестре и наоборот).
 *
 * Сами строки — отдельные предметы доски ([BoardRec]): правки разных строк одной таблицы с разных планшетов не
 * мешают друг другу. Таблица хранит только место, размер и название; все строки у неё одного размера и ровно
 * по столбцам — размер один на всю таблицу ([scale]).
 */
class BoardTable(var x: Float, var y: Float, var scale: Float, var title: String, val type: String = REG) {
    var id: String = Ids.next()

    val isList: Boolean get() = type == LIST

    /** Раскладка (считает [TableDraw]): пересчитывается, когда меняются строки, название или статусы. */
    internal var lay: TableLayout? = null

    companion object {
        const val REG = "reg"
        const val LIST = "list"
    }
}

/**
 * Строка таблицы ИД — одна схема ИГС: захватка/участок, тип конструкции, вид работ, дата работ, Чек-лист, DWG,
 * ИГС (статус из списка ИД), примечание, исполнитель. [t] — реестр, в котором строка живёт; [o] — порядок в нём;
 * [ls] — подборки, в которые она входит.
 */
class BoardRec(var t: String, var o: Double) {
    var id: String = Ids.next()
    var zah = ""
    var con = ""
    var work = ""
    /** Дата работ «2026-08-26» (пусто — нет). */
    var date = ""
    var ck = false
    var dwg = false
    var sid = ""
    var note = ""
    var who = ""
    val ls = ArrayList<String>()

    fun copy(): BoardRec = BoardRec(t, o).also { c ->
        c.zah = zah; c.con = con; c.work = work; c.date = date; c.ck = ck; c.dwg = dwg; c.sid = sid
        c.note = note; c.who = who; c.ls.addAll(ls)
    }

    /** Ключ для поиска «та же схема»: захватка + конструкция + вид работ (без пробелов, «№», регистра). */
    fun key(): String = IdTables.key(zah, con, work)
}

/** Столбцы таблицы ИД. Порядок — как в таблице Excel Владимира. */
object Col {
    const val CIPHER = 0
    const val ZAH = 1
    const val CON = 2
    const val WORK = 3
    const val DATE = 4
    const val CK = 5
    const val DWG = 6
    const val IGS = 7
    const val NOTE = 8
    const val WHO = 9

    val NAMES = arrayOf("Шифр", "Захватка", "Конструкция", "Вид работ", "Дата", "Чек-лист", "DWG", "ИГС", "Примечание", "Исполнитель")
    val REG = intArrayOf(ZAH, CON, WORK, DATE, CK, DWG, IGS, NOTE, WHO)
    val LIST = intArrayOf(CIPHER, ZAH, CON, WORK, DATE, CK, DWG, IGS, NOTE, WHO)
}

object IdTables {
    /** Виды работ для быстрого выбора (у одной захватки обычно «Опалубка» и «Бетон»). */
    val WORKS = listOf("Опалубка", "Армирование", "Бетон", "Разработка", "Обратная засыпка", "Кладка", "Монтаж")

    // ---------------------------------------------------------------- JSON

    fun tableJson(t: BoardTable): String =
        "{\"id\":${JSONObject.quote(t.id)},\"type\":${JSONObject.quote(t.type)},\"x\":${num(t.x)},\"y\":${num(t.y)}" +
            ",\"scale\":${t.scale.toDouble()},\"title\":${JSONObject.quote(t.title)}}"

    fun parseTable(j: JSONObject): BoardTable =
        BoardTable(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), j.optDouble("scale", 1.0).toFloat().takeIf { it > 0f && it.isFinite() } ?: 1f,
            j.optString("title"), if (j.optString("type") == BoardTable.LIST) BoardTable.LIST else BoardTable.REG)
            .also { it.id = j.optString("id", "") }

    fun recJson(r: BoardRec): String {
        val sb = StringBuilder(160)
        sb.append("{\"id\":").append(JSONObject.quote(r.id)).append(",\"t\":").append(JSONObject.quote(r.t))
        sb.append(",\"o\":").append(JSONObject.numberToString(r.o))
        fun s(k: String, v: String) { if (v.isNotEmpty()) sb.append(",\"").append(k).append("\":").append(JSONObject.quote(v)) }
        s("z", r.zah); s("c", r.con); s("w", r.work); s("d", r.date)
        if (r.ck) sb.append(",\"ck\":1")
        if (r.dwg) sb.append(",\"dwg\":1")
        s("sid", r.sid); s("n", r.note); s("who", r.who)
        if (r.ls.isNotEmpty()) sb.append(",\"ls\":").append(JSONArray(r.ls).toString())
        sb.append('}')
        return sb.toString()
    }

    fun parseRec(j: JSONObject): BoardRec = BoardRec(j.optString("t"), j.optDouble("o", 0.0).takeIf { it.isFinite() } ?: 0.0).also { r ->
        r.id = j.optString("id", "")
        r.zah = j.optString("z"); r.con = j.optString("c"); r.work = j.optString("w"); r.date = j.optString("d")
        r.ck = j.optInt("ck") == 1; r.dwg = j.optInt("dwg") == 1
        r.sid = j.optString("sid"); r.note = j.optString("n"); r.who = j.optString("who")
        j.optJSONArray("ls")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { if (it !in r.ls) r.ls += it } }
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

    /** Шифр для сравнения: «КЖ 6.1.1» = «кж6.1.1». */
    fun cipherKey(s: String): String = s.lowercase(Locale.ROOT).replace('ё', 'е').filter { !it.isWhitespace() }

    fun key(zah: String, con: String, work: String) = norm(zah) + "|" + norm(con) + "|" + norm(work)

    fun registers(b: Board): List<BoardTable> = b.tables.filter { !it.isList }
    fun lists(b: Board): List<BoardTable> = b.tables.filter { it.isList }

    fun table(b: Board, id: String): BoardTable? = b.tables.firstOrNull { it.id == id }

    /** Реестр строки (null — реестра уже нет: строку не показываем и не считаем). */
    fun registerOf(b: Board, r: BoardRec): BoardTable? = b.tables.firstOrNull { it.id == r.t && !it.isList }

    fun findRegister(b: Board, cipher: String): BoardTable? {
        val k = cipherKey(cipher)
        if (k.isEmpty()) return null
        return b.tables.firstOrNull { !it.isList && cipherKey(it.title) == k }
    }

    fun findRec(b: Board, reg: BoardTable, zah: String, con: String, work: String): BoardRec? {
        val k = key(zah, con, work)
        return b.recs.firstOrNull { it.t == reg.id && it.key() == k }
    }

    /** Строки таблицы по порядку. Реестр — свои строки; подборка — строки реестров, в неё входящие (по шифру, затем по порядку). */
    fun rowsOf(b: Board, t: BoardTable): List<BoardRec> {
        if (!t.isList) return b.recs.filter { it.t == t.id }.sortedWith(compareBy<BoardRec>({ it.o }, { it.id }))
        val regs = HashMap<String, BoardTable>()
        for (x in b.tables) if (!x.isList) regs[x.id] = x
        return b.recs.filter { t.id in it.ls && regs.containsKey(it.t) }
            .sortedWith { p, q ->
                val c = naturalCompare(regs[p.t]!!.title, regs[q.t]!!.title)
                if (c != 0) c else compareValuesBy(p, q, { it.o }, { it.id })
            }
    }

    /** Все строки, у которых есть реестр (для сводки). */
    fun liveRecs(b: Board): List<BoardRec> {
        val regs = HashSet<String>()
        for (x in b.tables) if (!x.isList) regs += x.id
        return b.recs.filter { it.t in regs }
    }

    /** Порядок для новой строки в конце реестра. */
    fun endOrder(b: Board, reg: BoardTable): Double = (b.recs.filter { it.t == reg.id }.maxOfOrNull { it.o } ?: 0.0) + 1.0

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

    /** Размер новой таблицы: как у таблиц, что уже есть на доске (все одного размера); первой — «в рост» экрана. */
    fun defaultScale(b: Board, density: Float): Float {
        val s = b.tables.map { it.scale }.sorted()
        return if (s.isNotEmpty()) s[s.size / 2] else density / b.camZ
    }

    /**
     * «Сдано» по умолчанию: если ни один статус ИД не отмечен «сдано», отмечаем «Передано в ПТО» и «Скан в архиве»
     * (статусы i3, i4 списка 0.18). Вызывается, когда на доске появляется первая таблица.
     */
    fun ensureDoneDefaults(b: Board) {
        val ids = b.statuses(BoardStatus.ID)
        if (ids.any { it.done }) return
        for (s in ids) if (s.id == "i3" || s.id == "i4") s.done = true
    }

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

    /** Дата Excel (дни от 30.12.1899) → «2026-08-26». */
    fun fromExcelDay(v: Double): String {
        if (!v.isFinite() || v < 1 || v > 120000) return ""
        val c = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
        c.clear(); c.set(1899, 11, 30)
        c.add(java.util.Calendar.DAY_OF_MONTH, v.toInt())
        return iso(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    /** «2026-08-26» → дни Excel (для выгрузки датой). */
    fun toExcelDay(iso: String): Int? {
        val p = iso.split('-').mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return null
        val base = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(1899, 11, 30) }
        val c = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(p[0], p[1] - 1, p[2]) }
        return ((c.timeInMillis - base.timeInMillis) / 86_400_000L).toInt()
    }

    // ---------------------------------------------------------------- значение ячейки

    fun cellText(b: Board, r: BoardRec, col: Int): String = when (col) {
        Col.CIPHER -> registerOf(b, r)?.title ?: ""
        Col.ZAH -> r.zah
        Col.CON -> r.con
        Col.WORK -> r.work
        Col.DATE -> dateShort(r.date)
        Col.CK -> if (r.ck) "✓" else "✗"
        Col.DWG -> if (r.dwg) "✓" else "✗"
        Col.IGS -> b.status(r.sid).name
        Col.NOTE -> r.note
        Col.WHO -> r.who
        else -> ""
    }
}

/**
 * Сводка по таблицам ИД (как лист «Статистика» в Excel Владимира): всего схем, сдано / не сдано,
 * по статусам, по шифрам, по исполнителям, по подборкам. fr — только реестры внутри раздела.
 */
class IgsSummary(val b: Board, val fr: BoardFrame? = null) {
    val registers: List<Pair<BoardTable, List<BoardRec>>> = IdTables.registers(b)
        .filter { fr == null || b.sectionOf(it) === fr }
        .sortedWith { p, q -> IdTables.naturalCompare(p.title, q.title) }
        .map { it to IdTables.rowsOf(b, it) }
    val recs: List<BoardRec> = registers.flatMap { it.second }
    val lists: List<Pair<BoardTable, List<BoardRec>>> = IdTables.lists(b).map { t -> t to IdTables.rowsOf(b, t).filter { r -> registers.any { it.first.id == r.t } } }
        .filter { fr == null || it.second.isNotEmpty() || b.sectionOf(it.first) === fr }
    val ids: List<BoardStatus> = b.statuses(BoardStatus.ID)
    val hasDone: Boolean = ids.any { it.done }

    fun done(r: BoardRec) = b.status(r.sid).done
    val total get() = recs.size
    val doneCount get() = recs.count { done(it) }

    /** Исполнители по алфавиту (пусто — «Не указан»). */
    val whos: List<String> = recs.map { it.who.trim() }.distinct().sortedWith { p, q ->
        if (p.isEmpty() != q.isEmpty()) (if (p.isEmpty()) 1 else -1) else IdTables.naturalCompare(p, q)
    }

    fun whoName(w: String) = w.ifEmpty { "Не указан" }

    /** Сколько строк с этим статусом (статус, которого уже нет в списке, считается первым). */
    fun count(rows: List<BoardRec>, sid: String) = rows.count { b.status(it.sid).id == sid }

    /** Таблица для Excel (через табуляцию): шифры × статусы, затем исполнители. */
    fun tsv(): String {
        val sb = StringBuilder()
        sb.append("Шифр\tВсего схем")
        if (hasDone) sb.append("\tСдано\tНе сдано")
        sb.append("\tЧек-лист ✓\tDWG ✓")
        for (s in ids) sb.append('\t').append(s.name)
        sb.append('\n')
        fun line(name: String, rows: List<BoardRec>) {
            sb.append(name).append('\t').append(rows.size)
            if (hasDone) sb.append('\t').append(rows.count { done(it) }).append('\t').append(rows.count { !done(it) })
            sb.append('\t').append(rows.count { it.ck }).append('\t').append(rows.count { it.dwg })
            for (s in ids) sb.append('\t').append(count(rows, s.id))
            sb.append('\n')
        }
        for ((t, rows) in registers) line(t.title, rows)
        line("Итого", recs)
        if (whos.isNotEmpty()) {
            sb.append('\n').append("Исполнитель\tВсего схем")
            if (hasDone) sb.append("\tСдано\tНе сдано")
            sb.append("\tЧек-лист ✓\tDWG ✓")
            for (s in ids) sb.append('\t').append(s.name)
            sb.append('\n')
            for (w in whos) line(whoName(w), recs.filter { it.who.trim() == w })
        }
        if (lists.isNotEmpty()) {
            sb.append('\n').append("Подборка\tСтрок")
            for (s in ids) sb.append('\t').append(s.name)
            sb.append('\n')
            for ((t, rows) in lists) {
                sb.append(t.title).append('\t').append(rows.size)
                for (s in ids) sb.append('\t').append(count(rows, s.id))
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
