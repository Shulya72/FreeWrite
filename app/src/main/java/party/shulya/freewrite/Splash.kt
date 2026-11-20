package party.shulya.freewrite

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.roundToInt

/**
 * Заставка при запуске (0.29.2, Владимир: «при запуске такая иконка — хочу, чтоб было красиво»; из вариантов выбрал «Ночную
 * стройку»: «оставляем надпись, логотип и город»). Системная заставка Android 12+ — только тёмно-синий фон без значка (на
 * HarmonyOS значок стоял на белом квадрате), сразу за ней — эта, на весь экран вместе с полосами сверху и снизу:
 *  - фон `splash_bg_tablet` / `splash_bg_phone` / `splash_bg_land` (ночное небо, внизу город с кранами) — какой ближе к экрану,
 *    по размеру экрана с обрезкой краёв;
 *  - поверх — знак и надпись `splash_hero` (векторные, отрисованы в 3× и уменьшены, прозрачный фон) посередине, шириной 0,8 экрана.
 * Фон проявляется, надпись следом, через [HOLD] заставка тает и виден главный экран. Касание — сразу дальше. Один раз за запуск
 * приложения и только при запуске с рабочего стола. Окна, которые главный экран показывает при запуске (имя, отчёт о вылете),
 * ждут конца заставки — [after]. Исходники картинок — `design/viadesk/splash/`.
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
        val bg = ImageView(a).apply {
            tag = "splash_bg"
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        val hero = ImageView(a).apply {
            tag = "splash_hero"
            setImageResource(R.drawable.splash_hero)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "ViaDesk — единое рабочее пространство для строительных и инженерных команд"
            alpha = 0f; scaleX = 0.97f; scaleY = 0.97f
        }
        val r = Stage(a, bg, hero).apply {
            tag = "splash"
            setBackgroundColor(COLOR)
            isClickable = true
            setOnClickListener { finish(fast = true) }
        }
        root = r
        // Прямо в окно, а не в содержимое: картинка и под строкой состояния, и под кнопками навигации.
        val decor = a.window.decorView as ViewGroup
        decor.addView(r, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bars(dark = true)
        bg.animate().alpha(1f).setDuration(FADE_IN).start()
        hero.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90).setDuration(FADE_IN + 60).setInterpolator(DecelerateInterpolator()).start()
        r.postDelayed({ finish(fast = false) }, HOLD)
        runCatching { Trail.add("Заставка") }
    }

    /** Раскладка заставки: фон — на весь экран (картинка под его форму), знак с надписью — посередине. */
    private class Stage(ctx: Activity, val bg: ImageView, val hero: ImageView) : FrameLayout(ctx) {
        private var bgRes = 0
        init { addView(bg); addView(hero) }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
            setMeasuredDimension(w, h)
            val res = bgFor(w, h)
            if (res != bgRes) { bgRes = res; bg.setImageResource(res) }
            bg.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            val (hw, hh) = heroSize(w, h)
            hero.measure(MeasureSpec.makeMeasureSpec(hw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l; val h = b - t
            bg.layout(0, 0, w, h)
            val hw = hero.measuredWidth; val hh = hero.measuredHeight
            val left = (w - hw) / 2
            val top = (h / 2f - HERO_CENTER * hh).roundToInt()
            hero.layout(left, top, left + hw, top + hh)
        }
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
        private const val HOLD = 1300L
        /** Знак с надписью — кусок 1236 × 372 из картинки-баннера шириной 1536 (тени и свечение — с запасом). */
        internal const val HERO_W = 1236f / 1536f
        private const val HERO_ASPECT = 372f / 1236f
        /** Где в картинке знака середина баннера (по высоте, доля сверху): баннер 406, кусок начинается с 40. */
        internal const val HERO_CENTER = (203f - 40f) / 372f

        /** Фон под форму экрана: набок — пейзажный, вытянутый телефон — телефонный, иначе планшетный. */
        internal fun bgFor(w: Int, h: Int): Int = when {
            w > h -> R.drawable.splash_bg_land
            h > w * 1.85f -> R.drawable.splash_bg_phone
            else -> R.drawable.splash_bg_tablet
        }

        /** Размер знака с надписью: 0,8 ширины баннера, баннер — во всю ширину экрана (набок — не шире 1,1 высоты). */
        internal fun heroSize(w: Int, h: Int): Pair<Int, Int> {
            val hw = (HERO_W * minOf(w.toFloat(), h * 1.1f)).roundToInt()
            return hw to (hw * HERO_ASPECT).roundToInt()
        }
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
