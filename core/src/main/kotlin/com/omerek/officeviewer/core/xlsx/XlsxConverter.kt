package com.omerek.officeviewer.core.xlsx

import com.omerek.officeviewer.core.DocumentException
import com.omerek.officeviewer.core.DocumentFormat
import com.omerek.officeviewer.core.Html
import com.omerek.officeviewer.core.RenderResult
import com.omerek.officeviewer.core.RenderStrings
import com.omerek.officeviewer.core.Units
import com.omerek.officeviewer.core.Units.fmt
import com.omerek.officeviewer.core.text.CsvConverter
import com.omerek.officeviewer.core.xml.OoxmlColors
import com.omerek.officeviewer.core.xml.OoxmlPackage
import com.omerek.officeviewer.core.xml.XNode
import java.util.Locale

/** Converts a SpreadsheetML (.xlsx) workbook to HTML with one table per sheet and sheet tabs. */
class XlsxConverter(
    private val strings: RenderStrings = RenderStrings(),
    private val locale: Locale = Locale.getDefault(),
) {
    companion object {
        const val MAX_ROWS = 5000
        const val MAX_COLS = 200
        private const val HEADER_H = 22.0
        private const val ROWNUM_W = 44.0
    }

    private class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean,
                       val color: String?, val size: Double?, val name: String?)
    private class Border(val css: String)
    private class Xf(val numFmtId: Int, val fontId: Int, val fillId: Int, val borderId: Int,
                     val hAlign: String?, val vAlign: String?, val wrap: Boolean, val indent: Int, val rotate: Int)

    private lateinit var pkg: OoxmlPackage
    private var scheme: Map<String, String> = OoxmlColors.DEFAULT_SCHEME
    private val sharedStrings = ArrayList<String>()
    private val numFmts = HashMap<Int, String>()
    private val fonts = ArrayList<Font>()
    private val fills = ArrayList<String?>()
    private val borders = ArrayList<Border>()
    private val xfs = ArrayList<Xf>()
    private lateinit var formatter: NumberFormatter
    private val warnings = ArrayList<String>()

    fun convert(bytes: ByteArray, title: String): RenderResult {
        pkg = OoxmlPackage.open(bytes)
        val wbPart = pkg.mainPart() ?: "xl/workbook.xml"
        val wb = pkg.xml(wbPart) ?: throw DocumentException("xl/workbook.xml not found")
        val rels = pkg.relationships(wbPart)
        rels.values.firstOrNull { it.type.endsWith("/theme") }?.let { scheme = OoxmlColors.parseScheme(pkg.xml(it.target)) }
        rels.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.let { loadSharedStrings(pkg.xml(it.target)) }
        rels.values.firstOrNull { it.type.endsWith("/styles") }?.let { loadStyles(pkg.xml(it.target)) }
        formatter = NumberFormatter(locale, date1904 = wb.child("workbookPr")?.get("date1904").let { it == "1" || it == "true" })

        val sheets = wb.child("sheets")?.children("sheet").orEmpty()
            .filter { it["state"] != "hidden" && it["state"] != "veryHidden" }
        val body = StringBuilder()
        val tabs = StringBuilder("<nav class=\"tabs\">")
        var count = 0
        for ((i, s) in sheets.withIndex()) {
            val rel = s["r:id"]?.let { rels[it] } ?: continue
            val name = s["name"] ?: "Sheet${i + 1}"
            val root = pkg.xml(rel.target)
            val isChartsheet = rel.type.endsWith("/chartsheet")
            body.append("<section class=\"sheet\" id=\"s$count\"").append(if (count == 0) "" else " hidden").append(">")
            if (root == null || isChartsheet) {
                body.append("<div class=\"ov-msg\">").append(Html.esc(strings.chart)).append("</div>")
            } else {
                renderSheet(root, rel.target, body)
            }
            body.append("</section>")
            tabs.append("<button class=\"tab").append(if (count == 0) " on" else "").append("\" data-i=\"$count\">")
                .append(Html.esc(name)).append("</button>")
            count++
        }
        tabs.append("</nav>")
        if (count == 0) body.append("<div class=\"ov-msg\">${Html.esc(strings.emptyDocument)}</div>")
        val html = Html.page(title, css(), body.toString() + (if (count > 1) tabs.toString() else ""), if (count > 1) SCRIPT else "")
        return RenderResult(html, DocumentFormat.XLSX, count, warnings)
    }

    // ------------------------------------------------------------------ workbook parts

    private fun loadSharedStrings(root: XNode?) {
        root ?: return
        for (si in root.children("si")) {
            val direct = si.child("t")
            if (direct != null) sharedStrings += direct.text
            else sharedStrings += si.children("r").joinToString("") { it.child("t")?.text.orEmpty() }
        }
    }

    private fun loadStyles(root: XNode?) {
        root ?: return
        root.child("numFmts")?.children("numFmt")?.forEach { n ->
            val id = n["numFmtId"]?.toIntOrNull() ?: return@forEach
            n["formatCode"]?.let { numFmts[id] = it }
        }
        root.child("fonts")?.children("font")?.forEach { f ->
            fun has(n: String) = f.child(n)?.let { it["val"] != "0" && it["val"] != "false" } == true
            fonts += Font(
                bold = has("b"), italic = has("i"),
                underline = f.child("u")?.let { it["val"] != "none" } == true,
                strike = has("strike"),
                color = OoxmlColors.sheetColor(f.child("color"), scheme),
                size = f.child("sz")?.get("val")?.toDoubleOrNull(),
                name = f.child("name")?.get("val"),
            )
        }
        root.child("fills")?.children("fill")?.forEach { f ->
            val p = f.child("patternFill")
            val type = p?.get("patternType")
            fills += when {
                p == null -> f.child("gradientFill")?.descendants("color")?.firstOrNull()?.let { OoxmlColors.sheetColor(it, scheme) }
                type == null || type == "none" -> null
                type == "solid" -> OoxmlColors.sheetColor(p.child("fgColor"), scheme)
                else -> OoxmlColors.sheetColor(p.child("fgColor"), scheme) ?: OoxmlColors.sheetColor(p.child("bgColor"), scheme)
            }
        }
        root.child("borders")?.children("border")?.forEach { b ->
            val css = StringBuilder()
            for ((side, el) in listOf("left" to "left", "right" to "right", "top" to "top", "bottom" to "bottom")) {
                val e = b.child(el) ?: b.child(if (el == "left") "start" else if (el == "right") "end" else el) ?: continue
                val style = e["style"] ?: continue
                if (style == "none") continue
                val color = OoxmlColors.sheetColor(e.child("color"), scheme) ?: "#000"
                val w = when (style) { "medium", "mediumDashed", "mediumDashDot", "mediumDashDotDot", "slantDashDot" -> 2; "thick" -> 3; "double" -> 3; else -> 1 }
                val line = when {
                    style == "double" -> "double"
                    style.contains("Dot", true) && !style.contains("Dash") || style == "dotted" || style == "hair" -> "dotted"
                    style.contains("Dash", true) || style == "dashed" -> "dashed"
                    else -> "solid"
                }
                css.append("border-$side:${w}px $line $color;")
            }
            borders += Border(css.toString())
        }
        root.child("cellXfs")?.children("xf")?.forEach { x ->
            val a = x.child("alignment")
            xfs += Xf(
                numFmtId = x["numFmtId"]?.toIntOrNull() ?: 0,
                fontId = x["fontId"]?.toIntOrNull() ?: 0,
                fillId = x["fillId"]?.toIntOrNull() ?: 0,
                borderId = x["borderId"]?.toIntOrNull() ?: 0,
                hAlign = a?.get("horizontal"),
                vAlign = a?.get("vertical"),
                wrap = a?.get("wrapText").let { it == "1" || it == "true" },
                indent = a?.get("indent")?.toIntOrNull() ?: 0,
                rotate = a?.get("textRotation")?.toIntOrNull() ?: 0,
            )
        }
    }

    // ------------------------------------------------------------------ sheet

    private class CellOut(val text: String, val css: String, val numeric: Boolean, val isText: Boolean, val wrap: Boolean, val link: String?)

    private fun renderSheet(sheet: XNode, part: String, out: StringBuilder) {
        val fmtPr = sheet.child("sheetFormatPr")
        val defaultColW = fmtPr?.get("defaultColWidth")?.toDoubleOrNull()
            ?: fmtPr?.get("baseColWidth")?.toDoubleOrNull()?.plus(0.71) ?: 8.43
        val defaultRowPt = fmtPr?.get("defaultRowHeight")?.toDoubleOrNull() ?: 15.0
        val showGrid = sheet.path("sheetViews", "sheetView")?.get("showGridLines") != "0"

        // Column widths
        val colW = HashMap<Int, Double>()
        val hiddenCols = HashSet<Int>()
        sheet.child("cols")?.children("col")?.forEach { c ->
            val min = c["min"]?.toIntOrNull() ?: return@forEach
            val max = minOf(c["max"]?.toIntOrNull() ?: min, MAX_COLS + 1)
            val w = c["width"]?.toDoubleOrNull()
            val hidden = c["hidden"] == "1" || c["hidden"] == "true"
            for (i in min..max) {
                if (w != null) colW[i - 1] = w
                if (hidden) hiddenCols += i - 1
            }
        }
        fun widthPx(col: Int): Double = if (col in hiddenCols) 0.0 else charsToPx(colW[col] ?: defaultColW)

        // Cells
        val rows = sortedMapOf<Int, Pair<XNode, MutableMap<Int, XNode>>>()
        var maxCol = -1
        var maxRow = -1
        var truncated = false
        val hyperlinks = HashMap<String, String>()
        val sheetRels = pkg.relationships(part)
        sheet.child("hyperlinks")?.children("hyperlink")?.forEach { h ->
            val ref = h["ref"]?.substringBefore(':') ?: return@forEach
            val target = h["r:id"]?.let { sheetRels[it]?.target }
            if (target != null) Html.safeHref(target)?.let { hyperlinks[ref.uppercase()] = it }
        }
        sheet.child("sheetData")?.children("row")?.forEachIndexed { idx, r ->
            val rIdx = (r["r"]?.toIntOrNull() ?: (idx + 1)) - 1
            if (rIdx >= MAX_ROWS) { truncated = true; return@forEachIndexed }
            val cells = HashMap<Int, XNode>()
            var colCursor = 0
            for (c in r.children("c")) {
                val col = c["r"]?.let { colIndex(it) } ?: colCursor
                colCursor = col + 1
                if (col >= MAX_COLS) { truncated = true; continue }
                cells[col] = c
                if (hasContent(c) || (c["s"]?.toIntOrNull() ?: 0) != 0) {
                    maxCol = maxOf(maxCol, col)
                    maxRow = maxOf(maxRow, rIdx)
                }
            }
            if (r["ht"] != null || cells.isNotEmpty() || r["hidden"] != null) rows[rIdx] = r to cells
        }

        // Merges
        val mergeOrigin = HashMap<Long, Pair<Int, Int>>() // key -> (rowspan, colspan)
        val covered = HashSet<Long>()
        sheet.child("mergeCells")?.children("mergeCell")?.forEach { m ->
            val ref = m["ref"] ?: return@forEach
            val parts = ref.split(':')
            if (parts.size != 2) return@forEach
            val (r1, c1) = cellRef(parts[0]) ?: return@forEach
            val (r2, c2) = cellRef(parts[1]) ?: return@forEach
            if (r1 >= MAX_ROWS || c1 >= MAX_COLS) return@forEach
            val rr2 = minOf(r2, MAX_ROWS - 1)
            val cc2 = minOf(c2, MAX_COLS - 1)
            mergeOrigin[key(r1, c1)] = (rr2 - r1 + 1) to (cc2 - c1 + 1)
            for (rr in r1..rr2) for (cc in c1..cc2) if (rr != r1 || cc != c1) covered += key(rr, cc)
            maxRow = maxOf(maxRow, rr2)
            maxCol = maxOf(maxCol, cc2)
        }

        // Drawings (images / charts) anchored to cells
        val drawings = sheet.children("drawing").mapNotNull { it["r:id"]?.let { id -> sheetRels[id]?.target } }
        val anchors = drawings.flatMap { parseDrawing(it) }
        for (a in anchors) { maxRow = maxOf(maxRow, minOf(a.toRow, MAX_ROWS - 1)); maxCol = maxOf(maxCol, minOf(a.toCol, MAX_COLS - 1)) }

        if (maxRow < 0 || maxCol < 0) {
            out.append("<div class=\"ov-msg\">").append(Html.esc(strings.emptyDocument)).append("</div>")
            return
        }
        val nCols = maxCol + 1
        val nRows = maxRow + 1
        if (truncated) {
            val msg = String.format(strings.sheetTruncated, MAX_ROWS, MAX_COLS)
            warnings += msg
            out.append("<div class=\"ov-warn\">").append(Html.esc(msg)).append("</div>")
        }

        val rowH = DoubleArray(nRows) { r ->
            val row = rows[r]?.first
            if (row?.get("hidden") == "1" || row?.get("hidden") == "true") 0.0
            else (row?.get("ht")?.toDoubleOrNull() ?: defaultRowPt) * 96 / 72
        }
        val colPx = DoubleArray(nCols) { widthPx(it) }

        out.append("<div class=\"wrap\"><div class=\"canvas\"><table class=\"grid").append(if (showGrid) "" else " nogrid")
            .append("\" style=\"width:${fmt(ROWNUM_W + colPx.sum())}px\"><colgroup><col style=\"width:${fmt(ROWNUM_W)}px\">")
        for (c in 0 until nCols) out.append("<col style=\"width:${fmt(colPx[c])}px\">")
        out.append("</colgroup><thead><tr><th class=\"corner\"></th>")
        for (c in 0 until nCols) out.append("<th>").append(CsvConverter.columnName(c)).append("</th>")
        out.append("</tr></thead><tbody>")

        for (r in 0 until nRows) {
            val cells = rows[r]?.second
            val rowStyle = rows[r]?.first?.get("s")?.toIntOrNull()?.takeIf { rows[r]?.first?.get("customFormat") == "1" }
            out.append("<tr style=\"height:${fmt(rowH[r])}px").append(if (rowH[r] == 0.0) ";display:none" else "").append("\"><th>")
                .append(r + 1).append("</th>")
            // Pre-compute cell content so text can overflow into empty neighbours like Excel does.
            val rendered = arrayOfNulls<CellOut>(nCols)
            for (c in 0 until nCols) {
                val node = cells?.get(c)
                rendered[c] = cellOut(node, rowStyle, colRef(r, c), hyperlinks)
            }
            for (c in 0 until nCols) {
                val k = key(r, c)
                if (k in covered) continue
                val cell = rendered[c]
                val merge = mergeOrigin[k]
                val css = StringBuilder(cell?.css.orEmpty())
                if (cell != null && cell.isText && !cell.wrap && merge == null) {
                    val nextEmpty = c + 1 >= nCols || rendered[c + 1]?.text.isNullOrEmpty()
                    if (nextEmpty && cell.text.isNotEmpty()) css.append("overflow:visible;")
                }
                out.append("<td")
                if (merge != null) {
                    if (merge.first > 1) out.append(" rowspan=\"").append(merge.first).append("\"")
                    if (merge.second > 1) out.append(" colspan=\"").append(merge.second).append("\"")
                }
                if (cell?.numeric == true) out.append(" class=\"n\"")
                if (css.isNotEmpty()) out.append(" style=\"").append(css).append("\"")
                out.append(">")
                if (cell != null && cell.text.isNotEmpty()) {
                    if (cell.link != null) out.append("<a href=\"").append(Html.esc(cell.link)).append("\">")
                    out.append(Html.esc(cell.text).replace("\n", "<br>"))
                    if (cell.link != null) out.append("</a>")
                }
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</tbody></table>")

        // Overlay drawings using cumulative row/column offsets.
        if (anchors.isNotEmpty()) {
            val colStart = DoubleArray(nCols + 1)
            for (c in 0 until nCols) colStart[c + 1] = colStart[c] + colPx[c]
            val rowStart = DoubleArray(nRows + 1)
            for (r in 0 until nRows) rowStart[r + 1] = rowStart[r] + rowH[r]
            fun x(col: Int, off: Double) = ROWNUM_W + colStart[col.coerceIn(0, nCols)] + off
            fun y(row: Int, off: Double) = HEADER_H + rowStart[row.coerceIn(0, nRows)] + off
            for (a in anchors) {
                val left = x(a.fromCol, a.fromColOff)
                val top = y(a.fromRow, a.fromRowOff)
                val w = if (a.widthPx != null) a.widthPx else x(a.toCol, a.toColOff) - left
                val h = if (a.heightPx != null) a.heightPx else y(a.toRow, a.toRowOff) - top
                if (w <= 0 || h <= 0) continue
                out.append("<div class=\"draw\" style=\"left:${fmt(left)}px;top:${fmt(top)}px;width:${fmt(w)}px;height:${fmt(h)}px\">")
                out.append(a.html)
                out.append("</div>")
            }
        }
        out.append("</div></div>")
    }

    private fun charsToPx(chars: Double): Double = if (chars <= 0) 0.0 else Math.round(chars * 7.0 + 5).toDouble()

    private fun hasContent(c: XNode) = c.child("v") != null || c.child("is") != null

    private fun cellOut(c: XNode?, rowStyle: Int?, ref: String, links: Map<String, String>): CellOut? {
        val styleIdx = c?.get("s")?.toIntOrNull() ?: rowStyle ?: 0
        val xf = xfs.getOrNull(styleIdx)
        if (c == null && (xf == null || styleIdx == 0)) return null
        val type = c?.get("t") ?: "n"
        val raw = c?.child("v")?.text
        var numeric = false
        var isText = false
        val text: String = when (type) {
            "s" -> { isText = true; raw?.trim()?.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty() }
            "inlineStr" -> { isText = true; c?.child("is")?.let { isn -> isn.child("t")?.text ?: isn.children("r").joinToString("") { it.child("t")?.text.orEmpty() } }.orEmpty() }
            "str" -> { isText = true; raw.orEmpty() }
            "b" -> if (raw?.trim() == "1") "TRUE" else if (raw != null) "FALSE" else ""
            "e" -> raw.orEmpty()
            "d" -> raw.orEmpty()
            else -> {
                val v = raw?.trim()?.toDoubleOrNull()
                if (v == null) raw.orEmpty() else {
                    numeric = true
                    val id = xf?.numFmtId ?: 0
                    runCatching { formatter.format(v, id, numFmts[id]) }.getOrElse { formatter.general(v) }
                }
            }
        }
        val css = StringBuilder()
        if (xf != null) {
            fonts.getOrNull(xf.fontId)?.let { f ->
                if (f.bold) css.append("font-weight:bold;")
                if (f.italic) css.append("font-style:italic;")
                if (f.underline || f.strike) css.append("text-decoration:${listOfNotNull(if (f.underline) "underline" else null, if (f.strike) "line-through" else null).joinToString(" ")};")
                f.color?.let { if (it != "#000000") css.append("color:$it;") }
                f.size?.let { if (it != 11.0) css.append("font-size:${fmt(it)}pt;") }
                f.name?.let { if (it != "Calibri") css.append("font-family:${Units.fontFamily(it)};") }
            }
            fills.getOrNull(xf.fillId)?.let { css.append("background:$it;") }
            borders.getOrNull(xf.borderId)?.css?.let { css.append(it) }
            when (xf.hAlign) {
                "center", "centerContinuous" -> css.append("text-align:center;")
                "right" -> css.append("text-align:right;")
                "left" -> css.append("text-align:left;")
                "justify", "distributed" -> css.append("text-align:justify;")
            }
            if (xf.hAlign == null && type == "b") css.append("text-align:center;")
            when (xf.vAlign) {
                "top" -> css.append("vertical-align:top;")
                "center" -> css.append("vertical-align:middle;")
            }
            if (xf.wrap) css.append("white-space:pre-wrap;")
            if (xf.indent > 0) css.append("padding-left:${xf.indent * 9 + 3}px;")
        }
        if (type == "e") css.append("text-align:center;")
        val link = links[ref]
        return CellOut(text, css.toString(), numeric, isText, xf?.wrap == true, link)
    }

    // ------------------------------------------------------------------ drawings

    private class Anchor(
        val fromCol: Int, val fromColOff: Double, val fromRow: Int, val fromRowOff: Double,
        val toCol: Int, val toColOff: Double, val toRow: Int, val toRowOff: Double,
        val widthPx: Double?, val heightPx: Double?, val html: String,
    )

    private fun parseDrawing(part: String): List<Anchor> {
        val root = pkg.xml(part) ?: return emptyList()
        val rels = pkg.relationships(part)
        val out = ArrayList<Anchor>()
        for (a in root.children) {
            if (a.name !in setOf("twoCellAnchor", "oneCellAnchor", "absoluteAnchor")) continue
            val from = a.child("from")
            val to = a.child("to")
            fun num(n: XNode?, k: String) = n?.child(k)?.text?.trim()?.toIntOrNull() ?: 0
            fun off(n: XNode?, k: String) = Units.emuToPx(n?.child(k)?.text?.trim()?.toDoubleOrNull() ?: 0.0)
            val ext = a.child("ext")
            val w = ext?.get("cx")?.toDoubleOrNull()?.let { Units.emuToPx(it) }
            val h = ext?.get("cy")?.toDoubleOrNull()?.let { Units.emuToPx(it) }
            val html = when {
                a.child("pic") != null -> {
                    val blip = a.child("pic")!!.firstDescendant("blip")
                    val rel = (blip?.get("r:embed"))?.let { rels[it] }
                    val bytes = rel?.takeIf { !it.external }?.let { pkg.bytes(it.target) }
                    val uri = if (bytes != null) Html.dataUri(rel.target, bytes) else null
                    if (uri != null) "<img src=\"$uri\" alt=\"\">" else Html.placeholder(strings.unsupportedImage)
                }
                a.child("graphicFrame") != null -> "<div class=\"chart\">${Html.esc(strings.chart)}</div>"
                a.child("sp") != null -> {
                    val txt = a.child("sp")!!.child("txBody")?.children("p")?.joinToString("<br>") { p -> Html.esc(p.allText("t")) }.orEmpty()
                    if (txt.isBlank()) continue
                    "<div class=\"shape\">$txt</div>"
                }
                else -> continue
            }
            if (a.name == "absoluteAnchor") continue
            out += Anchor(
                num(from, "col"), off(from, "colOff"), num(from, "row"), off(from, "rowOff"),
                num(to, "col"), off(to, "colOff"), num(to, "row"), off(to, "rowOff"),
                if (a.name == "oneCellAnchor") w else null, if (a.name == "oneCellAnchor") h else null, html,
            )
        }
        return out
    }

    // ------------------------------------------------------------------ refs

    private fun key(r: Int, c: Int): Long = r.toLong() * 100_000 + c

    private fun colIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') col = col * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') col = col * 26 + (ch - 'a' + 1)
            else break
        }
        return col - 1
    }

    private fun cellRef(ref: String): Pair<Int, Int>? {
        val clean = ref.replace("$", "")
        val col = colIndex(clean)
        val row = clean.dropWhile { it.isLetter() }.toIntOrNull() ?: return null
        if (col < 0) return null
        return (row - 1) to col
    }

    private fun colRef(r: Int, c: Int) = CsvConverter.columnName(c) + (r + 1)

    private fun css() = """
html,body{background:#fff;}body{padding-bottom:48px;}
.wrap{overflow:auto;}
.canvas{position:relative;display:inline-block;}
.grid{border-collapse:collapse;table-layout:fixed;font-size:11pt;font-family:Calibri,Carlito,Arial,sans-serif;background:#fff;}
.grid td{border:1px solid #e2e3e3;padding:0 3px;white-space:pre;overflow:hidden;vertical-align:bottom;position:relative;}
.grid.nogrid td{border-color:transparent;}
.grid th{background:#f1f3f4;color:#5f6368;font-weight:normal;font-size:12px;border:1px solid #c8c9cb;padding:0;overflow:hidden;}
.grid thead th{position:sticky;top:0;z-index:3;height:${HEADER_H.toInt() - 1}px;}
.grid tbody th{position:sticky;left:0;z-index:2;}
.grid th.corner{left:0;z-index:4;}
.grid td.n{text-align:right;}
.draw{position:absolute;z-index:1;overflow:hidden;}
.draw img{width:100%;height:100%;object-fit:fill;}
.chart,.shape{width:100%;height:100%;display:flex;align-items:center;justify-content:center;background:#f8f9fa;border:1px solid #9aa0a6;box-sizing:border-box;color:#5f6368;font-size:13px;text-align:center;}
.tabs{position:fixed;left:0;right:0;bottom:0;display:flex;overflow-x:auto;background:#f1f3f4;border-top:1px solid #c8c9cb;z-index:10;}
.tab{flex:none;border:none;background:transparent;padding:10px 16px;font-size:14px;color:#3c4043;border-bottom:3px solid transparent;}
.tab.on{background:#fff;color:#188038;border-bottom-color:#188038;font-weight:bold;}
a{color:#1155cc;}
"""

    private val SCRIPT = """
document.querySelectorAll('.tab').forEach(function(b){b.addEventListener('click',function(){
var i=b.getAttribute('data-i');
document.querySelectorAll('.sheet').forEach(function(s){s.hidden=s.id!=='s'+i;});
document.querySelectorAll('.tab').forEach(function(t){t.classList.toggle('on',t===b);});
window.scrollTo(0,0);});});
"""
}
