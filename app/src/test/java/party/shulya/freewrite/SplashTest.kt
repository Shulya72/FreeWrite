package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.junit.After
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration
import kotlin.math.roundToInt

/**
 * 0.29.2: заставка при запуске — картинка ViaDesk во всю ширину на тёмно-синем (Владимир: «хочу, чтоб было красиво»).
 * Только при запуске с рабочего стола и один раз за запуск приложения; окно «Как вас зовут?» ждёт конца заставки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w674dp-h1078dp-port-380dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        ctx.getSharedPreferences("profile", 0).edit().clear().commit()
        HomeActivity.crashOffered = true
        Splash.enabled = true
        Splash.shown = false
    }

    @After
    fun tearDown() {
        Splash.enabled = false
        Splash.shown = false
    }

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(ctx, HomeActivity::class.java)

    private fun content(a: HomeActivity): ViewGroup = a.findViewById(android.R.id.content)
    /** Заставка лежит прямо в окне (и под строкой состояния), а не в содержимом. */
    private fun decor(a: HomeActivity): ViewGroup = a.window.decorView as ViewGroup

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun bannerOnLaunchThenHomeAndNameAfterIt() {
        val a = Robolectric.buildActivity(HomeActivity::class.java, launcher()).setup().get()
        val s = decor(a).findViewWithTag<View>("splash")
        assertNotNull("заставка при запуске с рабочего стола", s)
        assertEquals(Splash.COLOR, a.window.statusBarColor)
        assertEquals(Splash.COLOR, a.window.navigationBarColor)
        assertNull("«Как вас зовут?» — после заставки", ShadowAlertDialog.getLatestAlertDialog())

        pass(700)
        val bg = s.findViewWithTag<ImageView>("splash_bg")
        val img = s.findViewWithTag<ImageView>("splash_hero")
        assertTrue("фон проявился", bg.alpha > 0.95f)
        assertTrue("знак с надписью проявились", img.alpha > 0.95f)
        assertEquals("фон на всё окно", decor(a).width, bg.width)
        assertEquals(decor(a).height, bg.height)
        assertEquals("планшет — планшетный фон", R.drawable.splash_bg_tablet, Splash.bgFor(s.width, s.height))
        assertEquals("знак с надписью — 0,8 ширины", (Splash.HERO_W * s.width).roundToInt().toFloat(), img.width.toFloat(), 1f)
        assertTrue("посередине по ширине", kotlin.math.abs((img.left + img.right) / 2 - s.width / 2) <= 1)
        assertEquals("середина баннера — посередине экрана", s.height / 2f, img.top + Splash.HERO_CENTER * img.height, 2f)
        shot(s, "splash-tablet.png")

        pass(1500)
        assertNull("заставка растаяла", decor(a).findViewWithTag<View>("splash"))
        assertEquals(0xFFFBFCFD.toInt(), a.window.statusBarColor)
        assertNotNull("теперь — «Как вас зовут?»", ShadowAlertDialog.getLatestAlertDialog())

        // Тот же запуск приложения — второй раз не показывается.
        val b = Robolectric.buildActivity(HomeActivity::class.java, launcher()).setup().get()
        assertNull(decor(b).findViewWithTag<View>("splash"))
    }

    @Test
    fun tapSkipsAndNotFromNotification() {
        Profile.save(ctx, "Владимир", "Шульмин")
        val a = Robolectric.buildActivity(HomeActivity::class.java, launcher()).setup().get()
        val s = decor(a).findViewWithTag<View>("splash")
        assertNotNull(s)
        pass(100)
        s.performClick()
        pass(400)
        assertNull("касание — сразу дальше", decor(a).findViewWithTag<View>("splash"))

        // Из уведомления календаря и после поворота/восстановления — без заставки.
        Splash.shown = false
        val n = Robolectric.buildActivity(HomeActivity::class.java,
            Intent(ctx, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_PLAN_DAY, "2026-11-20")).setup().get()
        assertNull(decor(n).findViewWithTag<View>("splash"))
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    fun phoneShot() {
        Profile.save(ctx, "Владимир", "Шульмин")
        val a = Robolectric.buildActivity(HomeActivity::class.java, launcher()).setup().get()
        pass(700)
        val s = decor(a).findViewWithTag<View>("splash")
        assertEquals("телефон — телефонный фон", R.drawable.splash_bg_phone, Splash.bgFor(s.width, s.height))
        val img = s.findViewWithTag<ImageView>("splash_hero")
        assertEquals((Splash.HERO_W * s.width).roundToInt().toFloat(), img.width.toFloat(), 1f)
        shot(s, "splash-phone.png")
    }

    @Test
    fun backgroundForScreenShape() {
        assertEquals(R.drawable.splash_bg_tablet, Splash.bgFor(1600, 2560))
        assertEquals(R.drawable.splash_bg_phone, Splash.bgFor(1080, 2340))
        assertEquals(R.drawable.splash_bg_land, Splash.bgFor(2560, 1600))
        // набок знак не шире 1,1 высоты × 0,8
        val (w, h) = Splash.heroSize(2560, 1600)
        assertEquals((Splash.HERO_W * 1760).roundToInt(), w)
        assertTrue("высота по пропорции", h in 420..432)
    }
}
