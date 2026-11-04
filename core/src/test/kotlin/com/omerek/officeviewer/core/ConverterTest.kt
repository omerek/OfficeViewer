package com.omerek.officeviewer.core

import com.omerek.officeviewer.core.xlsx.NumberFormatter
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConverterTest {

    private fun sample(name: String): ByteArray =
        javaClass.getResourceAsStream("/samples/$name")!!.use { it.readBytes() }

    private fun render(name: String, mime: String? = null): RenderResult {
        val r = DocumentConverter.render(sample(name), name, mime)
        // Keep the HTML around for manual inspection.
        val outDir = File(System.getProperty("ov.out", "build/samples-out"))
        outDir.mkdirs()
        File(outDir, "$name.html").writeText(r.html)
        return r
    }

    private fun assertNoScript(html: String) {
        // Only our own script blocks are allowed; document text must be escaped.
        assertFalse(html.contains("<script>alert"), "unescaped script")
    }

    @Test fun docx() {
        val r = render("sample.docx")
        assertEquals(DocumentFormat.DOCX, r.format)
        assertTrue(r.html.contains("Örnek Belge Başlığı"))
        assertTrue(r.html.contains("font-weight:bold"))
        assertTrue(r.html.contains("Birleştirilmiş hücre"))
        assertTrue(r.html.contains("colspan=\"2\""))
        assertTrue(r.html.contains("data:image/png;base64"))
        assertTrue(r.html.contains("•"), "bullet label")
        assertTrue(r.html.contains(">1.<"), "numbered label")
        assertTrue(r.html.contains("Üst bilgi metni"))
        assertEquals(2, r.sectionCount, "page break creates second page")
        assertNoScript(r.html)
    }

    @Test fun xlsx() {
        val r = render("sample.xlsx")
        assertEquals(DocumentFormat.XLSX, r.format)
        assertEquals(2, r.sectionCount)
        assertTrue(r.html.contains("Satışlar"))
        assertTrue(r.html.contains("Çanta"))
        assertTrue(r.html.contains("02.11.2026"), "date format")
        assertTrue(r.html.contains("colspan=\"3\""), "merged cells")
        assertTrue(r.html.contains("data:image/png;base64"), "anchored picture")
        assertTrue(r.html.contains("TRUE"))
    }

    @Test fun pptx() {
        val r = render("sample.pptx")
        assertEquals(DocumentFormat.PPTX, r.format)
        assertEquals(3, r.sectionCount)
        assertTrue(r.html.contains("Sunum Başlığı"))
        assertTrue(r.html.contains("Madde İşaretleri"))
        assertTrue(r.html.contains("border-radius:50%"), "ellipse")
        assertTrue(r.html.contains("data:image/png;base64"))
        assertTrue(r.html.contains("T22"))
    }

    @Test fun csv() {
        val r = render("sample.csv")
        assertEquals(DocumentFormat.CSV, r.format)
        assertTrue(r.html.contains("Ali, Veli"))
        assertTrue(r.html.contains("Ankara\nÇankaya"))
    }

    @Test fun rtf() {
        val r = render("sample.rtf")
        assertEquals(DocumentFormat.RTF, r.format)
        assertTrue(r.html.contains("RTF Başlığı"), r.html)
        assertTrue(r.html.contains("kırmızı"))
        assertTrue(r.html.contains("şı"))
        assertTrue(r.html.contains("<td>"))
        assertTrue(r.html.contains("B2"))
    }

    @Test fun legacyAndUnknown() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0)
        val r = DocumentConverter.render(ole, "old.xls", null)
        assertEquals(DocumentFormat.XLS, r.format)
        assertTrue(r.html.contains("ov-msg"))
        val bad = DocumentConverter.render(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3), "broken.docx", null)
        assertTrue(bad.html.contains("ov-msg"))
    }

    @Test fun escaping() {
        val r = DocumentConverter.render("<script>alert(1)</script>".toByteArray(), "x.txt", null)
        assertTrue(r.html.contains("&lt;script&gt;"))
        assertNoScript(r.html)
    }

    @Test fun numberFormats() {
        val f = NumberFormatter(Locale.US)
        assertEquals("1,234.50", f.formatCode(1234.5, "#,##0.00"))
        assertEquals("12.5%", f.formatCode(0.125, "0.0%"))
        assertEquals("15.03.2024", f.formatCode(45366.0, "dd.mm.yyyy"))
        assertEquals("13:30", f.formatCode(0.5625, "hh:mm"))
        assertEquals("(500)", f.formatCode(-500.0, "#,##0;(#,##0)"))
        assertEquals("42", f.general(42.0))
        assertEquals("0.1", f.general(0.1))
        assertEquals("$1,000.00", f.formatCode(1000.0, "[\$\$-409]#,##0.00"))
        val tr = NumberFormatter(Locale.forLanguageTag("tr-TR"))
        assertEquals("1.234,50", tr.formatCode(1234.5, "#,##0.00"))
    }

    @Test fun formatDetection() {
        assertEquals(DocumentFormat.RTF, DocumentFormat.detect("{\\rtf1 x}".toByteArray(), "a.doc", null))
        assertEquals(DocumentFormat.TXT, DocumentFormat.detect("hello".toByteArray(), null, null))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.detect(sample("sample.docx"), "file.bin", null))
        assertEquals(DocumentFormat.XLSX, DocumentFormat.detect(sample("sample.xlsx"), null, null))
        assertEquals(DocumentFormat.PPTX, DocumentFormat.detect(sample("sample.pptx"), null, null))
    }

    @Test fun extraFolderSmoke() {
        val dir = System.getProperty("ov.extra").orEmpty()
        if (dir.isEmpty()) return
        val outDir = File(System.getProperty("ov.out", "build/samples-out"), "extra").apply { mkdirs() }
        val failures = ArrayList<String>()
        File(dir).listFiles()!!.filter { it.isFile }.sortedBy { it.name }.forEach { f ->
            val start = System.nanoTime()
            val r = DocumentConverter.render(f.readBytes(), f.name, null)
            val ms = (System.nanoTime() - start) / 1_000_000
            File(outDir, f.name + ".html").writeText(r.html)
            println("${f.name}: ${r.format} sections=${r.sectionCount} ${ms}ms ${r.html.length / 1024}KB ${r.warnings.joinToString()}")
            if (r.html.contains("class=\"ov-msg\"><h2>")) failures += f.name + ": " + r.warnings
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
