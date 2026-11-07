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
import android.widget.ScrollView
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * Проверка экранов на компьютере (Robolectric), без планшета.
 * Размер — как у MatePad Mini (2560×1600, плотность 2x). Скриншоты — в app/build/screenshots/.
 *
 * ML Kit здесь не работает, и это ожидаемо: Robolectric исполняет код на обычной Java, а ML Kit
 * собран под Android (VerifyError, библиотека libdigitalink.so — только для Android).
 * Поэтому надпись в режиме «В текст» здесь не распознаётся и остаётся рисунком — это тоже проверяем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensSmokeTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun sampleNote(): Note {
        val n = Note.create("Планёрка · секция 3")
        n.blocks.clear()
        n.blocks += Block.Para("Прораб, геодезия, подрядчик «СтройМонтаж».")
        n.blocks += Block.Task("Исполнительная съёмка фундамента, секция 3", true)
        n.blocks += Block.Task("Акты АОСР на армирование колонн 2 этажа", false)
        n.blocks += Block.Para("Следующая планёрка — понедельник, 08:30.")
        n.caret = n.blocks.size - 1
        NoteStore(ctx).save(n)
        return n
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun home_listsNotes() {
        sampleNote()
        NoteStore(ctx).save(Note.create("Звонок с заказчиком"))
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        screenshot(activity.window.decorView, "home.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun note_penDrawsFingerTicksWriteFallsBack() {
        val n = sampleNote()
        val controller = Robolectric.buildActivity(
            NoteActivity::class.java,
            Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id),
        ).setup()
        val activity = controller.get()
        idle()
        val root = activity.window.decorView
        val ink = find(root, NoteInkView::class.java)
        assertTrue("слой пера должен иметь размер", ink.width > 0 && ink.height > 0)

        // 1. Ручка: штрих пером остаётся рисунком.
        root.findViewWithTag<View>("tool_pen").performClick()
        drawStroke(ink, MotionEvent.TOOL_TYPE_STYLUS, 0.55f, 0.62f)

        // 2. Палец по галочке второй задачи: «не сдано» → «сдано».
        afterPen()
        val check = findAll(root) { it.contentDescription == "Не сдано" }.first()
        val c = IntArray(2); val o = IntArray(2)
        check.getLocationInWindow(c); ink.getLocationInWindow(o)
        tap(ink, MotionEvent.TOOL_TYPE_FINGER, (c[0] - o[0] + check.width / 2).toFloat(), (c[1] - o[1] + check.height / 2).toFloat())

        screenshot(root, "note-portrait.png")

        // 3. «В текст» без модели: надпись не теряется, а становится рисунком.
        root.findViewWithTag<View>("tool_write").performClick()
        drawStroke(ink, MotionEvent.TOOL_TYPE_STYLUS, 0.25f, 0.75f)
        drawStroke(ink, MotionEvent.TOOL_TYPE_STYLUS, 0.32f, 0.75f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))

        controller.pause()
        val saved = NoteStore(ctx).load(n.id)!!
        assertEquals("1 рисунок ручкой + 2 нераспознанных штриха", 3, saved.drawings.size)
        assertEquals("обе задачи сданы после касания пальцем", 2, saved.tasksDone)
        controller.resume()
        idle()

        // Нижняя часть листа после прокрутки.
        find(root, ScrollView::class.java).scrollTo(0, 400)
        idle()
        screenshot(root, "note-scrolled.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun note_deleteLettersAtEndAndInsideWord() {
        val n = Note.create("Буквы")
        n.blocks.clear()
        n.blocks += Block.Para("как дилаа")
        n.caret = 0
        NoteStore(ctx).save(n)
        val controller = Robolectric.buildActivity(
            NoteActivity::class.java,
            Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id),
        ).setup()
        val root = controller.get().window.decorView
        idle()
        val letter = root.findViewWithTag<View>("mini_letter")

        // Две лишние буквы в конце: «как дилаа» → «как дил».
        press(letter); press(letter)
        // Палец — между «и» и «л» в «дил», потом «буква»: «как дил» → «как дл».
        val tv = findAll(root) { it is android.widget.TextView && it.text.toString().startsWith("как д") }.first() as android.widget.TextView
        val ink = find(root, NoteInkView::class.java)
        val t = IntArray(2); val o = IntArray(2)
        tv.getLocationInWindow(t); ink.getLocationInWindow(o)
        val x = t[0] - o[0] + tv.paddingLeft + tv.layout.getPrimaryHorizontal(6)
        val y = t[1] - o[1] + tv.paddingTop + (tv.layout.getLineTop(0) + tv.layout.getLineBottom(0)) / 2f
        tap(ink, MotionEvent.TOOL_TYPE_FINGER, x, y)
        press(letter)

        controller.pause()
        assertEquals("как дл", (NoteStore(ctx).load(n.id)!!.blocks[0] as Block.Para).text)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun note_insertSpacing() {
        val n = Note.create("Пробелы")
        NoteStore(ctx).save(n)
        val activity = Robolectric.buildActivity(
            NoteActivity::class.java,
            Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id),
        ).setup().get()
        idle()
        val noteField = NoteActivity::class.java.getDeclaredField("note").apply { isAccessible = true }
        val insert = NoteActivity::class.java.getDeclaredMethod("insertAtCaret", String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun check(text: String, offset: Int, word: String, expected: String) {
            val note = noteField.get(activity) as Note
            note.blocks.clear(); note.blocks += Block.Para(text); note.caret = 0; note.caretOffset = offset
            insert.invoke(activity, word, false)
            assertEquals("«$text» + «$word»", expected, ((noteField.get(activity) as Note).blocks[0] as Block.Para).text)
        }
        check("Привет", -1, "на", "Привет на")      // обычное письмо: короткое слово через пробел
        check("Привет", -1, ",", "Привет,")          // знак препинания — без пробела
        check("дл", 1, "и", "дил")                   // курсор внутри слова: исправление буквы
        check("мир", 0, "новый", "новый мир")        // слово перед словом — пробел после
        check("", -1, "Привет", "Привет")
    }

    /** Картинка «из галереи» (по ссылке, как её отдаёт выбор файла) копируется к себе. */
    @Test
    fun importImage_fromPickedUri() {
        val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.rgb(200, 60, 40))
        val f = File(ctx.cacheDir, "picked.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val block = NoteStore(ctx).importImage(ctx, android.net.Uri.fromFile(f))
        assertTrue("картинка должна открыться", block != null)
        assertEquals(300, block!!.width)
        assertEquals(200, block.height)
        assertTrue("файл скопирован", File(File(ctx.filesDir, "note_images"), block.file).exists())
    }

    /** Пример доски: оси, надписи, фото, три метки. */
    private fun sampleBoard(): Board {
        val b = Board.create("Секция 3 · приёмка")
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            Drawing("marker", Ui.MUTED, 2f, floatArrayOf(x0, y0, 0.5f, x1, y1, 0.5f))
        for (x in listOf(80f, 280f, 480f, 680f)) b.strokes += line(x, 120f, x, 520f)
        for (y in listOf(140f, 330f, 500f)) b.strokes += line(50f, y, 710f, y)
        b.texts += BoardText(40f, 70f, 40f, "Секция 3 · приёмка работ", Ui.INK)
        b.texts += BoardText(100f, 230f, 24f, "Фундамент", Ui.INK)
        b.texts += BoardText(300f, 230f, 24f, "Колонны 2 эт.", Ui.INK)
        b.texts += BoardText(500f, 420f, 24f, "Перекрытие", Ui.INK)
        b.stickers += BoardSticker(100f, 250f, "s2", 1.2f)
        b.stickers += BoardSticker(300f, 250f, "s1", 1.2f)
        b.stickers += BoardSticker(500f, 440f, "s0", 1.2f)
        // «Фото» — картинка, нарисованная прямо в тесте.
        val bmp = Bitmap.createBitmap(640, 440, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(android.graphics.Color.rgb(157, 185, 214))
            drawRect(0f, 300f, 640f, 440f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(139, 129, 114) })
            drawRect(50f, 150f, 590f, 172f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(201, 161, 90) })
        }
        val dir = File(ctx.filesDir, "note_images").apply { mkdirs() }
        FileOutputStream(File(dir, "test-photo.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        b.images += BoardImage("test-photo.jpg", 760f, 140f, 384f, 264f, 640, 440)
        BoardStore(ctx).save(b)
        return b
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun board_penStatusPanEraseWrite() {
        val b0 = sampleBoard()
        val controller = Robolectric.buildActivity(
            BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id),
        ).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        idle()
        val view = find(root, BoardView::class.java)
        view.fitAll()
        idle()
        screenshot(root, "board-portrait.png")
        val board = { activity.board() }

        // Ручка (включена сразу): штрих остаётся рисунком.
        val strokes0 = board().strokes.size
        drawStroke(view, MotionEvent.TOOL_TYPE_STYLUS, 0.3f, 0.75f)
        assertEquals(strokes0 + 1, board().strokes.size)

        // Ластик по этому штриху — стёрт.
        root.findViewWithTag<View>("board_tool_eraser").performClick()
        drawStroke(view, MotionEvent.TOOL_TYPE_STYLUS, 0.3f, 0.75f)
        assertEquals(strokes0, board().strokes.size)

        // Статус: касание пером на пустом месте — новая метка «Не сдано».
        root.findViewWithTag<View>("board_tool_status").performClick()
        tap(view, MotionEvent.TOOL_TYPE_STYLUS, view.width * 0.5f, view.height * 0.85f)
        assertEquals(4, board().stickers.size)
        assertEquals("s0", board().stickers.last().sid)

        // Пальцем по метке — следующий статус («В работе»). (Пауза: сразу после пера палец считается ладонью.)
        afterPen()
        val s = board().stickers.last()
        val sx = (s.x + view.stickerW(s) / 2 - board().camX) * board().camZ
        val sy = (s.y + s.h / 2 - board().camY) * board().camZ
        tap(view, MotionEvent.TOOL_TYPE_FINGER, sx, sy)
        assertEquals("s1", board().stickers.last().sid)

        // Палец двигает доску.
        val camX0 = board().camX
        drawStroke(view, MotionEvent.TOOL_TYPE_FINGER, 0.6f, 0.4f)
        assertTrue("доска сдвинулась", board().camX != camX0)

        // «В текст» без модели: надпись остаётся рисунком.
        root.findViewWithTag<View>("board_tool_write").performClick()
        drawStroke(view, MotionEvent.TOOL_TYPE_STYLUS, 0.2f, 0.6f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
        assertEquals(strokes0 + 1, board().strokes.size)

        controller.pause()
        val saved = BoardStore(ctx).load(b0.id)!!
        assertEquals(4, saved.stickers.size)
        assertEquals(strokes0 + 1, saved.strokes.size)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun board_landscapeAndHomeList() {
        val b0 = sampleBoard()
        val activity = Robolectric.buildActivity(
            BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id),
        ).setup().get()
        idle()
        find(activity.window.decorView, BoardView::class.java).fitAll()
        idle()
        screenshot(activity.window.decorView, "board-landscape.png")
        sampleNote()
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        screenshot(home.window.decorView, "home-with-board.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun note_landscape() {
        val n = sampleNote()
        val activity = Robolectric.buildActivity(
            NoteActivity::class.java,
            Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id),
        ).setup().get()
        idle()
        screenshot(activity.window.decorView, "note-landscape.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun diagnostics_penDrawsFingerDoesNot() {
        val activity = Robolectric.buildActivity(DiagnosticsActivity::class.java).setup().get()
        idle()
        val canvas = find(activity.window.decorView, InkCanvasView::class.java)
        drawStroke(canvas, MotionEvent.TOOL_TYPE_STYLUS, 0.2f, 0.4f)
        assertEquals(1, canvas.strokeCount)
        drawStroke(canvas, MotionEvent.TOOL_TYPE_FINGER, 0.6f, 0.6f)
        assertEquals(1, canvas.strokeCount)
        screenshot(activity.window.decorView, "diagnostics.png")
    }

    // ------------------------------------------------------------------ фигуры и картинки (0.5.0)

    private fun openNote(n: Note) = Robolectric.buildActivity(
        NoteActivity::class.java,
        Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id),
    ).setup()

    private fun noteOf(activity: NoteActivity): Note =
        NoteActivity::class.java.getDeclaredField("note").apply { isAccessible = true }.get(activity) as Note

    /** Рамка рисунка: [l, t, r, b]. */
    private fun bounds(d: Drawing): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < d.pts.size) { l = minOf(l, d.pts[i]); t = minOf(t, d.pts[i + 1]); r = maxOf(r, d.pts[i]); b = maxOf(b, d.pts[i + 1]); i += 3 }
        return floatArrayOf(l, t, r, b)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun note_shapesToolAndBigShapeInWriting() {
        val n = Note.create("Схема")
        n.blocks.clear()
        n.blocks += Block.Para("Поставка → монтаж → приёмка")
        NoteStore(ctx).save(n)
        val controller = openNote(n)
        val activity = controller.get()
        idle()
        val root = activity.window.decorView
        val ink = find(root, NoteInkView::class.java)
        root.findViewWithTag<View>("tool_shape").performClick()

        // Прямоугольник от руки (с перехлёстом) → ровный прямоугольник.
        drawPath(ink, MotionEvent.TOOL_TYPE_STYLUS, wobbly(poly(260f, 300f, 700f, 304f, 696f, 520f, 120f, 516f, 124f, 298f, 300f, 306f)))
        var note = noteOf(activity)
        assertEquals(1, note.drawings.size)
        val rb = bounds(note.drawings[0])
        assertEquals("ровный прямоугольник: ширина", 580f, rb[2] - rb[0], 25f)
        assertEquals("все точки одной толщины", 0.5f, note.drawings[0].pts[2], 0.001f)

        // Древко, потом наконечник отдельным штрихом → одна стрелка.
        drawPath(ink, MotionEvent.TOOL_TYPE_STYLUS, wobbly(poly(760f, 420f, 1180f, 410f)))
        drawPath(ink, MotionEvent.TOOL_TYPE_STYLUS, poly(1120f, 360f, 1184f, 410f, 1118f, 462f))
        note = noteOf(activity)
        assertEquals("древко и наконечник стали одной стрелкой", 2, note.drawings.size)

        // Круг.
        val circle = (0..80).flatMap { i ->
            val t = 0.4 + (2 * Math.PI + 0.3) * i / 80
            listOf((420 + 150 * Math.cos(t)).toFloat(), (800 + 140 * Math.sin(t)).toFloat())
        }
        drawPath(ink, MotionEvent.TOOL_TYPE_STYLUS, circle.chunked(2).map { it[0] to it[1] })
        assertEquals(3, noteOf(activity).drawings.size)

        // «В текст»: крупная рамка — это не буква, а фигура (без модели распознавания тоже).
        root.findViewWithTag<View>("tool_write").performClick()
        drawPath(ink, MotionEvent.TOOL_TYPE_STYLUS, wobbly(poly(800f, 700f, 1300f, 700f, 1300f, 980f, 800f, 980f, 800f, 702f)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
        note = noteOf(activity)
        assertEquals(4, note.drawings.size)
        val wb = bounds(note.drawings[3])
        assertEquals("рамка ровная", 500f, wb[2] - wb[0], 20f)
        assertEquals(0.5f, note.drawings[3].pts[2], 0.001f)
        screenshot(root, "note-shapes.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun note_imageSelectResizeMove() {
        photo("note-photo.jpg")
        val n = Note.create("Фото с объекта")
        n.blocks.clear()
        n.blocks += Block.Para("Первая строка")
        n.blocks += Block.Image("note-photo.jpg", 640, 440)
        n.blocks += Block.Para("Вторая строка")
        n.blocks += Block.Para("Третья строка")
        n.caret = 0
        NoteStore(ctx).save(n)
        val controller = openNote(n)
        val activity = controller.get()
        idle()
        val root = activity.window.decorView
        val ink = find(root, NoteInkView::class.java)
        val img = find(root, NoteImageView::class.java)
        fun rectInInk(): android.graphics.RectF {
            val a = IntArray(2); val o = IntArray(2)
            img.getLocationInWindow(a); ink.getLocationInWindow(o)
            val r = android.graphics.RectF(img.imageRect())
            r.offset((a[0] - o[0]).toFloat(), (a[1] - o[1]).toFloat())
            return r
        }

        // Касание пальцем — картинка выбрана, появилась панель.
        val r0 = rectInInk()
        tap(ink, MotionEvent.TOOL_TYPE_FINGER, r0.centerX(), r0.centerY())
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("image_bar").visibility)
        screenshot(root, "note-image-selected.png")

        // Правый нижний уголок влево-вверх — картинка меньше, левый край на месте.
        val w0 = r0.width()
        drag(ink, MotionEvent.TOOL_TYPE_FINGER, r0.right, r0.bottom, r0.right - 300f, r0.bottom - 206f)
        idle()
        val r1 = rectInInk()
        assertTrue("стала уже: ${r1.width()} < $w0", r1.width() < w0 - 200f)
        assertEquals("левый край на месте", r0.left, r1.left, 2f)

        // Саму картинку — вправо: сдвинулась вбок.
        drag(ink, MotionEvent.TOOL_TYPE_FINGER, r1.centerX(), r1.centerY(), r1.centerX() + 250f, r1.centerY())
        idle()
        assertTrue("сдвинута вправо", noteOf(activity).blocks.filterIsInstance<Block.Image>()[0].xa > 0.2f)

        // И вниз, за «Третью строку»: встала после неё.
        val third = findAll(root) { it is android.widget.TextView && it.text.toString().startsWith("Третья") }.first()
        val t = IntArray(2); val o = IntArray(2)
        third.getLocationInWindow(t); ink.getLocationInWindow(o)
        val r2 = rectInInk()
        val targetY = (t[1] - o[1]) + third.height + 10f
        drag(ink, MotionEvent.TOOL_TYPE_FINGER, r2.centerX(), r2.centerY(), r2.centerX(), targetY + (r2.centerY() - r2.top))
        idle()
        controller.pause()
        val saved = NoteStore(ctx).load(n.id)!!
        assertTrue("картинка в конце: ${saved.blocks.map { it.javaClass.simpleName }}", saved.blocks.last() is Block.Image)
        val im = saved.blocks.last() as Block.Image
        assertTrue("ширина сохранена: ${im.wf}", im.wf in 0.05f..0.6f)
        assertTrue(im.xa > 0.2f)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun board_imageSelectResizeMoveAndShapes() {
        val b0 = sampleBoard()
        val controller = Robolectric.buildActivity(
            BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id),
        ).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        idle()
        val view = find(root, BoardView::class.java)
        view.fitAll()
        idle()
        val board = { activity.board() }
        fun sx(x: Float) = (x - board().camX) * board().camZ
        fun sy(y: Float) = (y - board().camY) * board().camZ
        val m = board().images[0]

        // Палец по картинке — выбрана.
        tap(view, MotionEvent.TOOL_TYPE_FINGER, sx(m.x + m.w / 2), sy(m.y + m.h / 2))
        assertTrue("картинка выделена", view.selection.size == 1 && view.selection[0] === board().images[0])
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("selection_bar").visibility)

        // Правый нижний уголок наружу — больше, пропорции те же, левый верхний угол на месте.
        val (x0, y0, w0, h0) = listOf(m.x, m.y, m.w, m.h)
        drag(view, MotionEvent.TOOL_TYPE_FINGER, sx(m.x + m.w), sy(m.y + m.h), sx(m.x + m.w) + 80f, sy(m.y + m.h) + 55f)
        assertTrue("стала больше: ${m.w} > $w0", m.w > w0 * 1.1f)
        assertEquals("пропорции", w0 / h0, m.w / m.h, 0.01f)
        assertEquals(x0, m.x, 0.5f)
        assertEquals(y0, m.y, 0.5f)

        // Пальцем по выбранной картинке — двигается (а не доска).
        val camX = board().camX
        drag(view, MotionEvent.TOOL_TYPE_FINGER, sx(m.x + m.w / 2), sy(m.y + m.h / 2), sx(m.x + m.w / 2) - 120f, sy(m.y + m.h / 2) + 60f)
        assertTrue("картинка сдвинулась", m.x < x0 - 10f)
        assertEquals("доска на месте", camX, board().camX, 0.01f)

        // Фигуры на доске: круг пером.
        root.findViewWithTag<View>("board_tool_shape").performClick()
        val n0 = board().strokes.size
        val circle = (0..80).map { i ->
            val t = 0.2 + (2 * Math.PI + 0.3) * i / 80
            (view.width * 0.3f + 110 * Math.cos(t)).toFloat() to (view.height * 0.8f + 100 * Math.sin(t)).toFloat()
        }
        drawPath(view, MotionEvent.TOOL_TYPE_STYLUS, circle)
        assertEquals(n0 + 1, board().strokes.size)
        val c = board().strokes.last()
        assertEquals("круг ровный: начало = конец", c.pts[0], c.pts[c.pts.size - 3], 0.5f)
        screenshot(root, "board-image-selected.png")

        controller.pause()
        val saved = BoardStore(ctx).load(b0.id)!!
        assertEquals(w0 / h0, saved.images[0].w / saved.images[0].h, 0.01f)
        assertEquals(n0 + 1, saved.strokes.size)
    }

    // ------------------------------------------------------------------ 0.6: название, цвета, выделение

    private fun field(o: Any, name: String): Any? =
        o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private fun near(expected: Int, actual: Int, tol: Int = 18): Boolean =
        Math.abs(android.graphics.Color.red(expected) - android.graphics.Color.red(actual)) <= tol &&
            Math.abs(android.graphics.Color.green(expected) - android.graphics.Color.green(actual)) <= tol &&
            Math.abs(android.graphics.Color.blue(expected) - android.graphics.Color.blue(actual)) <= tol

    private fun latestDialogRoot(): View = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun home_newNoteAndBoardAskTitle() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        fun card(title: String): View {
            var v: View = findAll(home.window.decorView) { it is android.widget.TextView && it.text.toString() == title }.first()
            while (!v.isClickable) v = v.parent as View
            return v
        }
        card("Новая запись").performClick()
        idle()
        val dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val input = dlg.window!!.decorView.findViewWithTag<android.widget.EditText>("title_input")
        assertTrue("предложено название: ${input.text}", input.text.toString().startsWith("Запись "))
        screenshot(dlg.window!!.decorView.rootView, "new-note-title.png")
        input.setText("Планёрка 8:30")
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val id = shadowOf(home).nextStartedActivity.getStringExtra(NoteActivity.EXTRA_ID)!!
        assertEquals("Планёрка 8:30", NoteStore(ctx).load(id)!!.title)

        // Доска: оставили предложенное название.
        card("Новая доска").performClick()
        idle()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val bid = shadowOf(home).nextStartedActivity.getStringExtra(BoardActivity.EXTRA_ID)!!
        assertTrue(BoardStore(ctx).load(bid)!!.title.startsWith("Доска "))
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun note_paletteAndPipette() {
        photo("pip-photo.jpg")
        val n = Note.create("Цвета")
        n.blocks.clear()
        n.blocks += Block.Para("Строка")
        n.blocks += Block.Image("pip-photo.jpg", 640, 440)
        n.blocks += Block.Para("")
        n.caret = 0
        NoteStore(ctx).save(n)
        val controller = openNote(n)
        val activity = controller.get()
        idle()
        val root = activity.window.decorView
        val ink = find(root, NoteInkView::class.java)

        // Палитра: 24 цвета; оранжевый — ручка рисует им, он первый в последних цветах.
        root.findViewWithTag<View>("tool_palette").performClick()
        idle()
        val swatches = findAll(latestDialogRoot()) { it.tag == "palette_color" }
        assertEquals(24, swatches.size)
        screenshot(latestDialogRoot().rootView, "palette.png")
        swatches[6].performClick()
        idle()
        drawStroke(ink, MotionEvent.TOOL_TYPE_STYLUS, 0.55f, 0.8f)
        assertEquals(ColorKit.PALETTE[6], noteOf(activity).drawings.last().color)
        assertEquals(ColorKit.PALETTE[6], ColorKit.recent(ctx)[0])

        // Пипетка: касание неба на фото — цвет неба.
        root.findViewWithTag<View>("tool_palette").performClick()
        idle()
        latestDialogRoot().findViewWithTag<View>("palette_pipette").performClick()
        idle()
        val img = find(root, NoteImageView::class.java)
        val a = IntArray(2); val o = IntArray(2)
        img.getLocationInWindow(a); ink.getLocationInWindow(o)
        val r = img.imageRect()
        tap(ink, MotionEvent.TOOL_TYPE_FINGER, a[0] - o[0] + r.centerX(), a[1] - o[1] + r.top + r.height() * 0.15f)
        val picked = field(activity, "penColor") as Int
        assertTrue("цвет неба: ${Integer.toHexString(picked)}", near(android.graphics.Color.rgb(157, 185, 214), picked))
        assertEquals("после пипетки — снова ручка", "PEN", field(activity, "tool").toString())
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun board_selectEditCopyLassoPipette() {
        val b0 = sampleBoard()
        val controller = Robolectric.buildActivity(
            BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id),
        ).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        idle()
        val view = find(root, BoardView::class.java)
        view.fitAll()
        idle()
        val board = { activity.board() }
        fun sx(x: Float) = (x - board().camX) * board().camZ
        fun sy(y: Float) = (y - board().camY) * board().camZ
        val z = { board().camZ }

        // Палец по надписи — выделена, на панели «Изменить».
        val t = board().texts.first { it.text == "Фундамент" }
        tap(view, MotionEvent.TOOL_TYPE_FINGER, sx(t.x + 20f), sy(t.y - t.size / 3f))
        assertTrue("надпись выделена", view.selection.size == 1 && view.selection[0] === t)
        assertTrue(root.findViewWithTag<View>("sel_edit") != null)

        // Уголок — крупнее.
        val size0 = t.size
        val bb = view.selectionBounds()!!
        val pad = 4f * 2f / z()
        drag(view, MotionEvent.TOOL_TYPE_FINGER, sx(bb.right + pad), sy(bb.bottom + pad), sx(bb.right + pad) + 160f, sy(bb.bottom + pad) + 40f)
        assertTrue("надпись крупнее: ${t.size} > $size0", t.size > size0 * 1.2f)

        // Копия: новая надпись, выделена копия.
        val texts0 = board().texts.size
        root.findViewWithTag<View>("sel_copy").performClick()
        idle()
        assertEquals(texts0 + 1, board().texts.size)
        assertTrue(view.selection[0] === board().texts.last())

        // Изменить текст копии.
        root.findViewWithTag<View>("sel_edit").performClick()
        idle()
        val edit = findAll(latestDialogRoot()) { it is android.widget.EditText }.first() as android.widget.EditText
        edit.setText("Фундамент — схема есть")
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("Фундамент — схема есть", board().texts.last().text)

        // Цвет копии — красный из палитры.
        root.findViewWithTag<View>("sel_color").performClick()
        idle()
        findAll(latestDialogRoot()) { it.tag == "palette_color" }[4].performClick()
        idle()
        assertEquals(ColorKit.PALETTE[4], board().texts.last().color)
        controller.pause()
        assertTrue("сохранено", BoardStore(ctx).load(b0.id)!!.texts.any { it.text == "Фундамент — схема есть" && it.color == ColorKit.PALETTE[4] })
        controller.resume()
        idle()

        // «Готово», потом «Выделить»: обвести пером три метки.
        root.findViewWithTag<View>("sel_done").performClick()
        idle()
        assertEquals(0, view.selection.size)
        root.findViewWithTag<View>("board_tool_select").performClick()
        val st = board().stickers
        val l = st.minOf { it.x } - 20f; val r = st.maxOf { it.x + view.stickerW(it) } + 20f
        val tp = st.minOf { it.y } - 20f; val bt = st.maxOf { it.y + it.h } + 20f
        drawPath(view, MotionEvent.TOOL_TYPE_STYLUS, poly(sx(l), sy(tp), sx(r), sy(tp), sx(r), sy(bt), sx(l), sy(bt), sx(l), sy(tp)))
        assertTrue("все метки в выделении: ${view.selection.size}", st.all { s -> view.selection.any { it === s } })
        screenshot(root, "board-selection.png")
        val selected = view.selection.size
        val total0 = board().texts.size + board().stickers.size + board().strokes.size + board().images.size
        root.findViewWithTag<View>("sel_delete").performClick()
        idle()
        assertEquals(0, board().stickers.size)
        assertEquals("удалено ровно выделенное", total0 - selected,
            board().texts.size + board().stickers.size + board().strokes.size + board().images.size)

        // Пипетка: цвет неба с фото.
        root.findViewWithTag<View>("board_tool_palette").performClick()
        idle()
        latestDialogRoot().findViewWithTag<View>("palette_pipette").performClick()
        idle()
        val m = board().images[0]
        tap(view, MotionEvent.TOOL_TYPE_STYLUS, sx(m.x + m.w / 2f), sy(m.y + m.h * 0.15f))
        val picked = field(activity, "penColor") as Int
        assertTrue("цвет неба: ${Integer.toHexString(picked)}", near(android.graphics.Color.rgb(157, 185, 214), picked))

        controller.pause()
        val saved = BoardStore(ctx).load(b0.id)!!
        assertEquals(0, saved.stickers.size)
        assertEquals(board().texts.size, saved.texts.size)
    }

    @Test
    fun shapes_curvedArrowOnBoard() {
        // Кривая стрелка через инструмент «Фигуры» — один рисунок с плавным древком.
        val pts = ArrayList<Float>()
        for (i in 0..60) {
            val a = Math.PI + Math.PI / 2 * i / 60
            pts += (400 + 250 * Math.cos(a)).toFloat(); pts += (500 + 250 * Math.sin(a)).toFloat()
        }
        // Кончик (400, 250), направление вправо; усики назад-вверх и назад-вниз.
        val head = listOf(355f, 225f, 400f, 250f, 355f, 278f)
        val shape = ShapeRecognizer.recognize(listOf(pts.toFloatArray(), head.toFloatArray()), 40f, 6f)
        assertEquals(ShapeRecognizer.Kind.ARROW, shape?.kind)
    }

    // ------------------------------------------------------------------ 0.7: статусы, заливки, выноски

    @Test
    fun board_jsonStatusesFillsCalloutsAndOldBoards() {
        // Доска до 0.7.0: метки с числом статуса, без списка статусов.
        val old = org.json.JSONObject("""{"id":"old1","title":"Старая","stickers":[{"x":1,"y":2,"status":2,"scale":1}]}""")
        val b = Board.fromJson(old)
        assertEquals(listOf("Не сдано", "В работе", "Сдано"), b.statuses.map { it.name })
        assertEquals("Сдано", b.status(b.stickers[0].sid).name)
        // Свои статусы, заливка, выноска — сохраняются и читаются обратно.
        b.statuses += BoardStatus("own", "Сдали опалубку", ColorKit.PALETTE[7])
        b.fills += BoardFill(floatArrayOf(0f, 0f, 100f, 0f, 100f, 50f, 0f, 50f), ColorKit.PALETTE[19])
        b.callouts += BoardCallout(10f, 20f, 1.5f, "Захватка №1 от 11.09.26", "own").apply { addLeader(50f, 25f); addLeader(80f, 40f) }
        val back = Board.fromJson(org.json.JSONObject(b.toJson().toString()))
        assertEquals("Сдали опалубку", back.statuses.last().name)
        assertEquals(1, back.fills.size)
        assertEquals(8, back.fills[0].pts.size)
        assertEquals("Захватка №1 от 11.09.26", back.callouts[0].title)
        assertEquals(2, back.callouts[0].leaderCount)
        assertEquals("own", back.nextStatus("Сдано".let { back.statuses.first { s -> s.name == it }.id }))
        assertEquals(1, back.countStatus("own"))
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun board_statusesFillCallout() {
        val b0 = sampleBoard()
        val controller = Robolectric.buildActivity(
            BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id),
        ).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        idle()
        val view = find(root, BoardView::class.java)
        view.fitAll()
        idle()
        val board = { activity.board() }
        fun sx(x: Float) = (x - board().camX) * board().camZ
        fun sy(y: Float) = (y - board().camY) * board().camZ
        fun positive() = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()

        // «Статусы» → три своих статуса.
        root.findViewWithTag<View>("board_statuses").performClick()
        idle()
        val statusesRoot = latestDialogRoot()
        for ((name, color) in listOf("Сдали опалубку" to 7, "Забетонировано" to 3, "Сняли опалубку" to 5)) {
            statusesRoot.findViewWithTag<View>("status_add").performClick()
            idle()
            val d = latestDialogRoot()
            d.findViewWithTag<android.widget.EditText>("status_name").setText(name)
            findAll(d) { it.tag == "status_color" }[color].performClick()
            positive()
            idle()
        }
        assertEquals(listOf("Сдали опалубку", "Забетонировано", "Сняли опалубку"), board().statuses.takeLast(3).map { it.name })
        assertEquals(6, findAll(statusesRoot) { it.tag == "status_row" }.size)
        screenshot(statusesRoot.rootView, "board-statuses.png")
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        idle()

        // «Заливка»: обвести пером стену (прямоугольник) — ровная заливка.
        root.findViewWithTag<View>("board_tool_fill").performClick()
        drawPath(view, MotionEvent.TOOL_TYPE_STYLUS, wobbly(poly(sx(100f), sy(150f), sx(260f), sy(152f), sx(258f), sy(320f), sx(102f), sy(318f), sx(100f), sy(152f))))
        assertEquals(1, board().fills.size)
        assertTrue("ровный контур: ${board().fills[0].pts.size}", board().fills[0].pts.size <= 12)

        // «Выноска»: от заливки вверх-вправо — окно: надпись и статус.
        root.findViewWithTag<View>("board_tool_callout").performClick()
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, sx(180f), sy(240f), sx(180f) + 260f, sy(240f) - 220f)
        val dlg = latestDialogRoot()
        dlg.findViewWithTag<android.widget.EditText>("callout_title").setText("Захватка №1 от 11.09.26")
        findAll(dlg) { v -> v.tag == "callout_status" && findAll(v) { it is android.widget.TextView && it.text.toString() == "Сдали опалубку" }.isNotEmpty() }
            .first().performClick()
        idle()
        positive()
        idle()
        assertEquals(1, board().callouts.size)
        val c = board().callouts[0]
        assertEquals("Захватка №1 от 11.09.26", c.title)
        assertEquals("Сдали опалубку", board().status(c.sid).name)
        assertEquals(1, c.leaderCount)

        // Касание строки статуса пальцем — следующий статус по списку.
        afterPen()
        fun tapStatus() {
            val r = view.calloutRect(c, android.graphics.RectF())
            tap(view, MotionEvent.TOOL_TYPE_FINGER, sx(r.centerX()), sy(r.top + r.height() * 0.75f))
        }
        tapStatus()
        assertEquals("Забетонировано", board().status(c.sid).name)
        tapStatus()
        assertEquals("Сняли опалубку", board().status(c.sid).name)

        // От рамки — второй указатель.
        val r = view.calloutRect(c, android.graphics.RectF())
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, sx(r.centerX()), sy(r.top + r.height() * 0.25f), sx(500f), sy(400f))
        assertEquals(2, c.leaderCount)
        screenshot(root, "board-callouts.png")

        controller.pause()
        val saved = BoardStore(ctx).load(b0.id)!!
        assertEquals(1, saved.fills.size)
        assertEquals("Сняли опалубку", saved.status(saved.callouts[0].sid).name)
        assertEquals(2, saved.callouts[0].leaderCount)
    }

    // ------------------------------------------------------------------ 0.8.1: защита от ладони

    /** Пауза после пера: через 0,7 с пальцы снова работают. */
    private fun afterPen() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))

    private fun hoverPen(view: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        view.dispatchGenericMotionEvent(e)
        idle()
    }

    /** Событие с двумя касаниями: палец (id 0) и перо (id 1). */
    private fun twoPointer(down: Long, time: Long, action: Int, fx: Float, fy: Float, px: Float, py: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = fx; y = fy; pressure = 0.5f; size = 0.1f },
            MotionEvent.PointerCoords().apply { x = px; y = py; pressure = 0.4f },
        )
        return MotionEvent.obtain(down, time, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun board_palmDoesNotMoveImage() {
        val b0 = sampleBoard()
        val controller = Robolectric.buildActivity(
            BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id),
        ).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        idle()
        val view = find(root, BoardView::class.java)
        view.fitAll()
        idle()
        val board = { activity.board() }
        fun sx(x: Float) = (x - board().camX) * board().camZ
        fun sy(y: Float) = (y - board().camY) * board().camZ
        val m = board().images[0]
        val cx = sx(m.x + m.w / 2); val cy = sy(m.y + m.h / 2)
        tap(view, MotionEvent.TOOL_TYPE_FINGER, cx, cy)
        assertTrue("картинка выделена", view.selection.singleOrNull() === m)
        val x0 = m.x; val camX0 = board().camX

        // 1. Перо над экраном — ладонь по выделенной картинке её не двигает (и доску тоже).
        hoverPen(view, cx + 200f, cy + 300f)
        drag(view, MotionEvent.TOOL_TYPE_FINGER, cx, cy, cx - 150f, cy + 80f)
        assertEquals("картинка на месте", x0, m.x, 0.01f)
        assertEquals("доска на месте", camX0, board().camX, 0.01f)
        assertTrue("выделение не сбито ладонью", view.selection.singleOrNull() === m)

        // 2. Ладонь легла раньше пера и потащила картинку, следом коснулось перо — картинка возвращается.
        afterPen()
        val down = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, cx, cy, MotionEvent.TOOL_TYPE_FINGER))
        for (i in 1..6) view.dispatchTouchEvent(event(down, down + i * 10L, MotionEvent.ACTION_MOVE, cx - i * 15f, cy, MotionEvent.TOOL_TYPE_FINGER))
        assertTrue("ладонь успела сдвинуть: ${m.x}", m.x < x0 - 10f)
        val pd = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        view.dispatchTouchEvent(twoPointer(down, down + 80, pd, cx - 90f, cy, cx + 300f, cy + 400f))
        assertEquals("перо пришло — картинка вернулась", x0, m.x, 0.01f)
        for (i in 1..8) view.dispatchTouchEvent(twoPointer(down, down + 80 + i * 8L, MotionEvent.ACTION_MOVE, cx - 90f, cy, cx + 300f + i * 10f, cy + 400f))
        val pu = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        view.dispatchTouchEvent(twoPointer(down, down + 200, pu, cx - 90f, cy, cx + 380f, cy + 400f))
        view.dispatchTouchEvent(event(down, down + 210, MotionEvent.ACTION_UP, cx - 90f, cy, MotionEvent.TOOL_TYPE_FINGER))
        idle()
        assertEquals("картинка так и стоит на месте", x0, m.x, 0.01f)

        // 3. Пером провели штрих — выделение снято, ладони нечего тащить.
        assertEquals(0, view.selection.size)
        controller.pause()
        assertEquals(x0, BoardStore(ctx).load(b0.id)!!.images[0].x, 0.01f)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun note_palmIgnoredNearPen() {
        val n = sampleNote()
        val controller = openNote(n)
        val activity = controller.get()
        idle()
        val root = activity.window.decorView
        val ink = find(root, NoteInkView::class.java)
        val check = findAll(root) { it.contentDescription == "Не сдано" }.first()
        val c = IntArray(2); val o = IntArray(2)
        check.getLocationInWindow(c); ink.getLocationInWindow(o)
        val x = (c[0] - o[0] + check.width / 2).toFloat()
        val y = (c[1] - o[1] + check.height / 2).toFloat()
        // Перо рядом — касание по галочке не считается.
        hoverPen(ink, x + 300f, y + 200f)
        tap(ink, MotionEvent.TOOL_TYPE_FINGER, x, y)
        assertEquals("ладонь при пере рядом — задача не отмечена", 1, noteOf(activity).tasksDone)
        // Перо убрали — палец снова работает.
        afterPen()
        tap(ink, MotionEvent.TOOL_TYPE_FINGER, x, y)
        assertEquals(2, noteOf(activity).tasksDone)
    }

    /** Фото для записи — нарисовано прямо в тесте. */
    private fun photo(name: String) {
        val bmp = Bitmap.createBitmap(640, 440, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(android.graphics.Color.rgb(157, 185, 214))
            drawRect(0f, 300f, 640f, 440f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(139, 129, 114) })
            drawRect(50f, 150f, 590f, 172f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(201, 161, 90) })
        }
        val dir = File(ctx.filesDir, "note_images").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    // ------------------------------------------------------------------ helpers

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Волнистый штрих от точки (fx, fy) в долях вида. */
    private fun drawStroke(view: View, tool: Int, fx: Float, fy: Float) {
        val down = SystemClock.uptimeMillis()
        val x0 = view.width * fx
        val y0 = view.height * fy
        val w = view.width * 0.08f
        val h = view.height * 0.03f
        val n = 20
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, x0, y0, tool))
        for (i in 1..n) {
            val t = i / n.toFloat()
            view.dispatchTouchEvent(
                event(down, down + i * 8L, MotionEvent.ACTION_MOVE, x0 + w * t, y0 + h * kotlin.math.sin(t * Math.PI * 3).toFloat(), tool),
            )
        }
        view.dispatchTouchEvent(event(down, down + (n + 1) * 8L, MotionEvent.ACTION_UP, x0 + w, y0, tool))
        idle()
    }

    /** Ломаная с частыми точками (как идут события пера). */
    private fun poly(vararg p: Float): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        out += p[0] to p[1]
        var i = 2
        while (i + 1 < p.size) {
            val ax = p[i - 2]; val ay = p[i - 1]; val bx = p[i]; val by = p[i + 1]
            val n = maxOf(1, (Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()) / 6).toInt())
            for (k in 1..n) out += (ax + (bx - ax) * k / n) to (ay + (by - ay) * k / n)
            i += 2
        }
        return out
    }

    /** Дрожание руки. */
    private fun wobbly(p: List<Pair<Float, Float>>): List<Pair<Float, Float>> =
        p.mapIndexed { i, (x, y) -> (x + 3f * kotlin.math.sin(i / 7f)) to (y + 3f * kotlin.math.cos(i / 5f)) }

    private fun drawPath(view: View, tool: Int, pts: List<Pair<Float, Float>>) {
        val down = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, pts[0].first, pts[0].second, tool))
        for (i in 1 until pts.size) {
            view.dispatchTouchEvent(event(down, down + i * 4L, MotionEvent.ACTION_MOVE, pts[i].first, pts[i].second, tool))
        }
        view.dispatchTouchEvent(event(down, down + pts.size * 4L, MotionEvent.ACTION_UP, pts.last().first, pts.last().second, tool))
        idle()
    }

    /** Перетащить: нажать, плавно провести, отпустить. */
    private fun drag(view: View, tool: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, x0, y0, tool))
        val n = 16
        for (i in 1..n) {
            view.dispatchTouchEvent(event(down, down + i * 12L, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n, tool))
        }
        view.dispatchTouchEvent(event(down, down + (n + 1) * 12L, MotionEvent.ACTION_UP, x1, y1, tool))
        idle()
    }

    private fun tap(view: View, tool: Int, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, x, y, tool))
        view.dispatchTouchEvent(event(down, down + 60, MotionEvent.ACTION_UP, x, y, tool))
        idle()
    }

    /** Короткое нажатие на кнопку (пальцем). */
    private fun press(view: View) {
        val down = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, view.width / 2f, view.height / 2f, MotionEvent.TOOL_TYPE_FINGER))
        view.dispatchTouchEvent(event(down, down + 50, MotionEvent.ACTION_UP, view.width / 2f, view.height / 2f, MotionEvent.TOOL_TYPE_FINGER))
        idle()
    }

    private fun event(down: Long, time: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.4f; size = 0.1f })
        val source = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun <T : View> find(root: View, type: Class<T>): T =
        type.cast(findAll(root) { type.isInstance(it) }.first())!!

    private fun findAll(root: View, pred: (View) -> Boolean): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (pred(v)) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun screenshot(view: View, name: String) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
