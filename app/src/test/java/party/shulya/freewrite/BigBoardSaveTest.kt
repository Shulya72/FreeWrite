package party.shulya.freewrite

import android.content.Intent
import android.graphics.Color
import android.os.Looper
import org.junit.After
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
import java.io.File

/**
 * Большая общая доска без замираний (0.32.6). Отчёт Владимира 26.11: после открытия доски (13 МБ, 7 тыс. штрихов) экран стоял
 * ~2 с, при каждом уходе с неё — ещё 1–1,3 с: доска при каждом сохранении заново переводила в текст учёт общей доски, а в нём —
 * отметки всех удалений за всю жизнь доски (их становилось только больше). Проверяем: дошедшие до сервера отметки убираются,
 * удаления с сервера отметок не оставляют, файл прежнего вида, нетронутая доска не переписывается, запись — в фоне.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BigBoardSaveTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    @After
    fun tearDown() {
        BoardActivity.inlineIo = true
        RemoteSync.testTransport = null
    }

    private fun stroke(x: Float, y: Float = 0f, n: Int = 6): Drawing {
        val pts = FloatArray(n * 3) { i -> when (i % 3) { 0 -> x + (i / 3) * 2f; 1 -> y + (i / 3) % 3; else -> 0.5f } }
        return Drawing("pen", Color.BLUE, 2f, pts)
    }

    private fun sharedBoard(strokes: Int = 20): Board {
        val b = Board.create("Общая")
        for (i in 0 until strokes) b.strokes += stroke(i * 10f)
        b.sync.remoteKey = "key"; b.sync.remoteInvite = "ABCD2345"
        b.stamp("dev001", 1_763_000_000_000L)
        b.sync.pushedRev = b.sync.objs.values.maxOf { it.rev }
        return b
    }

    @Test
    fun confirmedDeletionMarksArePruned() {
        val b = sharedBoard()
        // Удалили 5 штрихов и отправили; потом ещё 2 — пока не отправлены.
        repeat(5) { b.strokes.removeAt(0) }
        b.stamp("dev001", 1_763_000_100_000L)
        b.sync.pushedRev = b.sync.tomb.values.max()
        repeat(2) { b.strokes.removeAt(0) }
        b.stamp("dev001", 1_763_000_200_000L)
        assertEquals(7, b.sync.tomb.size)
        assertEquals("убраны дошедшие до сервера", 5, b.sync.pruneTombs())
        assertEquals(2, b.sync.tomb.size)
        assertTrue("неотправленные удаления остаются и уйдут на сервер", b.sync.hasPending())
        assertEquals(2, Remote.pending(b).count { it.data == null })
        // Доска не общая — отметки нужны для обмена файлом, не трогаем.
        val local = Board.create("Своя")
        local.strokes += stroke(1f)
        local.stamp("dev001", 1000)
        local.strokes.clear()
        local.stamp("dev001", 2000)
        local.sync.pushedRev = "9999999999999.9999.zzz"
        assertEquals(0, local.sync.pruneTombs())
        assertEquals(1, local.sync.tomb.size)
    }

    @Test
    fun serverDeletionLeavesNoMark() {
        val b = sharedBoard(3)
        val (x, y) = b.strokes[0] to b.strokes[1]
        // Своя правка Y, ещё не отправленная, — новее присланного удаления: остаётся.
        y.pts[0] += 5f; y.changed()
        b.stamp("dev001", 1_763_000_300_000L)
        val rows = listOf(
            Remote.Row(x.id, BoardSync.rev(1_763_000_200_000L, 0, "kol002"), "", null, 1L),
            Remote.Row(y.id, BoardSync.rev(1_763_000_250_000L, 0, "kol002"), "", null, 2L),
        )
        Remote.apply(b, rows)
        assertFalse("удалён по серверу", b.strokes.any { it.id == x.id })
        assertFalse("и без отметки: сервер сам помнит удаление", b.sync.tomb.containsKey(x.id))
        assertFalse(b.sync.objs.containsKey(x.id))
        assertTrue("своя более новая правка осталась", b.strokes.any { it.id == y.id })
        assertTrue(b.sync.hasPending())
        // То же удаление ещё раз (как при перечитывании доски после обновления) — отметки не копятся.
        Remote.apply(b, rows, refresh = true)
        assertTrue(b.sync.tomb.isEmpty())
    }

    @Test
    fun fileLooksAsBeforeAndSnapshotIsStable() {
        val b = sharedBoard(50)
        repeat(10) { b.strokes.removeAt(0) }
        b.stamp("dev001", 1_763_000_100_000L)
        b.sync.members["kol002"] = "Николай Иванов"
        b.sync.uploadedImages += "a.jpg"; b.sync.peerImages += "a.jpg"
        b.texts += BoardText(10f, 10f, 20f, "Захватка \"1\"\n2", Color.BLACK)
        val sp = b.saveParts(stampDevice = "dev001")
        val w = java.io.StringWriter()
        sp.writeTo(w)
        assertEquals("файл того же вида, что раньше", b.toJsonString(), w.toString())
        val back = Board.fromJson(org.json.JSONObject(w.toString()))
        assertEquals(b.strokes.size, back.strokes.size)
        assertEquals(b.sync.tomb, back.sync.tomb)
        assertEquals("Николай Иванов", back.sync.members["kol002"])
        // Снимок не меняется от правок после него (в текст его переводят уже в потоке записи).
        val tail = sp.tail()
        b.strokes.clear()
        b.stamp("dev001", 1_763_000_200_000L)
        b.sync.pushedRev = "x"
        assertEquals(tail, sp.tail())
    }

    private fun boardFile(id: String) = File(File(ctx.filesDir, "boards"), "$id.json")

    @Test
    fun untouchedBoardIsNotRewritten() {
        val b0 = Board.create("Общая")
        for (i in 0 until 40) b0.strokes += stroke(i * 20f, 100f)
        val store = BoardStore(ctx)
        store.save(b0)
        val file = boardFile(b0.id)
        val ctl = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup()
        val a = ctl.get()
        idle()
        // Метка в файле: если доску перепишут, она пропадёт.
        file.writeText(file.readText().replaceFirst("\"title\":\"Общая\"", "\"title\":\"МЕТКА\""))
        ctl.pause()
        assertTrue("ничего не меняли — файл не переписан", file.readText().contains("МЕТКА"))
        ctl.resume(); idle()
        val updated0 = a.board().updated
        Thread.sleep(5)
        a.board().strokes += stroke(5000f, 100f)
        a.changed()
        ctl.pause()
        val after = file.readText()
        assertFalse("правка — файл переписан", after.contains("МЕТКА"))
        assertEquals(41, store.load(b0.id)!!.strokes.size)
        val updated1 = a.board().updated
        assertTrue("время правки доски сдвинулось", updated1 > updated0)
        // Только сдвинули доску — камера сохраняется, но это не «правка» доски.
        ctl.resume(); idle()
        file.writeText(file.readText().replaceFirst("\"title\":\"Общая\"", "\"title\":\"МЕТКА\""))
        a.board().camX += 300f
        a.onCameraChanged()
        ctl.pause()
        val moved = store.load(b0.id)!!
        assertEquals(a.board().camX, moved.camX, 0.01f)
        assertEquals("камера — не правка доски", updated1, moved.updated)
        ctl.stop().destroy()
    }

    @Test
    fun writesInBackgroundAndWarmsStrokes() {
        BoardActivity.inlineIo = false
        val b0 = Board.create("Общая")
        for (i in 0 until 300) b0.strokes += stroke(i * 20f, 100f, 40)
        val store = BoardStore(ctx)
        store.save(b0)
        val ctl = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup()
        val a = ctl.get()
        val warm = BoardActivity::class.java.getDeclaredField("jsonWarm").apply { isAccessible = true }
        val end = System.currentTimeMillis() + 10_000
        while (!(warm.get(a) as Boolean) && System.currentTimeMillis() < end) { Thread.sleep(10); idle() }
        assertTrue("строки штрихов приготовлены в фоне", warm.get(a) as Boolean)
        a.board().strokes += stroke(9000f, 100f)
        a.changed()
        ctl.pause()
        val end2 = System.currentTimeMillis() + 10_000
        while (store.load(b0.id)?.strokes?.size != 301 && System.currentTimeMillis() < end2) Thread.sleep(20)
        assertEquals("записано в фоне", 301, store.load(b0.id)!!.strokes.size)
        ctl.stop().destroy()
    }

    @Test
    fun pollWithoutChangesDoesNotRewriteAndNothingRestartsAfterLeaving() {
        val t = FakeServer()
        RemoteSync.testTransport = t
        val b = Board.create("Общая")
        for (i in 0 until 10) b.strokes += stroke(i * 10f)
        b.stamp("dev001", 1000)
        val (key, invite) = Remote.create(t, b.id, b.title, "dev001")
        b.sync.remoteKey = key; b.sync.remoteInvite = invite; b.sync.readBy = Remote.appVersion
        var saves = 0
        var edits = 0
        var ok = 0
        val rs = RemoteSync(ctx, t, object : RemoteSync.Host {
            override fun board() = b
            override fun canApply() = true
            override fun saveBoard() { saves++; b.stamp("dev001") }
            override fun saveIfEdited() { edits++ }
            override fun onRemoteApplied(changed: Int, authors: Map<String, Int>) {}
            override fun onImagesArrived() {}
            override fun onSyncState(state: RemoteSync.State) { if (state.text.startsWith("✓")) ok++ }
        })
        fun syncAndWait() {
            val before = ok
            rs.syncNow()
            val end = System.currentTimeMillis() + 8000
            while (ok == before && System.currentTimeMillis() < end) { idle(); Thread.sleep(10) }
            idle()
            assertTrue("синхронизация прошла", ok > before)
        }
        syncAndWait() // отправили своё — учёт сдвинулся, доска сохранена
        assertTrue(saves > 0)
        assertTrue("удаления, дошедшие до сервера, не копятся", b.sync.tomb.isEmpty())
        saves = 0; edits = 0
        syncAndWait() // ничего нового — файл не переписывается
        assertEquals("опрос без правок — без записи доски", 0, saves)
        assertEquals("свои правки — только если были", 1, edits)
        // Экран доски ушёл (stop) — «толчок» не запускает обмен заново в фоне.
        rs.stop()
        val calls = t.calls
        rs.kick(1)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        Thread.sleep(100); idle()
        assertEquals("после ухода с доски обмен сам не начинается", calls, t.calls)
        rs.close()
    }

    @Test
    fun memoryTrimKeepsStrokesOnScreen() {
        val b0 = Board.create("Общая")
        for (i in 0 until 60) b0.strokes += Drawing("pen", Color.rgb(200, 0, 160), 10f, floatArrayOf(100f + i * 15f, 100f, 0.5f, 100f + i * 15f, 400f, 0.5f))
        BoardStore(ctx).save(b0)
        val ctl = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup()
        val a = ctl.get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        view.asyncTiles = true
        val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        fun ink(): Int {
            bmp.eraseColor(Color.TRANSPARENT); view.draw(android.graphics.Canvas(bmp))
            var n = 0
            for (y in 0 until bmp.height step 3) for (x in 0 until bmp.width step 3) {
                val p = bmp.getPixel(x, y)
                if (Color.red(p) > 150 && Color.green(p) < 60 && Color.blue(p) > 110) n++
            }
            return n
        }
        var k = 0
        do { ink(); Thread.sleep(20); idle(); k++ } while ((view.tilesBusy || k < 3) && k < 300)
        val before = ink()
        assertTrue(before > 100)
        ctl.pause().stop()
        a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertTrue("картинки плиток отпущены", Trail.current().last().contains("отпущено"))
        ctl.start().resume(); idle()
        val after = ink()
        assertTrue("штрихи на месте сразу после возвращения ($before → $after)", after > before * 0.9)
        ctl.pause().stop().destroy()
    }

    @Test
    fun backgroundWatchReadsThreads() {
        val line = "4321 (board-tiles) S 1 2 3 4 5 6 7 8 9 10 250 50 0 0 20 0 1 0 100 0 0"
        assertEquals("board-tiles" to 300L, BgWatch.parseStat(line))
        assertEquals("odd) name" to 12L, BgWatch.parseStat("7 (odd) name) R 1 2 3 4 5 6 7 8 9 10 7 5 0"))
        assertEquals(null, BgWatch.parseStat("garbage"))
        val prev = mapOf(1 to ("main" to 1000L), 2 to ("board-tiles" to 500L), 3 to ("board-tiles" to 0L))
        val cur = mapOf(1 to ("main" to 1500L), 2 to ("board-tiles" to 3500L), 3 to ("board-tiles" to 1000L), 4 to ("sync-img" to 50L))
        assertEquals("board-tiles 4,0 с, main 0,5 с", BgWatch.busiest(prev, cur, 5))
    }

    /** Замеры (BENCH): сохранение большой общей доски с отметками удалений — сколько держит главный поток. */
    @Test
    fun benchBigSharedBoard() {
        val b = Board.create("Общая")
        val rnd = java.util.Random(5)
        for (k in 0 until 7000) b.strokes += stroke(rnd.nextFloat() * 14000f, rnd.nextFloat() * 22000f, 40 + rnd.nextInt(80))
        b.sync.remoteKey = "key"; b.sync.remoteInvite = "ABCD2345"
        b.stamp("dev001", 1_763_000_000_000L)
        for (i in 0 until 150_000) b.sync.tomb[Ids.next()] = BoardSync.rev(1_762_000_000_000L + i, 0, "dev00" + (i % 3))
        b.sync.pushedRev = b.sync.objs.values.maxOf { it.rev }
        b.toJsonParts()
        var t0 = System.nanoTime()
        repeat(3) { b.toJsonParts(stampDevice = "dev001") }
        val old = ms(t0) / 3
        t0 = System.nanoTime()
        var sp: Board.SaveParts? = null
        repeat(3) { sp = b.saveParts(stampDevice = "dev001") }
        val main = ms(t0) / 3
        t0 = System.nanoTime()
        val tail = sp!!.tail()
        val bg = ms(t0)
        val pruned = b.sync.pruneTombs()
        val tail2 = b.saveParts().tail()
        println("BENCH big-save before main ms=${"%.1f".format(old)} now main ms=${"%.1f".format(main)} (sync text in background ms=${"%.1f".format(bg)}, ${tail.length / 1024} KB)")
        println("BENCH big-save pruned marks=$pruned sync part ${tail.length / 1024} KB -> ${tail2.length / 1024} KB")
        assertEquals(150_000, pruned)
        assertNotEquals(tail.length, tail2.length)
    }
}
