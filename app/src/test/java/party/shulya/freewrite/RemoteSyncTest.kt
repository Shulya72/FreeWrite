package party.shulya.freewrite

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/**
 * Общая доска через сервер (0.10): два планшета, один сервер. Сервер — в памяти (FakeServer, правила как
 * у server/index.py) или настоящий в Яндекс Облаке, если сборка дала его адрес (FREEWRITE_SYNC_URL).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteSyncTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @org.junit.Before
    fun named() = Profile.save(ctx, "Владимир", "Шульмин")

    @After
    fun tearDown() {
        RemoteSync.testTransport = null
    }

    /** Один «планшет»: своя доска и свой номер устройства. */
    private class Tablet(val board: Board, val device: String) {
        val name get() = if (device == "vlad01") "Владимир Шульмин" else "Николай Иванов"
    }

    /** Цикл синхронизации, как у RemoteSync (без потоков): отметить, отправить, получить, влить. */
    private fun cycle(t: SyncTransport, tab: Tablet, now: Long): Int {
        val b = tab.board
        b.stamp(tab.device, now)
        val s = b.sync
        Remote.push(t, b.id, s.remoteKey, tab.device, Remote.pending(b), tab.name)?.let { if (it > s.pushedRev) s.pushedRev = it }
        val p = Remote.pull(t, b.id, s.remoteKey, s.remoteSeq)
        val n = Remote.apply(b, p.rows)
        s.remoteSeq = maxOf(s.remoteSeq, p.next)
        return n
    }

    private fun stroke(x: Float) = Drawing("pen", Ui.INK, 3f, floatArrayOf(x, 0f, 0.5f, x + 30, 10f, 0.5f))

    private fun twoTablets(t: SyncTransport, boardId: String = UUID.randomUUID().toString()): Pair<Tablet, Tablet> {
        val a = Board(boardId, "Секция 3", 1, 1).apply { statuses.addAll(Board.defaultStatuses()) }
        a.strokes += stroke(10f)
        a.texts += BoardText(100f, 200f, 24f, "Захватка №1 от 11.09.26", Ui.INK)
        a.callouts += BoardCallout(400f, 100f, 1f, "Захватка №2", "s1").apply { addLeader(380f, 160f) }
        a.statuses[0].name = "Сдали опалубку"
        val va = Tablet(a, "vlad01")
        va.board.stamp(va.device, 1000)
        val (key, invite) = Remote.create(t, a.id, a.title, va.device)
        a.sync.remoteKey = key
        a.sync.remoteInvite = invite
        cycle(t, va, 1001)
        assertTrue("всё своё отправлено", Remote.pending(a).isEmpty())

        val j = Remote.join(t, invite.lowercase())
        assertEquals(a.id, j.boardId)
        // Как «Подключиться по коду»: пустая доска, сначала всё получить и влить, только потом отмечать своё.
        val b = Board(j.boardId, j.title, 2, 2)
        b.sync.remoteKey = j.key
        b.sync.remoteInvite = j.invite
        val first = Remote.pull(t, b.id, j.key, 0)
        Remote.apply(b, first.rows)
        b.sync.remoteSeq = first.next
        val kb = Tablet(b, "kolya2")
        cycle(t, kb, 2000)
        return va to kb
    }

    private fun sameBoards(a: Board, b: Board) {
        assertEquals(a.title, b.title)
        assertEquals(a.entryHashes(), b.entryHashes())
    }

    private fun exchange(t: SyncTransport): Board {
        val (va, kb) = twoTablets(t)
        val a = va.board
        val b = kb.board
        sameBoards(a, b)
        assertEquals("Сдали опалубку", b.statuses[0].name)
        assertEquals("принятое не считается своими правками", 0, b.stamp(kb.device, 2001))
        assertTrue("и не отправляется обратно", Remote.pending(b).isEmpty())

        // Оба правят одновременно.
        val textId = a.texts[0].id
        a.texts[0].x += 50f                                   // Владимир двигает надпись
        a.texts += BoardText(10f, 500f, 24f, "Новое у Владимира", Ui.INK)
        val strokeId = b.strokes[0].id
        b.strokes.clear()                                     // коллега стирает штрих
        b.callouts[0].sid = "s2"                              // и меняет статус выноски
        b.texts.first { it.id == textId }.text = "Захватка №1 — забетонировано" // и чуть позже правит ту же надпись
        b.statuses += BoardStatus("sOpal", "Сняли опалубку", Ui.WARN)
        b.title = "Секция 3, захватки"

        cycle(t, va, 3000)
        cycle(t, kb, 3500)
        cycle(t, va, 4000)

        sameBoards(a, b)
        assertEquals("надпись — поздняя правка коллеги", "Захватка №1 — забетонировано", a.texts.first { it.id == textId }.text)
        assertTrue("штрих стёрт у обоих", a.strokes.none { it.id == strokeId })
        assertEquals("s2", a.callouts[0].sid)
        assertTrue(a.texts.any { it.text == "Новое у Владимира" } && b.texts.any { it.text == "Новое у Владимира" })
        assertTrue(a.statuses.any { it.id == "sOpal" })
        assertEquals("Секция 3, захватки", a.title)
        assertEquals(0, a.stamp(va.device, 4001))
        assertEquals(0, b.stamp(kb.device, 4001))
        assertTrue(Remote.pending(a).isEmpty() && Remote.pending(b).isEmpty())
        // Ещё круг — ничего не меняется.
        assertEquals(0, cycle(t, va, 5000))
        assertEquals(0, cycle(t, kb, 5000))
        return a
    }

    @Test
    fun twoTabletsThroughServer() {
        exchange(FakeServer())
    }

    @Test
    fun authorOfChangesIsKnown() {
        val t = FakeServer()
        val (va, kb) = twoTablets(t)
        assertEquals("коллега знает, кто выложил", "Владимир Шульмин", kb.board.sync.members["vlad01"])
        kb.board.texts += BoardText(1f, 2f, 24f, "От Николая", Ui.INK)
        kb.board.texts[0].text = "Правка Николая"
        kb.board.stamp(kb.device, 3000)
        val s = kb.board.sync
        Remote.push(t, kb.board.id, s.remoteKey, kb.device, Remote.pending(kb.board), kb.name)?.let { s.pushedRev = it }
        val p = Remote.pull(t, va.board.id, va.board.sync.remoteKey, va.board.sync.remoteSeq)
        val authors = HashMap<String, Int>()
        assertEquals(2, Remote.apply(va.board, p.rows, authors))
        assertEquals(mapOf("Николай Иванов" to 2), authors)
        assertEquals("Николай Иванов", va.board.sync.members["kolya2"])
        // Участники сохраняются вместе с доской.
        assertEquals("Николай Иванов", Board.fromJson(org.json.JSONObject(va.board.toJsonString())).sync.members["kolya2"])
    }

    @Test
    fun manyObjectsGoInBatchesAndPages() {
        val t = FakeServer()
        val (va, kb) = twoTablets(t)
        for (i in 0 until 2600) va.board.strokes += stroke(i * 2f)
        cycle(t, va, 3000)
        cycle(t, kb, 3100)
        assertEquals(va.board.strokes.size, kb.board.strokes.size)
        sameBoards(va.board, kb.board)
        assertTrue(Remote.batches(List(2600) { Remote.Change("i$it", "r", "stroke", "{}") }).size >= 3)
    }

    @Test
    fun localNewerEditSurvivesOlderRemote() {
        val t = FakeServer()
        val (va, kb) = twoTablets(t)
        val id = va.board.texts[0].id
        kb.board.texts.first { it.id == id }.text = "Коллега, раньше"
        kb.board.stamp(kb.device, 3000)
        va.board.texts[0].text = "Владимир, позже"
        va.board.stamp(va.device, 3500) // ещё не отправлено
        cycle(t, kb, 3600)  // коллега отправил своё (более раннее)
        cycle(t, va, 3700)  // у Владимира своя правка новее — остаётся и уходит на сервер
        assertEquals("Владимир, позже", va.board.texts[0].text)
        cycle(t, kb, 3800)
        assertEquals("Владимир, позже", kb.board.texts.first { it.id == id }.text)
    }

    @Test
    fun picturesInParts() {
        val t = FakeServer()
        val (va, kb) = twoTablets(t)
        val dir = File(ctx.filesDir, "note_images").apply { mkdirs() }
        val name = "plan-" + UUID.randomUUID() + ".jpg"
        val bytes = ByteArray(Remote.CHUNK * 2 + 777) { (it * 31 % 251).toByte() }
        File(dir, name).writeBytes(bytes)
        val b = va.board
        Remote.uploadImage(t, b.id, b.sync.remoteKey, File(dir, name))
        assertEquals(setOf(name), Remote.imagesOnServer(t, b.id, b.sync.remoteKey, listOf(name, "нет.jpg")))
        val target = File(dir, "copy-$name")
        Remote.downloadImage(t, kb.board.id, kb.board.sync.remoteKey, name, target)
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(3, t.images[name]!!.first)
    }

    @Test
    fun publishAgainFromSameTabletReturnsSameCode() {
        val t = FakeServer()
        val id = UUID.randomUUID().toString()
        val first = Remote.create(t, id, "Секция", "vlad01")
        assertEquals(first, Remote.create(t, id, "Секция", "vlad01"))
        val e = runCatching { Remote.create(t, id, "Секция", "kolya2") }.exceptionOrNull() as SyncException
        assertEquals("exists", e.code)
    }

    @Test
    fun wrongCodeAndNoNetwork() {
        val t = FakeServer()
        val e = runCatching { Remote.join(t, "QQQQ-QQQQ") }.exceptionOrNull() as SyncException
        assertEquals("no_invite", e.code)
        assertTrue(e.message!!.contains("Код не найден"))
        t.offline = true
        assertTrue(runCatching { Remote.ping(t) }.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun syncStateIsSavedWithBoard() {
        val b = Board.create("Общая")
        b.sync.remoteKey = "k"; b.sync.remoteInvite = "ABCDEFGH"; b.sync.remoteSeq = 42; b.sync.pushedRev = "0000000001000.0000.vlad01"
        b.sync.uploadedImages += "a.jpg"
        val back = Board.fromJson(org.json.JSONObject(b.toJsonString()))
        assertEquals("k", back.sync.remoteKey)
        assertEquals(42, back.sync.remoteSeq)
        assertEquals("0000000001000.0000.vlad01", back.sync.pushedRev)
        assertTrue("a.jpg" in back.sync.uploadedImages)
        assertFalse(Board.fromJson(org.json.JSONObject(Board.create("x").toJsonString())).sync.isShared)
    }

    /** Настоящий сервер в Яндекс Облаке — только в сборке GitHub (там есть его адрес). */
    @Test
    fun liveServer() {
        val url = System.getenv("FREEWRITE_SYNC_URL").orEmpty()
        assumeTrue("адреса сервера нет — проверка пропущена", url.startsWith("https://"))
        val t = HttpTransport(url)
        assertTrue(Remote.ping(t).optBoolean("ok"))
        val b = exchange(t)
        // Убрать временную доску с сервера.
        t.call(org.json.JSONObject().put("op", "delete").put("board", b.id).put("key", b.sync.remoteKey))
        println("LIVE сервер: обмен двух планшетов прошёл, временная доска удалена")
    }

    @Test
    fun boardScreenPublishesAndReceives() {
        val t = FakeServer()
        RemoteSync.testTransport = t
        val store = BoardStore(ctx)
        val b = Board.create("Секция 5")
        b.texts += BoardText(20f, 300f, 32f, "Захватка №1", Ui.INK)
        b.images += BoardImage(BoardImages.importBitmap(ctx, Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)).first,
            0f, 0f, 200f, 150f, 40, 30)
        store.save(b)
        val controller = org.robolectric.Robolectric.buildActivity(
            BoardActivity::class.java,
            android.content.Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id),
        ).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        fun waitFor(what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < end) { idle(); if (cond()) return; Thread.sleep(20) }
            idle(); assertTrue("не дождался: $what", cond())
        }
        idle()
        root.findViewWithTag<android.view.View>("board_cloud").performClick()
        idle()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        waitFor("код-приглашение") { t.boards.containsKey(b.id) && (activity.window.decorView.rootView != null) &&
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.window?.decorView?.findViewWithTag<android.widget.TextView>("invite_code") != null }
        val code = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            .findViewWithTag<android.widget.TextView>("invite_code").text.toString()
        assertEquals(9, code.length)
        val dir = File("build/screenshots").apply { mkdirs() }
        fun shot(v: android.view.View, name: String) {
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView, "board_invite.png")
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss()
        waitFor("доска на сервере") { t.boards[b.id]!!.objs.values.any { it.kind == "text" } }
        waitFor("картинка на сервере") { t.images.isNotEmpty() }

        // Коллега меняет надпись на сервере — экран доски её получает.
        val board0 = t.boards[b.id]!!
        val textObj = board0.objs.values.first { it.kind == "text" }
        val newBody = org.json.JSONObject(textObj.body).put("text", "Захватка №1 — забетонировано").toString()
        t.call(org.json.JSONObject().put("op", "push").put("board", b.id).put("key", board0.key).put("changes",
            org.json.JSONArray().put(org.json.JSONObject().put("id", textObj.id).put("rev", "9999999999999.0000.kolya2")
                .put("k", "text").put("d", newBody))))
        root.findViewWithTag<android.view.View>("board_cloud").performClick()
        idle()
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        val field = BoardActivity::class.java.getDeclaredField("board").apply { isAccessible = true }
        waitFor("правка коллеги") { (field.get(activity) as Board).texts[0].text.contains("забетонировано") }
        val sub = root.findViewWithTag<android.widget.TextView>("board_subtitle").text.toString()
        assertTrue(sub, sub.startsWith("общая доска · код " + code))
        // Нажать на строку под названием — окно с кодом.
        root.findViewWithTag<android.view.View>("board_subtitle").performClick()
        idle()
        assertEquals(code, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            .findViewWithTag<android.widget.TextView>("invite_code").text.toString())
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss()
        shot(root, "board_shared.png")
        controller.pause().stop().destroy()
    }

    @Test
    fun homeJoinsByCode() {
        val t = FakeServer()
        RemoteSync.testTransport = t
        val (va, _) = twoTablets(t)
        val code = va.board.sync.remoteInvite
        val controller = org.robolectric.Robolectric.buildActivity(HomeActivity::class.java).setup()
        val home = controller.get()
        fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        idle()
        home.window.decorView.findViewWithTag<android.view.View>("home_join").performClick()
        idle()
        val dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        dlg.window!!.decorView.findViewWithTag<android.widget.EditText>("join_code").setText(Remote.prettyInvite(code).lowercase())
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        val end = System.currentTimeMillis() + 8000
        var opened: android.content.Intent? = null
        while (System.currentTimeMillis() < end && opened == null) {
            idle(); Thread.sleep(20)
            opened = org.robolectric.Shadows.shadowOf(home).nextStartedActivity
        }
        assertEquals(BoardActivity::class.java.name, opened!!.component!!.className)
        val got = BoardStore(ctx).load(va.board.id)!!
        assertEquals(va.board.entryHashes(), got.entryHashes())
        assertTrue(got.sync.isShared)
        controller.pause().stop().destroy()
    }

    /** Ссылка на сайт просмотра (FW-003): адрес сервера + код доски. */
    @Test
    fun siteLinkForComputer() {
        assertEquals("https://functions.example/abc?v=ABCD-EFGH", Remote.siteLink("https://functions.example/abc", "ABCDEFGH"))
        assertEquals("https://functions.example/abc?v=ABCD-EFGH", Remote.siteLink("https://functions.example/abc/?op=1", "ABCDEFGH"))
        assertEquals(null, Remote.siteLink("", "ABCDEFGH"))
        assertEquals(null, Remote.siteLink("https://functions.example/abc", ""))
    }
}
