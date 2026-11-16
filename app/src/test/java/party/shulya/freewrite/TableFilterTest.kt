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
import android.widget.CheckBox
import android.widget.EditText
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
import java.time.Duration

/**
 * Отбор как в Excel (0.27.3, Владимир: «выбрать захватки 1, 2, 3, 4 — и объём суммарный только по ним»; «фильтрация не
 * отдельной кнопкой, а у каждой таблицы, как в Excel»): у каждого столбца в шапке «▾» — значения с галочками, поиск,
 * «(Выделить всё)»; столбцы складываются; объёмы — по видимым строкам.
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
            if (z <= 3) igs = IdTables.IGS_WORK
            if (z == 1) date = "2026-11-02"
        }
        t.plan[IdTables.planKey("Бетон", "w")] = VolPlan("Бетон", "550", "м³", "w")
        return b
    }

    private fun fact(b: Board, t: BoardTable, work: String, part: String) =
        TableDraw().layout(b, t).vol!!.first { it.work == work && it.part == part }.fact

    @Test
    fun columnFiltersLikeExcel() {
        val b = sample()
        val t = b.tables[0]
        // Значения столбца: по порядку «по-человечески», с числом строк.
        val zv = IdTables.filterValues(b, t, TableCol.ZAH)
        assertEquals((1..10).map { it.toString() }, zv.map { it.label })
        assertTrue(zv.all { it.count == 2 })
        assertEquals(550.0, fact(b, t, "Бетон", "w"), 1e-6)
        // Захватки 1, 2, 3, 4: стены 100, колонны 2 + 4 = 6; строки — только их.
        t.filter = RowFilter().with(TableCol.ZAH, setOf("1", "2", "3", "4"))
        val l = TableDraw().layout(b, t)
        assertEquals(8, l.rows.size)
        assertEquals(100.0, fact(b, t, "Бетон", "w"), 1e-6)
        assertEquals(6.0, fact(b, t, "Опалубка", "c"), 1e-6)
        assertEquals("по отбору: Захватка: 1, 2, 3, 4 — 8 из 20 схем", l.volFilter)
        assertEquals(550.0, l.vol!!.first { it.work == "Бетон" && it.part == "w" }.plan!!, 1e-6) // проект — всей таблицы
        // Ещё столбец — складываются («и»): только бетон этих захваток. Список значений захватки — с учётом вида работ (как в Excel).
        t.filter = t.filter!!.with(TableCol.WORK, setOf("бетон"))
        assertEquals(4, IdTables.rowsOf(b, t).size)
        assertTrue(IdTables.filterValues(b, t, TableCol.ZAH).all { it.count == 1 })
        assertEquals(listOf("Опалубка", "Бетон").sorted(), IdTables.filterValues(b, t, TableCol.WORK).map { it.label }.sorted())
        // Статусы — по названию, в порядке списка; даты — «(Пустые)» в конце.
        val iv = IdTables.filterValues(b, t, TableCol.IGS)
        assertEquals(IdTables.status(b, BoardStatus.ID, IdTables.IGS_WORK).name, iv.first { it.key == IdTables.IGS_WORK }.label)
        t.filter = null
        val dv = IdTables.filterValues(b, t, TableCol.DATE)
        assertEquals(listOf("02.11.26", IdTables.EMPTY_LABEL), dv.map { it.label })
        // Снять отбор столбца; снято всё — null.
        val f = RowFilter().with(TableCol.ZAH, setOf("1"))!!
        assertNull(f.with(TableCol.ZAH, null))
        assertTrue(f.with(TableCol.WORK, setOf("бетон"))!!.on(TableCol.ZAH))
        // Новая строка при отборе не прячется.
        val nr = BoardRec(t.id, 99.0).apply { zah = "11"; work = "Бетон" }
        b.recs += nr
        t.filter = f.including(b, nr)
        assertTrue(IdTables.rowsOf(b, t).contains(nr))
        // Ничего не подошло — объёмы пустые, но блок на месте.
        t.filter = RowFilter().with(TableCol.ZAH, setOf("99"))
        val l0 = TableDraw().layout(b, t)
        assertEquals(0, l0.rows.size)
        assertNotNull(l0.vol)
        assertTrue(l0.volFilter.startsWith("по отбору:"))
    }

    /**
     * Отбор и кнопки «▾» размеров таблицы не меняют (0.27.4, Владимир после 0.27.3: «открыл доску — все таблицы посъезжали,
     * наехали на текст»): та же ширина и высота, лист плана на том же месте, столбец по названию — как до кнопок.
     */
    @Test
    fun filterAndButtonsKeepTableSize() {
        val b = sample()
        val t = b.tables[0]
        // Лист плана под таблицей и длинное примечание у строки, которая скроется отбором.
        val bmp = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val (file, pw, ph) = BoardImages.importBitmap(RuntimeEnvironment.getApplication(), bmp)
        t.sheets += PlanSheet(file, pw, ph)
        b.recs.last().note = "Длинное примечание у последней захватки, которое задаёт ширину столбца"
        val draw = TableDraw()
        fun size(): FloatArray {
            val l = draw.layout(b, t)
            val r = draw.sheetRect(b, t, RectF())!!
            return floatArrayOf(l.w, l.h, l.volTop, r.left, r.top, r.width(), r.height()) + l.colX
        }
        val base = size()
        for (f in listOf(
            RowFilter().with(TableCol.ZAH, setOf("1", "2", "3", "4")),
            RowFilter().with(TableCol.ZAH, setOf("1"))!!.with(TableCol.WORK, setOf("бетон")),
            RowFilter().with(TableCol.ZAH, setOf("99")), // ничего не подошло
            RowFilter(text = "бетон 3"),
        )) {
            t.filter = f
            val now = size()
            for (i in base.indices) assertEquals("отбор не меняет размеров (${i})", base[i], now[i], 0.01f)
        }
        // Столбец, ширину которого задаёт название («Отдал заказчику»), — ровно по названию, без места под кнопку.
        t.filter = null
        val l = draw.layout(b, t)
        val ci = l.cols.indexOfFirst { it.k == TableCol.DCUST }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 100f; isFakeBoldText = true }
        val expected = paint.measureText(l.cols[ci].title()) * TableDraw.HEAD_FONT / 100f * 1.06f + 2f + 2 * TableDraw.PAD
        assertEquals(expected, l.colX[ci + 1] - l.colX[ci], 0.01f)
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

    private fun save(bmp: Bitmap, name: String) {
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(v: View, name: String, popup: View? = null, px: Float = 0f, py: Float = 0f) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        v.draw(c)
        // Окно отбора — поверх доски, у шапки столбца (как на планшете).
        if (popup != null) {
            val w = (300 * v.resources.displayMetrics.density).toInt()
            popup.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(v.height, View.MeasureSpec.AT_MOST))
            popup.layout(0, 0, popup.measuredWidth, popup.measuredHeight)
            popup.jumpDrawablesToCurrentState() // галочки — сразу в конечном виде (без анимации)
            c.save(); c.translate(px.coerceIn(0f, (v.width - w).toFloat()), py.coerceIn(0f, (v.height - popup.measuredHeight).toFloat().coerceAtLeast(0f))); popup.draw(c); c.restore()
        }
        save(bmp, name)
    }

    private class Screen(val a: BoardActivity, val view: BoardView) {
        val b get() = a.board()
        val t get() = b.tables[0]
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        /** Середина шапки столбца на экране и её низ-лево (куда встаёт окно). */
        fun head(col: String): FloatArray {
            val l = view.tableDraw.layout(b, t)
            val ci = l.cols.indexOfFirst { it.k == col }
            val x = t.x + (l.colX[ci] + l.colX[ci + 1]) / 2f * t.scale
            val y = t.y + (view.tableDraw.rowsTop(l) - l.headH / 2f) * t.scale
            return floatArrayOf(sx(x), sy(y), sx(t.x + l.colX[ci] * t.scale), sy(t.y + view.tableDraw.rowsTop(l) * t.scale))
        }
        fun pop(): View = a.tableUi.popup!!.contentView
    }

    private fun open(): Screen {
        val b0 = sample()
        BoardStore(ctx).save(b0)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        val s = Screen(a, view)
        val tb = view.tableDraw.bounds(s.b, s.t, RectF())
        view.fitRect(tb.left - 40f, tb.top - 40f, tb.right + 40f, tb.top + 900f)
        idle(); pause(800)
        return s
    }

    @Test
    fun tapColumnHeaderChooseZahvatkiThenWork() {
        val s = open()
        shot(s.view, "table-filter-before.png")
        // «▾» у «Захватки»: окно у шапки — все значения отмечены, «(Выделить всё)» тоже.
        val h = s.head(TableCol.ZAH)
        tap(s.view, h[0], h[1])
        val p = s.pop()
        assertTrue(p.findViewWithTag<CheckBox>("flt_all").isChecked)
        assertEquals("10", p.findViewWithTag<CheckBox>("flt_val_9").text.toString()) // 1, 2 … 10, не 1, 10, 2
        // Снять всё → отметить 1–4 → ОК.
        p.findViewWithTag<CheckBox>("flt_all").isChecked = false
        assertTrue((0 until 10).none { p.findViewWithTag<CheckBox>("flt_val_$it").isChecked })
        for (i in 0 until 4) p.findViewWithTag<CheckBox>("flt_val_$i").isChecked = true
        assertFalse(p.findViewWithTag<CheckBox>("flt_all").isChecked)
        shot(s.view, "table-filter-popup.png", p, h[2], h[3])
        p.findViewWithTag<View>("flt_ok").performClick()
        idle(); pause(300)
        assertEquals(setOf("1", "2", "3", "4"), s.t.filter!!.cols[TableCol.ZAH])
        val l = s.view.tableDraw.layout(s.b, s.t)
        assertEquals(8, l.rows.size)
        assertEquals(100.0, l.vol!!.first { it.work == "Бетон" && it.part == "w" }.fact, 1e-6)
        shot(s.view, "table-filter-zah.png")

        // «▾» у «Вида работ»: поиск «бет» — найдено только «Бетон», ОК — только бетон захваток 1–4.
        val hw = s.head(TableCol.WORK)
        tap(s.view, hw[0], hw[1])
        val p2 = s.pop()
        p2.findViewWithTag<EditText>("flt_search").setText("бет")
        idle()
        val vals = (0 until 2).map { p2.findViewWithTag<CheckBox>("flt_val_$it") }
        assertEquals(1, vals.count { (it.parent as View).visibility == View.VISIBLE })
        p2.findViewWithTag<View>("flt_ok").performClick()
        idle(); pause(300)
        assertEquals(4, s.view.tableDraw.layout(s.b, s.t).rows.size)
        assertEquals(setOf("бетон"), s.t.filter!!.cols[TableCol.WORK])
        shot(s.view, "table-filter-two.png")

        // Правка и «Отменить» — отбор остаётся.
        s.a.pushUndo(); s.b.recs.first().note = "x"; s.a.changed()
        s.a.undo(); idle()
        assertEquals(2, s.t.filter!!.cols.size)

        // «Снять» в окне столбца — снят только он.
        tap(s.view, hw[0], hw[1])
        s.pop().findViewWithTag<View>("flt_clear").performClick(); idle()
        assertEquals(setOf(TableCol.ZAH), s.t.filter!!.cols.keys)
        // «✕ снять» в шапке таблицы — всё.
        val l2 = s.view.tableDraw.layout(s.b, s.t)
        tap(s.view, s.sx(s.t.x + (l2.w - 30f) * s.t.scale), s.sy(s.t.y + TableDraw.TITLE_H / 2f * s.t.scale))
        assertNull(s.t.filter)
        assertEquals(550.0, s.view.tableDraw.layout(s.b, s.t).vol!!.first { it.work == "Бетон" && it.part == "w" }.fact, 1e-6)
    }

    @Test
    fun newZahvatkaStaysVisibleWhileFiltered() {
        val s = open()
        s.t.filter = RowFilter().with(TableCol.ZAH, setOf("9", "10"))
        // «+ следующая захватка» — 11; при отборе она добавляется к отобранным, а не прячется.
        s.a.tableUi.addForm(s.t, next = true)
        val d = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("11", d.window!!.decorView.findViewWithTag<EditText>("add_zah").text.toString())
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(setOf("9", "10", "11"), s.t.filter!!.cols[TableCol.ZAH])
        assertTrue(IdTables.rowsOf(s.b, s.t).any { it.zah == "11" })
    }
}
