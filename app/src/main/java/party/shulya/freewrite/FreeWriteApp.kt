package party.shulya.freewrite

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Если приложение упадёт (например, ML Kit не запустится на HarmonyOS), текст ошибки
 * сохраняется в файл. При следующем запуске он попадёт в отчёт — так мы узнаем причину,
 * а не будем гадать.
 */
class FreeWriteApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(base.filesDir, CRASH_FILE).writeText("${Date()} (поток ${thread.name}, ${BuildConfig.VERSION_NAME})\n" +
                    "${Trail.memShort()}\n$sw")
            } catch (_: Throwable) {
            }
            try { Trail.add("ОШИБКА в потоке ${thread.name}: $error"); Trail.flush() } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Trail.init(this) // журнал работы для отчёта о вылете (0.26.2)
    }

    /** Система просит освободить память — в журнал (так видно, что вылет из-за памяти). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) Trail.add("Система просит освободить память (уровень $level)")
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
