package party.shulya.freewrite

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * PDF на доску (0.12). В Robolectric нет PdfRenderer — подставляем свой «PDF» с нарисованными
 * листами (BoardPdf.opener); остальное — как на планшете: кнопка, выбор листов, раскладка на доске,
 * «Поделиться → FreeWrite» из Telegram.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** «PDF» из n листов A3 (в пунктах — маленькие, чтобы проверки шли быстро). */
    class FakePdf(val n: Int, val wPt: Int = 420, val hPt: Int = 297) : BoardPdf.Pages {
        val rendered = ArrayList<Pair<Int, Int>>() // лист → ширина картинки
        var closed = false
        override val count get() = n
        override fun size(page: Int) = wPt to hPt
        override fun render(page: Int, w: Int, h: Int): Bitmap {
            synchronized(rendered) { rendered += page to w }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(31, 79, 209); style = Paint.Style.STROKE; strokeWidth = w / 120f }
            c.drawRect(w * 0.05f, h * 0.05f, w * 0.95f, h * 0.95f, p)
            p.style = Paint.Style.FILL; p.textSize = h / 5f
            c.drawText("Лист ${page + 1}", w * 0.1f, h * 0.55f, p)
            return bmp
        }
        override fun close() { closed = true }
    }

    private var fake: FakePdf? = null

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    @After
    fun tearDown() {
        BoardPdf.opener = { error("PdfRenderer в проверках недоступен") }
    }

    private fun pdfFile(name: String = "Проект.pdf"): File =
        File(ctx.cacheDir, name).apply { writeText("%PDF-1.7\n% проверочный файл\n") }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!cond() && System.currentTimeMillis() < end) { idle(); Thread.sleep(15) }
        idle()
        assertTrue("не дождался: $what", cond())
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun incomingEmpty() = BoardPdf.incomingDir(ctx).listFiles().orEmpty().isEmpty()

    @Test
    fun pixelSizeAndWords() {
        // A1 (2384×1684 пт) — упирается в предел, A4 — ~200 точек на дюйм, маленький лист — не меньше 2048.
        assertEquals(6144 to 4340, BoardPdf.pixelSize(2384, 1684, 6144))
        assertEquals(4096 to 2893, BoardPdf.pixelSize(2384, 1684, 4096))
        assertEquals(1653 to 2339, BoardPdf.pixelSize(595, 842, 6144))
        assertEquals(2048, BoardPdf.pixelSize(200, 100, 6144).first)
        assertEquals("1 лист", BoardPdf.sheets(1))
        assertEquals("3 листа", BoardPdf.sheets(3))
        assertEquals("12 листов", BoardPdf.sheets(12))
        assertEquals("21 лист", BoardPdf.sheets(21))
        assertEquals("81 предмет", Ui.plural(81, "предмет", "предмета", "предметов"))
        assertEquals(BoardPdf.Kind.PDF, BoardPdf.kindOf(pdfFile()))
        assertEquals(BoardPdf.Kind.OTHER, BoardPdf.kindOf(File(ctx.cacheDir, "x.txt").apply { writeText("просто текст") }))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun boardInsertsChosenPdfSheets() {
        val f = FakePdf(12).also { fake = it }
        BoardPdf.opener = { f }
        val b0 = Board.create("Секция 3")
        BoardStore(ctx).save(b0)
        val controller = Robolectric.buildActivity(BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup()
        val a = controller.get()
        idle()
        val root = a.window.decorView
        val sa = shadowOf(a)

        // «Картинка» → «PDF — листы проекта» → выбор файла.
        root.findViewWithTag<View>("board_tool_image").performClick()
        idle()
        val menu = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(2, menu.listView.adapter.count)
        assertTrue(menu.listView.adapter.getItem(1).toString().startsWith("PDF"))
        menu.listView.performItemClick(null, 1, 1L)
        idle()
        val started = sa.nextStartedActivityForResult
        assertEquals(Intent.ACTION_CHOOSER, started.intent.action)
        @Suppress("DEPRECATION")
        assertEquals("application/pdf", started.intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.type)
        sa.receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(pdfFile())))

        // Окно выбора листов: 12 листов — по умолчанию ничего не выбрано.
        waitUntil("окно выбора листов") { ShadowAlertDialog.getLatestAlertDialog()?.window?.decorView?.findViewWithTag<View>("pdf_page_0") != null }
        val dlg = ShadowAlertDialog.getLatestAlertDialog()
        val dr = dlg.window!!.decorView
        val ok = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        assertTrue("12 листов в заголовке", shadowOf(dlg).title.toString().contains("12 листов"))
        assertTrue("пока ничего не выбрано — «Положить» неактивна", !ok.isEnabled)
        for (p in listOf(0, 2, 5)) dr.findViewWithTag<View>("pdf_page_$p").performClick()
        assertEquals("Положить (3)", ok.text.toString())
        waitUntil("миниатюры") { synchronized(f.rendered) { f.rendered.size } >= 12 }
        shot(dr.rootView, "pdf_pages.png")
        ok.performClick()

        // Листы нарисованы и легли на доску рядами, по порядку.
        val board = { field(a, "board") as Board }
        waitUntil("листы на доске") { board().images.size == 3 }
        val big = synchronized(f.rendered) { f.rendered.filter { it.second >= 2048 } }
        assertEquals(listOf(0, 2, 5), big.map { it.first })
        val imgs = board().images
        assertTrue("первый лист слева от второго", imgs[0].x < imgs[1].x && imgs[0].y == imgs[1].y)
        assertTrue("третий — ниже", imgs[2].y > imgs[0].y)
        assertTrue("картинка листа — во всю ширину чертежа", imgs[0].pxW >= 2048)
        for (im in imgs) assertTrue(File(File(ctx.filesDir, "note_images"), im.file).isFile)
        waitUntil("PDF закрыт") { f.closed }
        assertTrue("копия PDF удалена", incomingEmpty())
        a.findViewById<View>(android.R.id.content).findViewWithTag<View>("sel_done")?.performClick()
        find(root)?.fitAll()
        repeat(60) { idle(); Thread.sleep(25) } // картинки листов подгружаются в фоне
        shot(root, "pdf_board.png")
        controller.pause().stop().destroy()
    }

    private fun find(root: View): BoardView? {
        if (root is BoardView) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun passwordPdfExplainsWhatToDo() {
        BoardPdf.opener = { throw SecurityException("password required") }
        val b0 = Board.create("Секция 3")
        BoardStore(ctx).save(b0)
        val a = Robolectric.buildActivity(BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val p = field(a, "pdf") as PdfInsert
        val copy = File(BoardPdf.incomingDir(ctx), "locked.pdf").apply { writeText("%PDF-1.7") }
        p.start(copy, "Закрытый.pdf")
        waitUntil("сообщение") { ShadowAlertDialog.getLatestAlertDialog()?.let { shadowOf(it).message?.toString()?.contains("паролем") } == true }
        assertTrue("копия удалена", !copy.exists())
        assertTrue("можно вставлять снова", !p.busy)
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    fun shareFromTelegramPutsPdfOnChosenBoard() {
        val f = FakePdf(1).also { fake = it }
        BoardPdf.opener = { f }
        val store = BoardStore(ctx)
        val target = Board.create("Секция 3").also { store.save(it) }
        Board.create("Корпус 1").also { store.save(it) }

        // Telegram → «Поделиться» → «На доску FreeWrite».
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(pdfFile("План 3 этажа.pdf")))
        val imp = Robolectric.buildActivity(ImportActivity::class.java, send).setup().get()
        val root = imp.window.decorView
        val msg = root.findViewWithTag<android.widget.TextView>("import_message")
        waitUntil("выбор доски") { msg.text.contains("на какую доску") }
        assertTrue(msg.text.toString(), msg.text.contains("План 3 этажа.pdf"))
        val newRow = root.findViewWithTag<View>("insert_new")
        assertTrue("предложена новая доска с именем файла", (newRow as android.view.ViewGroup).getChildAt(0).let { it as android.widget.TextView }.text.contains("«План 3 этажа»"))
        shot(root, "pdf_share_choose.png")
        val rows = (0 until 2).map { root.findViewWithTag<android.view.ViewGroup>("insert_board_$it") }
        val row = rows.first { (it.getChildAt(0) as android.widget.TextView).text == "Секция 3" }
        row.performClick()

        // Открывается выбранная доска с присланным файлом.
        val si = shadowOf(imp)
        var boardIntent: Intent? = null
        while (true) {
            val i = si.nextStartedActivity ?: break
            if (i.component?.className == BoardActivity::class.java.name) boardIntent = i
        }
        boardIntent!!
        assertEquals(target.id, boardIntent.getStringExtra(BoardActivity.EXTRA_ID))
        val a = Robolectric.buildActivity(BoardActivity::class.java, boardIntent).setup().get()
        idle()
        val board = { field(a, "board") as Board }
        waitUntil("лист на доске") { board().images.size == 1 }
        assertTrue("PDF закрыт", f.closed)
        assertTrue("копия удалена", incomingEmpty())
        shot(a.window.decorView, "pdf_share_board.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    fun shareImagesFromGalleryToNewBoard() {
        val pics = (1..2).map { k ->
            File(ctx.cacheDir, "photo$k.jpg").apply {
                outputStream().use { Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40 * k, 120, 90)) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(pics.map { Uri.fromFile(it) }))
        val imp = Robolectric.buildActivity(ImportActivity::class.java, send).setup().get()
        val root = imp.window.decorView
        val msg = root.findViewWithTag<android.widget.TextView>("import_message")
        waitUntil("выбор доски") { msg.text.contains("на какую доску") }
        assertTrue(msg.text.toString(), msg.text.startsWith("Картинки (2)"))
        root.findViewWithTag<View>("insert_new").performClick()
        val si = shadowOf(imp)
        var boardIntent: Intent? = null
        while (true) {
            val i = si.nextStartedActivity ?: break
            if (i.component?.className == BoardActivity::class.java.name) boardIntent = i
        }
        val a = Robolectric.buildActivity(BoardActivity::class.java, boardIntent!!).setup().get()
        idle()
        val board = { field(a, "board") as Board }
        waitUntil("картинки на доске") { board().images.size == 2 }
        assertTrue(BoardStore(ctx).list().any { it.title.startsWith("Доска ") })
        assertTrue("копии удалены", incomingEmpty())
    }
}
