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
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Шаг 1 ТЗ «Учёт ИГС» (0.20): таблицы ИД — модель, обмен с коллегами, сводка и контроль, файл Excel; на экране —
 * создать таблицу, «+ строка» / «+ следующая захватка», карточка строки пальцем, список статусов у ячейки пером,
 * перо в ячейку, лассо по строкам, «В таблицу» для старых строк ИД, удаление с подтверждением.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TablesTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Доска с разделом «КЖ 6.1.1» и таблицей на 8 строк (4 захватки × опалубка/бетон). */
    private fun sample(): Board {
        val b = Board.create("ИГС")
        b.frames += BoardFrame(0f, 0f, 3000f, 1600f, "КЖ 6.1.1")
        val t = BoardTable(100f, 100f, 1.5f, "КЖ 6.1.1")
        b.tables += t
        var o = 1.0
        for (z in listOf("1", "2", "2.1", "3")) for (w in listOf("Опалубка", "Бетон")) b.recs += BoardRec(t.id, o++).apply {
            zah = z; con = "Стены"; work = w; who = if (z == "3") "СК Вектор" else "Монолит-Строй"
        }
        b.recs[0].apply { igs = IdTables.IGS_PTO; ck = IdTables.CK_DONE; dwg = IdTables.DWG_DONE; date = "2026-07-11"; dcust = "2026-07-25"; dpto = "2026-07-31" }
        b.recs[1].apply { igs = IdTables.IGS_CUST; ck = IdTables.CK_DONE; dcust = "2026-10-01" } // долго у заказчика
        b.recs[2].apply { igs = IdTables.IGS_NOT_GIVEN; ck = IdTables.CK_DONE }                 // чек-лист есть, схемы нет
        b.recs[3].apply { igs = IdTables.IGS_WORK; ck = "k1" }                                  // в работе без чек-листа
        return b
    }

    @Test
    fun modelJsonUndoAndColleagues() {
        val b = sample()
        val t = b.tables[0]
        t.cols.addAll(TableCol.defaults())
        t.cols[0].name = "Участок"
        t.cols.first { it.k == TableCol.NOTE }.hidden = true
        t.cols.add(2, TableCol("cabc12", "Секция"))
        b.recs[0].extra["cabc12"] = "1"
        assertEquals(listOf("Участок", "Конструкция", "Секция"), t.visibleCols().take(3).map { it.title() })
        assertTrue(t.visibleCols().none { it.k == TableCol.NOTE })

        // JSON туда-обратно — те же строки.
        val back = Board.fromJson(JSONObject(b.toJsonString()))
        assertEquals(IdTables.tableJson(t), IdTables.tableJson(back.tables.single()))
        assertEquals(b.recs.map { IdTables.recJson(it) }, back.recs.map { IdTables.recJson(it) })
        assertEquals("1", back.recs[0].extra["cabc12"])
        assertEquals("2026-07-31", back.recs[0].dpto)

        // «Отменить»: снимок и возврат.
        val snap = b.snapshot()
        b.recs[4].igs = IdTables.IGS_BACK
        b.tables[0].title = "другое"
        val r = b.restored(snap)
        assertEquals(IdTables.IGS_NONE, r.recs[4].igs)
        assertEquals("КЖ 6.1.1", r.tables[0].title)

        // Общая доска: таблица и строки — свои виды; правки разных строк с двух планшетов не мешают друг другу.
        b.toJsonString(stampDevice = "dev1")
        val kinds = Remote.pending(b).map { it.kind }.toSet()
        assertTrue(kinds.containsAll(listOf("table", "rec")))
        val base = b.toJsonString()
        val pa = Board.fromJson(JSONObject(base)); val pb = Board.fromJson(JSONObject(base))
        pa.recs[4].igs = IdTables.IGS_AN
        pa.toJsonString(stampDevice = "pa")
        Thread.sleep(5)
        pb.recs[5].ck = IdTables.CK_DONE
        pb.toJsonString(stampDevice = "pb")
        BoardShare.merge(pa, Board.fromJson(JSONObject(pb.toJsonString())))
        assertEquals(IdTables.IGS_AN, pa.recs.first { it.id == b.recs[4].id }.igs)
        assertEquals(IdTables.CK_DONE, pa.recs.first { it.id == b.recs[5].id }.ck)
    }

    @Test
    fun summaryControlsAndHelpers() {
        val b = sample()
        val s = IgsSummary(b, longDays = 7, today = "2026-11-11")
        assertEquals(8, s.total)
        assertEquals(1, s.doneCount)
        assertEquals(1, s.signing)
        assertEquals(listOf(b.recs[2]), s.ctrlNotGiven)
        assertEquals(listOf(b.recs[3]), s.ctrlNoChecklist)
        assertEquals(listOf(b.recs[1]), s.ctrlLong)
        assertEquals(listOf("Монолит-Строй", "СК Вектор"), s.whos)
        assertTrue(s.tsv().contains("КЖ 6.1.1\t8\t1\t7"))
        // Раздел: таблица внутри «КЖ 6.1.1»; статусы ИГС считаются и в легенде.
        assertEquals(8, IgsSummary(b, b.frames[0]).total)
        assertEquals(1, b.countStatus(IdTables.IGS_PTO, b.frames[0]))

        assertEquals("5", IdTables.nextZah("4.1"))
        assertEquals("Захватка№3 (6-7/А-Е)", IdTables.nextZah("Захватка№2 (6-7/А-Е)"))
        assertEquals("Лоток №3", IdTables.nextZah("Лоток №2"))
        assertEquals("2026-08-26", IdTables.parseDate("26.08.26"))
        assertEquals("2026-08-26", IdTables.parseDate("26.08.2026"))
        assertEquals("", IdTables.parseDate("бетон"))
        assertTrue(IdTables.naturalCompare("КЖ6.1.2", "КЖ6.1.10") < 0)
        assertEquals(41, IdTables.daysSince("2026-10-01", "2026-11-11"))
        assertTrue(RowFilter(igs = setOf(IdTables.IGS_PTO)).matches(b.recs[0]))
        assertTrue(!RowFilter(text = "бетон 3").matches(b.recs[0]) && RowFilter(text = "бетон 3").matches(b.recs[7]))
    }

    @Test
    fun excelFile() {
        val b = sample()
        val out = ByteArrayOutputStream()
        val n = IdExcel.write(b, IdExcel.allFilter(b), setOf(IdExcel.SHEET_SUMMARY, IdExcel.SHEET_ALL, IdExcel.SHEET_PER), out, "2026-11-11")
        assertEquals(8, n)
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        for (k in listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
            "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml")) assertTrue(k, k in files)
        // Все части — правильный XML.
        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for ((k, x) in files) db.parse(ByteArrayInputStream(x.toByteArray(Charsets.UTF_8))).also { assertNotNull(k, it) }
        val wb = files["xl/workbook.xml"]!!
        assertTrue(wb.contains("name=\"Сводка\"") && wb.contains("name=\"Схемы\"") && wb.contains("name=\"КЖ 6.1.1\""))
        val all = files["xl/worksheets/sheet2.xml"]!!
        assertEquals("заголовок + 8 строк", 9, Regex("<row ").findAll(all).count())
        assertTrue("дата — числом Excel", all.contains("<v>${IdTables.excelDay("2026-07-11")}</v>"))
        assertTrue(all.contains("Сдано в ПТО") && all.contains("autoFilter"))
        assertTrue(files["xl/worksheets/sheet1.xml"]!!.contains("Сводка ИГС"))
        // Отбор: только «СК Вектор».
        val f = IdExcel.Filter(setOf(b.tables[0].id), b.statuses(BoardStatus.ID).map { it.id }.toSet(), setOf("СК Вектор"))
        assertEquals(2, IdExcel.write(b, f, setOf(IdExcel.SHEET_ALL), ByteArrayOutputStream(), "2026-11-11"))
    }

    // ------------------------------------------------------------ на экране

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        val src = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
    }

    private fun tap(v: View, tool: Int, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, tool))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, x, y, tool))
        idle()
    }

    /** Обвести пером прямоугольник (лассо). */
    private fun lasso(v: View, l: Float, t: Float, r: Float, b: Float) {
        val d = SystemClock.uptimeMillis()
        val pts = listOf(l to t, r to t, r to b, l to b, l to t + 2f)
        val s = MotionEvent.TOOL_TYPE_STYLUS
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, l, t, s))
        var k = 1
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            for (j in 1..6) v.dispatchTouchEvent(event(d, d + 10L * k++, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * j / 6, y0 + (y1 - y0) * j / 6, s))
        }
        v.dispatchTouchEvent(event(d, d + 10L * k, MotionEvent.ACTION_UP, l, t + 2f, s))
        idle()
    }

    private fun shot(v: View, name: String) {
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
    }

    private fun open(b: Board): Screen {
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        return Screen(a, view)
    }

    private fun latest() = ShadowAlertDialog.getLatestAlertDialog()

    @Test
    fun createFillAndEditTable() {
        val b0 = Board.create("ИГС")
        b0.frames += BoardFrame(0f, 0f, 2400f, 1400f, "КЖ 6.1.1")
        val s = open(b0)
        val root = s.a.window.decorView
        s.view.fitRect(0f, 0f, 1600f, 900f)
        idle()

        // «Таблица ИД»: касание у чертежа — шифр (предложен раздел) → таблица → сразу форма строки.
        root.findViewWithTag<View>("board_tool_table").performClick()
        tap(s.view, MotionEvent.TOOL_TYPE_STYLUS, s.sx(200f), s.sy(150f))
        var dlg = latest()
        assertEquals("КЖ 6.1.1", dlg.window!!.decorView.findViewWithTag<EditText>("table_title").text.toString())
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val t = s.b.tables.single()
        assertEquals("КЖ 6.1.1", t.title)
        dlg = latest()
        dlg.window!!.decorView.findViewWithTag<EditText>("add_zah").setText("1")
        dlg.window!!.decorView.findViewWithTag<EditText>("add_who").setText("Монолит-Строй")
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("опалубка и бетон — две строки", listOf("1 Опалубка", "1 Бетон"), IdTables.allRows(s.b, t).map { it.zah + " " + it.work })
        assertEquals(IdTables.IGS_NONE, s.b.recs[0].igs)

        // «+ следующая захватка»: номер +1, те же виды работ; «Добавить и следующую» — сразу ещё форма.
        s.a.tableUi.onTap(t, TableHit(TableHit.ADD_NEXT, null, null), pen = true)
        idle()
        dlg = latest()
        assertEquals("2", dlg.window!!.decorView.findViewWithTag<EditText>("add_zah").text.toString())
        dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertEquals(4, s.b.recs.size)
        assertEquals("Монолит-Строй", s.b.recs.last().who)
        dlg = latest()
        assertEquals("3", dlg.window!!.decorView.findViewWithTag<EditText>("add_zah").text.toString())
        dlg.dismiss()
        idle()
        shot(s.view, "table-new.png")

        // Палец по строке — карточка снизу: статусы крупно, 2 касания на смену, «Ниже» — следующая строка.
        pause(1000)
        val r0 = IdTables.allRows(s.b, t)[0]
        val (wx, wy) = s.cell(r0, TableCol.WORK)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, wx, wy)
        val card = s.a.tableUi.card
        assertNotNull("карточка открылась", card)
        val cv = card!!.window!!.decorView
        cv.findViewWithTag<View>("card_igs").performClick()
        cv.findViewWithTag<View>("card_status_2").performClick() // «В работе»
        idle()
        assertEquals(IdTables.IGS_WORK, r0.igs)
        assertEquals(setOf(r0), s.view.markedRecs)
        cv.findViewWithTag<View>("card_next").performClick()
        idle()
        assertTrue((cv.findViewWithTag<TextView>("card_title").text).contains("Бетон"))
        card.dismiss()
        idle()

        // Перо по статусу — список у ячейки; выбор — статус.
        val (cx, cy) = s.cell(r0, TableCol.CK)
        tap(s.view, MotionEvent.TOOL_TYPE_STYLUS, cx, cy)
        val pop = s.a.tableUi.popup
        assertTrue("список у ячейки", pop != null && pop.isShowing)
        pop!!.contentView.findViewWithTag<View>("pop_status_3").performClick() // «Предъявлено»
        idle()
        assertEquals(IdTables.CK_DONE, r0.ck)

        // Перо в ячейку: распознанное «4.1» — в «Захватку», второе слово подряд — дописывается.
        val (zx, zy) = s.cell(r0, TableCol.ZAH)
        assertTrue(s.a.tableUi.penIntoCell((zx / s.b.camZ) + s.b.camX, (zy / s.b.camZ) + s.b.camY, "4.1"))
        assertEquals("4.1", r0.zah)
        assertTrue(s.a.tableUi.penIntoCell((zx / s.b.camZ) + s.b.camX, (zy / s.b.camZ) + s.b.camY, "бис"))
        assertEquals("4.1 бис", r0.zah)
        // Перо по статусу словом — статус по началу названия.
        val (ix, iy) = s.cell(r0, TableCol.IGS)
        s.a.tableUi.penIntoCell((ix / s.b.camZ) + s.b.camX, (iy / s.b.camZ) + s.b.camY, "сдано")
        assertEquals(IdTables.IGS_PTO, r0.igs)
        s.view.invalidate()
        shot(s.view, "table-edited.png")
        s.a.finish()
    }

    @Test
    fun lassoBulkOldRowsSummaryExportDelete() {
        val b0 = sample()
        val ids = b0.statuses(BoardStatus.ID)
        b0.rows += BoardRow(2000f, 300f, 1.5f, "Захватка 5 — Опалубка", true, false, ids[2].id)
        b0.rows += BoardRow(2000f, 400f, 1.5f, "Захватка 5 — Бетон", false, true, ids[0].id)
        val s = open(b0)
        val root = s.a.window.decorView
        s.view.fitRect(0f, 0f, 2600f, 900f)
        idle()
        val t = s.b.tables.single()
        val rows = IdTables.allRows(s.b, t)

        // «Выделить»: обвели номера захваток первых четырёх строк — выделены строки; «ИГС» всем.
        root.findViewWithTag<View>("board_tool_select").performClick()
        val r0 = s.view.tableDraw.rowRect(s.b, t, rows[0], RectF())!!
        val r3 = s.view.tableDraw.rowRect(s.b, t, rows[3], RectF())!!
        lasso(s.view, s.sx(t.x + 4f), s.sy(r0.top + 4f), s.sx(t.x + 70f * t.scale), s.sy(r3.bottom - 4f))
        assertEquals(rows.take(4).toSet(), s.view.selection.toSet())
        root.findViewWithTag<View>("sel_igs").performClick()
        latest().window!!.decorView.findViewWithTag<View>("bulk_status_7").performClick() // «Сдано в ПТО»
        idle()
        assertTrue(rows.take(4).all { it.igs == IdTables.IGS_PTO })
        assertEquals(IdTables.IGS_NONE, rows[4].igs)
        shot(s.view, "table-lasso.png")
        root.findViewWithTag<View>("sel_notdone").performClick()
        idle()
        assertTrue(rows.take(4).all { it.igs == IdTables.IGS_NONE && it.ck == IdTables.CK_NONE && it.dwg == IdTables.DWG_NONE })
        s.view.clearSelection()

        // Старые строки ИД → «В таблицу» (в эту же).
        s.view.select(s.b.rows.toList())
        idle()
        root.findViewWithTag<View>("sel_totable").performClick()
        shadowOf(latest()).clickOnItem(0)
        idle()
        assertTrue(s.b.rows.isEmpty())
        val moved = IdTables.allRows(s.b, t).takeLast(2)
        assertEquals(listOf("5 Опалубка", "5 Бетон"), moved.map { it.zah + " " + it.work })
        assertEquals(IdTables.CK_DONE, moved[0].ck)
        assertEquals(IdTables.DWG_DONE, moved[1].dwg)
        assertEquals(ids[2].id, moved[0].igs)

        // Сводка: контроль — касание подсвечивает строки на доске.
        s.b.recs.first { it.zah == "2.1" && it.work == "Опалубка" }.apply { igs = IdTables.IGS_NOT_GIVEN; ck = IdTables.CK_DONE }
        s.a.tableUi.summaryDialog()
        idle()
        latest().window!!.decorView.findViewWithTag<View>("sum_ctrl_1").performClick()
        idle()
        assertEquals(1, s.view.markedRecs!!.size)
        shot(s.view, "table-control.png")

        // Выгрузка в Excel: файл для отправки.
        val f = s.a.tableUi.exportFile(IdExcel.allFilter(s.b), setOf(IdExcel.SHEET_SUMMARY, IdExcel.SHEET_ALL))
        assertTrue(f != null && f.exists() && f.length() > 1000 && f.name.endsWith(".xlsx"))

        // Удалить таблицу — только после подтверждения; со строками; «Отменить» вернёт.
        s.view.select(listOf(t))
        idle()
        root.findViewWithTag<View>("sel_delete").performClick()
        assertEquals("ещё не удалена", 1, s.b.tables.size)
        latest().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(s.b.tables.isEmpty() && s.b.recs.isEmpty())
        root.findViewWithTag<View>("board_undo").performClick()
        idle()
        assertEquals(1, s.b.tables.size)
        assertEquals(10, s.b.recs.size)
        s.a.finish()
    }

    /** 0.20.1: доска 0.18, где список ИД не нетронутый (свой статус, другой порядок), тоже получает статусы ИГС. */
    @Test
    fun oldIdListWithOwnStatusesBecomesIgs() {
        val b = Board.create("Старая")
        b.statuses.clear()
        b.statuses += listOf(BoardStatus("i1", "В оформлении", 2), BoardStatus("i0", "Конструкция не принята", 1),
            BoardStatus("sNo", "Нет схемы", 9), BoardStatus("i2", "На подписи у Заказчика", 3), BoardStatus("i4", "Скан в архиве", 5),
            BoardStatus("i3", "Передано в ПТО", 4))
        b.statuses += Board.conStatuses()
        b.stickers += BoardSticker(1f, 2f, "i4", 1f)
        val t = BoardTable(0f, 0f, 1f, "КЖ")
        b.tables += t
        b.recs += BoardRec(t.id, 1.0).apply { igs = "i4" }
        val u = Board.fromJson(JSONObject(b.toJsonString()))
        val names = u.statuses(BoardStatus.ID).map { it.name }
        assertEquals(IdTables.igsStatuses().map { it.name } + "Нет схемы", names)
        assertEquals("Конструкция не выполнена", u.status(IdTables.IGS_NONE).name)
        assertEquals("«Скан в архиве» → «Сдано в ПТО»", IdTables.IGS_PTO, u.stickers[0].sid)
        assertEquals(IdTables.IGS_PTO, u.recs[0].igs)
        assertTrue(u.status(IdTables.IGS_PTO).done)
        // Второй раз ничего не меняется; удалённый статус не возвращается.
        assertEquals(u.entryHashes(), Board.fromJson(JSONObject(u.toJsonString())).entryHashes())
        u.statuses.removeAll { it.id == IdTables.IGS_AN }
        assertTrue(Board.fromJson(JSONObject(u.toJsonString())).statuses.none { it.id == IdTables.IGS_AN })
        // Доска 0.20.0 со статусом «Не выполнено» — новое название.
        val b20 = Board.create("0.20.0")
        b20.statuses.first { it.id == IdTables.IGS_NONE }.name = "Не выполнено"
        assertEquals("Конструкция не выполнена", Board.fromJson(JSONObject(b20.toJsonString())).status(IdTables.IGS_NONE).name)
    }

    /** 0.20.1: палец по статусу — список рядом (без карточки); перо в «В текст» — активная ячейка, пишут где угодно. */
    @Test
    fun fingerStatusListAndPenWritesAnywhereIntoActiveCell() {
        val b0 = sample()
        val s = open(b0)
        val root = s.a.window.decorView
        s.view.fitRect(0f, 0f, 2600f, 900f)
        idle()
        val t = s.b.tables.single()
        val rows = IdTables.allRows(s.b, t)

        // Палец по ИГС — список у ячейки, карточки нет; выбор — статус.
        pause(1000)
        val (ix, iy) = s.cell(rows[4], TableCol.IGS)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, ix, iy)
        assertTrue("список у ячейки", s.a.tableUi.popup?.isShowing == true)
        assertTrue("карточка не открылась", s.a.tableUi.card == null)
        s.a.tableUi.popup!!.contentView.findViewWithTag<View>("pop_status_2").performClick() // «В работе»
        idle()
        assertEquals(IdTables.IGS_WORK, rows[4].igs)
        // Палец по тексту строки — карточка, как раньше.
        val (wx, wy) = s.cell(rows[4], TableCol.WORK)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, wx, wy)
        assertNotNull(s.a.tableUi.card)
        s.a.tableUi.card!!.dismiss()
        idle()

        // «В текст»: касание пером ячейки «Захватка» — активная ячейка и панель «→ ↓ Готово».
        root.findViewWithTag<View>("board_tool_write").performClick()
        val (zx, zy) = s.cell(rows[4], TableCol.ZAH)
        tap(s.view, MotionEvent.TOOL_TYPE_STYLUS, zx, zy)
        assertEquals(rows[4] to TableCol.ZAH, s.a.tableUi.active)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("mode_bar").visibility)
        shot(s.view, "table-active-cell.png")
        // Написали далеко от таблицы — текст в активную ячейку (первая надпись заменяет, следующие дописываются).
        assertTrue(s.a.tableUi.penIntoCell(2500f, 800f, "3.1"))
        assertEquals("3.1", rows[4].zah)
        assertTrue(s.a.tableUi.penIntoCell(2500f, 800f, "бис"))
        assertEquals("3.1 бис", rows[4].zah)
        // → следующая ячейка для письма (конструкция), ↓ — та же ячейка строкой ниже.
        root.findViewWithTag<View>("cell_next").performClick()
        assertEquals(rows[4] to TableCol.CON, s.a.tableUi.active)
        s.a.tableUi.penIntoCell(2500f, 800f, "Колонны")
        assertEquals("Колонны", rows[4].con)
        root.findViewWithTag<View>("cell_down").performClick()
        assertEquals(rows[5] to TableCol.CON, s.a.tableUi.active)
        root.findViewWithTag<View>("cell_done").performClick()
        idle()
        assertEquals(null, s.a.tableUi.active)
        assertEquals(View.GONE, root.findViewWithTag<View>("mode_bar").visibility)
        // Без активной ячейки надпись вдали от таблиц — обычный текст на доске (не в таблицу).
        assertTrue(!s.a.tableUi.penIntoCell(2500f, 800f, "заметка"))
        // Касание пером мимо таблиц снимает активную ячейку.
        tap(s.view, MotionEvent.TOOL_TYPE_STYLUS, zx, zy)
        assertNotNull(s.a.tableUi.active)
        tap(s.view, MotionEvent.TOOL_TYPE_STYLUS, s.sx(2400f), s.sy(850f))
        assertEquals(null, s.a.tableUi.active)
        s.a.finish()
    }
}
