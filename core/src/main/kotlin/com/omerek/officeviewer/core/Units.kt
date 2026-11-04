package com.omerek.officeviewer.core

import java.util.Locale

internal object Units {
    /** 1440 twips per inch, 96 CSS px per inch. */
    fun twipsToPx(twips: Double): Double = twips / 15.0

    /** 914400 EMU per inch. */
    fun emuToPx(emu: Double): Double = emu / 9525.0

    fun fmt(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.US, "%.2f", r)
    }

    fun parseDouble(s: String?): Double? = s?.trim()?.toDoubleOrNull()

    /** Parses OOXML measurements that are usually plain numbers but may carry units (e.g. "12pt"). */
    fun twips(s: String?): Double? {
        if (s == null) return null
        val t = s.trim()
        return when {
            t.endsWith("pt") -> t.dropLast(2).toDoubleOrNull()?.times(20)
            t.endsWith("in") -> t.dropLast(2).toDoubleOrNull()?.times(1440)
            t.endsWith("cm") -> t.dropLast(2).toDoubleOrNull()?.times(1440 / 2.54)
            t.endsWith("mm") -> t.dropLast(2).toDoubleOrNull()?.times(144 / 2.54)
            t.endsWith("pc") || t.endsWith("pi") -> t.dropLast(2).toDoubleOrNull()?.times(240)
            else -> t.toDoubleOrNull()
        }
    }

    fun fontFamily(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val lower = name.lowercase()
        val generic = when {
            listOf("courier", "consolas", "mono", "lucida console").any { it in lower } -> "monospace"
            listOf("times", "georgia", "cambria", "garamond", "book", "serif", "palatino", "minion").any { it in lower } &&
                "sans" !in lower -> "serif"
            else -> "sans-serif"
        }
        val alias = when (lower) {
            "calibri" -> ",Carlito"
            "cambria" -> ",Caladea"
            "arial" -> ",'Liberation Sans'"
            "times new roman" -> ",'Liberation Serif','Noto Serif'"
            "courier new" -> ",'Liberation Mono'"
            else -> ""
        }
        val safe = name.replace("'", "").replace("\"", "").replace(";", "").replace("<", "")
        return "'$safe'$alias,$generic"
    }

    fun hexColor(v: String?): String? {
        if (v == null) return null
        val s = v.trim()
        if (s.equals("auto", true)) return null
        return when (s.length) {
            6 -> if (s.all { it.isHexDigit() }) "#$s" else null
            8 -> if (s.all { it.isHexDigit() }) "#" + s.substring(2) else null // ARGB
            else -> null
        }
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
        return sb.toString()
    }

    fun letters(n: Int): String {
        if (n <= 0) return n.toString()
        // Word style: a..z, aa..zz, aaa..
        val ch = ('a' + (n - 1) % 26)
        val repeat = (n - 1) / 26 + 1
        return ch.toString().repeat(repeat)
    }
}
