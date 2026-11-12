package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.FileOutputStream
import java.time.Duration

/**
 * План под таблицей (0.23, Владимир: «сверху захватки, а снизу план-схема, на которой отображаются захватки»): лист
 * (картинка/PDF) с обрезкой при вставке, области захваток — волшебной заливкой, прямоугольником, кругом, по точкам;
 * цвет по ИГС или свой; касание области — строки, касание номера — области.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TablePlanTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Чертёж: рамка листа, три помещения (захватки 1–3) с дверями, колонна, штамп в углу. */
    private fun planBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        fun box(l: Int, t: Int, r: Int, b: Int) = c.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), p)
        // Рамка листа и штамп — их отрежут.
        box(10, 10, 1190, 14); box(10, 786, 1190, 790); box(10, 10, 14, 790); box(1186, 10, 1190, 790)
        box(900, 700, 1186, 704); box(900, 700, 904, 786)
        // Здание: стены 6 px, перегородки с дверями.
        box(100, 100, 1000, 106); box(100, 594, 1000, 600); box(100, 100, 106, 600); box(994, 100, 1000, 600)
        box(397, 100, 403, 330); box(397, 350, 403, 600) // дверь 20 px
        box(697, 100, 703, 330); box(697, 350, 703, 600)
        box(540, 330, 560, 350) // колонна 20×20 в помещении 2
        return bmp
    }

    private fun sample(withPlan: Boolean = false): Board {
        val b = Board.create("План")
        b.frames += BoardFrame(0f, 0f, 3000f, 3000f, "КЖ 6.1.1")
        val t = BoardTable(100f, 100f, 1.2f, "Несущие стены")
        b.tables += t
        var o = 1.0
        for (z in listOf("1", "2", "3")) for (w in listOf("Опалубка", "Бетон")) b.recs += BoardRec(t.id, o++).apply {
            zah = z; con = "Стены"; work = w
            igs = when (z) { "1" -> IdTables.IGS_PTO; "2" -> if (w == "Опалубка") IdTables.IGS_PTO else IdTables.IGS_CUST; else -> IdTables.IGS_WORK }
        }
        if (withPlan) {
            val (f, w, h) = BoardImages.importBitmap(ctx, planBitmap())
            t.sheets += PlanSheet(f, w, h).also { it.setCrop(0.05f, 0.05f, 0.88f, 0.86f); it.name = "План на отм. +3.300" }
        }
        return b
    }

    @Test
    fun modelJsonUndoShareAndStatus() {
        val b = sample()
        val t = b.tables[0]
        val plain = IdTables.tableJson(t)
        assertFalse("таблица без плана — та же строка, что до 0.23", plain.contains("sheets"))
        val sh = PlanSheet("abc.jpg", 1200, 800).also { it.setCrop(0.1f, 0.1f, 0.9f, 0.8f); it.name = "Лист А" }
        t.sheets += sh
        t.zoneColor = TablePlan.OWN
        val z = BoardZone(t.id, sh.id, "", "2", MagicFill.rect(0.4f, 0.2f, 0.6f, 0.6f), TablePlan.PALETTE[1])
        b.zones += z
        val back = Board.fromJson(JSONObject(b.toJsonString()))
        val bt = back.tables[0]
        assertEquals(IdTables.tableJson(t), IdTables.tableJson(bt))
        assertEquals("Лист А", bt.sheets[0].name); assertEquals(0.1f, bt.sheets[0].cl, 1e-4f); assertEquals(TablePlan.OWN, bt.zoneColor)
        assertEquals(TablePlan.zoneJson(z), TablePlan.zoneJson(back.zones[0]))
        assertTrue(back.imageFiles().contains("abc.jpg"))
        // Точка внутри области, захватка, строки.
        assertTrue(z.contains(0.5f, 0.4f)); assertFalse(z.contains(0.7f, 0.4f))
        // Цвет по ИГС — самая отстающая схема: у захватки 2 бетон «На подписи у заказчика» → жёлтый.
        t.zoneColor = ""
        assertEquals(IdTables.IGS_CUST, TablePlan.zahStatus(b, t, "", "2")!!.id)
        assertEquals(b.status(IdTables.IGS_CUST).color, TablePlan.zoneColor(b, t, z))
        assertEquals(listOf(IdTables.IGS_CUST to 1), TablePlan.legend(b, t).map { it.first!!.id to it.second })
        assertTrue(TablePlan.zoneInfo(b, t, "", "2").startsWith("Захватка 2 · опалубка — Сдано в ПТО"))
        // Отмена.
        val snap = b.snapshot()
        b.zones.clear(); t.sheets.clear()
        val r = b.restored(snap)
        assertEquals(1, r.zones.size); assertEquals(1, r.tables[0].sheets.size)
        // Обмен файлом: область коллеги приходит.
        b.zones += z; t.sheets += sh
        b.toJsonString(stampDevice = "dev1")
        val other = Board.fromJson(JSONObject(b.toJsonString()))
        other.zones += BoardZone(t.id, sh.id, "", "3", MagicFill.ellipse(0.7f, 0.2f, 0.85f, 0.6f), TablePlan.PALETTE[2])
        other.toJsonString(stampDevice = "dev2")
        Thread.sleep(5)
        BoardShare.merge(b, Board.fromJson(JSONObject(other.toJsonString())))
        assertEquals(setOf("2", "3"), b.zones.map { it.zah }.toSet())
        // Сервер: вид «zone» среди правок.
        assertTrue(Remote.pending(b).any { it.kind == "zone" })
        // Удалили таблицу — её области тоже.
        b.zones.removeAll { it.t == t.id }
        assertTrue(TablePlan.zonesOf(b, t).isEmpty())
    }

    // ------------------------------------------------------------ на экране

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        val src = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
    }

    private fun tap(v: View, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_FINGER) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, tool))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, x, y, tool))
        idle()
    }

    private fun drag(v: View, x0: Float, y0: Float, x1: Float, y1: Float, tool: Int = MotionEvent.TOOL_TYPE_FINGER) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x0, y0, tool))
        for (i in 1..6) v.dispatchTouchEvent(event(d, d + i * 30L, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 6, y0 + (y1 - y0) * i / 6, tool))
        v.dispatchTouchEvent(event(d, d + 220, MotionEvent.ACTION_UP, x1, y1, tool))
        idle()
    }

    private fun shot(v: View, name: String) {
        // Картинки листов подгружаются в фоне.
        repeat(40) { v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888))); idle(); Thread.sleep(25) }
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Screen(val a: BoardActivity, val view: BoardView) {
        val b get() = a.board()
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        fun cell(r: BoardRec, col: String): Pair<Float, Float> {
            val t = IdTables.tableOf(b, r)!!
            val c = view.tableDraw.cellRect(b, t, r, col, RectF())!!
            return sx(c.centerX()) to sy(c.centerY())
        }
        /** Точка листа (доли картинки целиком) на экране. */
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

    /** Дождаться фоновой работы (волшебная заливка считается не в главном потоке). */
    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("не дождались: $what")
            Thread.sleep(40); idle()
        }
    }

    private fun fitTable(s: Screen) {
        val t = s.b.tables[0]
        val r = s.view.tableDraw.bounds(s.b, t, RectF())
        s.view.fitRect(r.left, r.top, r.right, r.bottom)
        idle(); pause(600)
    }

    @Test
    fun addPlanWithCropOutlineAndHighlight() {
        val s = open(sample())
        val root = s.a.window.decorView
        val t = s.b.tables[0]
        fitTable(s)
        // Без плана внизу таблицы — «+ план».
        val l0 = s.view.tableDraw.layout(s.b, t)
        assertNull(l0.plan)
        assertTrue(l0.planSplit < l0.w)
        // Выбрали картинку чертежа (выбор файла в проверке не открыть — подаём готовую) → обрезка.
        val img = BoardImages.importBitmap(ctx, planBitmap())
        s.a.planUi.target = t
        s.a.planUi.onSheets(listOf(img))
        idle()
        val cv = s.a.planUi.cropView!!
        cv.layout(0, 0, 1600, 900); cv.draw(Canvas(Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)))
        // Отрезаем рамку листа и штамп: левый верхний угол внутрь, правый нижний — к зданию.
        cv.dragFraction(0f, 0f, 0.05f, 0.08f)
        cv.dragFraction(1f, 1f, 0.88f, 0.86f)
        s.a.planUi.cropDialog!!.findViewById<View>(android.R.id.content).findViewWithTag<View>("pcrop_done").performClick()
        idle()
        val sh = t.sheets.single()
        assertEquals(0.05f, sh.cl, 0.01f); assertEquals(0.08f, sh.ct, 0.01f); assertEquals(0.88f, sh.cr, 0.01f); assertEquals(0.86f, sh.cb, 0.01f)
        val l1 = s.view.tableDraw.layout(s.b, t)
        assertNotNull(l1.plan)
        fitTable(s)

        // Касание номера захватки «1» пальцем — обвести; волшебная заливка в помещении 1.
        val r1 = IdTables.zahRows(s.b, t, "", "1")[0]
        val (zx, zy) = s.cell(r1, TableCol.ZAH)
        tap(s.view, zx, zy)
        val pe = s.view.planEdit!!
        assertEquals("1", pe.zah)
        val (rx, ry) = s.onSheet(t, 250f / 1200f, 350f / 800f)
        waitFor("подготовка листа") { tap(s.view, rx, ry); pe.pieces.isNotEmpty() || pe.busy }
        waitFor("волшебная заливка") { !pe.busy && pe.pieces.isNotEmpty() }
        val piece = pe.pieces.single()
        val box = BoardZone("", "", "", "", piece, 0).box()
        // Помещение 1: от стены x=103 до перегородки x=400, y 103…597 (доли картинки).
        assertEquals(103f / 1200f, box[0], 0.012f); assertEquals(400f / 1200f, box[2], 0.012f)
        assertEquals(103f / 800f, box[1], 0.015f); assertEquals(597f / 800f, box[3], 0.015f)
        root.findViewWithTag<View>("pt_done").performClick()
        idle()
        val z1 = s.b.zones.single()
        assertEquals("1", z1.zah); assertEquals(t.id, z1.t); assertEquals(sh.id, z1.s)
        // После «Готово» — захватка подсвечена и в таблице, и на плане.
        assertEquals(IdTables.zahRows(s.b, t, "", "1").toSet(), s.view.markedRecs)
        assertEquals(setOf(z1), s.view.markedZones)
        root.findViewWithTag<View>("zone_done").performClick()
        idle()

        // Захватка 2: «Обвести захватку» в шапке плана → «2» → колонна и прямоугольник; «Отменить» убирает кусок.
        s.a.planUi.chooseZah(t)
        latest().window!!.decorView.findViewWithTag<View>("pzah_1").performClick()
        idle()
        val pe2 = s.view.planEdit!!
        val (cx, cy) = s.onSheet(t, 550f / 1200f, 340f / 800f)
        tap(s.view, cx, cy)
        waitFor("колонна") { !pe2.busy && pe2.pieces.isNotEmpty() }
        val cb = BoardZone("", "", "", "", pe2.pieces[0], 0).box()
        assertTrue("колонна 20×20: ${(cb[2] - cb[0]) * 1200}", (cb[2] - cb[0]) * 1200 in 12f..34f)
        root.findViewWithTag<View>("pt_rect").performClick()
        val (ax, ay) = s.onSheet(t, 420f / 1200f, 120f / 800f)
        val (bx, by) = s.onSheet(t, 680f / 1200f, 580f / 800f)
        drag(s.view, ax, ay, bx, by)
        assertEquals(2, pe2.pieces.size)
        root.findViewWithTag<View>("pt_undo").performClick()
        assertEquals(1, pe2.pieces.size)
        drag(s.view, ax, ay, bx, by)
        root.findViewWithTag<View>("pt_done").performClick()
        idle()
        assertEquals(3, s.b.zones.size)
        root.findViewWithTag<View>("zone_done").performClick()
        idle()

        // Захватка 3 — по точкам, касание первой точки замыкает.
        s.a.planUi.startOutline(t, "", "3")
        idle()
        root.findViewWithTag<View>("pt_points").performClick()
        for ((fx, fy) in listOf(720f to 120f, 980f to 120f, 980f to 580f, 720f to 580f, 720f to 120f)) {
            val (px, py) = s.onSheet(t, fx / 1200f, fy / 800f)
            tap(s.view, px, py)
        }
        assertEquals(1, s.view.planEdit!!.pieces.size)
        root.findViewWithTag<View>("pt_done").performClick()
        idle()
        assertEquals(4, s.b.zones.size)
        root.findViewWithTag<View>("zone_done").performClick()
        idle()

        // Цвета по ИГС: 1 — сдано (зелёный), 2 — на подписи у заказчика, 3 — в работе. Легенда.
        assertEquals(s.b.status(IdTables.IGS_PTO).color, TablePlan.zoneColor(s.b, t, s.b.zones.first { it.zah == "1" }))
        assertEquals(s.b.status(IdTables.IGS_WORK).color, TablePlan.zoneColor(s.b, t, s.b.zones.first { it.zah == "3" }))
        assertEquals(3, s.view.tableDraw.layout(s.b, t).plan!!.legend.size)
        // Значок цвета у номера захватки в таблице.
        assertEquals(s.b.status(IdTables.IGS_PTO).color, s.view.tableDraw.layout(s.b, t).swatch["1"])
        fitTable(s)
        s.view.invalidate()
        shot(s.view, "table-plan.png")

        // Касание области на плане — подсвечены строки захватки 2, на панели — что с ней.
        val (qx, qy) = s.onSheet(t, 470f / 1200f, 450f / 800f)
        tap(s.view, qx, qy)
        assertEquals(IdTables.zahRows(s.b, t, "", "2").toSet(), s.view.markedRecs)
        assertEquals(2, s.view.markedZones!!.size)
        // «Стереть с плана» и «Отменить».
        root.findViewWithTag<View>("zone_erase").performClick()
        idle()
        assertEquals(2, s.b.zones.size)
        root.findViewWithTag<View>("board_undo").performClick()
        idle()
        assertEquals(4, s.a.board().zones.size)

        // Переключатель цвета: свой.
        s.a.planUi.colorMenu(s.a.board().tables[0])
        val d = latest()
        d.listView.performItemClick(d.listView.getChildAt(1) ?: d.listView, 1, 1)
        idle()
        val t2 = s.a.board().tables[0]
        assertEquals(TablePlan.OWN, t2.zoneColor)
        val zz = s.a.board().zones.first { it.zah == "1" }
        assertEquals(zz.color, TablePlan.zoneColor(s.a.board(), t2, zz))

        // Второй лист — вкладки; переключение.
        s.a.planUi.target = t2
        s.a.planUi.onSheets(listOf(BoardImages.importBitmap(ctx, planBitmap())))
        idle()
        s.a.planUi.cropDialog!!.findViewById<View>(android.R.id.content).findViewWithTag<View>("pcrop_done").performClick()
        idle()
        assertEquals(2, t2.sheets.size)
        assertEquals(1, t2.tab)
        val pl = s.view.tableDraw.layout(s.a.board(), t2).plan!!
        assertTrue(pl.tabsH > 0f)
        s.a.tableUi.onTap(t2, TableHit(TableHit.PLAN_TAB, null, "0"), pen = false)
        assertEquals(0, t2.tab)
        fitTable(s)
        shot(s.view, "table-plan-own.png")
        s.a.finish()
    }

    @Test
    fun planOnFarZoomAndSiteFiles() {
        val b = sample(withPlan = true)
        val t = b.tables[0]
        b.zones += BoardZone(t.id, t.sheets[0].id, "", "1", MagicFill.rect(0.09f, 0.13f, 0.33f, 0.74f), TablePlan.PALETTE[0])
        val s = open(b)
        s.view.fitRect(-2000f, -2000f, 6000f, 6000f)
        idle(); pause(600)
        s.view.invalidate()
        shot(s.view, "table-plan-far.png") // издалека не падает
        assertTrue(s.a.board().imageFiles().contains(t.sheets[0].file))
        val pkg = BoardShare.prepare(s.a.board(), onlyNew = false)
        assertTrue(pkg.files.contains(t.sheets[0].file))
        s.a.finish()
    }
}
