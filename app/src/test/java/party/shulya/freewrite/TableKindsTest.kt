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
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * 0.24 (Владимир): виды таблиц ИД. Кнопка «Таблица ИД» — проводник (раздел КЖ с плюсиком, «Вертикальные элементы» и
 * «Плиты перекрытия»); «Объём по схеме» делится на части (Стены | Колонны, Плита | Балки), итоги и проект — по частям.
 * Таблицы до 0.24 — «Вертикальные элементы» с пустыми объёмами, данные доски при этом не переписываются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableKindsTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
        ctx.getSharedPreferences("igs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Раздел КЖ 6.1.1: «Вертикальные элементы» (2 захватки) и «Плиты перекрытия» (1 захватка). */
    private fun sample(): Board {
        val b = Board.create("ИГС")
        b.frames += BoardFrame(0f, 0f, 3400f, 2600f, "КЖ 6.1.1")
        val tv = BoardTable(100f, 100f, 1.5f, TableKinds.KJ_VERT.name).apply { kind = TableKinds.KJ_VERT.id }
        val ts = BoardTable(100f, 1400f, 1.5f, TableKinds.KJ_SLAB.name).apply { kind = TableKinds.KJ_SLAB.id }
        b.tables += tv; b.tables += ts
        var o = 1.0
        for ((z, w, wall, col) in listOf(
            Quad("1", "Опалубка", "186.4", "24"), Quad("1", "Бетон", "42.1", "5.4"),
            Quad("2", "Опалубка", "190.2", "24"), Quad("2", "Бетон", "", ""))) {
            b.recs += BoardRec(tv.id, o++).apply {
                zah = z; work = w
                if (wall.isNotEmpty()) vp["w"] = wall
                if (col.isNotEmpty()) vp["c"] = col
            }
        }
        b.recs += BoardRec(ts.id, o++).apply { zah = "1"; work = "Опалубка"; vp["p"] = "412"; vp["b"] = "58.6" }
        tv.plan[IdTables.planKey("Опалубка", "w")] = VolPlan("Опалубка", "1200", "м²", "w")
        tv.plan[IdTables.planKey("Бетон", "c")] = VolPlan("Бетон", "50", "м³", "c")
        return b
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    @Test
    fun partsVolumesJsonUndoMergeExcel() {
        val b = sample()
        val tv = b.tables[0]; val ts = b.tables[1]
        // Столбцы: вместо «Объёма по схеме» — части; «Тип конструкции» скрыт.
        assertEquals(listOf("aosr", "nsch", "uch", "zah", "con", "work", "vol.w", "vol.c", "date"), tv.allCols().take(9).map { it.k })
        assertFalse(tv.visibleCols().any { it.k == TableCol.CON || it.k == TableCol.VOL })
        assertEquals(listOf("Плита", "Балки"), ts.visibleCols().filter { it.part != null }.map { it.title() })
        assertEquals("Объём · Стены", tv.allCols().first { it.k == "vol.w" }.longTitle())
        assertEquals("186,4 м²", IdTables.cellText(b, b.recs[0], "vol.w"))
        assertEquals("5,4 м³", IdTables.cellText(b, b.recs[1], "vol.c"))
        assertEquals("", IdTables.cellText(b, b.recs[0], TableCol.VOL))

        // Итоги: вид работ × часть; проект — у каждой части свой; схемы без объёма — у первой части.
        val v = IdTables.volumes(b, tv)
        assertEquals(listOf("Опалубка · Стены", "Опалубка · Колонны", "Бетон · Стены", "Бетон · Колонны"), v.map { it.label })
        assertEquals(376.6, v[0].fact, 1e-9); assertEquals(1200.0, v[0].plan!!, 1e-9); assertEquals(31, v[0].percent)
        assertEquals(48.0, v[1].fact, 1e-9); assertNull(v[1].plan)
        assertEquals(5.4, v[3].fact, 1e-9); assertEquals(11, v[3].percent)
        assertEquals("захватка 2, бетон — без объёма", 1, v.sumOf { it.missing.size })

        // JSON: вид таблицы, части строки (по алфавиту ключей), проект части.
        val json = b.toJsonString()
        val back = Board.fromJson(JSONObject(json))
        assertEquals(IdTables.tableJson(tv), IdTables.tableJson(back.tables[0]))
        assertEquals(IdTables.recJson(b.recs[0]), IdTables.recJson(back.recs[0]))
        assertTrue(IdTables.recJson(b.recs[0]).contains("\"vp\":{\"c\":\"24\",\"w\":\"186.4\"}"))
        assertEquals("kj_slab", back.tables[1].kind)
        assertEquals("1200", back.tables[0].plan[IdTables.planKey("Опалубка", "w")]!!.value)
        assertEquals(back.toJsonString(), json)

        // История и отмена: правка части объёма.
        Vedoms.set(b, b.recs[0], "vol.w", "186.9", "Владимир Шульмин")
        assertEquals("Объём · Стены: 186,4 → 186,9", Vedoms.eventText(b, Vedoms.history(b, b.recs[0]).first()))
        val snap = b.snapshot()
        b.recs[0].vp["w"] = "1"
        assertEquals("186.9", b.restored(snap).recs[0].vp["w"])
        b.recs[0].vp["w"] = "186.9"

        // Обмен: часть объёма от коллеги приходит.
        b.toJsonString(stampDevice = "dev1")
        val other = Board.fromJson(JSONObject(b.toJsonString()))
        other.recs[3].vp["w"] = "43"
        other.toJsonString(stampDevice = "dev2")
        Thread.sleep(5)
        BoardShare.merge(b, Board.fromJson(JSONObject(other.toJsonString())))
        assertEquals("43", b.recs[3].vp["w"])

        // Excel: «Объём · Стены», «Объём · Колонны» числом и единица; в «Сводке» — «Опалубка · Стены».
        val out = ByteArrayOutputStream()
        IdExcel.write(b, IdExcel.allFilter(b), setOf(IdExcel.SHEET_SUMMARY, IdExcel.SHEET_ALL, IdExcel.SHEET_PER), out, "2026-11-13")
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        val all = files["xl/worksheets/sheet2.xml"]!!
        assertTrue(all.contains("Объём · Стены") && all.contains("Объём · Колонны") && all.contains("Объём · Плита") && all.contains("Объём · Балки"))
        assertTrue(all.contains("<v>186.9</v>") && all.contains("<v>58.6</v>") && all.contains(">Ед.<"))
        assertTrue(files["xl/worksheets/sheet1.xml"]!!.contains("Опалубка · Стены"))
        val per = files["xl/worksheets/sheet3.xml"]!!
        assertTrue(per.contains("Объём · Стены") && !per.contains("Тип конструкции"))
    }

    @Test
    fun oldTablesAreVerticalWithEmptyVolumesWithoutRewriting() {
        // Таблица до 0.24: вид не записан, свой список столбцов с «Объёмом по схеме», объёмы и проект — одним числом.
        val b0 = Board.create("ИГС")
        b0.frames += BoardFrame(0f, 0f, 3000f, 2000f, "КЖ 6.1.1")
        val t0 = BoardTable(100f, 100f, 1.5f, "Несущие стены")
        b0.tables += t0
        b0.recs += BoardRec(t0.id, 1.0).apply { zah = "1"; con = "Стены"; work = "Бетон"; vol = "42.3" }
        t0.plan["бетон"] = VolPlan("Бетон", "170", "м³")
        val j = JSONObject(b0.toJsonString())
        val tj = j.getJSONArray("tables").getJSONObject(0)
        tj.remove("kind")
        val oldKeys = listOf("aosr", "nsch", "uch", "zah", "con", "work", "vol", "date", "ck", "dwg", "igs", "dcust", "dpto", "who", "note")
        tj.put("cols", JSONArray().apply { for (k in oldKeys) put(JSONObject().put("k", k)) })
        val b = Board.fromJson(j)
        val t = b.tables[0]
        assertEquals("", t.kind)
        assertEquals(TableKinds.KJ_VERT, t.kindDef())
        // Части — на месте «Объёма по схеме»; «Тип конструкции» — как был у таблицы (виден).
        assertEquals(listOf("aosr", "nsch", "uch", "zah", "con", "work", "vol.w", "vol.c", "date"), t.visibleCols().map { it.k }.take(9))
        // Старый объём и проект не считаются — Владимир впишет объёмы заново по частям.
        assertTrue(IdTables.volumes(b, t).all { it.fact == 0.0 && it.plan == null })
        assertEquals("", IdTables.cellText(b, b.recs[0], TableCol.VOL))
        assertEquals("", IdTables.cellText(b, b.recs[0], "vol.w"))
        // Данные не переписаны: строки таблицы и схемы — те же, что пришли (коллега со старой копией ничего не затрёт).
        val again = Board.fromJson(JSONObject(b.toJsonString()))
        assertEquals("42.3", again.recs[0].vol)
        val cols = JSONObject(IdTables.tableJson(again.tables[0])).getJSONArray("cols")
        assertEquals(oldKeys, (0 until cols.length()).map { cols.getJSONObject(it).getString("k") })
        assertEquals(IdTables.tableJson(t), IdTables.tableJson(again.tables[0]))
        assertTrue("«Объём по схеме» остался в своём списке", IdTables.tableJson(again.tables[0]).contains("\"k\":\"vol\""))
        assertFalse(IdTables.tableJson(again.tables[0]).contains("vol.w"))
        // Правка столбцов — список записывается как видно (части вместо объёма).
        t.ownCols().first { it.k == TableCol.CON }.hidden = true
        assertEquals(listOf("zah", "work", "vol.w", "vol.c"), t.visibleCols().map { it.k }.filter { it in setOf("zah", "con", "work", "vol.w", "vol.c", "vol") })
        assertFalse(IdTables.tableJson(t).contains("\"k\":\"vol\""))
        // Пустая таблица («как сейчас») — один объём.
        val c = BoardTable(0f, 0f, 1f, "Своя").apply { kind = TableKinds.CUSTOM }
        assertTrue(c.parts().isEmpty())
        assertTrue(c.visibleCols().any { it.k == TableCol.VOL } && c.visibleCols().any { it.k == TableCol.CON })
    }

    // ------------------------------------------------------------ на экране

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        val src = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, MotionEvent.TOOL_TYPE_FINGER))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, x, y, MotionEvent.TOOL_TYPE_FINGER))
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
    }

    private fun open(b: Board): Screen {
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        return Screen(a, view)
    }

    private fun latest() = ShadowAlertDialog.getLatestAlertDialog()

    private fun texts(v: View, out: MutableList<String> = ArrayList()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    /** Окно (проводник) — картинкой, для сверки с макетом. */
    private fun shotDialog(d: android.app.Dialog, name: String) {
        val root = d.window!!.decorView
        val w = 1400; val h = 1000
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bmp = Bitmap.createBitmap(root.measuredWidth.coerceAtLeast(1), root.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        root.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun explorerPlacesKindTableAndPartsAreEntered() {
        val b0 = Board.create("ИГС")
        b0.frames += BoardFrame(0f, 0f, 2400f, 1400f, "КЖ 6.1.1")
        val s = open(b0)
        val root = s.a.window.decorView
        s.view.fitRect(0f, 0f, 1600f, 900f)
        idle()

        // «Таблица ИД» — проводник: раздел КЖ свёрнут, плюсик раскрывает ветку с двумя видами.
        root.findViewWithTag<View>("board_tool_table").performClick()
        idle()
        val ex = s.a.tableUi.explorer!!
        val er = ex.window!!.decorView
        val head = texts(er).joinToString("\n")
        assertTrue(head, head.contains("Таблица ИД — какую ставим?") && head.contains("сейчас: КЖ 6.1.1") && head.contains("Пустая таблица"))
        assertNull(er.findViewWithTag<View>("kind_kj_vert"))
        er.findViewWithTag<View>("kind_sec_0").performClick(); idle()
        assertNotNull(er.findViewWithTag<View>("kind_kj_vert")); assertNotNull(er.findViewWithTag<View>("kind_kj_slab"))
        assertTrue(texts(er).contains("объём: Стены · Колонны"))
        shotDialog(ex, "kinds-explorer.png")
        // Поиск: «балк» — только плиты.
        er.findViewWithTag<EditText>("kind_search").setText("балк"); idle()
        assertNull(er.findViewWithTag<View>("kind_kj_vert")); assertNotNull(er.findViewWithTag<View>("kind_kj_slab"))
        er.findViewWithTag<EditText>("kind_search").setText(""); idle()

        // «Поставить» — таблица в разделе, название — вид, сразу форма строки (без «Типа конструкции»).
        er.findViewWithTag<View>("kind_kj_vert").performClick(); idle()
        assertFalse(ex.isShowing)
        val t = s.b.tables.single()
        assertEquals(TableKinds.KJ_VERT.id, t.kind)
        assertEquals("КЖ 6.1.1 · Вертикальные элементы", IdTables.fullTitle(s.b, t))
        var d = latest()
        assertNull(d.window!!.decorView.findViewWithTag<View>("add_con"))
        d.window!!.decorView.findViewWithTag<EditText>("add_zah").setText("1")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals("опалубка и бетон, без армирования", listOf("1 Опалубка", "1 Бетон"), IdTables.allRows(s.b, t).map { it.zah + " " + it.work })

        // Объём: касание ячейки «Стены» — сразу число.
        val tb = s.view.tableDraw.bounds(s.b, t, RectF())
        s.view.fitRect(tb.left - 40f, tb.top - 40f, tb.right + 40f, tb.bottom + 40f); idle(); pause(600)
        val rec = IdTables.allRows(s.b, t)[0]
        val c = s.view.tableDraw.cellRect(s.b, t, rec, "vol.w", RectF())!!
        tap(s.view, s.sx(c.centerX()), s.sy(c.centerY()))
        assertNull(s.a.tableUi.card)
        d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("cell_text").setText("186,4")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals("186.4", s.a.board().recs.first { it.id == rec.id }.vp["w"])
        val c2 = s.view.tableDraw.cellRect(s.b, t, rec, "vol.c", RectF())!!
        tap(s.view, s.sx(c2.centerX()), s.sy(c2.centerY()))
        d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("cell_text").setText("24")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals("24", s.a.board().recs.first { it.id == rec.id }.vp["c"])

        // Проект: касание ячейки сетки итогов «Опалубка × Стены» — проект этой части.
        val t1 = s.a.board().tables.single()
        val l = s.view.tableDraw.layout(s.b, t1)
        assertEquals(listOf("w", "c"), l.volParts.map { it.key })
        val pw = (l.w - TableDraw.VOLP_WORK - TableDraw.VOLP_TOTAL) / 2
        val hx = t1.x + (TableDraw.VOLP_WORK + pw * 0.5f) * t1.scale
        val hy = t1.y + (l.volTop + TableDraw.VOL_HEAD + TableDraw.VOLP_HEAD + TableDraw.VOLP_ROW / 2f) * t1.scale
        val hit = s.view.tableDraw.hit(s.b, t1, hx, hy)!!
        assertEquals(TableHit.VOL, hit.part); assertEquals("w", hit.vpart); assertEquals("Опалубка", hit.col)
        tap(s.view, s.sx(hx), s.sy(hy))
        d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("proj_value").setText("1 200")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val t2 = s.a.board().tables.single()
        assertEquals("1200", t2.plan[IdTables.planKey("Опалубка", "w")]!!.value)
        assertNull(t2.plan[IdTables.planKey("Опалубка", "c")])
        val v = IdTables.volumes(s.a.board(), t2)
        assertEquals(16, v.first { it.part == "w" && it.work == "Опалубка" }.percent)
        s.view.invalidate(); idle()
        shot(s.view, "kinds-vert-table.png")

        // «Пустая таблица» — как раньше: своё название, один объём.
        root.findViewWithTag<View>("board_tool_table").performClick(); idle()
        s.a.tableUi.explorer!!.window!!.decorView.findViewWithTag<View>("kind_custom").performClick(); idle()
        d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("table_title").setText("Своя")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        latest().dismiss()
        val own = s.a.board().tables.last()
        assertEquals(TableKinds.CUSTOM, own.kind)
        assertTrue(own.visibleCols().any { it.k == TableCol.VOL })
        // Новая таблица не легла поверх первой.
        val r1 = s.view.tableDraw.bounds(s.a.board(), s.a.board().tables[0], RectF())
        val r2 = s.view.tableDraw.bounds(s.a.board(), own, RectF())
        assertFalse("таблицы не перекрываются: $r1 / $r2", RectF.intersects(r1, r2))
        s.a.finish()
    }

    @Test
    fun slabOutsideSectionAsksCipher() {
        val s = open(Board.create("ИГС"))
        s.view.fitRect(0f, 0f, 1600f, 900f); idle()
        s.a.tableUi.chooseKind(400f to 300f); idle()
        val er = s.a.tableUi.explorer!!.window!!.decorView
        assertTrue(texts(er).joinToString().contains("раздела здесь нет"))
        er.findViewWithTag<View>("kind_sec_0").performClick(); idle()
        er.findViewWithTag<View>("kind_kj_slab").performClick(); idle()
        val d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("table_title").setText("КЖ 7.2.2")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        latest().dismiss()
        val t = s.b.tables.single()
        assertEquals("КЖ 7.2.2", t.title); assertEquals(TableKinds.KJ_SLAB.id, t.kind)
        assertEquals(400f, t.x, 0.01f); assertEquals(300f, t.y, 0.01f)
        // Ветка КЖ запомнилась раскрытой.
        s.a.tableUi.chooseKind(); idle()
        assertNotNull(s.a.tableUi.explorer!!.window!!.decorView.findViewWithTag<View>("kind_kj_vert"))
        s.a.finish()
    }
}
