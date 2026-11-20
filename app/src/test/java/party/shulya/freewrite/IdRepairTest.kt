package party.shulya.freewrite

import android.content.Intent
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * Пустая ведомость (Владимир, 19.11: «ведомость от 18-го стала пустая, а в таблицах у схем «На подписи у заказчика от …»»).
 * Причина — повтор номеров предметов в 0.27.5 (ThreadLocalRandom на Android повторяется от запуска к запуску). Здесь:
 * номера не повторяются; при повторе номер сохраняют ведомость и таблица; уже «повисшие» схемы и строки возвращаются
 * при чтении доски; «Вернуть схемы» по истории; дата при «Сдано в ПТО» из таблицы.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdRepairTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
        ctx.getSharedPreferences("igs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Таблица с листом плана и областями, 4 схемы; ведомость заказчику от 18-го — собрана и передана. */
    private fun sample(): Board {
        val b = Board.create("ИГС")
        val t = BoardTable(100f, 100f, 1.5f, TableKinds.KJ_VERT.name).apply { kind = TableKinds.KJ_VERT.id }
        val sh = PlanSheet("plan.png", 800, 600)
        t.sheets += sh
        b.tables += t
        var o = 1.0
        for (z in listOf("1", "2")) for (w in listOf("Опалубка", "Бетон")) b.recs += BoardRec(t.id, o++).apply {
            zah = z; work = w; igs = IdTables.IGS_WORK
        }
        b.zones += BoardZone(t.id, sh.id, "", "1", floatArrayOf(0.1f, 0.1f, 0.3f, 0.1f, 0.3f, 0.3f), 0)
        val v = BoardVedom(2000f, 100f, 1.5f, Vedoms.CUST, "З-14").apply { made = "2026-11-18" }
        b.vedoms += v
        val at18 = java.util.Calendar.getInstance().apply { set(2026, 10, 18, 10, 0) }.timeInMillis
        Vedoms.add(b, v, b.recs, "Владимир", at18)
        Vedoms.give(b, v, "2026-11-18", "Владимир", at18 + 3_600_000)
        return b
    }

    private fun reread(b: Board) = Board.fromJson(JSONObject(b.toJsonString()))

    @Test
    fun idsDoNotRepeat() {
        val seen = HashSet<String>()
        val abc = "abcdefghijkmnpqrstuvwxyz23456789"
        repeat(200_000) {
            val id = Ids.next()
            assertEquals(12, id.length)
            assertTrue(id.all { it in abc })
            assertTrue("повтор номера", seen.add(id))
        }
        assertEquals(6, Ids.next(6).length)
        assertEquals(20, Ids.next(20).length)
    }

    @Test
    fun sameIdAsStrokeKeepsVedomAndTable() {
        val b = sample()
        val v = b.vedoms[0]
        val t = b.tables[0]
        // Как в 0.27.5: новый штрих получил номер ведомости, другой — номер таблицы.
        b.strokes += Drawing("pen", 0, 3f, floatArrayOf(0f, 0f, 0.5f, 10f, 10f, 0.5f)).apply { id = v.id }
        b.strokes += Drawing("pen", 0, 3f, floatArrayOf(5f, 0f, 0.5f, 10f, 10f, 0.5f)).apply { id = t.id }
        val back = reread(b)
        assertEquals("ведомость с прежним номером", v.id, back.vedoms[0].id)
        assertEquals(t.id, back.tables[0].id)
        assertEquals(4, Vedoms.items(back, back.vedoms[0]).size)
        assertEquals(4, IdTables.allRows(back, back.tables[0]).size)
        assertTrue("новый номер — у штрихов", back.strokes.none { it.id == v.id || it.id == t.id })
    }

    @Test
    fun lostItemsAndRowsComeBackOnLoad() {
        val b = sample()
        val v = b.vedoms[0]
        val t = b.tables[0]
        // Как стало у Владимира: ведомость получила новый номер (схемы — к старому), на другом планшете — ещё копия.
        val oldV = v.id
        v.id = Ids.next()
        b.vedoms += Vedoms.parseVedom(JSONObject(Vedoms.vedomJson(v))).apply { id = Ids.next() }
        // Таблица — тоже новый номер (строки и области «висят»), и её пустая копия.
        t.id = Ids.next()
        b.tables += IdTables.parseTable(JSONObject(IdTables.tableJson(t))).apply { id = Ids.next() }
        assertEquals(0, Vedoms.items(b, v).size)
        assertEquals(0, IdTables.allRows(b, t).size)

        val back = reread(b)
        // Само при чтении ничего не исправляет (0.28.2) — только кнопкой «Исправить» в окне «Проверка доски».
        assertEquals(2, back.vedoms.size)
        assertEquals(0, back.vitems.count { i -> back.vedoms.any { it.id == i.v } })
        val plan = BoardRepair.check(back)
        assertEquals(plan.lines.toString(), 4, plan.lines.size) // таблица: строки, пустая копия; ведомость: копии, схемы
        BoardRepair.apply(back, plan)
        assertEquals("копии убраны", 1, back.vedoms.size)
        assertEquals(1, back.tables.size)
        val bv = back.vedoms[0]
        assertEquals("схемы вернулись в ведомость", 4, Vedoms.items(back, bv).size)
        assertEquals(4, IdTables.allRows(back, back.tables[0]).size)
        assertTrue(back.zones.all { it.t == back.tables[0].id })
        assertTrue(back.vitems.none { it.v == oldV })
        // Статусы и даты не тронуты.
        assertTrue(back.recs.all { it.igs == IdTables.IGS_CUST && it.dcust == "2026-11-18" })
        // Второй раз исправлять нечего.
        assertTrue(BoardRepair.check(reread(back)).isEmpty)
    }

    @Test
    fun foreignOrphansStayAlone() {
        // Позиции удалённой ведомости (её номер старше новой ведомости) не прилипают к новой пустой ведомости.
        val b = sample()
        val v = b.vedoms[0]
        b.vedoms.clear() // удалили на другом планшете — позиции остались
        val fresh = BoardVedom(2000f, 100f, 1.5f, Vedoms.CUST, "З-14").apply { made = "2026-11-20" }
        b.vedoms += fresh
        val back = reread(b)
        assertEquals(1, back.vedoms.size)
        assertEquals(0, Vedoms.items(back, back.vedoms[0]).size)
        assertEquals(4, back.vitems.count { it.v == v.id })
    }

    @Test
    fun boardCheckAsksBeforeFixing() {
        // 0.28.2, Владимир: «какого хрена приложение решает, что удалить и что восстановить» — только по кнопке.
        val b = sample()
        b.vedoms[0].id = Ids.next() // схемы «повисли»
        BoardStore(ctx).save(b)
        fun open() = Robolectric.buildActivity(BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get().also { idle() }
        val a = open()
        val d = a.repairDialog!!
        assertTrue(d.isShowing)
        val msg = shadowOf(d).message.toString()
        assertTrue(msg, msg.contains("Ведомость «З-14»: вернуть 4 схемы"))
        assertTrue(msg, msg.contains("Статусы и даты схем не меняются"))
        d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertEquals("«Не сейчас» — ничего не тронуто", 0, Vedoms.items(a.board(), a.board().vedoms[0]).size)
        a.offerRepair(); idle()
        assertFalse("до следующего открытия не спрашивает", a.repairDialog!!.isShowing)
        a.finish(); idle()
        val a2 = open()
        val d2 = a2.repairDialog!!
        assertTrue(d2.isShowing)
        d2.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(4, Vedoms.items(a2.board(), a2.board().vedoms[0]).size)
        assertTrue(a2.board().recs.all { it.igs == IdTables.IGS_CUST && it.dcust == "2026-11-18" })
        a2.undo(); idle()
        assertEquals("«Отменить» — как было", 0, Vedoms.items(a2.board(), a2.board().vedoms[0]).size)
    }

    @Test
    fun restoreFromHistoryOnScreen() {
        val b = sample()
        val v = b.vedoms[0]
        // Позиции удалились совсем (тёзку удалили на другом планшете) — история и статусы остались.
        b.vitems.clear()
        assertEquals(4, Vedoms.lostFromHistory(b, v).size)
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val bv = a.board().vedoms[0]
        val btn = a.vedomUi.selectionButtons(listOf(bv)).firstOrNull { it.tag == "sel_vrestore" }
        assertTrue("кнопка «Вернуть схемы»", btn != null)
        assertEquals("Вернуть схемы (4)", btn!!.text.toString())
        btn.performClick(); idle()
        val d = a.vedomUi.restoreDlg!!
        d.listView.performItemClick(d.listView, 1, 1) // одну не возвращаем
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(3, Vedoms.items(a.board(), bv).size)
        assertTrue(a.board().recs.all { it.igs == IdTables.IGS_CUST && it.dcust == "2026-11-18" })
        assertEquals(1, Vedoms.lostFromHistory(a.board(), bv).size)
        a.undo(); idle()
        assertEquals(0, Vedoms.items(a.board(), a.board().vedoms[0]).size)
        // Убранная из сборки схема не предлагается.
        val b2 = sample()
        val v2 = BoardVedom(2000f, 900f, 1.5f, Vedoms.CUST, "З-15").apply { made = IdTables.today() }
        b2.vedoms += v2
        val r = b2.recs[0]
        r.igs = IdTables.IGS_WORK
        Vedoms.add(b2, v2, listOf(r), "Владимир")
        Vedoms.unassemble(b2, v2, Vedoms.items(b2, v2), "Владимир")
        b2.vitems.removeAll { it.v == v2.id }
        assertEquals(0, Vedoms.lostFromHistory(b2, v2).size)
    }

    @Test
    fun ptoAndCustomerDateFromTableStatus() {
        val b = sample()
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val put = TableUi::class.java.getDeclaredMethod("put", BoardRec::class.java, String::class.java, String::class.java).apply { isAccessible = true }
        val r = a.board().recs[0]
        val today = IdTables.today()
        // «Сдано в ПТО» в таблице — сегодняшняя дата сдачи (и в истории).
        assertEquals("", r.dpto)
        put.invoke(a.tableUi, r, TableCol.IGS, IdTables.IGS_PTO)
        assertEquals(today, r.dpto)
        assertTrue(Vedoms.history(a.board(), r).any { it.k == TableCol.DPTO && it.to == today })
        // Уже вписанная дата остаётся.
        val r2 = a.board().recs[1]
        put.invoke(a.tableUi, r2, TableCol.DPTO, "2026-11-17")
        put.invoke(a.tableUi, r2, TableCol.IGS, IdTables.IGS_PTO)
        assertEquals("2026-11-17", r2.dpto)
        // «На подписи у заказчика» снова (после возврата) — дата передачи сегодняшняя, старая устарела.
        val r3 = a.board().recs[2]
        assertEquals("2026-11-18", r3.dcust)
        put.invoke(a.tableUi, r3, TableCol.IGS, IdTables.IGS_BACK)
        assertEquals("2026-11-18", r3.dcust)
        if (today > "2026-11-18") {
            put.invoke(a.tableUi, r3, TableCol.IGS, IdTables.IGS_CUST)
            assertEquals(today, r3.dcust)
        }
        assertNotEquals("", r3.dcust)
        assertFalse(r3.dpto.isNotEmpty())
    }
}
