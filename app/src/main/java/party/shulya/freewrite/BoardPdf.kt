package party.shulya.freewrite

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF на доску (0.12): листы PDF становятся обычными картинками доски — их можно двигать,
 * масштабировать, подписывать, они уходят коллегам и на сервер, как чертежи JPG.
 * Рисует встроенный в Android PdfRenderer — сторонние программы не нужны.
 */
object BoardPdf {

    /** Открытый PDF: сколько листов, размер листа в пунктах (1/72 дюйма), нарисовать лист. */
    interface Pages : Closeable {
        val count: Int
        fun size(page: Int): Pair<Int, Int>
        /** Лист целиком в картинку w×h на белом фоне. */
        fun render(page: Int, w: Int, h: Int): Bitmap
    }

    private class AndroidPages(file: File) : Pages {
        private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        private val renderer = try { PdfRenderer(fd) } catch (t: Throwable) { fd.close(); throw t }
        private val sizes = HashMap<Int, Pair<Int, Int>>()
        override val count: Int get() = renderer.pageCount

        // PdfRenderer умеет держать открытым только один лист — поэтому всё по очереди.
        @Synchronized override fun size(page: Int): Pair<Int, Int> = sizes.getOrPut(page) {
            renderer.openPage(page).use { it.width to it.height }
        }

        @Synchronized override fun render(page: Int, w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE) // у листа PDF прозрачный фон: без белого в JPEG он стал бы чёрным
            try {
                renderer.openPage(page).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            } catch (t: Throwable) {
                bmp.recycle(); throw t
            }
            return bmp
        }

        @Synchronized override fun close() {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    /** Открыть PDF. В проверках на компьютере подменяется: там нет PdfRenderer. */
    @JvmStatic var opener: (File) -> Pages = { AndroidPages(it) }

    fun open(file: File): Pages = opener(file)

    /** Сколько точек на дюйм у картинки листа: чертёж читается при сильном приближении. */
    const val DPI = 200f

    /** Больше за один раз не кладём: каждый лист — несколько секунд и 1–3 МБ (и на сервере). */
    const val MAX_PAGES = 30

    /** Папка для присланных файлов (разрешение на чужой файл временное — берём копию). */
    fun incomingDir(context: Context) = File(context.cacheDir, "incoming").apply { mkdirs() }

    /**
     * Скопировать файл к себе. PdfRenderer нужен файл с перемоткой, а мессенджеры и облака часто
     * отдают поток. Старые копии (больше суток) удаляются.
     */
    fun copyIn(context: Context, uri: Uri, ext: String): File {
        val dir = incomingDir(context)
        val old = System.currentTimeMillis() - 24 * 3600_000L
        dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        val f = File(dir, UUID.randomUUID().toString() + ext)
        try {
            (context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("файл не открылся")).use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        } catch (t: Throwable) {
            f.delete(); throw t
        }
        return f
    }

    /** Файл лежит в нашей папке присланных (только такие экран доски берёт из чужого вызова). */
    fun isIncoming(context: Context, f: File): Boolean =
        runCatching { f.canonicalFile.parentFile == incomingDir(context).canonicalFile }.getOrDefault(false)

    /** Имя файла, как его видит человек («План 3 этажа.pdf»). */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Что за файл — по первым байтам (тип от мессенджера бывает «просто файл»). */
    enum class Kind { PDF, IMAGE, OTHER }

    fun kindOf(f: File): Kind = runCatching {
        val b = ByteArray(1024)
        val n = f.inputStream().use { it.read(b) }
        if (n <= 0) return Kind.OTHER
        fun u(i: Int) = b[i].toInt() and 0xFF
        when {
            String(b, 0, n, Charsets.ISO_8859_1).contains("%PDF-") -> Kind.PDF
            n >= 3 && u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> Kind.IMAGE // JPEG
            n >= 4 && u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 -> Kind.IMAGE // PNG
            n >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> Kind.IMAGE
            n >= 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Kind.IMAGE // HEIC с телефона
            else -> Kind.OTHER
        }
    }.getOrDefault(Kind.OTHER)

    /** Наибольшая сторона картинки листа: 6144 px, если памяти с запасом, иначе 4096 (слабый телефон — 3072). */
    fun maxSide(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 4096
        if (am.isLowRamDevice) return 3072
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return if (mi.totalMem >= 5L shl 30) 6144 else 4096
    }

    /** Размер картинки листа: ~200 точек на дюйм, большая сторона — от 2048 до maxSide. */
    fun pixelSize(wPt: Int, hPt: Int, maxSide: Int): Pair<Int, Int> {
        val long = max(wPt, hPt).coerceAtLeast(1)
        val side = (long * DPI / 72f).roundToInt().coerceIn(minOf(2048, maxSide), maxSide)
        val k = side.toFloat() / long
        return (wPt * k).roundToInt().coerceAtLeast(1) to (hPt * k).roundToInt().coerceAtLeast(1)
    }

    /** Лист → картинка доски (JPEG 90 и уменьшенные копии). Не хватило памяти — помельче. */
    fun renderToImage(context: Context, pages: Pages, page: Int, maxSide: Int): Triple<String, Int, Int> {
        val (wPt, hPt) = pages.size(page)
        var limit = maxSide
        while (true) {
            val (w, h) = pixelSize(wPt, hPt, limit)
            val bmp = try {
                pages.render(page, w, h)
            } catch (e: OutOfMemoryError) {
                Trail.add("PDF: не хватило памяти на лист ${w}×${h}, пробую мельче")
                if (limit <= 2048) throw e
                limit = max(2048, (limit * 0.7f).roundToInt())
                continue
            }
            try {
                return BoardImages.importBitmap(context, bmp, quality = 90)
            } finally {
                bmp.recycle()
            }
        }
    }

    /** Миниатюра листа для выбора (большая сторона — side). */
    fun thumb(pages: Pages, page: Int, side: Int): Bitmap {
        val (wPt, hPt) = pages.size(page)
        val k = side.toFloat() / max(wPt, hPt).coerceAtLeast(1)
        return pages.render(page, (wPt * k).roundToInt().coerceAtLeast(1), (hPt * k).roundToInt().coerceAtLeast(1))
    }

    /** «1 лист», «3 листа», «12 листов». */
    fun sheets(n: Int): String = Ui.plural(n, "лист", "листа", "листов")
}

/**
 * Вставка PDF на экране доски: открыть, выбрать листы по миниатюрам, нарисовать выбранные
 * (с ходом работы и кнопкой «Остановить»), отдать готовые картинки доске.
 * Вся работа с PDF — в одном фоновом потоке (PdfRenderer не любит несколько сразу).
 */
class PdfInsert(
    private val activity: Activity,
    private val hint: (String) -> Unit,
    /** Готовые листы (имя файла, ширина, высота), сколько просили и сохранённый PDF (для плана таблицы). */
    private val onReady: (List<Triple<String, Int, Int>>, Int, Kept?) -> Unit,
) {
    /** PDF, сохранённый вместе с листами плана (0.26): файл в папке картинок и номера листов по порядку готовых. */
    class Kept(val file: String, val pages: List<Int>)

    /** Листы — под таблицу (0.26): сохранить сам PDF рядом, чтобы искать элементы по его линиям. */
    var keepPdf = false

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    /** Идёт работа с PDF (второй не начинаем). */
    var busy = false
        private set

    private fun dp(v: Float) = Ui.dp(activity, v)

    /** Открыть PDF-файл (наша копия; после вставки удаляется). */
    fun start(file: File, name: String?) {
        if (busy || closed) return
        busy = true
        hint("Открываю PDF…")
        exec.execute {
            val r = runCatching { BoardPdf.open(file).also { p -> if (p.count > 0) p.size(0) } }
            main.post {
                if (closed || activity.isDestroyed) { r.getOrNull()?.close(); file.delete(); return@post }
                r.onSuccess { p ->
                    when {
                        p.count <= 0 -> fail(file, p, "В PDF нет ни одного листа.")
                        p.count == 1 -> render(file, p, listOf(0))
                        else -> choose(file, p, name)
                    }
                }.onFailure { t ->
                    android.util.Log.w("FreeWrite", "pdf", t)
                    Trail.add("PDF не открылся: $t")
                    val why = when (t) {
                        is SecurityException -> "PDF защищён паролем. Откройте его в программе для PDF, сохраните копию без пароля и вставьте её."
                        is OutOfMemoryError -> "Не хватило памяти, чтобы открыть этот PDF."
                        else -> "Файл повреждён или это не PDF (${t.javaClass.simpleName})."
                    }
                    fail(file, null, why)
                }
            }
        }
    }

    private fun fail(file: File, pages: BoardPdf.Pages?, text: String) {
        pages?.let { p -> exec.execute { p.close() } }
        file.delete()
        busy = false
        AlertDialog.Builder(activity).setTitle("PDF не вставлен").setMessage(text).setPositiveButton("Понятно", null).show()
    }

    private fun finishWith(file: File, pages: BoardPdf.Pages) {
        exec.execute { pages.close(); file.delete() }
        busy = false
    }

    // ---------------------------------------------------------------- выбор листов

    private class Cell(val box: LinearLayout, val image: ImageView, val caption: TextView)

    private fun choose(file: File, pages: BoardPdf.Pages, name: String?) {
        val n = pages.count
        val selected = sortedSetOf<Int>()
        if (n <= 10) selected += 0 until n
        val phone = Input.isPhone(activity)
        val cellW = dp(if (phone) 96f else 132f)
        val thumbSide = minOf(cellW, 400)
        val cells = ArrayList<Cell>()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
        }
        val note = Ui.text(activity, "", 14f, Ui.INK2).apply { tag = "pdf_note" }
        content.addView(note)
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8f), 0, dp(8f)) }
        fun small(text: String, tag: String, onClick: () -> Unit) = Ui.text(activity, text, 14f, Ui.ACCENT, bold = true).apply {
            this.tag = tag
            background = Ui.rounded(activity, Ui.PAPER, Ui.RULE, 10f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener { onClick() }
        }
        content.addView(actions)
        val grid = Ui.FlowRow(activity).apply { hGap = dp(10f); vGap = dp(10f); setPadding(0, 0, 0, dp(12f)) }
        val scroll = ScrollView(activity).apply { addView(grid) }
        content.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        var dialog: AlertDialog? = null
        fun refresh() {
            for ((i, c) in cells.withIndex()) {
                val on = i in selected
                c.box.background = if (on) Ui.rounded(activity, Ui.ACCENT_SOFT, Ui.ACCENT, 10f) else Ui.rounded(activity, Ui.PAPER, Ui.RULE, 10f)
                c.caption.text = if (on) "✓ Лист ${i + 1}" else "Лист ${i + 1}"
                c.caption.setTextColor(if (on) Ui.ACCENT else Ui.INK2)
            }
            note.text = when {
                selected.isEmpty() -> "Отметьте листы, которые положить на доску. Каждый лист станет картинкой."
                else -> "Выбрано: ${BoardPdf.sheets(selected.size)} из $n." +
                    if (selected.size >= BoardPdf.MAX_PAGES) " За один раз — не больше ${BoardPdf.MAX_PAGES}." else ""
            }
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.let {
                it.text = if (selected.isEmpty()) "Положить" else "Положить (${selected.size})"
                it.isEnabled = selected.isNotEmpty()
            }
        }
        actions.addView(small("Все", "pdf_all") {
            selected.clear(); selected += (0 until n).take(BoardPdf.MAX_PAGES); refresh()
        })
        actions.addView(small("Снять все", "pdf_none") { selected.clear(); refresh() },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(10f) })

        for (i in 0 until n) {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6f), dp(6f), dp(6f), dp(4f))
                tag = "pdf_page_$i"
                isClickable = true
            }
            val img = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Ui.DESK)
            }
            box.addView(img, LinearLayout.LayoutParams(cellW, cellW))
            val cap = Ui.text(activity, "", 13f, Ui.INK2).apply { gravity = Gravity.CENTER; setPadding(0, dp(4f), 0, 0) }
            box.addView(cap, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            box.setOnClickListener {
                if (i in selected) selected -= i
                else if (selected.size >= BoardPdf.MAX_PAGES) hint("За один раз — не больше ${BoardPdf.MAX_PAGES} листов")
                else selected += i
                refresh()
            }
            grid.addView(box)
            cells += Cell(box, img, cap)
        }

        // Миниатюры — по одной в фоне, пока окно открыто.
        val showing = AtomicBoolean(true)
        val title = (name?.takeIf { it.isNotBlank() } ?: "PDF") + " — " + BoardPdf.sheets(n)
        val dlg = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(content)
            .setPositiveButton("Положить") { _, _ ->
                showing.set(false)
                val pick = selected.toList()
                if (pick.isEmpty()) finishWith(file, pages) else render(file, pages, pick)
            }
            .setNegativeButton("Отмена") { _, _ -> showing.set(false); finishWith(file, pages) }
            .setOnCancelListener { showing.set(false); finishWith(file, pages) }
            .create()
        dialog = dlg
        dlg.show()
        // Много листов — окно по высоте экрана, список прокручивается внутри.
        if (n > 6) dlg.window?.setLayout(Ui.MATCH, (activity.resources.displayMetrics.heightPixels * 0.85f).toInt())
        refresh()
        exec.execute {
            for (i in 0 until n) {
                if (!showing.get() || closed) break
                val bmp = runCatching { BoardPdf.thumb(pages, i, thumbSide) }.getOrNull() ?: continue
                main.post {
                    if (showing.get() && !closed) cells[i].image.apply { setImageBitmap(bmp); background = null } else bmp.recycle()
                }
            }
        }
    }

    // ---------------------------------------------------------------- листы → картинки

    private fun render(file: File, pages: BoardPdf.Pages, pick: List<Int>) {
        val cancelled = AtomicBoolean(false)
        val msg = Ui.text(activity, "", 16f, Ui.INK2).apply {
            tag = "pdf_progress"
            setPadding(dp(24f), dp(12f), dp(24f), dp(4f))
        }
        fun progress(done: Int) {
            msg.text = if (pick.size == 1) "Готовлю лист — несколько секунд…"
            else "Готово ${done} из ${pick.size}. Каждый лист — несколько секунд."
        }
        progress(0)
        val wait = AlertDialog.Builder(activity)
            .setTitle("Кладу на доску")
            .setView(msg)
            .setCancelable(false)
            .setNegativeButton(if (pick.size == 1) "Отмена" else "Остановить") { _, _ -> cancelled.set(true) }
            .show()
        val side = BoardPdf.maxSide(activity)
        val ctx = activity.applicationContext
        exec.execute {
            val out = ArrayList<Triple<String, Int, Int>>()
            val outPages = ArrayList<Int>()
            var failed = 0
            for (p in pick) {
                if (cancelled.get() || closed) break
                try {
                    out += BoardPdf.renderToImage(ctx, pages, p, side)
                    outPages += p
                    Trail.add("PDF: лист ${p + 1} готов (${out.last().second}×${out.last().third})")
                } catch (t: Throwable) {
                    android.util.Log.w("FreeWrite", "pdf page $p", t)
                    failed++
                    if (t is OutOfMemoryError) break
                }
                val done = out.size + failed
                main.post { if (!closed) progress(done) }
            }
            pages.close()
            var kept: Kept? = null
            if (keepPdf && out.isNotEmpty()) runCatching {
                val dir = java.io.File(ctx.filesDir, "note_images").apply { mkdirs() }
                val name = java.util.UUID.randomUUID().toString() + ".pdf"
                file.copyTo(java.io.File(dir, name))
                kept = Kept(name, outPages.toList())
            }
            keepPdf = false
            file.delete()
            main.post {
                busy = false
                if (closed || activity.isDestroyed) return@post
                runCatching { wait.dismiss() }
                if (cancelled.get() && out.isEmpty()) { hint("Отменено"); return@post }
                if (out.isEmpty()) {
                    AlertDialog.Builder(activity).setTitle("PDF не вставлен")
                        .setMessage("Не получилось нарисовать ни одного листа.").setPositiveButton("Понятно", null).show()
                    return@post
                }
                onReady(out, if (cancelled.get()) out.size else pick.size, kept)
            }
        }
    }

    fun close() {
        closed = true
        exec.shutdown()
    }
}
