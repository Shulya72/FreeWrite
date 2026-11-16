package party.shulya.freewrite

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Порядок схем в ведомости (0.27.2, Владимир: «в ведомости сборки упорядочивание по шифрам от меньшего к большему, как
 * везде в таблицах»): шифры «по-человечески» (6.1.1 → 6.1.2 → 6.1.10), внутри шифра — как строки в таблице; порядок
 * добавления не важен; удалённые строки — на месте своего шифра; на другом планшете порядок тот же.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VedomOrderTest {

    private class Sample(val b: Board, val v: BoardVedom, val rows: Map<String, BoardRec>)

    /** Три шифра (таблицы стоят не по порядку), в «КЖ 6.1.1» — подразделы «Несущие» и «Ненесущие». */
    private fun sample(): Sample {
        val b = Board.create("Порядок")
        val rows = LinkedHashMap<String, BoardRec>()
        fun table(title: String, x: Float, vararg zah: String): BoardTable {
            val t = BoardTable(x, 100f, 1f, title)
            b.tables += t
            for ((k, z) in zah.withIndex()) rows["$title/$z"] = BoardRec(t.id, k + 1.0).apply { this.zah = z; work = "Бетон"; igs = IdTables.IGS_WORK }.also { b.recs += it }
            return t
        }
        table("КЖ 6.1.10", 0f, "1")
        table("КЖ 6.1.2", 1500f, "1", "2")
        // Раздел «КЖ 6.1.1» с двумя таблицами-подразделами: полное имя — «КЖ 6.1.1 · Несущие».
        b.frames += BoardFrame(2900f, 0f, 3000f, 900f, "КЖ 6.1.1")
        table("Несущие", 3000f, "1", "2", "10")
        table("Ненесущие", 4500f, "1")
        val v = BoardVedom(0f, 1200f, 1f, Vedoms.CUST, "З-1").apply { made = "2026-11-16" }
        b.vedoms += v
        // Добавляем вразнобой, по одной — как ставят «Сборку» в разных таблицах в разные дни.
        for (k in listOf("КЖ 6.1.10/1", "Несущие/10", "КЖ 6.1.2/2", "Ненесущие/1", "Несущие/1", "КЖ 6.1.2/1", "Несущие/2"))
            Vedoms.add(b, v, listOf(rows.getValue(k)), "Владимир")
        return Sample(b, v, rows)
    }

    private fun order(b: Board, v: BoardVedom) = Vedoms.items(b, v).map { i ->
        val l = Vedoms.label(b, i)
        "${l.title}/${l.zah}"
    }

    @Test
    fun cipherThenTableRowOrder() {
        val s = sample()
        assertEquals(
            listOf("КЖ 6.1.1 · Ненесущие/1", "КЖ 6.1.1 · Несущие/1", "КЖ 6.1.1 · Несущие/2", "КЖ 6.1.1 · Несущие/10",
                "КЖ 6.1.2/1", "КЖ 6.1.2/2", "КЖ 6.1.10/1"),
            order(s.b, s.v))
        // На доске и в Excel — тот же порядок и номера по порядку.
        val lay = VedomDraw().layout(s.b, s.v, "2026-11-16")
        assertEquals(Vedoms.items(s.b, s.v), lay.items)
        assertEquals((1..7).map { it.toString() }, lay.texts.map { it[0] })
        assertEquals("КЖ 6.1.1 · Ненесущие", lay.texts[0][1])
        assertEquals("КЖ 6.1.10", lay.texts[6][1])
    }

    @Test
    fun followsRowOrderInTable() {
        val s = sample()
        // В таблице «Несущие» строку захватки 10 подняли наверх — в ведомости она тоже первая в своём шифре.
        s.rows.getValue("Несущие/10").o = 0.5
        assertEquals(listOf("КЖ 6.1.1 · Ненесущие/1", "КЖ 6.1.1 · Несущие/10", "КЖ 6.1.1 · Несущие/1", "КЖ 6.1.1 · Несущие/2"),
            order(s.b, s.v).take(4))
    }

    @Test
    fun deletedRowKeepsItsCipherPlace() {
        val s = sample()
        // Строку «КЖ 6.1.2 / 1» удалили из таблицы — позиция остаётся среди «КЖ 6.1.2» (по подписи), после живых.
        s.b.recs.remove(s.rows.getValue("КЖ 6.1.2/1"))
        assertEquals(listOf("КЖ 6.1.2/2", "КЖ 6.1.2/1", "КЖ 6.1.10/1"), order(s.b, s.v).takeLast(3))
        // Позиция без шифра (строки нет и подписи нет) — в самом конце.
        val lost = BoardVItem(s.v.id, "нет-такой", 0.0)
        s.b.vitems.add(0, lost)
        assertEquals(lost, Vedoms.items(s.b, s.v).last())
    }

    @Test
    fun sameOrderOnAnotherTablet() {
        val s = sample()
        val back = Board.fromJson(JSONObject(s.b.toJsonString()))
        val v2 = back.vedoms.first { it.id == s.v.id }
        assertEquals(Vedoms.items(s.b, s.v).map { it.id }, Vedoms.items(back, v2).map { it.id })
        // Порядок позиций в файле доски не важен.
        back.vitems.reverse()
        assertEquals(Vedoms.items(s.b, s.v).map { it.id }, Vedoms.items(back, v2).map { it.id })
    }
}
