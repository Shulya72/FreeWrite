package party.shulya.freewrite

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Вкладка «Задачи» (0.29): месяц → день → касание часа → «Новое событие» → блок в дне; поиск «ВСК» → день; подробно → удалить;
 * задача с галочкой; оповещение за час — будильник и уведомление.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlannerScreenTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        File(ctx.filesDir, "planner.json").delete()
        ctx.getSharedPreferences("plan", Context.MODE_PRIVATE).edit().clear().apply()
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().clear().apply()
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    @After
    fun tearDown() { PlanAlarms.clock = { System.currentTimeMillis() }; PlanAlarms.zone = ZoneId.systemDefault() }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        idle()
    }

    private fun shot(v: View, name: String) {
        if (v.width <= 0 || v.height <= 0) return
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dialogRoot(d: AlertDialog) = d.window!!.decorView

    @Test
    fun monthDayEditorSearchDetails() {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        val root = a.window.decorView
        root.findViewWithTag<View>("tab_tasks").performClick(); idle()
        val p = a.planner
        val today = p.today()
        // Прошлая запись с «ВСК» — для поиска; задача на сегодня.
        val past = PlanItem().apply {
            title = "Разбивка котлована, компания ВСК"; place = "Участок 3, ул. Заводская"; cal = Plan.SITE
            start = today.minusDays(7).atTime(12, 0); end = start.plusHours(1)
        }
        val task = PlanItem().apply { kind = Plan.TASK; title = "Позвонить геодезистам"; start = today.atTime(14, 0); end = start; alert = 15 }
        p.store.put(past); p.store.put(task)
        p.goToday(); idle()
        assertEquals(PlannerPage.MONTH, p.mode)
        val months = p.months
        val cell = months.rectOf(today)!!
        shot(root, "plan-month.png")

        // Месяц → день.
        tap(months, cell.centerX(), cell.centerY())
        assertEquals(PlannerPage.DAY, p.mode)
        assertEquals(Plan.dayTitle(today) + " г.", p.dayHead.text.toString())
        val tl = p.timeline
        assertNotNull("задача в дне", tl.blockRect(task.id))

        // Галочка задачи — касание квадратика.
        val tr = tl.blockRect(task.id)!!
        tap(tl, tr.left + 14f, tr.top + 14f)
        assertTrue(Plan.iso(today) in p.store.byId(task.id)!!.done)

        // Касание 12:00 → «Новое событие» 12:00–13:00.
        tap(tl, tl.width * 0.6f, tl.yOf(12 * 60 + 5).toFloat())
        val ed = p.editor!!
        assertTrue(ed.isShowing)
        val er = dialogRoot(ed)
        assertEquals("12:00", er.findViewWithTag<TextView>("ev_start_time").text.toString())
        assertEquals("13:00", er.findViewWithTag<TextView>("ev_end_time").text.toString())
        assertEquals("За 1 ч", er.findViewWithTag<TextView>("ev_alert").text.toString())
        val save = ed.getButton(AlertDialog.BUTTON_POSITIVE)
        assertTrue("без названия — нельзя", !save.isEnabled)
        er.findViewWithTag<EditText>("ev_title").setText("Обход с технадзором")
        er.findViewWithTag<EditText>("ev_place").setText("Корпус 2")
        shot(er, "plan-editor.png")
        save.performClick(); idle()
        val made = p.store.items.first { it.title == "Обход с технадзором" }
        assertEquals(today.atTime(12, 0), made.start)
        assertEquals("Корпус 2", made.place)
        assertNotNull(tl.blockRect(made.id))
        shot(root, "plan-day.png")

        // Поиск «вск» → строка → день той записи.
        root.findViewWithTag<View>("plan_search_d").performClick(); idle()
        assertEquals(PlannerPage.SEARCH, p.mode)
        p.qInput.setText("вск"); idle()
        val count = root.findViewWithTag<TextView>("q_count").text.toString()
        assertEquals("Найдено: 1 · дней: 1", count)
        shot(root, "plan-search.png")
        root.findViewWithTag<View>("q_row_0").performClick(); idle()
        assertEquals(PlannerPage.DAY, p.mode)
        assertEquals(today.minusDays(7), p.sel)
        assertNotNull(tl.blockRect(past.id))
        assertTrue((root.findViewWithTag<TextView>("plan_back")).text.toString().contains("Поиск"))
        assertTrue(p.back()); assertEquals(PlannerPage.SEARCH, p.mode)
        assertTrue(p.back()); assertEquals(PlannerPage.MONTH, p.mode)

        // Подробно → удалить.
        p.openDay(today); idle()
        val mr = tl.blockRect(made.id)!!
        tap(tl, mr.centerX(), mr.centerY())
        val det = p.details!!
        assertTrue(det.isShowing)
        shot(dialogRoot(det), "plan-details.png")
        det.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertNull(p.store.byId(made.id))
        assertNull(tl.blockRect(made.id))
        // «Назад» системы из дня — в месяц.
        assertTrue(p.back()); assertEquals(PlannerPage.MONTH, p.mode)
    }

    @Test
    fun alertAnHourBeforeComesAsNotification() {
        val zone = ZoneId.of("Europe/Moscow")
        PlanAlarms.zone = zone
        val day = LocalDate.of(2026, 11, 21)
        fun at(h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        PlanAlarms.clock = { at(9, 0) }
        val s = PlanStore(ctx)
        val meet = PlanItem().apply { title = "Сдать ведомость З-14 заказчику"; place = "Офис заказчика"; start = day.atTime(13, 0); end = start.plusHours(1); alert = 60 }
        val daily = PlanItem().apply { title = "Планёрка у прораба"; start = day.minusDays(3).atTime(8, 0); end = start.plusMinutes(30); repeat = Plan.DAY; alert = 10 }
        s.put(meet); s.put(daily)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val t = PlanAlarms.reschedule(ctx)
        assertEquals("ближайшее — за час до 13:00", at(12, 0), t)
        assertEquals(at(12, 0), shadowOf(am).nextScheduledAlarm!!.triggerAtTime)
        // 12:00 — сработал будильник.
        PlanAlarms.clock = { at(12, 0) }
        PlanAlarmReceiver().onReceive(ctx, android.content.Intent(PlanAlarms.ACTION))
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val list = shadowOf(nm).allNotifications
        assertEquals(1, list.size)
        val n = list[0]
        assertEquals("Сдать ведомость З-14 заказчику", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("13:00–14:00 · Офис заказчика", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        // Дальше — планёрка завтра в 7:50.
        assertEquals(day.plusDays(1).atTime(7, 50).atZone(zone).toInstant().toEpochMilli(), shadowOf(am).nextScheduledAlarm!!.triggerAtTime)
        // Планшет был выключен до 15:00 следующего дня — пропущенное утреннее не сыплется.
        PlanAlarms.clock = { day.plusDays(1).atTime(15, 0).atZone(zone).toInstant().toEpochMilli() }
        assertEquals(0, PlanAlarms.fire(ctx))
        assertEquals(1, shadowOf(nm).allNotifications.size)
        // Касание уведомления открывает день.
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(day.toString(), intent.getStringExtra(HomeActivity.EXTRA_PLAN_DAY))
        assertEquals(meet.id, intent.getStringExtra(HomeActivity.EXTRA_PLAN_ITEM))
        val home = Robolectric.buildActivity(HomeActivity::class.java, intent).setup().get()
        idle()
        assertEquals(PlannerPage.DAY, home.planner.mode)
        assertEquals(day, home.planner.sel)
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    fun phoneFits() {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        val root = a.window.decorView
        root.findViewWithTag<View>("tab_tasks").performClick(); idle()
        val p = a.planner
        p.store.put(PlanItem().apply { title = "Бетонирование захватки 4"; allDay = true; cal = Plan.SITE; start = p.today().atStartOfDay(); end = start; alert = 1440 })
        p.openDay(p.today()); idle()
        assertTrue(root.findViewWithTag<View>("allday_0").isShown)
        shot(root, "plan-phone-day.png")
        root.findViewWithTag<View>("plan_add_d").performClick(); idle()
        val er = p.editor!!.window!!.decorView
        val time = er.findViewWithTag<View>("ev_start_time")
        val loc = IntArray(2); time.getLocationOnScreen(loc)
        assertTrue("время помещается: ${loc[0] + time.width} ≤ ${er.width}", loc[0] + time.width <= er.width)
        shot(er, "plan-phone-editor.png")
    }
}
