package party.shulya.freewrite

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Голосовой ввод (0.32): распознавание русской речи прямо на планшете, без интернета и без оплаты.
 *
 * - Модель — GigaAM v3 e2e CTC (Сбер, https://github.com/salute-developers/GigaAM, лицензия MIT): сразу даёт текст со
 *   знаками препинания и заглавными. Готовит её сборка GitHub (`.github/workflows/voice-model.yml`) — один граф ONNX
 *   «звук 16 кГц → номера знаков», веса сжаты до 8 бит (320 МБ). Скачивается один раз из выпуска `voice-model-1`
 *   (тот же github.com, что у APK) и проверяется по SHA-256 — файл с другим отпечатком не берётся.
 * - Где кончается фраза — решает Silero VAD (MIT, 2,3 МБ, в APK): по паузе ≥ 0,7 с фраза уходит на распознавание.
 * - Фразы с короткими паузами (≤ 3 с, вместе ≤ 16 с) распознаются заново одним куском — знаки препинания расставляются
 *   по всему предложению, а не по каждой фразе отдельно; текст в заметке заменяется уточнённым.
 * - Работает ONNX Runtime (Microsoft, MIT).
 */

/** Файлы модели: скачать один раз, проверить, удалить. */
object VoiceModel {
    const val TAG = "voice-model-1"
    private const val BASE = "https://github.com/Shulya72/FreeWrite/releases/download/$TAG/"

    /** Откуда качать (в проверках — свой сервер на компьютере). */
    @Volatile internal var base = BASE

    class VFile(val name: String, val bytes: Long, val sha256: String)

    val MODEL = VFile("gigaam-v3-e2e-ctc-int8.onnx", 320_540_869L, "6ac381a8e293583ea70885329f4f2dd98bc12e20c77e04635f09a5e14fec6733")
    val TOKENS = VFile("tokens.txt", 1_992L, "1950ad9e44e0c7a790fc1c58c8005e32cc1d9482ec05955f5b4cb0b1b7c3837d")
    /** Файлы модели (в проверках — свои). */
    @Volatile internal var FILES = listOf(MODEL, TOKENS)
    val totalBytes: Long get() = FILES.sumOf { it.bytes }
    val totalMb: Int get() = ((totalBytes + 999_999) / 1_000_000).toInt()

    /** Для проверок на компьютере: считать модель скачанной (или нет). */
    @Volatile var testReady: Boolean? = null

    fun dir(ctx: Context): File = File(ctx.noBackupFilesDir, "voice")
    fun file(ctx: Context, f: VFile): File = File(dir(ctx), f.name)
    private fun okMark(ctx: Context) = File(dir(ctx), "ok-$TAG")

    fun ready(ctx: Context): Boolean = testReady ?: (okMark(ctx).exists() && FILES.all { file(ctx, it).length() == it.bytes })

    sealed class State {
        object Idle : State()
        class Running(val done: Long, val total: Long) : State()
        class Failed(val message: String) : State()
        object Done : State()
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val cancel = AtomicBoolean(false)
    @Volatile private var running = false

    fun listen(l: (State) -> Unit) { listeners += l }
    fun unlisten(l: (State) -> Unit) { listeners -= l }

    private fun publish(s: State) {
        state = s
        main.post { for (l in listeners) l(s) }
    }

    val isRunning: Boolean get() = running

    /** Скачать (с того места, где остановились). Повторный вызов во время скачивания ничего не делает. */
    @Synchronized
    fun download(ctx: Context) {
        if (running) return
        running = true
        cancel.set(false)
        val app = ctx.applicationContext
        Thread({
            try {
                fetchAll(app)
                publish(State.Done)
                Trail.add("Голос: модель скачана")
            } catch (t: Throwable) {
                val msg = when {
                    cancel.get() -> "Скачивание остановлено"
                    t is java.io.IOException && t !is ShaMismatch && t !is NoSpace -> "Нет связи с github.com — проверьте интернет и нажмите ещё раз (скачается с того же места)"
                    else -> t.message ?: t.javaClass.simpleName
                }
                if (!cancel.get()) Trail.add("Голос: модель не скачалась — ${t.javaClass.simpleName}: ${t.message}")
                publish(State.Failed(msg))
            } finally {
                running = false
            }
        }, "voice-download").start()
    }

    fun cancel() { cancel.set(true) }

    fun delete(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        VoiceEngine.dropModel()
        state = State.Idle
    }

    private class ShaMismatch(m: String) : java.io.IOException(m)
    private class NoSpace(m: String) : java.io.IOException(m)

    private fun fetchAll(ctx: Context) {
        val d = dir(ctx).apply { mkdirs() }
        // Старые выпуски модели (другой TAG) — убрать.
        d.listFiles()?.forEach { f -> if (FILES.none { f.name == it.name || f.name == it.name + ".part" } && f.name != okMark(ctx).name) f.delete() }
        if (ready(ctx)) return
        okMark(ctx).delete()
        fun haveBytes(): Long = FILES.sumOf { f ->
            val fin = file(ctx, f)
            if (fin.length() == f.bytes) f.bytes else File(d, f.name + ".part").length().coerceAtMost(f.bytes)
        }
        val need = totalBytes - haveBytes()
        if (d.usableSpace in 1 until need + 40_000_000L) {
            throw NoSpace("Не хватает места: нужно ещё ${(need + 40_000_000L) / 1_000_000} МБ свободных")
        }
        for (f in FILES) {
            val fin = file(ctx, f)
            if (fin.length() == f.bytes && sha256(fin) == f.sha256) continue
            fin.delete()
            val part = File(d, f.name + ".part")
            fetch(f, part) { publish(State.Running(haveBytes(), totalBytes)) }
            // Связь оборвалась, а сервер не сказал — скачанное остаётся, в следующий раз докачается.
            if (part.length() < f.bytes) throw java.io.IOException("скачано ${part.length()} из ${f.bytes}")
            if (sha256(part) != f.sha256) {
                part.delete()
                throw ShaMismatch("Файл «${f.name}» скачался с ошибкой — нажмите ещё раз")
            }
            if (!part.renameTo(fin)) throw java.io.IOException("не удалось сохранить ${f.name}")
        }
        okMark(ctx).writeText(TAG)
    }

    private fun fetch(f: VFile, part: File, progress: () -> Unit) {
        if (part.length() > f.bytes) part.delete()
        if (part.length() == f.bytes) return
        var url = URL(base + f.name)
        var hops = 0
        var conn: HttpURLConnection? = null
        val from = part.length()
        while (conn == null) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "FreeWrite-Android")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            val code = c.responseCode
            when {
                code in 300..399 && hops < 6 -> { url = URL(url, c.getHeaderField("Location")); hops++; c.disconnect() }
                code == 200 || code == 206 -> conn = c
                code == 416 -> { part.delete(); c.disconnect(); return fetch(f, part, progress) }
                else -> { c.disconnect(); throw java.io.IOException("сервер ответил $code") }
            }
        }
        val c = conn
        val append = c.responseCode == 206
        try {
            c.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var last = 0L
                    while (true) {
                        if (cancel.get()) throw java.io.InterruptedIOException("cancel")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        val now = SystemClock.uptimeMillis()
                        if (now - last > 250) { last = now; progress() }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        progress()
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Распознаватель: отсчёты звука 16 кГц (−1…1) → текст. */
interface SpeechRecognizer : Closeable {
    fun recognize(pcm: FloatArray): String
}

/** Таблица знаков модели: номер → кусок слова SentencePiece («▁» — пробел перед словом). */
class VoiceTokens(lines: List<String>) {
    private val pieces: Array<String>
    val blank: Int

    init {
        val map = HashMap<Int, String>()
        for (l in lines) {
            if (l.isEmpty()) continue
            val tab = l.lastIndexOf('\t')
            if (tab < 0) continue
            val id = l.substring(tab + 1).trim().toIntOrNull() ?: continue
            map[id] = l.substring(0, tab)
        }
        val n = (map.keys.maxOrNull() ?: -1) + 1
        pieces = Array(n) { map[it] ?: "" }
        blank = n
    }

    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (i in ids) if (i in pieces.indices) sb.append(pieces[i])
        return sb.toString().replace('▁', ' ').replace(Regex(" {2,}"), " ").trim()
    }

    companion object {
        fun read(f: File) = VoiceTokens(f.readLines(Charsets.UTF_8))
    }
}

object Ctc {
    /** Жадная расшифровка CTC: повторы подряд склеить, «пусто» выбросить. */
    fun collapse(labels: LongArray, frames: Int, blank: Int): IntArray {
        val out = ArrayList<Int>()
        var prev = -1L
        for (t in 0 until min(frames, labels.size)) {
            val v = labels[t]
            if (v != blank.toLong() && v != prev) out += v.toInt()
            prev = v
        }
        return out.toIntArray()
    }
}

/** GigaAM v3 e2e CTC в ONNX Runtime. */
class GigaAmRecognizer(model: File, private val tokens: VoiceTokens, threads: Int) : SpeechRecognizer {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val o = OrtSession.SessionOptions()
        o.setIntraOpNumThreads(threads)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Без «ожидания вхолостую» между запусками — меньше расход батареи.
        runCatching { o.addConfigEntry("session.intra_op.allow_spinning", "0") }
        session = env.createSession(model.path, o)
        o.close()
    }

    override fun recognize(pcm: FloatArray): String {
        if (pcm.size < 1600) return ""
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pcm), longArrayOf(1, pcm.size.toLong())).use { wav ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(pcm.size.toLong())), longArrayOf(1)).use { len ->
                session.run(mapOf("wav" to wav, "length" to len)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val labels = (r[0].value as Array<LongArray>)[0]
                    val frames = when (val f = r[1].value) {
                        is IntArray -> f[0]
                        is LongArray -> f[0].toInt()
                        else -> labels.size
                    }
                    return tokens.decode(Ctc.collapse(labels, frames, tokens.blank))
                }
            }
        }
    }

    override fun close() { session.close() }
}

/** Общая на всё приложение загруженная модель (300+ МБ в памяти): держится, пока нужна, и ещё 3 минуты после. */
object VoiceEngine {
    /** Для проверок на компьютере — подменный распознаватель. */
    @Volatile var testRecognizer: SpeechRecognizer? = null

    private var rec: SpeechRecognizer? = null
    private var users = 0
    @Volatile var lastLoadMs = 0L
        private set
    private val main = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { closeIfUnused() }

    val loaded: Boolean @Synchronized get() = rec != null || testRecognizer != null

    fun threads(): Int = min(4, max(1, Runtime.getRuntime().availableProcessors()))

    /** Взять модель (загрузить, если её нет). Долго при первом вызове — звать не на главном потоке. */
    @Synchronized
    fun acquire(ctx: Context): SpeechRecognizer {
        main.removeCallbacks(releaseRunnable)
        val r = testRecognizer ?: rec ?: run {
            val t0 = SystemClock.uptimeMillis()
            val g = GigaAmRecognizer(VoiceModel.file(ctx, VoiceModel.MODEL), VoiceTokens.read(VoiceModel.file(ctx, VoiceModel.TOKENS)), threads())
            lastLoadMs = SystemClock.uptimeMillis() - t0
            Trail.add("Голос: модель загружена за ${lastLoadMs} мс (${threads()} потока) · ${Trail.memShort()}")
            rec = g
            g
        }
        users++
        return r
    }

    @Synchronized
    fun release() {
        users = max(0, users - 1)
        if (users == 0) {
            main.removeCallbacks(releaseRunnable)
            main.postDelayed(releaseRunnable, 3 * 60_000L)
        }
    }

    @Synchronized
    private fun closeIfUnused() {
        if (users > 0) return
        rec?.close()
        rec = null
    }

    @Synchronized
    fun dropModel() {
        if (users > 0) return
        rec?.close()
        rec = null
    }
}

/** Где в звуке речь (Silero VAD, кадр 32 мс). */
interface VoiceActivity : Closeable {
    /** Вероятность речи в кадре из [FRAME] отсчётов. */
    fun prob(frame: FloatArray): Float
    fun reset()

    companion object {
        const val FRAME = 512
    }
}

class SileroVad(model: ByteArray) : VoiceActivity {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val state = FloatArray(2 * 128)
    private val input = FloatArray(CONTEXT + VoiceActivity.FRAME)
    private val sr = longArrayOf(16000)

    init {
        val o = OrtSession.SessionOptions()
        o.setIntraOpNumThreads(1)
        o.setInterOpNumThreads(1)
        session = env.createSession(model, o)
        o.close()
    }

    override fun prob(frame: FloatArray): Float {
        System.arraycopy(frame, 0, input, CONTEXT, VoiceActivity.FRAME)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { s ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(sr), longArrayOf()).use { rate ->
                    session.run(mapOf("input" to x, "state" to s, "sr" to rate)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val p = (r[0].value as Array<FloatArray>)[0][0]
                        @Suppress("UNCHECKED_CAST")
                        val st = r[1].value as Array<Array<FloatArray>>
                        System.arraycopy(st[0][0], 0, state, 0, 128)
                        System.arraycopy(st[1][0], 0, state, 128, 128)
                        // Хвост кадра — начало следующего входа (так устроена модель).
                        System.arraycopy(input, VoiceActivity.FRAME, input, 0, CONTEXT)
                        return p
                    }
                }
            }
        }
    }

    override fun reset() {
        state.fill(0f)
        input.fill(0f)
    }

    override fun close() { session.close() }

    companion object {
        private const val CONTEXT = 64
        fun fromAssets(ctx: Context) = SileroVad(ctx.assets.open("voice/silero_vad.onnx").use { it.readBytes() })
    }
}

/**
 * Режет поток на фразы по вероятности речи (как VADIterator у Silero). Положения — номера отсчётов от начала записи.
 * Речь началась: вероятность ≥ [on]; кончилась: ниже [off] дольше [minSilence]; к фразе с обеих сторон — [pad] запаса;
 * короче [minSpeech] — шум, не фраза; длиннее [maxSpeech] — режется (модель рассчитана на куски до 25 с).
 */
class Segmenter(
    private val on: Float = 0.5f,
    private val off: Float = 0.35f,
    private val minSilence: Int = 16_000 * 700 / 1000,
    private val pad: Int = 16_000 * 200 / 1000,
    private val minSpeech: Int = 16_000 * 250 / 1000,
    private val maxSpeech: Int = 16_000 * 20,
    private val emit: (start: Long, end: Long) -> Unit,
) {
    var inSpeech = false
        private set
    private var start = 0L
    private var rawStart = 0L
    private var silenceFrom = -1L
    /** Последний отсчёт, где была речь. */
    var lastSpeech = -1L
        private set

    fun feed(pos: Long, p: Float, frame: Int = VoiceActivity.FRAME) {
        val end = pos + frame
        if (p >= on) lastSpeech = end
        if (!inSpeech) {
            if (p >= on) {
                inSpeech = true
                rawStart = pos
                start = max(0L, pos - pad)
                silenceFrom = -1
            }
            return
        }
        if (p >= on) silenceFrom = -1
        else if (p < off) {
            if (silenceFrom < 0) silenceFrom = pos
            if (end - silenceFrom >= minSilence) { finish(silenceFrom + pad, silenceFrom); return }
        }
        if (end - start >= maxSpeech) {
            finish(end, end)
            inSpeech = true
            start = end
            rawStart = end
        }
    }

    /** Запись кончилась: недоговорённое — последней фразой. */
    fun flush(pos: Long) {
        if (inSpeech) {
            if (silenceFrom >= 0) finish(min(pos, silenceFrom + pad), silenceFrom) else finish(pos, pos)
        }
    }

    /** [e] — конец фразы с запасом, [speechEnd] — где кончилась сама речь (короче [minSpeech] — шум). */
    private fun finish(e: Long, speechEnd: Long) {
        inSpeech = false
        silenceFrom = -1
        if (speechEnd - rawStart >= minSpeech) emit(start, e)
    }
}

/** Последние [seconds] секунд звука (16 кГц, 16 бит). */
class PcmRing(seconds: Int) {
    private val buf = ShortArray(16_000 * seconds)
    var total = 0L
        private set

    @Synchronized
    fun write(src: ShortArray, n: Int) {
        for (i in 0 until n) buf[((total + i) % buf.size).toInt()] = src[i]
        total += n
    }

    /** Отсчёты [from, to) как −1…1; null — уже вытеснены. */
    @Synchronized
    fun read(from: Long, to: Long): FloatArray? {
        val a = max(0L, from)
        val b = min(total, to)
        if (b <= a || a < total - buf.size) return null
        return FloatArray((b - a).toInt()) { buf[((a + it) % buf.size).toInt()] / 32768f }
    }
}

/**
 * Одна запись с микрофона: поток звука → фразы (VAD) → текст. Распознавание — в своём потоке, по очереди.
 * Вызовы [Listener] — на главном потоке.
 */
class VoiceSession(private val ctx: Context, private val l: Listener) {
    interface Listener {
        /** Готовим модель / слушаем / распознаём. */
        fun onVoiceState(state: State)
        /** Громкость 0…1 (примерно 10 раз в секунду). */
        fun onVoiceLevel(level: Float)
        /**
         * Текст кусочка речи [chunk]. [replace] — тот же кусочек распознан заново вместе с новой фразой (уточнённые знаки):
         * прежний текст этого кусочка заменить.
         */
        fun onVoiceText(chunk: Int, text: String, replace: Boolean)
        fun onVoiceError(message: String)
        /** Запись кончилась (всё распознано). [silence] — сама выключилась после долгой тишины. */
        fun onVoiceStopped(silence: Boolean)
    }

    enum class State { LOADING, LISTENING, SPEECH, RECOGNIZING }

    /** Источник звука: микрофон или (в проверках) файл. read: −1 — звук кончился. */
    interface Source : Closeable {
        fun open(): Boolean
        fun read(buf: ShortArray): Int
    }

    private val main = Handler(Looper.getMainLooper())
    private val stopFlag = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val breakChunk = AtomicBoolean(false)
    private val pending = AtomicInteger(0)
    private val ring = PcmRing(60)
    private val asr: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "voice-asr") }
    private var recThread: Thread? = null
    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var silenceStop = false

    // Кусочек речи, к которому присоединяются фразы (только поток распознавания).
    private var chunk = 0
    private var chunkStart = -1L
    private var chunkEnd = -1L
    private var lastMs = 0L

    /** Статистика для отчёта. */
    @Volatile var phrases = 0
        private set
    @Volatile var speechMs = 0L
        private set
    @Volatile var recognizeMs = 0L
        private set

    fun start() {
        val src = testSource?.invoke() ?: MicSource()
        if (!VoiceEngine.loaded) post { l.onVoiceState(State.LOADING) }
        asr.execute {
            try {
                recognizer = VoiceEngine.acquire(ctx)
            } catch (t: Throwable) {
                Trail.add("Голос: модель не загрузилась — ${t.javaClass.simpleName}: ${t.message}")
                post { l.onVoiceError("Модель распознавания не загрузилась (${t.javaClass.simpleName}). Скачайте её заново в «Проверке планшета».") }
                stopFlag.set(true)
            }
        }
        recThread = Thread({ record(src) }, "voice-rec").apply { start() }
    }

    /** Закончить: недоговорённое распознаётся, потом [Listener.onVoiceStopped]. */
    fun stop() { stopFlag.set(true) }

    /** Экран закрыт: остановить без ответов. */
    fun cancel() {
        cancelled.set(true)
        stopFlag.set(true)
    }

    /** Текст правили или курсор ушёл — следующая фраза начинает новый кусочек. */
    fun breakChunk() { breakChunk.set(true) }

    private fun post(f: () -> Unit) { main.post { if (!cancelled.get()) f() } }

    private fun record(src: Source) {
        var vad: VoiceActivity? = null
        try {
            if (!src.open()) {
                post { l.onVoiceError("Микрофон недоступен — возможно, его занимает другое приложение") }
                return
            }
            vad = testVad?.invoke() ?: SileroVad.fromAssets(ctx)
            val seg = Segmenter { s, e -> submit(s, e) }
            val frame = ShortArray(VoiceActivity.FRAME)
            val f = FloatArray(VoiceActivity.FRAME)
            var pos = 0L
            var levelAcc = 0.0
            var levelN = 0
            var lastState: State? = null
            post { l.onVoiceState(State.LISTENING) }
            while (!stopFlag.get()) {
                val filled = src.read(frame) // целый кадр за раз
                if (filled < 0) break
                if (filled == 0) continue
                ring.write(frame, filled)
                for (i in 0 until filled) f[i] = frame[i] / 32768f
                if (filled < f.size) java.util.Arrays.fill(f, filled, f.size, 0f)
                val p = vad.prob(f)
                seg.feed(pos, p, filled)
                pos += filled
                var sq = 0.0
                for (i in 0 until filled) sq += f[i] * f[i]
                levelAcc += sq / filled
                levelN++
                if (levelN >= 3) {
                    val rms = sqrt(levelAcc / levelN)
                    val db = 20 * log10(max(rms, 1e-6))
                    val level = ((db + 55) / 45).toFloat().coerceIn(0f, 1f)
                    levelAcc = 0.0
                    levelN = 0
                    post { l.onVoiceLevel(level) }
                }
                val st = if (seg.inSpeech) State.SPEECH else if (pending.get() > 0) State.RECOGNIZING else State.LISTENING
                if (st != lastState) { lastState = st; post { l.onVoiceState(st) } }
                // Долго тишина — микрофон выключается сам (батарея).
                val quiet = pos - max(0L, seg.lastSpeech)
                if (quiet > 16_000L * SILENCE_STOP_S && pending.get() == 0) { silenceStop = true; break }
            }
            seg.flush(pos)
        } catch (t: Throwable) {
            Trail.add("Голос: запись прервалась — ${t.javaClass.simpleName}: ${t.message}")
            post { l.onVoiceError("Запись прервалась: ${t.message ?: t.javaClass.simpleName}") }
        } finally {
            runCatching { src.close() }
            runCatching { vad?.close() }
            asr.execute {
                if (recognizer != null) VoiceEngine.release()
                recognizer = null
                if (phrases > 0) Trail.add("Голос: запись — фраз $phrases, речи ${speechMs / 1000.0} с, распознавание ${recognizeMs} мс")
                post { l.onVoiceStopped(silenceStop) }
            }
            asr.shutdown()
        }
    }

    private fun submit(s: Long, e: Long) {
        pending.incrementAndGet()
        asr.execute {
            try {
                recognizeSegment(s, e)
            } catch (t: Throwable) {
                Trail.add("Голос: ошибка распознавания — ${t.javaClass.simpleName}: ${t.message}")
                post { l.onVoiceError("Ошибка распознавания: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun recognizeSegment(s: Long, e: Long) {
        val r = recognizer ?: return
        val wantBreak = breakChunk.getAndSet(false)
        var join = chunkStart >= 0 && !wantBreak && s - chunkEnd <= JOIN_GAP && e - chunkStart <= MAX_CHUNK &&
            lastMs <= SLOW_MS && pending.get() <= 1
        val whole = if (join) ring.read(chunkStart, e) else null
        if (whole == null) join = false
        val pcm = whole ?: ring.read(s, e) ?: return
        post { l.onVoiceState(State.RECOGNIZING) }
        val t0 = SystemClock.uptimeMillis()
        val text = r.recognize(pcm)
        val ms = SystemClock.uptimeMillis() - t0
        lastMs = ms
        phrases++
        speechMs += (e - s) * 1000 / 16_000
        recognizeMs += ms
        val sec = pcm.size / 16_000.0
        Trail.add("Голос: ${"%.1f".format(sec)} с звука → ${ms} мс (×${"%.2f".format(ms / 1000.0 / max(sec, 0.1))})${if (join) " · уточнение" else ""}")
        lastPhrase = Phrase(text, sec, ms)
        if (text.isBlank()) {
            if (!join) chunkStart = -1
            return
        }
        if (!join) {
            chunk++
            chunkStart = s
        }
        chunkEnd = e
        val c = chunk
        post { l.onVoiceText(c, text, join) }
    }

    class Phrase(val text: String, val seconds: Double, val ms: Long)

    /** Последняя распознанная фраза (для «Проверки планшета»). */
    @Volatile var lastPhrase: Phrase? = null
        private set

    /** Микрофон: 16 кГц, моно, 16 бит. */
    private class MicSource : Source {
        private var rec: AudioRecord? = null

        @Suppress("MissingPermission")
        override fun open(): Boolean {
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val size = max(min, 16_000 * 2 / 2) // полсекунды
            for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                val r = runCatching { AudioRecord(source, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }.getOrNull() ?: continue
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); continue }
                rec = r
                return true
            }
            return false
        }

        override fun read(buf: ShortArray): Int {
            val r = rec ?: return -1
            var got = 0
            while (got < buf.size) {
                val n = r.read(buf, got, buf.size - got)
                if (n < 0) return if (got > 0) got else -1
                got += n
            }
            return got
        }

        override fun close() {
            rec?.let { runCatching { it.stop() }; it.release() }
            rec = null
        }
    }

    companion object {
        /** Пауза между фразами, после которой новая фраза — новый кусочек (не уточняет прежний). */
        const val JOIN_GAP = 16_000L * 3
        /** Кусочек, который распознаётся заново целиком, — не длиннее. */
        const val MAX_CHUNK = 16_000L * 16
        /** Распознавание шло дольше — не уточнять (планшет не успевает). */
        const val SLOW_MS = 3_000L
        /** Тишина дольше — запись выключается сама. */
        const val SILENCE_STOP_S = 60

        /** Для проверок на компьютере: звук и VAD вместо микрофона и Silero. */
        @Volatile var testSource: (() -> Source)? = null
        @Volatile var testVad: (() -> VoiceActivity)? = null
    }
}
