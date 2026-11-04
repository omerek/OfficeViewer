package com.omerek.officeviewer.core

class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class DocumentFamily { WORD, EXCEL, POWERPOINT, TEXT, UNKNOWN }

enum class DocumentFormat(val family: DocumentFamily, val extensions: List<String>, val supported: Boolean) {
    DOCX(DocumentFamily.WORD, listOf("docx", "docm", "dotx", "dotm"), true),
    XLSX(DocumentFamily.EXCEL, listOf("xlsx", "xlsm", "xltx", "xltm"), true),
    PPTX(DocumentFamily.POWERPOINT, listOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm"), true),
    CSV(DocumentFamily.EXCEL, listOf("csv", "tsv"), true),
    RTF(DocumentFamily.WORD, listOf("rtf"), true),
    TXT(DocumentFamily.TEXT, listOf("txt", "log"), true),

    /** Legacy binary (OLE2/CFB) formats: planned for phase 2. */
    DOC(DocumentFamily.WORD, listOf("doc", "dot"), false),
    XLS(DocumentFamily.EXCEL, listOf("xls", "xlt"), false),
    PPT(DocumentFamily.POWERPOINT, listOf("ppt", "pot", "pps"), false),
    UNKNOWN(DocumentFamily.UNKNOWN, emptyList(), false);

    companion object {
        private val MIME = mapOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to DOCX,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template" to DOCX,
            "application/vnd.ms-word.document.macroenabled.12" to DOCX,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to XLSX,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template" to XLSX,
            "application/vnd.ms-excel.sheet.macroenabled.12" to XLSX,
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to PPTX,
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to PPTX,
            "application/vnd.openxmlformats-officedocument.presentationml.template" to PPTX,
            "application/vnd.ms-powerpoint.presentation.macroenabled.12" to PPTX,
            "text/csv" to CSV,
            "text/comma-separated-values" to CSV,
            "text/tab-separated-values" to CSV,
            "application/rtf" to RTF,
            "text/rtf" to RTF,
            "text/plain" to TXT,
            "application/msword" to DOC,
            "application/vnd.ms-excel" to XLS,
            "application/vnd.ms-powerpoint" to PPT,
        )

        /** MIME types the app registers for and offers in the file picker. */
        val PICKER_MIME_TYPES: Array<String> = (MIME.keys + "application/octet-stream").toTypedArray()

        fun fromExtension(name: String?): DocumentFormat {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (ext.isEmpty()) return UNKNOWN
            return entries.firstOrNull { ext in it.extensions } ?: UNKNOWN
        }

        fun fromMime(mime: String?): DocumentFormat = MIME[mime?.lowercase()] ?: UNKNOWN

        /**
         * Decide the format using file content first (most reliable), then extension, then MIME type.
         */
        fun detect(bytes: ByteArray, fileName: String?, mime: String?): DocumentFormat {
            val byName = fromExtension(fileName)
            val byMime = fromMime(mime)
            val hint = if (byName != UNKNOWN) byName else byMime
            fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xff else -1
            val isZip = b(0) == 0x50 && b(1) == 0x4B && b(2) == 0x03 && b(3) == 0x04
            val isOle = b(0) == 0xD0 && b(1) == 0xCF && b(2) == 0x11 && b(3) == 0xE0
            val isRtf = bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "{\\rtf"
            return when {
                isZip -> sniffZip(bytes) ?: hint.takeIf { it in listOf(DOCX, XLSX, PPTX) } ?: UNKNOWN
                isOle -> hint.takeIf { it in listOf(DOC, XLS, PPT) } ?: DOC
                isRtf -> RTF
                hint != UNKNOWN -> hint
                else -> if (looksLikeText(bytes)) TXT else UNKNOWN
            }
        }

        /** Peek at zip entry names (local headers) without inflating anything. */
        private fun sniffZip(bytes: ByteArray): DocumentFormat? {
            val head = String(bytes, 0, minOf(bytes.size, 256 * 1024), Charsets.ISO_8859_1)
            return when {
                head.contains("word/") -> DOCX
                head.contains("xl/") -> XLSX
                head.contains("ppt/") -> PPTX
                else -> null
            }
        }

        private fun looksLikeText(bytes: ByteArray): Boolean {
            val n = minOf(bytes.size, 4096)
            if (n == 0) return true
            var bad = 0
            for (i in 0 until n) {
                val c = bytes[i].toInt() and 0xff
                if (c == 0) return false
                if (c < 0x09 || (c in 0x0E..0x1F)) bad++
            }
            return bad * 20 < n
        }
    }
}
