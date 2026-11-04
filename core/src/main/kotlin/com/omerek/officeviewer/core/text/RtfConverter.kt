package com.omerek.officeviewer.core.text

import com.omerek.officeviewer.core.DocumentFormat
import com.omerek.officeviewer.core.Html
import com.omerek.officeviewer.core.RenderResult
import com.omerek.officeviewer.core.RenderStrings
import com.omerek.officeviewer.core.Units
import com.omerek.officeviewer.core.Units.fmt
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64

/** A pragmatic RTF reader: text, character formatting, alignment, simple tables and PNG/JPEG pictures. */
class RtfConverter(private val strings: RenderStrings = RenderStrings()) {

    private data class State(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var sizeHalfPt: Int = 24,
        var color: Int = 0,
        var highlight: Int = 0,
        var font: Int = -1,
        var vert: Int = 0,
        var hidden: Boolean = false,
        var dest: Dest = Dest.NORMAL,
        var uc: Int = 1,
        var align: String = "",
        var inTable: Boolean = false,
        var leftIndent: Int = 0,
        var firstIndent: Int = 0,
        var spaceAfter: Int = 0,
        var spaceBefore: Int = 0,
    )

    private enum class Dest { NORMAL, SKIP, FONTTBL, COLORTBL, PICT }

    private val skipDestinations = setOf(
        "stylesheet", "info", "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
        "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping",
        "latentstyles", "datastore", "fldinst", "bkmkstart", "bkmkend", "nonshppict", "footnote", "annotation",
        "atnid", "atnauthor", "comment", "revtbl", "pgdsctbl", "filetbl", "mmathPr", "objdata", "pn", "pntext",
        "pntxta", "pntxtb", "listtext", "wgrffmtfilter", "xe", "tc", "template", "userprops", "operator", "author",
        "title", "company", "doccomm", "keywords", "subject", "category", "manager",
    )

    private var defaultCharset: Charset = Charsets.ISO_8859_1
    private val fontCharsets = HashMap<Int, Charset?>()
    private val colors = ArrayList<String?>()
    private val out = StringBuilder()
    private val para = StringBuilder()
    private val pendingBytes = ByteArrayOutputStream()
    private var pendingState: State? = null

    // table state
    private var tableOpen = false
    private val rowCells = ArrayList<String>()
    private val cellBuf = StringBuilder()

    // picture state
    private val pictHex = StringBuilder()
    private var pictType = ""
    private var pictW = 0
    private var pictH = 0

    fun convert(bytes: ByteArray, title: String): RenderResult {
        val s = String(bytes, Charsets.ISO_8859_1)
        defaultCharset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        parse(s)
        closeSpan()
        if (para.isNotEmpty()) flushParagraph(State())
        closeTable()
        val css = """
body{padding:12px 0;}
.page{background:#fff;max-width:816px;margin:0 auto;padding:48px 56px;box-sizing:border-box;box-shadow:0 1px 4px rgba(0,0,0,.25);font-family:'Times New Roman',serif;font-size:12pt;overflow-wrap:break-word;}
.page p{margin:0;min-height:1em;}
table{border-collapse:collapse;margin:4px 0;}
td{border:1px solid #000;padding:2px 6px;vertical-align:top;}
img{max-width:100%;height:auto;}
@media (max-width:600px){body{padding:0;}.page{padding:20px 16px;box-shadow:none;min-height:100vh;}}
"""
        val body = "<div class=\"page\">${out.ifEmpty { "<p>${Html.esc(strings.emptyDocument)}</p>" }}</div>"
        return RenderResult(Html.page(title, css, body), DocumentFormat.RTF, 1)
    }

    private fun parse(s: String) {
        val stack = ArrayDeque<State>()
        var st = State()
        var skipChars = 0
        var i = 0
        val n = s.length
        var firstInGroup = false

        while (i < n) {
            val c = s[i]
            when (c) {
                '{' -> {
                    flushBytes(st)
                    stack.addLast(st.copy())
                    firstInGroup = true
                    i++
                    continue
                }
                '}' -> {
                    flushBytes(st)
                    if (st.dest == Dest.PICT) finishPicture(st)
                    val prev = stack.removeLastOrNull() ?: State()
                    st = prev
                    i++
                    continue
                }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val d = s[i]
                    if (d.isLetter()) {
                        val start = i
                        while (i < n && s[i].isLetter()) i++
                        val word = s.substring(start, i)
                        var param: Int? = null
                        if (i < n && (s[i] == '-' || s[i].isDigit())) {
                            val ps = i
                            i++
                            while (i < n && s[i].isDigit()) i++
                            param = s.substring(ps, i).toIntOrNull()
                        }
                        if (i < n && s[i] == ' ') i++
                        if (word != "u") flushBytes(st)
                        if (skipChars > 0 && word != "u") { skipChars--; firstInGroup = false; continue }
                        skipChars = controlWord(word, param, st, firstInGroup, skipChars)
                        firstInGroup = false
                    } else {
                        i++
                        when (d) {
                            '\'' -> {
                                val hex = s.substring(i, minOf(i + 2, n))
                                i += hex.length
                                if (skipChars > 0) { skipChars--; continue }
                                hex.toIntOrNull(16)?.let { b ->
                                    if (st.dest == Dest.NORMAL || st.dest == Dest.FONTTBL) {
                                        if (pendingBytes.size() == 0) pendingState = st.copy()
                                        pendingBytes.write(b)
                                    }
                                }
                            }
                            '*' -> {
                                // Unknown destinations flagged with \* are skipped unless we know them.
                                st.dest = if (st.dest == Dest.NORMAL) Dest.SKIP else st.dest
                                markStarredSkip(s, i, st)
                            }
                            '~' -> text(" ", st)
                            '_' -> text("‑", st)
                            '-' -> {}
                            '\\', '{', '}' -> { flushBytes(st); if (skipChars > 0) skipChars-- else text(d.toString(), st) }
                            '\r', '\n' -> controlWord("par", null, st, false, 0)
                            else -> {}
                        }
                        firstInGroup = false
                    }
                    continue
                }
                '\r', '\n' -> { i++; continue }
                else -> {
                    flushBytes(st)
                    if (skipChars > 0) { skipChars--; i++; continue }
                    when (st.dest) {
                        Dest.NORMAL -> text(c.toString(), st)
                        Dest.PICT -> if (c.isLetterOrDigit()) pictHex.append(c)
                        Dest.FONTTBL -> {}
                        Dest.COLORTBL -> if (c == ';') colors.add(currentColor ?: null).also { currentColor = null }
                        Dest.SKIP -> {}
                    }
                    firstInGroup = false
                    i++
                }
            }
        }
    }

    private var currentColor: String? = null
    private var r = 0
    private var g = 0
    private var b = 0
    private var currentFont = -1

    /**
     * After "\*", a known destination such as \shppict should still be processed. We restore the
     * state to NORMAL for the ones we understand.
     */
    private fun markStarredSkip(s: String, i: Int, st: State) {
        var j = i
        while (j < s.length && (s[j] == ' ' || s[j] == '\r' || s[j] == '\n')) j++
        if (j < s.length && s[j] == '\\') {
            val start = j + 1
            var k = start
            while (k < s.length && s[k].isLetter()) k++
            val word = s.substring(start, k)
            if (word == "shppict") st.dest = Dest.NORMAL
        }
    }

    private fun controlWord(word: String, param: Int?, st: State, firstInGroup: Boolean, skip: Int): Int {
        if (st.dest == Dest.SKIP) return skip
        if (st.dest == Dest.COLORTBL) {
            when (word) {
                "red" -> { r = param ?: 0; currentColor = rgb() }
                "green" -> { g = param ?: 0; currentColor = rgb() }
                "blue" -> { b = param ?: 0; currentColor = rgb() }
            }
            return skip
        }
        if (st.dest == Dest.FONTTBL) {
            when (word) {
                "f" -> currentFont = param ?: -1
                "fcharset" -> fontCharsets[currentFont] = charsetFor(param ?: 0)
            }
            return skip
        }
        if (st.dest == Dest.PICT) {
            when (word) {
                "pngblip" -> pictType = "image/png"
                "jpegblip" -> pictType = "image/jpeg"
                "picwgoal" -> pictW = param ?: 0
                "pichgoal" -> pictH = param ?: 0
                "bin" -> {}
            }
            return skip
        }
        if (word in skipDestinations) { st.dest = Dest.SKIP; return skip }
        when (word) {
            "ansicpg" -> param?.let { cp -> runCatching { Charset.forName("windows-$cp") }.getOrNull()?.let { defaultCharset = it } }
            "fonttbl" -> st.dest = Dest.FONTTBL
            "colortbl" -> { st.dest = Dest.COLORTBL; colors.clear(); currentColor = null }
            "pict" -> { st.dest = Dest.PICT; pictHex.setLength(0); pictType = ""; pictW = 0; pictH = 0 }
            "uc" -> st.uc = param ?: 1
            "u" -> {
                var code = param ?: 0
                if (code < 0) code += 65536
                text(String(Character.toChars(code)), st)
                return st.uc
            }
            "par", "sect" -> paragraph(st)
            "line" -> text("\n", st)
            "tab" -> text("\t", st)
            "page" -> paragraph(st)
            "emdash" -> text("—", st)
            "endash" -> text("–", st)
            "bullet" -> text("•", st)
            "lquote" -> text("‘", st)
            "rquote" -> text("’", st)
            "ldblquote" -> text("“", st)
            "rdblquote" -> text("”", st)
            "plain" -> { st.bold = false; st.italic = false; st.underline = false; st.strike = false; st.sizeHalfPt = 24; st.color = 0; st.highlight = 0; st.vert = 0; st.hidden = false }
            "b" -> st.bold = param != 0
            "i" -> st.italic = param != 0
            "ul" -> st.underline = param != 0
            "ulnone" -> st.underline = false
            "strike" -> st.strike = param != 0
            "v" -> st.hidden = param != 0
            "fs" -> st.sizeHalfPt = param ?: 24
            "cf" -> st.color = param ?: 0
            "highlight", "cb" -> st.highlight = param ?: 0
            "f" -> st.font = param ?: -1
            "super" -> st.vert = 1
            "sub" -> st.vert = -1
            "nosupersub" -> st.vert = 0
            "pard" -> { st.align = ""; st.inTable = false; st.leftIndent = 0; st.firstIndent = 0; st.spaceAfter = 0; st.spaceBefore = 0 }
            "ql" -> st.align = ""
            "qc" -> st.align = "center"
            "qr" -> st.align = "right"
            "qj" -> st.align = "justify"
            "li" -> st.leftIndent = param ?: 0
            "fi" -> st.firstIndent = param ?: 0
            "sa" -> st.spaceAfter = param ?: 0
            "sb" -> st.spaceBefore = param ?: 0
            "intbl" -> st.inTable = true
            "trowd" -> {}
            "cell" -> {
                flushParagraphInto(cellBuf, st)
                rowCells.add(cellBuf.toString())
                cellBuf.setLength(0)
            }
            "row" -> {
                if (!tableOpen) { out.append("<table>"); tableOpen = true }
                out.append("<tr>")
                for (cell in rowCells) out.append("<td>").append(cell).append("</td>")
                out.append("</tr>")
                rowCells.clear()
            }
        }
        return skip
    }

    private fun rgb() = String.format("#%02x%02x%02x", r and 255, g and 255, b and 255)

    private fun charsetFor(code: Int): Charset? {
        val name = when (code) {
            0 -> return null
            2 -> return null
            162 -> "windows-1254"
            161 -> "windows-1253"
            204 -> "windows-1251"
            238 -> "windows-1250"
            177 -> "windows-1255"
            178 -> "windows-1256"
            186 -> "windows-1257"
            163 -> "windows-1258"
            222 -> "x-windows-874"
            128 -> "Shift_JIS"
            134 -> "GBK"
            136 -> "Big5"
            129 -> "x-windows-949"
            else -> return null
        }
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun flushBytes(st: State) {
        if (pendingBytes.size() == 0) return
        val state = pendingState ?: st
        val cs = fontCharsets[state.font] ?: defaultCharset
        val str = String(pendingBytes.toByteArray(), cs)
        pendingBytes.reset()
        pendingState = null
        if (state.dest == Dest.NORMAL) text(str, state)
    }

    private var lastSpanKey: String? = null

    private fun text(t: String, st: State) {
        if (st.dest != Dest.NORMAL || st.hidden) return
        val css = StringBuilder()
        if (st.bold) css.append("font-weight:bold;")
        if (st.italic) css.append("font-style:italic;")
        if (st.underline || st.strike) css.append("text-decoration:${listOfNotNull(if (st.underline) "underline" else null, if (st.strike) "line-through" else null).joinToString(" ")};")
        if (st.sizeHalfPt != 24) css.append("font-size:${fmt(st.sizeHalfPt / 2.0 * if (st.vert != 0) 0.7 else 1.0)}pt;")
        colors.getOrNull(st.color)?.let { css.append("color:$it;") }
        if (st.highlight > 0) colors.getOrNull(st.highlight)?.let { css.append("background:$it;") }
        if (st.vert == 1) css.append("vertical-align:super;") else if (st.vert == -1) css.append("vertical-align:sub;")
        val key = css.toString()
        if (key != lastSpanKey) {
            if (lastSpanKey != null && lastSpanKey!!.isNotEmpty()) para.append("</span>")
            if (key.isNotEmpty()) para.append("<span style=\"").append(key).append("\">")
            lastSpanKey = key
        }
        para.append(Html.esc(t).replace("\n", "<br>").replace("\t", "&emsp;&emsp;"))
    }

    private fun closeSpan() {
        if (!lastSpanKey.isNullOrEmpty()) para.append("</span>")
        lastSpanKey = null
    }

    private fun paragraph(st: State) {
        if (st.inTable) {
            // Paragraph inside a cell: keep it in the cell buffer.
            flushParagraphInto(cellBuf, st)
            return
        }
        closeTable()
        flushParagraph(st)
    }

    private fun flushParagraph(st: State) {
        closeSpan()
        if (para.isEmpty() && out.isEmpty()) return
        out.append("<p style=\"").append(paraCss(st)).append("\">").append(para.ifEmpty { "<br>" }).append("</p>")
        para.setLength(0)
    }

    private fun flushParagraphInto(target: StringBuilder, st: State) {
        closeSpan()
        if (para.isNotEmpty()) {
            target.append("<p style=\"").append(paraCss(st)).append("\">").append(para).append("</p>")
            para.setLength(0)
        }
    }

    private fun paraCss(st: State) = buildString {
        if (st.align.isNotEmpty()) append("text-align:${st.align};")
        if (st.leftIndent != 0) append("margin-left:${fmt(Units.twipsToPx(st.leftIndent.toDouble()))}px;")
        if (st.firstIndent != 0) append("text-indent:${fmt(Units.twipsToPx(st.firstIndent.toDouble()))}px;")
        if (st.spaceAfter != 0) append("margin-bottom:${fmt(st.spaceAfter / 20.0)}pt;")
        if (st.spaceBefore != 0) append("margin-top:${fmt(st.spaceBefore / 20.0)}pt;")
    }

    private fun closeTable() {
        if (rowCells.isNotEmpty()) controlWord("row", null, State(), false, 0)
        if (tableOpen) { out.append("</table>"); tableOpen = false }
    }

    private fun finishPicture(st: State) {
        val hex = pictHex.toString()
        if (pictType.isEmpty() || hex.length < 16) {
            para.append(Html.placeholder(strings.unsupportedImage))
            return
        }
        val bytes = ByteArray(hex.length / 2) { idx -> hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
        val w = if (pictW > 0) Units.twipsToPx(pictW.toDouble()) else 0.0
        val style = if (w > 0) "width:${fmt(w)}px;" else ""
        closeSpan()
        para.append("<img alt=\"\" style=\"$style\" src=\"data:$pictType;base64,")
            .append(Base64.getEncoder().encodeToString(bytes)).append("\">")
    }
}
