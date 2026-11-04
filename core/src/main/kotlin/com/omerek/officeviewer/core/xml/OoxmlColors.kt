package com.omerek.officeviewer.core.xml

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colour helpers shared by the spreadsheet and presentation converters. */
object OoxmlColors {

    /** Theme colour scheme keyed by DrawingML names (dk1, lt1, accent1, hlink ...). */
    fun parseScheme(theme: XNode?): Map<String, String> {
        val scheme = theme?.firstDescendant("clrScheme") ?: return DEFAULT_SCHEME
        val map = HashMap(DEFAULT_SCHEME)
        for (c in scheme.children) {
            val value = c.child("srgbClr")?.get("val") ?: c.child("sysClr")?.let { it["lastClr"] ?: sysColor(it["val"]) }
            if (value != null && value.length == 6) map[c.name] = value.uppercase(Locale.ROOT)
        }
        return map
    }

    private fun sysColor(v: String?) = when (v) {
        "windowText" -> "000000"
        "window" -> "FFFFFF"
        else -> null
    }

    val DEFAULT_SCHEME: Map<String, String> = mapOf(
        "dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "44546A", "lt2" to "E7E6E6",
        "accent1" to "4472C4", "accent2" to "ED7D31", "accent3" to "A5A5A5", "accent4" to "FFC000",
        "accent5" to "5B9BD5", "accent6" to "70AD47", "hlink" to "0563C1", "folHlink" to "954F72",
    )

    /**
     * Resolves a DrawingML colour choice element (the parent of srgbClr/schemeClr/...),
     * applying lumMod/lumOff/tint/shade/alpha modifiers. Returns a CSS colour or null.
     */
    fun drawingColor(parent: XNode?, scheme: Map<String, String>, phColor: String? = null): String? {
        if (parent == null) return null
        val c = parent.children.firstOrNull {
            it.name in setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")
        } ?: return null
        var hex: String = when (c.name) {
            "srgbClr" -> c["val"]
            "sysClr" -> c["lastClr"] ?: sysColor(c["val"])
            "schemeClr" -> when (val v = c["val"]) {
                "tx1" -> scheme["dk1"]
                "bg1" -> scheme["lt1"]
                "tx2" -> scheme["dk2"]
                "bg2" -> scheme["lt2"]
                "phClr" -> phColor?.removePrefix("#")
                else -> scheme[v]
            }
            "prstClr" -> PRESET[c["val"]]
            "scrgbClr" -> {
                fun p(k: String) = ((c[k]?.toDoubleOrNull() ?: 0.0) / 100000.0 * 255).roundToInt().coerceIn(0, 255)
                String.format(Locale.ROOT, "%02X%02X%02X", p("r"), p("g"), p("b"))
            }
            else -> null
        } ?: return null
        if (hex.length != 6) return null
        var alpha = 1.0
        for (m in c.children) {
            val v = (m["val"]?.toDoubleOrNull() ?: continue) / 100000.0
            hex = when (m.name) {
                "lumMod" -> hsl(hex) { h, s, l -> Triple(h, s, l * v) }
                "lumOff" -> hsl(hex) { h, s, l -> Triple(h, s, l + v) }
                "tint" -> mixRgb(hex, "FFFFFF", 1 - v)
                "shade" -> mixRgb(hex, "000000", 1 - v)
                "satMod" -> hsl(hex) { h, s, l -> Triple(h, s * v, l) }
                "alpha" -> { alpha = v; hex }
                else -> hex
            }
        }
        return css(hex, alpha)
    }

    /** SpreadsheetML colour element (rgb / theme+tint / indexed). */
    fun sheetColor(n: XNode?, scheme: Map<String, String>): String? {
        if (n == null || n["auto"] == "1") return null
        var hex: String? = n["rgb"]?.let { if (it.length == 8) it.substring(2) else it }
        if (hex == null) {
            n["theme"]?.toIntOrNull()?.let { idx ->
                val key = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4",
                    "accent5", "accent6", "hlink", "folHlink").getOrNull(idx)
                hex = key?.let { scheme[it] }
            }
        }
        if (hex == null) {
            n["indexed"]?.toIntOrNull()?.let { idx -> hex = INDEXED.getOrNull(idx) }
        }
        var h = hex ?: return null
        if (h.length != 6) return null
        n["tint"]?.toDoubleOrNull()?.let { t ->
            h = hsl(h) { hh, s, l -> Triple(hh, s, if (t < 0) l * (1 + t) else l * (1 - t) + t) }
        }
        return "#$h"
    }

    fun css(hex: String, alpha: Double = 1.0): String =
        if (alpha >= 0.999) "#$hex" else {
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            "rgba($r,$g,$b,${String.format(Locale.ROOT, "%.3f", alpha)})"
        }

    private fun mixRgb(a: String, b: String, t: Double): String {
        fun ch(s: String, i: Int) = s.substring(i, i + 2).toInt(16)
        val r = (ch(a, 0) + (ch(b, 0) - ch(a, 0)) * t).roundToInt().coerceIn(0, 255)
        val g = (ch(a, 2) + (ch(b, 2) - ch(a, 2)) * t).roundToInt().coerceIn(0, 255)
        val bl = (ch(a, 4) + (ch(b, 4) - ch(a, 4)) * t).roundToInt().coerceIn(0, 255)
        return String.format(Locale.ROOT, "%02X%02X%02X", r, g, bl)
    }

    private fun hsl(hex: String, f: (Double, Double, Double) -> Triple<Double, Double, Double>): String {
        val r = hex.substring(0, 2).toInt(16) / 255.0
        val g = hex.substring(2, 4).toInt(16) / 255.0
        val b = hex.substring(4, 6).toInt(16) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        var h = 0.0
        var s = 0.0
        val l = (mx + mn) / 2
        if (mx != mn) {
            val d = mx - mn
            s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6
        }
        val (h2, s2, l2) = f(h, s, l)
        val ss = s2.coerceIn(0.0, 1.0)
        val ll = l2.coerceIn(0.0, 1.0)
        fun hue(p: Double, q: Double, t0: Double): Double {
            var t = t0
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        val (rr, gg, bb) = if (ss == 0.0) Triple(ll, ll, ll) else {
            val q = if (ll < 0.5) ll * (1 + ss) else ll + ss - ll * ss
            val p = 2 * ll - q
            Triple(hue(p, q, h2 + 1.0 / 3), hue(p, q, h2), hue(p, q, h2 - 1.0 / 3))
        }
        return String.format(
            Locale.ROOT, "%02X%02X%02X",
            (rr * 255).roundToInt().coerceIn(0, 255), (gg * 255).roundToInt().coerceIn(0, 255), (bb * 255).roundToInt().coerceIn(0, 255),
        )
    }

    private val PRESET = mapOf(
        "black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "green" to "008000", "blue" to "0000FF",
        "yellow" to "FFFF00", "gray" to "808080", "grey" to "808080", "orange" to "FFA500", "purple" to "800080",
        "darkBlue" to "00008B", "darkRed" to "8B0000", "darkGreen" to "006400", "ltGray" to "D3D3D3",
        "dkGray" to "A9A9A9", "navy" to "000080", "cyan" to "00FFFF", "magenta" to "FF00FF",
    )

    /** The legacy 64-entry Excel palette. */
    val INDEXED = listOf(
        "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
        "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
        "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "808080",
        "9999FF", "993366", "FFFFCC", "CCFFFF", "660066", "FF8080", "0066CC", "CCCCFF",
        "000080", "FF00FF", "FFFF00", "00FFFF", "800080", "800000", "008080", "0000FF",
        "00CCFF", "CCFFFF", "CCFFCC", "FFFF99", "99CCFF", "FF99CC", "CC99FF", "FFCC99",
        "3366FF", "33CCCC", "99CC00", "FFCC00", "FF9900", "FF6600", "666699", "969696",
        "003366", "339966", "003300", "333300", "993300", "993366", "333399", "333333",
    )
}
