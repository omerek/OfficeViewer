package com.omerek.officeviewer.core

import com.omerek.officeviewer.core.docx.DocxConverter
import com.omerek.officeviewer.core.pptx.PptxConverter
import com.omerek.officeviewer.core.text.CsvConverter
import com.omerek.officeviewer.core.text.PlainTextConverter
import com.omerek.officeviewer.core.text.RtfConverter
import com.omerek.officeviewer.core.xlsx.XlsxConverter

/** Result of rendering a document to a self-contained HTML page (no network resources). */
data class RenderResult(
    val html: String,
    val format: DocumentFormat,
    /** Number of pages / sheets / slides when meaningful. */
    val sectionCount: Int = 0,
    val warnings: List<String> = emptyList(),
)

/** Localised strings used inside rendered HTML (passed from the app's resources). */
data class RenderStrings(
    val unsupportedTitle: String = "This format is not supported yet",
    val legacyMessage: String = "Legacy binary Office files (.doc, .xls, .ppt) will be supported in a future version. " +
        "Please save the file in the modern format (.docx, .xlsx, .pptx) and try again.",
    val unknownMessage: String = "The file type could not be recognised.",
    val errorTitle: String = "The document could not be opened",
    val image: String = "Image",
    val unsupportedImage: String = "Image (format not supported)",
    val chart: String = "Chart",
    val sheetTruncated: String = "Only the first %1\$d rows and %2\$d columns are shown.",
    val slide: String = "Slide",
    val emptyDocument: String = "The document is empty.",
)

object DocumentConverter {

    fun render(
        bytes: ByteArray,
        fileName: String?,
        mime: String?,
        strings: RenderStrings = RenderStrings(),
    ): RenderResult {
        val format = DocumentFormat.detect(bytes, fileName, mime)
        val title = fileName ?: ""
        return try {
            when (format) {
                DocumentFormat.DOCX -> DocxConverter(strings).convert(bytes, title)
                DocumentFormat.XLSX -> XlsxConverter(strings).convert(bytes, title)
                DocumentFormat.PPTX -> PptxConverter(strings).convert(bytes, title)
                DocumentFormat.CSV -> CsvConverter.convert(bytes, title, tab = fileName?.lowercase()?.endsWith(".tsv") == true)
                DocumentFormat.RTF -> RtfConverter(strings).convert(bytes, title)
                DocumentFormat.TXT -> PlainTextConverter.convert(bytes, title)
                DocumentFormat.DOC, DocumentFormat.XLS, DocumentFormat.PPT ->
                    message(title, format, strings.unsupportedTitle, strings.legacyMessage)
                DocumentFormat.UNKNOWN ->
                    message(title, format, strings.unsupportedTitle, strings.unknownMessage)
            }
        } catch (e: DocumentException) {
            message(title, format, strings.errorTitle, e.message ?: "")
        } catch (e: OutOfMemoryError) {
            message(title, format, strings.errorTitle, "Out of memory")
        } catch (e: Exception) {
            message(title, format, strings.errorTitle, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    private fun message(title: String, format: DocumentFormat, heading: String, text: String) = RenderResult(
        html = Html.page(title, "", "<div class=\"ov-msg\"><h2>${Html.esc(heading)}</h2><p>${Html.esc(text)}</p></div>"),
        format = format,
        warnings = listOf(text),
    )
}
