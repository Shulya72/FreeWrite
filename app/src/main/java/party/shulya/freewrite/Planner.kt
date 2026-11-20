package party.shulya.freewrite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Календарь ViaDesk — вкладка «Задачи» (0.29, Владимир: «как календарь на айфоне: запись привязываешь ко времени, за час
 * приходит уведомление; поиск — вписываю «ВСК» и вижу, в какие даты»). По макету `design/viadesk/maket.html`.
 *
 * Запись — событие (начало и конец) или задача (срок и галочка). Повтор, календарь (Работа / Объект / Личное), два оповещения,
 * место, заметки. Без экрана — проверяется обычными тестами (`PlanModelTest`).
 */
class PlanItem(var id: String = Ids.next()) {
    var kind = Plan.EVENT
    var title = ""
    var place = ""
    var notes = ""
    var allDay = false
    /** Начало (весь день — 00:00 первого дня). */
    var start: LocalDateTime = LocalDate.now().atTime(9, 0)
    /** Конец события (весь день — 00:00 последнего дня, включительно). У задачи — как начало. */
    var end: LocalDateTime = start.plusHours(1)
    var repeat = Plan.NONE
    /** Повторять до (включительно); null — без конца. */
    var until: LocalDate? = null
    var cal = Plan.WORK
    /** Оповещения: минут до начала (весь день — от 9:00 дня события), [Plan.NO_ALERT] — нет. */
    var alert = 60
    var alert2 = Plan.NO_ALERT
    /** Задача: дни выполненных повторов (без повтора — день задачи). */
    val done = LinkedHashSet<String>()
    /** Удалённые повторы (дни начала). */
    val skip = LinkedHashSet<String>()
    var created = 0L
    var updated = 0L

    val isTask get() = kind == Plan.TASK
    fun alerts(): List<Int> = listOf(alert, alert2).filter { it >= 0 }.distinct()

    fun copy(): PlanItem = Plan.parse(Plan.json(this))
}

/** Один день записи: [item] и день, с которого начинается этот повтор ([day]). */
class PlanOcc(val item: PlanItem, val day: LocalDate) {
    val start: LocalDateTime get() = day.atTime(item.start.toLocalTime())
    val end: LocalDateTime get() = start.plusMinutes(Plan.durationMin(item))
    val done get() = item.isTask && Plan.iso(day) in item.done
}

object Plan {
    const val EVENT = "event"
    const val TASK = "task"

    const val NONE = "none"
    const val DAY = "day"
    const val WEEK = "week"
    const val WEEK2 = "2week"
    const val MONTH = "month"
    const val YEAR = "year"
    val REPEATS = linkedMapOf(NONE to "Никогда", DAY to "Каждый день", WEEK to "Каждую неделю", WEEK2 to "Каждые 2 недели",
        MONTH to "Каждый месяц", YEAR to "Каждый год")

    const val WORK = "work"
    const val SITE = "site"
    const val HOME = "home"

    class Cal(val id: String, val name: String, val color: Int)
    val CALS = listOf(
        Cal(WORK, "Работа", android.graphics.Color.rgb(47, 111, 219)),
        Cal(SITE, "Объект", android.graphics.Color.rgb(232, 137, 12)),
        Cal(HOME, "Личное", android.graphics.Color.rgb(46, 158, 91)),
    )
    fun cal(id: String) = CALS.firstOrNull { it.id == id } ?: CALS[0]

    const val NO_ALERT = -1
    /** Оповещения события со временем: минут до начала. */
    val ALERTS = linkedMapOf(NO_ALERT to "Нет", 0 to "В момент события", 5 to "За 5 мин", 10 to "За 10 мин", 15 to "За 15 мин",
        30 to "За 30 мин", 60 to "За 1 ч", 120 to "За 2 ч", 1440 to "За 1 день", 2880 to "За 2 дня", 10080 to "За неделю")
    /** Оповещения на весь день — в 9:00: в день события, за день, за 2 дня, за неделю. */
    val ALERTS_ALL_DAY = linkedMapOf(NO_ALERT to "Нет", 0 to "В день события (9:00)", 1440 to "За 1 день (9:00)",
        2880 to "За 2 дня (9:00)", 10080 to "За неделю (9:00)")
    fun alertName(it: PlanItem, a: Int): String =
        (if (it.allDay) ALERTS_ALL_DAY[a] else ALERTS[a]) ?: (if (a < 0) "Нет" else "За $a мин")

    val WEEKDAYS = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    val WEEKDAYS_LONG = arrayOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    val MONTHS = arrayOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
    val MONTHS_GEN = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

    fun iso(d: LocalDate): String = d.toString()

    // ---------------------------------------------------------------- повторы

    /** Сколько дней сверх первого занимает запись (событие на несколько дней). */
    fun spanDays(it: PlanItem): Int {
        if (it.isTask) return 0
        var n = ChronoUnit.DAYS.between(it.start.toLocalDate(), it.end.toLocalDate()).toInt()
        // Событие до 00:00 следующего дня — только первый день.
        if (!it.allDay && n > 0 && it.end.toLocalTime() == LocalTime.MIDNIGHT) n--
        return n.coerceIn(0, 366)
    }

    fun durationMin(it: PlanItem): Long =
        if (it.isTask) 0 else maxOf(0L, ChronoUnit.MINUTES.between(it.start, it.end))

    /** Начинается ли повтор записи в день [d]. */
    fun isStart(it: PlanItem, d: LocalDate): Boolean {
        val s = it.start.toLocalDate()
        if (d < s) return false
        it.until?.let { u -> if (d > u) return false }
        if (iso(d) in it.skip) return false
        return when (it.repeat) {
            DAY -> true
            WEEK -> ChronoUnit.DAYS.between(s, d) % 7 == 0L
            WEEK2 -> ChronoUnit.DAYS.between(s, d) % 14 == 0L
            MONTH -> d.dayOfMonth == s.dayOfMonth
            YEAR -> d.dayOfMonth == s.dayOfMonth && d.monthValue == s.monthValue
            else -> d == s
        }
    }

    /** Есть ли запись в день [d] (с учётом событий на несколько дней): день начала этого повтора или null. */
    fun occursOn(it: PlanItem, d: LocalDate): LocalDate? {
        for (k in 0..spanDays(it)) {
            val c = d.minusDays(k.toLong())
            if (isStart(it, c)) return c
        }
        return null
    }

    /** Записи дня: сначала «весь день», потом по времени начала. */
    fun on(items: List<PlanItem>, d: LocalDate, hidden: Set<String> = emptySet()): List<PlanOcc> =
        items.asSequence().filter { it.cal !in hidden }.mapNotNull { i -> occursOn(i, d)?.let { PlanOcc(i, it) } }
            .sortedWith(compareBy<PlanOcc>({ !it.item.allDay }, { it.start.toLocalTime() }, { it.item.title })).toList()

    /** Ближайший день начала повтора не раньше [from] (до [maxDays] дней вперёд). */
    fun nextStart(it: PlanItem, from: LocalDate, maxDays: Int = 1500): LocalDate? {
        var d = maxOf(from, it.start.toLocalDate())
        val stop = d.plusDays(maxDays.toLong())
        if (it.repeat == NONE) return if (isStart(it, d)) d else null
        while (d <= stop) {
            it.until?.let { u -> if (d > u) return null }
            if (isStart(it, d)) return d
            d = d.plusDays(1)
        }
        return null
    }

    // ---------------------------------------------------------------- оповещения

    /** Когда оповестить о повторе, начинающемся в [day], за [a] минут (весь день — от 9:00). */
    fun alertAt(it: PlanItem, day: LocalDate, a: Int): LocalDateTime =
        (if (it.allDay) day.atTime(9, 0) else day.atTime(it.start.toLocalTime())).minusMinutes(a.toLong())

    class Due(val item: PlanItem, val day: LocalDate, val alert: Int, val at: Long) {
        /** Номер уведомления: запись + день + оповещение. */
        val key get() = (item.id + "|" + day + "|" + alert).hashCode()
    }

    /** Оповещения со временем в (after, upTo] (мс). Выполненные задачи не оповещают. */
    fun due(items: List<PlanItem>, after: Long, upTo: Long, zone: ZoneId = ZoneId.systemDefault()): List<Due> {
        val out = ArrayList<Due>()
        for (it in items) for (a in it.alerts()) {
            // Повтор, чьё оповещение позже after, начинается не раньше дня after + a минут.
            var d = nextStart(it, java.time.Instant.ofEpochMilli(after).atZone(zone).toLocalDate().minusDays(1)) ?: continue
            var guard = 0
            while (guard++ < 400) {
                val t = alertAt(it, d, a).atZone(zone).toInstant().toEpochMilli()
                if (t > upTo) break
                if (t > after && !(it.isTask && iso(d) in it.done)) out += Due(it, d, a, t)
                d = nextStart(it, d.plusDays(1)) ?: break
            }
        }
        return out.sortedBy { it.at }
    }

    /** Ближайшее оповещение позже [after] (мс), не дальше года. */
    fun nextAlert(items: List<PlanItem>, after: Long, zone: ZoneId = ZoneId.systemDefault()): Due? {
        var best: Due? = null
        for (it in items) for (a in it.alerts()) {
            var d = nextStart(it, java.time.Instant.ofEpochMilli(after).atZone(zone).toLocalDate().minusDays(1)) ?: continue
            var guard = 0
            while (guard++ < 400) {
                val t = alertAt(it, d, a).atZone(zone).toInstant().toEpochMilli()
                if (t > after && !(it.isTask && iso(d) in it.done)) {
                    if (best == null || t < best.at) best = Due(it, d, a, t)
                    break
                }
                if (best != null && t > best.at) break
                d = nextStart(it, d.plusDays(1)) ?: break
            }
        }
        return best?.takeIf { it.at - after < 366L * 86_400_000 }
    }

    // ---------------------------------------------------------------- поиск

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')

    /** Поиск по словам в названии, месте и заметках (все слова, без регистра, «ё» = «е»), по дате начала. */
    fun search(items: List<PlanItem>, query: String): List<PlanItem> {
        val words = norm(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return items.filter { i ->
            val t = norm(i.title + " " + i.place + " " + i.notes)
            words.all { it in t }
        }.sortedWith(compareBy({ it.start }, { it.title }))
    }

    /** «сегодня», «вчера», «завтра», «7 дней назад», «через 3 дня». */
    fun relDays(d: LocalDate, today: LocalDate): String {
        val n = ChronoUnit.DAYS.between(today, d).toInt()
        val a = Math.abs(n)
        val w = Ui.plural(a, "день", "дня", "дней")
        return when (n) { 0 -> "сегодня"; -1 -> "вчера"; 1 -> "завтра"; else -> if (n < 0) "$w назад" else "через $w" }
    }

    fun hm(t: LocalTime) = "%d:%02d".format(t.hour, t.minute)

    /** «Пятница, 20 ноября 2026». */
    fun dayTitle(d: LocalDate) = WEEKDAYS_LONG[d.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() } + ", " +
        d.dayOfMonth + " " + MONTHS_GEN[d.monthValue - 1] + " " + d.year

    /** «весь день», «в 14:00» (задача), «12:00–13:00». */
    fun timeText(o: PlanOcc): String = when {
        o.item.allDay -> "весь день"
        o.item.isTask -> "в " + hm(o.start.toLocalTime())
        else -> hm(o.start.toLocalTime()) + "–" + hm(o.end.toLocalTime())
    }

    // ---------------------------------------------------------------- JSON

    fun json(it: PlanItem): String = JSONObject().apply {
        put("id", it.id); put("k", it.kind); put("t", it.title)
        if (it.place.isNotEmpty()) put("pl", it.place)
        if (it.notes.isNotEmpty()) put("n", it.notes)
        if (it.allDay) put("ad", 1)
        put("s", it.start.toString()); put("e", it.end.toString())
        if (it.repeat != NONE) put("r", it.repeat)
        it.until?.let { u -> put("u", u.toString()) }
        put("c", it.cal); put("a", it.alert)
        if (it.alert2 != NO_ALERT) put("a2", it.alert2)
        if (it.done.isNotEmpty()) put("dn", JSONArray(it.done.toList()))
        if (it.skip.isNotEmpty()) put("sk", JSONArray(it.skip.toList()))
        put("cr", it.created); put("up", it.updated)
    }.toString()

    fun parse(s: String): PlanItem = parse(JSONObject(s))

    fun parse(j: JSONObject): PlanItem = PlanItem(j.optString("id").ifEmpty { Ids.next() }).also { it ->
        it.kind = if (j.optString("k") == TASK) TASK else EVENT
        it.title = j.optString("t"); it.place = j.optString("pl"); it.notes = j.optString("n")
        it.allDay = j.optInt("ad") == 1
        it.start = runCatching { LocalDateTime.parse(j.optString("s")) }.getOrDefault(LocalDate.now().atTime(9, 0))
        it.end = runCatching { LocalDateTime.parse(j.optString("e")) }.getOrDefault(it.start.plusHours(1))
        it.repeat = j.optString("r", NONE).takeIf { r -> r in REPEATS } ?: NONE
        it.until = j.optString("u").takeIf { u -> u.isNotEmpty() }?.let { u -> runCatching { LocalDate.parse(u) }.getOrNull() }
        it.cal = j.optString("c", WORK).takeIf { c -> CALS.any { k -> k.id == c } } ?: WORK
        it.alert = j.optInt("a", 60); it.alert2 = j.optInt("a2", NO_ALERT)
        j.optJSONArray("dn")?.let { a -> for (k in 0 until a.length()) it.done += a.getString(k) }
        j.optJSONArray("sk")?.let { a -> for (k in 0 until a.length()) it.skip += a.getString(k) }
        it.created = j.optLong("cr"); it.updated = j.optLong("up")
    }
}

/**
 * Записи календаря на планшете: `files/planner.json` (номера записей и удалённые — `gone`, для будущей синхронизации
 * между планшетом и телефоном). Запись — целиком, через временный файл.
 */
class PlanStore(context: Context) {
    private val file = File(context.filesDir, "planner.json")
    val items = ArrayList<PlanItem>()
    /** Удалённые записи: номер → когда. */
    val gone = LinkedHashMap<String, Long>()

    init { load() }

    fun load(): PlanStore {
        items.clear(); gone.clear()
        if (!file.isFile) return this
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONArray("items")?.let { a -> for (k in 0 until a.length()) items += Plan.parse(a.getJSONObject(k)) }
            o.optJSONObject("gone")?.let { g -> for (k in g.keys()) gone[k] = g.optLong(k) }
        }.onFailure { runCatching { Trail.add("Задачи: не прочитался planner.json — ${it.message}") } }
        return this
    }

    fun save() {
        val sb = StringBuilder("{\"v\":1,\"items\":[")
        items.forEachIndexed { k, it -> if (k > 0) sb.append(','); sb.append(Plan.json(it)) }
        sb.append("],\"gone\":").append(JSONObject(gone as Map<*, *>).toString()).append('}')
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun put(it: PlanItem) {
        val now = System.currentTimeMillis()
        if (it.created == 0L) it.created = now
        it.updated = now
        val k = items.indexOfFirst { o -> o.id == it.id }
        if (k >= 0) items[k] = it else items += it
        gone.remove(it.id)
        save()
    }

    fun remove(id: String) {
        if (items.removeAll { it.id == id }) gone[id] = System.currentTimeMillis()
        save()
    }

    fun byId(id: String) = items.firstOrNull { it.id == id }
}
