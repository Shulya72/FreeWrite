package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Импорт доски MyScript Nebo (FW-007). Файл Nebo собирается здесь же по описанию формата
 * (`docs/NEBO-ФОРМАТ.md`): сжатый и обычный штрих, выровненная линия со стрелкой, сдвиг группы
 * штрихов, стёртый хвост штриха, заглушка картинки и картинка со своей матрицей.
 *
 * Проверка на настоящей доске Владимира — [realNeboBoard], только на компьютере разработчика:
 * `NEBO_FILE=/путь/к/доске.nebo ./gradlew testDebugUnitTest --tests '*NeboTest*'` (файл в репозиторий не кладём).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeboTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    // ------------------------------------------------------------ сборка файла Nebo

    /** Ссылка на строку из таблицы (тип 03), в отличие от обычной строки (тип 01). */
    class Ref(val s: String)

    /** Число, записанное битами double в u64 (тип 05) — так Nebo хранит матрицы. */
    class Bits(val v: Double)

    class E(val name: String, val attrs: List<Pair<String, Any>> = emptyList(), val kids: List<E> = emptyList())

    private class Out {
        val bos = ByteArrayOutputStream()
        fun u8(v: Int) = bos.write(v)
        fun bytes(b: ByteArray) = bos.write(b)
        private fun le(n: Int, f: (ByteBuffer) -> Unit) = bytes(ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).also(f).array())
        fun u16(v: Int) = le(2) { it.putShort(v.toShort()) }
        fun u32(v: Int) = le(4) { it.putInt(v) }
        fun u64(v: Long) = le(8) { it.putLong(v) }
        fun f32(v: Float) = le(4) { it.putFloat(v) }
        fun f64(v: Double) = le(8) { it.putDouble(v) }
        fun str(s: String) { val b = s.toByteArray(); u32(b.size); bytes(b) }
        fun range(s1: Int, s2: Int, p1: Int = 0, p2: Int? = null) {
            u32((p1 shl 16) or 3); u32(s1)
            if (p2 == null) { u16(0xFF05); u16(0xFFFF) } else { u16(1); u16(p2) }
            u32(s2)
        }
        fun array(): ByteArray = bos.toByteArray()
    }

    private fun bdom(root: E): ByteArray {
        val strs = LinkedHashMap<String, Int>()
        fun idx(s: String) = strs.getOrPut(s) { strs.size }
        fun collect(e: E) {
            idx(e.name)
            for ((k, v) in e.attrs) { idx(k); if (v is Ref) idx(v.s) }
            e.kids.forEach(::collect)
        }
        collect(root)
        val o = Out()
        o.bytes("BDOM".toByteArray()); o.u16(1)
        for (s in strs.keys) o.str(s)
        o.u32(0xFF)
        fun write(e: E) {
            o.u8(3); o.u32(strs[e.name]!!); o.u32(0xFF)
            for ((k, v) in e.attrs) {
                o.u8(0); o.u32(strs[k]!!)
                when (v) {
                    is String -> { o.u8(1); o.str(v) }
                    is Ref -> { o.u8(3); o.u32(strs[v.s]!!) }
                    is Int -> { o.u8(7); o.u32(v) }
                    is Float -> { o.u8(0x0A); o.f32(v) }
                    is Bits -> { o.u8(5); o.u64(java.lang.Double.doubleToRawLongBits(v.v)) }
                    else -> error("тип $v")
                }
            }
            o.u8(0xFF)
            e.kids.forEach(::write)
            o.u8(0xFF)
        }
        write(root)
        return o.array()
    }

    private fun bink(): ByteArray {
        val o = Out()
        o.bytes("BINK".toByteArray()); o.u16(0x0500); o.u32(0); o.u32(0) // форматов каналов — 0
        o.u32(6) // слотов
        o.u32(-1) // 0: удалённый штрих
        // 1: сжатый — начало (10; 20), разности по 0,002 мм: (+1; +1), (+1; 0), (+1; −1).
        o.u32(0x80000000.toInt()); o.u64(1L); o.f32(10f); o.f32(20f); o.u16(2781); o.u32(0x0C49); o.u32(4)
        for (d in intArrayOf(0, 500, 500, 500)) o.u16(d)
        for (d in intArrayOf(0, 500, 0, -500)) o.u16(d)
        repeat(4) { o.u8(128) }
        // 2: как есть — (0; 0), (5; 0), (5; 5); сдвинут на (100; 50), перо 0.70.
        o.u32(0); o.u64(2L); o.u32(3); o.u32(3)
        for (v in floatArrayOf(0f, 0f, 5f, 0f, 5f, 5f)) o.f32(v)
        repeat(3) { o.f32(0.5f) }
        repeat(3) { o.u32(it) }
        // 3: выровненная линия (0; 0)–(10; 0), матрица: ×2 и сдвиг (20; 30) → (20; 30)–(40; 30), стрелка на конце.
        o.u32(1); o.u64(3L); o.u32(3111); o.u32(0); o.u32(0)
        for (v in floatArrayOf(0f, 0f, 10f, 0f)) o.f32(v)
        o.u32(0)
        // 4: сжатый из 10 точек по 1 мм вправо от (0; 100); видны только первые 5 (хвост стёрт).
        o.u32(0x80000000.toInt()); o.u64(4L); o.f32(0f); o.f32(100f); o.u16(2781); o.u32(0x0C49); o.u32(10)
        repeat(10) { o.u16(if (it == 0) 0 else 500) }
        repeat(10) { o.u16(0) }
        repeat(10) { o.u8(128) }
        // 5: рамка-заглушка картинки (рисовать не надо).
        o.u32(0); o.u64(5L); o.u32(3); o.u32(2)
        for (v in floatArrayOf(-200f, -100f, 100f, 50f)) o.f32(v)
        repeat(2) { o.f32(0.5f) }
        repeat(2) { o.u32(it) }
        // Сдвиги групп штрихов.
        o.u32(1); o.range(2, 2); for (v in doubleArrayOf(1.0, 0.0, 100.0, 0.0, 1.0, 50.0)) o.f64(v)
        o.u32(1); o.range(3, 3); for (v in doubleArrayOf(2.0, 0.0, 20.0, 0.0, 2.0, 30.0)) o.f64(v)
        o.u32(0)
        // Метки.
        class T(val name: String, val id: Int, val payload: String = "", val ranges: Out.() -> Int)
        val tags = listOf(
            T("LAYOUT_STROKES", 81) { range(1, 3); range(4, 4, p2 = 5); range(5, 5); 3 },
            T(".STYLE", 100, "color: #ff1a40ff;") { range(1, 1); 1 },
            T("pen-070", 101) { range(2, 2); 1 },
            T("PRIMITIVE", 102) { range(3, 3); 1 },
            T("imageFakeContent", 103) { range(5, 5); 1 },
        )
        o.u32(tags.size); o.u8(0)
        for (t in tags) {
            o.str(t.name)
            val rs = Out(); val n = t.ranges(rs)
            o.u32(n); o.bytes(rs.array())
            o.str(t.payload)
            o.u32(1); o.u32(t.id); o.u32(0)
        }
        return o.array()
    }

    private fun page(): ByteArray {
        val m = listOf("a" to 1.0, "b" to 0.0, "c" to -200.0, "d" to 0.0, "e" to 1.0, "f" to -100.0)
        return bdom(
            E("page", kids = listOf(
                E("layout", kids = listOf(
                    E("transforms", kids = listOf(E("transform", listOf<Pair<String, Any>>("id" to "tr1") + m.map { it.first to Bits(it.second) }))),
                    E("layers", kids = listOf(E("layer", kids = listOf(E("group", listOf("id" to "RootBox"), listOf(
                        E("group", listOf("id" to "ActiveBlock"), listOf(
                            E("object", listOf("url" to "img.jpg", "x" to 0f, "y" to 0f, "width" to 100f, "height" to 50f, "transform" to Ref("tr1"))),
                            E("strokes", listOf("tag" to 81)),
                        )),
                    )))))),
                )),
                E("content", kids = listOf(E("shapeField", listOf("id" to "Shape"), listOf(
                    E("decoratedLine", listOf("x1" to 20f, "y1" to 30f, "x2" to 40f, "y2" to 30f, "p1Decoration" to "none", "p2Decoration" to "arrow-head", "tag" to 102)),
                )))),
            )),
        )
    }

    private fun neboFile(name: String = "План ИГС.nebo"): File {
        val f = File(ctx.cacheDir, name)
        val jpg = ByteArrayOutputStream().also { Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 220, 240)) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("meta.json", "{\"Application\":\"Nebo\"}".toByteArray())
            put("rel.json", "{\"pages\":{\"pg1\":{\"version\":5}}}".toByteArray())
            put("pages/pg1/meta.json", "{\"pageTitle\":\"План ИГС\"}".toByteArray())
            put("pages/pg1/page.bdom", page())
            put("pages/pg1/ink.bink", bink())
            put("objects/img.jpg", jpg)
        }
        return f
    }

    private fun near(exp: FloatArray, got: FloatArray, what: String) {
        assertEquals("$what: число координат (${got.joinToString()})", exp.size, got.size)
        for (i in exp.indices) assertTrue("$what[$i]: ждали ${exp[i]}, вышло ${got[i]}", abs(exp[i] - got[i]) < 1e-3f)
    }

    // ------------------------------------------------------------ проверки

    @Test
    fun readsStrokesShapesAndImages() {
        val f = neboFile()
        assertTrue(NeboImport.isNebo(f))
        assertTrue("файл доски FreeWrite — не Nebo", !NeboImport.isNebo(File(ctx.cacheDir, "x.zip").apply {
            ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry("board.json")); it.write("{}".toByteArray()); it.closeEntry() }
        }))
        val pages = NeboImport.read(f)
        assertEquals(1, pages.size)
        val p = pages[0]
        assertEquals("План ИГС", p.title)

        // Картинка: прямоугольник через матрицу.
        assertEquals(1, p.images.size)
        val img = p.images[0]
        assertEquals("objects/img.jpg", img.entry)
        near(floatArrayOf(-200f, -100f, 100f, 50f), floatArrayOf(img.x, img.y, img.w, img.h), "картинка")

        // Штрихи по порядку: сжатый, сдвинутый, линия + стрелка, обрезанный; заглушки картинки нет.
        assertEquals(p.strokes.joinToString { it.pts.joinToString(prefix = "[", postfix = "]") }, 5, p.strokes.size)
        val (s1, s2, line, head, s4) = p.strokes
        near(floatArrayOf(10f, 20f, 11f, 21f, 12f, 21f, 13f, 20f), s1.pts, "сжатый штрих")
        assertEquals(0xFFFF1A40.toInt(), s1.color)
        assertEquals(0.625f, s1.widthMm, 1e-4f)
        near(floatArrayOf(100f, 50f, 105f, 50f, 105f, 55f), s2.pts, "сдвинутый штрих")
        assertEquals(Color.BLACK, s2.color)
        assertEquals(1.25f, s2.widthMm, 1e-4f)
        near(floatArrayOf(20f, 30f, 40f, 30f), line.pts, "линия")
        val l = 0.625f * 8; val c = cos(Math.toRadians(30.0)).toFloat() * l; val s = sin(Math.toRadians(30.0)).toFloat() * l
        near(floatArrayOf(40f - c, 30f + s, 40f, 30f, 40f - c, 30f - s), head.pts, "стрелка")
        // Хвост стёрт: видны точки 0…4, лежат на одной прямой — остаются концы.
        near(floatArrayOf(0f, 100f, 4f, 100f), s4.pts, "обрезанный штрих")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun openNeboFileMakesBoard() {
        val f = neboFile()
        val view = Intent(Intent.ACTION_VIEW).setData(Uri.fromFile(f))
        val imp = Robolectric.buildActivity(ImportActivity::class.java, view).setup().get()
        val root = imp.window.decorView
        val msg = root.findViewWithTag<android.widget.TextView>("import_message")
        val end = System.currentTimeMillis() + 30_000
        while (!msg.text.contains("перенесена") && !msg.text.contains("Не удалось") && System.currentTimeMillis() < end) { idle(); Thread.sleep(15) }
        assertTrue(msg.text.toString(), msg.text.startsWith("Доска «План ИГС» перенесена из Nebo: 5 штрихов, 1 картинка"))
        root.findViewWithTag<View>("import_open").performClick()
        val si = shadowOf(imp)
        var boardIntent: Intent? = null
        while (true) {
            val i = si.nextStartedActivity ?: break
            if (i.component?.className == BoardActivity::class.java.name) boardIntent = i
        }
        assertNotNull(boardIntent)
        assertTrue("доска откроется целиком", boardIntent!!.getBooleanExtra(BoardActivity.EXTRA_FIT, false))
        val b = BoardStore(ctx).load(boardIntent.getStringExtra(BoardActivity.EXTRA_ID)!!)!!
        assertEquals("План ИГС", b.title)
        assertEquals(5, b.strokes.size)
        assertEquals(1, b.images.size)
        val U = NeboImport.U
        b.images[0].let { near(floatArrayOf(-200 * U, -100 * U, 100 * U, 50 * U), floatArrayOf(it.x, it.y, it.w, it.h), "картинка на доске") }
        b.strokes[0].let { d ->
            assertEquals(0.625f * U, d.width, 1e-4f)
            near(floatArrayOf(10 * U, 20 * U, 0.5f), d.pts.copyOfRange(0, 3), "первая точка")
        }
        val a = Robolectric.buildActivity(BoardActivity::class.java, boardIntent).setup().get()
        idle()
        shot(a.window.decorView, "nebo_board.png")
    }

    /** Настоящая доска Владимира — только если путь к ней задан (на компьютере разработчика). */
    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun realNeboBoard() {
        val path = System.getenv("NEBO_FILE")
        assumeTrue("NEBO_FILE не задан — проверка на настоящей доске пропущена", path != null && File(path).exists())
        val t0 = System.currentTimeMillis()
        val pages = NeboImport.read(File(path!!))
        val t1 = System.currentTimeMillis()
        val r = NeboImport.import(ctx, File(path))
        val t2 = System.currentTimeMillis()
        val pts = pages.sumOf { p -> p.strokes.sumOf { it.pts.size / 2 } }
        println("NEBO: ${r.message()} точек ${pts}; чтение ${t1 - t0} мс, перенос ${t2 - t1} мс")
        assertTrue("штрихов: ${r.strokes}", r.strokes > 10_000)
        assertEquals(39, r.images)
        val b = BoardStore(ctx).load(r.boardIds[0])!!
        val json = File(ctx.filesDir, "boards/${b.id}.json").length()
        println("NEBO: файл доски ${json / 1024} КБ")
        val a = Robolectric.buildActivity(BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id).putExtra(BoardActivity.EXTRA_FIT, true)).setup().get()
        idle()
        val v = a.window.decorView
        val waitEnd = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < waitEnd) { idle(); Thread.sleep(50) } // картинки грузятся в фоне
        shot(v, "nebo_real.png")
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
