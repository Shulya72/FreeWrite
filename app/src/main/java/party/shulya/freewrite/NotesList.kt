package party.shulya.freewrite

import java.util.Calendar

/**
 * Список заметок как в Заметках айфона (0.28): закреплённые сверху, дальше по времени правки — «Сегодня», «Вчера»,
 * «Предыдущие 7 дней», «Предыдущие 30 дней», затем по месяцам («Октябрь», в прошлом году — «Октябрь 2025»).
 * Отбор по папке и по словам из названия и текста. Без экрана — проверяется обычными тестами.
 */
object NotesList {
    const val PINNED = "Закреплённые"
    private val MONTHS = arrayOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
    private val WEEKDAYS = arrayOf("Воскресенье", "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота")

    private fun day(t: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** Сколько полных дней между днём заметки и сегодня (0 — сегодня). */
    fun daysAgo(t: Long, now: Long): Int = Math.round((day(now).timeInMillis - day(t).timeInMillis) / 86_400_000.0).toInt()

    /** Название группы по времени правки. */
    fun group(t: Long, now: Long): String {
        val d = daysAgo(t, now)
        return when {
            d <= 0 -> "Сегодня"
            d == 1 -> "Вчера"
            d < 7 -> "Предыдущие 7 дней"
            d < 30 -> "Предыдущие 30 дней"
            else -> {
                val c = Calendar.getInstance().apply { timeInMillis = t }
                val y = c.get(Calendar.YEAR)
                MONTHS[c.get(Calendar.MONTH)] + if (y != Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)) " $y" else ""
            }
        }
    }

    /** Время в строке: сегодня — «8:05», вчера — «Вчера», на этой неделе — день недели, раньше — «12.10.2026». */
    fun rowTime(t: Long, now: Long): String {
        val d = daysAgo(t, now)
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return when {
            d <= 0 -> "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            d == 1 -> "Вчера"
            d < 7 -> WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
            else -> "%02d.%02d.%d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
        }
    }

    /** Подходит ли заметка под поиск (все слова, без регистра, «ё» = «е»). */
    fun matches(n: Note, query: String): Boolean {
        val words = norm(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = norm(n.searchText)
        return words.all { it in text }
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')

    /** Время правки заметки (у очень старых заметок его нет — время создания). */
    fun edited(n: Note): Long = if (n.updated > 0) n.updated else n.created

    /**
     * Группы для экрана: [folder] — номер папки (пусто — все заметки), [query] — поиск. Закреплённые — первой группой,
     * внутри групп — сначала свежие.
     */
    fun arrange(all: List<Note>, folder: String, query: String, now: Long): List<Pair<String, List<Note>>> {
        val picked = all.filter { (folder.isEmpty() || it.folder == folder) && matches(it, query) }
        val out = ArrayList<Pair<String, List<Note>>>()
        val pinned = picked.filter { it.pinned }.sortedByDescending { edited(it) }
        if (pinned.isNotEmpty()) out += PINNED to pinned
        val rest = picked.filter { !it.pinned }.sortedByDescending { edited(it) }
        var cur: String? = null
        var bucket = ArrayList<Note>()
        for (n in rest) {
            val g = group(edited(n), now)
            if (g != cur) {
                if (cur != null) out += cur to bucket
                cur = g; bucket = ArrayList()
            }
            bucket += n
        }
        if (cur != null) out += cur to bucket
        return out
    }
}
