package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

/**
 * Обычный телефон без пера (0.11): палец работает выбранным инструментом, два пальца двигают и
 * приближают доску, начинаем с «Руки», после метки/выноски/надписи снова «Рука»; инструменты внизу.
 * Экран как у телефона: 360×780 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ev(down: Long, t: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun ev2(down: Long, t: Long, action: Int, x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 0.5f; size = 0.05f },
            MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 0.5f; size = 0.05f },
        )
        return MotionEvent.obtain(down, t, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun drag(v: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, x0, y0))
        for (i in 1..12) v.dispatchTouchEvent(ev(d, d + i * 12L, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 12, y0 + (y1 - y0) * i / 12))
        v.dispatchTouchEvent(ev(d, d + 160, MotionEvent.ACTION_UP, x1, y1))
        idle()
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, x, y))
        v.dispatchTouchEvent(ev(d, d + 60, MotionEvent.ACTION_UP, x, y))
        idle()
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T : View> find(root: View, type: Class<T>): T {
        fun walk(v: View): T? {
            if (type.isInstance(v)) return type.cast(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            return null
        }
        return walk(root)!!
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    @Test
    fun boardWorksWithFinger() {
        Profile.save(ctx, "Николай", "Иванов")
        val store = BoardStore(ctx)
        val b = Board.create("Секция 3")
        b.texts += BoardText(40f, 120f, 28f, "Захватка №1", Ui.INK)
        b.callouts += BoardCallout(60f, 200f, 1f, "Захватка №2", "s1").apply { addLeader(40f, 320f) }
        store.save(b)
        val controller = Robolectric.buildActivity(BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup()
        val a = controller.get()
        idle()
        val root = a.window.decorView
        val view = find(root, BoardView::class.java)
        fun board() = field(a, "board") as Board
        fun tool() = field(a, "tool")

        assertEquals("без пера начинаем с «Руки»", BoardActivity.Tool.HAND, tool())
        assertNotNull("инструменты внизу", root.findViewWithTag<View>("board_bottom_tools"))
        assertEquals("«Палец рисует» на телефоне не нужен", View.GONE, root.findViewWithTag<View>("board_finger_draws").visibility)

        // «Рука»: пальцем — двигать доску.
        val cx = view.width / 2f; val cy = view.height / 2f
        val camX0 = board().camX
        drag(view, cx, cy, cx - 200f, cy)
        assertNotEquals("доска сдвинулась", camX0, board().camX, 0.01f)
        assertEquals(0, board().strokes.size)

        // «Ручка»: пальцем — рисовать.
        root.findViewWithTag<View>("board_tool_pen").performClick()
        drag(view, cx - 150f, cy + 100f, cx + 150f, cy + 160f)
        assertEquals("палец нарисовал штрих", 1, board().strokes.size)

        // Лёг второй палец — двигают и приближают доску, штрих не появляется.
        val z0 = board().camZ
        val d = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, cx - 50f, cy))
        view.dispatchTouchEvent(ev(d, d + 10, MotionEvent.ACTION_MOVE, cx - 54f, cy))
        view.dispatchTouchEvent(ev2(d, d + 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx - 54f, cy, cx + 50f, cy))
        for (i in 1..10) view.dispatchTouchEvent(ev2(d, d + 20 + i * 12L, MotionEvent.ACTION_MOVE, cx - 54f - i * 15f, cy, cx + 50f + i * 15f, cy))
        view.dispatchTouchEvent(ev2(d, d + 200, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx - 204f, cy, cx + 200f, cy))
        view.dispatchTouchEvent(ev(d, d + 210, MotionEvent.ACTION_UP, cx - 204f, cy))
        idle()
        assertEquals("второй палец отменил штрих", 1, board().strokes.size)
        assertTrue("доска приблизилась", board().camZ > z0 * 1.5f)

        // «Метка»: касание пальцем — метка, потом снова «Рука».
        root.findViewWithTag<View>("board_tool_status").performClick()
        tap(view, cx, cy - 120f)
        assertEquals(1, board().stickers.size)
        assertEquals("после метки — снова «Рука»", BoardActivity.Tool.HAND, tool())

        // «Текст»: касание — клавиатура, надпись на месте касания, потом снова «Рука».
        root.findViewWithTag<View>("board_tool_text").performClick()
        tap(view, cx - 100f, cy + 250f)
        val dlg = ShadowAlertDialog.getLatestAlertDialog()
        dlg.window!!.decorView.findViewWithTag<android.widget.EditText>("text_input").setText("Сдали опалубку")
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(board().texts.any { it.text == "Сдали опалубку" })
        assertEquals(BoardActivity.Tool.HAND, tool())

        view.fitAll(); idle()
        shot(root, "phone-board.png")
        controller.pause().stop().destroy()
    }

    @Test
    fun homeFitsPhoneAndAsksName() {
        // Общая доска с длинным названием и статусами, запись с задачами — как у Владимира.
        val b = Board.create("Рабочая доска — секция 3, захватки и ИГС")
        b.sync.remoteKey = "k"; b.sync.remoteInvite = "ABCDEFGH"
        for (i in 0 until 3) b.stickers += BoardSticker(10f * i, 0f, b.statuses[2].id, 1f)
        for (i in 0 until 3) b.stickers += BoardSticker(10f * i, 40f, b.statuses[1].id, 1f)
        b.statuses[1].name = "Предъявлено заказчику"
        for (i in 0 until 75) b.texts += BoardText(0f, 80f + i * 30f, 20f, "Надпись $i", Ui.INK)
        BoardStore(ctx).save(b)
        NoteStore(ctx).save(Note.create("Планёрка на объекте"))

        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val a = controller.get()
        idle()
        // Первый запуск — «Как вас зовут?».
        val dlg = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dlg)
        val dr = dlg.window!!.decorView
        dr.findViewWithTag<android.widget.EditText>("profile_first").setText("Иван")
        dr.findViewWithTag<android.widget.EditText>("profile_last").setText("Петров")
        shot(dr, "phone-profile.png")
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("Иван Петров", Profile.name(ctx))
        val root = a.window.decorView
        // В шапке — кружок с инициалами.
        val avatar = root.findViewWithTag<ViewGroup>("home_profile")
        val ini = (0 until avatar.childCount).map { avatar.getChildAt(it) }.filterIsInstance<android.widget.TextView>().first()
        assertEquals("ИП", ini.text.toString())

        // Три плитки в ряд помещаются в ширину экрана.
        val tiles = root.findViewWithTag<ViewGroup>("home_tiles")
        assertEquals(3, tiles.childCount)
        for (i in 0 until 3) assertTrue("плитка ${i + 1} не вылезает за край", tiles.getChildAt(i).right <= tiles.width)
        assertTrue("плитки компактные", tiles.height < Ui.dp(ctx, 110f))

        // Название доски не сжато статусами: занимает почти всю ширину строки.
        val titles = ArrayList<android.widget.TextView>()
        val texts = ArrayList<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView) texts += v.text.toString()
            if (v is android.widget.TextView && v.tag == "row_title") titles += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        val boardTitle = titles.first { it.text.startsWith("Рабочая доска") }
        assertTrue("название доски широкое: ${boardTitle.width} из ${root.width}", boardTitle.width > root.width / 2)
        assertEquals("две строки: доска и запись", 2, titles.size)
        assertTrue("статусы видны: $texts", "3 предъявлено заказчику" in texts)
        assertTrue("предметы по-русски: $texts", texts.any { it == "Общая доска · 81 предмет" })
        shot(root, "phone-home.png")
        controller.pause().stop().destroy()
    }

    @Test
    fun fillPointsWithLoupeAndSnap() {
        Profile.save(ctx, "Николай", "Иванов")
        val b = Board.create("Захватки")
        b.fills += BoardFill(floatArrayOf(100f, 100f, 300f, 100f, 300f, 300f, 100f, 300f), Ui.PEN_BLUE)
        b.camX = 0f; b.camY = 0f; b.camZ = 1f
        BoardStore(ctx).save(b)
        val controller = Robolectric.buildActivity(BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup()
        val a = controller.get()
        idle()
        val root = a.window.decorView
        val view = find(root, BoardView::class.java)
        fun board() = field(a, "board") as Board
        @Suppress("UNCHECKED_CAST")
        fun pts() = field(a, "fillPts") as List<Float>
        root.findViewWithTag<View>("board_tool_fill").performClick()

        // Палец лёг рядом с углом соседней захватки и подвёлся — пока держит, видна лупа.
        val d = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, view.sx(340f), view.sy(350f)))
        for (i in 1..8) view.dispatchTouchEvent(ev(d, d + i * 15L, MotionEvent.ACTION_MOVE, view.sx(340f - i * 5f), view.sy(350f - i * 6f)))
        idle()
        shot(root, "phone-loupe.png")
        view.dispatchTouchEvent(ev(d, d + 200, MotionEvent.ACTION_UP, view.sx(300f + 12f), view.sy(302f + 10f)))
        idle()
        assertEquals("точка прилипла к углу соседней захватки", listOf(300f, 300f), pts())

        // Свободная точка — где отпустили.
        tap(view, view.sx(520f), view.sy(90f))
        assertEquals(520f, pts()[2], 0.5f)
        assertEquals(90f, pts()[3], 0.5f)
        tap(view, view.sx(520f), view.sy(320f))
        assertEquals(6, pts().size)
        // Рядом с первой точкой — прилипает к ней и замыкает: заливка готова, угол общий с соседней.
        tap(view, view.sx(306f), view.sy(294f))
        assertEquals(2, board().fills.size)
        val f = board().fills[1]
        assertEquals(6, f.pts.size)
        assertEquals(300f, f.pts[0], 0.01f)
        assertEquals(300f, f.pts[1], 0.01f)
        assertTrue("штрихов пальцем не осталось", board().strokes.isEmpty())
        controller.pause().stop().destroy()
    }
}
