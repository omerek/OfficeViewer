package com.omerek.officeviewer.core.pptx

import com.omerek.officeviewer.core.DocumentException
import com.omerek.officeviewer.core.DocumentFormat
import com.omerek.officeviewer.core.Html
import com.omerek.officeviewer.core.RenderResult
import com.omerek.officeviewer.core.RenderStrings
import com.omerek.officeviewer.core.Units
import com.omerek.officeviewer.core.Units.fmt
import com.omerek.officeviewer.core.docx.DocxNumbering
import com.omerek.officeviewer.core.xml.OoxmlColors
import com.omerek.officeviewer.core.xml.OoxmlPackage
import com.omerek.officeviewer.core.xml.XNode

/**
 * Converts a PresentationML (.pptx) deck to HTML: every slide becomes an absolutely
 * positioned canvas at its native size, scaled to the screen width by a small script.
 */
class PptxConverter(private val strings: RenderStrings = RenderStrings()) {

    private lateinit var pkg: OoxmlPackage
    private var slideW = 960.0
    private var slideH = 540.0

    /** Per-slide context: the part chain and theme. */
    private class Ctx(
        val part: String,
        val layoutPart: String?,
        val masterPart: String?,
        val layout: XNode?,
        val master: XNode?,
        val scheme: Map<String, String>,
        val majorFont: String,
        val minorFont: String,
    )

    /** Affine mapping for group shapes: child EMU -> slide px. */
    private class Xform(val ox: Double, val oy: Double, val sx: Double, val sy: Double) {
        fun x(v: Double) = ox + v * sx
        fun y(v: Double) = oy + v * sy
        fun w(v: Double) = v * sx
        fun h(v: Double) = v * sy
        companion object {
            val ROOT = Xform(0.0, 0.0, 1 / 9525.0, 1 / 9525.0)
        }
    }

    fun convert(bytes: ByteArray, title: String): RenderResult {
        pkg = OoxmlPackage.open(bytes)
        val presPart = pkg.mainPart() ?: "ppt/presentation.xml"
        val pres = pkg.xml(presPart) ?: throw DocumentException("ppt/presentation.xml not found")
        pres.child("sldSz")?.let { sz ->
            sz["cx"]?.toDoubleOrNull()?.let { slideW = Units.emuToPx(it) }
            sz["cy"]?.toDoubleOrNull()?.let { slideH = Units.emuToPx(it) }
        }
        val rels = pkg.relationships(presPart)
        val slides = pres.child("sldIdLst")?.children("sldId").orEmpty().mapNotNull { s ->
            s["r:id"]?.let { rels[it] }?.target
        }
        val body = StringBuilder()
        var n = 0
        for (slidePart in slides) {
            val slide = pkg.xml(slidePart) ?: continue
            n++
            body.append("<div class=\"lbl\">").append(Html.esc(strings.slide)).append(' ').append(n).append("</div>")
            body.append("<div class=\"sw\"><div class=\"slide\" style=\"width:${fmt(slideW)}px;height:${fmt(slideH)}px;")
            val ctx = context(slidePart)
            body.append(background(slide, ctx))
            body.append("\">")
            renderSlide(slide, ctx, body)
            body.append("</div></div>")
        }
        if (n == 0) body.append("<div class=\"ov-msg\">${Html.esc(strings.emptyDocument)}</div>")
        val html = Html.page(title, css(), body.toString(), script())
        return RenderResult(html, DocumentFormat.PPTX, n)
    }

    private fun context(slidePart: String): Ctx {
        val layoutPart = pkg.relationshipByType(slidePart, "/slideLayout")?.target
        val masterPart = layoutPart?.let { pkg.relationshipByType(it, "/slideMaster")?.target }
        val themePart = masterPart?.let { pkg.relationshipByType(it, "/theme")?.target }
        val theme = themePart?.let { pkg.xml(it) }
        return Ctx(
            part = slidePart,
            layoutPart = layoutPart,
            masterPart = masterPart,
            layout = layoutPart?.let { pkg.xml(it) },
            master = masterPart?.let { pkg.xml(it) },
            scheme = OoxmlColors.parseScheme(theme),
            majorFont = theme?.firstDescendant("majorFont")?.child("latin")?.get("typeface") ?: "Calibri Light",
            minorFont = theme?.firstDescendant("minorFont")?.child("latin")?.get("typeface") ?: "Calibri",
        )
    }

    // ------------------------------------------------------------------ background

    private fun background(slide: XNode, ctx: Ctx): String {
        for ((node, part) in listOf(slide to ctx.part, ctx.layout to ctx.layoutPart, ctx.master to ctx.masterPart)) {
            val bg = node?.path("cSld", "bg") ?: continue
            bg.child("bgPr")?.let { pr ->
                fillCss(pr, ctx, part ?: ctx.part)?.let { return it }
            }
            bg.child("bgRef")?.let { ref ->
                OoxmlColors.drawingColor(ref, ctx.scheme)?.let { return "background:$it;" }
            }
        }
        return "background:#fff;"
    }

    /** CSS for a fill (solidFill / gradFill / blipFill / noFill) found among the children of [n]. */
    private fun fillCss(n: XNode, ctx: Ctx, part: String): String? {
        n.child("noFill")?.let { return "background:transparent;" }
        n.child("solidFill")?.let { f -> OoxmlColors.drawingColor(f, ctx.scheme)?.let { return "background:$it;" } }
        n.child("gradFill")?.let { g ->
            val stops = g.child("gsLst")?.children("gs").orEmpty().mapNotNull { gs ->
                val pos = (gs["pos"]?.toDoubleOrNull() ?: 0.0) / 1000
                OoxmlColors.drawingColor(gs, ctx.scheme)?.let { "$it ${fmt(pos)}%" }
            }
            if (stops.size >= 2) {
                val ang = (g.child("lin")?.get("ang")?.toDoubleOrNull() ?: 0.0) / 60000 + 90
                return if (g.child("path") != null) "background:radial-gradient(${stops.joinToString(",")});"
                else "background:linear-gradient(${fmt(ang)}deg,${stops.joinToString(",")});"
            }
            if (stops.size == 1) return "background:${stops[0].substringBefore(' ')};"
        }
        n.child("blipFill")?.let { bf ->
            val uri = blipUri(bf.child("blip"), part) ?: return null
            return "background:url('$uri') center/cover no-repeat;"
        }
        return null
    }

    private fun blipUri(blip: XNode?, part: String): String? {
        val rid = blip?.get("r:embed") ?: return null
        val rel = pkg.relationships(part)[rid] ?: return null
        if (rel.external) return null
        val bytes = pkg.bytes(rel.target) ?: return null
        return Html.dataUri(rel.target, bytes)
    }

    // ------------------------------------------------------------------ slide tree

    private fun renderSlide(slide: XNode, ctx: Ctx, out: StringBuilder) {
        val showMaster = slide["showMasterSp"] != "0" && ctx.layout?.get("showMasterSp") != "0"
        if (showMaster && ctx.master != null && ctx.masterPart != null) {
            ctx.master.path("cSld", "spTree")?.let { renderTree(it, ctx, ctx.masterPart, Xform.ROOT, out, skipPlaceholders = true) }
        }
        if (ctx.layout != null && ctx.layoutPart != null) {
            ctx.layout.path("cSld", "spTree")?.let { renderTree(it, ctx, ctx.layoutPart, Xform.ROOT, out, skipPlaceholders = true) }
        }
        slide.path("cSld", "spTree")?.let { renderTree(it, ctx, ctx.part, Xform.ROOT, out, skipPlaceholders = false) }
    }

    private fun renderTree(tree: XNode, ctx: Ctx, part: String, xf: Xform, out: StringBuilder, skipPlaceholders: Boolean) {
        for (n in tree.children) {
            when (n.name) {
                "sp", "cxnSp" -> {
                    val ph = placeholder(n)
                    if (ph != null && skipPlaceholders) continue
                    renderShape(n, ctx, part, xf, out)
                }
                "pic" -> {
                    if (skipPlaceholders && placeholder(n) != null) continue
                    renderPicture(n, ctx, part, xf, out)
                }
                "grpSp" -> {
                    val x = n.path("grpSpPr", "xfrm")
                    if (x == null) { renderTree(n, ctx, part, xf, out, skipPlaceholders); continue }
                    val off = x.child("off"); val ext = x.child("ext")
                    val chOff = x.child("chOff"); val chExt = x.child("chExt")
                    fun d(node: XNode?, k: String) = node?.get(k)?.toDoubleOrNull() ?: 0.0
                    val sx = if (d(chExt, "cx") > 0) d(ext, "cx") / d(chExt, "cx") else 1.0
                    val sy = if (d(chExt, "cy") > 0) d(ext, "cy") / d(chExt, "cy") else 1.0
                    // child EMU c -> parent EMU: off + (c - chOff) * s ; then parent xf
                    val child = Xform(
                        ox = xf.x(d(off, "x") - d(chOff, "x") * sx),
                        oy = xf.y(d(off, "y") - d(chOff, "y") * sy),
                        sx = xf.sx * sx,
                        sy = xf.sy * sy,
                    )
                    renderTree(n, ctx, part, child, out, skipPlaceholders)
                }
                "graphicFrame" -> renderGraphicFrame(n, ctx, part, xf, out)
                "AlternateContent" -> (n.child("Choice") ?: n.child("Fallback"))?.let { renderTree(it, ctx, part, xf, out, skipPlaceholders) }
            }
        }
    }

    private class Ph(val type: String, val idx: String?)

    private fun placeholder(n: XNode): Ph? {
        val nv = n.children.firstOrNull { it.name.startsWith("nv") } ?: return null
        val ph = nv.child("nvPr")?.child("ph") ?: return null
        return Ph(ph["type"] ?: "body", ph["idx"])
    }

    /** Finds the layout and master shapes a placeholder inherits from. */
    private fun inherited(ph: Ph, ctx: Ctx): List<XNode> {
        val out = ArrayList<XNode>()
        fun find(root: XNode?, byIdx: Boolean): XNode? {
            val shapes = root?.path("cSld", "spTree")?.descendants("sp").orEmpty()
            if (byIdx && ph.idx != null) {
                shapes.firstOrNull { placeholder(it)?.idx == ph.idx }?.let { return it }
            }
            val type = ph.type
            shapes.firstOrNull { placeholder(it)?.type == type }?.let { return it }
            val alias = when (type) {
                "ctrTitle" -> "title"
                "subTitle", "obj" -> "body"
                else -> null
            }
            return alias?.let { a -> shapes.firstOrNull { placeholder(it)?.type == a } }
        }
        find(ctx.layout, true)?.let { out += it }
        find(ctx.master, false)?.let { out += it }
        return out
    }

    private class Box(val x: Double, val y: Double, val w: Double, val h: Double, val rot: Double, val flipH: Boolean, val flipV: Boolean)

    private fun box(xfrm: XNode?, xf: Xform): Box? {
        xfrm ?: return null
        val off = xfrm.child("off") ?: return null
        val ext = xfrm.child("ext") ?: return null
        val x = off["x"]?.toDoubleOrNull() ?: 0.0
        val y = off["y"]?.toDoubleOrNull() ?: 0.0
        val cx = ext["cx"]?.toDoubleOrNull() ?: 0.0
        val cy = ext["cy"]?.toDoubleOrNull() ?: 0.0
        return Box(
            xf.x(x), xf.y(y), xf.w(cx), xf.h(cy),
            (xfrm["rot"]?.toDoubleOrNull() ?: 0.0) / 60000.0,
            xfrm["flipH"] == "1", xfrm["flipV"] == "1",
        )
    }

    private fun boxCss(b: Box, extra: String = ""): String = buildString {
        append("left:${fmt(b.x)}px;top:${fmt(b.y)}px;width:${fmt(maxOf(b.w, 0.0))}px;height:${fmt(maxOf(b.h, 0.0))}px;")
        val t = buildList {
            if (b.rot != 0.0) add("rotate(${fmt(b.rot)}deg)")
            if (b.flipH) add("scaleX(-1)")
            if (b.flipV) add("scaleY(-1)")
        }
        if (t.isNotEmpty()) append("transform:${t.joinToString(" ")};")
        append(extra)
    }

    // ------------------------------------------------------------------ shapes

    private fun renderShape(sp: XNode, ctx: Ctx, part: String, xf: Xform, out: StringBuilder) {
        val ph = placeholder(sp)
        val parents = if (ph != null) inherited(ph, ctx) else emptyList()
        val spPr = sp.child("spPr")
        val b = box(spPr?.child("xfrm"), xf)
            ?: parents.firstNotNullOfOrNull { box(it.path("spPr", "xfrm"), Xform.ROOT) }
            ?: return
        val geom = spPr?.child("prstGeom")?.get("prst") ?: parents.firstNotNullOfOrNull { it.path("spPr", "prstGeom")?.get("prst") } ?: "rect"
        val style = sp.child("style")

        val css = StringBuilder()
        // Fill
        val fill = spPr?.let { fillCss(it, ctx, part) }
            ?: parents.firstNotNullOfOrNull { p -> p.child("spPr")?.let { fillCss(it, ctx, part) } }
            ?: style?.child("fillRef")?.takeIf { (it["idx"]?.toIntOrNull() ?: 0) > 0 }
                ?.let { OoxmlColors.drawingColor(it, ctx.scheme) }?.let { "background:$it;" }
        val isLine = sp.name == "cxnSp" || geom in setOf("line", "straightConnector1", "bentConnector2", "bentConnector3", "curvedConnector3")
        if (fill != null && !isLine) css.append(fill)
        // Outline
        val ln = spPr?.child("ln")
        val lnColor = when {
            ln?.child("noFill") != null -> null
            ln?.child("solidFill") != null -> OoxmlColors.drawingColor(ln.child("solidFill"), ctx.scheme)
            else -> style?.child("lnRef")?.takeIf { (it["idx"]?.toIntOrNull() ?: 0) > 0 }?.let { OoxmlColors.drawingColor(it, ctx.scheme) }
        }
        val lnW = maxOf(Units.emuToPx(ln?.get("w")?.toDoubleOrNull() ?: 9525.0), 1.0)
        val dash = ln?.child("prstDash")?.get("val")?.let { if (it.contains("dot", true)) "dotted" else if (it != "solid") "dashed" else "solid" } ?: "solid"

        if (isLine) {
            if (lnColor == null) return
            // Draw as a rotated line from corner to corner of the box.
            val len = Math.hypot(b.w, b.h)
            var angle = Math.toDegrees(Math.atan2(b.h, b.w))
            if (b.flipH) angle = 180 - angle
            if (b.flipV) angle = -angle
            val cx = b.x + b.w / 2
            val cy = b.y + b.h / 2
            out.append("<div class=\"sh\" style=\"left:${fmt(cx - len / 2)}px;top:${fmt(cy)}px;width:${fmt(len)}px;height:0;")
                .append("border-top:${fmt(lnW)}px $dash $lnColor;transform:rotate(${fmt(angle + b.rot)}deg);\"></div>")
            return
        }
        if (lnColor != null) css.append("border:${fmt(lnW)}px $dash $lnColor;")
        when (geom) {
            "ellipse", "flowChartConnector" -> css.append("border-radius:50%;")
            "roundRect", "flowChartAlternateProcess" -> css.append("border-radius:${fmt(minOf(b.w, b.h) * 0.16667)}px;")
            "triangle" -> css.append("clip-path:polygon(50% 0,100% 100%,0 100%);")
            "rtTriangle" -> css.append("clip-path:polygon(0 0,100% 100%,0 100%);")
            "diamond", "flowChartDecision" -> css.append("clip-path:polygon(50% 0,100% 50%,50% 100%,0 50%);")
            "rightArrow" -> css.append("clip-path:polygon(0 25%,70% 25%,70% 0,100% 50%,70% 100%,70% 75%,0 75%);")
            "leftArrow" -> css.append("clip-path:polygon(30% 0,30% 25%,100% 25%,100% 75%,30% 75%,30% 100%,0 50%);")
            "upArrow" -> css.append("clip-path:polygon(50% 0,100% 30%,75% 30%,75% 100%,25% 100%,25% 30%,0 30%);")
            "downArrow" -> css.append("clip-path:polygon(25% 0,75% 0,75% 70%,100% 70%,50% 100%,0 70%,25% 70%);")
            "pentagon", "homePlate" -> css.append("clip-path:polygon(0 0,80% 0,100% 50%,80% 100%,0 100%);")
            "chevron" -> css.append("clip-path:polygon(0 0,80% 0,100% 50%,80% 100%,0 100%,20% 50%);")
            "hexagon" -> css.append("clip-path:polygon(25% 0,75% 0,100% 50%,75% 100%,25% 100%,0 50%);")
            "parallelogram" -> css.append("clip-path:polygon(25% 0,100% 0,75% 100%,0 100%);")
            "trapezoid" -> css.append("clip-path:polygon(25% 0,75% 0,100% 100%,0 100%);")
            "star5" -> css.append("clip-path:polygon(50% 0,61% 35%,98% 35%,68% 57%,79% 91%,50% 70%,21% 91%,32% 57%,2% 35%,39% 35%);")
        }

        val txBody = sp.child("txBody")
        out.append("<div class=\"sh\" style=\"").append(boxCss(b, css.toString())).append("\">")
        if (txBody != null) renderText(txBody, ph, parents, ctx, style, out, b)
        out.append("</div>")
    }

    private fun renderPicture(pic: XNode, ctx: Ctx, part: String, xf: Xform, out: StringBuilder) {
        val ph = placeholder(pic)
        val b = box(pic.path("spPr", "xfrm"), xf)
            ?: ph?.let { p -> inherited(p, ctx).firstNotNullOfOrNull { box(it.path("spPr", "xfrm"), Xform.ROOT) } }
            ?: return
        val media = pic.firstDescendant("videoFile") ?: pic.firstDescendant("audioFile")
        val uri = blipUri(pic.child("blipFill")?.child("blip"), part)
        val geom = pic.path("spPr", "prstGeom")?.get("prst")
        val radius = if (geom == "ellipse") "border-radius:50%;" else if (geom == "roundRect") "border-radius:${fmt(minOf(b.w, b.h) * 0.16667)}px;" else ""
        out.append("<div class=\"sh\" style=\"").append(boxCss(b, "overflow:hidden;$radius")).append("\">")
        if (uri != null) {
            // Honour cropping (srcRect, in 1/1000 %).
            val src = pic.path("blipFill", "srcRect")
            fun p(k: String) = (src?.get(k)?.toDoubleOrNull() ?: 0.0) / 1000.0
            val l = p("l"); val t = p("t"); val r = p("r"); val bt = p("b")
            if (l != 0.0 || t != 0.0 || r != 0.0 || bt != 0.0) {
                val wPct = 100.0 / maxOf(1.0, 100 - l - r) * 100
                val hPct = 100.0 / maxOf(1.0, 100 - t - bt) * 100
                out.append("<img alt=\"\" src=\"$uri\" style=\"position:absolute;width:${fmt(wPct)}%;height:${fmt(hPct)}%;")
                    .append("left:-${fmt(l * wPct / 100)}%;top:-${fmt(t * hPct / 100)}%;\">")
            } else {
                out.append("<img alt=\"\" src=\"$uri\" style=\"width:100%;height:100%;\">")
            }
        } else {
            out.append(Html.placeholder(if (media != null) "▶" else strings.unsupportedImage, b.w.toInt(), b.h.toInt()))
        }
        out.append("</div>")
    }

    private fun renderGraphicFrame(gf: XNode, ctx: Ctx, part: String, xf: Xform, out: StringBuilder) {
        val b = box(gf.child("xfrm"), xf) ?: return
        val data = gf.path("graphic", "graphicData") ?: return
        val uri = data["uri"].orEmpty()
        when {
            data.child("tbl") != null -> {
                out.append("<div class=\"sh\" style=\"").append(boxCss(b, "height:auto;")).append("\">")
                renderTable(data.child("tbl")!!, ctx, out, xf)
                out.append("</div>")
            }
            uri.endsWith("/diagram") -> {
                // SmartArt: PowerPoint stores a pre-rendered drawing we can reuse.
                val drawingPart = pkg.relationships(part).values.firstOrNull { it.type.endsWith("/diagramDrawing") }?.target
                val drawing = drawingPart?.let { pkg.xml(it) }
                val tree = drawing?.firstDescendant("spTree")
                if (tree != null && drawingPart != null) {
                    val inner = Xform(b.x, b.y, xf.sx, xf.sy)
                    renderTree(tree, ctx, drawingPart, inner, out, skipPlaceholders = false)
                } else {
                    out.append("<div class=\"sh ph\" style=\"").append(boxCss(b)).append("\">").append(Html.esc(strings.chart)).append("</div>")
                }
            }
            uri.endsWith("/chart") || uri.contains("chart") -> {
                out.append("<div class=\"sh ph\" style=\"").append(boxCss(b)).append("\">").append(Html.esc(strings.chart)).append("</div>")
            }
            else -> {
                // OLE objects usually carry a fallback picture.
                val pic = gf.firstDescendant("pic")
                if (pic != null) {
                    val img = blipUri(pic.child("blipFill")?.child("blip"), part)
                    if (img != null) {
                        out.append("<div class=\"sh\" style=\"").append(boxCss(b)).append("\"><img alt=\"\" src=\"$img\" style=\"width:100%;height:100%\"></div>")
                        return
                    }
                }
                out.append("<div class=\"sh ph\" style=\"").append(boxCss(b)).append("\"></div>")
            }
        }
    }

    private fun renderTable(tbl: XNode, ctx: Ctx, out: StringBuilder, xf: Xform) {
        val cols = tbl.child("tblGrid")?.children("gridCol").orEmpty().map { xf.w(it["w"]?.toDoubleOrNull() ?: 0.0) }
        val firstRow = tbl.child("tblPr")?.get("firstRow") == "1"
        val bandRow = tbl.child("tblPr")?.get("bandRow") == "1"
        val accent = OoxmlColors.css(ctx.scheme["accent1"] ?: "4472C4")
        out.append("<table class=\"pt\"><colgroup>")
        for (c in cols) out.append("<col style=\"width:${fmt(c)}px\">")
        out.append("</colgroup>")
        for ((ri, tr) in tbl.children("tr").withIndex()) {
            val h = xf.h(tr["h"]?.toDoubleOrNull() ?: 0.0)
            out.append("<tr style=\"height:${fmt(h)}px\">")
            for (tc in tr.children("tc")) {
                if (tc["hMerge"] == "1" || tc["vMerge"] == "1") continue
                val tcPr = tc.child("tcPr")
                val css = StringBuilder()
                val fill = tcPr?.let { fillCss(it, ctx, "") }
                when {
                    fill != null -> css.append(fill)
                    firstRow && ri == 0 -> css.append("background:$accent;color:#fff;font-weight:bold;")
                    bandRow && ri % 2 == 1 -> css.append("background:#cfd5ea;")
                    bandRow -> css.append("background:#e9ebf5;")
                }
                for ((side, key) in listOf("left" to "lnL", "right" to "lnR", "top" to "lnT", "bottom" to "lnB")) {
                    val l = tcPr?.child(key) ?: continue
                    if (l.child("noFill") != null) continue
                    val col = OoxmlColors.drawingColor(l.child("solidFill"), ctx.scheme) ?: continue
                    css.append("border-$side:${fmt(maxOf(Units.emuToPx(l["w"]?.toDoubleOrNull() ?: 12700.0), 1.0))}px solid $col;")
                }
                when (tcPr?.get("anchor")) { "ctr" -> css.append("vertical-align:middle;"); "b" -> css.append("vertical-align:bottom;") }
                out.append("<td")
                tc["gridSpan"]?.let { out.append(" colspan=\"").append(Html.esc(it)).append("\"") }
                tc["rowSpan"]?.let { out.append(" rowspan=\"").append(Html.esc(it)).append("\"") }
                out.append(" style=\"").append(css).append("\">")
                tc.child("txBody")?.let { renderParagraphs(it, emptyList(), null, ctx, out, 1.0, defaultSize = 18.0) }
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
    }

    // ------------------------------------------------------------------ text

    private fun renderText(txBody: XNode, ph: Ph?, parents: List<XNode>, ctx: Ctx, style: XNode?, out: StringBuilder, b: Box) {
        val bodyPrs = listOfNotNull(txBody.child("bodyPr")) + parents.mapNotNull { it.path("txBody", "bodyPr") }
        fun bp(k: String) = bodyPrs.firstNotNullOfOrNull { it[k] }
        val anchor = bp("anchor") ?: "t"
        fun inset(k: String, d: Double) = Units.emuToPx(bp(k)?.toDoubleOrNull() ?: d)
        val autofit = bodyPrs.firstNotNullOfOrNull { it.child("normAutofit") }
        val scale = (autofit?.get("fontScale")?.toDoubleOrNull() ?: 100000.0) / 100000.0
        val justify = when (anchor) { "ctr" -> "center"; "b" -> "flex-end"; else -> "flex-start" }
        val wrap = bp("wrap") != "none"
        val vert = bp("vert")
        out.append("<div class=\"tx\" style=\"justify-content:$justify;padding:${fmt(inset("tIns", 45720.0))}px ${fmt(inset("rIns", 91440.0))}px ${fmt(inset("bIns", 45720.0))}px ${fmt(inset("lIns", 91440.0))}px;")
        if (!wrap) out.append("white-space:nowrap;")
        if (vert == "vert" || vert == "eaVert") out.append("writing-mode:vertical-rl;")
        if (vert == "vert270") out.append("writing-mode:vertical-rl;transform:rotate(180deg);")
        // Text in shapes with a theme style uses the style's font colour.
        val fontRefColor = style?.child("fontRef")?.let { fr -> OoxmlColors.drawingColor(fr, ctx.scheme) }
        fontRefColor?.let { out.append("color:$it;") }
        out.append("\">")
        val defaultSize = when (ph?.type) {
            "title", "ctrTitle" -> 44.0
            "subTitle" -> 24.0
            "body", "obj" -> 28.0
            null -> 18.0
            else -> 12.0
        }
        renderParagraphs(txBody, parents, ph, ctx, out, scale, defaultSize, fontRefColor)
        out.append("</div>")
    }

    /** The list-style chain for a paragraph level, most specific first. */
    private fun levelProps(level: Int, txBody: XNode, parents: List<XNode>, ph: Ph?, ctx: Ctx): List<XNode> {
        val key = "lvl${level + 1}pPr"
        val out = ArrayList<XNode>()
        txBody.child("lstStyle")?.child(key)?.let { out += it }
        for (p in parents) p.path("txBody", "lstStyle", key)?.let { out += it }
        val tx = ctx.master?.child("txStyles")
        val masterStyle = when (ph?.type) {
            null -> null
            "title", "ctrTitle" -> tx?.child("titleStyle")
            "body", "subTitle", "obj" -> tx?.child("bodyStyle")
            else -> tx?.child("otherStyle")
        }
        masterStyle?.child(key)?.let { out += it }
        if (ph == null) {
            // Free text boxes use the presentation default text style.
            val pres = pkg.mainPart()?.let { pkg.xml(it) }
            pres?.child("defaultTextStyle")?.child(key)?.let { out += it }
        }
        return out
    }

    private fun renderParagraphs(
        txBody: XNode, parents: List<XNode>, ph: Ph?, ctx: Ctx, out: StringBuilder, scale: Double, defaultSize: Double,
        fontRefColor: String? = null,
    ) {
        val counters = IntArray(9)
        for (p in txBody.children("p")) {
            val pPr = p.child("pPr")
            val lvl = pPr?.get("lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
            val chain = listOfNotNull(pPr) + levelProps(lvl, txBody, parents, ph, ctx)
            fun attr(k: String) = chain.firstNotNullOfOrNull { it[k] }
            fun child(k: String) = chain.firstNotNullOfOrNull { it.child(k) }
            val defRPr = chain.mapNotNull { it.child("defRPr") }

            val css = StringBuilder()
            when (attr("algn")) {
                "ctr" -> css.append("text-align:center;")
                "r" -> css.append("text-align:right;")
                "just", "dist" -> css.append("text-align:justify;")
            }
            val marL = Units.emuToPx(attr("marL")?.toDoubleOrNull() ?: 0.0)
            val indent = Units.emuToPx(attr("indent")?.toDoubleOrNull() ?: 0.0)
            if (marL != 0.0) css.append("padding-left:${fmt(marL)}px;")
            if (indent != 0.0) css.append("text-indent:${fmt(indent)}px;")
            child("lnSpc")?.let { ls ->
                ls.child("spcPct")?.get("val")?.toDoubleOrNull()?.let { css.append("line-height:${fmt(it / 100000.0 * 1.2)};") }
                ls.child("spcPts")?.get("val")?.toDoubleOrNull()?.let { css.append("line-height:${fmt(it / 100.0 * 96 / 72)}px;") }
            }
            child("spcBef")?.child("spcPts")?.get("val")?.toDoubleOrNull()?.let { css.append("margin-top:${fmt(it / 100.0 * 96 / 72 * scale)}px;") }
            child("spcAft")?.child("spcPts")?.get("val")?.toDoubleOrNull()?.let { css.append("margin-bottom:${fmt(it / 100.0 * 96 / 72 * scale)}px;") }

            val firstRunSize = (p.children("r").firstOrNull()?.child("rPr")?.get("sz")?.toDoubleOrNull()
                ?: defRPr.firstNotNullOfOrNull { it["sz"]?.toDoubleOrNull() })?.div(100.0) ?: defaultSize
            css.append("font-size:${fmt(firstRunSize * scale * 96 / 72)}px;")

            out.append("<p style=\"").append(css).append("\">")
            val hasText = p.children.any { (it.name == "r" || it.name == "fld") && it.allText("t").isNotEmpty() }
            // Bullets
            val bu = chain.firstOrNull { it.child("buChar") != null || it.child("buAutoNum") != null || it.child("buNone") != null }
            if (hasText && bu != null && bu.child("buNone") == null) {
                val label = if (bu.child("buChar") != null) {
                    DocxNumbering.bullet(bu.child("buChar")!!["char"] ?: "\u2022")
                } else {
                    val an = bu.child("buAutoNum")!!
                    val start = an["startAt"]?.toIntOrNull() ?: 1
                    counters[lvl]++
                    for (i in lvl + 1 until 9) counters[i] = 0
                    autoNum(an["type"] ?: "arabicPeriod", start + counters[lvl] - 1)
                }
                val buColor = child("buClr")?.let { OoxmlColors.drawingColor(it, ctx.scheme) }
                out.append("<span class=\"bu\"").append(if (buColor != null) " style=\"color:$buColor\"" else "").append(">")
                    .append(Html.esc(label)).append("</span>")
            }
            var any = false
            for (r in p.children) {
                when (r.name) {
                    "r", "fld" -> {
                        val text = r.child("t")?.text ?: ""
                        if (text.isEmpty()) continue
                        any = true
                        val rPrs = listOfNotNull(r.child("rPr")) + defRPr
                        out.append("<span style=\"").append(runCss(rPrs, ctx, scale, defaultSize, fontRefColor)).append("\">")
                        val link = r.child("rPr")?.child("hlinkClick")?.get("r:id")?.let { pkg.relationships(ctx.part)[it] }?.takeIf { it.external }?.target?.let { Html.safeHref(it) }
                        if (link != null) out.append("<a href=\"").append(Html.esc(link)).append("\">")
                        out.append(Html.esc(text))
                        if (link != null) out.append("</a>")
                        out.append("</span>")
                    }
                    "br" -> { out.append("<br>"); any = true }
                }
            }
            if (!any) out.append("<br>")
            out.append("</p>")
        }
    }

    private fun runCss(rPrs: List<XNode>, ctx: Ctx, scale: Double, defaultSize: Double, fontRefColor: String? = null): String {
        fun attr(k: String) = rPrs.firstNotNullOfOrNull { it[k] }
        val sb = StringBuilder()
        val size = (attr("sz")?.toDoubleOrNull()?.div(100.0) ?: defaultSize) * scale
        val baseline = attr("baseline")?.toDoubleOrNull() ?: 0.0
        sb.append("font-size:${fmt(size * (if (baseline != 0.0) 0.7 else 1.0) * 96 / 72)}px;")
        if (baseline > 0) sb.append("vertical-align:super;") else if (baseline < 0) sb.append("vertical-align:sub;")
        if (attr("b") == "1" || attr("b") == "true") sb.append("font-weight:bold;")
        if (attr("i") == "1" || attr("i") == "true") sb.append("font-style:italic;")
        val deco = buildList {
            attr("u")?.let { if (it != "none") add("underline") }
            attr("strike")?.let { if (it != "noStrike") add("line-through") }
        }
        if (deco.isNotEmpty()) sb.append("text-decoration:${deco.joinToString(" ")};")
        if (attr("cap") == "all") sb.append("text-transform:uppercase;")
        if (attr("cap") == "small") sb.append("font-variant:small-caps;")
        // An explicit run colour wins; otherwise a shape style colour beats inherited list styles.
        val explicit = rPrs.firstOrNull()?.child("solidFill")?.let { OoxmlColors.drawingColor(it, ctx.scheme) }
        val inherited = rPrs.firstOrNull { it.child("solidFill") != null }?.child("solidFill")?.let { OoxmlColors.drawingColor(it, ctx.scheme) }
        (explicit ?: fontRefColor ?: inherited)?.let { sb.append("color:$it;") }
        rPrs.firstOrNull { it.child("highlight") != null }?.child("highlight")?.let { OoxmlColors.drawingColor(it, ctx.scheme) }?.let { sb.append("background:$it;") }
        val typeface = rPrs.firstNotNullOfOrNull { it.child("latin")?.get("typeface") }
        val font = when {
            typeface == null -> null
            typeface.startsWith("+mj") -> ctx.majorFont
            typeface.startsWith("+mn") -> ctx.minorFont
            else -> typeface
        }
        if (font != null) sb.append("font-family:${Units.fontFamily(font)};")
        return sb.toString()
    }

    private fun autoNum(type: String, n: Int): String {
        val core = when {
            type.startsWith("arabic") -> n.toString()
            type.startsWith("alphaLc") -> Units.letters(n)
            type.startsWith("alphaUc") -> Units.letters(n).uppercase()
            type.startsWith("romanLc") -> Units.roman(n).lowercase()
            type.startsWith("romanUc") -> Units.roman(n)
            else -> n.toString()
        }
        return when {
            type.endsWith("ParenBoth") -> "($core)"
            type.endsWith("ParenR") -> "$core)"
            type.endsWith("Plain") -> core
            else -> "$core."
        }
    }

    private fun css() = """
body{padding:8px 0 24px 0;}
.lbl{color:#5f6368;font-size:12px;margin:12px auto 4px auto;text-align:center;}
.sw{margin:0 auto;overflow:hidden;}
.slide{position:relative;overflow:hidden;transform-origin:0 0;box-shadow:0 1px 4px rgba(0,0,0,.3);font-family:Calibri,Carlito,Arial,sans-serif;color:#000;}
.sh{position:absolute;box-sizing:border-box;}
.sh.ph{display:flex;align-items:center;justify-content:center;background:#f1f3f4;border:1px dashed #9aa0a6;color:#5f6368;font-size:18px;}
.tx{position:absolute;inset:0;display:flex;flex-direction:column;box-sizing:border-box;overflow:visible;overflow-wrap:break-word;line-height:1.2;}
.tx p{margin:0;}
.bu{display:inline-block;min-width:0.9em;padding-right:0.35em;text-indent:0;}
.pt{border-collapse:collapse;width:100%;table-layout:fixed;font-size:18px;}
.pt td{padding:4px 8px;vertical-align:top;overflow-wrap:break-word;}
.pt p{margin:0;}
a{color:inherit;}
"""

    private fun script() = """
(function(){var W=${fmt(slideW)},H=${fmt(slideH)};
function fit(){var avail=Math.max(100,document.documentElement.clientWidth-16);var s=Math.min(avail/W,2);
document.querySelectorAll('.sw').forEach(function(w){w.style.width=(W*s)+'px';w.style.height=(H*s)+'px';
w.firstChild.style.transform='scale('+s+')';});}
fit();window.addEventListener('resize',fit);})();
"""
}
