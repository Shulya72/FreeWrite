package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
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

/**
 * Таблица собирается из отмеченных столбцов и порядок строк (0.32.5, Владимир: «у сетей нет захваток — участок ДК1–ДК2, колодец
 * ДК1 отдельно, работы выбираю галочками; у КПП нет ни участков, ни захваток; упорядочивать по участку или по захватке»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableFormOrderTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest() = ShadowAlertDialog.getLatestAlertDialog()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    private val pipe = listOf("Разработка траншеи", "Песчаное основание", "Монтаж трубопровода", "Защитный слой", "Обратная засыпка")
    private val well = listOf("Разработка котлована", "Монтаж колодцев")

    /** Сети: свой вид, отмечен только «Участок» (захватки и типа конструкции нет). */
    private fun seti(b: Board): BoardTable {
        val t = BoardTable(100f, 100f, 1f, "Канализация К1")
        t.kind = TableKinds.USER + "seti1"
        t.ownKind = TableKinds.Kind(t.kind, "Сети", "Наружная канализация", emptyList(), pipe + well, setOf(TableCol.CON))
        t.cols.addAll(TableCol.defaultsFor(t)); t.cols.first { it.k == TableCol.ZAH }.hidden = true
        b.tables += t
        return t
    }

    private fun rec(b: Board, t: BoardTable, o: Double, uch: String, work: String) =
        BoardRec(t.id, o).apply { this.uch = uch; this.work = work }.also { b.recs += it }

    @Test
    fun nextNumberAndRowOrderByUchastok() {
        // Следующий номер: участок между колодцами — сдвиг на один колодец; остальное — первое число +1.
        assertEquals("ДК2–ДК3", IdTables.nextAddr("ДК1–ДК2"))
        assertEquals("ДК02-ДК03", IdTables.nextAddr("ДК01-ДК02"))
        assertEquals("Уч. 2 - 3", IdTables.nextAddr("Уч. 1 - 2"))
        assertEquals("8", IdTables.nextAddr("7"))
        assertEquals("LM2", IdTables.nextAddr("LM1"))
        assertEquals("4 этаж", IdTables.nextAddr("3 этаж"))

        val b = Board.create("Сети")
        val t = seti(b)
        var o = 1.0
        // Вразнобой: сначала труба ДК2–ДК3, потом колодец ДК1, труба ДК1–ДК2 (работы задом наперёд), колодец ДК2, строка без участка.
        for (w in pipe) rec(b, t, o++, "ДК2–ДК3", w)
        for (w in well) rec(b, t, o++, "ДК1", w)
        for (w in pipe.reversed()) rec(b, t, o++, "ДК1–ДК2", w)
        for (w in well) rec(b, t, o++, "ДК2", w)
        rec(b, t, o++, "", "Обратная засыпка")
        val added = IdTables.allRows(b, t).map { it.uch + " " + it.work }
        assertEquals("ДК2–ДК3 Разработка траншеи", added.first())

        t.sort = TableCol.UCH
        val sorted = IdTables.allRows(b, t).map { it.uch + " " + it.work }
        val want = well.map { "ДК1 $it" } + pipe.map { "ДК1–ДК2 $it" } + well.map { "ДК2 $it" } + pipe.map { "ДК2–ДК3 $it" } + " Обратная засыпка"
        assertEquals("по трассе, работы — как в виде таблицы, пустые — в конце", want, sorted)
        assertEquals("по столбцу «Участок»", IdTables.sortName(t))
        assertTrue(IdTables.sortCols(t).any { it.k == TableCol.UCH })
        assertFalse("захватка не отмечена — по ней не предлагаем", IdTables.sortCols(t).any { it.k == TableCol.ZAH })

        // Порядок — в самой таблице (один для всех); пустой не пишется; «как добавляли» — снова прежний порядок.
        val t2 = IdTables.parseTable(JSONObject(IdTables.tableJson(t)))
        assertEquals(TableCol.UCH, t2.sort)
        t.sort = ""
        assertFalse(IdTables.tableJson(t).contains("\"srt\""))
        assertEquals(added, IdTables.allRows(b, t).map { it.uch + " " + it.work })

        // Ведомость — в порядке таблицы.
        t.sort = TableCol.UCH
        val v = BoardVedom(0f, 2000f, 1f, Vedoms.CUST, "З-1").also { b.vedoms += it }
        val rows = IdTables.allRows(b, t)
        for ((i, r) in rows.filter { it.uch.isNotEmpty() }.shuffled(java.util.Random(7)).withIndex()) b.vitems += BoardVItem(v.id, r.id, i.toDouble())
        assertEquals(rows.filter { it.uch.isNotEmpty() }.map { it.id }, Vedoms.items(b, v).map { it.r })
    }

    @Test
    fun formShowsOnlyChosenColumnsAndNothingIsTicked() {
        val b = Board.create("Сети")
        val t = seti(b)
        val kpp = BoardTable(100f, 1400f, 1f, "КПП").apply { kind = TableKinds.CUSTOM }
        kpp.cols.addAll(TableCol.defaultsFor(kpp)); kpp.cols.filter { it.k == TableCol.ZAH || it.k == TableCol.UCH }.forEach { it.hidden = true }
        b.tables += kpp
        assertEquals("Следующий участок", TableDraw.nextTitle(t))
        assertNull("КПП — только «+ строка»", TableDraw.nextLabel(kpp))
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val bb = a.board()
        val st = bb.tables.first { it.title == "Канализация К1" }
        val sk = bb.tables.first { it.title == "КПП" }

        // Колодец ДК1: в форме участок, захватки и типа конструкции нет, ничего не отмечено.
        a.tableUi.onTap(st, TableHit(TableHit.ADD, null, null), pen = true); idle()
        var d = latest(); var fr = d.window!!.decorView
        assertNotNull(fr.findViewWithTag<EditText>("add_uch"))
        assertNull(fr.findViewWithTag<View>("add_zah")); assertNull(fr.findViewWithTag<View>("add_con"))
        assertTrue("ничего не отмечено", ticked(fr).isEmpty())
        fr.findViewWithTag<EditText>("add_uch").setText("ДК1")
        for (w in well) pill(fr, w).performClick()
        shotDialog(d, "table-form-well.png")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(well.map { "ДК1 $it" }, IdTables.allRows(bb, st).map { it.uch + " " + it.work })

        // «+ следующий участок»: труба ДК1–ДК2 (номер вписали), работы трубы — галочками; колодец сам не прилип.
        a.tableUi.onTap(st, TableHit(TableHit.ADD_NEXT, null, null), pen = true); idle()
        d = latest(); fr = d.window!!.decorView
        assertTrue("заголовок — по участку", texts(d.window!!.decorView).any { it.startsWith("Следующий участок") })
        fr.findViewWithTag<EditText>("add_uch").setText("ДК1–ДК2")
        assertTrue(ticked(fr).isEmpty())
        for (w in pipe) pill(fr, w).performClick()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()

        // Дальше по трассе: номер подсказан (ДК2–ДК3), «Как в прошлой строке» — работы трубы одним касанием.
        a.tableUi.onTap(st, TableHit(TableHit.ADD_NEXT, null, null), pen = true); idle()
        d = latest(); fr = d.window!!.decorView
        assertEquals("ДК2–ДК3", fr.findViewWithTag<EditText>("add_uch").text.toString())
        fr.findViewWithTag<View>("add_works_last").performClick()
        assertEquals(pipe.toSet(), ticked(fr).toSet())
        shotDialog(d, "table-form-pipe.png")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        // И колодец ДК2 — отдельно.
        a.tableUi.onTap(st, TableHit(TableHit.ADD, null, null), pen = true); idle()
        d = latest(); fr = d.window!!.decorView
        fr.findViewWithTag<EditText>("add_uch").setText("ДК2")
        for (w in well) pill(fr, w).performClick()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(2 + 5 + 5 + 2, IdTables.allRows(bb, st).size)

        // «Порядок строк» → по участку: по трассе. «Отменить» — как добавляли.
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        view.select(listOf(st)); idle()
        a.window.decorView.findViewWithTag<View>("sel_sort").performClick(); idle()
        a.tableUi.sortDlg!!.window!!.decorView.findViewWithTag<View>("sort_uch").performClick(); idle()
        val stNow = a.board().tables.first { it.id == st.id }
        assertEquals(TableCol.UCH, stNow.sort)
        val order = IdTables.allRows(a.board(), stNow).map { it.uch }.distinct()
        assertEquals(listOf("ДК1", "ДК1–ДК2", "ДК2", "ДК2–ДК3"), order)
        view.fitRect(stNow.x - 40f, stNow.y - 40f, stNow.x + 2400f, stNow.y + 900f); idle()
        shot(view, "table-sorted-uch.png")
        a.undo(); idle()
        assertEquals("", a.board().tables.first { it.id == st.id }.sort)

        // КПП: ни участка, ни захватки; без работы не добавить; с работой — строка без номера.
        a.tableUi.onTap(sk, TableHit(TableHit.ADD, null, null), pen = true); idle()
        d = latest(); fr = d.window!!.decorView
        assertNull(fr.findViewWithTag<View>("add_uch")); assertNull(fr.findViewWithTag<View>("add_zah"))
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(IdTables.allRows(a.board(), sk).isEmpty())
        a.tableUi.onTap(sk, TableHit(TableHit.ADD, null, null), pen = true); idle()
        d = latest(); fr = d.window!!.decorView
        fr.findViewWithTag<EditText>("add_work_other").setText("Разработка котлована")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val k = IdTables.allRows(a.board(), a.board().tables.first { it.id == sk.id })
        assertEquals(listOf("Разработка котлована"), k.map { it.work })
        assertTrue(k.all { it.uch.isEmpty() && it.zah.isEmpty() })
        a.finish()
    }

    // ---------------------------------------------------------------- помощники

    private fun pills(fr: View): List<TextView> {
        val out = ArrayList<TextView>()
        var i = 0
        while (true) { out += fr.findViewWithTag<TextView>("add_work_$i") ?: break; i++ }
        return out
    }

    private fun pill(fr: View, w: String) = pills(fr).first { it.text.toString() == w }

    /** Отмеченные работы — у них белый текст на бирюзе. */
    private fun ticked(fr: View) = pills(fr).filter { it.currentTextColor == android.graphics.Color.WHITE }.map { it.text.toString() }

    private fun texts(v: View, out: MutableList<String> = ArrayList()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun shotDialog(d: android.app.Dialog, name: String) {
        val root = d.window!!.decorView as ViewGroup
        val custom = root.findViewById<ViewGroup>(android.R.id.custom)
        val content = (custom?.let { findScroll(it) })?.getChildAt(0) ?: root
        content.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(content.measuredWidth.coerceAtLeast(1), content.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        content.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun findScroll(g: ViewGroup): android.widget.ScrollView? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is android.widget.ScrollView) return c
            if (c is ViewGroup) findScroll(c)?.let { return it }
        }
        return null
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
