package party.shulya.freewrite

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Голосовой ввод (0.32): склейка знаков модели, нарезка речи на фразы, настоящий Silero VAD на образце (две фразы
 * синтезированной речи с паузами), запись целиком — фразы с короткой паузой уточняются одним куском. Настоящая модель
 * GigaAM — только на компьютере разработчика (VOICE_MODEL_DIR=папка с файлами выпуска voice-model-1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        VoiceSession.testSource = null
        VoiceSession.testVad = null
        VoiceEngine.testRecognizer = null
        VoiceModel.testReady = null
    }

    @Test
    fun tokensAndCtc() {
        val t = VoiceTokens(listOf("\t0", "▁\t1", ".\t2", "▁При\t3", "вет\t4", ",\t5", "▁как\t6", "▁дела\t7", "?\t8"))
        assertEquals(9, t.blank)
        // Повторы подряд склеиваются, «пусто» (9) разделяет одинаковые знаки, после числа кадров — не читается.
        val labels = longArrayOf(9, 3, 3, 9, 4, 5, 5, 9, 6, 9, 7, 7, 8, 9, 3)
        val ids = Ctc.collapse(labels, labels.size - 1, t.blank)
        assertEquals(listOf(3, 4, 5, 6, 7, 8), ids.toList())
        assertEquals("Привет, как дела?", t.decode(ids))
        assertEquals("", t.decode(intArrayOf(0)))
        assertEquals(listOf(4, 4), Ctc.collapse(longArrayOf(4, 9, 4), 3, 9).toList())
    }

    @Test
    fun segmenterCutsByPauses() {
        val segs = ArrayList<Pair<Long, Long>>()
        val s = Segmenter { a, b -> segs += a to b }
        val f = VoiceActivity.FRAME
        var pos = 0L
        fun run(p: Float, seconds: Double) {
            val n = (seconds * 16_000 / f).toInt()
            repeat(n) { s.feed(pos, p); pos += f }
        }
        run(0.05f, 1.0)   // тишина
        run(0.9f, 2.0)    // фраза 1
        run(0.4f, 0.5)    // между порогами — фраза не кончается
        run(0.9f, 1.0)
        run(0.05f, 1.2)   // пауза > 0,7 с — конец фразы 1
        run(0.9f, 0.1)    // щелчок короче 0,25 с — не фраза
        run(0.05f, 1.0)
        run(0.9f, 1.5)    // фраза 2 — недоговорена, запись кончилась
        s.flush(pos)
        assertEquals(2, segs.size)
        val (a1, b1) = segs[0]
        assertEquals(1.0 - 0.2, a1 / 16_000.0, 0.1)
        assertEquals(4.5 + 0.2, b1 / 16_000.0, 0.1)
        assertEquals(pos, segs[1].second)
        // Длинная речь без пауз режется по 20 с.
        segs.clear()
        pos = 0
        run(0.9f, 45.0)
        s.flush(pos)
        assertEquals(3, segs.size)
        assertTrue(segs.all { it.second - it.first <= 16_000L * 20 })
    }

    @Test
    fun autoGainLiftsQuietSpeechOnly() {
        fun tone(seconds: Double, amp: Double) = FloatArray((seconds * 16_000).toInt()) { i ->
            // «речь»: тон 220 Гц, громкость дышит 4 раза в секунду
            val t = i / 16_000.0
            (amp * Math.sin(2 * Math.PI * 220 * t) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 4 * t))).toFloat()
        }
        fun rms(x: FloatArray, a: Int = 0, b: Int = x.size): Double {
            var s = 0.0
            for (i in a until b) s += x[i] * x[i]
            return Math.sqrt(s / (b - a))
        }
        // Громко (−17 дБ) — не трогаем.
        val loud = tone(3.0, 0.2)
        val l = AutoGain.apply(loud)
        assertEquals(1f, l.maxGain, 0.01f)
        assertEquals(rms(loud), rms(l.pcm), 1e-4)
        // Тихо (−51 дБ, человек далеко) — поднято к −20 дБ, не больше чем в 31,6 раза.
        val quiet = tone(3.0, 0.004)
        val q = AutoGain.apply(quiet)
        assertTrue("усиление ${q.maxGain}", q.maxGain > 20f && q.maxGain <= 31.7f)
        assertTrue("громкость ${rms(q.pcm)}", rms(q.pcm) > 0.05)
        // В одной фразе: сначала громко (рядом), потом тихо (далеко) — громкое как было, тихое поднято.
        val mix = loud + quiet
        val m = AutoGain.apply(mix)
        val half = loud.size
        assertEquals(rms(loud, 0, half - 8000), rms(m.pcm, 0, half - 8000), 1e-3)
        assertTrue(rms(m.pcm, half + 8000, mix.size) > 0.05)
        // Пики не выше полной шкалы.
        val peaks = AutoGain.apply(tone(1.0, 0.95))
        assertTrue(peaks.pcm.all { kotlin.math.abs(it) <= 1f })
        assertEquals(0, AutoGain.apply(FloatArray(100)).pcm.count { it != 0f })
    }

    @Test
    fun liveGainKeepsOrderAndLiftsQuiet() {
        val g = LiveGain()
        val got = ArrayList<Pair<Long, Float>>()
        val f = VoiceActivity.FRAME
        fun feed(amp: Float, frames: Int, start: Long): Long {
            var pos = start
            repeat(frames) {
                val x = FloatArray(f) { i -> (amp * Math.sin(2 * Math.PI * 220 * (pos + i) / 16_000.0)).toFloat() }
                g.push(x, f, pos) { y, n, at ->
                    var sq = 0.0
                    for (i in 0 until n) sq += y[i] * y[i]
                    got += at to Math.sqrt(sq / n).toFloat()
                }
                pos += f
            }
            return pos
        }
        val end = feed(0.003f, 60, feed(0.3f, 40, 0))
        g.flush { y, n, at ->
            var sq = 0.0
            for (i in 0 until n) sq += y[i] * y[i]
            got += at to Math.sqrt(sq / n).toFloat()
        }
        assertEquals("все кадры по порядку", (0 until 100).map { it * f.toLong() }, got.map { it.first })
        assertEquals(end, got.last().first + f)
        // Громкое (0,21 rms) — как было; тихое (0,002) к концу поднято примерно в 30 раз.
        assertEquals(0.212f, got[10].second, 0.01f)
        assertTrue("${got[90].second}", got[90].second > 0.05f)
    }

    @Test
    fun quietFarSpeechStillBecomesPhrases() {
        // −74 дБ (человек далеко): без усиления Silero теряет вторую фразу целиком и часть первой («полоска шевелится, а букв нет»).
        val quiet = wav("voice/two_phrases.wav").let { pcm -> ShortArray(pcm.size) { (pcm[it] * 0.002f).toInt().toShort() } }
        val vad = SileroVad.fromAssets(ctx)
        val raw = ArrayList<Pair<Long, Long>>()
        val s = Segmenter { a, b -> raw += a to b }
        val fr = FloatArray(VoiceActivity.FRAME)
        var pos = 0
        while (pos + fr.size <= quiet.size) {
            for (i in fr.indices) fr[i] = quiet[pos + i] / 32768f
            s.feed(pos.toLong(), vad.prob(fr))
            pos += fr.size
        }
        s.flush(pos.toLong())
        vad.close()
        val rawSec = raw.sumOf { it.second - it.first } / 16_000.0
        println("без усиления: фраз ${raw.size}, речи $rawSec с")
        assertTrue("без усиления находится не вся речь: $raw", raw.size <= 1 && rawSec < 4.0)
        // Запись целиком (с усилением перед детектором) — фразы есть и уходят на распознавание.
        val fake = FakeRecognizer { "Фраза." }
        VoiceEngine.testRecognizer = fake
        VoiceSession.testSource = { WavSource(quiet) }
        var stopped = false
        val got = ArrayList<String>()
        VoiceSession(ctx, object : VoiceSession.Listener {
            override fun onVoiceState(state: VoiceSession.State) {}
            override fun onVoiceLevel(level: Float) {}
            override fun onVoiceText(chunk: Int, text: String, replace: Boolean) { got += text }
            override fun onVoiceError(message: String) { throw AssertionError(message) }
            override fun onVoiceStopped(silence: Boolean) { stopped = true }
        }).start()
        val until = System.currentTimeMillis() + 30_000
        while (!stopped && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(stopped)
        println("с усилением: распознаваний ${fake.calls}")
        assertTrue("распознаваний: ${fake.calls}", fake.calls.isNotEmpty())
        // С усилением — обе фразы, как на обычной громкости (вторая уточняет первую одним куском).
        assertEquals("${fake.calls}", 2, fake.calls.size)
        assertTrue("с усилением речи больше: ${fake.calls}", fake.calls.max() > rawSec + 2.0)
        assertTrue(got.isNotEmpty())
    }

    @Test
    fun ringKeepsLastSeconds() {
        val r = PcmRing(1)
        val chunk = ShortArray(4000) { 1000 }
        repeat(5) { r.write(chunk, chunk.size) }
        assertEquals(20_000L, r.total)
        assertEquals(null, r.read(0, 1000))
        val x = r.read(10_000, 20_000)!!
        assertEquals(10_000, x.size)
        assertEquals(1000 / 32768f, x[5], 1e-6f)
    }

    @Test
    fun sileroFindsTwoPhrases() {
        val pcm = wav("voice/two_phrases.wav")
        val vad = SileroVad.fromAssets(ctx)
        val segs = ArrayList<Pair<Long, Long>>()
        val s = Segmenter { a, b -> segs += a to b }
        val f = FloatArray(VoiceActivity.FRAME)
        var pos = 0
        while (pos + f.size <= pcm.size) {
            for (i in f.indices) f[i] = pcm[pos + i] / 32768f
            s.feed(pos.toLong(), vad.prob(f))
            pos += f.size
        }
        s.flush(pos.toLong())
        vad.close()
        // Фразы: 1,0–5,0 с и 6,9–9,4 с (паузы — тишина 1 и 1,5 с).
        assertEquals("$segs", 2, segs.size)
        assertEquals(0.9, segs[0].first / 16_000.0, 0.25)
        assertEquals(5.1, segs[0].second / 16_000.0, 0.3)
        assertEquals(7.0, segs[1].first / 16_000.0, 0.3)
        assertEquals(9.5, segs[1].second / 16_000.0, 0.3)
    }

    /** Звук из файла — как с микрофона, только быстрее. */
    class WavSource(private val pcm: ShortArray, private val sleepMs: Long = 0) : VoiceSession.Source {
        private var pos = 0
        override fun open() = true
        override fun read(buf: ShortArray): Int {
            if (sleepMs > 0) Thread.sleep(sleepMs)
            if (pos >= pcm.size) return -1
            val n = minOf(buf.size, pcm.size - pos)
            System.arraycopy(pcm, pos, buf, 0, n)
            pos += n
            return n
        }
        override fun close() {}
    }

    /** Подменный распознаватель: отвечает по длине звука. */
    class FakeRecognizer(private val answer: (seconds: Double) -> String) : SpeechRecognizer {
        val calls = ArrayList<Double>()
        override fun recognize(pcm: FloatArray): String {
            val s = pcm.size / 16_000.0
            synchronized(calls) { calls += s }
            return answer(s)
        }
        override fun close() {}
    }

    @Test
    fun sessionJoinsPhrasesWithShortPause() {
        val pcm = wav("voice/two_phrases.wav")
        val fake = FakeRecognizer { s -> if (s < 6) "Завтра в девять утра встречаемся на объекте." else "Завтра в девять утра встречаемся на объекте, проверить армирование плиты." }
        VoiceEngine.testRecognizer = fake
        VoiceSession.testSource = { WavSource(pcm) }
        val got = ArrayList<Triple<Int, String, Boolean>>()
        var stopped = false
        val states = HashSet<VoiceSession.State>()
        val session = VoiceSession(ctx, object : VoiceSession.Listener {
            override fun onVoiceState(state: VoiceSession.State) { states += state }
            override fun onVoiceLevel(level: Float) {}
            override fun onVoiceText(chunk: Int, text: String, replace: Boolean) { got += Triple(chunk, text, replace) }
            override fun onVoiceError(message: String) { throw AssertionError(message) }
            override fun onVoiceStopped(silence: Boolean) { stopped = true }
        })
        session.start()
        val until = System.currentTimeMillis() + 30_000
        while (!stopped && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("запись не закончилась", stopped)
        assertEquals("$got", 2, got.size)
        assertEquals(Triple(1, "Завтра в девять утра встречаемся на объекте.", false), got[0])
        // Вторая фраза через 1,9 с — распознана вместе с первой и заменяет её текст.
        assertEquals(1, got[1].first)
        assertTrue(got[1].third)
        assertEquals(2, fake.calls.size)
        assertEquals(4.2, fake.calls[0], 0.4)
        assertEquals(8.6, fake.calls[1], 0.5)
        assertTrue(states.contains(VoiceSession.State.SPEECH))
        assertEquals(2, session.phrases)
    }

    @Test
    fun sessionBreakStartsNewChunk() {
        val pcm = wav("voice/two_phrases.wav")
        val fake = FakeRecognizer { s -> "Фраза ${"%.0f".format(s)}." }
        VoiceEngine.testRecognizer = fake
        val got = ArrayList<Triple<Int, String, Boolean>>()
        var stopped = false
        lateinit var session: VoiceSession
        session = VoiceSession(ctx, object : VoiceSession.Listener {
            override fun onVoiceState(state: VoiceSession.State) {}
            override fun onVoiceLevel(level: Float) {}
            override fun onVoiceText(chunk: Int, text: String, replace: Boolean) {
                got += Triple(chunk, text, replace)
                session.breakChunk() // как будто после первой фразы поправили текст
            }
            override fun onVoiceError(message: String) { throw AssertionError(message) }
            override fun onVoiceStopped(silence: Boolean) { stopped = true }
        })
        // Звук подаётся медленнее, чтобы первая фраза успела дойти до экрана раньше второй.
        VoiceSession.testSource = { WavSource(pcm, sleepMs = 1) }
        session.start()
        val until = System.currentTimeMillis() + 30_000
        while (!stopped && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(stopped)
        assertEquals("$got", 2, got.size)
        assertFalse(got[1].third)
        assertEquals(2, got[1].first)
    }

    /** Настоящая модель Сбера — только если файлы лежат рядом (компьютер разработчика). */
    @Test
    fun realGigaAm() {
        val dir = System.getenv("VOICE_MODEL_DIR")?.let { File(it) }
        assumeTrue("нет VOICE_MODEL_DIR", dir != null && File(dir, VoiceModel.MODEL.name).exists())
        dir!!
        val tokens = VoiceTokens.read(File(dir, VoiceModel.TOKENS.name))
        assertEquals(256, tokens.blank)
        val t0 = System.currentTimeMillis()
        val rec = GigaAmRecognizer(File(dir, VoiceModel.MODEL.name), tokens, 4)
        val loadMs = System.currentTimeMillis() - t0
        val pcm = wav("voice/two_phrases.wav")
        fun part(a: Double, b: Double) = FloatArray(((b - a) * 16_000).toInt()) { pcm[(a * 16_000).toInt() + it] / 32768f }
        val t1 = System.currentTimeMillis()
        val text = rec.recognize(part(0.888, 5.128))
        val ms = System.currentTimeMillis() - t1
        println("GigaAM: загрузка $loadMs мс, 4,2 с речи → $ms мс: «$text»")
        // Синтезированная речь espeak понимается плохо, но начало фразы — верно (так же отвечает модель в Python).
        assertTrue(text, text.startsWith("Завтра"))
        assertTrue(text.endsWith("."))
        assertEquals("", rec.recognize(FloatArray(800)))
        rec.close()
        // Отпечатки в приложении совпадают с файлами выпуска.
        assertEquals(VoiceModel.TOKENS.sha256, VoiceModel.sha256(File(dir, VoiceModel.TOKENS.name)))
        assertEquals(VoiceModel.MODEL.bytes, File(dir, VoiceModel.MODEL.name).length())
    }

    companion object {
        /** 16-битный WAV моно 16 кГц из ресурсов проверок. */
        fun wav(name: String): ShortArray {
            val bytes = VoiceTest::class.java.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var p = 12
            while (p + 8 <= bytes.size) {
                val id = String(bytes, p, 4, Charsets.US_ASCII)
                val len = bb.getInt(p + 4)
                if (id == "fmt ") {
                    assertEquals(1, bb.getShort(p + 8).toInt()) // PCM
                    assertEquals(1, bb.getShort(p + 10).toInt()) // моно
                    assertEquals(16_000, bb.getInt(p + 12))
                }
                if (id == "data") {
                    val n = minOf(len, bytes.size - p - 8) / 2
                    return ShortArray(n) { bb.getShort(p + 8 + it * 2) }
                }
                p += 8 + len + (len and 1)
            }
            throw AssertionError("нет данных в $name")
        }
    }
}
