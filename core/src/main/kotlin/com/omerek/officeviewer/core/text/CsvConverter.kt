package com.omerek.officeviewer.core.text

import com.omerek.officeviewer.core.DocumentFormat
import com.omerek.officeviewer.core.Html
import com.omerek.officeviewer.core.RenderResult

object CsvConverter {
    private const val MAX_ROWS = 20_000
    private const val MAX_COLS = 500

    fun convert(bytes: ByteArray, title: String, tab: Boolean = false): RenderResult {
        val text = TextDecoding.decode(bytes)
        val delimiter = if (tab) '\t' else detectDelimiter(text)
        val rows = parse(text, delimiter, MAX_ROWS + 1)
        val truncated = rows.size > MAX_ROWS || rows.any { it.size > MAX_COLS }
        val cols = minOf(rows.maxOfOrNull { it.size } ?: 0, MAX_COLS)

        val sb = StringBuilder("<div class=\"wrap\"><table class=\"grid\"><thead><tr><th class=\"corner\"></th>")
        for (c in 0 until cols) sb.append("<th>").append(columnName(c)).append("</th>")
        sb.append("</tr></thead><tbody>")
        for ((i, row) in rows.take(MAX_ROWS).withIndex()) {
            sb.append("<tr><th>").append(i + 1).append("</th>")
            for (c in 0 until cols) {
                val v = row.getOrNull(c).orEmpty()
                val numeric = v.isNotEmpty() && v.trim().replace(",", ".").toDoubleOrNull() != null
                sb.append(if (numeric) "<td class=\"n\">" else "<td>").append(Html.esc(v)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table></div>")
        val warnings = if (truncated) listOf("truncated") else emptyList()
        return RenderResult(Html.page(title, GRID_CSS, sb.toString()), DocumentFormat.CSV, 1, warnings)
    }

    internal fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().take(20).toList()
        val candidates = listOf(',', ';', '\t', '|')
        // Prefer the delimiter that appears a consistent, non-zero number of times per line.
        return candidates.maxByOrNull { d ->
            val counts = sample.filter { it.isNotBlank() }.map { line -> line.count { it == d } }
            if (counts.isEmpty() || counts.all { it == 0 }) -1.0
            else {
                val mode = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
                mode.key * (mode.value.toDouble() / counts.size)
            }
        } ?: ','
    }

    /** RFC 4180 parser (quotes, escaped quotes, embedded newlines). */
    internal fun parse(text: String, d: Char, maxRows: Int): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length
        while (i < n && rows.size < maxRows) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else {
                when (c) {
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(c)
                    d -> { row.add(field.toString()); field.setLength(0) }
                    '\r' -> {}
                    '\n' -> { row.add(field.toString()); field.setLength(0); rows.add(row); row = ArrayList() }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    fun columnName(index: Int): String {
        var i = index + 1
        val sb = StringBuilder()
        while (i > 0) {
            val r = (i - 1) % 26
            sb.insert(0, ('A' + r))
            i = (i - 1) / 26
        }
        return sb.toString()
    }

    internal const val GRID_CSS = """
html,body{background:#fff;}
.wrap{overflow:auto;}
.grid{border-collapse:collapse;font-size:13px;background:#fff;}
.grid td,.grid th{border:1px solid #dadce0;padding:2px 6px;white-space:pre;max-width:480px;overflow:hidden;text-overflow:ellipsis;}
.grid thead th{position:sticky;top:0;background:#f1f3f4;color:#5f6368;font-weight:normal;z-index:2;}
.grid tbody th{position:sticky;left:0;background:#f1f3f4;color:#5f6368;font-weight:normal;text-align:center;z-index:1;}
.grid th.corner{left:0;z-index:3;}
.grid td.n{text-align:right;}
"""
}
