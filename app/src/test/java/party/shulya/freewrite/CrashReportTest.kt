package party.shulya.freewrite

import android.app.ActivityManager
import android.app.AlertDialog
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.io.File
import java.time.Duration

/**
 * Отчёт о вылете (0.26.2, Владимир: «приложение подтормаживает и периодически вылетает»): после вылета Ежедневник
 * предлагает отправить отчёт; в отчёте — как система завершала приложение, след зависания, журнал прошлого запуска.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReportTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Profile.save(ctx, "Владимир", "Шульмин")
        HomeActivity.crashOffered = false
    }

    private fun addExit(reason: Int, importance: Int, agoMs: Long, trace: String? = null) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val b = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(ctx.packageName).setReason(reason).setImportance(importance)
            .setTimestamp(System.currentTimeMillis() - agoMs).setPss(512L * 1024).setRss(640L * 1024)
            .setDescription(if (reason == 6) "Input dispatching timed out" else "")
        trace?.let { b.setTraceInputStream(it.byteInputStream()) }
        shadowOf(am).addApplicationExitInfo(b.build())
    }

    @Test
    fun afterHangHomeOffersReportWithTraceAndPreviousTrail() {
        // Прошлый запуск: журнал с долгим кадром, потом «перезапуск» — он уходит в «прошлый запуск».
        Trail.add("Открыта доска: таблиц 3, областей 360")
        Trail.add("Долгий кадр 640 мс (режим NONE)")
        Trail.flush()
        Trail.init(ctx)
        addExit(10, 400, 3_600_000) // закрыли сами — не повод спрашивать
        addExit(6, 100, 60_000, "----- pid 123 -----\n\"main\" prio=5 tid=1 Runnable\n  at party.shulya.freewrite.BoardView.drawPlanSheet(BoardView.kt:2180)\n" +
            "  at party.shulya.freewrite.BoardView.drawFrame(BoardView.kt:2010)\n\n\"RenderThread\" prio=7\n  at x.y\n")

        val head = CrashReport.pending(ctx)
        assertNotNull(head)
        assertTrue(head!!, head.contains("зависло"))

        val home = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        val d = CrashReport.dialog
        assertNotNull("нет окна об ошибке", d)
        assertTrue(d!!.isShowing)

        val text = CrashReport.build(home)
        assertTrue(text, text.contains("зависло (не отвечало) [6], было на экране"))
        assertTrue(text, text.contains("закрыто пользователем [10], в фоне"))
        assertTrue(text, text.contains("BoardView.drawPlanSheet"))
        assertTrue("в следе — только главный поток", !text.contains("RenderThread"))
        assertTrue(text, text.contains("== Журнал: прошлый запуск =="))
        assertTrue(text, text.contains("Долгий кадр 640 мс"))
        assertTrue(text, text.contains("Запуск FreeWrite " + BuildConfig.VERSION_NAME))

        // «Не надо» — больше не спрашивает.
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertNull(CrashReport.pending(ctx))

        // Новая ошибка в программе (файл) — снова спросит.
        Thread.sleep(20)
        File(ctx.filesDir, FreeWriteApp.CRASH_FILE).apply { writeText("java.lang.OutOfMemoryError: Failed to allocate"); setLastModified(System.currentTimeMillis() + 5000) }
        val again = CrashReport.pending(ctx)
        assertNotNull(again)
        assertTrue(again!!, again.contains("ошибка в программе"))
        assertTrue(CrashReport.build(ctx).contains("OutOfMemoryError"))
    }

    @Test
    fun nativeCrashGivesReadableLines() {
        val bytes = byteArrayOf(1, 2) + "Fatal signal 11 (SIGSEGV)".toByteArray() + byteArrayOf(0, 7) + "libpdfium.so".toByteArray() + byteArrayOf(3) + "ab".toByteArray()
        val s = CrashReport.printable(bytes)
        assertEquals(listOf("Fatal signal 11 (SIGSEGV)", "libpdfium.so"), s.lines())
    }

    @Test
    fun noCrashNoDialog() {
        addExit(10, 400, 60_000)
        assertNull(CrashReport.pending(ctx))
        CrashReport.dialog = null
        Robolectric.buildActivity(HomeActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertNull(CrashReport.dialog)
    }
}
