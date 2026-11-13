package party.shulya.freewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

/**
 * Поиск элементов по линиям PDF (0.26): чтение файла (слои, сжатие, повороты), несущие/ненесущие стены, плита с
 * отверстием и швом-зигзагом, ножницы. PDF собирается в проверке; чертежи Владимира — только с PLAN_PDF/SLAB_PDF
 * на компьютере разработчика (рабочие данные в репозиторий не кладутся).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanElementsTest {

    companion object {
        /** Маленький PDF: страница 600×400 пт, слои по именам, содержимое [content] (сжатое или нет). */
        fun pdf(content: String, layers: List<String>, flate: Boolean = true, rotate: Int = 0): ByteArray {
            val out = ByteArrayOutputStream()
            fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
            val n = 4 + layers.size
            val offs = IntArray(n + 1)
            w("%PDF-1.7\n%âã\n")
            val ocgs = layers.indices.joinToString(" ") { "${4 + it} 0 R" }
            offs[1] = out.size(); w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [$ocgs] >> >>\nendobj\n")
            offs[2] = out.size(); w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 600 400] >>\nendobj\n")
            val props = layers.indices.joinToString(" ") { "/oc$it ${4 + it} 0 R" }
            offs[3] = out.size(); w("3 0 obj\n<< /Type /Page /Parent 2 0 R ${if (rotate != 0) "/Rotate $rotate" else ""} /Resources << /Properties << $props >> >> /Contents $n 0 R >>\nendobj\n")
            for ((i, name) in layers.withIndex()) { offs[4 + i] = out.size(); w("${4 + i} 0 obj\n<< /Type /OCG /Name ($name) >>\nendobj\n") }
            val raw = content.toByteArray(Charsets.ISO_8859_1)
            val data = if (flate) { val d = Deflater(); d.setInput(raw); d.finish(); val b = ByteArray(raw.size + 64); val k = d.deflate(b); d.end(); b.copyOf(k) } else raw
            offs[n] = out.size()
            w("$n 0 obj\n<< /Length ${data.size}${if (flate) " /Filter /FlateDecode" else ""} >>\nstream\n"); out.write(data); w("\nendstream\nendobj\n")
            val xref = out.size()
            w("xref\n0 ${n + 1}\n0000000000 65535 f \n")
            for (i in 1..n) w(String.format("%010d 00000 n \n", offs[i]))
            w("trailer\n<< /Size ${n + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return out.toByteArray()
        }

        val WALL_LAYERS = listOf("A-WALL-____-MCUT", "A-WALL-____-PATT", "A-____-____-DIMS-100", "A-DOOR-OPNG-MCUT")

        /** Два пилона, стена с проёмом (линии оборваны), ненесущая тонкая со штриховкой, размер с текстом, проём пунктиром. */
        val WALLS = """
            /OC /oc0 BDC
            2 w
            100 100 10 50 re S
            200 100 10 50 re S
            300 200 m 340 200 l S
            300 208 m 340 208 l S
            300 200 m 300 208 l S
            360 200 m 400 200 l S
            360 208 m 400 208 l S
            400 200 m 400 208 l S
            0.2 w
            150 300 20 8 re S
            EMC
            /OC /oc1 BDC 0.2 w
            152 300 m 156 308 l S 155 300 m 159 308 l S 158 300 m 162 308 l S 161 300 m 165 308 l S 164 300 m 168 308 l S
            102 100 m 108 112 l S 102 115 m 108 127 l S
            EMC
            /OC /oc2 BDC 1 w 100 80 m 210 80 l S 0 0 0 rg 100 78 m 104 82 l 100 82 l f
            BT /F1 8 Tf 150 82 Td (4500) Tj ET EMC
            /OC /oc3 BDC 2 w 342 200 m 346 200 l S 350 200 m 354 200 l S EMC
        """.trimIndent()
    }

    private fun find(bytes: ByteArray, mode: Int, crop: FloatArray = floatArrayOf(0f, 0f, 1f, 1f)) =
        PlanElements.find(PdfVector.read(bytes, 0), mode, crop)

    @Test
    fun readsLayersPathsAndCurves() {
        for (flate in listOf(true, false)) {
            val p = PdfVector.read(pdf(WALLS + "\n/OC /oc0 BDC 1 w 50 50 m 50 80 80 80 80 50 c S EMC", WALL_LAYERS, flate), 0)
            assertEquals(600f, p.w, 0.01f); assertEquals(400f, p.h, 0.01f)
            assertEquals(setOf(*WALL_LAYERS.toTypedArray()), p.layers.keys)
            val r = p.paths.first { it.layer == "A-WALL-____-MCUT" && it.rings[0].size == 10 }
            // Прямоугольник 100,100 → 110,150 (y вверх) → y вниз: 250…300.
            assertEquals(100f, r.rings[0][0], 0.01f); assertEquals(300f, r.rings[0][1], 0.01f)
            assertTrue(r.closed[0])
            assertEquals(2f, r.width, 0.01f)
            val curve = p.paths.last()
            assertTrue("кривая — ломаная", curve.rings[0].size > 8)
            assertTrue("текст не читается как линии", p.paths.none { it.layer.contains("DIMS") && it.rings.any { r2 -> r2.size > 6 } && it.stroke && it.width < 0.9f })
        }
        // Поворот листа: 600×400 → 400×600.
        val rot = PdfVector.read(pdf(WALLS, WALL_LAYERS, rotate = 90), 0)
        assertEquals(400f, rot.w, 0.01f); assertEquals(600f, rot.h, 0.01f)
    }

    @Test
    fun bearingWallsPylonsAndOpening() {
        val f = find(pdf(WALLS, WALL_LAYERS), PlanElements.BEARING)
        // 2 пилона + 2 куска стены (проём между ними замкнут поперёк); размер, текст и пунктир проёма — не элементы.
        assertEquals(f.message, 4, f.elements.size)
        val boxes = f.elements.map { e -> box(e.outer) }.sortedBy { it[0] }
        // Пилон 100…110 × (400−150)…(400−100) → доли 0,167…0,183 × 0,625…0,75.
        assertEquals(100f / 600f, boxes[0][0], 0.01f); assertEquals(110f / 600f, boxes[0][2], 0.01f)
        assertEquals(250f / 400f, boxes[0][1], 0.01f); assertEquals(300f / 400f, boxes[0][3], 0.01f)
        // Куски стены: левый кончается у проёма (≈340), правый начинается после (≈360).
        assertEquals(340f / 600f, boxes[2][2], 0.01f)
        assertEquals(360f / 600f, boxes[3][0], 0.01f)
    }

    @Test
    fun nonBearingThinWithHatch() {
        val f = find(pdf(WALLS, WALL_LAYERS), PlanElements.NONBEARING)
        assertEquals(f.message, 1, f.elements.size)
        val b = box(f.elements[0].outer)
        assertEquals(150f / 600f, b[0], 0.01f); assertEquals(170f / 600f, b[2], 0.01f)
    }

    @Test
    fun slabOutlineHolesOpeningAndZigzagSeam() {
        val zig = StringBuilder("/OC /oc1 BDC 0.12 w 300 60 m")
        var y = 60
        var k = 0
        while (y <= 340) { zig.append(" ${if (k % 2 == 0) 299 else 301} $y l"); y += 5; k++ }
        zig.append(" S EMC")
        val content = """
            /OC /oc0 BDC 1 w
            50 50 500 300 re S
            200 150 40 30 re S
            EMC
            /OC /oc2 BDC 0.5 w 420 250 50 40 re S 0 0 0 rg 420 250 m 470 250 l 420 290 l f EMC
            /OC /oc3 BDC 1 w 60 200 m 540 200 l S EMC
            $zig
        """.trimIndent()
        val bytes = pdf(content, listOf("A-FLOR-____-OTLN", "A-DETL-GENF-OTLN", "A-WALL-____-MCUT", "S-BEAM-____-HDLN"))
        val f = find(bytes, PlanElements.SLAB)
        assertEquals(f.message, 2, f.elements.size)
        assertEquals(1, f.seams.size)
        // Шов продлён до края плиты: от y≈50 до y≈350 (доли 0,125…0,875).
        val s = f.seams[0]
        assertEquals(0.5f, (s[0] + s[2]) / 2f, 0.01f)
        assertTrue(minOf(s[1], s[3]) < 0.14f && maxOf(s[1], s[3]) > 0.86f)
        // Площадь: плита минус отверстие 40×30 и проём со значком 50×40 (в долях листа).
        val total = f.elements.sumOf { PlanElements.area(it.outer, it.holes, 400f / 600f).toDouble() }
        val expect = (500.0 * 300 - 40 * 30 - 50 * 40) / (600.0 * 600)
        assertEquals(expect, total, expect * 0.04)
        // Невидимая линия балки не режет плиту.
    }

    @Test
    fun scissorsSplitAndMiss() {
        val rect = MagicFill.rect(0.2f, 0.2f, 0.6f, 0.4f)
        val parts = PlanElements.split(rect, emptyList(), 0.7f, floatArrayOf(0.4f, 0.1f, 0.4f, 0.5f))!!
        assertEquals(2, parts.size)
        val a = parts.sumOf { PlanElements.area(it.outer, it.holes, 0.7f).toDouble() }
        assertEquals(0.4 * 0.2 * 0.7, a, 0.4 * 0.2 * 0.7 * 0.04)
        val b0 = box(parts[0].outer); val b1 = box(parts[1].outer)
        assertTrue(maxOf(b0[0], b1[0]) > 0.39f && minOf(b0[2], b1[2]) < 0.41f)
        // Линия не насквозь — не делит.
        assertNull(PlanElements.split(rect, emptyList(), 0.7f, floatArrayOf(0.4f, 0.1f, 0.4f, 0.3f)))
        // Кусок с дырой: разрез через дыру — две части.
        val hole = MagicFill.rect(0.35f, 0.25f, 0.45f, 0.35f)
        assertEquals(2, PlanElements.split(rect, listOf(hole), 0.7f, floatArrayOf(0.4f, 0.1f, 0.4f, 0.5f))!!.size)
    }

    /** Привязка «как в AutoCAD» (0.26.1): угол пилона, пересечение линий; размеры и штриховка — нет. */
    @Test
    fun snapToCornersAndCrossings() {
        val content = WALLS + "\n/OC /oc0 BDC 1 w 450 50 m 550 150 l S 450 150 m 550 50 l S EMC"
        val sn = PlanSnap(PdfVector.read(pdf(content, WALL_LAYERS), 0))
        // Угол пилона 100,100…110,150 (y вверх) → левый верхний на картинке: 100 / 600, (400 − 150) / 400.
        val h = sn.near(0.169f, 0.628f, 0.01f)!!
        assertEquals(PlanSnap.END, h.kind)
        assertEquals(100f / 600f, h.fx, 1e-4f); assertEquals(250f / 400f, h.fy, 1e-4f)
        // Пересечение двух линий в 500, 100 → 500 / 600, 300 / 400.
        val c = sn.near(0.835f, 0.752f, 0.01f)!!
        assertEquals(PlanSnap.CROSS, c.kind)
        assertEquals(500f / 600f, c.fx, 1e-3f); assertEquals(0.75f, c.fy, 1e-3f)
        // Вдали от линий — ничего; линия размеров (слой DIMS) — не привязывает.
        assertEquals(null, sn.near(0.9f, 0.1f, 0.01f))
        assertEquals(null, sn.near(100f / 600f, (400f - 80f) / 400f, 0.003f))
    }

    @Test
    fun brokenXrefStillReads() {
        val good = pdf(WALLS, WALL_LAYERS)
        val s = String(good, Charsets.ISO_8859_1)
        val bad = s.replace(Regex("startxref\\n\\d+"), "startxref\n999999").toByteArray(Charsets.ISO_8859_1)
        val p = PdfVector.read(bad, 0)
        assertTrue(p.paths.size > 10)
    }

    @Test
    fun layerRoles() {
        assertEquals(PlanElements.Role.CUT, PlanElements.roleOf("A-WALL-____-MCUT"))
        assertEquals(PlanElements.Role.HATCH, PlanElements.roleOf("A-WALL-____-PATT"))
        assertEquals(PlanElements.Role.SLAB, PlanElements.roleOf("A-FLOR-____-OTLN"))
        assertEquals(PlanElements.Role.ANNOT, PlanElements.roleOf("A-____-____-DIMS-100"))
        assertEquals(PlanElements.Role.ANNOT, PlanElements.roleOf("S-GRID-____-OTLN"))
        assertEquals(PlanElements.Role.HIDDEN, PlanElements.roleOf("S-BEAM-____-HDLN"))
        assertEquals(PlanElements.Role.OPENING, PlanElements.roleOf("A-DOOR-OPNG-MCUT"))
        assertEquals(PlanElements.Role.OTHER, PlanElements.roleOf("A-DETL-GENF-OTLN"))
        assertEquals(PlanElements.Role.CUT, PlanElements.roleOf("КЖ_Стены"))
    }

    /** Чертежи Владимира (КЖ 7.1.3 — стены, КЖ 7.2.2 — плита): только на компьютере разработчика. */
    @Test
    fun realDrawings() {
        val walls = System.getenv("PLAN_PDF")?.let { File(it) }?.takeIf { it.isFile }
        val slab = System.getenv("SLAB_PDF")?.let { File(it) }?.takeIf { it.isFile }
        assumeTrue(walls != null || slab != null)
        if (walls != null) {
            val t0 = System.nanoTime()
            val p = PdfVector.read(walls, 0)
            val t1 = System.nanoTime()
            val b = PlanElements.find(p, PlanElements.BEARING, floatArrayOf(0f, 0f, 1f, 1f))
            val t2 = System.nanoTime()
            val nb = PlanElements.find(p, PlanElements.NONBEARING, floatArrayOf(0f, 0f, 1f, 1f))
            val t3 = System.nanoTime()
            println("REAL walls: paths ${p.paths.size}, layers ${p.layers.size}, read ${(t1 - t0) / 1_000_000} ms; " +
                "bearing ${b.elements.size} (${(t2 - t1) / 1_000_000} ms), nonbearing ${nb.elements.size} (${(t3 - t2) / 1_000_000} ms)")
            assertTrue("несущих ~54: ${b.elements.size}", b.elements.size in 48..60)
            assertTrue("ненесущих ~62: ${nb.elements.size}", nb.elements.size in 55..70)
            debugShot(p, b, "real-bearing.png")
            debugShot(p, nb, "real-nonbearing.png")
        }
        if (slab != null) {
            val t0 = System.nanoTime()
            val p = PdfVector.read(slab, 0)
            val t1 = System.nanoTime()
            val f = PlanElements.find(p, PlanElements.SLAB, floatArrayOf(0f, 0f, 1f, 1f))
            val t2 = System.nanoTime()
            val area = f.elements.sumOf { PlanElements.area(it.outer, it.holes, p.h / p.w).toDouble() }
            println("REAL slab: paths ${p.paths.size}, read ${(t1 - t0) / 1_000_000} ms; pieces ${f.elements.size}, seams ${f.seams.size}, " +
                "area ${"%.4f".format(area)} (${(t2 - t1) / 1_000_000} ms) — ${f.message}")
            assertTrue(f.elements.size >= 2)
            debugShot(p, f, "real-slab.png")
        }
    }

    /** Картинка найденного (для глаз разработчика): куски разными цветами, швы красным. */
    private fun debugShot(p: PdfVector.Page, f: PlanElements.Found, name: String) {
        val w = 2000; val h = (2000 * p.h / p.w).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val line = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; color = android.graphics.Color.GRAY; strokeWidth = 1f }
        for (pp in p.paths) for (r in pp.rings) { var i = 0; while (i + 3 < r.size) { c.drawLine(r[i] / p.w * w, r[i + 1] / p.h * h, r[i + 2] / p.w * w, r[i + 3] / p.h * h, line); i += 2 } }
        val cols = intArrayOf(0x88D6470C.toInt(), 0x882979FF.toInt(), 0x882EA043.toInt(), 0x88C89600.toInt(), 0x889C27B0.toInt())
        for ((k, e) in f.elements.withIndex()) {
            val path = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
            for (r in listOf(e.outer) + e.holes) { path.moveTo(r[0] * w, r[1] * h); var i = 2; while (i + 1 < r.size) { path.lineTo(r[i] * w, r[i + 1] * h); i += 2 }; path.close() }
            c.drawPath(path, android.graphics.Paint().apply { color = cols[k % cols.size] })
        }
        val red = android.graphics.Paint().apply { color = android.graphics.Color.RED; strokeWidth = 4f }
        for (s in f.seams) c.drawLine(s[0] * w, s[1] * h, s[2] * w, s[3] * h, red)
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun box(p: FloatArray): FloatArray {
        var l = 9f; var t = 9f; var r = -9f; var b = -9f
        var i = 0
        while (i + 1 < p.size) { l = minOf(l, p[i]); r = maxOf(r, p[i]); t = minOf(t, p[i + 1]); b = maxOf(b, p[i + 1]); i += 2 }
        return floatArrayOf(l, t, r, b)
    }
}
