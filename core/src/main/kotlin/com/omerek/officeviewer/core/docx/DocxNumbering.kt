package com.omerek.officeviewer.core.docx

import com.omerek.officeviewer.core.Units
import com.omerek.officeviewer.core.xml.XNode

internal class NumLevel(
    val start: Int,
    val format: String,
    val text: String,
    val pPr: ParaProps?,
    val rPr: RunProps?,
)

/** Resolves list numbering (numbering.xml) and keeps running counters. */
internal class DocxNumbering(root: XNode?) {
    private val abstract = HashMap<String, Map<Int, NumLevel>>()
    private val numToAbstract = HashMap<String, String>()
    private val overrides = HashMap<String, Map<Int, Int>>()
    private val counters = HashMap<String, IntArray>()

    init {
        root?.children("abstractNum")?.forEach { a ->
            val id = a["abstractNumId"] ?: return@forEach
            abstract[id] = a.children("lvl").associate { l ->
                (l["ilvl"]?.toIntOrNull() ?: 0) to NumLevel(
                    start = l.child("start")?.get("val")?.toIntOrNull() ?: 1,
                    format = l.child("numFmt")?.get("val") ?: "decimal",
                    text = l.child("lvlText")?.get("val") ?: "",
                    pPr = ParaProps.parse(l.child("pPr")),
                    rPr = RunProps.parse(l.child("rPr")),
                )
            }
        }
        root?.children("num")?.forEach { n ->
            val id = n["numId"] ?: return@forEach
            n.child("abstractNumId")?.get("val")?.let { numToAbstract[id] = it }
            val ov = n.children("lvlOverride").mapNotNull { o ->
                val lvl = o["ilvl"]?.toIntOrNull() ?: return@mapNotNull null
                val start = o.child("startOverride")?.get("val")?.toIntOrNull() ?: return@mapNotNull null
                lvl to start
            }.toMap()
            if (ov.isNotEmpty()) overrides[id] = ov
        }
    }

    fun level(numId: String, ilvl: Int): NumLevel? = numToAbstract[numId]?.let { abstract[it]?.get(ilvl) }

    /** Advances the counter for (numId, ilvl) and returns the label text, or null for "no numbering". */
    fun nextLabel(numId: String, ilvl: Int): String? {
        if (numId == "0") return null
        val absId = numToAbstract[numId] ?: return null
        val levels = abstract[absId] ?: return null
        val lvl = levels[ilvl] ?: return null
        // Counters are shared per abstract definition so that lists split by other
        // paragraphs keep counting, as Word does.
        val c = counters.getOrPut(absId) { IntArray(9) { -1 } }
        for (i in 0 until 9) {
            if (i < ilvl && c[i] < 0) c[i] = startFor(numId, levels, i)
        }
        c[ilvl] = if (c[ilvl] < 0) startFor(numId, levels, ilvl) else c[ilvl] + 1
        for (i in ilvl + 1 until 9) c[i] = -1

        if (lvl.format == "bullet") return bullet(lvl.text)
        if (lvl.format == "none") return lvl.text.ifEmpty { null }
        var text = lvl.text
        for (i in 0..ilvl) {
            val l = levels[i] ?: continue
            text = text.replace("%${i + 1}", format(maxOf(c[i], 0), l.format))
        }
        return text
    }

    private fun startFor(numId: String, levels: Map<Int, NumLevel>, i: Int): Int =
        overrides[numId]?.get(i) ?: levels[i]?.start ?: 1

    companion object {
        fun format(n: Int, fmt: String): String = when (fmt) {
            "decimal", "decimalEnclosedCircle", "decimalEnclosedFullstop", "decimalEnclosedParen" -> n.toString()
            "decimalZero" -> if (n < 10) "0$n" else n.toString()
            "lowerLetter" -> Units.letters(n)
            "upperLetter" -> Units.letters(n).uppercase()
            "lowerRoman" -> Units.roman(n).lowercase()
            "upperRoman" -> Units.roman(n)
            "ordinal" -> "$n."
            else -> n.toString()
        }

        /** Symbol/Wingdings private-use bullets are mapped to visible Unicode equivalents. */
        fun bullet(text: String): String {
            if (text.isEmpty()) return "\u2022"
            val c = text[0].code
            return when {
                c == 0xF0B7 -> "\u2022"
                c == 0xF0A7 -> "\u25AA"
                c == 0xF0D8 -> "\u27A2"
                c == 0xF076 -> "\u2756"
                c == 0xF0FC -> "\u2713"
                c == 0xF06E -> "\u25A0"
                c == 0xF0A8 -> "\u25A1"
                text == "o" -> "\u25E6"
                c in 0xF000..0xF0FF -> "\u2022"
                else -> text
            }
        }
    }
}
