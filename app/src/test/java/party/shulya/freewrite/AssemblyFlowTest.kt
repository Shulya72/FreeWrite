package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * 0.25 (Владимир, 13.11.2026): в таблице ставлю «Сборка» — первая за день предлагает завести ведомость от сегодня, схемы
 * в «Сборке» ложатся в неё (дальше — сами); меняю состояние ведомости на «На подписи у заказчика» — у всех её схем статус
 * «На подписи у заказчика от <день перевода>».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssemblyFlowTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private val today get() = IdTables.today()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
        ctx.getSharedPreferences("igs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun sample(): Board {
        val b = Board.create("ИГС")
        b.frames += BoardFrame(0f, 0f, 3600f, 2400f, "КЖ 5.1.1")
        val t = BoardTable(100f, 100f, 1.5f, TableKinds.KJ_VERT.name).apply { kind = TableKinds.KJ_VERT.id }
        b.tables += t
        var o = 1.0
        for (z in listOf("1", "2")) for (w in listOf("Опалубка", "Бетон")) b.recs += BoardRec(t.id, o++).apply {
            zah = z; work = w; igs = IdTables.IGS_WORK
        }
        return b
    }

    @Test
    fun modelMadeDateDropAndStatusSince() {
        val b = sample()
        val t = b.tables[0]
        val v = BoardVedom(2000f, 100f, 1.5f, Vedoms.CUST, "З-1").apply { made = "2026-11-13" }
        b.vedoms += v
        // Дата «от …» — в JSON только если есть; старые ведомости — как были.
        assertTrue(Vedoms.vedomJson(v).contains("\"made\":\"2026-11-13\""))
        assertEquals("2026-11-13", Vedoms.parseVedom(JSONObject(Vedoms.vedomJson(v))).made)
        assertFalse(Vedoms.vedomJson(BoardVedom(0f, 0f, 1f, Vedoms.CUST, "З-2")).contains("made"))
        assertEquals("Сборка от 13.11.26 ▾", Vedoms.stateChip(b, v))
        assertEquals(v, Vedoms.assemblyOf(b, "2026-11-13"))
        assertEquals(null, Vedoms.assemblyOf(b, "2026-11-14"))
        // Схемы в «Сборке» без ведомости; вывели из «Сборки» — уходит из ведомости на столе.
        val rows = IdTables.allRows(b, t)
        for (r in rows.take(3)) r.igs = IdTables.IGS_ASSEMBLY
        Vedoms.add(b, v, listOf(rows[0]))
        assertEquals(listOf(rows[1], rows[2]), Vedoms.looseAssembly(b))
        rows[0].igs = IdTables.IGS_WORK
        assertEquals(listOf("З-1"), Vedoms.followTable(b, rows[0]))
        assertEquals(0, Vedoms.items(b, v).size)
        rows[0].igs = IdTables.IGS_ASSEMBLY
        // Передали: «На подписи у заказчика от 13.11.26» — у схемы и в кнопке ведомости.
        Vedoms.add(b, v, rows.take(2))
        Vedoms.give(b, v, "2026-11-13", "Владимир Шульмин")
        assertEquals("На подписи у заказчика от 13.11.26", IdTables.cellText(b, rows[0], TableCol.IGS))
        assertEquals("На подписи у заказчика от 13.11.26 ▾", Vedoms.stateChip(b, v))
        assertEquals("В работе", IdTables.cellText(b, rows[3], TableCol.IGS))
        // На АН — день перевода по истории.
        Vedoms.set(b, rows[3], TableCol.IGS, IdTables.IGS_AN, "Владимир Шульмин")
        assertTrue(IdTables.cellText(b, rows[3], TableCol.IGS).startsWith("На подписи АН от "))
    }

    /**
     * Владимир: «в ведомости сборки «ИГС сейчас» — 2 варианта, Сборка и на согласовании АН; что на АН — прокликаю вручную;
     * на подпись — выбираю состояние ведомости, и статусы обновляются».
     */
    @Test
    fun anRouteInsideAssemblyVedom() {
        val b = sample()
        val rows = IdTables.allRows(b, b.tables[0])
        val v = BoardVedom(2000f, 100f, 1.5f, Vedoms.CUST, "З-1").apply { made = "2026-11-13" }
        b.vedoms += v
        Vedoms.add(b, v, rows.take(3), "Владимир Шульмин")
        assertTrue(rows.take(3).all { it.igs == IdTables.IGS_ASSEMBLY })
        val items = Vedoms.items(b, v)
        // Вторая схема — на АН (в сборке «ИГС сейчас» → АН): статус в таблице тоже, отметка в позиции.
        Vedoms.route(b, v, items[1], an = true, who = "Владимир Шульмин")
        assertEquals(IdTables.IGS_AN, rows[1].igs); assertEquals(Vedoms.AN, items[1].via)
        assertEquals(Vedoms.AN, Vedoms.kindOf(v, items[1])); assertEquals(Vedoms.CUST, Vedoms.kindOf(v, items[0]))
        assertTrue(Vedoms.itemJson(items[1]).contains("\"via\":\"an\""))
        assertEquals(Vedoms.AN, Vedoms.parseItem(JSONObject(Vedoms.itemJson(items[1]))).via)
        assertFalse(Vedoms.itemJson(items[0]).contains("via"))
        // В таблице «Сборка» → АН у третьей — остаётся в ведомости, тоже через АН; назад в «Сборку» — снова заказчику.
        rows[2].igs = IdTables.IGS_AN
        assertTrue(Vedoms.followTable(b, rows[2]).isEmpty())
        assertEquals(Vedoms.AN, items[2].via)
        rows[2].igs = IdTables.IGS_ASSEMBLY
        Vedoms.followTable(b, rows[2])
        assertEquals("", items[2].via)
        // Добавили в сборку схему, что уже на АН, — сразу «через АН».
        rows[3].igs = IdTables.IGS_AN
        Vedoms.add(b, v, listOf(rows[3]))
        assertEquals(Vedoms.AN, Vedoms.items(b, v).last().via)
        // На подпись: «Сборка» → «На подписи у заказчика от …»; схемы на АН — как были.
        Vedoms.give(b, v, "2026-11-13", "Владимир Шульмин")
        assertEquals(IdTables.IGS_CUST, rows[0].igs); assertEquals("2026-11-13", rows[0].dcust)
        assertEquals(IdTables.IGS_CUST, rows[2].igs)
        assertEquals(IdTables.IGS_AN, rows[1].igs); assertEquals("", rows[1].dcust)
        assertEquals(IdTables.IGS_AN, rows[3].igs)
        // Ответ по схеме «через АН» — как у АН: «Согласовано» → «На подписи у заказчика» и ждём подписи в этой же ведомости.
        Vedoms.answer(b, v, items[1], Vedoms.OK, "2026-11-15", "Владимир Шульмин")
        assertEquals(IdTables.IGS_CUST, rows[1].igs); assertEquals("2026-11-15", rows[1].dcust)
        assertEquals("", items[1].via); assertEquals("", items[1].res)
        assertEquals("На подписи у заказчика от 15.11.26", IdTables.cellText(b, rows[1], TableCol.IGS))
        // Заказчик подписал первую; АН не согласовал четвёртую.
        Vedoms.answer(b, v, items[0], Vedoms.OK, "2026-11-16", "Владимир Шульмин")
        assertEquals(IdTables.IGS_PTO, rows[0].igs)
        val i3 = Vedoms.items(b, v).last()
        Vedoms.answer(b, v, i3, Vedoms.NO, "2026-11-16", "Владимир Шульмин")
        assertEquals(IdTables.IGS_AN_NO, rows[3].igs)
        assertEquals("Не согласовано", Vedoms.resName(Vedoms.kindOf(v, i3), i3.res))
        val sum = Vedoms.summary(b, v, "2026-11-16")
        assertTrue(sum, sum.contains("подписано 1") && sum.contains("не согласовано 1") && sum.contains("ждём 2"))
    }

    // ------------------------------------------------------------ на экране

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        val src = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, MotionEvent.TOOL_TYPE_FINGER))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, x, y, MotionEvent.TOOL_TYPE_FINGER))
        idle()
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun drag(v: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x0, y0, MotionEvent.TOOL_TYPE_FINGER))
        for (i in 1..6) v.dispatchTouchEvent(event(d, d + i * 30L, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 6, y0 + (y1 - y0) * i / 6, MotionEvent.TOOL_TYPE_FINGER))
        v.dispatchTouchEvent(event(d, d + 220, MotionEvent.ACTION_UP, x1, y1, MotionEvent.TOOL_TYPE_FINGER))
        idle()
    }

    /** Подержать палец на месте (дольше, чем [BoardView.PLACE_MS]). */
    private fun hold(v: View, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, MotionEvent.TOOL_TYPE_FINGER))
        pause(700)
        v.dispatchTouchEvent(event(d, d + 700, MotionEvent.ACTION_UP, x, y, MotionEvent.TOOL_TYPE_FINGER))
        idle()
    }

    private class Screen(val a: BoardActivity, val view: BoardView) {
        val b get() = a.board()
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        fun rec(i: Int) = IdTables.allRows(b, b.tables[0])[i]
    }

    private fun open(b: Board): Screen {
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        return Screen(a, view)
    }

    /** ИГС строки i — касанием ячейки: список у ячейки → статус. */
    private fun setIgs(s: Screen, i: Int, sid: String) {
        val r = s.rec(i)
        val t = s.b.tables[0]
        val c = s.view.tableDraw.cellRect(s.b, t, r, TableCol.IGS, RectF())!!
        tap(s.view, s.sx(c.centerX()), s.sy(c.centerY()))
        val idx = s.b.statuses(BoardStatus.ID).indexOfFirst { it.id == sid }
        s.a.tableUi.popup!!.contentView.findViewWithTag<View>("pop_status_$idx").performClick()
        idle()
    }

    @Test
    fun assemblyInTableMakesTodaysVedomAndStateChangesStatuses() {
        val s = open(sample())
        val tb = s.view.tableDraw.bounds(s.b, s.b.tables[0], RectF())
        s.view.fitRect(tb.left - 40f, tb.top - 40f, tb.right + 1400f, tb.bottom + 300f); idle()
        pause(1000)

        // Первая «Сборка» сегодня — предложение завести ведомость от сегодня.
        setIgs(s, 0, IdTables.IGS_ASSEMBLY)
        val ask = s.a.vedomUi.asmAsk!!
        assertTrue(ask.isShowing)
        ask.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() // «Заказчику»
        idle()
        // Место — выбираем сами (как на компьютере: двигаем доску, «правой» — «Вставить»): пока ведомости нет.
        assertTrue(s.a.placingNow)
        assertTrue(s.b.vedoms.isEmpty())
        assertEquals(View.VISIBLE, s.a.window.decorView.findViewWithTag<View>("mode_bar").visibility)
        // Палец двигает доску (и ничего не вставляет).
        val cam0 = s.b.camX
        drag(s.view, s.view.width * 0.7f, s.view.height * 0.5f, s.view.width * 0.5f, s.view.height * 0.5f)
        assertTrue("доска сдвинулась", s.b.camX > cam0 + 10f)
        assertEquals(null, s.a.placePopup)
        // Подержали — контур и меню; «Не сюда» — ищем дальше.
        hold(s.view, s.view.width * 0.5f, s.view.height * 0.6f)
        assertNotNull(s.view.placeGhost)
        s.a.placePopup!!.contentView.findViewWithTag<View>("place_no").performClick(); idle()
        assertEquals(null, s.view.placeGhost)
        assertTrue(s.a.placingNow)
        // Справа от таблицы — «Вставить ведомость сюда».
        val tr = s.view.tableDraw.bounds(s.b, s.b.tables[0], RectF())
        val px = s.sx(tr.right + 300f); val py = s.sy(tr.top)
        hold(s.view, px, py)
        shot(s.view, "assembly-place.png")
        s.a.placePopup!!.contentView.findViewWithTag<View>("place_here").performClick(); idle()
        assertFalse(s.a.placingNow)
        var v = s.b.vedoms.single()
        assertEquals(tr.right + 300f, v.x, 1f); assertEquals(tr.top, v.y, 1f)
        assertEquals(Vedoms.CUST, v.kind); assertEquals(today, v.made); assertTrue(v.isDraft)
        assertEquals(listOf(s.rec(0).id), Vedoms.items(s.b, v).map { it.r })
        assertEquals("Сборка от ${IdTables.dateShort(today)} ▾", Vedoms.stateChip(s.b, v))
        // Ведомость встала справа от таблицы, не поверх.
        assertFalse(RectF.intersects(s.view.tableDraw.bounds(s.b, s.b.tables[0], RectF()), s.view.vedomDraw.bounds(s.b, v, RectF())))

        // Следующие «Сборки» сегодня — сами в эту ведомость, без вопросов.
        setIgs(s, 1, IdTables.IGS_ASSEMBLY)
        assertSame(ask, s.a.vedomUi.asmAsk)
        assertEquals(2, Vedoms.items(s.b, s.b.vedoms.single()).size)
        // «Отменить» — и статус, и место в ведомости.
        s.a.window.decorView.findViewWithTag<View>("board_undo").performClick(); idle()
        assertEquals(IdTables.IGS_WORK, s.rec(1).igs)
        assertEquals(1, Vedoms.items(s.b, s.b.vedoms.single()).size)
        setIgs(s, 1, IdTables.IGS_ASSEMBLY)
        setIgs(s, 2, IdTables.IGS_ASSEMBLY)
        assertEquals(3, Vedoms.items(s.b, s.b.vedoms.single()).size)
        // Передумал: «Сборку» снял — схема ушла из ведомости.
        setIgs(s, 2, IdTables.IGS_WORK)
        v = s.b.vedoms.single()
        assertEquals(listOf(s.rec(0).id, s.rec(1).id), Vedoms.items(s.b, v).map { it.r })
        // В ведомости «ИГС сейчас» второй схемы → «На подписи АН» (касание, список у ячейки).
        val vi = Vedoms.items(s.b, v)[1]
        val vc = s.view.vedomDraw.cellRect(s.b, v, vi, TableCol.IGS, RectF())!!
        tap(s.view, s.sx(vc.centerX()), s.sy(vc.centerY()))
        s.a.vedomUi.popup!!.contentView.findViewWithTag<View>("route_an").performClick(); idle()
        v = s.b.vedoms.single()
        assertEquals(IdTables.IGS_AN, s.rec(1).igs)
        assertEquals(Vedoms.AN, Vedoms.items(s.b, v)[1].via)
        s.view.invalidate(); idle()
        shot(s.view, "assembly-an-route.png")

        // Состояние ведомости → «На подписи у заказчика»: дата — сегодня, у всех её схем статус «… от сегодня».
        s.a.vedomUi.stateDialog(v); idle()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.findViewWithTag<View>("vstate_give").performClick()
        idle()
        v = s.b.vedoms.single()
        assertEquals(today, v.given)
        val d = IdTables.dateShort(today)
        assertEquals(IdTables.IGS_CUST, s.rec(0).igs)
        assertEquals(today, s.rec(0).dcust)
        assertEquals("На подписи у заказчика от $d", IdTables.cellText(s.b, s.rec(0), TableCol.IGS))
        // Схема на АН — статус не тронут.
        assertEquals(IdTables.IGS_AN, s.rec(1).igs)
        assertEquals("", s.rec(1).dcust)
        // Ответ по ней — варианты АН («Не согласовано» есть только у АН).
        s.a.vedomUi.resultPopup(v, listOf(Vedoms.items(s.b, v)[1])); idle()
        assertNotNull(s.a.vedomUi.popup!!.contentView.findViewWithTag<View>("res_no"))
        s.a.vedomUi.popup!!.dismiss()
        assertEquals(IdTables.IGS_WORK, s.rec(2).igs)
        assertEquals("На подписи у заказчика от $d ▾", Vedoms.stateChip(s.b, v))
        assertFalse("и после передачи (статусы длиннее) таблица не наезжает на ведомость",
            RectF.intersects(s.view.tableDraw.bounds(s.b, s.b.tables[0], RectF()), s.view.vedomDraw.bounds(s.b, v, RectF())))
        s.view.invalidate(); idle()
        shot(s.view, "assembly-given.png")

        // Сегодняшняя ведомость уже унесена — новая «Сборка» снова спросит; «Не сегодня» — больше не спрашивает.
        setIgs(s, 2, IdTables.IGS_ASSEMBLY)
        val ask2 = s.a.vedomUi.asmAsk!!
        assertTrue(ask2 !== ask && ask2.isShowing)
        ask2.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertEquals(1, s.b.vedoms.size)
        setIgs(s, 3, IdTables.IGS_ASSEMBLY)
        assertSame(ask2, s.a.vedomUi.asmAsk)
        assertFalse(ask2.isShowing)
        assertEquals(1, s.b.vedoms.size)
        assertNotNull(s.view)
        s.a.finish()
    }
}
