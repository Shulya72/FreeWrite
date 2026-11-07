package party.shulya.freewrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Картинки доски — так, чтобы сотни чертежей не тормозили и не съедали память.
 *
 * - На диске у каждой картинки: оригинал (до 8192 px — чтобы мелкий текст чертежа читался
 *   при приближении) и уменьшенные копии 256, 1024 и 2048 px (`имя@256.jpg` …). У картинок,
 *   вставленных до 0.8.0, копии делаются сами при первом показе.
 * - В память грузится копия нужного размера — в фоне; пока грузится, рисуется любая уже
 *   загруженная (или серое место). Память ограничена: давно не видимые копии выгружаются.
 * - Если картинку приблизили сильнее, чем 2048 px, из оригинала вырезается и грузится только
 *   видимый кусок ([detail]).
 */
class BoardImages(context: Context, private val onReady: () -> Unit) {

    private val dir = File(context.filesDir, "note_images").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newFixedThreadPool(2)
    private val budget = min(Runtime.getRuntime().maxMemory() / 4, 160L shl 20).toInt()
    private val cache = object : LruCache<String, Bitmap>(budget) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pending = HashSet<String>()
    @Volatile private var closed = false

    /**
     * Лучшая уже загруженная копия картинки для показа шириной screenW пикселей экрана.
     * Если нужна копия покрупнее — заказывает её загрузку (по готовности — [onReady]).
     */
    fun get(img: BoardImage, screenW: Float): Bitmap? {
        val need = levelFor(img, screenW)
        cache.get(key(img.file, need))?.let { return it }
        request(img, need)
        for (l in LEVELS.reversed()) if (l != need) cache.get(key(img.file, l))?.let { return it }
        return cache.get(key(img.file, ORIGINAL))
    }

    /** Уровень копии для показа шириной screenW: 256/1024/2048, а у небольших картинок — сам оригинал. */
    private fun levelFor(img: BoardImage, screenW: Float): Int {
        val big = max(img.pxW, img.pxH)
        val k = if (img.pxW > 0) screenW / img.pxW * big else screenW
        for (l in LEVELS) if (l >= k) return if (big in 1..l) ORIGINAL else l
        return if (big in 1..LEVELS.last()) ORIGINAL else LEVELS.last()
    }

    private fun request(img: BoardImage, level: Int) {
        val key = key(img.file, level)
        if (closed || !pending.add(key)) return
        exec.execute {
            val bmp = try { loadLevel(img, level) } catch (_: Throwable) { null }
            main.post {
                pending.remove(key)
                if (bmp != null && !closed) {
                    cache.put(key, bmp)
                    onReady()
                }
            }
        }
    }

    private fun loadLevel(img: BoardImage, level: Int): Bitmap? {
        val orig = File(dir, img.file)
        if (level == ORIGINAL) return decodeFit(orig, 4096, Bitmap.Config.ARGB_8888)
        val f = levelFile(dir, img.file, level)
        if (!f.exists()) makeLevel(orig, level, f)
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inPreferredConfig = if (level >= 2048) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        })
    }

    // ---------------------------------------------------------------- кусок оригинала при сильном приближении

    class Detail(val file: String, val region: Rect, val sample: Int, val bitmap: Bitmap)

    private val details = object : LruCache<String, Detail>(min(budget / 3, 64 shl 20)) {
        override fun sizeOf(key: String, value: Detail) = value.bitmap.allocationByteCount
    }
    private var detailPending: String? = null

    /** Уже загруженный кусок оригинала этой картинки (если есть). */
    fun detail(img: BoardImage): Detail? = details.get(img.file)

    /**
     * Заказать кусок оригинала: region — видимая часть в пикселях оригинала, sample — во сколько раз
     * уменьшать (1, 2, 4…). Повторный заказ того же куска ничего не делает.
     */
    fun requestDetail(img: BoardImage, region: Rect, sample: Int) {
        if (closed) return
        val have = details.get(img.file)
        if (have != null && have.sample <= sample && have.region.contains(region)) return
        val key = img.file + region.toShortString() + sample
        if (detailPending == key) return
        detailPending = key
        // Берём с запасом по краям, чтобы небольшой сдвиг не требовал новой загрузки.
        val padX = region.width() / 4
        val padY = region.height() / 4
        val want = Rect(region.left - padX, region.top - padY, region.right + padX, region.bottom + padY)
        exec.execute {
            val f = File(dir, img.file)
            val d = try {
                val dec = BitmapRegionDecoder.newInstance(f.path)
                try {
                    val r = Rect(want)
                    if (!r.intersect(0, 0, dec.width, dec.height)) null
                    else dec.decodeRegion(r, BitmapFactory.Options().apply { inSampleSize = sample })?.let { Detail(img.file, r, sample, it) }
                } finally {
                    dec.recycle()
                }
            } catch (_: Throwable) {
                null
            } ?: try { cropRegion(img.file, f, want, sample) } catch (_: Throwable) { null }
            main.post {
                if (detailPending == key) detailPending = null
                if (d != null && !closed) {
                    details.put(img.file, d)
                    onReady()
                }
            }
        }
    }

    /**
     * Запасной путь, если вырезать кусок прямо из файла не получилось: прочитать картинку
     * (не больше 4096 px) и вырезать кусок из неё.
     */
    private fun cropRegion(file: String, f: File, want: Rect, sample: Int): Detail? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        if (b.outWidth <= 0) return null
        var s = sample
        while (max(b.outWidth, b.outHeight) / s > 4096) s *= 2
        val full = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val r = Rect(want)
        if (!r.intersect(0, 0, b.outWidth, b.outHeight)) { full.recycle(); return null }
        val k = full.width.toFloat() / b.outWidth
        val x = (r.left * k).toInt().coerceIn(0, full.width - 1)
        val y = (r.top * k).toInt().coerceIn(0, full.height - 1)
        val w = (r.width() * k).toInt().coerceIn(1, full.width - x)
        val h = (r.height() * k).toInt().coerceIn(1, full.height - y)
        val crop = Bitmap.createBitmap(full, x, y, w, h)
        if (crop !== full) full.recycle()
        return Detail(file, r, s, crop)
    }

    fun close() {
        closed = true
        exec.shutdownNow()
        cache.evictAll()
        details.evictAll()
    }

    companion object {
        val LEVELS = intArrayOf(256, 1024, 2048)
        private const val ORIGINAL = 0
        /** Самая большая сторона оригинала на диске. */
        const val MAX_ORIGINAL = 8192

        private fun key(file: String, level: Int) = "$file@$level"

        fun levelFile(dir: File, file: String, level: Int) = File(dir, file.substringBeforeLast('.') + "@" + level + ".jpg")

        /** Удалить картинку и её уменьшенные копии. */
        fun deleteFiles(dir: File, file: String) {
            File(dir, file).delete()
            for (l in LEVELS) levelFile(dir, file, l).delete()
        }

        /** Прочитать файл, уменьшив так, чтобы большая сторона была не больше maxSide. */
        fun decodeFit(f: File, maxSide: Int, config: Bitmap.Config): Bitmap? {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            if (b.outWidth <= 0) return null
            var sample = 1
            while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = config })
                ?: return null
            return scaleDown(bmp, maxSide)
        }

        private fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
            val big = max(bmp.width, bmp.height)
            if (big <= maxSide) return bmp
            val k = maxSide.toFloat() / big
            val out = Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt().coerceAtLeast(1), (bmp.height * k).roundToInt().coerceAtLeast(1), true)
            if (out !== bmp) bmp.recycle()
            return out
        }

        /** Сделать копию уровня level из оригинала (JPEG 85). */
        fun makeLevel(orig: File, level: Int, out: File) {
            val bmp = decodeFit(orig, level, Bitmap.Config.ARGB_8888) ?: return
            val tmp = File(out.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        }

        private fun makeLevels(dir: File, name: String) {
            val orig = File(dir, name)
            for (l in LEVELS) makeLevel(orig, l, levelFile(dir, name, l))
        }

        /**
         * Вставить картинку из галереи на доску: оригинал (JPEG без поворота копируется как есть,
         * до 8192 px; иначе — до 4096 px с поворотом по EXIF) и уменьшенные копии.
         * Возвращает имя файла и размер оригинала или null.
         */
        fun import(context: Context, uri: Uri): Triple<String, Int, Int>? {
            val dir = File(context.filesDir, "note_images").apply { mkdirs() }
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            (resolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val degrees = try {
                resolver.openInputStream(uri)?.use {
                    when (android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
                    )) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Throwable) {
                0f
            }
            val name = UUID.randomUUID().toString() + ".jpg"
            val target = File(dir, name)
            val big = max(bounds.outWidth, bounds.outHeight)
            val w: Int
            val h: Int
            if (degrees == 0f && bounds.outMimeType == "image/jpeg" && big <= MAX_ORIGINAL) {
                // Чертёж в JPEG — копируем как есть: никакой потери качества.
                (resolver.openInputStream(uri) ?: return null).use { input -> target.outputStream().use { input.copyTo(it) } }
                w = bounds.outWidth; h = bounds.outHeight
            } else {
                var sample = 1
                while (big / (sample * 2) >= 4096) sample *= 2
                var bmp = (resolver.openInputStream(uri) ?: return null).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return null
                bmp = scaleDown(bmp, 4096)
                if (degrees != 0f) {
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
                    if (rotated !== bmp) bmp.recycle()
                    bmp = rotated
                }
                target.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                w = bmp.width; h = bmp.height
                bmp.recycle()
            }
            makeLevels(dir, name)
            return Triple(name, w, h)
        }

        /** Сохранить готовую картинку (тестовая доска): оригинал + копии. */
        fun importBitmap(context: Context, bmp: Bitmap): Triple<String, Int, Int> {
            val dir = File(context.filesDir, "note_images").apply { mkdirs() }
            val name = UUID.randomUUID().toString() + ".jpg"
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            makeLevels(dir, name)
            return Triple(name, bmp.width, bmp.height)
        }
    }
}
