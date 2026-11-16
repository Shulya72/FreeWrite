package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
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
import java.time.Duration

/**
 * Отбор по захваткам и объёмы по отбору (0.27.3, Владимир: «выбрать, например, захватки 1, 2, 3, 4 — и объём суммарный
 * показывался только по ним»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableFilterTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** «Вертикальные элементы» (стены · колонны): захватки 1…10, у каждой опалубка и бетон; объём стен = 10 × номер захватки. */
    private fun sample(): Board {
        val b = Board.create("Отбор")
        val t = BoardTable(100f, 100f, 1.2f, "КЖ 6.1.1").apply { kind = TableKinds.KJ_VERT.id }
        b.tables += t
        var o = 1.0
        for (z in 1..10) for (w in listOf("Опалубка", "Бетон")) b.recs += BoardRec(t.id, o++).apply {
            zah = z.toString(); work = w
            vp["w"] = IdTables.volKey(10.0 * z)
            if (z % 2 == 0) vp["c"] = IdTables.volKey(1.0 * z)
        }
        t.plan[IdTables.planKey("Бетон", "w")] = VolPlan("Бетон", "550", "м³", "w")
        return b
    }

    private fun fact(b: Board, t: BoardTable, work: String, part: String) =
        TableDraw().layout(b, t).vol!!.first { it.work == work && it.part == part }.fact

    @Test
    fun volumesFollowZahFilter() {
        val b = sample()
        val t = b.tables[0]
        // Без отбора — вся таблица: стены 10 + 20 + … + 100 = 550.
        assertEquals(550.0, fact(b, t, "Бетон", "w"), 1e-6)
        assertEquals("", TableDraw().layout(b, t).volFilter)
        // Захватки 1, 2, 3, 4: стены 100, колонны 2 + 4 = 6; строки — только их.
        t.filter = RowFilter(zah = setOf("1", "2", "3", "4"))
        val l = TableDraw().layout(b, t)
        assertEquals(8, l.rows.size)
        assertEquals(100.0, fact(b, t, "Бетон", "w"), 1e-6)
        assertEquals(6.0, fact(b, t, "Опалубка", "c"), 1e-6)
        assertEquals("по отбору: захватки 1, 2, 3, 4 — 8 из 20 схем", l.volFilter)
        // Проект остаётся проектом всей таблицы (доля — 100 из 550).
        assertEquals(550.0, l.vol!!.first { it.work == "Бетон" && it.part == "w" }.plan!!, 1e-6)
        // Порядок захваток в подписи — «по-человечески», и вместе с другим отбором.
        t.filter = RowFilter(igs = setOf(IdTables.IGS_NONE), zah = setOf("10", "2", "1"))
        assertEquals("захватки 1, 2, 10 · ${IdTables.status(b, BoardStatus.ID, IdTables.IGS_NONE).name}", IdTables.filterText(b, t))
        // Захватки с участком: «LM1 · 3» и «LM2 · 3» — разные.
        b.recs.filter { it.zah == "3" }.forEachIndexed { i, r -> r.uch = if (i == 0) "LM1" else "LM2" }
        t.filter = RowFilter(zah = setOf(IdTables.zkey("LM1", "3")))
        assertEquals(1, IdTables.rowsOf(b, t).size)
        assertEquals("захватка LM1 · 3", IdTables.filterText(b, t))
        // Ничего не подошло — объёмы пустые, но блок на месте (видно, что отбор есть).
        t.filter = RowFilter(zah = setOf("99"))
        val l0 = TableDraw().layout(b, t)
        assertEquals(0, l0.rows.size)
        assertNotNull(l0.vol)
        assertTrue(l0.volFilter.startsWith("по отбору:"))
    }

    // ------------------------------------------------------------ на экране

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, x, y))
        idle()
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Снимок окна (для показа Владимиру): разметить по ширине [w] пикселей. */
    private fun shotDialog(d: android.app.AlertDialog, name: String, w: Int = 1100) {
        val root = d.window!!.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bmp = Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.rgb(120, 120, 120))
        root.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun tapZahHeaderChooseFourAndSeeTheirVolume() {
        val b0 = sample()
        BoardStore(ctx).save(b0)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        val b = a.board()
        val t = b.tables[0]
        val tb = view.tableDraw.bounds(b, t, android.graphics.RectF())
        view.fitRect(tb.left - 40f, tb.top - 40f, tb.right + 40f, tb.top + 900f)
        idle(); pause(800)
        shot(view, "table-filter-before.png")
        // Касание «Захватка ▾» в шапке — окно отбора с захватками кнопками.
        val l = view.tableDraw.layout(b, t)
        val ci = l.cols.indexOfFirst { it.k == TableCol.ZAH }
        val hx = t.x + (l.colX[ci] + l.colX[ci + 1]) / 2f * t.scale
        val hy = t.y + (view.tableDraw.rowsTop(l) - l.headH / 2f) * t.scale
        tap(view, (hx - b.camX) * b.camZ, (hy - b.camY) * b.camZ)
        val d = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("окно отбора", d)
        val root = d.window!!.decorView
        for (i in 0 until 4) root.findViewWithTag<CheckBox>("flt_zah_$i").isChecked = true
        assertEquals("10", root.findViewWithTag<CheckBox>("flt_zah_9").text.toString()) // 1, 2 … 10, не 1, 10, 2
        shotDialog(d, "table-filter-dialog.png")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle(); pause(300)
        assertEquals(setOf("1", "2", "3", "4"), t.filter!!.zah)
        val lf = view.tableDraw.layout(b, t)
        assertEquals(8, lf.rows.size)
        assertEquals(100.0, lf.vol!!.first { it.work == "Бетон" && it.part == "w" }.fact, 1e-6)
        shot(view, "table-filter-zah.png")

        // Правка и «Отменить» — отбор остаётся.
        a.pushUndo(); b.recs.first().note = "x"; a.changed()
        a.undo(); idle()
        assertEquals(setOf("1", "2", "3", "4"), a.board().tables[0].filter?.zah)

        // «Все» / «Снять» в окне; «Показать всё» — снова вся таблица.
        a.tableUi.filterDialog(a.board().tables[0])
        val d2 = ShadowAlertDialog.getLatestAlertDialog()
        d2.window!!.decorView.findViewWithTag<View>("flt_zah_all").performClick()
        assertTrue((0 until 10).all { d2.window!!.decorView.findViewWithTag<CheckBox>("flt_zah_$it").isChecked })
        d2.window!!.decorView.findViewWithTag<View>("flt_zah_none").performClick()
        assertTrue((0 until 10).none { d2.window!!.decorView.findViewWithTag<CheckBox>("flt_zah_$it").isChecked })
        d2.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertNull(a.board().tables[0].filter)
        assertEquals(550.0, view.tableDraw.layout(a.board(), a.board().tables[0]).vol!!.first { it.work == "Бетон" && it.part == "w" }.fact, 1e-6)
    }

    @Test
    fun newZahvatkaStaysVisibleWhileFiltered() {
        val b0 = sample()
        BoardStore(ctx).save(b0)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val t = a.board().tables[0]
        t.filter = RowFilter(zah = setOf("9", "10"))
        // «+ следующая захватка» — 11; при отборе по захваткам она добавляется к отобранным, а не прячется.
        a.tableUi.addForm(t, next = true)
        val d = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("11", d.window!!.decorView.findViewWithTag<EditText>("add_zah").text.toString())
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val tt = a.board().tables[0]
        assertEquals(setOf("9", "10", "11"), tt.filter!!.zah)
        assertTrue(IdTables.rowsOf(a.board(), tt).any { it.zah == "11" })
    }
}
