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
 * Шаг 2 ТЗ «Учёт ИГС» (0.21): ведомости и история схемы. Модель (передача, ответы по частям, закрытие, сводка, обмен
 * с коллегами, отмена, опись в Excel) и на экране — проверка из ТЗ: ведомость «На подпись заказчику» из 15 схем →
 * передать → 12 подписано, 2 замечания, 1 ждём (в разные дни) → статусы и даты в таблице верны, история — кто и когда.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VedomTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Таблица «КЖ 6.1.1»: 8 захваток × опалубка/бетон = 16 схем; 15 «В работе», последняя — «Конструкция не выполнена». */
    private fun sample(): Board {
        val b = Board.create("ИГС")
        val t = BoardTable(100f, 100f, 1.5f, "КЖ 6.1.1")
        b.tables += t
        var o = 1.0
        for (z in 1..8) for (w in listOf("Опалубка", "Бетон")) b.recs += BoardRec(t.id, o++).apply {
            zah = z.toString(); con = "Стены"; work = w; who = "Монолит-Строй"; igs = IdTables.IGS_WORK; ck = IdTables.CK_DONE
        }
        b.recs.last().apply { igs = IdTables.IGS_NONE; ck = IdTables.CK_NONE }
        return b
    }

    @Test
    fun customerVedomByPartsHistoryAndColleagues() {
        val b = sample()
        val recs = b.recs.filter { it.igs == IdTables.IGS_WORK }
        assertEquals(15, recs.size)
        val v = BoardVedom(1500f, 100f, 1.5f, Vedoms.CUST, Vedoms.nextNum(b, Vedoms.CUST))
        b.vedoms += v
        assertEquals("З-1", v.num)
        assertEquals(15, Vedoms.add(b, v, recs, "Владимир Шульмин", now = 500L))
        assertEquals("повторно не добавляются", 0, Vedoms.add(b, v, recs.take(3)))
        assertEquals("сборка", Vedoms.state(b, v))
        assertEquals("Сборка ▾", Vedoms.stateChip(b, v))
        assertTrue("в сборке — схемы «Сборка»", recs.all { it.igs == IdTables.IGS_ASSEMBLY })

        // Передать 10.11: всем — «На подписи у заказчика» и «Отдал заказчику».
        Vedoms.give(b, v, "2026-11-10", "Владимир Шульмин", now = 1000L)
        assertTrue(recs.all { it.igs == IdTables.IGS_CUST && it.dcust == "2026-11-10" })
        assertEquals("15 схем · ждём 15 (1 дн.)", Vedoms.summary(b, v, "2026-11-11"))
        assertTrue(Vedoms.isOpen(b, v))

        // Ответы по частям: 11.11 — 8 подписано, 12.11 — 4 подписано и 2 замечания, одна ждёт.
        val items = Vedoms.items(b, v)
        for (i in items.take(8)) Vedoms.answer(b, v, i, Vedoms.OK, "2026-11-11", "Владимир Шульмин", now = 2000L)
        for (i in items.subList(8, 12)) Vedoms.answer(b, v, i, Vedoms.OK, "2026-11-12", "Иван Петров", now = 3000L)
        for (i in items.subList(12, 14)) Vedoms.answer(b, v, i, Vedoms.FIX, "2026-11-12", "Иван Петров", now = 3000L)
        assertEquals("15 схем · подписано 12 · замечания 2 · ждём 1 (6 дн.)", Vedoms.summary(b, v, "2026-11-16"))
        assertTrue(!Vedoms.isClosed(b, v))
        val r0 = Vedoms.rec(b, items[0])!!
        assertEquals("подписано — сразу «Сдано в ПТО» с датой", IdTables.IGS_PTO, r0.igs)
        assertEquals("2026-11-11", r0.dpto)
        assertEquals("2026-11-12", Vedoms.rec(b, items[9])!!.dpto)
        assertEquals(IdTables.IGS_BACK, Vedoms.rec(b, items[12])!!.igs)
        assertEquals(IdTables.IGS_CUST, Vedoms.rec(b, items[14])!!.igs)
        // Сводка: открытые ведомости, долго на подписи.
        val s = IgsSummary(b, longDays = 7, today = "2026-11-20")
        assertEquals(1, s.openVedoms.size)
        assertEquals(1, s.openVedoms[0].second)
        assertEquals(listOf(Vedoms.rec(b, items[14])), s.ctrlLong)

        // История схемы: кто, когда, было → стало, по какой ведомости.
        val h = Vedoms.history(b, r0)
        assertEquals(listOf("ИГС: На подписи у заказчика → Сдано в ПТО · ведомость З-1", "Сдано в ПТО: — → 11.11.2026 · ведомость З-1",
            "ИГС: Сборка → На подписи у заказчика · ведомость З-1", "Отдал заказчику: — → 10.11.2026 · ведомость З-1",
            "ИГС: В работе → Сборка · ведомость З-1").sorted(),
            h.map { Vedoms.eventText(b, it) }.sorted())
        assertEquals(setOf("Владимир Шульмин"), h.map { it.who }.toSet())
        assertEquals("Иван Петров", Vedoms.history(b, Vedoms.rec(b, items[12])!!).first().who)

        // «Ждём» снова — статус передачи, дата «Сдано в ПТО», поставленная ответом, снимается.
        Vedoms.answer(b, v, items[0], "", "2026-11-13", "Владимир Шульмин", now = 4000L)
        assertEquals(IdTables.IGS_CUST, r0.igs)
        assertEquals("", r0.dpto)
        Vedoms.answer(b, v, items[0], Vedoms.OK, "2026-11-11", "Владимир Шульмин", now = 5000L)
        // Последняя: закрыта, в архиве остаётся состав и даты, даже если статусы потом поменялись.
        Vedoms.answer(b, v, items[14], Vedoms.OK, "2026-11-14", "Владимир Шульмин", now = 6000L)
        assertTrue(Vedoms.isClosed(b, v))
        assertEquals("закрыта", Vedoms.state(b, v))
        r0.igs = IdTables.IGS_BACK
        assertEquals(Vedoms.OK, items[0].res)

        // JSON туда-обратно, отмена, номера.
        val back = Board.fromJson(JSONObject(b.toJsonString()))
        assertEquals(b.vedoms.map { Vedoms.vedomJson(it) }, back.vedoms.map { Vedoms.vedomJson(it) })
        assertEquals(b.vitems.map { Vedoms.itemJson(it) }, back.vitems.map { Vedoms.itemJson(it) })
        assertEquals(b.events.map { it.json() }, back.events.map { it.json() })
        val snap = b.snapshot()
        val nEv = b.events.size
        Vedoms.answer(b, v, items[1], Vedoms.FIX, "2026-11-15", "x", now = 7000L)
        val restored = b.restored(snap)
        assertEquals(nEv, restored.events.size)
        assertEquals(Vedoms.OK, restored.vitems.first { it.id == items[1].id }.res)
        assertTrue("события без изменений — те же объекты", restored.events.first() === b.events.first())

        // Общая доска: ведомость, позиции, события — свои виды; ответы по разным схемам с двух планшетов сходятся.
        b.toJsonString(stampDevice = "dev1")
        assertTrue(Remote.pending(b).map { it.kind }.toSet().containsAll(listOf("vedom", "vitem", "event")))
        val base = sample()
        val v2 = BoardVedom(0f, 0f, 1f, Vedoms.AN, "А-1").also { base.vedoms += it }
        Vedoms.add(base, v2, base.recs.take(4), "Владимир Шульмин")
        val t10 = java.util.Calendar.getInstance().apply { clear(); set(2026, 10, 10, 12, 0) }.timeInMillis
        Vedoms.give(base, v2, "2026-11-10", "Владимир Шульмин", now = t10)
        base.toJsonString(stampDevice = "dev0")
        val json = base.toJsonString()
        val pa = Board.fromJson(JSONObject(json)); val pb = Board.fromJson(JSONObject(json))
        val va = pa.vedoms[0]; val vb = pb.vedoms[0]
        Vedoms.answer(pa, va, Vedoms.items(pa, va)[0], Vedoms.OK, "2026-11-11", "Владимир Шульмин")
        pa.toJsonString(stampDevice = "pa")
        Thread.sleep(5)
        Vedoms.answer(pb, vb, Vedoms.items(pb, vb)[1], Vedoms.NO, "2026-11-11", "Иван Петров")
        pb.toJsonString(stampDevice = "pb")
        BoardShare.merge(pa, Board.fromJson(JSONObject(pb.toJsonString())))
        val ia = Vedoms.items(pa, va)
        assertEquals(listOf(Vedoms.OK, Vedoms.NO, "", ""), ia.map { it.res })
        assertEquals(IdTables.IGS_CUST, Vedoms.rec(pa, ia[0])!!.igs)
        assertEquals("АН согласовал — дата «Отдал заказчику»", "2026-11-11", Vedoms.rec(pa, ia[0])!!.dcust)
        assertEquals(IdTables.IGS_AN_NO, Vedoms.rec(pa, ia[1])!!.igs)
        assertEquals("история обоих", setOf("Владимир Шульмин", "Иван Петров"), pa.events.map { it.who }.toSet())
        // Согласованные — одной кнопкой заказчику; схема, что ждёт АН, — предупреждение при сборе в другую.
        assertEquals(listOf(ia[0].r), Vedoms.approvedForCustomer(pa, va).map { it.id })
        assertEquals(listOf(va), Vedoms.waitingElsewhere(pa, Vedoms.rec(pa, ia[2])!!, null))
        // На подписи АН дольше срока — по дате передачи АН (и у заказчика — с 11.11, после согласования АН).
        val sa = IgsSummary(pa, longDays = 7, today = "2026-11-30")
        assertEquals(setOf(ia[0].r, ia[2].r, ia[3].r), sa.ctrlLong.map { it.id }.toSet())
        assertEquals(setOf(ia[0].r), IgsSummary(pa, longDays = 7, today = "2026-11-19").ctrlLong.map { it.id }.toSet().minus(setOf(ia[2].r, ia[3].r)))
        assertEquals("2026-11-10", sa.signingSince(Vedoms.rec(pa, ia[2])!!))
    }

    /** 0.22.1: «Сборка» → «На подписи у заказчика» (Владимир); убрали из сборки — прежний статус; назад в сборку; старые доски. */
    @Test
    fun assemblyThenCarriedToCustomer() {
        val b = sample()
        val recs = b.recs.take(4)
        recs[3].igs = IdTables.IGS_BACK        // возврат на корректировку — тоже в сборку
        val last = b.recs.last()               // «Конструкция не выполнена» — статус не трогаем
        val v = BoardVedom(0f, 0f, 1f, Vedoms.CUST, "З-1").also { b.vedoms += it }
        Vedoms.add(b, v, recs + last, "Владимир Шульмин")
        assertTrue(recs.all { it.igs == IdTables.IGS_ASSEMBLY })
        assertEquals(IdTables.IGS_NONE, last.igs)
        assertEquals("Сборка", IdTables.status(b, BoardStatus.ID, IdTables.IGS_ASSEMBLY).name)
        // Убрали из сборки — статус, что был до неё.
        val it3 = Vedoms.items(b, v).first { i -> i.r == recs[3].id }
        Vedoms.unassemble(b, v, listOf(it3), "Владимир Шульмин")
        b.vitems.remove(it3)
        assertEquals(IdTables.IGS_BACK, recs[3].igs)
        // Уже в сборке в другой ведомости — предупреждение при сборе.
        assertEquals(listOf(v), Vedoms.waitingElsewhere(b, recs[0], null))
        // Унесли заказчику — «На подписи у заказчика», дата; кнопка состояния.
        Vedoms.give(b, v, "2026-11-12", "Владимир Шульмин")
        assertTrue(recs.take(3).all { it.igs == IdTables.IGS_CUST && it.dcust == "2026-11-12" })
        assertEquals("На подписи у заказчика от 12.11.26 ▾", Vedoms.stateChip(b, v))
        // Ошиблись — назад в сборку (ответов ещё нет): снова «Сборка», дата убрана.
        assertTrue(Vedoms.canUngive(b, v))
        Vedoms.ungive(b, v, "Владимир Шульмин")
        assertTrue(v.isDraft)
        assertTrue(recs.take(3).all { it.igs == IdTables.IGS_ASSEMBLY && it.dcust.isEmpty() })
        Vedoms.give(b, v, "2026-11-13", "Владимир Шульмин")
        Vedoms.answer(b, v, Vedoms.items(b, v)[0], Vedoms.OK, "2026-11-14", "Владимир Шульмин")
        assertTrue("есть ответ — назад нельзя", !Vedoms.canUngive(b, v))
        // Старая доска (0.21) — статус «Сборка» встаёт после «В работе», один раз, одинаково на всех планшетах.
        val old = Board.create("Старая")
        old.statuses.removeAll { it.id == IdTables.IGS_ASSEMBLY }
        val back = Board.fromJson(JSONObject(old.toJsonString()))
        val ids = back.statuses(BoardStatus.ID).map { it.id }
        assertEquals(ids.indexOf(IdTables.IGS_WORK) + 1, ids.indexOf(IdTables.IGS_ASSEMBLY))
        assertEquals(1, ids.count { it == IdTables.IGS_ASSEMBLY })
        assertEquals(ids, Board.fromJson(JSONObject(back.toJsonString())).statuses(BoardStatus.ID).map { it.id })
    }

    @Test
    fun ptoListManualAndExcel() {
        val b = sample()
        // ПТО — закрывается сразу при передаче.
        val p = BoardVedom(0f, 0f, 1f, Vedoms.PTO, "П-1").also { b.vedoms += it }
        Vedoms.add(b, p, b.recs.take(2))
        Vedoms.give(b, p, "2026-11-11", "Владимир Шульмин")
        assertTrue(Vedoms.isClosed(b, p))
        assertTrue(b.recs.take(2).all { it.igs == IdTables.IGS_PTO && it.dpto == "2026-11-11" })
        // Список — статусы не меняет, не закрывается.
        val l = BoardVedom(0f, 0f, 1f, Vedoms.LIST, Vedoms.nextNum(b, Vedoms.LIST)).also { b.vedoms += it }
        Vedoms.add(b, l, b.recs.drop(4).take(3))
        assertEquals("С-1", l.num)
        assertEquals("список", Vedoms.state(b, l))
        // Строку удалили — в ведомости остаётся подпись (шифр, захватка, вид работ).
        val gone = b.recs[4]
        b.recs.remove(gone)
        val it0 = Vedoms.items(b, l)[0]
        assertEquals(null, Vedoms.rec(b, it0))
        assertEquals("КЖ 6.1.1 · захв. 3 · Стены · Опалубка", Vedoms.labelText(b, it0))
        // Опись в Excel — правильный XML, шапка, строки, фильтр со строки заголовка.
        val v = BoardVedom(0f, 0f, 1f, Vedoms.CUST, "З-7").also { it.to = "Технадзор"; b.vedoms += it }
        Vedoms.add(b, v, b.recs.drop(8).take(5))
        Vedoms.give(b, v, "2026-11-10", "Владимир Шульмин")
        Vedoms.answer(b, v, Vedoms.items(b, v)[0], Vedoms.OK, "2026-11-11", "Владимир Шульмин")
        val out = ByteArrayOutputStream()
        IdExcel.writeVedom(b, v, out, "2026-11-12")
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for ((k, x) in files) assertNotNull(k, db.parse(ByteArrayInputStream(x.toByteArray(Charsets.UTF_8))))
        val sh = files["xl/worksheets/sheet1.xml"]!!
        assertTrue(sh.contains("Ведомость З-7 — На подпись заказчику") && sh.contains("Кому: Технадзор") && sh.contains("Подписано") && sh.contains("Ждём"))
        assertEquals("шапка 5 + пустая + заголовок + 5 схем", 12, Regex("<row ").findAll(sh).count())
        assertTrue(sh.contains("№ схемы") && sh.contains("№ АОСР"))
        assertTrue(sh.contains("<autoFilter ref=\"A7:M12\"/>"))
        assertTrue(files["xl/workbook.xml"]!!.contains("'!\$A\$7:\$M\$12"))
        // Сводка в Excel — открытые ведомости.
        val sum = ByteArrayOutputStream()
        IdExcel.write(b, IdExcel.allFilter(b), setOf(IdExcel.SHEET_SUMMARY), sum, "2026-11-12")
        ZipInputStream(ByteArrayInputStream(sum.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; val x = z.readBytes().toString(Charsets.UTF_8); if (e.name.endsWith("sheet1.xml")) assertTrue(x.contains("Открытые ведомости") && x.contains("З-7")) }
        }
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

    /** Ищут место для новой ведомости (0.25): подержать палец (sx, sy) → «Вставить ведомость сюда». */
    private fun placeHere(s: Screen, sx: Float = s.view.width * 0.6f, sy: Float = s.view.height * 0.3f) {
        assertTrue("ищут место для ведомости", s.a.placingNow)
        pause(1000) // перо рядом недавно — палец был бы ладонью
        val d = SystemClock.uptimeMillis()
        s.view.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, sx, sy, MotionEvent.TOOL_TYPE_FINGER))
        pause(700)
        s.view.dispatchTouchEvent(event(d, d + 700, MotionEvent.ACTION_UP, sx, sy, MotionEvent.TOOL_TYPE_FINGER))
        idle()
        s.a.placePopup!!.contentView.findViewWithTag<View>("place_here").performClick()
        idle()
    }

    private class Screen(val a: BoardActivity, val view: BoardView) {
        val b get() = a.board()
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        fun vcell(v: BoardVedom, i: BoardVItem, col: String): Pair<Float, Float> {
            val c = view.vedomDraw.cellRect(b, v, i, col, RectF())!!
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
    fun screenCollectGiveAnswerByPartsHistory() {
        val s = open(sample())
        val root = s.a.window.decorView
        s.view.fitRect(0f, 0f, 2600f, 1400f)
        idle()
        pause(1000)

        // «Ведомости» → «+ Новая ведомость» → «На подпись заказчику» → номер, кому → «Создать» → сразу сбор схем.
        root.findViewWithTag<View>("board_vedoms").performClick()
        latest().window!!.decorView.findViewWithTag<View>("vlist_new").performClick()
        idle()
        latest().window!!.decorView.findViewWithTag<View>("vnew_kind_0").performClick()
        idle()
        val det = latest()
        assertEquals("З-1", det.window!!.decorView.findViewWithTag<EditText>("vnew_num").text.toString())
        det.window!!.decorView.findViewWithTag<EditText>("vnew_to").setText("Технадзор заказчика")
        det.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        // Место ведомости (0.25): подержать палец там, где поставить → «Вставить ведомость сюда».
        assertTrue(s.b.vedoms.isEmpty())
        placeHere(s, s.view.width * 0.75f, s.view.height * 0.2f)
        val v = s.b.vedoms.single()
        assertEquals(s.view.wx(s.view.width * 0.75f), v.x, 0.5f)
        assertEquals(Vedoms.CUST, v.kind)
        // Сбор: отбор «В работе» и «Возврат» уже выбран — 15 схем, все отмечены.
        val col = latest()
        val cv = col.window!!.decorView
        assertTrue(cv.findViewWithTag<TextView>("col_count").text.toString(), cv.findViewWithTag<TextView>("col_count").text.toString().contains("15 из 15"))
        assertTrue(cv.findViewWithTag<CheckBox>("col_row_0").isChecked)
        col.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val items = Vedoms.items(s.b, v)
        assertEquals(15, items.size)
        s.view.invalidate()
        shot(s.view, "vedom-draft.png")

        // В сборке: схемы — «Сборка», кнопка состояния в шапке — «Сборка ▾».
        assertTrue(items.all { Vedoms.rec(s.b, it)!!.igs == IdTables.IGS_ASSEMBLY })
        // «На подписи у заказчика →» внизу ведомости → окно с датой → «Унесли заказчику»: всем статус и дата.
        val vb = s.view.vedomDraw.bounds(s.b, v, RectF())
        val lay = s.view.vedomDraw.layout(s.b, v)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, s.sx(v.x + (lay.giveX + 20f) * v.scale), s.sy(vb.bottom - 10f * v.scale))
        latest().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val today = IdTables.today()
        assertEquals(today, v.given)
        val recs = items.map { Vedoms.rec(s.b, it)!! }
        assertTrue(recs.all { it.igs == IdTables.IGS_CUST && it.dcust == today })

        // День 1: пальцем по «Ответ» у строки — список рядом → «Подписано» (8 строк по одной).
        s.a.vedomUi.answerDate = "2026-11-11"
        for (i in 0 until 8) {
            val (x, y) = s.vcell(v, Vedoms.items(s.b, v)[i], VedomDraw.RES)
            tap(s.view, MotionEvent.TOOL_TYPE_FINGER, x, y)
            assertTrue("список ответов у ячейки", s.a.vedomUi.popup?.isShowing == true)
            s.a.vedomUi.popup!!.contentView.findViewWithTag<View>("res_ok").performClick()
            idle()
        }
        assertEquals(8, items.count { it.res == Vedoms.OK })
        // День 2: обвели пером строки 9–12 → «Ответ…» → «Подписано»; 13–14 — «Замечания».
        s.a.vedomUi.answerDate = "2026-11-12"
        root.findViewWithTag<View>("board_tool_select").performClick()
        fun lassoRows(from: Int, to: Int) {
            s.view.clearSelection() // внутри выделенного «Выделить» двигает, а не обводит
            val r1 = s.view.vedomDraw.rowRect(s.b, v, Vedoms.items(s.b, v)[from], RectF())!!
            val r2 = s.view.vedomDraw.rowRect(s.b, v, Vedoms.items(s.b, v)[to], RectF())!!
            lasso(s.view, s.sx(v.x + 3f), s.sy(r1.top + 3f), s.sx(v.x + 30f * v.scale), s.sy(r2.bottom - 3f))
        }
        lassoRows(8, 11)
        assertEquals(4, s.view.selection.count { it is BoardVItem })
        root.findViewWithTag<View>("sel_vanswer").performClick()
        s.a.vedomUi.popup!!.contentView.findViewWithTag<View>("res_ok").performClick()
        idle()
        lassoRows(12, 13)
        root.findViewWithTag<View>("sel_vanswer").performClick()
        s.a.vedomUi.popup!!.contentView.findViewWithTag<View>("res_fix").performClick()
        idle()
        s.view.clearSelection()
        assertEquals("15 схем · подписано 12 · замечания 2 · ждём 1", Vedoms.summary(s.b, v, today).substringBefore(" ("))
        // В таблице: подписанные — «Сдано в ПТО» с датой ответа, замечания — «Возврат на корректировку», одна ждёт.
        assertTrue(recs.take(8).all { it.igs == IdTables.IGS_PTO && it.dpto == "2026-11-11" })
        assertTrue(recs.subList(8, 12).all { it.igs == IdTables.IGS_PTO && it.dpto == "2026-11-12" })
        assertTrue(recs.subList(12, 14).all { it.igs == IdTables.IGS_BACK })
        assertEquals(IdTables.IGS_CUST, recs[14].igs)
        s.view.invalidate()
        shot(s.view, "vedom-answers.png")

        // Пальцем по строке — карточка позиции; «↓» — следующая; «Показать в таблице» — схема подсвечена, «← К ведомости».
        root.findViewWithTag<View>("board_tool_hand").performClick()
        pause(1000)
        val (nx, ny) = s.vcell(v, Vedoms.items(s.b, v)[14], TableCol.ZAH)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, nx, ny)
        val card = s.a.vedomUi.card!!
        assertTrue((card.window!!.decorView.findViewWithTag<TextView>("card_title")).text.toString().contains("15 из 15"))
        card.window!!.decorView.findViewWithTag<View>("card_show").performClick()
        idle()
        assertEquals(setOf(recs[14]), s.view.markedRecs)
        assertNotNull(root.findViewWithTag<View>("back_vedom"))
        shot(s.view, "vedom-show-in-table.png")
        root.findViewWithTag<View>("back_vedom").performClick()
        idle()

        // История схемы: кто и когда, по какой ведомости.
        s.a.vedomUi.historyDialog(recs[0])
        idle()
        val hv = latest().window!!.decorView
        val first = (hv.findViewWithTag<View>("hist_0") as android.view.ViewGroup)
        val whoLine = (first.getChildAt(0) as TextView).text.toString()
        val what = (first.getChildAt(1) as TextView).text.toString()
        assertTrue(whoLine, whoLine.contains("Владимир Шульмин"))
        assertTrue(what, what.contains("З-1"))
        latest().dismiss()

        // Строки таблицы → «В ведомость» → новая (список) — состав виден; удалить ведомость — с подтверждением; «Отменить».
        val t = s.b.tables.single()
        s.view.select(IdTables.allRows(s.b, t).takeLast(1))
        idle()
        root.findViewWithTag<View>("sel_tovedom").performClick()
        idle()
        latest().window!!.decorView.findViewWithTag<View>("vnew_kind_3").performClick() // «Список»
        idle()
        latest().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        placeHere(s, s.view.width * 0.4f, s.view.height * 0.8f)
        val list = s.b.vedoms.first { it.kind == Vedoms.LIST }
        assertEquals(1, Vedoms.items(s.b, list).size)
        s.view.select(listOf(list))
        idle()
        root.findViewWithTag<View>("sel_delete").performClick()
        assertEquals("ещё не удалена", 2, s.b.vedoms.size)
        latest().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, s.b.vedoms.size)
        root.findViewWithTag<View>("board_undo").performClick()
        idle()
        assertEquals(2, s.b.vedoms.size)
        s.a.finish()
    }

    @Test
    fun screenManualEntryCreatesMissingRow() {
        val s = open(sample())
        s.view.fitRect(0f, 0f, 2600f, 1400f)
        idle()
        val v = BoardVedom(1500f, 100f, 1.5f, Vedoms.AN, "А-1")
        s.b.vedoms += v
        // Есть схема — добавляется ссылкой (обе схемы захватки, вид работ не выбран).
        s.a.vedomUi.resolveManual(v, "кж 6.1.1", "3", "", emptyList()) {}
        idle()
        assertEquals(2, Vedoms.items(s.b, v).size)
        // Нет схемы — после подтверждения создаётся строка в таблице шифра.
        val before = s.b.recs.size
        s.a.vedomUi.resolveManual(v, "КЖ 6.1.1", "9", "", listOf("Бетон")) {}
        idle()
        assertEquals("без подтверждения ничего не создаётся", before, s.b.recs.size)
        latest().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(before + 1, s.b.recs.size)
        assertEquals("9 Бетон", s.b.recs.last().let { it.zah + " " + it.work })
        assertEquals(3, Vedoms.items(s.b, v).size)
        // Нет и таблицы — предложит создать таблицу.
        s.a.vedomUi.resolveManual(v, "КЖ 4 Лотки", "Лоток №1", "", emptyList()) {}
        idle()
        latest().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(2, s.b.tables.size)
        assertEquals("Лоток №1", s.b.recs.last().zah)
        assertEquals(4, Vedoms.items(s.b, v).size)
        // Передать АН → согласовано → «Согласованные → заказчику».
        // Кнопка состояния в шапке: касание → «Сборка» / «На подписи АН» → сразу, дата — сегодня (0.25).
        val hb = s.view.vedomDraw.bounds(s.b, v, RectF())
        s.view.fitRect(hb.left, hb.top, hb.right, hb.bottom); idle()
        assertTrue(s.view.vedomDraw.layout(s.b, v).state.startsWith("Сборка"))
        val hh = s.view.vedomDraw.hit(s.b, v, hb.right - 30f * v.scale, v.y + 22f * v.scale)!!
        assertEquals(VedomHit.STATE, hh.part)
        s.a.vedomUi.onTap(v, hh, pen = false)
        idle()
        latest().window!!.decorView.findViewWithTag<View>("vstate_give").performClick()
        idle()
        assertTrue(!v.isDraft)
        assertEquals(IdTables.today(), v.given)
        assertTrue(Vedoms.items(s.b, v).mapNotNull { Vedoms.rec(s.b, it) }.all { it.igs == IdTables.IGS_AN })
        s.view.invalidate()
        shot(s.view, "vedom-an-given.png")
        s.a.vedomUi.answerDate = "2026-11-11"
        s.a.vedomUi.setAnswer(v, Vedoms.items(s.b, v), Vedoms.OK)
        idle()
        assertTrue(Vedoms.isClosed(s.b, v))
        latest().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() // «Собрать»
        idle()
        placeHere(s)
        val cust = s.b.vedoms.first { it.kind == Vedoms.CUST }
        assertEquals(4, Vedoms.items(s.b, cust).size)
        assertTrue(cust.isDraft)
        s.view.invalidate()
        shot(s.view, "vedom-an-to-customer.png")
        s.a.finish()
    }
}
