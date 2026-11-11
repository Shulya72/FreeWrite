package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
 * 0.13: обрезка картинки, булавка (закреплённую не выделить и не сдвинуть) и заливка по точкам.
 * Планшет с пером, как у Владимира.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditToolsTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun afterPen() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))

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

    private fun drag(v: View, tool: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x0, y0, tool))
        for (i in 1..16) v.dispatchTouchEvent(event(d, d + i * 12L, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 16, y0 + (y1 - y0) * i / 16, tool))
        v.dispatchTouchEvent(event(d, d + 210, MotionEvent.ACTION_UP, x1, y1, tool))
        idle()
    }

    private fun tap(v: View, tool: Int, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, tool))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, x, y, tool))
        idle()
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun find(root: View): BoardView {
        fun walk(v: View): BoardView? {
            if (v is BoardView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            return null
        }
        return walk(root)!!
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    /** Доска с чертежом 400×300 (слева синяя половина, справа — оранжевая). */
    private fun boardWithDrawing(): Board {
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(31, 79, 209))
        c.drawRect(200f, 0f, 400f, 300f, Paint().apply { color = Color.rgb(212, 71, 12) })
        val img = BoardImages.importBitmap(ctx, bmp)
        val b = Board.create("Секция 3")
        b.images += BoardImage(img.first, 100f, 100f, 400f, 300f, img.second, img.third)
        b.camX = 0f; b.camY = 0f; b.camZ = 1f
        BoardStore(ctx).save(b)
        return b
    }

    private fun open(b: Board) = Robolectric.buildActivity(BoardActivity::class.java,
        Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup()

    @Test
    fun cropKeepsPictureInPlaceAndCanBeUndone() {
        val b0 = boardWithDrawing()
        val controller = open(b0)
        val a = controller.get()
        idle()
        val root = a.window.decorView
        val view = find(root)
        fun board() = field(a, "board") as Board
        val img = board().images[0]
        view.select(listOf(img))
        idle()
        root.findViewWithTag<View>("sel_crop").performClick()
        idle()
        assertTrue("режим обрезки", view.cropping === img)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("mode_bar").visibility)

        // Правый край — влево на 200 единиц доски: осталась левая (синяя) половина, на том же месте.
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, view.sx(500f), view.sy(250f), view.sx(300f), view.sy(250f))
        assertEquals(100f, img.x, 0.5f)
        assertEquals(200f, img.w, 1f)
        assertEquals(0.5f, img.cr, 0.01f)
        assertEquals("картинка целиком не изменилась", 400f, img.fullW(), 1f)
        // Верхний край — вниз на 75: видна нижняя часть.
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, view.sx(200f), view.sy(100f), view.sx(200f), view.sy(175f))
        assertEquals(175f, img.y, 0.5f)
        assertEquals(0.25f, img.ct, 0.01f)
        assertEquals(400f, img.fullW(), 1f)
        assertEquals(300f, img.fullH(), 1f)
        repeat(20) { idle(); Thread.sleep(20) }
        shot(root, "crop_mode.png")

        root.findViewWithTag<View>("crop_done").performClick()
        idle()
        assertNull(view.cropping)
        assertEquals("после «Готово» картинка выделена", listOf<Any>(img), view.selection.toList())
        repeat(20) { idle(); Thread.sleep(20) }
        shot(root, "crop_done.png")

        // Сохраняется и читается (в том числе у коллег — та же строка).
        val again = Board.fromJson(JSONObject(board().toJsonString())).images[0]
        assertEquals(0.5f, again.cr, 0.001f)
        assertEquals(0.25f, again.ct, 0.001f)
        assertEquals(200f, again.w, 0.5f)

        // «Отменить» — обрезка верхнего края отменяется.
        root.findViewWithTag<View>("board_undo").performClick()
        idle()
        val restored = board().images[0]
        assertEquals(0f, restored.ct, 0.001f)
        assertEquals(0.5f, restored.cr, 0.01f)

        // «Как было» — картинка снова целиком.
        view.select(listOf(restored))
        idle()
        root.findViewWithTag<View>("sel_crop").performClick()
        idle()
        root.findViewWithTag<View>("crop_reset").performClick()
        idle()
        val full = board().images[0]
        assertFalse(full.cropped)
        assertEquals(100f, full.x, 0.5f)
        assertEquals(400f, full.w, 0.5f)
        controller.pause().stop().destroy()
    }

    @Test
    fun pinnedPictureCannotBeSelectedOrMoved() {
        val b0 = boardWithDrawing()
        val controller = open(b0)
        val a = controller.get()
        idle()
        val root = a.window.decorView
        val view = find(root)
        fun board() = field(a, "board") as Board
        val img = board().images[0]
        view.select(listOf(img))
        idle()
        root.findViewWithTag<View>("sel_pin").performClick()
        idle()
        assertTrue(img.locked)
        assertTrue(view.selection.isEmpty())

        // Палец по чертежу — не выделяется; тащить — двигается доска, а не чертёж.
        afterPen()
        tap(view, MotionEvent.TOOL_TYPE_FINGER, view.sx(300f), view.sy(250f))
        assertTrue("закреплённую не выделить", view.selection.isEmpty())
        drag(view, MotionEvent.TOOL_TYPE_FINGER, view.sx(300f), view.sy(250f), view.sx(300f) + 120f, view.sy(250f))
        assertEquals("чертёж на месте", 100f, img.x, 0.01f)
        // Лассо вокруг — тоже не выделяется.
        root.findViewWithTag<View>("board_tool_select").performClick()
        val l = view.sx(80f) ; val t = view.sy(80f); val r = view.sx(520f); val bt = view.sy(420f)
        val d = SystemClock.uptimeMillis()
        val pts = listOf(l to t, r to t, r to bt, l to bt, l to t)
        view.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, l, t, MotionEvent.TOOL_TYPE_STYLUS))
        var k = 1L
        for (i in 1 until pts.size) for (j in 1..8) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            view.dispatchTouchEvent(event(d, d + k++ * 10, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * j / 8, y0 + (y1 - y0) * j / 8, MotionEvent.TOOL_TYPE_STYLUS))
        }
        view.dispatchTouchEvent(event(d, d + k * 10, MotionEvent.ACTION_UP, l, t, MotionEvent.TOOL_TYPE_STYLUS))
        idle()
        assertTrue("лассо не берёт закреплённую", view.selection.isEmpty())
        // Рисовать поверх — можно.
        root.findViewWithTag<View>("board_tool_pen").performClick()
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, view.sx(150f), view.sy(150f), view.sx(450f), view.sy(350f))
        assertEquals(1, board().strokes.size)
        shot(root, "pin.png")

        // Булавка: касание пальцем — «Открепить».
        afterPen()
        val dens = ctx.resources.displayMetrics.density
        tap(view, MotionEvent.TOOL_TYPE_FINGER, view.sx(500f) - 15f * dens * 1.5f, view.sy(100f) + 15f * dens * 1.5f)
        val dlg = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dlg)
        assertEquals("Картинка закреплена", shadowOf(dlg).title.toString())
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val now = board().images[0]
        assertFalse(now.locked)
        assertEquals(listOf<Any>(now), view.selection.toList())

        // Флажок сохраняется в доске (и уходит коллегам).
        now.locked = true
        assertTrue(Board.fromJson(JSONObject(board().toJsonString())).images[0].locked)
        controller.pause().stop().destroy()
    }

    @Test
    fun fillByTappingCorners() {
        val b0 = Board.create("Захватки").also { BoardStore(ctx).save(it) }
        val controller = open(b0)
        val a = controller.get()
        idle()
        val root = a.window.decorView
        val view = find(root)
        fun board() = field(a, "board") as Board
        root.findViewWithTag<View>("board_tool_fill").performClick()
        val corners = listOf(300f to 200f, 700f to 220f, 720f to 520f, 280f to 480f)
        for ((x, y) in corners.take(3)) tap(view, MotionEvent.TOOL_TYPE_STYLUS, x, y)
        assertEquals("пока не залито", 0, board().fills.size)
        assertNotNull("после трёх углов — «Залить»", root.findViewWithTag<View>("fill_done"))
        // Лишняя точка — убрать («Отменить» убирает последнюю точку).
        tap(view, MotionEvent.TOOL_TYPE_STYLUS, 900f, 600f)
        root.findViewWithTag<View>("board_undo").performClick()
        idle()
        assertEquals(6, (field(a, "fillPts") as List<*>).size)
        tap(view, MotionEvent.TOOL_TYPE_STYLUS, corners[3].first, corners[3].second)
        shot(root, "fill_points.png")
        // Касание первой точки — замкнуть и залить.
        tap(view, MotionEvent.TOOL_TYPE_STYLUS, corners[0].first + 4f, corners[0].second - 3f)
        assertEquals(1, board().fills.size)
        val f = board().fills[0]
        assertEquals("четыре угла", 8, f.pts.size)
        assertEquals(view.wx(700f), f.pts[2], 0.5f)
        assertEquals(View.GONE, root.findViewWithTag<View>("mode_bar").visibility)
        assertTrue("штрихов не осталось", board().strokes.isEmpty())
        shot(root, "fill_done.png")

        // Обвести одним движением — как раньше.
        val d = SystemClock.uptimeMillis()
        val ring = (0..40).map { i -> val ang = Math.PI * 2 * i / 40; (1000 + 120 * Math.cos(ang)).toFloat() to (400 + 90 * Math.sin(ang)).toFloat() }
        view.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, ring[0].first, ring[0].second, MotionEvent.TOOL_TYPE_STYLUS))
        for ((i, p) in ring.withIndex()) view.dispatchTouchEvent(event(d, d + i * 10L, MotionEvent.ACTION_MOVE, p.first, p.second, MotionEvent.TOOL_TYPE_STYLUS))
        view.dispatchTouchEvent(event(d, d + 500, MotionEvent.ACTION_UP, ring.last().first, ring.last().second, MotionEvent.TOOL_TYPE_STYLUS))
        idle()
        assertEquals(2, board().fills.size)
        controller.pause().stop().destroy()
    }

    @Test
    fun legendHighlightsOneStatus() {
        val b0 = Board.create("Захватки")
        val ids = b0.statuses(BoardStatus.ID)
        val cons = b0.statuses(BoardStatus.CON)
        for (i in 0 until 3) b0.stickers += BoardSticker(100f + i * 200f, 100f, ids[2].id, 1f)
        for (i in 0 until 2) b0.stickers += BoardSticker(100f + i * 200f, 400f, ids[1].id, 1f)
        b0.callouts += BoardCallout(900f, 600f, 1f, "Захватка №7", cons[2].id).apply { addLeader(1500f, 900f) }
        BoardStore(ctx).save(b0)
        val controller = open(b0)
        val a = controller.get()
        idle()
        val root = a.window.decorView
        val view = find(root)
        root.findViewWithTag<View>("board_statuses").performClick()
        idle()
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("legend").visibility)
        fun count(row: Int): String {
            val r = root.findViewWithTag<ViewGroup>("legend_row_$row")
            return (r.getChildAt(r.childCount - 1) as android.widget.TextView).text.toString()
        }
        // Строки легенды: сначала 5 статусов ИД, потом 5 конструкций.
        assertEquals("3 метки «${ids[2].name}»", "3", count(2))
        assertEquals("2", count(1))
        assertEquals("выноска — в списке конструкций", "1", count(ids.size + 2))
        root.findViewWithTag<View>("legend_row_2").performClick()
        idle()
        assertEquals(ids[2].id, view.highlight)
        root.findViewWithTag<View>("legend_show").performClick()
        idle()
        // Камера показывает все метки этого статуса.
        assertTrue(view.wx(0f) <= 100f && view.wx(view.width.toFloat()) >= 500f)
        shot(root, "legend.png")
        root.findViewWithTag<View>("legend_row_2").performClick()
        idle()
        assertNull("ещё касание — показать всё", view.highlight)
        root.findViewWithTag<View>("legend_row_1").performClick()
        root.findViewWithTag<View>("legend_close").performClick()
        idle()
        assertEquals(View.GONE, root.findViewWithTag<View>("legend").visibility)
        assertNull("легенду закрыли — подсветка снята", view.highlight)
        controller.pause().stop().destroy()
    }

    @Test
    fun sectionMovesWithContentsAndListJumps() {
        val b0 = boardWithDrawing() // чертёж 400×300 в (100, 100)
        val store = BoardStore(ctx)
        val bb = store.load(b0.id)!!
        bb.texts += BoardText(900f, 200f, 28f, "Снаружи", Ui.INK)
        store.save(bb)
        val controller = open(bb)
        val a = controller.get()
        idle()
        val root = a.window.decorView
        val view = find(root)
        fun board() = field(a, "board") as Board

        // «Раздел»: рамка пером вокруг чертежа → название.
        root.findViewWithTag<View>("board_tool_frame").performClick()
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, view.sx(60f), view.sy(60f), view.sx(560f), view.sy(460f))
        val dlg = ShadowAlertDialog.getLatestAlertDialog()
        dlg.window!!.decorView.findViewWithTag<android.widget.EditText>("frame_title").setText("Секция 3")
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, board().frames.size)
        val f = board().frames[0]
        assertEquals("Секция 3", f.title)
        assertEquals(60f, f.x, 1f)
        assertEquals(listOf<Any>(f), view.selection.toList())
        shot(root, "section.png")

        // Тянуть за ярлык пальцем — раздел переезжает вместе с чертежом; надпись снаружи — на месте.
        afterPen()
        val lab = view.frameLabelRect(f, android.graphics.RectF())
        tap(view, MotionEvent.TOOL_TYPE_FINGER, lab.centerX(), lab.centerY()) // касание выделенного — можно тянуть
        drag(view, MotionEvent.TOOL_TYPE_FINGER, lab.centerX(), lab.centerY(), lab.centerX() + 200f, lab.centerY() + 100f)
        val dx = 200f / board().camZ
        assertEquals(60f + dx, f.x, 1f)
        assertEquals("чертёж внутри уехал вместе с разделом", 100f + dx, board().images[0].x, 1f)
        assertEquals("надпись снаружи на месте", 900f, board().texts[0].x, 0.01f)

        // Сохраняется, уходит коллегам (вид «frame»), читается.
        val again = Board.fromJson(JSONObject(board().toJsonString()))
        assertEquals("Секция 3", again.frames[0].title)
        board().stamp("vlad01")
        assertTrue(Remote.pending(board()).any { it.kind == "frame" && it.id == f.id })

        // «Разделы» → переход: раздел целиком на экране.
        view.clearSelection()
        view.fitRect(2000f, 2000f, 2500f, 2400f)
        root.findViewWithTag<View>("board_sections").performClick()
        idle()
        val list = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(1, list.listView.adapter.count)
        assertTrue(list.listView.adapter.getItem(0).toString().startsWith("Секция 3"))
        list.listView.performItemClick(null, 0, 0L)
        idle()
        assertTrue("раздел на экране", view.wx(0f) <= f.x && view.wx(view.width.toFloat()) >= f.x + f.w)
        controller.pause().stop().destroy()
    }
}
