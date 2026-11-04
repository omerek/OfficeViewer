package com.omerek.officeviewer.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.omerek.officeviewer.R
import com.omerek.officeviewer.core.DocumentConverter
import com.omerek.officeviewer.core.RenderResult
import com.omerek.officeviewer.core.RenderStrings
import java.io.ByteArrayOutputStream
import java.io.IOException

data class FileInfo(val name: String, val size: Long, val mime: String?)

object DocumentLoader {
    /** Hard limit to protect the app from running out of memory. */
    const val MAX_BYTES = 200L * 1024 * 1024
    const val WARN_BYTES = 40L * 1024 * 1024

    fun info(context: Context, uri: Uri): FileInfo {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return FileInfo(name ?: "document", size, runCatching { context.contentResolver.getType(uri) }.getOrNull())
    }

    fun read(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open stream")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IOException("File is larger than ${MAX_BYTES / (1024 * 1024)} MB")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    fun render(context: Context, bytes: ByteArray, info: FileInfo): RenderResult =
        DocumentConverter.render(bytes, info.name, info.mime, renderStrings(context))

    private fun renderStrings(c: Context) = RenderStrings(
        unsupportedTitle = c.getString(R.string.render_unsupported_title),
        legacyMessage = c.getString(R.string.render_legacy_message),
        unknownMessage = c.getString(R.string.render_unknown_message),
        errorTitle = c.getString(R.string.render_error_title),
        image = c.getString(R.string.render_image),
        unsupportedImage = c.getString(R.string.render_unsupported_image),
        chart = c.getString(R.string.render_chart),
        // Keep the raw format placeholders; the converter fills them in.
        sheetTruncated = c.getString(R.string.render_sheet_truncated).replace("%%", "%"),
        slide = c.getString(R.string.render_slide),
        emptyDocument = c.getString(R.string.render_empty),
    )
}
