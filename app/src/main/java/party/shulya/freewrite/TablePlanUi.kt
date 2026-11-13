package party.shulya.freewrite

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Идёт обводка захватки на листе плана (0.23): инструмент, зазор, готовые куски (доли картинки целиком), точки. */
class PlanEdit(val t: BoardTable, val sheet: PlanSheet, val uch: String, val zah: String) {
    var tool = MAGIC
    /** Номер ступени зазора ([GAPS]): по умолчанию закрываются проёмы до ~20 px (дверь на листе A1). */
    var gap = 3
    val pieces = ArrayList<FloatArray>()
    val points = ArrayList<Float>()
    /** Протяжка пальцем/пером сейчас (координаты доски). */
    var from: PointF? = null
    var to: PointF? = null
    /** След кисти сейчас (x, y доски). */
    val path = ArrayList<Float>()
    /** Волшебная заливка считается. */
    var busy = false
    /** Выбранный цвет захватки (0.23.1; null — как у её областей или следующий по порядку). */
    var color: Int? = null
    /** Разметка элементов (0.26): куски становятся элементами без захватки; ножницы, «убрать». */
    var elements = false
    /** Привязка к углам и пересечениям линий чертежа (0.26.1) — по PDF листа; готовится в фоне. */
    @Volatile var snap: PlanSnap? = null

    companion object {
        const val MAGIC = 0
        const val RECT = 1
        const val CIRCLE = 2
        const val POINTS = 3
        /** Кисть по стенам (0.23.1): провести вдоль стен — берутся стены под кистью. */
        const val BRUSH = 4
        /** Полуширина кисти на экране, dp. */
        const val BRUSH_DP = 14f
        /** Отмечать элементы захватки (0.26): касание — элемент, провести — все под пальцем. */
        const val ASSIGN = 5
        /** Шов / ножницы (0.26): провести поперёк элемента — разрезать. */
        const val SCISSORS = 6
        /** Убрать элемент из разметки (0.26). */
        const val ERASE = 7
        /** Зазор по ступеням — пикселей на картинке анализа (большая сторона до 2048); закрывается щель до 2× зазора. */
        val GAPS = intArrayOf(0, 3, 6, 10, 15, 22)

        /** Инструменты, которым нужен след пальца/пера. */
        fun pathTool(t: Int) = t == BRUSH || t == ASSIGN || t == ERASE

        /** Где точка притягивается к углам (лупа с привязкой). */
        fun snapTool(t: Int) = t == POINTS || t == RECT || t == CIRCLE
    }
}

/**
 * План под таблицей на экране доски (0.23): добавить лист (картинка или лист PDF) с обрезкой, вкладки, переключатель
 * цвета, обвести захватку (волшебная заливка, прямоугольник, круг, по точкам), касание области — её строки,
 * касание номера захватки — её области.
 */
class PlanUi(private val a: BoardActivity) {
    private val b: Board get() = a.board
    private val v: BoardView get() = a.view
    private fun dp(x: Float) = Ui.dp(a, x)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "plan").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val imageDir get() = File(a.filesDir, "note_images")

    /** Таблица, для которой выбирают картинку/PDF (результат выбора файла придёт в [onSheets]). */
    var target: BoardTable? = null
        internal set

    fun clearTarget() { target = null }

    /** «Отменить» во время обводки — последний кусок или точку; false — нечего (тогда — отмена доски). */
    fun undoPiece(): Boolean {
        val pe = v.planEdit ?: return false
        if (pe.points.isNotEmpty()) { pe.points.removeAt(pe.points.size - 1); pe.points.removeAt(pe.points.size - 1) }
        else if (pe.pieces.isNotEmpty()) pe.pieces.removeAt(pe.pieces.size - 1)
        else return pe.tool != PlanEdit.ASSIGN && pe.tool != PlanEdit.SCISSORS && pe.tool != PlanEdit.ERASE && !pe.elements
        v.invalidate()
        return true
    }

    /** Режим, который «Отменить» доски не закрывает (отметка, ножницы, «убрать» — каждое касание сразу на доске). */
    fun keepOnUndo(pe: PlanEdit) = pe.pieces.isEmpty() && pe.points.isEmpty() &&
        (pe.tool == PlanEdit.ASSIGN || pe.tool == PlanEdit.SCISSORS || pe.tool == PlanEdit.ERASE || pe.elements)

    /** После отмены доски — тот же режим на восстановленной таблице и листе. */
    fun rebind(old: PlanEdit) {
        val t = b.tables.firstOrNull { it.id == old.t.id }
        val sh = t?.sheets?.firstOrNull { it.id == old.sheet.id }
        if (t == null || sh == null) { v.planEdit = null; a.hideModeBar(); return }
        val pe = PlanEdit(t, sh, old.uch, old.zah).also { it.tool = old.tool; it.elements = old.elements; it.color = old.color; it.gap = old.gap; it.snap = old.snap }
        v.planEdit = pe
        v.planHover = null
        if (pe.tool == PlanEdit.ASSIGN) v.markedRecs = IdTables.zahRows(b, t, pe.uch, pe.zah).toSet()
        bar(pe)
    }

    /** Окно обрезки (для проверок). */
    internal var cropDialog: Dialog? = null
    internal var cropView: PlanCropView? = null

    // ---------------------------------------------------------------- листы

    /** «+ план» / «+ лист»: откуда взять. */
    fun addSheet(t: BoardTable) {
        AlertDialog.Builder(a).setTitle(if (t.sheets.isEmpty()) "План под таблицей" else "Ещё лист плана")
            .setItems(arrayOf("Лист PDF — чертёж проекта", "Картинка — JPG, фото")) { _, which ->
                target = t
                a.pickForPlan(pdf = which == 0)
            }
            .setNegativeButton("Отмена", null).show()
    }

    /** Картинки или отрисованные листы PDF для таблицы [target]: по одному — обрезка, потом лист. */
    fun onSheets(blocks: List<Triple<String, Int, Int>>, kept: PdfInsert.Kept? = null) {
        val t = target?.takeIf { tt -> b.tables.any { it === tt } } ?: run { target = null; kept?.let { File(imageDir, it.file).delete() }; return }
        target = null
        if (blocks.isEmpty()) { a.hint("Не удалось открыть картинку"); return }
        val queue = ArrayDeque(blocks.withIndex().toList())
        fun next() {
            val (bi, bl) = queue.removeFirstOrNull() ?: return
            val sh = PlanSheet(bl.first, bl.second, bl.third)
            // PDF листа (0.26) — для поиска элементов по линиям.
            if (kept != null && bi < kept.pages.size) { sh.pdf = kept.file; sh.page = kept.pages[bi] }
            cropSheet(sh, isNew = true) { ok ->
                if (ok) {
                    a.pushUndo()
                    t.sheets += sh
                    t.tab = t.sheets.size - 1
                    a.changed()
                    if (queue.isEmpty()) {
                        showSheet(t)
                        if (sh.pdf.isNotEmpty()) offerFind(t)
                        else a.hint("План под таблицей. Обведите захватки: «Обвести захватку» или касание номера захватки в таблице", 5000)
                    }
                }
                next()
            }
        }
        next()
    }

    /** Обрезка листа: при вставке и потом («Обрезать план»). done(true) — нажали «Готово». */
    fun cropSheet(sh: PlanSheet, isNew: Boolean = false, done: (Boolean) -> Unit) {
        val f = File(imageDir, sh.file)
        val lvl = BoardImages.levelFile(imageDir, sh.file, BoardImages.LEVELS.last())
        val src = if (lvl.isFile) lvl else f
        val bmp = BoardImages.decodeFit(src, 2048, Bitmap.Config.ARGB_8888)
        if (bmp == null) { a.hint("Не удалось открыть картинку"); done(false); return }
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val cv = PlanCropView(a, bmp, floatArrayOf(sh.cl, sh.ct, sh.cr, sh.cb))
        val root = FrameLayout(a).apply { setBackgroundColor(Color.rgb(40, 44, 52)) }
        root.addView(cv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH).apply { bottomMargin = dp(64f); topMargin = dp(52f) })
        root.addView(Ui.text(a, if (isNew) "Оставьте только план: тяните углы и края рамки — рамка листа, штамп, разрезы отрежутся"
            else "Обрезка плана: тяните углы и края рамки", 15f, Color.WHITE).apply {
            setPadding(dp(16f), dp(14f), dp(16f), dp(8f))
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))
        var answered = false
        fun close(ok: Boolean) {
            if (answered) return
            answered = true
            if (ok) { val c = cv.crop; sh.setCrop(c[0], c[1], c[2], c[3]) }
            d.dismiss()
            cropDialog = null; cropView = null
            done(ok)
        }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
            addView(Ui.pill(a, "Весь лист", Ui.INK, Ui.DESK).apply { tag = "pcrop_all"; setOnClickListener { cv.reset() } })
            addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
            addView(Ui.pill(a, "Отмена", Ui.INK, Ui.DESK).apply { tag = "pcrop_cancel"; setOnClickListener { close(false) } },
                LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(8f) })
            addView(Ui.pill(a, if (isNew) "Готово — под таблицу" else "Готово", Color.WHITE, Ui.ACCENT).apply { tag = "pcrop_done"; setOnClickListener { close(true) } })
        }
        root.addView(bar, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        d.setContentView(root)
        d.setOnCancelListener { close(false) }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        cropDialog = d; cropView = cv
        d.show()
    }

    /** Показать лист таблицы на экране, если его не видно. */
    fun showSheet(t: BoardTable) {
        val r = v.tableDraw.sheetRect(b, t, RectF()) ?: return
        val screen = RectF(v.wx(0f), v.wy(0f), v.wx(v.width.toFloat()), v.wy(v.height.toFloat()))
        if (!screen.contains(r)) {
            val all = v.tableDraw.bounds(b, t, RectF())
            v.fitRect(all.left, r.top - (r.top - all.top).coerceAtMost(r.height() * 0.15f), all.right, r.bottom + 20f * t.scale)
        }
        v.invalidate()
    }

    fun setTab(t: BoardTable, i: Int) {
        if (i !in t.sheets.indices || t.tab == i) return
        endOutline(save = false)
        t.tab = i
        t.lay = null
        v.invalidate()
    }

    /** Цвет областей: по ИГС / свой. */
    fun colorMenu(t: BoardTable) {
        val items = arrayOf("По ИГС — видно, что сдано, что висит", "Свой цвет у каждой захватки")
        AlertDialog.Builder(a).setTitle("Цвет захваток на плане")
            .setSingleChoiceItems(items, if (t.zoneColor == TablePlan.OWN) 1 else 0) { d, which ->
                val nv = if (which == 1) TablePlan.OWN else ""
                if (nv != t.zoneColor) { a.pushUndo(); t.zoneColor = nv; a.changed() }
                d.dismiss()
            }
            .setNegativeButton("Отмена", null).show()
    }

    /** Меню листа (кнопка «План» на панели таблицы): обрезать, название, убрать. */
    fun sheetMenu(t: BoardTable) {
        val sh = TablePlan.current(t) ?: run { addSheet(t); return }
        val name = v.tableDraw.sheetName(t, t.sheets.indexOf(sh))
        AlertDialog.Builder(a).setTitle("План · $name")
            .setItems(arrayOf("Обрезать", "Название вкладки", "+ ещё лист", "Убрать этот лист")) { _, which ->
                when (which) {
                    0 -> { val copy = sh.copy(); cropSheet(copy) { ok -> if (ok && (copy.cl != sh.cl || copy.ct != sh.ct || copy.cr != sh.cr || copy.cb != sh.cb)) {
                        a.pushUndo(); sh.setCrop(copy.cl, copy.ct, copy.cr, copy.cb); t.lay = null; a.changed() } } }
                    1 -> renameSheet(t, sh)
                    2 -> addSheet(t)
                    3 -> {
                        val n = b.zones.count { it.t == t.id && it.s == sh.id }
                        AlertDialog.Builder(a).setMessage("Убрать лист «$name»" + (if (n > 0) " и ${Ui.plural(n, "область", "области", "областей")} захваток на нём" else "") + "? «Отменить» вернёт.")
                            .setPositiveButton("Убрать") { _, _ ->
                                endOutline(save = false)
                                a.pushUndo()
                                t.sheets.remove(sh)
                                b.zones.removeAll { it.t == t.id && it.s == sh.id }
                                t.tab = t.tab.coerceIn(0, max(0, t.sheets.size - 1))
                                a.changed()
                            }
                            .setNegativeButton("Отмена", null).show()
                    }
                }
            }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun renameSheet(t: BoardTable, sh: PlanSheet) {
        val e = EditText(a).apply { setText(sh.name); setSelection(text.length); isSingleLine = true; hint = "например: План на отм. +3.300"; tag = "sheet_name" }
        AlertDialog.Builder(a).setTitle("Название вкладки")
            .setView(LinearLayout(a).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(e, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)) })
            .setPositiveButton("Готово") { _, _ -> a.pushUndo(); sh.name = e.text.toString().trim(); t.lay = null; a.changed() }
            .setNegativeButton("Отмена", null).show()
    }

    // ---------------------------------------------------------------- касания плана и захваток

    /** Касание листа (не в режиме обводки): область — подсветить её захватку и строки. */
    fun onSheetTap(t: BoardTable, fx: Float, fy: Float) {
        val sh = TablePlan.current(t) ?: return
        val z = b.zones.lastOrNull { it.t == t.id && it.s == sh.id && it.contains(fx, fy) }
        if (z == null) { clearMarks(); a.hideModeBar(); return }
        if (z.free) { a.hint("Элемент ещё не в захватке. Коснитесь квадратика захватки в таблице — и отмечайте элементы", 3500); return }
        showZah(t, z.uch, z.zah, moveCamera = false)
    }

    /**
     * Захватка на плане: области подсвечены (вкладка — где они), строки таблицы подсвечены; внизу — что с ней, кнопки
     * «Обвести ещё», «Стереть с плана», «Готово».
     */
    fun showZah(t: BoardTable, uch: String, zah: String, moveCamera: Boolean = true) {
        val zones = TablePlan.zonesOf(b, t, uch, zah)
        if (zones.isNotEmpty() && zones.none { it.s == TablePlan.current(t)?.id }) {
            t.sheets.indexOfFirst { it.id == zones[0].s }.takeIf { it >= 0 }?.let { t.tab = it; t.lay = null }
        }
        v.markedZones = zones.toSet()
        v.markedRecs = IdTables.zahRows(b, t, uch, zah).toSet()
        if (moveCamera) showSheet(t)
        v.invalidate()
        a.showModeBar(TablePlan.zoneInfo(b, t, uch, zah), listOf(
            Ui.pill(a, "Обвести ещё", Ui.INK, Ui.DESK).apply { tag = "zone_more"; setOnClickListener { startOutline(t, uch, zah) } },
            Ui.pill(a, "● Цвет", zones.firstOrNull()?.color ?: Ui.INK, Ui.DESK).apply { tag = "zone_color"; setOnClickListener { recolorZah(t, uch, zah) } },
            Ui.pill(a, "Стереть с плана", Ui.INK, Ui.DESK).apply { tag = "zone_erase"; setOnClickListener { eraseZah(t, uch, zah) } },
            Ui.pill(a, "Готово", Color.WHITE, Ui.ACCENT).apply { tag = "zone_done"; setOnClickListener { clearMarks(); a.hideModeBar() } },
        ))
    }

    private fun eraseZah(t: BoardTable, uch: String, zah: String) {
        val sh = TablePlan.current(t) ?: return
        val k = IdTables.zkey(uch, zah)
        if (b.zones.none { it.t == t.id && it.s == sh.id && it.zkey() == k }) return
        a.pushUndo()
        b.zones.removeAll { it.t == t.id && it.s == sh.id && it.zkey() == k }
        a.changed()
        clearMarks()
        a.hideModeBar()
        a.hint("Захватка ${IdTables.zahLabel(uch, zah)} стёрта с плана — «Отменить» вернёт", 2000)
    }

    fun clearMarks() {
        if (v.markedZones != null || v.markedRecs != null) { v.markedZones = null; v.markedRecs = null; v.invalidate() }
    }

    /** «Обвести захватку» в шапке плана: какую (✓ — уже обведена). */
    fun chooseZah(t: BoardTable) {
        val zahs = IdTables.zahList(b, t)
        if (zahs.isEmpty()) { a.hint("В таблице пока нет строк — сначала «+ строка»", 2200); return }
        val done = TablePlan.outlined(b, t)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(6f), dp(18f), dp(4f)) }
        box.addView(Ui.text(a, "Какую захватку обвести на плане? ✓ — уже обведена (можно добавить ещё кусок).", 13f, Ui.MUTED))
        val flow = Ui.FlowRow(a).apply { hGap = dp(6f); vGap = dp(6f); setPadding(0, dp(10f), 0, dp(4f)) }
        val d = AlertDialog.Builder(a).setTitle("Обвести захватку · ${IdTables.fullTitle(b, t)}")
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton("Отмена", null).create()
        for ((i, uz) in zahs.withIndex()) {
            val has = IdTables.zkey(uz.first, uz.second) in done
            val st = TablePlan.zahStatus(b, t, uz.first, uz.second)
            flow.addView(Ui.pill(a, IdTables.zahLabel(uz.first, uz.second) + if (has) " ✓" else "", Ui.INK,
                if (has) Ui.OK_SOFT else Ui.DESK).apply {
                tag = "pzah_$i"
                st?.let { compoundDrawablePadding = dp(6f) }
                setOnClickListener { d.dismiss(); startOutline(t, uz.first, uz.second) }
            })
        }
        box.addView(flow)
        d.show()
    }

    // ---------------------------------------------------------------- обводка

    private var analysis: Analysis? = null

    /** Картинка листа для волшебной заливки: видимая часть, большая сторона до 2048, маска линий; закрытые зазоры — по запросу. */
    private class Analysis(val sheetId: String, val file: String, val crop: FloatArray, val w: Int, val h: Int, val ink: BooleanArray) {
        private val closed = HashMap<Int, BooleanArray>()
        fun closedFor(r: Int): BooleanArray = synchronized(closed) { closed.getOrPut(r) { MagicFill.dilate(ink, w, h, r) } }
        /** Толстое (стены, колонны) — один раз на лист. */
        val solid: BooleanArray by lazy { MagicFill.solidMask(ink, w, h) }
        fun matches(sh: PlanSheet) = sh.id == sheetId && sh.file == file && crop[0] == sh.cl && crop[1] == sh.ct && crop[2] == sh.cr && crop[3] == sh.cb
    }

    fun startOutline(t: BoardTable, uch: String, zah: String) {
        val sh = TablePlan.current(t) ?: run { a.hint("Сначала «+ план» внизу таблицы", 2000); return }
        endOutline(save = false)
        clearMarks()
        val pe = PlanEdit(t, sh, uch, zah)
        v.planEdit = pe
        attachSnap(pe)
        v.markedRecs = IdTables.zahRows(b, t, uch, zah).toSet()
        showSheet(t)
        outlineBar(pe)
        if (analysis?.matches(sh) != true) prepare(sh)
        a.hint(toolHint(pe.tool), 3500)
    }

    private fun toolHint(tool: Int) = when (tool) {
        PlanEdit.MAGIC -> "Касайтесь внутри помещения, плиты, по колонне или стене — контур найдётся сам. Можно несколько раз"
        PlanEdit.BRUSH -> "Проведите пальцем или пером вдоль нужных стен — возьмутся стены под кистью"
        PlanEdit.RECT -> "Протяните от угла до угла"
        PlanEdit.CIRCLE -> "Протяните рамку круга от угла до угла"
        else -> "Касайтесь углов по порядку; касание первой точки — замкнуть"
    }

    private fun prepare(sh: PlanSheet) {
        val file = File(imageDir, sh.file)
        val crop = floatArrayOf(sh.cl, sh.ct, sh.cr, sh.cb)
        exec.execute {
            val an = runCatching { analyze(sh.id, sh.file, file, crop) }.getOrNull()
            main.post {
                if (an == null) { a.hint("Не удалось прочитать картинку листа — обведите прямоугольником или по точкам", 3000); return@post }
                analysis = an
                // Ступень зазора по умолчанию — посчитать заранее.
                val pe = v.planEdit
                if (pe != null) exec.execute { an.closedFor(PlanEdit.GAPS[pe.gap]); an.solid }
            }
        }
    }

    private fun analyze(id: String, name: String, file: File, crop: FloatArray): Analysis? {
        val bmp = decodeCrop(file, crop, 2048) ?: return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val lum = ByteArray(w * h) { i ->
            val c = px[i]
            ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000).toByte()
        }
        return Analysis(id, name, crop, w, h, MagicFill.inkMask(lum))
    }

    /** Видимая часть картинки (доли) с большей стороной до maxSide: кусок оригинала, а если не вышло — из копии целиком. */
    private fun decodeCrop(f: File, crop: FloatArray, maxSide: Int): Bitmap? {
        val ob = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, ob)
        if (ob.outWidth > 0) try {
            @Suppress("DEPRECATION")
            val dec = BitmapRegionDecoder.newInstance(f.path, false)
            if (dec != null) {
                val r = Rect((crop[0] * ob.outWidth).toInt(), (crop[1] * ob.outHeight).toInt(),
                    kotlin.math.ceil(crop[2] * ob.outWidth).toInt().coerceAtMost(ob.outWidth), kotlin.math.ceil(crop[3] * ob.outHeight).toInt().coerceAtMost(ob.outHeight))
                var sample = 1
                while (max(r.width(), r.height()) / (sample * 2) >= maxSide) sample *= 2
                val bmp = dec.decodeRegion(r, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                dec.recycle()
                // Кусок без единого непрозрачного пикселя (так бывает вне планшета) — не верим ему.
                if (bmp != null && hasPixels(bmp)) return fit(bmp, maxSide)
                bmp?.recycle()
            }
        } catch (_: Throwable) { }
        // Запасной путь (и в проверках, где куска оригинала не прочитать): целиком, потом вырезать.
        val full = BoardImages.decodeFit(f, maxSide * 2, Bitmap.Config.ARGB_8888) ?: return null
        val l = (crop[0] * full.width).toInt().coerceIn(0, full.width - 1)
        val t = (crop[1] * full.height).toInt().coerceIn(0, full.height - 1)
        val r = kotlin.math.ceil(crop[2] * full.width).toInt().coerceIn(l + 1, full.width)
        val bt = kotlin.math.ceil(crop[3] * full.height).toInt().coerceIn(t + 1, full.height)
        val part = Bitmap.createBitmap(full, l, t, r - l, bt - t)
        if (part !== full) full.recycle()
        return fit(part, maxSide)
    }

    private fun hasPixels(bmp: Bitmap): Boolean {
        for (i in 0 until 9) if (Color.alpha(bmp.getPixel(bmp.width * (1 + i % 3) / 4, bmp.height * (1 + i / 3) / 4)) != 0) return true
        return false
    }

    private fun fit(bmp: Bitmap, maxSide: Int): Bitmap {
        val big = max(bmp.width, bmp.height)
        if (big <= maxSide) return bmp
        val k = maxSide.toFloat() / big
        val s = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
        if (s !== bmp) bmp.recycle()
        return s
    }

    /** Панель обводки: инструменты, зазор, «Отменить кусок», «Готово», «Отмена». */
    private fun outlineBar(pe: PlanEdit) {
        fun toolPill(name: String, tool: Int, tag: String) = Ui.pill(a, name, if (pe.tool == tool) Color.WHITE else Ui.INK, if (pe.tool == tool) Ui.INK else Ui.DESK).apply {
            this.tag = tag
            setOnClickListener {
                pe.tool = tool; pe.points.clear(); bar(pe); v.invalidate(); a.hint(toolHint(tool), 2500)
                if ((tool == PlanEdit.MAGIC || tool == PlanEdit.BRUSH) && analysis?.matches(pe.sheet) != true) prepare(pe.sheet)
            }
        }
        val buttons = ArrayList<TextView>()
        if (!pe.elements && TablePlan.elements(b, pe.t).isNotEmpty())
            buttons += Ui.pill(a, "← Отмечать элементы", Ui.INK, Ui.DESK).apply { tag = "pt_assign"; setOnClickListener { pe.tool = PlanEdit.ASSIGN; pe.points.clear(); bar(pe); v.invalidate() } }
        if (pe.elements) {
            buttons += toolPill("Ножницы / шов", PlanEdit.SCISSORS, "pt_scissors")
            buttons += toolPill("Убрать", PlanEdit.ERASE, "pt_erase")
        }
        buttons += toolPill("Волшебная", PlanEdit.MAGIC, "pt_magic")
        buttons += toolPill("▭", PlanEdit.RECT, "pt_rect")
        buttons += toolPill("◯", PlanEdit.CIRCLE, "pt_circle")
        buttons += toolPill("По точкам", PlanEdit.POINTS, "pt_points")
        buttons += toolPill("Кисть по стенам", PlanEdit.BRUSH, "pt_brush")
        if (pe.tool == PlanEdit.MAGIC) {
            buttons += Ui.pill(a, "Зазор −", Ui.INK, Ui.DESK).apply { tag = "pt_gap_minus"; setOnClickListener { if (pe.gap > 0) { pe.gap--; outlineBar(pe) } } }
            buttons += Ui.pill(a, "+ (${pe.gap})", Ui.INK, Ui.DESK).apply { tag = "pt_gap_plus"; setOnClickListener { if (pe.gap < PlanEdit.GAPS.size - 1) { pe.gap++; outlineBar(pe) } } }
        }
        // Цвет захватки: палитра, последние, пипетка (0.23.1).
        if (!pe.elements) buttons += Ui.pill(a, "● Цвет", colorOf(pe), Ui.DESK).apply { tag = "pt_color"; setOnClickListener { chooseColor(pe.t, colorOf(pe)) { c -> pe.color = c; outlineBar(pe); v.invalidate() } } }
        if (pe.elements) buttons += Ui.pill(a, "Найти…", Ui.INK, Ui.DESK).apply { tag = "pt_find"; setOnClickListener { val t = pe.t; endOutline(save = true); findDialog(t) } }
        buttons += Ui.pill(a, "Отменить", Ui.INK, Ui.DESK).apply {
            tag = "pt_undo"
            setOnClickListener { undoPiece() }
        }
        buttons += Ui.pill(a, "Отмена", Ui.INK, Ui.DESK).apply { tag = "pt_cancel"; setOnClickListener { endOutline(save = false) } }
        buttons += Ui.pill(a, "Готово", Color.WHITE, Ui.ACCENT).apply { tag = "pt_done"; setOnClickListener { endOutline(save = true) } }
        a.showModeBar(if (pe.elements) "Разметка элементов · ${elementsInfo(pe.t, pe.sheet)}" else "Захватка ${IdTables.zahLabel(pe.uch, pe.zah)}", buttons)
    }

    /** Панель режима: отметка элементов, разметка или обводка. */
    private fun bar(pe: PlanEdit) = if (pe.tool == PlanEdit.ASSIGN) assignBar(pe) else outlineBar(pe)

    /** Цвет захватки в обводке: выбранный, иначе как у её областей / следующий по порядку. */
    fun colorOf(pe: PlanEdit): Int = pe.color ?: TablePlan.ownColorFor(b, pe.t, pe.uch, pe.zah)

    /**
     * Палитра (24 цвета, последние, пипетка — цвет области или пикселя плана). Выбрали цвет, а у таблицы «Цвет: по ИГС» —
     * переключаем на «свой» (иначе выбранного цвета не видно).
     */
    fun chooseColor(t: BoardTable, current: Int, done: (Int) -> Unit) {
        val apply: (Int) -> Unit = { c ->
            ColorKit.remember(a, c)
            if (t.zoneColor != TablePlan.OWN && b.tables.any { it === t }) {
                a.pushUndo(); t.zoneColor = TablePlan.OWN; a.changed()
                a.hint("Цвет захваток — свой (переключатель «Цвет» над планом)", 2500)
            }
            done(c)
        }
        ColorKit.showPalette(a, current, apply) { a.pickColor(apply) }
    }

    /** «Цвет» у обведённой захватки: все её области — новым цветом. */
    private fun recolorZah(t: BoardTable, uch: String, zah: String) {
        val zones = TablePlan.zonesOf(b, t, uch, zah)
        if (zones.isEmpty()) return
        chooseColor(t, zones[0].color) { c ->
            a.pushUndo()
            for (z in TablePlan.zonesOf(b, t, uch, zah)) z.color = c
            a.changed()
            showZah(t, uch, zah, moveCamera = false)
        }
    }

    /** Касание или протяжка по листу в режиме обводки (координаты доски). */
    fun onGesture(x0: Float, y0: Float, x1: Float, y1: Float, tap: Boolean) {
        val pe = v.planEdit ?: return
        val r = v.tableDraw.sheetRect(b, pe.t, RectF()) ?: return
        val p0 = FloatArray(2); val p1 = FloatArray(2)
        v.tableDraw.toSheet(pe.t, pe.sheet, r, x0, y0, p0)
        v.tableDraw.toSheet(pe.t, pe.sheet, r, x1, y1, p1)
        if (pe.tool == PlanEdit.ASSIGN || pe.tool == PlanEdit.ERASE) { pickElements(pe, r, x1, y1, tap); return }
        if (pe.tool == PlanEdit.SCISSORS) { scissors(pe, p0, p1, tap); return }
        if (pe.tool == PlanEdit.BRUSH) {
            val trail = ArrayList(pe.path)
            pe.path.clear()
            if (trail.size < 2) trail.addAll(listOf(x0, y0))
            brush(pe, r, trail)
            return
        }
        when (pe.tool) {
            PlanEdit.MAGIC -> if (tap) magic(pe, p0[0], p0[1]) else a.hint("Волшебная заливка — касанием внутри помещения", 1800)
            PlanEdit.RECT, PlanEdit.CIRCLE -> {
                val minPx = Ui.dpf(a, 12f)
                if (tap || abs(x1 - x0) * b.camZ < minPx || abs(y1 - y0) * b.camZ < minPx) { a.hint("Протяните от угла до угла", 1600); return }
                pe.pieces += if (pe.tool == PlanEdit.RECT) MagicFill.rect(p0[0], p0[1], p1[0], p1[1]) else MagicFill.ellipse(p0[0], p0[1], p1[0], p1[1])
                v.invalidate()
            }
            else -> {
                val px = if (tap) p0 else p1
                val n = pe.points.size / 2
                if (n >= 3) {
                    val q = FloatArray(2)
                    v.tableDraw.fromSheet(pe.sheet, r, pe.points[0], pe.points[1], q)
                    val bx = if (tap) x0 else x1; val by = if (tap) y0 else y1
                    if (hypot(q[0] - bx, q[1] - by) * b.camZ < Ui.dpf(a, 24f)) { closePoints(pe); return }
                }
                pe.points += px[0]; pe.points += px[1]
                v.invalidate()
            }
        }
    }

    private fun closePoints(pe: PlanEdit) {
        if (pe.points.size >= 6) pe.pieces += pe.points.toFloatArray()
        pe.points.clear()
        v.invalidate()
    }

    private fun magic(pe: PlanEdit, fx: Float, fy: Float) {
        val sh = pe.sheet
        val an = analysis?.takeIf { it.matches(sh) }
        if (an == null) { a.hint("Готовлю план… коснитесь ещё раз через секунду", 1800); if (analysis?.matches(sh) != true) prepare(sh); return }
        if (pe.busy) return
        val ax = ((fx - sh.cl) / (sh.cr - sh.cl) * an.w).toInt()
        val ay = ((fy - sh.ct) / (sh.cb - sh.ct) * an.h).toInt()
        if (ax !in 0 until an.w || ay !in 0 until an.h) return
        pe.busy = true
        val gap = PlanEdit.GAPS[pe.gap]
        exec.execute {
            val res = runCatching { MagicFill.find(an.ink, an.closedFor(gap), an.w, an.h, ax, ay, gap, an.solid) }.getOrNull()
            main.post {
                pe.busy = false
                if (v.planEdit !== pe) return@post
                when {
                    res == null -> a.hint("Не получилось — обведите прямоугольником или по точкам", 2200)
                    res.error == MagicFill.ERR_LINE -> a.hint("Здесь тесно от линий — коснитесь ближе к середине или уменьшите зазор «−»", 2600)
                    !res.ok -> a.hint("Не получилось — обведите прямоугольником или по точкам", 2200)
                    else -> {
                        val pts = FloatArray(res.pts.size)
                        for (i in res.pts.indices step 2) {
                            pts[i] = sh.cl + res.pts[i] / an.w * (sh.cr - sh.cl)
                            pts[i + 1] = sh.ct + res.pts[i + 1] / an.h * (sh.cb - sh.ct)
                        }
                        pe.pieces += pts
                        v.invalidate()
                        when {
                            res.leaked -> a.hint("Заливка дошла до края плана — если это не то, «Отменить» и прибавьте зазор «+»", 3500)
                            res.kind == MagicFill.WALL -> a.hint("Выделены стены — всё, что соединено. Нужна часть: «Отменить» и «Кисть по стенам»", 4000)
                        }
                    }
                }
            }
        }
    }

    /** Кисть по стенам: след (координаты доски) → толстое под кистью на картинке анализа → куски. */
    private fun brush(pe: PlanEdit, r: RectF, trail: List<Float>) {
        val sh = pe.sheet
        val an = analysis?.takeIf { it.matches(sh) }
        if (an == null) { a.hint("Готовлю план… проведите ещё раз через секунду", 1800); if (analysis?.matches(sh) != true) prepare(sh); return }
        if (pe.busy) return
        val q = FloatArray(2)
        val path = FloatArray(trail.size)
        for (i in trail.indices step 2) {
            v.tableDraw.toSheet(pe.t, sh, r, trail[i], trail[i + 1], q)
            path[i] = (q[0] - sh.cl) / (sh.cr - sh.cl) * an.w
            path[i + 1] = (q[1] - sh.ct) / (sh.cb - sh.ct) * an.h
        }
        // Полуширина кисти: 14 dp на экране → пиксели картинки анализа.
        val radius = Ui.dpf(a, PlanEdit.BRUSH_DP) / b.camZ / r.width() * an.w
        pe.busy = true
        exec.execute {
            val pieces = runCatching { MagicFill.brush(an.solid, an.w, an.h, path, radius) }.getOrDefault(emptyList())
            main.post {
                pe.busy = false
                if (v.planEdit !== pe) return@post
                if (pieces.isEmpty()) { a.hint("Под кистью ничего не нашлось — проведите по стенам", 2000); return@post }
                for (pc in pieces) {
                    val f = FloatArray(pc.size)
                    for (i in pc.indices step 2) {
                        f[i] = sh.cl + pc[i] / an.w * (sh.cr - sh.cl)
                        f[i + 1] = sh.ct + pc[i + 1] / an.h * (sh.cb - sh.ct)
                    }
                    pe.pieces += f
                }
                v.invalidate()
            }
        }
    }

    /** Закончить обводку: save — куски стали областями захватки. */
    fun endOutline(save: Boolean) {
        val pe = v.planEdit ?: return
        if (save && pe.points.size >= 6) closePoints(pe)
        v.planEdit = null
        v.planHover = null
        a.hideModeBar()
        if (save && pe.pieces.isNotEmpty() && b.tables.any { it === pe.t }) {
            a.pushUndo()
            if (pe.elements) {
                // Разметка (0.26): куски — элементы без захватки.
                b.zones += pe.pieces.map { BoardZone(pe.t.id, pe.sheet.id, "", "", it, TablePlan.FREE).also { z -> z.e = true } }
                a.changed()
                a.hint("Добавлено элементов: ${pe.pieces.size}", 2000)
                return
            }
            val color = colorOf(pe)
            // Выбрали цвет — у всей захватки он один (и у прежних её областей).
            if (pe.color != null) for (z in TablePlan.zonesOf(b, pe.t, pe.uch, pe.zah)) z.color = color
            val asElements = TablePlan.elements(b, pe.t).isNotEmpty()
            val made = pe.pieces.map { BoardZone(pe.t.id, pe.sheet.id, pe.uch, pe.zah, it, color).also { z -> z.e = asElements } }
            b.zones += made
            a.changed()
            showZah(pe.t, pe.uch, pe.zah, moveCamera = false)
        } else {
            v.markedRecs = null
            v.invalidate()
        }
    }

    // ---------------------------------------------------------------- элементы разметки (0.26)

    /** Прочитанный лист PDF (линии со слоями) — последний. */
    private var vecKey = ""
    private var vec: PdfVector.Page? = null

    private fun loadVector(sh: PlanSheet): PdfVector.Page {
        val key = sh.pdf + "#" + sh.page
        synchronized(this) { if (key == vecKey) vec?.let { return it } }
        val p = PdfVector.read(File(imageDir, sh.pdf), sh.page)
        synchronized(this) { vecKey = key; vec = p }
        return p
    }

    private val prefs get() = a.getSharedPreferences("plan", Context.MODE_PRIVATE)

    /** Привязка «как в AutoCAD» (0.26.1): у листа есть PDF — углы и пересечения его линий, в фоне. */
    private var snapKey = ""
    private var snapIndex: PlanSnap? = null

    private fun attachSnap(pe: PlanEdit) {
        val sh = pe.sheet
        if (sh.pdf.isEmpty() || !File(imageDir, sh.pdf).isFile) return
        val key = sh.pdf + "#" + sh.page
        snapIndex?.takeIf { snapKey == key }?.let { pe.snap = it; return }
        exec.execute {
            val s = runCatching { PlanSnap(loadVector(sh)) }.getOrNull() ?: return@execute
            main.post {
                snapKey = key; snapIndex = s
                if (v.planEdit === pe) pe.snap = s
            }
        }
    }

    /** Свои галочки слоёв на этой доске (имя слоя → участвует), остальные — по названию. */
    private fun layerUse(): Map<String, Boolean> {
        val s = prefs.getString("layers_" + b.id, null) ?: return emptyMap()
        return runCatching { val o = org.json.JSONObject(s); o.keys().asSequence().associateWith { o.optBoolean(it) } }.getOrDefault(emptyMap())
    }

    private fun elementsInfo(t: BoardTable, sh: PlanSheet): String {
        val n = TablePlan.elements(b, t, sh.id).size
        return if (n == 0) "элементов нет" else Ui.plural(n, "элемент", "элемента", "элементов")
    }

    /** Лист с PDF вставлен — предложить найти элементы. */
    private fun offerFind(t: BoardTable) {
        AlertDialog.Builder(a).setTitle("План под таблицей")
            .setMessage("Найти на листе стены, пилоны или плиту по линиям чертежа? Потом касание квадратика захватки в таблице — и отмечайте, что залили.")
            .setPositiveButton("Найти элементы") { _, _ -> findDialog(t) }
            .setNegativeButton("Позже", null).show()
    }

    /** «Элементы ▾» в шапке плана. */
    fun elementsMenu(t: BoardTable) {
        val sh = TablePlan.current(t) ?: run { addSheet(t); return }
        val n = TablePlan.elements(b, t, sh.id).size
        val items = arrayListOf("Найти элементы на листе…", "Ножницы / шов — разрезать элемент", "Добавить элемент вручную", "Убрать элементы касанием")
        if (n > 0) items += "Убрать всю разметку листа ($n)"
        AlertDialog.Builder(a).setTitle("Элементы · ${v.tableDraw.sheetName(t, t.sheets.indexOf(sh))}")
            .setItems(items.toTypedArray()) { _, w ->
                when (w) {
                    0 -> findDialog(t)
                    1 -> startElements(t, PlanEdit.SCISSORS)
                    2 -> startElements(t, PlanEdit.MAGIC)
                    3 -> startElements(t, PlanEdit.ERASE)
                    4 -> clearElements(t, sh)
                }
            }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun clearElements(t: BoardTable, sh: PlanSheet) {
        AlertDialog.Builder(a).setMessage("Убрать всю разметку листа и швы? Захватки, отмеченные на этом листе, тоже уйдут с плана. «Отменить» вернёт.")
            .setPositiveButton("Убрать") { _, _ ->
                endOutline(save = false)
                a.pushUndo()
                b.zones.removeAll { it.t == t.id && it.s == sh.id && it.e }
                sh.seams.clear()
                t.lay = null
                a.changed()
            }
            .setNegativeButton("Отмена", null).show()
    }

    /** Окно «Найти элементы» (для проверок). */
    internal var findDlg: AlertDialog? = null

    /** «Найти элементы»: что искать — несущие, ненесущие, плита; слои. */
    fun findDialog(t: BoardTable) {
        val sh = TablePlan.current(t) ?: return
        if (sh.pdf.isEmpty() || !File(imageDir, sh.pdf).isFile) { askPdf(t, sh); return }
        val guess = when {
            t.kindDef()?.id == TableKinds.KJ_SLAB.id -> PlanElements.SLAB
            t.title.contains("ненесущ", ignoreCase = true) -> PlanElements.NONBEARING
            else -> PlanElements.BEARING
        }
        var mode = guess
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(6f), dp(20f), 0) }
        box.addView(Ui.text(a, "Найду по линиям чертежа. Текст, размеры, оси и выноски не трогаются. Что искать на этом листе:", 13.5f, Ui.INK2))
        val group = android.widget.RadioGroup(a).apply { setPadding(0, dp(8f), 0, dp(4f)) }
        for ((m, label) in listOf(
            PlanElements.BEARING to "Несущие стены и пилоны — жирный контур",
            PlanElements.NONBEARING to "Ненесущие стены — тонкий контур со штриховкой",
            PlanElements.SLAB to "Плита перекрытия — по краю, с отверстиями и швами-зигзагами",
        )) {
            group.addView(android.widget.RadioButton(a).apply {
                id = View.generateViewId(); text = label; textSize = 15f; tag = "find_$m"
                isChecked = m == guess
                setOnClickListener { mode = m }
            })
        }
        box.addView(group)
        box.addView(Ui.text(a, "Слои чертежа…", 14f, Ui.ACCENT, bold = true).apply {
            tag = "find_layers"; setPadding(0, dp(6f), 0, dp(10f))
            setOnClickListener { layersDialog(sh) }
        })
        val old = TablePlan.elements(b, t, sh.id)
        if (old.isNotEmpty()) box.addView(Ui.text(a, "На листе уже есть разметка (${old.size}). Элементы без захватки заменятся, отмеченные в захватках — останутся.", 12.5f, Ui.MUTED))
        findDlg = AlertDialog.Builder(a).setTitle("Найти элементы")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Найти") { _, _ -> runFind(t, sh, mode) }
            .setNegativeButton("Отмена", null).show()
    }

    /** Слои чертежа: какие участвуют в поиске (по умолчанию — по названию; запоминается для доски). */
    private fun layersDialog(sh: PlanSheet) {
        exec.execute {
            val page = runCatching { loadVector(sh) }.getOrNull()
            main.post {
                if (page == null) { a.hint("Не удалось прочитать PDF", 2000); return@post }
                val layers = PlanElements.layers(page)
                if (layers.isEmpty()) { a.hint("В PDF нет слоёв — поиск идёт по толщине линий", 2500); return@post }
                val use = layerUse()
                val checked = BooleanArray(layers.size) { use[layers[it].name] ?: layers[it].auto }
                val names = layers.map { l ->
                    val what = when (l.role) {
                        PlanElements.Role.CUT -> "контуры"; PlanElements.Role.HATCH -> "штриховка"; PlanElements.Role.SLAB -> "плита"
                        PlanElements.Role.ANNOT -> "размеры, текст, оси"; PlanElements.Role.HIDDEN -> "невидимые линии"
                        PlanElements.Role.OPENING -> "проёмы"; PlanElements.Role.OTHER -> "прочее"
                    }
                    "${l.name.ifEmpty { "без слоя" }} — $what, ${l.count}"
                }.toTypedArray()
                AlertDialog.Builder(a).setTitle("Слои для поиска")
                    .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton("Готово") { _, _ ->
                        val o = org.json.JSONObject()
                        for ((i, l) in layers.withIndex()) if (checked[i] != l.auto) o.put(l.name, checked[i])
                        prefs.edit().putString("layers_" + b.id, o.toString()).apply()
                    }
                    .setNeutralButton("Как было") { _, _ -> prefs.edit().remove("layers_" + b.id).apply() }
                    .setNegativeButton("Отмена", null).show()
            }
        }
    }

    /** Идёт поиск (для проверок). */
    @Volatile internal var finding = false

    private fun runFind(t: BoardTable, sh: PlanSheet, mode: Int) {
        val msg = Ui.text(a, "Ищу элементы по линиям чертежа…", 16f, Ui.INK2).apply { setPadding(dp(24f), dp(14f), dp(24f), dp(6f)) }
        val wait = AlertDialog.Builder(a).setView(msg).setCancelable(false).show()
        finding = true
        val crop = floatArrayOf(sh.cl, sh.ct, sh.cr, sh.cb)
        val use = layerUse()
        exec.execute {
            val r = runCatching { PlanElements.find(loadVector(sh), mode, crop, use) }
            main.post {
                finding = false
                runCatching { wait.dismiss() }
                r.onFailure { e ->
                    android.util.Log.w("FreeWrite", "find", e)
                    AlertDialog.Builder(a).setTitle("Не получилось").setMessage("Не удалось прочитать линии PDF (${e.javaClass.simpleName}). Можно разметить вручную: «Элементы» → «Добавить элемент вручную».")
                        .setPositiveButton("Понятно", null).show()
                }
                r.onSuccess { f -> applyFound(t, sh, f) }
            }
        }
    }

    private fun applyFound(t: BoardTable, sh: PlanSheet, f: PlanElements.Found) {
        if (b.tables.none { it === t } || t.sheets.none { it === sh }) return
        if (f.elements.isEmpty()) {
            AlertDialog.Builder(a).setTitle("Ничего не нашлось").setMessage(f.message).setPositiveButton("Понятно", null).show()
            return
        }
        endOutline(save = false)
        a.pushUndo()
        val kept = b.zones.filter { it.t == t.id && it.s == sh.id && it.e && !it.free }
        b.zones.removeAll { it.t == t.id && it.s == sh.id && it.free }
        var added = 0
        for (e in f.elements) {
            // Уже отмеченное в захватке не дублируем.
            val c = centroid(e.outer)
            if (kept.any { it.contains(c[0], c[1]) }) continue
            b.zones += BoardZone(t.id, sh.id, "", "", e.outer, TablePlan.FREE).also { z -> z.e = true; z.holes = e.holes }
            added++
        }
        for (s in f.seams) if (sh.seams.none { o -> (0..3).all { kotlin.math.abs(o[it] - s[it]) < 0.002f } }) sh.seams += s
        // Каждой захватке — свой цвет (Владимир: «20–30 захваток — все разного цвета»).
        if (t.zoneColor != TablePlan.OWN) t.zoneColor = TablePlan.OWN
        t.lay = null
        a.changed()
        showSheet(t)
        AlertDialog.Builder(a).setTitle("Разметка готова")
            .setMessage("${f.message}.\n\nСерым — ещё не залито. Теперь коснитесь цветного квадратика у номера захватки в таблице и отмечайте элементы, которые залили. Лишнее — «Элементы» → «Убрать», длинную стену разделить — «Ножницы / шов».")
            .setPositiveButton("Понятно", null).show()
        if (added == 0) a.hint("Новых элементов нет — всё уже отмечено", 2500)
    }

    private fun centroid(p: FloatArray): FloatArray {
        var x = 0f; var y = 0f
        val n = p.size / 2
        for (i in 0 until n) { x += p[2 * i]; y += p[2 * i + 1] }
        return floatArrayOf(x / n, y / n)
    }

    /** Лист вставлен без PDF (до 0.26 или картинкой) — выбрать тот же PDF. */
    private var pdfFor: Pair<String, String>? = null

    private fun askPdf(t: BoardTable, sh: PlanSheet) {
        AlertDialog.Builder(a).setTitle("Нужен PDF этого листа")
            .setMessage("Лист вставлен картинкой — линий чертежа при нём нет. Выберите тот же PDF ещё раз: элементы найдутся по его линиям, картинка листа не изменится.")
            .setPositiveButton("Выбрать PDF") { _, _ -> pdfFor = t.id to sh.id; a.pickSheetPdf() }
            .setNegativeButton("Отмена", null).show()
    }

    /** Ответ на «Выбрать PDF»: копия к себе, лист (если в PDF их несколько — выбрать), потом «Найти элементы». */
    fun onSheetPdf(uri: android.net.Uri) {
        val (tid, sid) = pdfFor ?: return
        pdfFor = null
        a.hint("Читаю PDF…", 2000)
        exec.execute {
            val r = runCatching {
                val name = java.util.UUID.randomUUID().toString() + ".pdf"
                val f = File(imageDir.apply { mkdirs() }, name)
                (a.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("файл не открылся")).use { input -> f.outputStream().use { input.copyTo(it) } }
                val n = PdfVector.pageCount(f.readBytes())
                if (n <= 0) { f.delete(); throw IllegalArgumentException("это не PDF") }
                name to n
            }
            main.post {
                r.onFailure { a.hint("Не удалось открыть PDF: ${it.message ?: it.javaClass.simpleName}", 3000) }
                r.onSuccess { (name, n) -> attachPdf(tid, sid, name, n) }
            }
        }
    }

    private fun attachPdf(tid: String, sid: String, name: String, pages: Int) {
        fun done(page: Int) {
            val t = b.tables.firstOrNull { it.id == tid } ?: return
            val sh = t.sheets.firstOrNull { it.id == sid } ?: return
            a.pushUndo()
            sh.pdf = name; sh.page = page
            a.changed()
            findDialog(t)
        }
        if (pages <= 1) { done(0); return }
        AlertDialog.Builder(a).setTitle("Какой лист PDF?")
            .setItems(Array(pages) { "Лист ${it + 1}" }) { _, i -> done(i) }
            .setNegativeButton("Отмена") { _, _ -> File(imageDir, name).delete() }.show()
    }

    /** Разметка элементов вручную: ножницы, убрать, волшебная, прямоугольник, точки, кисть. */
    fun startElements(t: BoardTable, tool: Int) {
        val sh = TablePlan.current(t) ?: run { a.hint("Сначала «+ план» внизу таблицы", 2000); return }
        endOutline(save = false)
        clearMarks()
        val pe = PlanEdit(t, sh, "", "").also { it.elements = true; it.tool = tool }
        v.planEdit = pe
        attachSnap(pe)
        showSheet(t)
        outlineBar(pe)
        if ((tool == PlanEdit.MAGIC || tool == PlanEdit.BRUSH) && analysis?.matches(sh) != true) prepare(sh)
        a.hint(when (tool) {
            PlanEdit.SCISSORS -> "Проведите линию поперёк элемента — по месту шва. Элемент разделится на два"
            PlanEdit.ERASE -> "Касайтесь лишних элементов (текст, мусор) — они уберутся"
            else -> toolHint(tool)
        }, 3500)
    }

    /**
     * Отметка захватки на плане (0.26, Владимир: «выбрал захватку, пальцем проставляю выделенные участки — они окрасились»):
     * касание элемента — в захватку (повторное — снять), провести — все под пальцем.
     */
    fun startAssign(t: BoardTable, uch: String, zah: String) {
        val els = TablePlan.elements(b, t)
        val cur = TablePlan.current(t)
        val sh = cur?.takeIf { c -> els.any { it.s == c.id } } ?: els.firstOrNull()?.let { z -> TablePlan.sheet(t, z.s) } ?: cur
            ?: run { a.hint("Сначала «+ план» внизу таблицы", 2000); return }
        endOutline(save = false)
        clearMarks()
        if (sh !== cur) { t.tab = t.sheets.indexOf(sh).coerceAtLeast(0); t.lay = null }
        val pe = PlanEdit(t, sh, uch, zah).also { it.tool = PlanEdit.ASSIGN }
        v.planEdit = pe
        attachSnap(pe)
        v.markedRecs = IdTables.zahRows(b, t, uch, zah).toSet()
        showSheet(t)
        assignBar(pe)
        a.hint("Касайтесь элементов, которые залили, — окрасятся. Провести пальцем — все под ним. Повторное касание — снять", 4500)
    }

    private fun assignBar(pe: PlanEdit) {
        val key = IdTables.zkey(pe.uch, pe.zah)
        val n = b.zones.count { it.t == pe.t.id && !it.free && it.zkey() == key }
        val pct = TablePlan.percent(b, pe.t)
        val title = "Захватка ${IdTables.zahLabel(pe.uch, pe.zah)} · отмечено $n" + (pct?.let { " · выполнено ≈ $it %" } ?: "")
        val buttons = ArrayList<TextView>()
        buttons += Ui.pill(a, "● Цвет", colorOf(pe), Ui.DESK).apply {
            tag = "pt_color"
            setOnClickListener {
                chooseColor(pe.t, colorOf(pe)) { c ->
                    a.pushUndo()
                    pe.color = c
                    for (z in b.zones) if (z.t == pe.t.id && !z.free && z.zkey() == key) z.color = c
                    a.changed()
                    assignBar(pe)
                }
            }
        }
        buttons += Ui.pill(a, "Отменить", Ui.INK, Ui.DESK).apply { tag = "pt_undo"; setOnClickListener { a.undo() } }
        buttons += Ui.pill(a, "Обвести вручную", Ui.INK, Ui.DESK).apply {
            tag = "as_manual"
            setOnClickListener { pe.tool = PlanEdit.MAGIC; outlineBar(pe); if (analysis?.matches(pe.sheet) != true) prepare(pe.sheet); a.hint(toolHint(PlanEdit.MAGIC), 3000) }
        }
        buttons += Ui.pill(a, "Готово", Color.WHITE, Ui.ACCENT).apply { tag = "pt_done"; setOnClickListener { endOutline(save = true) } }
        a.showModeBar(title, buttons)
    }

    /** Элемент под точкой (координаты доски): внутри, иначе ближайший в пределах допуска (палец толще тонкой стены). */
    private fun zoneAt(pe: PlanEdit, r: RectF, bx: Float, by: Float, strict: Boolean = false): BoardZone? {
        val q = FloatArray(2)
        v.tableDraw.toSheet(pe.t, pe.sheet, r, bx, by, q)
        val sh = pe.sheet
        val ratio = sh.ratio()
        val list = b.zones.filter { it.t == pe.t.id && it.s == sh.id && (pe.tool != PlanEdit.ERASE || it.e) }
        list.filter { it.contains(q[0], q[1]) }.minByOrNull { it.area(ratio) }?.let { return it }
        val tol = Ui.dpf(a, if (strict) 5f else 14f) / b.camZ * (sh.cr - sh.cl) / max(1f, r.width())
        var best: BoardZone? = null; var bd = tol
        for (z in list) { val d = z.distance(q[0], q[1], ratio); if (d <= bd) { bd = d; best = z } }
        return best
    }

    /** Палец/перо над листом при отметке — подсветить элемент. */
    fun onHover(x: Float, y: Float) {
        val pe = v.planEdit ?: return
        if (pe.tool != PlanEdit.ASSIGN && pe.tool != PlanEdit.ERASE) return
        val r = v.tableDraw.sheetRect(b, pe.t, RectF()) ?: return
        v.planHover = zoneAt(pe, r, x, y)
    }

    private fun pickElements(pe: PlanEdit, r: RectF, x: Float, y: Float, tap: Boolean) {
        val trail = ArrayList(pe.path)
        pe.path.clear()
        v.planHover = null
        val hits = LinkedHashSet<BoardZone>()
        if (tap || trail.size < 4) zoneAt(pe, r, x, y)?.let { hits += it }
        else {
            // Провели — все элементы под следом (с шагом ~4 dp на экране).
            val step = Ui.dpf(a, 4f) / b.camZ
            var i = 0
            while (i + 3 < trail.size) {
                val ax = trail[i]; val ay = trail[i + 1]; val bx2 = trail[i + 2]; val by2 = trail[i + 3]
                val n = max(1, (kotlin.math.hypot(bx2 - ax, by2 - ay) / step).toInt())
                for (k in 0..n) zoneAt(pe, r, ax + (bx2 - ax) * k / n, ay + (by2 - ay) * k / n, strict = true)?.let { hits += it }
                i += 2
            }
        }
        if (hits.isEmpty()) {
            a.hint(if (pe.tool == PlanEdit.ERASE) "Здесь нет элемента" else "Здесь нет элемента — касайтесь серых элементов", 1600)
            v.invalidate(); return
        }
        a.pushUndo()
        if (pe.tool == PlanEdit.ERASE) {
            b.zones.removeAll { z -> hits.any { it === z } }
            a.changed()
            outlineBar(pe)
            return
        }
        val key = IdTables.zkey(pe.uch, pe.zah)
        val color = colorOf(pe)
        val one = hits.singleOrNull()
        if (tap && one != null && !one.free && one.zkey() == key) {
            // Повторное касание — снять отметку.
            one.uch = ""; one.zah = ""; one.color = TablePlan.FREE; one.e = true
        } else {
            val moved = hits.count { !it.free && it.zkey() != key }
            for (z in hits) { z.uch = pe.uch; z.zah = pe.zah; z.color = color; z.e = true }
            if (moved > 0) a.hint("Перенесено из другой захватки: $moved", 2000)
        }
        a.changed()
        assignBar(pe)
    }

    /** Ножницы / шов: линия поперёк — элементы под ней делятся; шов остаётся на плане зигзагом. */
    private fun scissors(pe: PlanEdit, p0: FloatArray, p1: FloatArray, tap: Boolean) {
        val sh = pe.sheet
        val ratio = sh.ratio()
        if (tap || kotlin.math.hypot(p1[0] - p0[0], (p1[1] - p0[1]) * ratio) < 1e-4f) { a.hint("Проведите линию поперёк элемента", 1800); return }
        // Почти по горизонтали или вертикали (до 7°) — ровно: швы обычно вдоль осей.
        val ddx = p1[0] - p0[0]; val ddy = (p1[1] - p0[1]) * ratio
        val ang = Math.toDegrees(kotlin.math.atan2(kotlin.math.abs(ddy).toDouble(), kotlin.math.abs(ddx).toDouble()))
        if (ang < 7.0) { val m = (p0[1] + p1[1]) / 2f; p0[1] = m; p1[1] = m }
        else if (ang > 83.0) { val m = (p0[0] + p1[0]) / 2f; p0[0] = m; p1[0] = m }
        val line = floatArrayOf(p0[0], p0[1], p1[0], p1[1])
        val lx0 = min(p0[0], p1[0]); val lx1 = max(p0[0], p1[0]); val ly0 = min(p0[1], p1[1]); val ly1 = max(p0[1], p1[1])
        var changed = false
        val targets = b.zones.filter { z ->
            if (z.t != pe.t.id || z.s != sh.id) return@filter false
            val bb = z.box()
            !(bb[2] < lx0 || bb[0] > lx1 || bb[3] < ly0 || bb[1] > ly1)
        }
        for (z in targets) {
            val parts = PlanElements.split(z.pts, z.holes, ratio, line) ?: continue
            if (!changed) { a.pushUndo(); changed = true }
            val idx = b.zones.indexOfFirst { it === z }
            if (idx < 0) continue
            b.zones.removeAt(idx)
            val made = parts.map { p -> BoardZone(z.t, z.s, z.uch, z.zah, p.outer, z.color).also { n -> n.e = true; n.holes = p.holes } }
            b.zones.addAll(idx, made)
        }
        if (!changed) { a.hint("Линия должна пересечь элемент насквозь", 2000); return }
        sh.seams += line
        pe.t.lay = null
        a.changed()
        outlineBar(pe)
    }

    fun close() {
        exec.shutdownNow()
    }
}

/**
 * Обрезка листа плана (0.23): картинка по размеру экрана, рамка с ручками на углах и сторонах, внутри рамки — двигать
 * её целиком. [crop] — видимая часть в долях (l, t, r, b).
 */
class PlanCropView(context: Context, private val bmp: Bitmap, init: FloatArray) : View(context) {
    val crop = init.copyOf()
    private val img = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var edges = 0
    private val start = FloatArray(4)
    private var downX = 0f
    private var downY = 0f

    fun reset() { crop[0] = 0f; crop[1] = 0f; crop[2] = 1f; crop[3] = 1f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val pad = Ui.dpf(context, 22f)
        val k = min((width - 2 * pad) / bmp.width, (height - 2 * pad) / bmp.height)
        val w = bmp.width * k; val h = bmp.height * k
        img.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(bmp, null, img, paint)
        val l = img.left + crop[0] * w; val t = img.top + crop[1] * h; val r = img.left + crop[2] * w; val b = img.top + crop[3] * h
        canvas.drawRect(img.left, img.top, img.right, t, shade)
        canvas.drawRect(img.left, b, img.right, img.bottom, shade)
        canvas.drawRect(img.left, t, l, b, shade)
        canvas.drawRect(r, t, img.right, b, shade)
        frame.strokeWidth = Ui.dpf(context, 2.5f)
        canvas.drawRect(l, t, r, b, frame)
        val kr = Ui.dpf(context, 11f)
        for (x in floatArrayOf(l, r)) for (y in floatArrayOf(t, b)) { canvas.drawCircle(x, y, kr, knob); canvas.drawCircle(x, y, kr, frame) }
        val bar = Ui.dpf(context, 26f); val th = Ui.dpf(context, 7f)
        knob.color = Ui.ACCENT
        canvas.drawRoundRect((l + r) / 2 - bar / 2, t - th / 2, (l + r) / 2 + bar / 2, t + th / 2, th, th, knob)
        canvas.drawRoundRect((l + r) / 2 - bar / 2, b - th / 2, (l + r) / 2 + bar / 2, b + th / 2, th, th, knob)
        canvas.drawRoundRect(l - th / 2, (t + b) / 2 - bar / 2, l + th / 2, (t + b) / 2 + bar / 2, th, th, knob)
        canvas.drawRoundRect(r - th / 2, (t + b) / 2 - bar / 2, r + th / 2, (t + b) / 2 + bar / 2, th, th, knob)
        knob.color = Color.WHITE
    }

    /** Края под пальцем: 1 — левый, 2 — верхний, 4 — правый, 8 — нижний; 16 — внутри (двигать). */
    private fun edgesAt(x: Float, y: Float): Int {
        val w = img.width(); val h = img.height()
        val l = img.left + crop[0] * w; val t = img.top + crop[1] * h; val r = img.left + crop[2] * w; val b = img.top + crop[3] * h
        val near = Ui.dpf(context, 28f)
        var e = 0
        if (abs(x - l) < near && y > t - near && y < b + near) e = e or 1
        if (abs(x - r) < near && y > t - near && y < b + near) e = e or 4
        if (abs(y - t) < near && x > l - near && x < r + near) e = e or 2
        if (abs(y - b) < near && x > l - near && x < r + near) e = e or 8
        if (e == 0 && x in l..r && y in t..b) e = 16
        return e
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edges = edgesAt(ev.x, ev.y)
                System.arraycopy(crop, 0, start, 0, 4)
                downX = ev.x; downY = ev.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (edges != 0 && img.width() > 0f) {
                val dx = (ev.x - downX) / img.width(); val dy = (ev.y - downY) / img.height()
                val m = BoardImage.MIN_CROP * 2
                if (edges == 16) {
                    val w = start[2] - start[0]; val h = start[3] - start[1]
                    val nl = (start[0] + dx).coerceIn(0f, 1f - w); val nt = (start[1] + dy).coerceIn(0f, 1f - h)
                    crop[0] = nl; crop[2] = nl + w; crop[1] = nt; crop[3] = nt + h
                } else {
                    if (edges and 1 != 0) crop[0] = (start[0] + dx).coerceIn(0f, crop[2] - m)
                    if (edges and 4 != 0) crop[2] = (start[2] + dx).coerceIn(crop[0] + m, 1f)
                    if (edges and 2 != 0) crop[1] = (start[1] + dy).coerceIn(0f, crop[3] - m)
                    if (edges and 8 != 0) crop[3] = (start[3] + dy).coerceIn(crop[1] + m, 1f)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edges = 0
        }
        return true
    }

    /** Для проверок: протянуть угол/край из точки (доли картинки) в точку. */
    internal fun dragFraction(fx0: Float, fy0: Float, fx1: Float, fy1: Float) {
        fun sx(f: Float) = img.left + f * img.width()
        fun sy(f: Float) = img.top + f * img.height()
        val t0 = android.os.SystemClock.uptimeMillis()
        dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, sx(fx0), sy(fy0), 0))
        dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 40, MotionEvent.ACTION_MOVE, sx(fx1), sy(fy1), 0))
        dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 80, MotionEvent.ACTION_UP, sx(fx1), sy(fy1), 0))
    }
}
