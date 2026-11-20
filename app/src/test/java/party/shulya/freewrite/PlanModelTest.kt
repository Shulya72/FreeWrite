package party.shulya.freewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Календарь (0.29): повторы, события на несколько дней, поиск «ВСК», оповещения, хранение. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanModelTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun d(s: String) = LocalDate.parse(s)
    private fun dt(s: String) = LocalDateTime.parse(s)
    private fun ms(s: String) = dt(s).atZone(zone).toInstant().toEpochMilli()
    private fun ev(title: String, s: String, e: String, rep: String = Plan.NONE, alert: Int = 60) = PlanItem().apply {
        this.title = title; start = dt(s); end = dt(e); repeat = rep; this.alert = alert
    }

    @Test
    fun repeatsMultiDayAndOrder() {
        val w = ev("Планёрка", "2026-11-16T08:00", "2026-11-16T08:30", Plan.WEEK)
        assertTrue(Plan.isStart(w, d("2026-11-23"))); assertFalse(Plan.isStart(w, d("2026-11-24"))); assertFalse(Plan.isStart(w, d("2026-11-09")))
        val w2 = ev("Сверка", "2026-11-16T10:00", "2026-11-16T11:00", Plan.WEEK2)
        assertFalse(Plan.isStart(w2, d("2026-11-23"))); assertTrue(Plan.isStart(w2, d("2026-11-30")))
        val m = ev("Отчёт", "2026-01-31T10:00", "2026-01-31T11:00", Plan.MONTH)
        assertNull("в феврале 31-го нет", Plan.occursOn(m, d("2026-02-28"))); assertEquals(d("2026-03-31"), Plan.occursOn(m, d("2026-03-31")))
        val y = ev("Годовщина", "2024-02-29T10:00", "2024-02-29T11:00", Plan.YEAR)
        assertNull(Plan.occursOn(y, d("2025-02-28"))); assertEquals(d("2028-02-29"), Plan.nextStart(y, d("2025-01-01")))
        val day = ev("Обход", "2026-11-18T09:00", "2026-11-18T10:00", Plan.DAY).apply { until = d("2026-11-20"); skip += "2026-11-19" }
        assertEquals(listOf(true, false, true, false), listOf("18", "19", "20", "21").map { Plan.occursOn(day, d("2026-11-$it")) != null })
        // Ночная смена: 22:00–02:00 — два дня; до 00:00 следующего дня — один.
        val night = ev("Бетонирование", "2026-11-20T22:00", "2026-11-21T02:00")
        assertEquals(d("2026-11-20"), Plan.occursOn(night, d("2026-11-21")))
        assertNull(Plan.occursOn(ev("До полуночи", "2026-11-20T20:00", "2026-11-21T00:00"), d("2026-11-21")))
        val all = ev("Захватка 4", "2026-11-20T00:00", "2026-11-22T00:00").apply { allDay = true }
        assertEquals(listOf(true, true, true, false), listOf("20", "21", "22", "23").map { Plan.occursOn(all, d("2026-11-$it")) != null })
        // Весь день — первым, потом по времени.
        val list = Plan.on(listOf(night, ev("Утро", "2026-11-20T08:00", "2026-11-20T09:00"), all), d("2026-11-20"))
        assertEquals(listOf("Захватка 4", "Утро", "Бетонирование"), list.map { it.item.title })
        assertEquals("весь день", Plan.timeText(list[0])); assertEquals("8:00–9:00", Plan.timeText(list[1]))
        // Скрытый календарь не показывается.
        assertEquals(2, Plan.on(listOf(night.apply { cal = Plan.HOME }, all), d("2026-11-20"), setOf(Plan.HOME)).size + 1)
    }

    @Test
    fun searchLikeVsk() {
        val a = ev("Разбивка котлована, компания ВСК", "2026-11-13T12:00", "2026-11-13T13:00").apply { place = "Участок 3, ул. Заводская" }
        val b = ev("Исполнительная съёмка котлована", "2026-11-17T15:00", "2026-11-17T16:30").apply { notes = "Для ВСК, отметка дна −4.200" }
        val c = ev("Совещание по графику", "2026-11-08T09:00", "2026-11-08T10:00").apply { notes = "ВСК просят начать котлован раньше" }
        val e = ev("Ёлка в бытовке", "2026-12-28T17:00", "2026-12-28T18:00")
        val all = listOf(a, b, c, e)
        assertEquals(listOf(c, a, b), Plan.search(all, "вск"))
        assertEquals(listOf(a, b), Plan.search(all, "котлована"))
        assertEquals(listOf(a), Plan.search(all, "ВСК заводская"))
        assertEquals(listOf(e), Plan.search(all, "елка"))
        assertTrue(Plan.search(all, "  ").isEmpty())
        val today = d("2026-11-20")
        assertEquals("7 дней назад", Plan.relDays(d("2026-11-13"), today))
        assertEquals("вчера", Plan.relDays(d("2026-11-19"), today))
        assertEquals("через 3 дня", Plan.relDays(d("2026-11-23"), today))
        assertEquals("сегодня", Plan.relDays(today, today))
        assertEquals("Пятница, 20 ноября 2026", Plan.dayTitle(today))
    }

    @Test
    fun alertsTimes() {
        val meet = ev("Сдать ведомость З-14", "2026-11-21T13:00", "2026-11-21T14:00", alert = 60)
        val n = Plan.nextAlert(listOf(meet), ms("2026-11-20T10:00"), zone)!!
        assertEquals(ms("2026-11-21T12:00"), n.at)
        val all = ev("Бетонирование захватки 4", "2026-11-22T00:00", "2026-11-22T00:00").apply { allDay = true; alert = 1440 }
        assertEquals(ms("2026-11-21T09:00"), Plan.nextAlert(listOf(all), ms("2026-11-20T10:00"), zone)!!.at)
        // Каждый день в 8:00, за 10 мин: после сегодняшнего — завтрашнее.
        val daily = ev("Планёрка", "2026-11-01T08:00", "2026-11-01T08:30", Plan.DAY, alert = 10)
        assertEquals(ms("2026-11-21T07:50"), Plan.nextAlert(listOf(daily), ms("2026-11-20T07:50"), zone)!!.at)
        val due = Plan.due(listOf(daily, meet), ms("2026-11-20T07:00"), ms("2026-11-21T12:00"), zone)
        assertEquals(listOf(ms("2026-11-20T07:50"), ms("2026-11-21T07:50"), ms("2026-11-21T12:00")), due.map { it.at })
        // Два оповещения; выполненная задача молчит.
        val task = PlanItem().apply { kind = Plan.TASK; title = "Позвонить геодезистам"; start = dt("2026-11-20T14:00"); end = start; alert = 15; alert2 = 0 }
        assertEquals(listOf(ms("2026-11-20T13:45"), ms("2026-11-20T14:00")), Plan.due(listOf(task), ms("2026-11-20T10:00"), ms("2026-11-20T15:00"), zone).map { it.at })
        task.done += "2026-11-20"
        assertNull(Plan.nextAlert(listOf(task), ms("2026-11-20T10:00"), zone))
        assertNull("без оповещений", Plan.nextAlert(listOf(ev("Тихо", "2026-11-21T13:00", "2026-11-21T14:00", alert = Plan.NO_ALERT)), ms("2026-11-20T10:00"), zone))
    }

    @Test
    fun jsonAndStore() {
        val a = ev("Обход с технадзором", "2026-11-20T09:30", "2026-11-20T12:00", Plan.WEEK).apply {
            place = "Корпус 2"; notes = "Взять акты"; cal = Plan.SITE; alert2 = 15; until = d("2026-12-31"); skip += "2026-11-27"
        }
        val t = PlanItem().apply { kind = Plan.TASK; title = "Подписать акт"; start = dt("2026-11-22T10:00"); end = start; done += "2026-11-22"; allDay = false }
        val back = Plan.parse(Plan.json(a))
        assertEquals(Plan.json(a), Plan.json(back))
        assertEquals(Plan.json(t), Plan.json(Plan.parse(Plan.json(t))))
        val ctx = RuntimeEnvironment.getApplication()
        java.io.File(ctx.filesDir, "planner.json").delete()
        val s = PlanStore(ctx)
        s.put(a); s.put(t)
        val s2 = PlanStore(ctx)
        assertEquals(listOf(a.id, t.id), s2.items.map { it.id })
        assertEquals("Корпус 2", s2.byId(a.id)!!.place)
        s2.remove(t.id)
        val s3 = PlanStore(ctx)
        assertEquals(1, s3.items.size)
        assertTrue(t.id in s3.gone)
    }

    @Test
    fun severalWeekdays() {
        // Пн, ср, пт с среды 18 ноября: 18, 20, 23 — да; 16 (до начала), 19, 24 — нет.
        val w = ev("Обход секций", "2026-11-18T08:00", "2026-11-18T09:00", Plan.WEEK).apply { days += listOf(1, 3, 5) }
        assertEquals(listOf(false, true, false, true, true, false), listOf("16", "18", "19", "20", "23", "24").map { Plan.occursOn(w, d("2026-11-$it")) != null })
        assertEquals("Каждую неделю: пн, ср, пт", Plan.repeatText(w))
        // Вт и чт раз в 2 недели с 17 ноября: 17, 19 — да; 24, 26 — нет; 1, 3 декабря — да.
        val w2 = ev("Сверка с АН", "2026-11-17T10:00", "2026-11-17T11:00", Plan.WEEK2).apply { days += listOf(2, 4) }
        assertEquals(listOf(true, true, false, false, true, true), listOf("2026-11-17", "2026-11-19", "2026-11-24", "2026-11-26", "2026-12-01", "2026-12-03").map { Plan.occursOn(w2, d(it)) != null })
        assertEquals("Каждые 2 недели: вт, чт", Plan.repeatText(w2))
        // По будням; без своих дней — как раньше (день недели начала).
        val wd = ev("Планёрка", "2026-11-16T08:00", "2026-11-16T08:30", Plan.WEEK).apply { days += Plan.WORKDAYS }
        assertEquals("По будням (пн–пт)", Plan.repeatText(wd))
        assertEquals(listOf(true, true, false), listOf("20", "19", "21").map { Plan.occursOn(wd, d("2026-11-$it")) != null })
        assertEquals("Каждую неделю", Plan.repeatText(ev("Обычная", "2026-11-16T08:00", "2026-11-16T09:00", Plan.WEEK)))
        // Оповещение — к ближайшему из дней; JSON хранит дни.
        assertEquals(ms("2026-11-20T07:00"), Plan.nextAlert(listOf(w), ms("2026-11-18T08:00"), zone)!!.at)
        val back = Plan.parse(Plan.json(w))
        assertEquals(setOf(1, 3, 5), back.days.toSet())
        assertEquals(Plan.json(w), Plan.json(back))
        assertTrue("старая запись без дней", Plan.parse(Plan.json(ev("x", "2026-11-16T08:00", "2026-11-16T09:00", Plan.WEEK))).days.isEmpty())
    }
}
