package party.shulya.freewrite

import android.graphics.Color
import org.json.JSONObject

/**
 * Ведомость (ТЗ «Учёт ИГС», раздел 6; шаг 2, 0.21): пачка схем, которую отдают авторскому надзору, заказчику или в ПТО,
 * либо просто список («что делать завтра»). Табличка в любом месте доски.
 *
 * Сама ведомость хранит место, размер, вид, номер («З-12»), кому и дату передачи (пусто — черновик). Её позиции
 * ([BoardVItem]) — отдельные предметы доски со ссылкой на схему, своим результатом и датой ответа: двое отмечают ответы
 * по разным схемам — правки не мешают друг другу. Закрыта — сама, когда у всех позиций есть результат.
 */
class BoardVedom(var x: Float, var y: Float, var scale: Float, var kind: String, var num: String) {
    var id: String = Ids.next()
    /** Кому: «ГИП Иванов», «ООО Заказчик». */
    var to = ""
    /** Дата передачи «2026-11-11»; пусто — черновик. */
    var given = ""
    /** «Ведомость от …» (0.25): день, когда завели (первая «Сборка» дня); пусто — не записан (до 0.25). */
    var made = ""

    /** Раскладка (считает [VedomDraw]). */
    internal var lay: VedomLayout? = null

    val isDraft: Boolean get() = given.isEmpty()
}

/**
 * Позиция ведомости: схема [r] (строка таблицы), порядок [o], результат [res] и его дата [rd].
 * lt/lu/lz/lc/lw — шифр, участок, захватка, тип конструкции, вид работ на момент добавления: ведомость в архиве
 * читается, даже если строку потом удалили.
 */
class BoardVItem(var v: String, var r: String, var o: Double) {
    var id: String = Ids.next()
    /** "" — ждём ответа; [Vedoms.OK], [Vedoms.NO], [Vedoms.FIX]. */
    var res = ""
    var rd = ""
    var lt = ""
    var lu = ""
    var lz = ""
    var lc = ""
    var lw = ""
}

/** Подпись позиции ведомости: шифр (полное имя таблицы), участок, захватка, тип конструкции, вид работ. */
class VLabel(val title: String, val uch: String, val zah: String, val con: String, val work: String)

/**
 * Событие истории схемы (ТЗ, раздел 7): кто, когда, что было → стало ([k] — столбец: статус или дата), по какой ведомости
 * ([vn] — её номер). Только дописывается: правки двух людей в одной ячейке видны обе.
 */
class BoardEvent(val r: String, val at: Long, val who: String, val k: String, val from: String, val to: String,
                 val vn: String = "", val note: String = "") {
    var id: String = Ids.next()
        set(value) { field = value; cache = null }
    private var cache: String? = null

    /** Строка JSON (одна на событие — оно не меняется; снимки отмены делят строки). */
    fun json(): String = cache ?: Vedoms.eventJson(this).also { cache = it }
}

object Vedoms {
    const val AN = "an"
    const val CUST = "cust"
    const val PTO = "pto"
    const val LIST = "list"
    val KINDS = listOf(CUST, AN, PTO, LIST)

    const val OK = "ok"
    const val NO = "no"
    const val FIX = "fix"

    /** Столбцы, правки которых пишутся в историю: статусы, даты и объём по схеме. */
    val HIST_KEYS = setOf(TableCol.DATE, TableCol.CK, TableCol.DWG, TableCol.IGS, TableCol.DCUST, TableCol.DPTO, TableCol.VOL)

    fun kindName(k: String) = when (k) {
        AN -> "На согласование АН"; CUST -> "На подпись заказчику"; PTO -> "Сдача в ПТО"; else -> "Список"
    }

    /** Буква номера: «А-3», «З-12», «П-2», «С-1». */
    fun prefix(k: String) = when (k) { AN -> "А"; CUST -> "З"; PTO -> "П"; else -> "С" }

    fun kindColor(k: String) = when (k) {
        AN -> Color.rgb(123, 63, 196); CUST -> Color.rgb(184, 138, 0); PTO -> Color.rgb(35, 128, 58); else -> Color.rgb(75, 88, 115)
    }

    /** Отмечают ли в ведомости ответы (АН и заказчик); ПТО закрывается сразу, список — просто список. */
    fun hasAnswers(k: String) = k == AN || k == CUST

    /** Результаты для ответа по виду ведомости. */
    fun results(k: String): List<String> = when (k) { AN -> listOf(OK, NO, FIX); CUST -> listOf(OK, FIX); else -> emptyList() }

    fun resName(k: String, res: String) = when (res) {
        OK -> if (k == AN) "Согласовано" else if (k == PTO) "Сдано" else "Подписано"
        NO -> "Не согласовано"
        FIX -> "Замечания"
        else -> "Ждём"
    }

    fun resColor(res: String) = when (res) {
        OK -> Color.rgb(35, 128, 58); NO -> Color.rgb(142, 27, 47); FIX -> Color.rgb(212, 100, 12); else -> Color.rgb(138, 147, 163)
    }

    /** Что значит передача по виду ведомости: «На подписи у заказчика», «На подписи АН», «Сдано в ПТО». */
    fun giveLabel(k: String) = when (k) { AN -> "На подписи АН"; CUST -> "На подписи у заказчика"; PTO -> "Сдано в ПТО"; else -> "" }

    /** Кнопка передачи: «Унесли заказчику», «Унесли на АН», «Сдали в ПТО». */
    fun giveVerb(k: String) = when (k) { AN -> "Унесли на АН"; CUST -> "Унесли заказчику"; PTO -> "Сдали в ПТО"; else -> "" }

    /** Схемы с этими статусами при сборке ведомости получают «Сборка» (дальше по пути — не трогаем). */
    private val TO_ASSEMBLY = setOf(IdTables.IGS_WORK, IdTables.IGS_NOT_GIVEN, IdTables.IGS_BACK, IdTables.IGS_AN_NO)

    /** Статус ИГС, который ставит передача ведомости ("" — никакого). */
    fun giveStatus(k: String) = when (k) { AN -> IdTables.IGS_AN; CUST -> IdTables.IGS_CUST; PTO -> IdTables.IGS_PTO; else -> "" }

    // ---------------------------------------------------------------- JSON

    private fun q(s: String) = JSONObject.quote(s)
    private fun f(v: Float): String { val sb = StringBuilder(); JsonNum.num(sb, v); return sb.toString() }

    fun vedomJson(v: BoardVedom): String {
        val sb = StringBuilder(160)
        sb.append("{\"id\":").append(q(v.id)).append(",\"x\":").append(f(v.x)).append(",\"y\":").append(f(v.y))
        sb.append(",\"scale\":").append(v.scale.toDouble()).append(",\"kind\":").append(q(v.kind)).append(",\"num\":").append(q(v.num))
        if (v.to.isNotEmpty()) sb.append(",\"to\":").append(q(v.to))
        if (v.given.isNotEmpty()) sb.append(",\"given\":").append(q(v.given))
        if (v.made.isNotEmpty()) sb.append(",\"made\":").append(q(v.made))
        sb.append('}')
        return sb.toString()
    }

    fun parseVedom(j: JSONObject): BoardVedom {
        val kind = j.optString("kind").takeIf { it in KINDS } ?: LIST
        return BoardVedom(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(),
            j.optDouble("scale", 1.0).toFloat().takeIf { it > 0f && it.isFinite() } ?: 1f, kind, j.optString("num")).also {
            it.id = j.optString("id", "")
            it.to = j.optString("to"); it.given = j.optString("given"); it.made = j.optString("made")
        }
    }

    fun itemJson(i: BoardVItem): String {
        val sb = StringBuilder(160)
        sb.append("{\"id\":").append(q(i.id)).append(",\"v\":").append(q(i.v)).append(",\"r\":").append(q(i.r))
        sb.append(",\"o\":").append(JSONObject.numberToString(i.o))
        fun s(k: String, x: String) { if (x.isNotEmpty()) sb.append(",\"").append(k).append("\":").append(q(x)) }
        s("res", i.res); s("rd", i.rd); s("lt", i.lt); s("lu", i.lu); s("lz", i.lz); s("lc", i.lc); s("lw", i.lw)
        sb.append('}')
        return sb.toString()
    }

    fun parseItem(j: JSONObject) = BoardVItem(j.optString("v"), j.optString("r"), j.optDouble("o", 0.0).takeIf { it.isFinite() } ?: 0.0).also {
        it.id = j.optString("id", "")
        it.res = j.optString("res").takeIf { r -> r == OK || r == NO || r == FIX }.orEmpty()
        it.rd = j.optString("rd"); it.lt = j.optString("lt"); it.lu = j.optString("lu"); it.lz = j.optString("lz"); it.lc = j.optString("lc"); it.lw = j.optString("lw")
    }

    fun eventJson(e: BoardEvent): String {
        val sb = StringBuilder(160)
        sb.append("{\"id\":").append(q(e.id)).append(",\"r\":").append(q(e.r)).append(",\"at\":").append(e.at)
        fun s(k: String, x: String) { if (x.isNotEmpty()) sb.append(",\"").append(k).append("\":").append(q(x)) }
        s("who", e.who); s("k", e.k); s("f", e.from); s("t", e.to); s("vn", e.vn); s("n", e.note)
        sb.append('}')
        return sb.toString()
    }

    fun parseEvent(j: JSONObject) = BoardEvent(j.optString("r"), j.optLong("at"), j.optString("who"), j.optString("k"),
        j.optString("f"), j.optString("t"), j.optString("vn"), j.optString("n")).also { it.id = j.optString("id", "") }

    // ---------------------------------------------------------------- состав и сводка

    fun vedom(b: Board, id: String): BoardVedom? = b.vedoms.firstOrNull { it.id == id }

    /** Позиции ведомости по порядку. */
    fun items(b: Board, v: BoardVedom): List<BoardVItem> = b.vitems.filter { it.v == v.id }.sortedWith(compareBy<BoardVItem>({ it.o }, { it.id }))

    fun rec(b: Board, i: BoardVItem): BoardRec? = b.recs.firstOrNull { it.id == i.r }?.takeIf { IdTables.tableOf(b, it) != null }

    fun endOrder(b: Board, v: BoardVedom): Double = (b.vitems.filter { it.v == v.id }.maxOfOrNull { it.o } ?: 0.0) + 1.0

    /** Шифр, участок, захватка, тип конструкции, вид работ позиции: по строке таблицы (если она есть) или как было при добавлении. */
    fun label(b: Board, i: BoardVItem): VLabel {
        val r = rec(b, i) ?: return VLabel(i.lt, i.lu, i.lz, i.lc, i.lw)
        return VLabel(IdTables.tableOf(b, r)?.let { IdTables.fullTitle(b, it) }.orEmpty(), r.uch, r.zah, r.con, r.work)
    }

    fun labelText(b: Board, i: BoardVItem): String {
        val l = label(b, i)
        return listOf(l.title, l.uch, l.zah.takeIf { it.isNotEmpty() }?.let { "захв. $it" }.orEmpty(), l.con, l.work).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun remember(b: Board, i: BoardVItem) {
        val r = rec(b, i) ?: return
        i.lt = IdTables.tableOf(b, r)?.let { IdTables.fullTitle(b, it) }.orEmpty(); i.lu = r.uch; i.lz = r.zah; i.lc = r.con; i.lw = r.work
    }

    /** Закрыта: ПТО — сразу после передачи; АН и заказчик — когда у всех позиций есть результат. Список не закрывается. */
    fun isClosed(b: Board, v: BoardVedom): Boolean {
        if (v.isDraft) return false
        return when (v.kind) {
            PTO -> true
            AN, CUST -> items(b, v).let { it.isNotEmpty() && it.all { x -> x.res.isNotEmpty() } }
            else -> false
        }
    }

    /** Открыта и ждёт ответа: передана, есть позиции без результата. */
    fun isOpen(b: Board, v: BoardVedom) = !v.isDraft && hasAnswers(v.kind) && !isClosed(b, v)

    /** Кнопка состояния в шапке ведомости: «Сборка ▾», «На подписи у заказчика · 12.11.26 ▾», «Закрыта ✓», «Список». */
    fun stateChip(b: Board, v: BoardVedom): String = when {
        v.kind == LIST -> "Список"
        v.isDraft -> if (v.made.isNotEmpty()) "Сборка от " + IdTables.dateShort(v.made) + " ▾" else "Сборка ▾"
        isClosed(b, v) -> "Закрыта ✓"
        else -> giveLabel(v.kind) + " от " + IdTables.dateShort(v.given) + " ▾"
    }

    fun state(b: Board, v: BoardVedom): String = when {
        v.isDraft -> if (v.kind == LIST) "список" else "сборка"
        isClosed(b, v) -> "закрыта"
        hasAnswers(v.kind) -> "передана " + IdTables.dateShort(v.given)
        else -> "передана " + IdTables.dateShort(v.given)
    }

    /** «15 схем · подписано 12 · замечания 2 · ждём 1 (6 дн.)». */
    fun summary(b: Board, v: BoardVedom, today: String = IdTables.today()): String {
        val list = items(b, v)
        val head = Ui.plural(list.size, "схема", "схемы", "схем")
        if (v.isDraft || !hasAnswers(v.kind)) return head
        val parts = arrayListOf(head)
        for (res in results(v.kind)) {
            val n = list.count { it.res == res }
            if (n > 0) parts += resName(v.kind, res).lowercase() + " " + n
        }
        val wait = list.count { it.res.isEmpty() }
        if (wait > 0) parts += "ждём $wait (${IdTables.daysSince(v.given, today)} дн.)"
        return parts.joinToString(" · ")
    }

    fun title(v: BoardVedom) = listOf(v.num, kindName(v.kind)).filter { it.isNotBlank() }.joinToString(" · ")

    /** Следующий номер ведомости этого вида: «З-12» → «З-13». */
    fun nextNum(b: Board, kind: String): String {
        val p = prefix(kind)
        var max = 0
        for (v in b.vedoms) {
            if (v.kind != kind) continue
            Regex("(\\d+)\\s*$").find(v.num)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > max) max = it }
        }
        return "$p-${max + 1}"
    }

    /** Другие ведомости, где эта схема ждёт ответа или лежит в сборке (АН, заказчик, ПТО; списки не считаются). */
    fun waitingElsewhere(b: Board, rec: BoardRec, except: BoardVedom?): List<BoardVedom> {
        val out = ArrayList<BoardVedom>()
        for (i in b.vitems) {
            if (i.r != rec.id || i.res.isNotEmpty() || i.v == except?.id) continue
            val v = vedom(b, i.v) ?: continue
            if (v.kind != LIST && (v.isDraft || hasAnswers(v.kind)) && out.none { it === v }) out += v
        }
        return out
    }

    /**
     * Ведомость сегодняшней сборки (0.25, Владимир): заведена сегодня, ещё на столе (не передана), не список. Новые
     * «Сборки» дня ложатся в неё сами.
     */
    fun assemblyOf(b: Board, day: String): BoardVedom? =
        b.vedoms.lastOrNull { it.isDraft && it.kind != LIST && it.made == day }

    /** Схемы в «Сборке», которых нет ни в одной ведомости на столе или в ожидании ответа. */
    fun looseAssembly(b: Board): List<BoardRec> =
        b.tables.flatMap { IdTables.allRows(b, it) }.filter { it.igs == IdTables.IGS_ASSEMBLY && waitingElsewhere(b, it, null).isEmpty() }

    /** Схему вывели из «Сборки» в таблице: убрать из ведомостей на столе (передать её с пачкой уже нельзя). Номера ведомостей. */
    fun dropFromAssembly(b: Board, r: BoardRec): List<String> {
        val out = ArrayList<String>()
        val it = b.vitems.iterator()
        while (it.hasNext()) {
            val i = it.next()
            if (i.r != r.id) continue
            val v = vedom(b, i.v) ?: continue
            if (!v.isDraft || v.kind == LIST) continue
            it.remove()
            out += v.num
        }
        return out
    }

    /** Ведомости, где есть схема (для истории). */
    fun itemsOf(b: Board, rec: BoardRec): List<Pair<BoardVedom, BoardVItem>> =
        b.vitems.filter { it.r == rec.id }.mapNotNull { i -> vedom(b, i.v)?.let { it to i } }

    // ---------------------------------------------------------------- правки схем с историей

    /**
     * Поставить значение столбца схемы и записать в историю (если это статус или дата и значение поменялось).
     * true — значение поменялось.
     */
    fun set(b: Board, r: BoardRec, k: String, value: String, who: String, vn: String = "", note: String = "",
            now: Long = System.currentTimeMillis()): Boolean {
        val old = r.get(k)
        if (old == value) return false
        r.set(k, value)
        if (k in HIST_KEYS || TableKinds.partOf(k) != null) b.events += BoardEvent(r.id, now, who, k, old, value, vn, note)
        return true
    }

    /**
     * Добавить схемы в ведомость (уже бывшие в ней — пропускаются). Возвращает, сколько добавлено. Ведомость в сборке
     * (АН, заказчик, ПТО): схемы «В работе», «Не предоставлена», «Возврат», «Не согласована» → «Сборка» (лежат на столе).
     */
    fun add(b: Board, v: BoardVedom, recs: List<BoardRec>, who: String = "", now: Long = System.currentTimeMillis()): Int {
        val have = b.vitems.filter { it.v == v.id }.map { it.r }.toHashSet()
        var o = endOrder(b, v)
        var n = 0
        for (r in recs) {
            if (!have.add(r.id)) continue
            val i = BoardVItem(v.id, r.id, o)
            remember(b, i)
            b.vitems += i
            if (v.isDraft && v.kind != LIST && r.igs in TO_ASSEMBLY) set(b, r, TableCol.IGS, IdTables.IGS_ASSEMBLY, who, v.num, "в сборке", now)
            o += 1.0
            n++
        }
        return n
    }

    /**
     * Схемы ушли из сборки (убрали из ведомости, удалили ведомость в сборке): «Сборка» → статус, что был до неё (по истории),
     * иначе «В работе».
     */
    fun unassemble(b: Board, v: BoardVedom, items: List<BoardVItem>, who: String = "", now: Long = System.currentTimeMillis()) {
        if (!v.isDraft || v.kind == LIST) return
        for (i in items) {
            val r = rec(b, i) ?: continue
            if (r.igs != IdTables.IGS_ASSEMBLY) continue
            val prev = b.events.filter { it.r == r.id && it.k == TableCol.IGS && it.to == IdTables.IGS_ASSEMBLY }.maxByOrNull { it.at }?.from
            set(b, r, TableCol.IGS, prev?.takeIf { it.isNotEmpty() && it != IdTables.IGS_ASSEMBLY } ?: IdTables.IGS_WORK, who, v.num, "убрана из сборки", now)
        }
    }

    /** Можно ли вернуть переданную ведомость в сборку: ответов ещё нет (ПТО — всегда). */
    fun canUngive(b: Board, v: BoardVedom) = !v.isDraft && v.kind != LIST && (v.kind == PTO || items(b, v).none { it.res.isNotEmpty() })

    /** Вернуть в сборку (ошиблись с «Унесли»): статус передачи → «Сборка», дата передачи у схем убирается. */
    fun ungive(b: Board, v: BoardVedom, who: String, now: Long = System.currentTimeMillis()) {
        if (!canUngive(b, v)) return
        val st = giveStatus(v.kind)
        for (i in items(b, v)) {
            val r = rec(b, i)
            if (r != null) {
                if (r.igs == st) set(b, r, TableCol.IGS, IdTables.IGS_ASSEMBLY, who, v.num, "снова в сборке", now)
                if (v.kind == CUST && r.dcust == v.given) set(b, r, TableCol.DCUST, "", who, v.num, "снова в сборке", now)
                if (v.kind == PTO && r.dpto == v.given) set(b, r, TableCol.DPTO, "", who, v.num, "снова в сборке", now)
            }
            if (v.kind == PTO) { i.res = ""; i.rd = "" }
        }
        v.given = ""
    }

    /**
     * Передать ведомость (дата date): всем схемам — статус по виду ведомости и дата («Отдал заказчику», «Сдано в ПТО»);
     * ПТО закрывается сразу. Каждая правка — в историю с номером ведомости.
     */
    fun give(b: Board, v: BoardVedom, date: String, who: String, now: Long = System.currentTimeMillis()) {
        v.given = date
        val st = giveStatus(v.kind)
        for (i in items(b, v)) {
            remember(b, i)
            val r = rec(b, i) ?: continue
            if (st.isNotEmpty()) set(b, r, TableCol.IGS, st, who, v.num, giveVerb(v.kind).lowercase(), now)
            when (v.kind) {
                CUST -> set(b, r, TableCol.DCUST, date, who, v.num, "", now)
                PTO -> { set(b, r, TableCol.DPTO, date, who, v.num, "", now); i.res = OK; i.rd = date }
            }
        }
    }

    /**
     * Ответ по позиции (ТЗ, раздел 6.1): АН «Согласовано» → «На подписи у заказчика» + «Отдал заказчику»; «Не согласовано» →
     * «Не согласована»; «Замечания» → «Возврат на корректировку». Заказчик «Подписано» → сразу «Сдано в ПТО» + дата;
     * «Замечания» → «Возврат на корректировку». "" — снова ждём: статус передачи.
     */
    fun answer(b: Board, v: BoardVedom, i: BoardVItem, res: String, date: String, who: String, now: Long = System.currentTimeMillis()) {
        val prevRes = i.res
        val prevDate = i.rd
        i.res = res
        i.rd = if (res.isEmpty()) "" else date
        val r = rec(b, i) ?: return
        val note = if (res.isEmpty()) "снова ждём ответа" else "ответ: " + resName(v.kind, res).lowercase()
        when (v.kind) {
            AN -> when (res) {
                OK -> { set(b, r, TableCol.IGS, IdTables.IGS_CUST, who, v.num, note, now); set(b, r, TableCol.DCUST, date, who, v.num, note, now) }
                NO -> set(b, r, TableCol.IGS, IdTables.IGS_AN_NO, who, v.num, note, now)
                FIX -> set(b, r, TableCol.IGS, IdTables.IGS_BACK, who, v.num, note, now)
                else -> {
                    set(b, r, TableCol.IGS, IdTables.IGS_AN, who, v.num, note, now)
                    if (prevRes == OK && r.dcust == prevDate) set(b, r, TableCol.DCUST, "", who, v.num, note, now)
                }
            }
            CUST -> when (res) {
                OK -> { set(b, r, TableCol.IGS, IdTables.IGS_PTO, who, v.num, note, now); set(b, r, TableCol.DPTO, date, who, v.num, note, now) }
                FIX -> set(b, r, TableCol.IGS, IdTables.IGS_BACK, who, v.num, note, now)
                else -> {
                    set(b, r, TableCol.IGS, IdTables.IGS_CUST, who, v.num, note, now)
                    if (prevRes == OK && r.dpto == prevDate) set(b, r, TableCol.DPTO, "", who, v.num, note, now)
                }
            }
        }
    }

    /** Схемы из ведомости АН, согласованные и ещё не попавшие в ведомость заказчику после этого. */
    fun approvedForCustomer(b: Board, v: BoardVedom): List<BoardRec> {
        if (v.kind != AN) return emptyList()
        val inCust = HashSet<String>()
        for (i in b.vitems) {
            val w = vedom(b, i.v) ?: continue
            if (w.kind == CUST && (w.isDraft || i.res.isEmpty())) inCust += i.r
        }
        return items(b, v).filter { it.res == OK }.mapNotNull { rec(b, it) }.filter { it.id !in inCust }
    }

    // ---------------------------------------------------------------- история

    /** События схемы, новые сверху. */
    fun history(b: Board, r: BoardRec): List<BoardEvent> = b.events.filter { it.r == r.id }.sortedWith(compareByDescending<BoardEvent> { it.at }.thenByDescending { it.id })

    fun valueText(b: Board, k: String, value: String): String = when {
        value.isEmpty() -> "—"
        k == TableCol.VOL || TableKinds.partOf(k) != null -> IdTables.volText(value)
        k == TableCol.IGS || k == TableCol.CK || k == TableCol.DWG -> b.statuses(IdTables.groupOf(k)).firstOrNull { it.id == value }?.name ?: value
        else -> IdTables.dateLong(value)
    }

    /** «ИГС: В работе → На подписи у заказчика». */
    fun eventText(b: Board, e: BoardEvent, colName: (String) -> String = { TableCol.defaultName(it) }): String {
        val base = if (e.k.isEmpty()) e.note else (if (TableKinds.partOf(e.k) != null) "Объём · " else "") + colName(e.k) + ": " + valueText(b, e.k, e.from) + " → " + valueText(b, e.k, e.to)
        return base + if (e.vn.isNotEmpty()) " · ведомость ${e.vn}" else ""
    }

    /** Когда схему последний раз передали на подпись АН (дата «2026-11-11», по истории); пусто — неизвестно. */
    fun anSince(b: Board): Map<String, String> {
        val best = HashMap<String, Long>()
        for (e in b.events) if (e.k == TableCol.IGS && e.to == IdTables.IGS_AN) { val p = best[e.r]; if (p == null || e.at > p) best[e.r] = e.at }
        val out = HashMap<String, String>(best.size * 2)
        val c = java.util.Calendar.getInstance()
        for ((r, at) in best) {
            c.timeInMillis = at
            out[r] = IdTables.iso(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
        }
        // Передача ведомостью АН — тоже дата (на случай событий без времени).
        for (i in b.vitems) {
            val v = vedom(b, i.v) ?: continue
            if (v.kind == AN && !v.isDraft && i.res.isEmpty()) { val cur = out[i.r]; if (cur == null || v.given > cur) out[i.r] = v.given }
        }
        return out
    }

    /** Убрать ведомость вместе с позициями (история схем остаётся). */
    fun remove(b: Board, v: BoardVedom) {
        b.vedoms.removeAll { it === v }
        b.vitems.removeAll { it.v == v.id }
    }

    /** Открытые ведомости (передана, ждём ответа): ведомость, сколько ждут, сколько дней. */
    fun open(b: Board, today: String = IdTables.today()): List<Triple<BoardVedom, Int, Int>> =
        b.vedoms.filter { isOpen(b, it) }.map { v -> Triple(v, items(b, v).count { it.res.isEmpty() }, IdTables.daysSince(v.given, today)) }
            .sortedByDescending { it.third }
}
