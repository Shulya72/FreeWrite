package party.shulya.freewrite

/**
 * Инструмент «Фигуры» — общий для листа записи и доски.
 *
 * Каждый штрих сразу проверяется: линия, стрелка, прямоугольник, круг… — и заменяется ровной
 * фигурой. Если не похоже ни на что — остаётся как нарисовано.
 * Несколько штрихов подряд (без паузы дольше 2,5 с) проверяются и вместе: древко + наконечник
 * становятся стрелкой, три линии с общими концами — треугольником, четыре — прямоугольником.
 */
class ShapeTool {

    /** Что сделать с рисунком: убрать прежние штрихи (их заменила фигура) и добавить новый. */
    class Result(val remove: List<Drawing>, val add: Drawing, val kind: ShapeRecognizer.Kind?)

    private class Recent(val raw: FloatArray, val drawing: Drawing)

    private val recent = ArrayList<Recent>()
    private var lastTime = 0L

    /**
     * @param drawings рисунки на листе/доске сейчас (чтобы не трогать уже стёртое или отменённое)
     * @param minSize, step — в единицах листа/доски
     */
    fun onStroke(
        s: InkStroke,
        style: NoteInkView.StrokeStyle,
        drawings: List<Drawing>,
        minSize: Float,
        step: Float,
        now: Long,
    ): Result {
        val raw = xy(s)
        if (now - lastTime > PAUSE_MS) recent.clear()
        lastTime = now
        recent.removeAll { r -> drawings.none { it === r.drawing } }

        // Сначала — вместе с последним штрихом, потом с двумя последними и т. д.:
        // так соседний, уже готовый квадрат не мешает собрать стрелку из древка и усиков.
        for (k in 1..recent.size) {
            val part = recent.takeLast(k)
            val combo = ShapeRecognizer.recognize(part.map { it.raw } + listOf(raw), minSize, step) ?: continue
            val removed = part.map { it.drawing }
            val d = drawingOf(combo, style)
            recent.clear() // собранная фигура готова, дальше — с чистого листа
            return Result(removed, d, combo.kind)
        }
        val one = ShapeRecognizer.recognize(listOf(raw), minSize, step)
        val d = if (one != null) drawingOf(one, style) else NoteInkView.toDrawing(s, style)
        recent += Recent(raw, d)
        while (recent.size > 3) recent.removeAt(0)
        return Result(emptyList(), d, one?.kind)
    }

    fun reset() {
        recent.clear()
    }

    companion object {
        private const val PAUSE_MS = 2500L

        /** Точки штриха x, y подряд. */
        fun xy(s: InkStroke): FloatArray {
            val out = FloatArray(s.points.size * 2)
            s.points.forEachIndexed { i, p -> out[i * 2] = p.x; out[i * 2 + 1] = p.y }
            return out
        }

        fun xy(strokes: List<InkStroke>): List<FloatArray> = strokes.map { xy(it) }

        /** Ровная фигура → рисунок ручкой ровной толщины (нажим 0,5 = толщина инструмента). */
        fun drawingOf(shape: ShapeRecognizer.Shape, style: NoteInkView.StrokeStyle): Drawing {
            val n = shape.pts.size / 2
            val arr = FloatArray(n * 3)
            for (i in 0 until n) {
                arr[i * 3] = shape.pts[i * 2]
                arr[i * 3 + 1] = shape.pts[i * 2 + 1]
                arr[i * 3 + 2] = 0.5f
            }
            return Drawing("pen", style.color, style.width, arr)
        }

        /**
         * Надпись в режиме «В текст» — на самом деле фигура? Только если она крупная
         * (больше нескольких строк текста), из 1–3 штрихов, и распознаватель текста
         * не увидел в ней слова (не больше двух букв: «О», «→», «—» и т. п.).
         */
        fun fromWriting(strokes: List<InkStroke>, bestText: String, bigEnough: Float, step: Float): ShapeRecognizer.Shape? {
            if (strokes.isEmpty() || strokes.size > 3) return null
            if (bestText.count { it.isLetter() } > 2) return null
            val b = NoteInkView.bounds(strokes)
            if (maxOf(b[2] - b[0], b[3] - b[1]) < bigEnough) return null
            return ShapeRecognizer.recognize(xy(strokes), bigEnough * 0.5f, step)
        }
    }
}
