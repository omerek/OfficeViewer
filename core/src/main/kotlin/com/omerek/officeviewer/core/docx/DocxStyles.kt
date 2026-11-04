package com.omerek.officeviewer.core.docx

import com.omerek.officeviewer.core.Units
import com.omerek.officeviewer.core.xml.XNode

/** Character formatting. Null means "inherit". */
internal data class RunProps(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val caps: Boolean? = null,
    val smallCaps: Boolean? = null,
    val hidden: Boolean? = null,
    val color: String? = null,
    val sizeHalfPt: Double? = null,
    val highlight: String? = null,
    val shading: String? = null,
    val vertAlign: String? = null,
    val font: String? = null,
    val themeFont: String? = null,
) {
    fun merge(o: RunProps?): RunProps {
        if (o == null) return this
        return RunProps(
            bold = o.bold ?: bold,
            italic = o.italic ?: italic,
            underline = o.underline ?: underline,
            strike = o.strike ?: strike,
            caps = o.caps ?: caps,
            smallCaps = o.smallCaps ?: smallCaps,
            hidden = o.hidden ?: hidden,
            color = o.color ?: color,
            sizeHalfPt = o.sizeHalfPt ?: sizeHalfPt,
            highlight = o.highlight ?: highlight,
            shading = o.shading ?: shading,
            vertAlign = o.vertAlign ?: vertAlign,
            font = if (o.font != null || o.themeFont != null) o.font else font,
            themeFont = if (o.font != null || o.themeFont != null) o.themeFont else themeFont,
        )
    }

    companion object {
        fun toggle(n: XNode?): Boolean? {
            if (n == null) return null
            val v = n["val"] ?: return true
            return !(v == "0" || v.equals("false", true) || v.equals("off", true) || v.equals("none", true))
        }

        fun parse(rPr: XNode?): RunProps? {
            if (rPr == null) return null
            val u = rPr.child("u")
            val fonts = rPr.child("rFonts")
            return RunProps(
                bold = toggle(rPr.child("b")),
                italic = toggle(rPr.child("i")),
                underline = u?.let { val v = it["val"]; v != null && v != "none" && v != "0" },
                strike = toggle(rPr.child("strike")) ?: toggle(rPr.child("dstrike")),
                caps = toggle(rPr.child("caps")),
                smallCaps = toggle(rPr.child("smallCaps")),
                hidden = toggle(rPr.child("vanish")),
                color = rPr.child("color")?.let { Units.hexColor(it["val"]) ?: if (it["val"] == "auto") "auto" else null },
                sizeHalfPt = Units.parseDouble(rPr.child("sz")?.get("val")),
                highlight = rPr.child("highlight")?.get("val")?.let { HIGHLIGHT[it] ?: Units.hexColor(it) },
                shading = rPr.child("shd")?.get("fill")?.let { Units.hexColor(it) },
                vertAlign = rPr.child("vertAlign")?.get("val"),
                font = fonts?.let { it["ascii"] ?: it["hAnsi"] ?: it["cs"] ?: it["eastAsia"] },
                themeFont = fonts?.let { if (it["ascii"] == null && it["hAnsi"] == null) it["asciiTheme"] ?: it["hAnsiTheme"] else null },
            )
        }

        val HIGHLIGHT = mapOf(
            "yellow" to "#ffff00", "green" to "#00ff00", "cyan" to "#00ffff", "magenta" to "#ff00ff",
            "blue" to "#0000ff", "red" to "#ff0000", "darkBlue" to "#000080", "darkCyan" to "#008080",
            "darkGreen" to "#008000", "darkMagenta" to "#800080", "darkRed" to "#800000",
            "darkYellow" to "#808000", "darkGray" to "#808080", "lightGray" to "#c0c0c0",
            "black" to "#000000", "white" to "#ffffff", "none" to "transparent",
        )
    }
}

/** Paragraph formatting. Null means "inherit". Measurements in twips. */
internal data class ParaProps(
    val align: String? = null,
    val indLeft: Double? = null,
    val indRight: Double? = null,
    val firstLine: Double? = null,
    val hanging: Double? = null,
    val spaceBefore: Double? = null,
    val spaceAfter: Double? = null,
    val line: Double? = null,
    val lineRule: String? = null,
    val numId: String? = null,
    val ilvl: Int? = null,
    val shading: String? = null,
    val pageBreakBefore: Boolean? = null,
    val borderBottom: Boolean? = null,
    val borderTop: Boolean? = null,
    val outlineLvl: Int? = null,
) {
    fun merge(o: ParaProps?): ParaProps {
        if (o == null) return this
        return ParaProps(
            align = o.align ?: align,
            indLeft = o.indLeft ?: indLeft,
            indRight = o.indRight ?: indRight,
            firstLine = if (o.hanging != null) null else o.firstLine ?: firstLine,
            hanging = if (o.firstLine != null && o.hanging == null) null else o.hanging ?: hanging,
            spaceBefore = o.spaceBefore ?: spaceBefore,
            spaceAfter = o.spaceAfter ?: spaceAfter,
            line = o.line ?: line,
            lineRule = if (o.line != null) o.lineRule else lineRule,
            numId = o.numId ?: numId,
            ilvl = o.ilvl ?: ilvl,
            shading = o.shading ?: shading,
            pageBreakBefore = o.pageBreakBefore ?: pageBreakBefore,
            borderBottom = o.borderBottom ?: borderBottom,
            borderTop = o.borderTop ?: borderTop,
            outlineLvl = o.outlineLvl ?: outlineLvl,
        )
    }

    companion object {
        fun parse(pPr: XNode?): ParaProps? {
            if (pPr == null) return null
            val ind = pPr.child("ind")
            val sp = pPr.child("spacing")
            val num = pPr.child("numPr")
            val bdr = pPr.child("pBdr")
            fun border(n: XNode?) = n?.let { val v = it["val"]; v != null && v != "none" && v != "nil" }
            return ParaProps(
                align = pPr.child("jc")?.get("val"),
                indLeft = Units.twips(ind?.get("left") ?: ind?.get("start")),
                indRight = Units.twips(ind?.get("right") ?: ind?.get("end")),
                firstLine = Units.twips(ind?.get("firstLine")),
                hanging = Units.twips(ind?.get("hanging")),
                spaceBefore = if (sp?.get("beforeAutospacing") == "1") 14.0 * 20 else Units.twips(sp?.get("before")),
                spaceAfter = if (sp?.get("afterAutospacing") == "1") 14.0 * 20 else Units.twips(sp?.get("after")),
                line = Units.parseDouble(sp?.get("line")),
                lineRule = sp?.get("lineRule"),
                numId = num?.child("numId")?.get("val"),
                ilvl = num?.child("ilvl")?.get("val")?.toIntOrNull(),
                shading = pPr.child("shd")?.get("fill")?.let { Units.hexColor(it) },
                pageBreakBefore = RunProps.toggle(pPr.child("pageBreakBefore")),
                borderBottom = border(bdr?.child("bottom")),
                borderTop = border(bdr?.child("top")),
                outlineLvl = pPr.child("outlineLvl")?.get("val")?.toIntOrNull(),
            )
        }
    }
}

internal class StyleDef(
    val id: String,
    val type: String,
    val name: String,
    val basedOn: String?,
    val pPr: ParaProps?,
    val rPr: RunProps?,
    val tblBorders: Boolean?,
)

internal class DocxStyles(root: XNode?) {
    private val styles = HashMap<String, StyleDef>()
    val defaultRun: RunProps
    val defaultPara: ParaProps
    var defaultParagraphStyle: String? = null
        private set
    var defaultTableStyle: String? = null
        private set

    init {
        val defaults = root?.child("docDefaults")
        defaultRun = RunProps(sizeHalfPt = 20.0).merge(RunProps.parse(defaults?.path("rPrDefault", "rPr")))
        // Word's built-in defaults when docDefaults has no pPr: no spacing, single line.
        defaultPara = ParaProps(spaceBefore = 0.0, spaceAfter = 0.0, line = 240.0, lineRule = "auto")
            .merge(ParaProps.parse(defaults?.path("pPrDefault", "pPr")))
        root?.children("style")?.forEach { s ->
            val id = s["styleId"] ?: return@forEach
            val type = s["type"] ?: "paragraph"
            val def = StyleDef(
                id = id,
                type = type,
                name = s.child("name")?.get("val") ?: id,
                basedOn = s.child("basedOn")?.get("val"),
                pPr = ParaProps.parse(s.child("pPr")),
                rPr = RunProps.parse(s.child("rPr")),
                tblBorders = s.path("tblPr", "tblBorders")?.let { hasBorders(it) },
            )
            styles[id] = def
            if (s["default"] == "1" || s["default"] == "true") {
                if (type == "paragraph") defaultParagraphStyle = id
                if (type == "table") defaultTableStyle = id
            }
        }
    }

    private fun chain(id: String?): List<StyleDef> {
        val out = ArrayList<StyleDef>()
        var cur = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val s = styles[cur] ?: break
            out.add(0, s)
            cur = s.basedOn
        }
        return out
    }

    fun paraProps(styleId: String?): ParaProps {
        var p = defaultPara
        for (s in chain(styleId ?: defaultParagraphStyle)) p = p.merge(s.pPr)
        return p
    }

    fun runPropsForParagraphStyle(styleId: String?): RunProps {
        var r = defaultRun
        for (s in chain(styleId ?: defaultParagraphStyle)) r = r.merge(s.rPr)
        return r
    }

    fun runPropsForCharStyle(styleId: String?): RunProps? {
        if (styleId == null) return null
        var r = RunProps()
        for (s in chain(styleId)) r = r.merge(s.rPr)
        return r
    }

    fun tableHasBorders(styleId: String?): Boolean? {
        var b: Boolean? = null
        for (s in chain(styleId ?: defaultTableStyle)) b = s.tblBorders ?: b
        return b
    }

    /** Numbering properties attached to a paragraph style (e.g. "List Bullet"). */
    fun styleName(styleId: String?): String? = styleId?.let { styles[it]?.name }

    companion object {
        fun hasBorders(tblBorders: XNode): Boolean = tblBorders.children.any {
            val v = it["val"]; v != null && v != "none" && v != "nil"
        }
    }
}
