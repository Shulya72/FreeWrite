package party.shulya.freewrite

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Журнал работы (0.26.2, Владимир: «приложение начало подтормаживать и периодически вылетает»). Ошибки разбираем по
 * отчёту, а не наугад, поэтому приложение само записывает короткие события: что открыли, долгие кадры, подвисания экрана
 * (и на чём именно экран стоял), нехватку памяти, ошибки. Последние [MAX] строк лежат в файле — после вылета они
 * попадают в отчёт. Личного в журнале нет: только числа и названия действий.
 */
object Trail {
    private const val MAX = 120
    private const val FILE = "trail.txt"
    private const val PREV1 = "trail_prev1.txt"
    private const val PREV2 = "trail_prev2.txt"
    private const val MB = 1024 * 1024
    private val lines = ArrayDeque<String>()
    @Volatile private var dir: File? = null
    private var dirty = false
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)

    /** В тестах (Robolectric) главный поток стоит, пока его не прокрутят, — сторож там не нужен. */
    private val robolectric = Build.FINGERPRINT == "robolectric"

    /** Запуск приложения: журнал прошлого запуска — в «прошлый», позапрошлого — в «позапрошлый». */
    fun init(ctx: Context) {
        val d = ctx.filesDir
        dir = d
        runCatching {
            val cur = File(d, FILE); val p1 = File(d, PREV1); val p2 = File(d, PREV2)
            if (cur.isFile) {
                if (p1.isFile) { p2.delete(); p1.renameTo(p2) }
                cur.renameTo(p1)
            }
        }
        add("Запуск FreeWrite ${BuildConfig.VERSION_NAME}")
    }

    fun memShort(): String {
        val r = Runtime.getRuntime()
        return "память ${(r.totalMemory() - r.freeMemory()) / MB}/${r.maxMemory() / MB} МБ"
    }

    /** Событие в журнал (из любого потока). */
    fun add(msg: String) {
        synchronized(this) {
            lines.addLast(fmt.format(Date()) + " " + msg + " · " + memShort())
            while (lines.size > MAX) lines.removeFirst()
            if (dirty || dir == null) return
            dirty = true
        }
        Handler(Looper.getMainLooper()).postDelayed({ flush() }, 3000)
    }

    /** Записать журнал в файл сейчас (при уходе с экрана и при ошибке). */
    fun flush() {
        val text: String
        synchronized(this) { dirty = false; text = lines.joinToString("\n") }
        val d = dir ?: return
        runCatching { File(d, FILE).writeText(text) }
    }

    fun current(): List<String> = synchronized(this) { lines.toList() }

    fun previous(ctx: Context): List<Pair<String, String>> = listOf(PREV1 to "прошлый запуск", PREV2 to "позапрошлый запуск").mapNotNull { (f, name) ->
        File(ctx.filesDir, f).takeIf { it.isFile }?.let { runCatching { name to it.readText() }.getOrNull() }
    }

    // ------------------------------------------------------------ сторож главного потока

    @Volatile private var watching = false
    private var watcher: Thread? = null
    /** Что сейчас на экране — для строки «Экран не отвечал …». */
    @Volatile var where = ""

    /**
     * Сторож: раз в секунду проверяет, отвечает ли экран. Не ответил за 0,7 с — в журнал «Экран не отвечал N мс» и где
     * стоял главный поток (несколько строк нашего кода). Только пока открыта доска.
     */
    fun startWatch(what: String) {
        where = what
        if (robolectric || watching) return
        watching = true
        val main = Handler(Looper.getMainLooper())
        watcher = Thread({
            while (watching) {
                val sent = SystemClock.uptimeMillis()
                val done = AtomicLong(0L)
                main.post { done.set(SystemClock.uptimeMillis()) }
                try { Thread.sleep(700) } catch (_: InterruptedException) { break }
                if (done.get() == 0L && watching) {
                    val stack = mainStack()
                    while (watching && done.get() == 0L && SystemClock.uptimeMillis() - sent < 30000) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    }
                    val end = done.get().takeIf { it != 0L } ?: SystemClock.uptimeMillis()
                    add("Экран не отвечал ${end - sent} мс ($where)" + if (stack.isNotEmpty()) " — стоял в: $stack" else "")
                }
                try { Thread.sleep(300) } catch (_: InterruptedException) { break }
            }
        }, "freewrite-watch").apply { isDaemon = true; start() }
    }

    fun stopWatch() {
        watching = false
        watcher?.interrupt()
        watcher = null
    }

    /** Где сейчас главный поток: до 8 строк нашего кода (без системных), сверху вниз. */
    private fun mainStack(): String = runCatching {
        Looper.getMainLooper().thread.stackTrace
            .filter { it.className.startsWith("party.shulya.freewrite") }
            .take(8)
            .joinToString(" ← ") { it.className.substringAfterLast('.').substringBefore('$') + "." + it.methodName + ":" + it.lineNumber }
    }.getOrDefault("")
}

/**
 * Отчёт о вылете (0.26.2): при следующем запуске Ежедневник предлагает его отправить. В отчёте — как система завершала
 * приложение в последние разы (падение, зависание, нехватка памяти, закрыто системой), след ошибки, память планшета,
 * сколько досок и картинок, журнал работы ([Trail]) перед вылетом. Тот же отчёт — в «⋮ → Отправить отчёт о работе».
 */
object CrashReport {
    private const val PREFS = "crash"
    private const val MB = 1024 * 1024

    class Exit(val time: Long, val reason: Int, val importance: Int, val desc: String, val pss: Long, val rss: Long, val trace: String)

    /** Последние завершения приложения по словам системы (Android 11+), новые сверху; [withTrace] — со следом (читать файлы). */
    fun exits(ctx: Context, max: Int = 12, withTrace: Boolean = false): List<Exit> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching {
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, max).map { e ->
                Exit(e.timestamp, e.reason, e.importance, e.description.orEmpty(), e.pss, e.rss, if (withTrace) readTrace(e) else "")
            }
        }.getOrDefault(emptyList())
    }

    private fun readTrace(e: ApplicationExitInfo): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        return runCatching {
            val bytes = e.traceInputStream?.use { it.readBytes() } ?: return ""
            when (e.reason) {
                ApplicationExitInfo.REASON_ANR -> anrMain(String(bytes, Charsets.UTF_8))
                ApplicationExitInfo.REASON_CRASH_NATIVE -> printable(bytes)
                else -> String(bytes, Charsets.UTF_8).take(4000)
            }
        }.getOrDefault("")
    }

    /** Из следа зависания — главный поток (до 60 строк). */
    internal fun anrMain(text: String): String {
        val ls = text.lines()
        val i = ls.indexOfFirst { it.startsWith("\"main\"") }
        if (i < 0) return ls.take(40).joinToString("\n")
        val out = ArrayList<String>()
        for (j in i until ls.size) { if (j > i && ls[j].isBlank()) break; out += ls[j]; if (out.size >= 60) break }
        return out.joinToString("\n")
    }

    /** Из записи о падении в системной библиотеке (двоичной) — читаемые строки: сигнал, библиотеки, функции. */
    internal fun printable(bytes: ByteArray): String {
        val out = LinkedHashSet<String>()
        val sb = StringBuilder()
        fun end() { if (sb.length >= 6) out += sb.toString(); sb.setLength(0) }
        for (b in bytes) { val c = b.toInt() and 0xff; if (c in 32..126) sb.append(c.toChar()) else end() }
        end()
        return out.take(80).joinToString("\n").take(5000)
    }

    fun reasonText(r: Int): String = when (r) {
        1 -> "закрылось само"
        2 -> "остановлено сигналом"
        3 -> "нехватка памяти"
        4 -> "ошибка в программе"
        5 -> "ошибка в системной библиотеке"
        6 -> "зависло (не отвечало)"
        7 -> "не запустилось"
        8 -> "изменились разрешения"
        9 -> "слишком много ресурсов"
        10 -> "закрыто пользователем"
        11 -> "остановлено пользователем"
        12 -> "закрылось вслед за другим"
        13 -> "закрыто системой"
        14 -> "заморожено системой"
        15, 16 -> "обновление приложения"
        else -> "причина неизвестна"
    }

    /** Завершение, о котором стоит спросить: падение, зависание, нехватка памяти — или закрыто, пока было на экране. */
    fun isBad(e: Exit): Boolean = when (e.reason) {
        3, 4, 5, 6, 7, 9 -> true
        0, 2, 13 -> e.importance in 1..ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        else -> false
    }

    private fun crashFile(ctx: Context) = File(ctx.filesDir, FreeWriteApp.CRASH_FILE)

    /**
     * Есть ли о чём спросить: новое плохое завершение или новая ошибка в файле. Строка для окна («14.11 в 10:32 —
     * нехватка памяти») или null. Впервые (после обновления) смотрим последние 3 дня.
     */
    fun pending(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getLong("seen", 0L).takeIf { it > 0 } ?: (System.currentTimeMillis() - 3 * 24 * 3600_000L)
        val bad = exits(ctx).filter { isBad(it) && it.time > seen }
        val f = crashFile(ctx)
        val fileNew = f.isFile && f.lastModified() > p.getLong("seenFile", 0L) && f.lastModified() > seen - 60_000
        if (bad.isEmpty() && !fileNew) return null
        val df = SimpleDateFormat("dd.MM 'в' HH:mm", Locale.US)
        val last = bad.maxByOrNull { it.time }
        val head = last?.let { df.format(Date(it.time)) + " — " + reasonText(it.reason) } ?: (df.format(Date(f.lastModified())) + " — ошибка в программе")
        return if (bad.size > 1) "$head (и ещё ${bad.size - 1})" else head
    }

    fun markSeen(ctx: Context) {
        val newest = exits(ctx).maxOfOrNull { it.time } ?: 0L
        val f = crashFile(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("seen", maxOf(System.currentTimeMillis(), newest))
            .putLong("seenFile", if (f.isFile) f.lastModified() else 0L)
            .apply()
    }

    /** Текст отчёта (без личного: названия досок не пишем, только числа). */
    fun build(ctx: Context): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("FreeWrite ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(") — отчёт о работе\n")
        sb.append("Время: ").append(now).append("\n")
        runCatching { sb.append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · ").append(DeviceInfo.osLine())
            .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n") }
        runCatching { sb.append("Процессор: ").append(DeviceInfo.cpuLine()).append('\n') }
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val r = Runtime.getRuntime()
            sb.append("Память: ").append(DeviceInfo.memoryLine(ctx)).append(if (mi.lowMemory) " (система: памяти мало)" else "")
                .append("; приложению можно до ").append(r.maxMemory() / MB).append(" МБ (класс ").append(am?.memoryClass ?: 0)
                .append("/").append(am?.largeMemoryClass ?: 0).append("), сейчас занято ").append((r.totalMemory() - r.freeMemory()) / MB).append(" МБ\n")
        }
        runCatching {
            val boards = File(ctx.filesDir, "boards").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            val imgs = File(ctx.filesDir, "note_images").listFiles().orEmpty()
            sb.append("Досок: ").append(boards.size).append(", самая большая ").append(String.format(Locale.US, "%.1f", (boards.maxOfOrNull { it.length() } ?: 0L) / 1048576.0))
                .append(" МБ; картинок и листов: ").append(imgs.size).append(" (").append(imgs.sumOf { it.length() } / MB).append(" МБ)\n")
        }
        sb.append('\n')
        val df = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
        val ex = exits(ctx, withTrace = true)
        sb.append("== Как завершались последние запуски (по словам системы) ==\n")
        if (ex.isEmpty()) sb.append(if (Build.VERSION.SDK_INT < 30) "система не сообщает (нужен Android 11+)\n" else "записей нет\n")
        for (e in ex) {
            sb.append(df.format(Date(e.time))).append(" — ").append(reasonText(e.reason)).append(" [").append(e.reason).append("]")
                .append(if (e.importance in 1..ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) ", было на экране" else ", в фоне")
                .append(", память ").append(e.pss / 1024).append("/").append(e.rss / 1024).append(" МБ")
            if (e.desc.isNotBlank()) sb.append(" · ").append(e.desc.take(200))
            sb.append('\n')
        }
        for (e in ex.filter { it.trace.isNotBlank() }.take(2)) {
            sb.append("\n-- след: ").append(df.format(Date(e.time))).append(" — ").append(reasonText(e.reason)).append(" --\n").append(e.trace).append('\n')
        }
        val f = crashFile(ctx)
        if (f.isFile) runCatching { sb.append("\n== Ошибка в программе (последняя) ==\n").append(f.readText().take(8000)).append('\n') }
        for ((name, text) in Trail.previous(ctx)) sb.append("\n== Журнал: ").append(name).append(" ==\n").append(text.takeLast(9000)).append('\n')
        sb.append("\n== Журнал: этот запуск ==\n")
        for (l in Trail.current()) sb.append(l).append('\n')
        return sb.toString()
    }

    /** Окно в Ежедневнике после вылета: «Отправить» / «Скопировать» / «Не надо». */
    internal var dialog: AlertDialog? = null
    fun offer(a: Activity, head: String) {
        dialog = AlertDialog.Builder(a).setTitle("FreeWrite закрылся с ошибкой")
            .setMessage("$head.\n\nОтправьте отчёт разработчику — по нему найдём причину. В отчёте нет ваших записей, только " +
                "сведения о работе приложения.")
            .setPositiveButton("Отправить") { _, _ -> markSeen(a); share(a, build(a)) }
            .setNeutralButton("Скопировать") { _, _ -> markSeen(a); copy(a, build(a)) }
            .setNegativeButton("Не надо") { _, _ -> markSeen(a) }
            .setCancelable(false)
            .show()
    }

    fun share(a: Activity, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "FreeWrite ${BuildConfig.VERSION_NAME} — отчёт о работе")
            .putExtra(Intent.EXTRA_TEXT, text)
        try { a.startActivity(Intent.createChooser(intent, "Отправить отчёт")) } catch (_: Throwable) { copy(a, text) }
    }

    fun copy(a: Activity, text: String) {
        a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Отчёт FreeWrite", text))
        android.widget.Toast.makeText(a, "Отчёт скопирован — вставьте его в сообщение", android.widget.Toast.LENGTH_LONG).show()
    }
}
