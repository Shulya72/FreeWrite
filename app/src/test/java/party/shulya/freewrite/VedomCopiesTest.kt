package party.shulya.freewrite

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.28.1, Владимир: «на доске снова слетела ведомость на подписи у заказчика от 18.11.26 — теперь там сборка от 17.11.26».
 * После повтора номеров 0.27.5 у ведомости бывают копии (тот же номер и место): переданная и старая, ещё в сборке. Починка
 * 0.27.6 оставляла копию, к которой привязаны схемы, и убирала другую — даже переданную. Здесь: остаётся копия, чьё
 * состояние совпадает с историей схем; ведомости «в сборке», переданной по истории, возвращается дата передачи.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VedomCopiesTest {

    private fun at(d: Int, h: Int) = java.util.Calendar.getInstance().apply { set(2026, 10, d, h, 0, 0) }.timeInMillis

    /** Таблица с 4 схемами; ведомость заказчику «З-14»: собрана 17-го, унесли заказчику 18-го. */
    private fun sample(): Board {
        val b = Board.create("ИГС")
        val t = BoardTable(100f, 100f, 1.5f, TableKinds.KJ_VERT.name).apply { kind = TableKinds.KJ_VERT.id }
        b.tables += t
        var o = 1.0
        for (z in listOf("1", "2")) for (w in listOf("Опалубка", "Бетон")) b.recs += BoardRec(t.id, o++).apply {
            zah = z; work = w; igs = IdTables.IGS_WORK
        }
        val v = BoardVedom(2000f, 100f, 1.5f, Vedoms.CUST, "З-14").apply { made = "2026-11-17" }
        b.vedoms += v
        Vedoms.add(b, v, b.recs, "Владимир", at(17, 10))
        Vedoms.give(b, v, "2026-11-18", "Владимир", at(18, 9))
        return b
    }

    /** Копия ведомости в том виде, какой она была 17-го (в сборке), с другим номером предмета. */
    private fun staleCopy(v: BoardVedom) = Vedoms.parseVedom(JSONObject(Vedoms.vedomJson(v))).apply { id = Ids.next(); given = "" }

    private fun reread(b: Board) = Board.fromJson(JSONObject(b.toJsonString()))

    /** Прочитать доску (сама ничего не исправляет — 0.28.2) и нажать «Исправить». */
    private fun fixed(b: Board): Board {
        val back = reread(b)
        assertEquals("чтение ничего не меняет", b.toJsonString(withCamera = false), back.toJsonString(withCamera = false))
        val p = BoardRepair.check(back)
        assertTrue("есть что исправить", !p.isEmpty)
        BoardRepair.apply(back, p)
        assertTrue("второй раз — нечего", BoardRepair.check(back).isEmpty)
        return back
    }

    private fun assertGivenWithAll(b: Board) {
        assertEquals("одна ведомость", 1, b.vedoms.size)
        val v = b.vedoms[0]
        assertEquals("на подписи у заказчика от 18.11", "2026-11-18", v.given)
        assertEquals("Сборка от 17.11 не вернулась", "2026-11-17", v.made)
        assertEquals(4, Vedoms.items(b, v).size)
        assertEquals(4, b.vitems.size)
        assertTrue(b.recs.all { it.igs == IdTables.IGS_CUST && it.dcust == "2026-11-18" })
        assertTrue(Vedoms.stateChip(b, v).startsWith("На подписи у заказчика от 18.11.26"))
    }

    @Test
    fun staleAssemblyCopyWithSchemesLosesToGiven() {
        for (staleFirst in listOf(false, true)) {
            val b = sample()
            val v = b.vedoms[0]
            val s = staleCopy(v)
            if (staleFirst) b.vedoms.add(0, s) else b.vedoms += s
            for (i in b.vitems) i.v = s.id // схемы привязаны к старой копии — так у Владимира
            val back = fixed(b)
            assertGivenWithAll(back)
            assertEquals("осталась переданная копия", v.id, back.vedoms[0].id)
        }
    }

    @Test
    fun sameSurvivorWhateverCopyHoldsSchemes() {
        val b1 = sample()
        val s = staleCopy(b1.vedoms[0])
        b1.vedoms += s
        val b2 = Board.fromJson(JSONObject(b1.toJsonString()))
        for (i in b2.vitems) i.v = s.id
        val r1 = fixed(b1)
        val r2 = fixed(b2)
        assertEquals(r1.vedoms[0].id, r2.vedoms[0].id)
        assertGivenWithAll(r1)
        assertGivenWithAll(r2)
    }

    @Test
    fun givenDateComesBackFromHistory() {
        // Переданную копию уже убрали (0.28.0 и раньше) — осталась «Сборка от 17.11» со схемами.
        val b = sample()
        b.vedoms[0].given = ""
        val back = fixed(b)
        assertGivenWithAll(back)
        // Правка уходит коллегам: у ведомости новая отметка.
        back.stamp("tab", at(20, 12))
        val rev = back.sync.objs[back.vedoms[0].id]!!.rev
        assertTrue(rev, rev.contains(".tab"))
    }

    @Test
    fun returnedToAssemblyStaysAssembly() {
        // Честно вернули в сборку («ошиблись с «Унесли»»): старая переданная копия не перебивает.
        val b = sample()
        val v = b.vedoms[0]
        val stale = Vedoms.parseVedom(JSONObject(Vedoms.vedomJson(v))).apply { id = Ids.next() } // ещё «унесли 18-го»
        Vedoms.ungive(b, v, "Владимир", at(18, 15))
        b.vedoms += stale
        val back = fixed(b)
        assertEquals(1, back.vedoms.size)
        assertEquals("", back.vedoms[0].given)
        assertEquals(4, Vedoms.items(back, back.vedoms[0]).size)
        assertTrue(back.recs.all { it.igs == IdTables.IGS_ASSEMBLY && it.dcust.isEmpty() })
        // И одна — тоже остаётся в сборке: исправлять нечего.
        assertTrue(BoardRepair.check(reread(back)).isEmpty)
    }

    @Test
    fun assemblyWithoutGiveHistoryIsLeftAlone() {
        // Дата у схем есть, а передачи в этой ведомости по истории нет — ничего не угадываем.
        val b = Board.create("ИГС")
        val t = BoardTable(100f, 100f, 1.5f, "Стены").apply { kind = TableKinds.KJ_VERT.id }
        b.tables += t
        for (k in 1..3) b.recs += BoardRec(t.id, k.toDouble()).apply { zah = "$k"; work = "Бетон"; igs = IdTables.IGS_WORK }
        val v = BoardVedom(2000f, 100f, 1.5f, Vedoms.CUST, "З-15").apply { made = "2026-11-19" }
        b.vedoms += v
        Vedoms.add(b, v, b.recs, "Владимир", at(19, 10))
        for (r in b.recs) r.dcust = "2026-11-18"
        assertTrue(BoardRepair.check(b).isEmpty)
        // Схемы унесли в другой ведомости (З-14) — к З-15 это отношения не имеет.
        for (r in b.recs) b.events += BoardEvent(r.id, at(19, 12), "Владимир", TableCol.DCUST, "", "2026-11-19", "З-14", "")
        assertTrue(BoardRepair.check(b).isEmpty)
        assertEquals("", b.vedoms[0].given)
    }

    @Test
    fun copiesWithSchemesSplitMergeWithoutDoubles() {
        val b = sample()
        val v = b.vedoms[0]
        val s = staleCopy(v)
        b.vedoms += s
        // На другом планшете вторая копия собрала свои позиции тех же схем; одна с ответом.
        val first = b.vitems.first()
        for ((k, i) in b.vitems.toList().withIndex()) if (k < 2) b.vitems += BoardVItem(s.id, i.r, i.o).apply {
            if (k == 0) { res = Vedoms.OK; rd = "2026-11-19" }
        }
        val back = fixed(b)
        assertEquals(1, back.vedoms.size)
        assertEquals("2026-11-18", back.vedoms[0].given)
        assertEquals("по позиции на схему", 4, Vedoms.items(back, back.vedoms[0]).size)
        assertEquals(Vedoms.OK, Vedoms.items(back, back.vedoms[0]).first { it.r == first.r }.res)
        assertEquals("лишние позиции не удаляются — просто не видны", 6, back.vitems.size)
    }

    @Test
    fun staleCopyFromServerDoesNotTurnBackToAssembly() {
        // Планшет: ведомость передана 18-го, всё отмечено. С сервера приходит старая копия «в сборке» и схемы — к ней
        // (так делал телефон со старой копией доски). После приёма — по-прежнему «На подписи у заказчика от 18.11».
        val b = sample()
        b.sync.remoteKey = "k"
        b.stamp("tab", at(18, 10))
        val v = b.vedoms[0]
        val s = staleCopy(v)
        val now = at(20, 9)
        val rows = ArrayList<Remote.Row>()
        rows += Remote.Row(s.id, "%013d.%04d.%s".format(now, 0, "phone"), "vedom", Vedoms.vedomJson(s), 1)
        for ((k, i) in b.vitems.withIndex()) {
            val c = Vedoms.parseItem(JSONObject(Vedoms.itemJson(i))).apply { id = i.id; this.v = s.id }
            rows += Remote.Row(i.id, "%013d.%04d.%s".format(now, k + 1, "phone"), "vitem", Vedoms.itemJson(c), 2L + k)
        }
        val n = Remote.apply(b, rows)
        assertTrue(n > 0)
        assertEquals("само ничего не исправляет", 2, b.vedoms.size)
        BoardRepair.run(b)
        assertGivenWithAll(b)
        // Своё уйдёт следующим кругом: сборная копия удалена, схемы — снова к переданной.
        b.stamp("tab", at(20, 10))
        val pending = Remote.pending(b).map { it.id }.toSet()
        assertTrue(s.id in pending)
        assertTrue(b.vitems.all { it.id in pending })
    }

    @Test
    fun hiddenSchemesComeBackIntoVedomWithSchemes() {
        // Как у Владимира после 0.28.1: «На подписи у заказчика от 18.11» вернулась, но не все схемы — две схемы добавили
        // 18-го в переданную копию, её убрала починка 0.27.6, их позиции остались привязаны к пропавшей ведомости.
        val b = sample()
        val v = b.vedoms[0]
        val t = b.tables[0]
        val more = listOf(BoardRec(t.id, 5.0).apply { zah = "3"; work = "Опалубка"; igs = IdTables.IGS_WORK },
            BoardRec(t.id, 6.0).apply { zah = "3"; work = "Бетон"; igs = IdTables.IGS_WORK })
        b.recs += more
        val gone = BoardVedom(v.x, v.y, v.scale, Vedoms.CUST, v.num).apply { made = v.made }
        b.vedoms += gone
        Vedoms.add(b, gone, more, "Владимир", at(18, 8))
        Vedoms.give(b, gone, "2026-11-18", "Владимир", at(18, 9))
        b.vedoms.remove(gone) // убрали «пустую копию»
        assertEquals(4, Vedoms.items(b, v).size)
        val p = BoardRepair.check(b)
        assertEquals(listOf("Ведомость «З-14»: вернуть 2 схемы — по истории они лежат в этой ведомости, но не видны."), p.lines)
        BoardRepair.apply(b, p)
        assertEquals(6, Vedoms.items(b, v).size)
        assertTrue(b.recs.all { it.igs == IdTables.IGS_CUST && it.dcust == "2026-11-18" })
        assertTrue(BoardRepair.check(b).isEmpty)
    }

    @Test
    fun linesSayWhatWillBeDone() {
        val b = sample()
        val v = b.vedoms[0]
        val s = staleCopy(v)
        b.vedoms += s
        for (i in b.vitems) i.v = s.id
        val p = BoardRepair.check(b)
        assertEquals(listOf("Ведомость «З-14» лежит на доске двумя копиями друг на друге («На подписи у заказчика от 18.11.26» и " +
            "«Сборка от 17.11.26») — оставить одну, «На подписи у заказчика от 18.11.26», схемы всех копий — в ней."), p.lines)
        // Только описание — доска не тронута.
        assertEquals(2, b.vedoms.size)
        assertTrue(b.vitems.all { it.v == s.id })
        // Осталась одна «Сборка» — предлагается вернуть дату.
        b.vedoms.remove(v)
        assertEquals(listOf("Ведомость «З-14»: на доске «Сборка от 17.11.26», а по истории схем её унесли заказчику 18.11.26 — " +
            "вернуть «На подписи у заказчика от 18.11.26»."), BoardRepair.check(b).lines)
    }
}
