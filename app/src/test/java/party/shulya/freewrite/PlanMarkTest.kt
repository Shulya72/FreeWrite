package party.shulya.freewrite

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import java.io.FileOutputStream
import java.time.Duration
import java.util.UUID

/**
 * Разметка этажа и захватки касанием (0.26, Владимир: «размечаю этаж заранее, потом выбираю захватку и пальцем
 * проставляю залитые участки — они окрасились; процент выполнения конструкции»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanMarkTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        val src = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
    }

    private fun tap(v: View, p: Pair<Float, Float>, tool: Int = MotionEvent.TOOL_TYPE_FINGER) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, p.first, p.second, tool))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, p.first, p.second, tool))
        idle()
    }

    private fun drag(v: View, a: Pair<Float, Float>, b: Pair<Float, Float>, tool: Int = MotionEvent.TOOL_TYPE_FINGER) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, a.first, a.second, tool))
        for (i in 1..12) v.dispatchTouchEvent(event(d, d + i * 20L, MotionEvent.ACTION_MOVE, a.first + (b.first - a.first) * i / 12, a.second + (b.second - a.second) * i / 12, tool))
        v.dispatchTouchEvent(event(d, d + 300, MotionEvent.ACTION_UP, b.first, b.second, tool))
        idle()
    }

    private fun shot(v: View, name: String) {
        repeat(30) { v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888))); idle(); Thread.sleep(20) }
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Screen(val a: BoardActivity, val view: BoardView) {
        val b get() = a.board()
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        fun swatch(r: BoardRec): Pair<Float, Float> {
            val t = IdTables.tableOf(b, r)!!
            val c = view.tableDraw.cellRect(b, t, r, TableCol.ZAH, RectF())!!
            return sx(c.left + (TableDraw.PAD + TableDraw.SWATCH / 2f) * t.scale) to sy(c.centerY())
        }
        fun onSheet(t: BoardTable, fx: Float, fy: Float): Pair<Float, Float> {
            val r = view.tableDraw.sheetRect(b, t, RectF())!!
            val p = FloatArray(2)
            view.tableDraw.fromSheet(TablePlan.current(t)!!, r, fx, fy, p)
            return sx(p[0]) to sy(p[1])
        }
    }

    private fun open(b: Board): Screen {
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        return Screen(a, view)
    }

    private fun latest() = ShadowAlertDialog.getLatestAlertDialog()

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 20000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("не дождались: $what")
            Thread.sleep(40); idle()
        }
    }

    private fun clickTag(tag: String) {
        val v = latest()?.window?.decorView?.findViewWithTag<View>(tag)
        assertNotNull("нет кнопки $tag", v)
        v!!.performClick(); idle()
    }

    private fun barClick(s: Screen, tag: String) {
        val v = s.a.window.decorView.findViewWithTag<View>(tag)
        assertNotNull("нет кнопки $tag на панели", v)
        v.performClick(); idle()
    }

    /** Доска: таблица «Несущие стены» (2 захватки), лист плана с PDF (2 пилона, стена с проёмом, ненесущая). */
    private fun sample(): Board {
        val b = Board.create("КЖ 7.1.3")
        val t = BoardTable(100f, 100f, 1.2f, "Несущие стены").apply { kind = TableKinds.KJ_VERT.id }
        b.tables += t
        for ((o, z) in listOf("1", "2").withIndex()) b.recs += BoardRec(t.id, o + 1.0).apply { zah = z; work = "Бетон" }
        val white = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val (f, w, h) = BoardImages.importBitmap(ctx, white)
        val pdfName = "walls-" + UUID.randomUUID() + ".pdf"
        File(File(ctx.filesDir, "note_images").apply { mkdirs() }, pdfName).writeBytes(PlanElementsTest.pdf(PlanElementsTest.WALLS, PlanElementsTest.WALL_LAYERS))
        t.sheets += PlanSheet(f, w, h).also { it.pdf = pdfName }
        return b
    }

    private fun zoomSheet(s: Screen) {
        val r = s.view.tableDraw.sheetRect(s.b, s.b.tables[0], RectF())!!
        s.view.fitRect(r.left, r.top, r.right, r.bottom)
        idle(); pause(600)
    }

    @Test
    fun findAssignScissorsEraseUndoColor() {
        val s = open(sample())
        var t = s.b.tables[0]
        // «Найти элементы»: по названию таблицы — несущие.
        s.a.planUi.findDialog(t)
        assertTrue((s.a.planUi.findDlg!!.window!!.decorView.findViewWithTag<android.widget.RadioButton>("find_0")).isChecked)
        s.a.planUi.findDlg!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        waitFor("поиск") { !s.a.planUi.finding }
        idle()
        assertTrue(latest().isShowing)
        latest().dismiss()
        val els = TablePlan.elements(s.b, t)
        assertEquals(4, els.size)
        assertTrue(els.all { it.free })
        assertEquals("каждой захватке — свой цвет", TablePlan.OWN, t.zoneColor)
        assertEquals(0, TablePlan.percent(s.b, t))

        // Квадратик захватки 1 → отмечать элементы.
        val r1 = s.b.recs.first { it.zah == "1" }
        s.view.fitRect(t.x, t.y, t.x + s.view.tableDraw.layout(s.b, t).w * t.scale, t.y + 300f)
        idle(); pause(400)
        tap(s.view, s.swatch(r1))
        assertEquals(PlanEdit.ASSIGN, s.view.planEdit?.tool)
        zoomSheet(s)
        // Пилон 1 (100…110 × 250…300 из 600×400): касание — в захватку 1.
        val pylon1 = 0.175f to 0.6875f
        val pylon2 = 0.341f to 0.6875f
        fun zoneAt(f: Pair<Float, Float>) = s.b.zones.first { it.e && it.contains(f.first, f.second) }
        tap(s.view, s.onSheet(t, pylon1.first, pylon1.second))
        assertEquals("1", zoneAt(pylon1).zah)
        assertTrue(TablePlan.percent(s.b, t)!! > 0)
        // Повторное касание — снять.
        tap(s.view, s.onSheet(t, pylon1.first, pylon1.second))
        assertTrue(zoneAt(pylon1).free)
        // Провести по двум пилонам — оба.
        drag(s.view, s.onSheet(t, 0.14f, 0.69f), s.onSheet(t, 0.37f, 0.69f))
        assertEquals("1", zoneAt(pylon1).zah)
        assertEquals("1", zoneAt(pylon2).zah)
        val pct = TablePlan.percent(s.b, t)!!
        assertTrue("два пилона из четырёх элементов: $pct", pct in 10..60)
        assertEquals(pct, s.view.tableDraw.layout(s.b, t).plan!!.pct)
        shot(s.view, "plan-elements-assign.png")
        // «Отменить» — шаг назад, режим остаётся.
        barClick(s, "pt_undo")
        t = s.b.tables[0] // после отмены доска — из снимка (новые объекты)
        assertTrue(zoneAt(pylon1).free && zoneAt(pylon2).free)
        assertEquals(PlanEdit.ASSIGN, s.view.planEdit?.tool)
        // Снова отметить и выбрать «любой цвет».
        tap(s.view, s.onSheet(t, pylon1.first, pylon1.second))
        barClick(s, "pt_color")
        clickTag("palette_custom")
        ColorKit.lastPicker!!.set(120f, 0.8f, 0.6f)
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(Color.HSVToColor(floatArrayOf(120f, 0.8f, 0.6f)), zoneAt(pylon1).color)
        barClick(s, "pt_done")
        assertEquals(null, s.view.planEdit)

        // Ножницы: провести поперёк левого куска стены (300…340 × 192…200) — два куска и шов.
        s.a.planUi.startElements(t, PlanEdit.SCISSORS)
        zoomSheet(s)
        val before = TablePlan.elements(s.b, t).size
        drag(s.view, s.onSheet(t, 0.531f, 0.44f), s.onSheet(t, 0.536f, 0.54f))
        assertEquals(before + 1, TablePlan.elements(s.b, t).size)
        assertEquals(1, TablePlan.current(t)!!.seams.size)
        // «Убрать» — касание пилона 2.
        s.a.planUi.startElements(t, PlanEdit.ERASE)
        zoomSheet(s)
        tap(s.view, s.onSheet(t, pylon2.first, pylon2.second))
        assertFalse(s.b.zones.any { it.e && it.contains(pylon2.first, pylon2.second) })
        s.a.planUi.endOutline(save = false)
        shot(s.view, "plan-elements.png")

        // Сохранение: элементы, дыры, швы, PDF листа.
        val back = Board.fromJson(JSONObject(s.b.toJsonString()))
        val tb = back.tables[0]
        assertEquals(TablePlan.elements(s.b, t).size, TablePlan.elements(back, tb).size)
        assertEquals(1, tb.sheets[0].seams.size)
        assertTrue(tb.sheets[0].pdf.endsWith(".pdf"))
        assertTrue(TablePlan.files(back).any { it.endsWith(".pdf") })
    }

    /** Меню листа (0.26.2, Владимир: «как удалить лист с PDF из таблицы?»): шапка «План захваток ▾», открытая вкладка, «Элементы ▾». */
    @Test
    fun sheetMenuFromHeaderTabAndElements() {
        val s = open(sample())
        var t = s.b.tables[0]
        val first = t.sheets[0]
        // Второй лист — появляются вкладки.
        val white = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val (f2, w2, h2) = BoardImages.importBitmap(ctx, white)
        s.a.pushUndo(); t.sheets += PlanSheet(f2, w2, h2).also { it.name = "Отм. +3.300" }; t.lay = null; s.a.changed(); idle()
        s.view.fitAll(); idle()
        fun headerPoint(): Pair<Float, Float> {
            val tt = s.b.tables[0]
            val p = s.view.tableDraw.layout(s.b, tt).plan!!
            return s.sx(tt.x + 40f * tt.scale) to s.sy(tt.y + (p.top + TableDraw.PLAN_HEAD / 2f) * tt.scale)
        }
        fun tabPoint(i: Int): Pair<Float, Float> {
            val tt = s.b.tables[0]
            val p = s.view.tableDraw.layout(s.b, tt).plan!!
            return s.sx(tt.x + (p.tabX[i] + 20f) * tt.scale) to s.sy(tt.y + (p.top + TableDraw.PLAN_HEAD + p.tabsH / 2f + 2f) * tt.scale)
        }
        // Касание шапки — меню листа.
        tap(s.view, headerPoint())
        assertTrue(latest()!!.isShowing)
        assertEquals("Убрать этот лист", latest()!!.listView.adapter.getItem(3))
        latest()!!.dismiss(); idle()
        // Другая вкладка — открывается; уже открытая — меню листа.
        tap(s.view, tabPoint(1))
        t = s.b.tables[0]
        assertEquals(1, t.tab)
        tap(s.view, tabPoint(1))
        val menu = latest()!!
        assertTrue(menu.isShowing)
        menu.listView.performItemClick(menu.listView.getChildAt(3) ?: menu.listView, 3, 3)
        idle()
        s.a.planUi.removeDlg!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        t = s.b.tables[0]
        assertEquals(listOf(first.id), t.sheets.map { it.id })
        assertEquals(0, t.tab)
        // «Отменить» — лист вернулся.
        s.a.undo(); idle()
        assertEquals(2, s.b.tables[0].sheets.size)
        // «Элементы ▾» → последний пункт — убрать лист (открыт первый).
        s.a.planUi.setTab(s.b.tables[0], 0); idle()
        s.a.planUi.elementsMenu(s.b.tables[0])
        val em = latest()!!
        val last = em.listView.adapter.count - 1
        assertEquals("Убрать этот лист из таблицы…", em.listView.adapter.getItem(last))
        em.listView.performItemClick(em.listView.getChildAt(last) ?: em.listView, last, last.toLong())
        idle()
        s.a.planUi.removeDlg!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf("Отм. +3.300"), s.b.tables[0].sheets.map { it.name })
        shot(s.view, "plan-sheet-menu.png")
    }

    /** Ручная обводка по точкам (0.26.1): касание рядом с углом пилона — точка ровно в угол чертежа. */
    @Test
    fun pointsSnapToDrawingCorners() {
        val s = open(sample())
        val t = s.b.tables[0]
        s.a.planUi.startOutline(t, "", "1")
        val pe = s.view.planEdit!!
        pe.tool = PlanEdit.POINTS
        waitFor("привязка по PDF") { pe.snap != null }
        zoomSheet(s)
        // Угол пилона 1 — 100 / 600, 250 / 400; касаемся чуть мимо.
        val near = s.onSheet(t, 0.169f, 0.628f)
        val d = SystemClock.uptimeMillis()
        s.view.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, near.first, near.second, MotionEvent.TOOL_TYPE_FINGER))
        s.view.dispatchTouchEvent(event(d, d + 40, MotionEvent.ACTION_MOVE, near.first + 1f, near.second, MotionEvent.TOOL_TYPE_FINGER))
        idle()
        shot(s.view, "plan-snap-loupe.png")
        s.view.dispatchTouchEvent(event(d, d + 80, MotionEvent.ACTION_UP, near.first + 1f, near.second, MotionEvent.TOOL_TYPE_FINGER))
        idle()
        assertEquals(2, pe.points.size)
        assertEquals(100f / 600f, pe.points[0], 0.0015f)
        assertEquals(250f / 400f, pe.points[1], 0.0015f)
        s.a.planUi.endOutline(save = false)
    }

    @Test
    fun oldZonesStaySameAndPercentNeedsElements() {
        // Область 0.23 (без «e») — строка JSON та же, процента нет.
        val z = BoardZone("t1", "s1", "", "3", MagicFill.rect(0.1f, 0.1f, 0.3f, 0.3f), Color.RED)
        val js = TablePlan.zoneJson(z)
        assertFalse(js.contains("\"e\""))
        assertFalse(js.contains("\"h\""))
        val back = TablePlan.parseZone(JSONObject(js))!!
        assertFalse(back.e)
        // Элемент с дырой: дыра — не внутри.
        val e = BoardZone("t1", "s1", "", "", MagicFill.rect(0.1f, 0.1f, 0.5f, 0.5f), TablePlan.FREE).also { it.e = true; it.holes = listOf(MagicFill.rect(0.2f, 0.2f, 0.3f, 0.3f)) }
        val e2 = TablePlan.parseZone(JSONObject(TablePlan.zoneJson(e)))!!
        assertTrue(e2.e && e2.free)
        assertEquals(1, e2.holes.size)
        assertFalse(e2.contains(0.25f, 0.25f))
        assertTrue(e2.contains(0.15f, 0.15f))
        assertEquals((0.16 - 0.01) * 0.7, e2.area(0.7f).toDouble(), 1e-4)
    }

    @Test
    fun manyZahvatkasGetDistinctColors() {
        val cs = (0 until 40).map { TablePlan.autoColor(it) }
        assertEquals(40, cs.toSet().size)
        // Соседние номера заметно разные.
        for (i in 1 until 40) {
            val a = cs[i - 1]; val b = cs[i]
            val d = Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b))
            assertTrue("цвета $i и ${i - 1} похожи", d > 60)
        }
    }
}
