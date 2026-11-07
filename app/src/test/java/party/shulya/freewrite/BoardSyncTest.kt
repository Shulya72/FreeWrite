package party.shulya.freewrite

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Общая доска через файл (0.9): два планшета правят одну доску и обмениваются файлами.
 * Проверяем: по каждому предмету побеждает поздняя правка, удаление доходит, ничего не «воскресает»,
 * после обмена туда-обратно доски одинаковые, повторный файл ничего не меняет.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardSyncTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    /** «Передать файл»: доска строкой и разбор на другом планшете (как в файле .freewrite). */
    private fun transfer(b: Board): Board = Board.fromJson(JSONObject(b.toJsonString()))

    private fun stroke(x: Float, y: Float) = Drawing("pen", Ui.INK, 3f, floatArrayOf(x, y, 0.5f, x + 40, y + 10, 0.5f, x + 80, y, 0.5f))

    private fun sample(): Board {
        val b = Board.create("Захватки")
        b.strokes += stroke(10f, 10f)
        b.texts += BoardText(100f, 200f, 24f, "Захватка №1 от 11.09.26", Ui.INK)
        b.stickers += BoardSticker(300f, 300f, "s0", 1f)
        b.callouts += BoardCallout(400f, 100f, 1f, "Захватка №2", "s1").apply { addLeader(380f, 160f) }
        b.fills += BoardFill(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f), Ui.OK)
        b.images += BoardImage("plan-1.jpg", 0f, 400f, 300f, 200f, 1200, 800)
        return b
    }

    @Test
    fun twoTabletsExchangeAndConverge() {
        val a = sample()
        a.stamp("vlad01", 1000)
        val b = transfer(a)
        BoardShare.adopt(b)
        assertEquals("коллега получил ту же доску", a.entryHashes(), b.entryHashes())
        assertEquals("принятая доска не считается своими правками", 0, b.stamp("kolya2", 1001))
        assertEquals("без правок — нечего отмечать", 0, a.stamp("vlad01", 1002))

        // Владимир: двигает надпись, удаляет метку, пишет новую надпись.
        val t1a = a.texts[0]
        t1a.x += 10f
        val stickerId = a.stickers[0].id
        a.stickers.clear()
        a.texts += BoardText(500f, 500f, 24f, "Новое у Владимира", Ui.INK)
        assertEquals(3, a.stamp("vlad01", 2000))

        // Коллега позже: правит ту же надпись, меняет статус выноски, стирает штрих, рисует свой,
        // переименовывает доску и добавляет статус.
        val t1b = b.texts.first { it.id == t1a.id }
        t1b.text = "Захватка №1 — забетонировано"
        b.callouts[0].sid = "s2"
        val strokeId = b.strokes[0].id
        b.strokes.clear()
        b.strokes += stroke(600f, 600f)
        b.title = "Захватки, секция 3"
        b.statuses += BoardStatus("sOpal", "Сняли опалубку", Ui.WARN)
        b.stamp("kolya2", 3000)

        // Файл коллеги → Владимиру.
        val st = BoardShare.merge(a, transfer(b))
        assertEquals("надпись — поздняя правка коллеги", "Захватка №1 — забетонировано", a.texts.first { it.id == t1a.id }.text)
        assertEquals("статус выноски от коллеги", "s2", a.callouts[0].sid)
        assertTrue("стёртый штрих удалён", a.strokes.none { it.id == strokeId })
        assertEquals("новый штрих коллеги пришёл", 1, a.strokes.size)
        assertEquals("Захватки, секция 3", a.title)
        assertTrue("новый статус пришёл", a.statuses.any { it.id == "sOpal" })
        assertTrue("своя удалённая метка не вернулась", a.stickers.none { it.id == stickerId })
        assertTrue("своя новая надпись осталась", a.texts.any { it.text == "Новое у Владимира" })
        assertEquals("новых: штрих и статус", 2, st.added)
        assertEquals("изменено: надпись и выноска", 2, st.changed)
        assertEquals("удалено: штрих", 1, st.removed)
        assertTrue(st.renamed)
        assertEquals("после слияния нечего отмечать заново", 0, a.stamp("vlad01", 3500))

        // Файл Владимира → коллеге: доски сходятся.
        val st2 = BoardShare.merge(b, transfer(a))
        assertEquals("новая надпись Владимира", 1, st2.added)
        assertEquals("удалённая метка", 1, st2.removed)
        assertEquals("доски одинаковые", a.entryHashes(), b.entryHashes())
        assertEquals(0, b.stamp("kolya2", 3600))

        // Повторный (старый) файл ничего не меняет.
        assertEquals(0, BoardShare.merge(a, transfer(b)).total)
        assertEquals(0, BoardShare.merge(b, transfer(a)).total)
    }

    @Test
    fun deleteAgainstEditLaterWins() {
        val a = sample()
        a.stamp("vlad01", 1000)
        val b = transfer(a).also { BoardShare.adopt(it) }
        val id = a.texts[0].id

        // Коллега правит в 4000, Владимир удаляет в 5000 — удаление позже, побеждает.
        b.texts[0].text = "Правка коллеги"; b.stamp("kolya2", 4000)
        a.texts.clear(); a.stamp("vlad01", 5000)
        BoardShare.merge(a, transfer(b))
        assertTrue("удаление позже правки", a.texts.none { it.id == id })
        BoardShare.merge(b, transfer(a))
        assertTrue("удаление дошло до коллеги", b.texts.none { it.id == id })

        // Наоборот: удаление раньше правки — предмет возвращается с правкой.
        val c = sample().also { it.stamp("vlad01", 1000) }
        val d = transfer(c).also { BoardShare.adopt(it) }
        val cid = c.texts[0].id
        c.texts.clear(); c.stamp("vlad01", 4000)
        d.texts[0].text = "Правка позже удаления"; d.stamp("kolya2", 6000)
        BoardShare.merge(c, transfer(d))
        assertEquals("Правка позже удаления", c.texts.first { it.id == cid }.text)
    }

    @Test
    fun clockNeverGoesBackAfterImport() {
        // Часы коллеги спешат на час: своя правка после приёма его файла всё равно считается позже.
        val a = sample().also { it.stamp("vlad01", 1_000_000) }
        val b = transfer(a).also { BoardShare.adopt(it) }
        b.texts[0].text = "Коллега (часы спешат)"; b.stamp("kolya2", 1_000_000 + 3_600_000)
        BoardShare.merge(a, transfer(b))
        a.texts[0].text = "Владимир после приёма"; a.stamp("vlad01", 1_000_100)
        BoardShare.merge(b, transfer(a))
        assertEquals("Владимир после приёма", b.texts[0].text)
    }

    @Test
    fun oldBoardsGetSameIdsOnEveryLoad() {
        // Доска до 0.9: у предметов нет номеров. Два одинаковых штриха — разные номера.
        val legacy = """{"v":2,"kind":"board","id":"old-1","title":"Старая","created":1,"updated":1,
            "statuses":[{"id":"s0","name":"Не сдано","color":-1}],
            "strokes":[{"kind":"pen","color":-16777216,"w":3,"pts":[1,2,0.5,3,4,0.5]},{"kind":"pen","color":-16777216,"w":3,"pts":[1,2,0.5,3,4,0.5]}],
            "texts":[{"x":1,"y":2,"size":24,"text":"Привет","color":-16777216}],
            "stickers":[{"x":5,"y":6,"status":2,"scale":1}]}"""
        val one = Board.fromJson(JSONObject(legacy))
        val two = Board.fromJson(JSONObject(legacy))
        val ids1 = one.entryHashes().keys
        assertEquals("номера одинаковые при каждой загрузке", ids1, two.entryHashes().keys)
        assertTrue(one.strokes.all { it.id.isNotEmpty() })
        assertFalse("у одинаковых штрихов разные номера", one.strokes[0].id == one.strokes[1].id)
        assertEquals("s2", one.stickers[0].sid)
        // Копия предмета — с новым номером.
        assertFalse(one.strokes[0].copy().id == one.strokes[0].id)
    }

    @Test
    fun fileWithPicturesNewBoardThenOnlyChanges() {
        val store = BoardStore(ctx)
        val imgDir = File(ctx.filesDir, "note_images").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val img = BoardImages.importBitmap(ctx, bmp)
        val b = Board.create("Секция 3")
        b.images += BoardImage(img.first, 0f, 0f, 320f, 240f, img.second, img.third)
        b.texts += BoardText(10f, 300f, 24f, "Захватка №1", Ui.INK)
        store.save(b)

        val (all, fresh) = BoardShare.imageSizes(ctx, b)
        assertTrue(all > 0 && fresh == all)
        val p = BoardShare.prepare(b, onlyNew = false)
        assertEquals(listOf(img.first), p.files)
        val file = BoardShare.write(ctx, p)
        assertTrue(file.name.endsWith(".freewrite"))
        b.sync.peerImages += p.files
        assertTrue("второй раз «только новое» — без картинок", BoardShare.prepare(b, onlyNew = true).files.isEmpty())

        // «Другой планшет»: доски и картинки нет.
        store.delete(b.id)
        assertFalse(File(imgDir, img.first).exists())
        val r = BoardShare.import(ctx, file.inputStream())
        assertTrue(r.isNew)
        assertEquals(1, r.imagesCopied)
        assertEquals(0, r.imagesMissing)
        assertTrue(r.message(), r.message().startsWith("Новая доска «Секция 3» добавлена"))
        val got = store.load(b.id)!!
        assertEquals("Захватка №1", got.texts[0].text)
        assertTrue(File(imgDir, img.first).exists())
        assertTrue("открытый экран доски заметит приём", BoardStore.importCount(b.id) >= 1)

        // Своя правка, потом тот же (старый) файл ещё раз — правка остаётся, изменений нет.
        got.texts[0].text = "Захватка №1 — сдано"
        store.save(got)
        val r2 = BoardShare.import(ctx, file.inputStream())
        assertFalse(r2.isNew)
        assertEquals(0, r2.stats.total)
        assertTrue(r2.message().contains("изменений нет"))
        assertEquals("Захватка №1 — сдано", store.load(b.id)!!.texts[0].text)
    }

    @Test
    fun notABoardFileIsRefused() {
        val f = File(ctx.cacheDir, "x.freewrite").apply { writeText("просто текст") }
        val e = runCatching { BoardShare.import(ctx, f.inputStream()) }.exceptionOrNull()
        assertNotNull(e)
        assertEquals(BoardShare.NOT_BOARD, e!!.message)
        assertNull(BoardStore(ctx).list().firstOrNull { it.title == "x" })
    }
}
