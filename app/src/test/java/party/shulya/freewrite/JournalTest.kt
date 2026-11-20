package party.shulya.freewrite

import android.content.Intent
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowPopupMenu

/**
 * Журнал на сервере (0.28.3, Владимир: «журнал на сервере делай»): прежние версии предметов хранятся 30 дней, в приложении —
 * «⋮ → Журнал изменений»: кто что изменил или удалил, «Вернуть как было» — обычной правкой, уходит всем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JournalTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    @After
    fun tearDown() { RemoteSync.testTransport = null }

    private fun at(d: Int, h: Int) = java.util.Calendar.getInstance().apply { set(2026, 10, d, h, 0, 0) }.timeInMillis

    /** Доска с таблицей (2 схемы) и ведомостью «З-14», переданной 18-го; выложена на сервер. */
    private fun shared(t: FakeServer): Board {
        val b = Board.create("План ИГС")
        val tb = BoardTable(100f, 100f, 1.5f, "Стены").apply { kind = TableKinds.KJ_VERT.id }
        b.tables += tb
        for (k in 1..2) b.recs += BoardRec(tb.id, k.toDouble()).apply { zah = "$k"; work = "Бетон"; igs = IdTables.IGS_WORK }
        val v = BoardVedom(2000f, 100f, 1.5f, Vedoms.CUST, "З-14").apply { made = "2026-11-17" }
        b.vedoms += v
        Vedoms.add(b, v, b.recs, "Владимир", at(17, 10))
        Vedoms.give(b, v, "2026-11-18", "Владимир", at(18, 9))
        b.stamp("vlad01", at(18, 10))
        val (key, invite) = Remote.create(t, b.id, b.title, "vlad01")
        b.sync.remoteKey = key
        b.sync.remoteInvite = invite
        Remote.push(t, b.id, key, "vlad01", Remote.pending(b), "Владимир Шульмин")?.let { b.sync.pushedRev = it }
        val p = Remote.pull(t, b.id, key, 0)
        Remote.apply(b, p.rows)
        b.sync.remoteSeq = p.next
        return b
    }

    /** Коллега удалил ведомость (с позициями) — как пришло бы с его телефона. */
    private fun colleagueDeletes(t: FakeServer, b: Board) {
        val v = b.vedoms[0]
        val ch = JSONArray()
        val rev = "%013d.0000.kolya2".format(at(20, 7))
        ch.put(JSONObject().put("id", v.id).put("rev", rev).put("k", "vedom").put("x", 1))
        for ((n, i) in b.vitems.withIndex()) ch.put(JSONObject().put("id", i.id).put("rev", "%013d.%04d.kolya2".format(at(20, 7), n + 1)).put("k", "vitem").put("x", 1))
        t.call(JSONObject().put("op", "push").put("board", b.id).put("key", b.sync.remoteKey).put("device", "kolya2")
            .put("author", "Николай Иванов").put("changes", ch))
    }

    private fun shot(v: android.view.View, name: String) {
        if (v.width <= 0 || v.height <= 0) return
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val dir = java.io.File("build/screenshots").apply { mkdirs() }
        java.io.File(dir, name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun deletedVedomComesBackFromJournal() {
        val t = FakeServer()
        val b = shared(t)
        val vid = b.vedoms[0].id
        colleagueDeletes(t, b)
        val p = Remote.pull(t, b.id, b.sync.remoteKey, b.sync.remoteSeq)
        Remote.apply(b, p.rows)
        b.sync.remoteSeq = p.next
        assertTrue("ведомость пропала у всех", b.vedoms.isEmpty() && b.vitems.isEmpty())

        val h = Remote.history(t, b.id, b.sync.remoteKey)
        assertEquals(3, h.size)
        assertTrue(h.all { it.byDeleted && it.by == "Николай Иванов" && it.author == "Владимир Шульмин" })
        val g = BoardHistory.Group(h[0].at, h[0].by, h)
        assertEquals("удалено: ведомость «З-14» (На подписи у заказчика от 18.11.26), 2 схемы в ведомостях", BoardHistory.summary(b, g))

        assertEquals(3, Remote.restore(b, h))
        assertEquals(vid, b.vedoms.single().id)
        assertEquals("2026-11-18", b.vedoms[0].given)
        assertEquals(2, Vedoms.items(b, b.vedoms[0]).size)
        // Вернули — это правка: уйдёт на сервер и к коллегам.
        b.stamp("vlad01", at(20, 8))
        val pending = Remote.pending(b).map { it.id }.toSet()
        assertTrue(vid in pending)
        Remote.push(t, b.id, b.sync.remoteKey, "vlad01", Remote.pending(b), "Владимир Шульмин")
        assertFalse("на сервере снова есть", t.boards[b.id]!!.objs[vid]!!.deleted)
        // Журнал помнит и удаление, и что было до него; штрихи по умолчанию не приходят.
        assertTrue(Remote.history(t, b.id, b.sync.remoteKey).none { it.kind == "stroke" })
    }

    @Test
    fun journalOnBoardScreen() {
        val t = FakeServer()
        RemoteSync.testTransport = t
        val b = shared(t)
        val vid = b.vedoms[0].id
        BoardStore(ctx).save(b)
        colleagueDeletes(t, b)
        val controller = Robolectric.buildActivity(BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup()
        val a = controller.get()
        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < end) { idle(); if (cond()) return; Thread.sleep(20) }
            idle(); assertTrue("не дождался: $what", cond())
        }
        waitFor("удаление коллеги пришло") { a.board().vedoms.isEmpty() }
        val root = a.window.decorView
        root.findViewWithTag<android.view.View>("board_more").performClick(); idle()
        val pm = ShadowPopupMenu.getLatestPopupMenu()
        val item = pm.menu.findItem(5)
        assertEquals("Журнал изменений", item.title.toString())
        shadowOf(pm).onMenuItemClickListener.onMenuItemClick(item); idle()
        waitFor("журнал загружен") { a.history.listDlg?.isShowing == true }
        val list = a.history.listDlg!!.window!!.decorView
        shot(list, "journal-list.png")
        val row = list.findViewWithTag<android.view.View>("hist_0")
        val text = (0 until (row as android.view.ViewGroup).childCount).joinToString("\n") { (row.getChildAt(it) as android.widget.TextView).text }
        assertTrue(text, text.contains("Николай Иванов") && text.contains("удалено: ведомость «З-14»"))
        row.performClick(); idle()
        val d = a.history.detailDlg!!
        shot(d.window!!.decorView, "journal-details.png")
        assertTrue(shadowOf(d).message.toString().contains("удалено — Ведомость «З-14» (На подписи у заказчика от 18.11.26)"))
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(vid, a.board().vedoms.single().id)
        assertEquals(2, Vedoms.items(a.board(), a.board().vedoms[0]).size)
        waitFor("вернули — ушло на сервер") { t.boards[b.id]!!.objs[vid]?.deleted == false }
        a.undo(); idle()
        assertTrue("«Отменить» — снова без ведомости", a.board().vedoms.isEmpty())
        controller.pause().stop().destroy()
    }
}
