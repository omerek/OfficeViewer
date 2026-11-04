package com.omerek.officeviewer.core.xlsx

import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Formats numeric cell values according to Excel number format codes (common subset). */
class NumberFormatter(private val locale: Locale = Locale.getDefault(), private val date1904: Boolean = false) {

    private val symbols = DecimalFormatSymbols.getInstance(locale)
    private val cache = HashMap<String, DecimalFormat>()

    fun format(value: Double, numFmtId: Int, customCode: String?): String {
        val code = customCode ?: BUILTIN[numFmtId] ?: return general(value)
        return formatCode(value, code)
    }

    fun formatCode(value: Double, code: String): String {
        if (code.equals("General", true) || code.isBlank()) return general(value)
        if (code == "@") return general(value)
        val sections = splitSections(code)
        var v = value
        var section = sections[0]
        if (sections.size >= 2 && value < 0) { section = sections[1]; v = abs(value) }
        else if (sections.size >= 3 && value == 0.0) section = sections[2]
        val cleaned = cleanSection(section)
        if (cleaned.equals("General", true)) return general(v)
        return if (isDate(cleaned)) formatDate(v, cleaned) else formatNumber(v, cleaned, negativeHandled = sections.size >= 2 && value < 0)
    }

    fun general(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        if (v == floor(v) && abs(v) < 1e15) {
            return DecimalFormat("0", symbols).format(v)
        }
        val bd = BigDecimal(v).round(MathContext(11)).stripTrailingZeros()
        return if (abs(v) >= 1e-5 && abs(v) < 1e15) {
            val df = DecimalFormat("0.##########", symbols)
            df.format(bd)
        } else {
            DecimalFormat("0.#####E0", symbols).format(bd).replace("E", "E+").replace("E+-", "E-")
        }
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var inBracket = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' && i + 1 < code.length -> { sb.append(c).append(code[i + 1]); i++ }
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c == '[' && !inQuote -> { inBracket = true; sb.append(c) }
                c == ']' && !inQuote -> { inBracket = false; sb.append(c) }
                c == ';' && !inQuote && !inBracket -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** Removes colour/condition tags, keeps currency symbols from [$sym-LCID]. */
    private fun cleanSection(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '[') {
                val end = s.indexOf(']', i)
                if (end < 0) break
                val tag = s.substring(i + 1, end)
                when {
                    tag.startsWith("$") -> {
                        val sym = tag.substring(1).substringBefore('-')
                        if (sym.isNotEmpty()) sb.append('"').append(sym).append('"')
                    }
                    tag.matches(Regex("[hHmMsS]+")) -> sb.append('[').append(tag).append(']') // elapsed time
                }
                i = end + 1
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun isDate(s: String): Boolean {
        var inQuote = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> inQuote = !inQuote
                c == '\\' -> i++
                !inQuote && c.lowercaseChar() in "ymdhs" -> return true
            }
            i++
        }
        return false
    }

    // ------------------------------------------------------------------ dates

    fun toDateTime(serial: Double): LocalDateTime? {
        if (serial < 0 || serial > 2958465) return null
        var days = floor(serial).toLong()
        val millis = ((serial - days) * 86_400_000.0).roundToLong()
        val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        if (!date1904 && days < 61) days += 1 // Excel's fictional 29 Feb 1900
        return base.plusDays(days).plusNanos(millis * 1_000_000)
    }

    private fun formatDate(v: Double, code: String): String {
        val dt = toDateTime(v) ?: return general(v)
        val tokens = tokenize(code)
        val hasAmPm = tokens.any { it.equals("AM/PM", true) || it.equals("A/P", true) }
        val sb = StringBuilder()
        for ((idx, t) in tokens.withIndex()) {
            val lower = t.lowercase()
            val minuteContext = isMinute(tokens, idx)
            when {
                t.startsWith("\"") -> sb.append(t.trim('"'))
                t.startsWith("\\") -> sb.append(t.drop(1))
                lower.startsWith("[h") -> sb.append(floor(v * 24).toLong())
                lower.startsWith("[m") -> sb.append(floor(v * 1440).toLong())
                lower.startsWith("[s") -> sb.append(floor(v * 86400).toLong())
                lower == "yyyy" || lower == "yyy" -> sb.append(dt.year)
                lower == "yy" || lower == "y" -> sb.append(String.format(Locale.ROOT, "%02d", dt.year % 100))
                lower.startsWith("m") && minuteContext -> sb.append(if (lower.length >= 2) pad(dt.minute) else dt.minute)
                lower == "mmmmm" -> sb.append(dt.month.getDisplayName(TextStyle.NARROW, locale))
                lower == "mmmm" -> sb.append(dt.month.getDisplayName(TextStyle.FULL, locale))
                lower == "mmm" -> sb.append(dt.month.getDisplayName(TextStyle.SHORT, locale))
                lower == "mm" -> sb.append(pad(dt.monthValue))
                lower == "m" -> sb.append(dt.monthValue)
                lower == "dddd" -> sb.append(dt.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
                lower == "ddd" -> sb.append(dt.dayOfWeek.getDisplayName(TextStyle.SHORT, locale))
                lower == "dd" -> sb.append(pad(dt.dayOfMonth))
                lower == "d" -> sb.append(dt.dayOfMonth)
                lower.startsWith("h") -> {
                    val h = if (hasAmPm) (dt.hour % 12).let { if (it == 0) 12 else it } else dt.hour
                    sb.append(if (lower.length >= 2) pad(h) else h)
                }
                lower.startsWith("s") -> sb.append(if (lower.length >= 2) pad(dt.second) else dt.second)
                lower == "am/pm" -> sb.append(if (dt.hour < 12) "AM" else "PM")
                lower == "a/p" -> sb.append(if (dt.hour < 12) "A" else "P")
                lower.startsWith(".0") -> {
                    val frac = (dt.nano / 1_000_000_000.0)
                    sb.append(String.format(Locale.ROOT, "%.${lower.length - 1}f", frac).substring(1))
                }
                else -> sb.append(t.replace("_", "").replace("*", ""))
            }
        }
        return sb.toString()
    }

    private fun pad(n: Int) = if (n < 10) "0$n" else n.toString()

    /** An "m" token means minutes when it follows an hour or precedes a seconds token. */
    private fun isMinute(tokens: List<String>, idx: Int): Boolean {
        val t = tokens[idx].lowercase()
        if (!t.startsWith("m") || t.length > 2) return false
        for (j in idx - 1 downTo 0) {
            val p = tokens[j].lowercase()
            if (p.startsWith("h") || p.startsWith("[h")) return true
            if (p.first().isLetter() && !p.startsWith("\"")) break
        }
        for (j in idx + 1 until tokens.size) {
            val p = tokens[j].lowercase()
            if (p.startsWith("s")) return true
            if (p.first().isLetter() && !p.startsWith("\"")) break
        }
        return false
    }

    private fun tokenize(code: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> {
                    val end = code.indexOf('"', i + 1).let { if (it < 0) code.length - 1 else it }
                    out += code.substring(i, end + 1); i = end + 1
                }
                c == '\\' && i + 1 < code.length -> { out += code.substring(i, i + 2); i += 2 }
                c == '[' -> {
                    val end = code.indexOf(']', i).let { if (it < 0) code.length - 1 else it }
                    out += code.substring(i, end + 1); i = end + 1
                }
                code.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> { out += code.substring(i, i + 5); i += 5 }
                code.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> { out += code.substring(i, i + 3); i += 3 }
                c.lowercaseChar() in "ymdhs" -> {
                    var j = i
                    while (j < code.length && code[j].lowercaseChar() == c.lowercaseChar()) j++
                    out += code.substring(i, j); i = j
                }
                c == '.' && i + 1 < code.length && code[i + 1] == '0' -> {
                    var j = i + 1
                    while (j < code.length && code[j] == '0') j++
                    out += code.substring(i, j); i = j
                }
                c == '_' && i + 1 < code.length -> { out += " "; i += 2 }
                c == '*' && i + 1 < code.length -> { i += 2 }
                else -> { out += c.toString(); i++ }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ numbers

    private fun formatNumber(value: Double, code: String, negativeHandled: Boolean): String {
        // Split literal prefix/suffix from the numeric pattern.
        val sb = StringBuilder()     // pattern for DecimalFormat
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        var seenDigit = false
        var percent = 0
        var i = 0
        var pendingLiteral = StringBuilder()
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> {
                    val end = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }
                    pendingLiteral.append(code, i + 1, end); i = end + 1; continue
                }
                c == '\\' && i + 1 < code.length -> { pendingLiteral.append(code[i + 1]); i += 2; continue }
                c == '_' && i + 1 < code.length -> { pendingLiteral.append(' '); i += 2; continue }
                c == '*' && i + 1 < code.length -> { i += 2; continue }
                c in "0#?,." || (c == 'E' || c == 'e') && seenDigit || (c == '+' || c == '-') && sb.endsWith("E") -> {
                    if (!seenDigit) { prefix.append(pendingLiteral) } else if (pendingLiteral.isNotEmpty()) {
                        // literal between digits (e.g. phone formats); keep it as quoted text in pattern
                        sb.append('\'').append(pendingLiteral.toString().replace("'", "''")).append('\'')
                    }
                    pendingLiteral = StringBuilder()
                    seenDigit = seenDigit || c in "0#?"
                    sb.append(
                        when (c) {
                            '?' -> '#'
                            'e' -> 'E'
                            else -> c
                        },
                    )
                }
                c == '%' -> { percent++; pendingLiteral.append('%') }
                c == '/' -> return general(value) // fractions: fall back
                else -> pendingLiteral.append(c)
            }
            i++
        }
        if (seenDigit) suffix.append(pendingLiteral) else prefix.append(pendingLiteral)
        var pattern = sb.toString().replace("E+", "E").replace("E-", "E")
        if (pattern.isEmpty()) return prefix.toString() + suffix.toString()

        var v = value
        repeat(percent) { v *= 100 }
        // Trailing commas scale by 1000.
        while (pattern.endsWith(",")) { pattern = pattern.dropLast(1); v /= 1000 }
        val df = cache.getOrPut(pattern) {
            runCatching { DecimalFormat(pattern, symbols) }.getOrElse { DecimalFormat("0.##", symbols) }
        }
        var body = df.format(v)
        if (pattern.contains('E')) body = body.replace("E", "E+").replace("E+-", "E-")
        if (negativeHandled) body = body.removePrefix("-")
        return prefix.toString() + body + suffix.toString()
    }

    companion object {
        val BUILTIN: Map<Int, String> = mapOf(
            0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
            5 to "#,##0;-#,##0", 6 to "#,##0;[Red]-#,##0", 7 to "#,##0.00;-#,##0.00", 8 to "#,##0.00;[Red]-#,##0.00",
            9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "General", 13 to "General",
            14 to "dd.mm.yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
            18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "dd.mm.yyyy h:mm",
            37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)",
            40 to "#,##0.00;[Red](#,##0.00)", 45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0",
            48 to "##0.0E+0", 49 to "@",
        )
    }
}
