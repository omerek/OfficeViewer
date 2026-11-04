package com.omerek.officeviewer.core.docx

import com.omerek.officeviewer.core.DocumentException
import com.omerek.officeviewer.core.DocumentFormat
import com.omerek.officeviewer.core.Html
import com.omerek.officeviewer.core.RenderResult
import com.omerek.officeviewer.core.RenderStrings
import com.omerek.officeviewer.core.Units
import com.omerek.officeviewer.core.Units.fmt
import com.omerek.officeviewer.core.xml.OoxmlPackage
import com.omerek.officeviewer.core.xml.XNode

/**
 * Converts a WordprocessingML (.docx) document to HTML.
 *
 * Layout is "flowing": pages are split where Word last rendered a page break or where
 * an explicit page/section break exists, but text is not re-paginated.
 */
class DocxConverter(private val strings: RenderStrings = RenderStrings()) {

    private lateinit var pkg: OoxmlPackage
    private lateinit var docPart: String
    private lateinit var styles: DocxStyles
    private lateinit var numbering: DocxNumbering
    private var majorFont = "Calibri Light"
    private var minorFont = "Calibri"
    private val footnotes = LinkedHashMap<String, String>()
    private val usedFootnotes = ArrayList<Pair<String, String>>() // (kind:id, number)
    private var currentPart = ""

    private val out = StringBuilder()
    private var pageCount = 0
    private var pageOpen = false
    private var pageStyle = ""
    private var pageWidthPx = 816.0
    private var pageContentStart = 0

    fun convert(bytes: ByteArray, title: String): RenderResult {
        pkg = OoxmlPackage.open(bytes)
        docPart = pkg.mainPart() ?: "word/document.xml"
        val doc = pkg.xml(docPart) ?: throw DocumentException("word/document.xml not found")
        val rels = pkg.relationships(docPart).values
        styles = DocxStyles(rels.firstOrNull { it.type.endsWith("/styles") }?.let { pkg.xml(it.target) })
        numbering = DocxNumbering(rels.firstOrNull { it.type.endsWith("/numbering") }?.let { pkg.xml(it.target) })
        rels.firstOrNull { it.type.endsWith("/theme") }?.let { pkg.xml(it.target) }?.let { theme ->
            theme.firstDescendant("majorFont")?.child("latin")?.get("typeface")?.takeIf { it.isNotBlank() }?.let { majorFont = it }
            theme.firstDescendant("minorFont")?.child("latin")?.get("typeface")?.takeIf { it.isNotBlank() }?.let { minorFont = it }
        }
        loadNotes(rels.firstOrNull { it.type.endsWith("/footnotes") }?.target, "footnote", "fn")
        loadNotes(rels.firstOrNull { it.type.endsWith("/endnotes") }?.target, "endnote", "en")

        val body = doc.child("body") ?: throw DocumentException("Document body is missing")
        val sectPr = body.child("sectPr")
        setupPage(sectPr)

        currentPart = docPart
        val header = headerFooter(sectPr, "headerReference")
        val footer = headerFooter(sectPr, "footerReference")

        openPage(header)
        renderBlocks(body.children, topLevel = true, header = header, footer = footer)
        if (usedFootnotes.isNotEmpty()) {
            out.append("<div class=\"notes\"><hr>")
            for ((key, num) in usedFootnotes) {
                val html = footnotes[key] ?: continue
                out.append("<div class=\"note\" id=\"").append(Html.esc(key)).append("\"><sup>").append(num)
                    .append("</sup> ").append(html).append("</div>")
            }
            out.append("</div>")
        }
        closePage(footer)
        if (pageCount == 0) out.append("<div class=\"page\"><p>${Html.esc(strings.emptyDocument)}</p></div>")

        val viewport = (pageWidthPx + 24).toInt()
        val html = Html.page(title, css(), out.toString())
            .replace("content=\"width=device-width, initial-scale=1\"", "content=\"width=$viewport\"")
        return RenderResult(html, DocumentFormat.DOCX, pageCount)
    }

    // ---------------------------------------------------------------- page handling

    private fun setupPage(sectPr: XNode?) {
        val pgSz = sectPr?.child("pgSz")
        val pgMar = sectPr?.child("pgMar")
        val w = Units.twips(pgSz?.get("w")) ?: 11906.0
        val h = Units.twips(pgSz?.get("h")) ?: 16838.0
        pageWidthPx = Units.twipsToPx(w)
        val top = Units.twipsToPx(Units.twips(pgMar?.get("top")) ?: 1440.0)
        val bottom = Units.twipsToPx(Units.twips(pgMar?.get("bottom")) ?: 1440.0)
        val left = Units.twipsToPx(Units.twips(pgMar?.get("left")) ?: 1440.0)
        val right = Units.twipsToPx(Units.twips(pgMar?.get("right")) ?: 1440.0)
        pageStyle = "width:${fmt(pageWidthPx)}px;min-height:${fmt(Units.twipsToPx(h))}px;" +
            "padding:${fmt(maxOf(top, 0.0))}px ${fmt(right)}px ${fmt(maxOf(bottom, 0.0))}px ${fmt(left)}px;"
    }

    private fun headerFooter(sectPr: XNode?, refName: String): String? {
        val refs = sectPr?.children(refName) ?: return null
        val ref = refs.firstOrNull { it["type"] == "default" } ?: refs.firstOrNull() ?: return null
        val rid = ref["r:id"] ?: return null
        val target = pkg.relationships(docPart)[rid]?.target ?: return null
        val root = pkg.xml(target) ?: return null
        val saved = out.length
        val savedPart = currentPart
        currentPart = target
        renderBlocks(root.children, topLevel = false)
        currentPart = savedPart
        val html = out.substring(saved)
        out.setLength(saved)
        return html.takeIf { stripTags(it).isNotBlank() || it.contains("<img") }
    }

    private fun openPage(header: String?) {
        out.append("<div class=\"page\" style=\"").append(pageStyle).append("\">")
        if (header != null) out.append("<div class=\"hdr\">").append(header).append("</div>")
        pageOpen = true
        pageCount++
        pageContentStart = out.length
    }

    private fun closePage(footer: String?) {
        if (!pageOpen) return
        if (footer != null) out.append("<div class=\"ftr\">").append(footer).append("</div>")
        out.append("</div>")
        pageOpen = false
    }

    private fun pageBreak(header: String?, footer: String?) {
        closePage(footer)
        openPage(header)
    }

    // ---------------------------------------------------------------- blocks

    private fun renderBlocks(nodes: List<XNode>, topLevel: Boolean, header: String? = null, footer: String? = null) {
        for (n in nodes) {
            when (n.name) {
                "p" -> {
                    if (topLevel) {
                        val brk = breakPosition(n)
                        if (brk == Break.BEFORE && out.length > pageContentStart) pageBreak(header, footer)
                        renderParagraph(n)
                        // A paragraph carrying a (non-continuous) section break ends the page too.
                        val sect = n.path("pPr", "sectPr")
                        val sectBreak = sect != null && sect.path("type")?.get("val") != "continuous"
                        if (brk == Break.AFTER || sectBreak) pageBreak(header, footer)
                    } else {
                        renderParagraph(n)
                    }
                }
                "tbl" -> renderTable(n)
                "sdt" -> n.child("sdtContent")?.let { renderBlocks(it.children, topLevel, header, footer) }
                "customXml", "smartTag" -> renderBlocks(n.children, topLevel, header, footer)
                "AlternateContent" -> alternate(n)?.let { renderBlocks(it.children, topLevel, header, footer) }
                "ins" -> renderBlocks(n.children, topLevel, header, footer)
            }
        }
    }

    private enum class Break { NONE, BEFORE, AFTER }

    /** Decide whether a top-level paragraph starts or ends a page. */
    private fun breakPosition(p: XNode): Break {
        val props = ParaProps.parse(p.child("pPr"))
        val styleProps = styles.paraProps(p.path("pPr", "pStyle")?.get("val"))
        if ((props?.pageBreakBefore ?: styleProps.pageBreakBefore) == true) return Break.BEFORE
        var sawText = false
        var result = Break.NONE
        fun walk(n: XNode): Boolean {
            for (c in n.children) {
                when (c.name) {
                    "t", "drawing", "pict", "tab", "sym" -> sawText = true
                    "lastRenderedPageBreak" -> if (!sawText) { result = Break.BEFORE; return true }
                    "br" -> if (c["type"] == "page") {
                        result = if (sawText) Break.AFTER else Break.BEFORE
                        return true
                    }
                    "pPr", "rPr", "del", "txbxContent" -> continue
                }
                if (walk(c)) return true
            }
            return false
        }
        walk(p)
        return result
    }

    private fun alternate(n: XNode): XNode? = n.child("Choice") ?: n.child("Fallback")

    // ---------------------------------------------------------------- paragraphs

    private fun renderParagraph(p: XNode) {
        val pPrNode = p.child("pPr")
        val styleId = pPrNode?.child("pStyle")?.get("val")
        var props = styles.paraProps(styleId)
        val direct = ParaProps.parse(pPrNode)

        // Numbering may come from the style or from the paragraph itself.
        val numId = direct?.numId ?: props.numId
        val ilvl = direct?.ilvl ?: props.ilvl ?: 0
        var label: String? = null
        var labelRun: RunProps? = null
        if (numId != null) {
            val lvl = numbering.level(numId, ilvl)
            if (lvl != null) {
                props = props.merge(lvl.pPr)
                label = numbering.nextLabel(numId, ilvl)
                labelRun = lvl.rPr
            }
        }
        props = props.merge(direct)

        val baseRun = styles.runPropsForParagraphStyle(styleId)
        val paraMarkRun = baseRun.merge(RunProps.parse(pPrNode?.child("rPr")))

        val css = StringBuilder()
        when (props.align) {
            "center" -> css.append("text-align:center;")
            "right", "end" -> css.append("text-align:right;")
            "both", "distribute", "justify" -> css.append("text-align:justify;")
        }
        props.indLeft?.let { if (it != 0.0) css.append("margin-left:${fmt(Units.twipsToPx(it))}px;") }
        props.indRight?.let { if (it != 0.0) css.append("margin-right:${fmt(Units.twipsToPx(it))}px;") }
        when {
            props.hanging != null && props.hanging != 0.0 -> css.append("text-indent:-${fmt(Units.twipsToPx(props.hanging!!))}px;")
            props.firstLine != null && props.firstLine != 0.0 -> css.append("text-indent:${fmt(Units.twipsToPx(props.firstLine!!))}px;")
        }
        css.append("margin-top:${fmt((props.spaceBefore ?: 0.0) / 20)}pt;")
        css.append("margin-bottom:${fmt((props.spaceAfter ?: 0.0) / 20)}pt;")
        val line = props.line
        if (line != null) {
            when (props.lineRule) {
                "exact" -> css.append("line-height:${fmt(line / 20)}pt;")
                "atLeast" -> css.append("line-height:max(${fmt(line / 20)}pt,1.15);")
                else -> css.append("line-height:${fmt(maxOf(line / 240.0, 0.5) * 1.15)};")
            }
        }
        props.shading?.let { css.append("background:$it;") }
        if (props.borderBottom == true) css.append("border-bottom:1px solid #444;padding-bottom:2px;")
        if (props.borderTop == true) css.append("border-top:1px solid #444;padding-top:2px;")
        css.append(runCss(paraMarkRun, includeBackground = false))

        out.append("<p style=\"").append(css).append("\">")
        val start = out.length
        if (label != null) {
            val lr = paraMarkRun.merge(labelRun).copy(underline = false)
            out.append("<span class=\"lbl\" style=\"").append(runCss(lr)).append("\">")
                .append(Html.esc(label)).append("</span>")
        }
        val contentStart = out.length
        renderInline(p.children, baseRun)
        if (out.length == contentStart && label == null) out.append("<br>")
        else if (out.length == start) out.append("<br>")
        out.append("</p>")
    }

    /** State for complex fields (fldChar begin/separate/end). */
    private var fieldDepth = 0
    private var fieldInInstr = false

    private fun renderInline(nodes: List<XNode>, baseRun: RunProps) {
        for (n in nodes) {
            when (n.name) {
                "r" -> renderRun(n, baseRun)
                "hyperlink" -> {
                    val href = n["r:id"]?.let { rid -> pkg.relationships(currentPart)[rid]?.takeIf { it.external }?.target }
                        ?: n["anchor"]?.let { "#$it" }
                    val safe = href?.let { Html.safeHref(it) }
                    if (safe != null) out.append("<a href=\"").append(Html.esc(safe)).append("\">")
                    renderInline(n.children, baseRun)
                    if (safe != null) out.append("</a>")
                }
                "bookmarkStart" -> n["name"]?.let { if (!it.startsWith("_")) out.append("<a id=\"").append(Html.esc(it)).append("\"></a>") }
                "fldSimple", "smartTag", "customXml", "ins", "moveTo", "dir", "bdo" -> renderInline(n.children, baseRun)
                "sdt" -> n.child("sdtContent")?.let { renderInline(it.children, baseRun) }
                "AlternateContent" -> alternate(n)?.let { renderInline(it.children, baseRun) }
                "oMathPara", "oMath" -> out.append("<span class=\"math\">").append(Html.esc(n.allText("t"))).append("</span>")
            }
        }
    }

    private fun renderRun(r: XNode, baseRun: RunProps) {
        val rPr = r.child("rPr")
        val props = baseRun
            .merge(styles.runPropsForCharStyle(rPr?.child("rStyle")?.get("val")))
            .merge(RunProps.parse(rPr))
        if (props.hidden == true) return

        val buf = StringBuilder()
        for (c in r.children) {
            when (c.name) {
                "fldChar" -> when (c["fldCharType"]) {
                    "begin" -> { fieldDepth++; fieldInInstr = true }
                    "separate" -> fieldInInstr = false
                    "end" -> { fieldDepth = maxOf(0, fieldDepth - 1); fieldInInstr = false }
                }
                "instrText", "delText" -> {}
                "t" -> if (!fieldInInstr) buf.append(Html.esc(c.text).replace("  ", " &nbsp;"))
                "tab", "ptab" -> if (!fieldInInstr) buf.append("<span class=\"tab\"></span>")
                "br", "cr" -> if (!fieldInInstr && c["type"] != "page" && c["type"] != "column") buf.append("<br>")
                "noBreakHyphen" -> buf.append("&#8209;")
                "softHyphen" -> buf.append("&shy;")
                "sym" -> {
                    val code = c["char"]?.toIntOrNull(16)
                    if (code != null) buf.append(Html.esc(DocxNumbering.bullet(String(Character.toChars(code)))))
                }
                "drawing" -> if (!fieldInInstr) buf.append(drawing(c))
                "pict", "object" -> if (!fieldInInstr) buf.append(vml(c))
                "AlternateContent" -> alternate(c)?.let { alt ->
                    alt.child("drawing")?.let { buf.append(drawing(it)) } ?: alt.child("pict")?.let { buf.append(vml(it)) }
                }
                "footnoteReference", "endnoteReference" -> {
                    val prefix = if (c.name == "footnoteReference") "fn" else "en"
                    val key = "$prefix-${c["id"]}"
                    if (footnotes.containsKey(key)) {
                        val num = (usedFootnotes.size + 1).toString()
                        usedFootnotes += key to num
                        buf.append("<sup><a href=\"#").append(Html.esc(key)).append("\">").append(num).append("</a></sup>")
                    }
                }
            }
        }
        if (buf.isEmpty()) return
        val css = runCss(props)
        var html = buf.toString()
        if (props.caps == true) html = "<span style=\"text-transform:uppercase\">$html</span>"
        if (css.isEmpty()) out.append(html)
        else out.append("<span style=\"").append(css).append("\">").append(html).append("</span>")
    }

    private fun runCss(p: RunProps, includeBackground: Boolean = true): String {
        val sb = StringBuilder()
        if (p.bold == true) sb.append("font-weight:bold;") else if (p.bold == false) sb.append("font-weight:normal;")
        if (p.italic == true) sb.append("font-style:italic;") else if (p.italic == false) sb.append("font-style:normal;")
        val deco = buildList {
            if (p.underline == true) add("underline")
            if (p.strike == true) add("line-through")
        }
        if (deco.isNotEmpty()) sb.append("text-decoration:${deco.joinToString(" ")};")
        if (p.smallCaps == true) sb.append("font-variant:small-caps;")
        p.color?.let { if (it == "auto") sb.append("color:#000;") else sb.append("color:$it;") }
        p.sizeHalfPt?.let {
            val pt = if (p.vertAlign == "superscript" || p.vertAlign == "subscript") it / 2 * 0.7 else it / 2
            sb.append("font-size:${fmt(pt)}pt;")
        }
        when (p.vertAlign) {
            "superscript" -> sb.append("vertical-align:super;")
            "subscript" -> sb.append("vertical-align:sub;")
        }
        if (includeBackground) {
            (p.highlight ?: p.shading)?.let { sb.append("background:$it;") }
        }
        val font = p.font ?: when {
            p.themeFont?.startsWith("major") == true -> majorFont
            p.themeFont?.startsWith("minor") == true -> minorFont
            else -> null
        }
        if (font != null) sb.append("font-family:${Units.fontFamily(font)};")
        return sb.toString()
    }

    // ---------------------------------------------------------------- drawings

    private fun drawing(d: XNode): String {
        val container = d.child("inline") ?: d.child("anchor") ?: return ""
        val extent = container.child("extent")
        val w = Units.emuToPx(Units.parseDouble(extent?.get("cx")) ?: 0.0)
        val h = Units.emuToPx(Units.parseDouble(extent?.get("cy")) ?: 0.0)
        val graphicData = container.path("graphic", "graphicData") ?: return ""
        val uri = graphicData["uri"] ?: ""
        val isAnchor = container.name == "anchor"
        val floatCss = if (isAnchor) {
            when (container.path("positionH", "align")?.text?.trim()) {
                "right" -> "float:right;margin:0 0 4px 8px;"
                "center" -> "display:block;margin:4px auto;"
                else -> if (container.child("wrapTopAndBottom") != null) "display:block;margin:4px 0;" else "float:left;margin:0 8px 4px 0;"
            }
        } else ""

        if (uri.endsWith("/chart") || uri.contains("chart")) {
            return Html.placeholder(strings.chart, w.toInt(), h.toInt())
        }
        // Text boxes / shapes (wps:wsp)
        graphicData.firstDescendant("txbxContent")?.let { tb ->
            val saved = out.length
            renderBlocks(tb.children, topLevel = false)
            val inner = out.substring(saved)
            out.setLength(saved)
            return "<span class=\"txbx\" style=\"width:${fmt(w)}px;$floatCss\">$inner</span>"
        }
        val blip = graphicData.firstDescendant("blip") ?: return ""
        return image(blip["r:embed"] ?: blip["embed"], w, h, floatCss)
    }

    private fun vml(p: XNode): String {
        p.firstDescendant("txbxContent")?.let { tb ->
            val saved = out.length
            renderBlocks(tb.children, topLevel = false)
            val inner = out.substring(saved)
            out.setLength(saved)
            return "<span class=\"txbx\">$inner</span>"
        }
        val img = p.firstDescendant("imagedata") ?: return ""
        val style = p.firstDescendant("shape")?.get("style").orEmpty()
        fun dim(key: String): Double? {
            val m = Regex("$key:([0-9.]+)(pt|px|in)?").find(style) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            return when (m.groupValues[2]) {
                "pt" -> v * 96 / 72
                "in" -> v * 96
                else -> v
            }
        }
        return image(img["r:id"] ?: img["id"], dim("width") ?: 0.0, dim("height") ?: 0.0, "")
    }

    private fun image(rid: String?, w: Double, h: Double, extraCss: String): String {
        val rel = rid?.let { pkg.relationships(currentPart)[it] }
        val bytes = rel?.takeIf { !it.external }?.let { pkg.bytes(it.target) }
        val dims = buildString {
            if (w > 0) append("width:${fmt(w)}px;")
            if (h > 0) append("height:${fmt(h)}px;")
        }
        val uri = if (bytes != null) Html.dataUri(rel.target, bytes) else null
        return if (uri != null) {
            "<img src=\"$uri\" style=\"$dims$extraCss\" alt=\"\">"
        } else {
            Html.placeholder(strings.unsupportedImage, w.toInt().takeIf { it > 0 }, h.toInt().takeIf { it > 0 })
        }
    }

    // ---------------------------------------------------------------- tables

    private class Cell(val node: XNode, val col: Int, val span: Int, var rowspan: Int = 1, val skip: Boolean)

    private fun renderTable(tbl: XNode) {
        val tblPr = tbl.child("tblPr")
        val styleId = tblPr?.child("tblStyle")?.get("val")
        val direct = tblPr?.child("tblBorders")?.let { DocxStyles.hasBorders(it) }
        val bordered = direct ?: styles.tableHasBorders(styleId) ?: false
        val grid = tbl.child("tblGrid")?.children("gridCol")?.map { Units.twipsToPx(Units.twips(it["w"]) ?: 0.0) }.orEmpty()

        val rows = tbl.children.flatMap { c ->
            when (c.name) {
                "tr" -> listOf(c)
                "sdt" -> c.child("sdtContent")?.children("tr").orEmpty()
                else -> emptyList()
            }
        }
        // Build a grid model so vertical merges become rowspans.
        val model = ArrayList<List<Cell>>()
        val origin = HashMap<Int, Cell>()
        for (tr in rows) {
            var col = tr.path("trPr", "gridBefore")?.get("val")?.toIntOrNull() ?: 0
            val cells = ArrayList<Cell>()
            val tcs = tr.children.flatMap { c ->
                when (c.name) {
                    "tc" -> listOf(c)
                    "sdt" -> c.child("sdtContent")?.children("tc").orEmpty()
                    else -> emptyList()
                }
            }
            for (tc in tcs) {
                val tcPr = tc.child("tcPr")
                val span = tcPr?.child("gridSpan")?.get("val")?.toIntOrNull() ?: 1
                val vMerge = tcPr?.child("vMerge")
                val cont = vMerge != null && vMerge["val"] != "restart"
                val cell = Cell(tc, col, span, skip = cont && origin[col] != null)
                if (cell.skip) origin[col]!!.rowspan++ else origin[col] = cell
                cells += cell
                col += span
            }
            model += cells
        }

        val tableCss = StringBuilder()
        val tblW = tblPr?.child("tblW")
        if (tblW?.get("type") == "pct") {
            val v = tblW["w"]?.removeSuffix("%")?.toDoubleOrNull()
            if (v != null) tableCss.append("width:${fmt(if (tblW["w"]!!.endsWith("%")) v else v / 50)}%;")
        } else if (tblW?.get("type") == "dxa") {
            Units.twips(tblW["w"])?.let { if (it > 0) tableCss.append("width:${fmt(Units.twipsToPx(it))}px;") }
        }
        when (tblPr?.child("jc")?.get("val")) {
            "center" -> tableCss.append("margin-left:auto;margin-right:auto;")
            "right", "end" -> tableCss.append("margin-left:auto;")
        }
        out.append("<table class=\"").append(if (bordered) "tb b" else "tb").append("\" style=\"").append(tableCss).append("\">")
        if (grid.isNotEmpty()) {
            out.append("<colgroup>")
            for (g in grid) out.append("<col style=\"width:${fmt(g)}px\">")
            out.append("</colgroup>")
        }
        for ((ri, cells) in model.withIndex()) {
            val tr = rows[ri]
            val trH = tr.path("trPr", "trHeight")?.get("val")?.let { Units.twips(it) }
            out.append("<tr")
            if (trH != null && trH > 0) out.append(" style=\"height:${fmt(Units.twipsToPx(trH))}px\"")
            out.append(">")
            for (cell in cells) {
                if (cell.skip) continue
                val tcPr = cell.node.child("tcPr")
                val css = StringBuilder()
                tcPr?.child("shd")?.get("fill")?.let { Units.hexColor(it) }?.let { css.append("background:$it;") }
                when (tcPr?.child("vAlign")?.get("val")) {
                    "center" -> css.append("vertical-align:middle;")
                    "bottom" -> css.append("vertical-align:bottom;")
                }
                tcPr?.child("tcBorders")?.let { b ->
                    for (side in listOf("top", "left", "bottom", "right")) {
                        val e = b.child(side) ?: b.child(if (side == "left") "start" else if (side == "right") "end" else side) ?: continue
                        val v = e["val"]
                        if (v == "nil" || v == "none") css.append("border-$side:none;")
                        else {
                            val color = Units.hexColor(e["color"]) ?: "#000"
                            val sz = (Units.parseDouble(e["sz"]) ?: 4.0) / 8 * 96 / 72
                            css.append("border-$side:${fmt(maxOf(sz, 1.0))}px solid $color;")
                        }
                    }
                }
                out.append("<td")
                if (cell.span > 1) out.append(" colspan=\"").append(cell.span).append("\"")
                if (cell.rowspan > 1) out.append(" rowspan=\"").append(cell.rowspan).append("\"")
                if (css.isNotEmpty()) out.append(" style=\"").append(css).append("\"")
                out.append(">")
                renderBlocks(cell.node.children, topLevel = false)
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
    }

    // ---------------------------------------------------------------- notes

    private fun loadNotes(part: String?, element: String, prefix: String) {
        val root = part?.let { pkg.xml(it) } ?: return
        val savedPart = currentPart
        currentPart = part
        for (n in root.children(element)) {
            val type = n["type"]
            if (type == "separator" || type == "continuationSeparator" || type == "continuationNotice") continue
            val id = n["id"] ?: continue
            val saved = out.length
            renderBlocks(n.children, topLevel = false)
            val html = out.substring(saved)
            out.setLength(saved)
            footnotes["$prefix-$id"] = html.replace("<p ", "<span ").replace("</p>", "</span> ")
        }
        currentPart = savedPart
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ")

    private fun css() = """
body{padding:12px 0;}
.page{background:#fff;margin:0 auto 16px auto;box-shadow:0 1px 4px rgba(0,0,0,.25);box-sizing:border-box;position:relative;overflow-wrap:break-word;font-size:11pt;}
.page p{margin:0;}
.hdr{margin-bottom:12px;color:#444;}
.ftr{margin-top:24px;color:#444;}
.lbl{display:inline-block;min-width:24px;padding-right:6px;text-indent:0;}
.tab{display:inline-block;width:48px;}
.tb{border-collapse:collapse;margin:4px 0;max-width:100%;}
.tb td{padding:2px 6px;vertical-align:top;}
.tb.b td{border:1px solid #000;}
.txbx{display:inline-block;border:1px solid #bbb;padding:4px;vertical-align:top;box-sizing:border-box;max-width:100%;}
img{max-width:100%;height:auto;vertical-align:bottom;}
.notes{margin-top:24px;font-size:9pt;}
.notes hr{width:30%;margin-left:0;}
.math{font-family:'Cambria Math',serif;font-style:italic;}
a{color:#1155cc;}
"""
}
