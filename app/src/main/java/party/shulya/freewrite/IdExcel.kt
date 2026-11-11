package party.shulya.freewrite

import android.graphics.Color
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Выгрузка таблиц ИД в Excel (ТЗ «Учёт ИГС», раздел 9.1): файл .xlsx без сторонних библиотек.
 * Листы: «Сводка» (как «Статистика» Владимира), «Схемы» (все отобранные строки, фильтр в заголовке) и по листу на шифр.
 */
object IdExcel {
    const val SHEET_SUMMARY = "sum"
    const val SHEET_ALL = "all"
    const val SHEET_PER = "per"
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** Что выгружать: таблицы, статусы ИГС, исполнители (пустое имя — «не указан»). */
    class Filter(val tables: Set<String>, val igs: Set<String>, val who: Set<String>) {
        fun matches(r: BoardRec) = r.t in tables && r.igs in igs && r.who.trim() in who
    }

    fun allFilter(b: Board) = Filter(b.tables.map { it.id }.toSet(), b.statuses(BoardStatus.ID).map { it.id }.toSet(),
        IdTables.liveRecs(b).map { it.who.trim() }.toSet())

    /** Отобранные строки по порядку: таблицы по шифру, строки по порядку в таблице. */
    fun rows(b: Board, f: Filter): List<Pair<BoardTable, BoardRec>> =
        b.tables.sortedWith { p, q -> IdTables.naturalCompare(p.title, q.title) }
            .flatMap { t -> IdTables.allRows(b, t).filter { f.matches(it) }.map { t to it } }

    // ---------------------------------------------------------------- книга

    private class Cell(val v: Any?, val style: Int = 0)

    private class Sheet(val name: String) {
        val rows = ArrayList<List<Cell>>()
        var widths = DoubleArray(0)
        var filterCols = 0
        var filterFrom = 0
        var freeze = 0
    }

    /** Стили: 0 — обычный, 1 — заголовок, 2 — дата, 3 — текст с рамкой, 4 — число с рамкой, 5 — крупный жирный, 6… — статусы (фон). */
    private class Styles {
        val fills = ArrayList<Int>()
        fun status(color: Int): Int {
            val bg = Board.statusBg(color)
            var i = fills.indexOf(bg)
            if (i < 0) { fills += bg; i = fills.size - 1 }
            return 6 + i
        }
    }

    /** Записать книгу; вернуть, сколько схем выгружено. */
    fun write(b: Board, f: Filter, sheets: Set<String>, out: OutputStream, today: String = IdTables.today()): Int {
        val st = Styles()
        val list = rows(b, f)
        val book = ArrayList<Sheet>()
        if (SHEET_SUMMARY in sheets) book += summarySheet(b, list.map { it.second }, list.map { it.first }.distinct(), today)
        val custom = LinkedHashMap<String, String>() // ключ своего столбца → название (первое встретившееся)
        for (t in b.tables) for (c in t.allCols()) if (c.custom && c.k !in custom) custom[c.k] = c.title()
        if (SHEET_ALL in sheets) book += dataSheet("Схемы", b, list, null, custom, st)
        if (SHEET_PER in sheets) {
            val used = HashSet<String>().apply { book.forEach { add(it.name.lowercase()) } }
            for ((t, part) in list.groupBy({ it.first }, { it }).entries.sortedWith { p, q -> IdTables.naturalCompare(p.key.title, q.key.title) }) {
                book += dataSheet(sheetName(t.title, used), b, part, t, custom, st)
            }
        }
        if (book.isEmpty()) book += summarySheet(b, list.map { it.second }, list.map { it.first }.distinct(), today)
        writeZip(book, st, out)
        return list.size
    }

    private fun sheetName(title: String, used: HashSet<String>): String {
        var base = title.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().ifEmpty { "Таблица" }.take(31)
        var n = base; var i = 2
        while (!used.add(n.lowercase())) { val suf = " ($i)"; n = base.take(31 - suf.length) + suf; i++ }
        return n
    }

    private fun dataSheet(name: String, b: Board, list: List<Pair<BoardTable, BoardRec>>, only: BoardTable?, custom: Map<String, String>, st: Styles): Sheet {
        val s = Sheet(name)
        // Столбцы: для листа шифра — как в его таблице (видимые, его названия); для «Схемы» — все встроенные + свои.
        val cols: List<Pair<String, String>> = if (only != null) only.visibleCols().map { it.k to it.title() }
        else TableCol.ORDER.map { it to TableCol.defaultName(it) } + custom.map { it.key to it.value }
        val head = ArrayList<Cell>()
        if (only == null) head += Cell("Шифр", 1)
        for ((_, n) in cols) head += Cell(n, 1)
        s.rows.add(head)
        for ((t, r) in list) {
            val row = ArrayList<Cell>()
            if (only == null) row += Cell(t.title, 3)
            for ((k, _) in cols) {
                row += when (k) {
                    TableCol.IGS, TableCol.CK, TableCol.DWG -> {
                        val sx = IdTables.status(b, IdTables.groupOf(k), r.get(k))
                        Cell(sx.name, st.status(sx.color))
                    }
                    TableCol.DATE, TableCol.DCUST, TableCol.DPTO -> IdTables.excelDay(r.get(k))?.let { Cell(it, 2) } ?: Cell(null, 3)
                    else -> Cell(r.get(k).ifEmpty { null }, 3)
                }
            }
            s.rows.add(row)
        }
        s.widths = DoubleArray(head.size) { i ->
            val k = if (only == null) (if (i == 0) "cipher" else cols[i - 1].first) else cols[i].first
            when (k) {
                "cipher" -> 14.0; TableCol.ZAH -> 16.0; TableCol.CON -> 24.0; TableCol.WORK -> 16.0
                TableCol.DATE, TableCol.DCUST, TableCol.DPTO -> 14.0; TableCol.CK -> 24.0; TableCol.DWG -> 12.0
                TableCol.IGS -> 26.0; TableCol.WHO -> 18.0; TableCol.NOTE -> 34.0; else -> 18.0
            }
        }
        s.filterCols = head.size
        s.filterFrom = 1
        s.freeze = 1
        return s
    }

    private fun summarySheet(b: Board, recs: List<BoardRec>, tables: List<BoardTable>, today: String): Sheet {
        val s = Sheet("Сводка")
        val igs = b.statuses(BoardStatus.ID)
        val ck = b.statuses(BoardStatus.CK)
        val dwg = b.statuses(BoardStatus.DWG)
        fun done(r: BoardRec) = IdTables.status(b, BoardStatus.ID, r.igs).done
        fun cnt(rows: List<BoardRec>, k: String, sx: BoardStatus) = rows.count { IdTables.status(b, IdTables.groupOf(k), it.get(k)).id == sx.id }
        s.rows.add(listOf(Cell("Сводка ИГС — " + b.title, 5)))
        s.rows.add(listOf(Cell("на " + IdTables.dateLong(today) + " · схем: " + recs.size + " · сдано в ПТО: " + recs.count { done(it) })))
        s.rows.add(emptyList())
        fun block(first: String, groups: List<Pair<String, List<BoardRec>>>) {
            s.rows.add(listOf(Cell(first, 1), Cell("Всего схем", 1), Cell("Сдано в ПТО", 1), Cell("Не сдано", 1)) + igs.map { Cell(it.name, 1) })
            for ((n, rows) in groups) {
                s.rows.add(listOf(Cell(n, 3), Cell(rows.size, 4), Cell(rows.count { done(it) }, 4), Cell(rows.count { !done(it) }, 4)) +
                    igs.map { Cell(cnt(rows, TableCol.IGS, it), 4) })
            }
        }
        val byTable = tables.sortedWith { p, q -> IdTables.naturalCompare(p.title, q.title) }.map { t -> t.title to recs.filter { it.t == t.id } }
        block("Шифр", byTable + listOf("Итого" to recs))
        s.rows.add(emptyList())
        val whos = recs.map { it.who.trim() }.distinct().sortedWith { p, q -> IdTables.naturalCompare(p, q) }
        block("Исполнитель", whos.map { w -> w.ifEmpty { "Не указан" } to recs.filter { it.who.trim() == w } })
        s.rows.add(emptyList())
        s.rows.add(listOf(Cell("Чек-лист", 1)) + ck.map { Cell(it.name, 1) })
        s.rows.add(listOf(Cell("Всего", 3)) + ck.map { Cell(cnt(recs, TableCol.CK, it), 4) })
        s.rows.add(emptyList())
        s.rows.add(listOf(Cell("DWG", 1)) + dwg.map { Cell(it.name, 1) })
        s.rows.add(listOf(Cell("Всего", 3)) + dwg.map { Cell(cnt(recs, TableCol.DWG, it), 4) })
        val n = 4 + igs.size
        s.widths = DoubleArray(n) { if (it == 0) 22.0 else 14.0 }
        return s
    }

    // ---------------------------------------------------------------- XML и zip

    private fun esc(v: String): String {
        val sb = StringBuilder(v.length + 8)
        for (ch in v) when (ch) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;")
            else -> if (ch < ' ' && ch != '\n' && ch != '\t') sb.append(' ') else sb.append(ch)
        }
        return sb.toString()
    }

    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun sheetXml(s: Sheet): String {
        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (s.freeze > 0) sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"").append(s.freeze)
            .append("\" topLeftCell=\"A").append(s.freeze + 1).append("\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        if (s.widths.isNotEmpty()) {
            sb.append("<cols>")
            for ((i, w) in s.widths.withIndex()) sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(w).append("\" customWidth=\"1\"/>")
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        for ((ri, row) in s.rows.withIndex()) {
            sb.append("<row r=\"").append(ri + 1).append("\">")
            for ((ci, c) in row.withIndex()) {
                val ref = colName(ci) + (ri + 1)
                when (val v = c.v) {
                    null -> sb.append("<c r=\"").append(ref).append("\" s=\"").append(c.style).append("\"/>")
                    is Number -> sb.append("<c r=\"").append(ref).append("\" s=\"").append(c.style).append("\"><v>").append(v).append("</v></c>")
                    else -> sb.append("<c r=\"").append(ref).append("\" s=\"").append(c.style).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(esc(v.toString())).append("</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (s.filterCols > 0 && s.rows.size > s.filterFrom - 1) {
            sb.append("<autoFilter ref=\"A").append(s.filterFrom).append(':').append(colName(s.filterCols - 1)).append(s.rows.size).append("\"/>")
        }
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun hex(c: Int) = String.format("FF%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c))

    private fun stylesXml(st: Styles): String {
        val sb = StringBuilder(2048)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"dd.mm.yyyy\"/></numFmts>")
        sb.append("<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"14\"/><name val=\"Calibri\"/></font></fonts>")
        sb.append("<fills count=\"").append(3 + st.fills.size).append("\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>")
        sb.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDDEFE2\"/><bgColor indexed=\"64\"/></patternFill></fill>")
        for (c in st.fills) sb.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"").append(hex(c)).append("\"/><bgColor indexed=\"64\"/></patternFill></fill>")
        sb.append("</fills>")
        sb.append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>")
        sb.append("<border><left style=\"thin\"><color rgb=\"FFC9D2DF\"/></left><right style=\"thin\"><color rgb=\"FFC9D2DF\"/></right><top style=\"thin\"><color rgb=\"FFC9D2DF\"/></top><bottom style=\"thin\"><color rgb=\"FFC9D2DF\"/></bottom><diagonal/></border></borders>")
        sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        sb.append("<cellXfs count=\"").append(6 + st.fills.size).append("\">")
        sb.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
        sb.append("<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"><alignment wrapText=\"1\" vertical=\"center\"/></xf>")
        sb.append("<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>")
        sb.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/>")
        sb.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/>")
        sb.append("<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>")
        for (i in st.fills.indices) sb.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"").append(3 + i).append("\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\"/>")
        sb.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
        return sb.toString()
    }

    private fun writeZip(book: List<Sheet>, st: Styles, out: OutputStream) {
        val sheets = book.map { sheetXml(it) } // стили статусов набираются при подготовке листов — до styles.xml
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            val ct = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            ct.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            ct.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            for (i in book.indices) ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            ct.append("</Types>")
            put("[Content_Types].xml", ct.toString())
            put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            val wb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
            for ((i, s) in book.withIndex()) wb.append("<sheet name=\"").append(esc(s.name)).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>")
            wb.append("</sheets>")
            val dn = book.withIndex().filter { it.value.filterCols > 0 && it.value.rows.size > 1 }
            if (dn.isNotEmpty()) {
                wb.append("<definedNames>")
                for ((i, s) in dn) wb.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i).append("\" hidden=\"1\">'")
                    .append(esc(s.name.replace("'", "''"))).append("'!\$A\$1:\$").append(colName(s.filterCols - 1)).append('$').append(s.rows.size).append("</definedName>")
                wb.append("</definedNames>")
            }
            wb.append("</workbook>")
            put("xl/workbook.xml", wb.toString())
            val rels = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            for (i in book.indices) rels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>")
            rels.append("<Relationship Id=\"rId").append(book.size + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
            put("xl/_rels/workbook.xml.rels", rels.toString())
            put("xl/styles.xml", stylesXml(st))
            for ((i, x) in sheets.withIndex()) put("xl/worksheets/sheet" + (i + 1) + ".xml", x)
        }
    }
}
