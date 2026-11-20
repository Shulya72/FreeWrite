package party.shulya.freewrite

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.roundToInt

/**
 * Заставка при запуске (0.29.2, Владимир: «при запуске такая иконка — хочу, чтоб было красиво», прислал картинку ViaDesk).
 * Системная заставка Android 12+ — только тёмно-синий фон без значка (на HarmonyOS значок стоял на белом квадрате), сразу за
 * ней — эта: картинка `splash_banner` во всю ширину экрана на том же фоне (края картинки заранее переходят в фон), проявляется,
 * держится ~1 с, потом заставка тает и виден главный экран. Касание — сразу дальше. Строки состояния и навигации на это время
 * тёмные. Один раз за запуск приложения и только при запуске с рабочего стола. Окна, которые главный экран показывает при
 * запуске (имя, отчёт о вылете), ждут конца заставки — [after].
 */
class Splash(private val a: Activity) {

    private var root: FrameLayout? = null
    private val waiting = ArrayList<() -> Unit>()
    private var done = false

    /** Заставка на экране (ещё не растаяла). */
    val active get() = root != null

    /** Сделать после заставки (или сразу, если её нет). */
    fun after(fn: () -> Unit) { if (active) waiting += fn else fn() }

    fun show() {
        shown = true
        val dm = a.resources.displayMetrics
        val pic = a.getDrawable(R.drawable.splash_banner)
        val w = minOf(dm.widthPixels, (dm.heightPixels * 1.1f).toInt())
        val h = if (pic != null && pic.intrinsicWidth > 0) (w.toFloat() * pic.intrinsicHeight / pic.intrinsicWidth).roundToInt() else w / 4
        val img = ImageView(a).apply {
            tag = "splash_banner"
            setImageDrawable(pic)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "ViaDesk — единое рабочее пространство для строительных и инженерных команд"
            alpha = 0f; scaleX = 0.97f; scaleY = 0.97f
        }
        val r = FrameLayout(a).apply {
            tag = "splash"
            setBackgroundColor(COLOR)
            isClickable = true
            setOnClickListener { finish(fast = true) }
            addView(img, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        }
        root = r
        a.addContentView(r, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bars(dark = true)
        img.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(FADE_IN).setInterpolator(DecelerateInterpolator()).start()
        r.postDelayed({ finish(fast = false) }, HOLD)
        runCatching { Trail.add("Заставка") }
    }

    private fun finish(fast: Boolean) {
        val r = root ?: return
        if (done) return
        done = true
        r.isClickable = false
        r.animate().alpha(0f).setDuration(if (fast) 160L else FADE_OUT).withEndAction {
            (r.parent as? ViewGroup)?.removeView(r)
            root = null
            bars(dark = false)
            val list = ArrayList(waiting); waiting.clear()
            for (fn in list) if (!a.isFinishing && !a.isDestroyed) fn()
        }.start()
    }

    /** Строки состояния и навигации: на заставке — её цвета, потом — как в теме (светлые, тёмные значки). */
    private fun bars(dark: Boolean) = runCatching {
        val win = a.window
        win.statusBarColor = if (dark) COLOR else LIGHT
        win.navigationBarColor = if (dark) COLOR else LIGHT
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            win.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            win.decorView.systemUiVisibility = if (dark) win.decorView.systemUiVisibility and flags.inv()
                else win.decorView.systemUiVisibility or flags
        }
    }

    companion object {
        /** Фон заставки = фон картинки (#0A2133); тот же цвет — у системной заставки и окна до первого кадра. */
        const val COLOR = 0xFF0A2133.toInt()
        /** Строки состояния и навигации в теме (#FBFCFD). */
        private const val LIGHT = 0xFFFBFCFD.toInt()
        private const val FADE_IN = 260L
        /** С начала показа до начала таяния. */
        private const val HOLD = 1100L
        private const val FADE_OUT = 320L

        /** Уже показывали в этом запуске приложения. */
        internal var shown = false
        /** В проверках (Robolectric) выключена, проверка заставки включает. */
        internal var enabled = Build.FINGERPRINT != "robolectric"

        /** Показывать ли: первый запуск главного экрана с рабочего стола. */
        fun wanted(a: Activity, saved: Bundle?): Boolean {
            val i = a.intent ?: return false
            return enabled && !shown && saved == null && i.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_LAUNCHER)
        }
    }
}
