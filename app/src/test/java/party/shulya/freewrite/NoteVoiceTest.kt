package party.shulya.freewrite

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.security.MessageDigest

/**
 * Голосовой ввод в заметке (0.32): микрофон на нижней панели → полоска «Говорите…» → фразы встают у курсора, фраза после
 * короткой паузы уточняет прежнюю (один кусочек, один шаг «Отменить»); правка руками во время диктовки — новая фраза
 * отдельно; модели нет — окно «Скачать»; «Проверка планшета» — фраза и время. Скачивание модели — с обрывом и докачкой.
 * Звук — образец из файла, распознаватель — подменный (настоящая модель — в VoiceTest.realGigaAm).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteVoiceTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val first = "Завтра в девять утра встречаемся на объекте."
    private val joined = "Завтра в девять утра встречаемся на объекте, проверить армирование плиты."

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
        shadowOf(ctx).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        VoiceSession.testSource = null
        VoiceSession.testVad = null
        VoiceEngine.testRecognizer = null
        VoiceModel.testReady = null
        VoiceModel.base = "https://github.com/Shulya72/FreeWrite/releases/download/${VoiceModel.TAG}/"
        VoiceModel.FILES = listOf(VoiceModel.MODEL, VoiceModel.TOKENS)
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun open(n: Note, keys: Boolean): NoteActivity {
        NoteStore(ctx).save(n)
        val i = Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)
        if (keys) i.putExtra(NoteActivity.EXTRA_KEYS, true)
        val a = Robolectric.buildActivity(NoteActivity::class.java, i).setup().get()
        idle()
        return a
    }

    private fun click(a: NoteActivity, tag: String) { a.window.decorView.findViewWithTag<View>(tag)!!.performClick(); idle() }

    /** Ждать (обрабатывая главный поток), пока не выполнится условие. */
    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) {
            assertTrue("не дождались: $what", System.currentTimeMillis() < until)
            idle()
            Thread.sleep(3)
        }
    }

    private fun body(a: NoteActivity): String = (a.model().blocks[0] as Block.Para).text

    private fun planning(): Note = Note.create("Планёрка").apply {
        paper = NotePaper.NONE
        blocks.clear()
        blocks += Block.Para("Задачи:")
    }

    private fun fake() = VoiceTest.FakeRecognizer { s -> if (s < 6) first else joined }

    @Test
    fun micDictatesPhrasesAndRefinesThem() {
        VoiceModel.testReady = true
        VoiceEngine.testRecognizer = fake()
        VoiceSession.testSource = { VoiceTest.WavSource(VoiceTest.wav("voice/two_phrases.wav"), sleepMs = 2) }
        val a = open(planning(), keys = true)
        val root = a.window.decorView
        val e = a.caretEditor()
        e.setSelection(e.text.length)
        idle()
        assertEquals(View.GONE, root.findViewWithTag<View>("voice_bar").visibility)

        click(a, "keys_mic")
        assertTrue(a.voiceActive())
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("voice_bar").visibility)
        assertTrue((root.findViewWithTag<View>("keys_mic") as Ui.ToolButton).alert)

        // Первая фраза — у курсора, через пробел.
        waitFor("первая фраза") { body(a).contains(first) }
        assertEquals("Задачи: $first", body(a))
        shot(root, "note-voice-recording.png")
        // Вторая (пауза 1,9 с) — распознана вместе с первой: текст кусочка уточнён, не продублирован.
        waitFor("запись закончилась") { !a.voiceActive() }
        assertEquals("Задачи: $joined", body(a))
        assertEquals(View.GONE, root.findViewWithTag<View>("voice_bar").visibility)
        assertFalse((root.findViewWithTag<View>("keys_mic") as Ui.ToolButton).alert)
        assertEquals("курсор — после продиктованного", a.caretEditor().text.indexOf(joined) + joined.length, a.caretEditor().selectionStart)

        // Кусочек — один шаг «Отменить».
        click(a, "note_undo")
        assertEquals("Задачи:", body(a))
        click(a, "note_redo")
        assertEquals("Задачи: $joined", body(a))
    }

    @Test
    fun typingDuringDictationKeepsPhrasesSeparate() {
        VoiceModel.testReady = true
        val rec = VoiceTest.FakeRecognizer { s -> if (s < 3) "Проверить армирование плиты." else if (s < 6) first else joined }
        VoiceEngine.testRecognizer = rec
        VoiceSession.testSource = { VoiceTest.WavSource(VoiceTest.wav("voice/two_phrases.wav"), sleepMs = 3) }
        val a = open(planning(), keys = true)
        val e = a.caretEditor()
        e.setSelection(e.text.length)
        idle()
        click(a, "keys_mic")
        waitFor("первая фраза") { body(a).contains(first) }
        // Дописали руками — следующая фраза встаёт отдельно (не заменяет написанное).
        val ed = a.caretEditor()
        ed.text.insert(ed.selectionStart, " Срочно!")
        idle()
        waitFor("запись закончилась") { !a.voiceActive() }
        assertEquals("Задачи: $first Срочно! Проверить армирование плиты.", body(a))
        assertEquals(2, rec.calls.size)
        assertTrue("вторая фраза распознана одна, без первой", rec.calls[1] < 3)
    }

    @Test
    fun stopButtonAndPenBarMic() {
        VoiceModel.testReady = true
        VoiceEngine.testRecognizer = fake()
        // Звук идёт медленно — успеваем нажать «Готово» до конца.
        VoiceSession.testSource = { VoiceTest.WavSource(VoiceTest.wav("voice/two_phrases.wav"), sleepMs = 20) }
        val a = open(planning(), keys = false)
        val root = a.window.decorView
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("pen_bar").visibility)
        click(a, "pen_mic")
        assertTrue(a.voiceActive())
        waitFor("слушаю") { root.findViewWithTag<TextView>("voice_status").text.isNotEmpty() }
        click(a, "voice_stop")
        assertEquals("Дописываю…", root.findViewWithTag<TextView>("voice_status").text.toString())
        waitFor("запись закончилась") { !a.voiceActive() }
        assertEquals(View.GONE, root.findViewWithTag<View>("voice_bar").visibility)
    }

    @Test
    fun noModelOffersDownload() {
        VoiceModel.testReady = false
        val a = open(planning(), keys = true)
        click(a, "keys_mic")
        assertFalse(a.voiceActive())
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(d.isShowing)
        val msg = shadowOf(d).message?.toString() ?: d.findViewById<TextView>(android.R.id.message)?.text.toString()
        assertTrue(msg, msg.contains("${VoiceModel.totalMb} МБ") && msg.contains("без интернета"))
        assertEquals("Скачать", d.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        shot(d.window!!.decorView, "note-voice-offer.png")
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(d.isShowing)
        assertFalse(VoiceModel.isRunning)
    }

    @Test
    fun diagnosticsVoiceCheck() {
        VoiceModel.testReady = true
        VoiceEngine.testRecognizer = fake()
        VoiceSession.testSource = { VoiceTest.WavSource(VoiceTest.wav("voice/two_phrases.wav"), sleepMs = 1) }
        val a = Robolectric.buildActivity(DiagnosticsActivity::class.java).setup().get()
        idle()
        val root = a.window.decorView
        assertTrue(root.findViewWithTag<TextView>("diag_voice_state").text.startsWith("Модель скачана"))
        root.findViewWithTag<View>("diag_voice_test").performClick()
        idle()
        val result = root.findViewWithTag<TextView>("diag_voice_result")
        waitFor("фраза") { result.text.startsWith("«") }
        assertTrue(result.text.toString(), result.text.startsWith("«$first» — 4,"))
        assertTrue(result.text.toString(), result.text.contains("раз быстрее речи"))
        waitFor("проверка закончилась") { root.findViewWithTag<TextView>("diag_voice_test").text.startsWith("Проверить") }
    }

    // ---------------------------------------------------------------- скачивание модели

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun downloadResumesAfterBreakAndChecksSha() {
        val big = ByteArray(700_000) { (it * 31 % 251).toByte() }
        val small = "▁\t1\n".toByteArray()
        var breakOnce = true
        var ranges = 0
        // Маленький сервер на компьютере: отдаёт файлы, понимает Range, первый раз обрывает связь на середине модели.
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { c ->
                    val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val req = input.readLine() ?: return@use
                    var range: String? = null
                    while (true) {
                        val h = input.readLine() ?: break
                        if (h.isEmpty()) break
                        if (h.startsWith("Range:", ignoreCase = true)) range = h.substringAfter(':').trim()
                    }
                    val name = req.split(' ')[1].removePrefix("/m/")
                    val out = c.getOutputStream()
                    val data = when (name) { "model.onnx" -> big; "tokens.txt" -> small; else -> null }
                    if (data == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use }
                    val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    if (from > 0) ranges++
                    val head = (if (from > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${data.size - 1}/${data.size}\r\n" else "HTTP/1.1 200 OK\r\n") +
                        "Content-Length: ${data.size - from}\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray())
                    if (name == "model.onnx" && breakOnce) {
                        breakOnce = false
                        out.write(data, from, 300_000) // связь оборвалась на середине
                        out.flush()
                        return@use
                    }
                    out.write(data, from, data.size - from)
                    out.flush()
                }
            }
        }
        try {
            VoiceModel.delete(ctx)
            VoiceModel.base = "http://127.0.0.1:${server.localPort}/m/"
            VoiceModel.FILES = listOf(VoiceModel.VFile("model.onnx", big.size.toLong(), sha(big)), VoiceModel.VFile("tokens.txt", small.size.toLong(), sha(small)))
            val states = ArrayList<VoiceModel.State>()
            val l: (VoiceModel.State) -> Unit = { states += it }
            VoiceModel.listen(l)
            fun runDownload(): VoiceModel.State {
                VoiceModel.download(ctx)
                waitFor("скачивание") { !VoiceModel.isRunning }
                idle()
                return VoiceModel.state
            }
            val s1 = runDownload()
            assertTrue("обрыв — ошибка: $s1", s1 is VoiceModel.State.Failed)
            assertTrue((s1 as VoiceModel.State.Failed).message.contains("с того же места"))
            assertFalse(VoiceModel.ready(ctx))
            assertEquals(300_000L, File(VoiceModel.dir(ctx), "model.onnx.part").length())
            val s2 = runDownload()
            assertTrue("$s2", s2 is VoiceModel.State.Done)
            assertEquals("докачано с места обрыва", 1, ranges)
            assertTrue(VoiceModel.ready(ctx))
            assertTrue(File(VoiceModel.dir(ctx), "model.onnx").readBytes().contentEquals(big))
            assertTrue(states.any { it is VoiceModel.State.Running })
            VoiceModel.unlisten(l)

            // Испорченный файл (отпечаток другой) — не берётся.
            VoiceModel.delete(ctx)
            VoiceModel.FILES = listOf(VoiceModel.VFile("model.onnx", big.size.toLong(), sha(small)), VoiceModel.VFile("tokens.txt", small.size.toLong(), sha(small)))
            val s3 = runDownload()
            assertTrue("$s3", s3 is VoiceModel.State.Failed)
            assertTrue((s3 as VoiceModel.State.Failed).message.contains("с ошибкой"))
            assertFalse(VoiceModel.ready(ctx))
            assertFalse(File(VoiceModel.dir(ctx), "model.onnx").exists())
        } finally {
            server.close()
            VoiceModel.delete(ctx)
        }
    }
}
