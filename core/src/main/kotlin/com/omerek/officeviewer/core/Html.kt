package com.omerek.officeviewer.core

import java.util.Base64

internal object Html {
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> if (ch.code < 0x20 && ch != '\n' && ch != '\t' && ch != '\r') {
                    // drop control characters
                } else sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Only allow link schemes that are safe to hand to the system. */
    fun safeHref(url: String): String? {
        val u = url.trim()
        val lower = u.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("mailto:") || lower.startsWith("tel:") || u.startsWith("#")
        ) u else null
    }

    fun mimeForImage(name: String): String? = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp", "dib" -> "image/bmp"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "tif", "tiff" -> null // not supported by WebView
        else -> null
    }

    /** Sniff the image type from magic bytes, falling back to the file extension. */
    fun dataUri(name: String, bytes: ByteArray): String? {
        val mime = sniff(bytes) ?: mimeForImage(name) ?: return null
        if (mime == "image/svg+xml") return null // SVG may carry script; skip and rely on PNG fallback
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private fun sniff(b: ByteArray): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xff else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x42 && at(1) == 0x4D -> "image/bmp"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 -> "image/webp"
            else -> null
        }
    }

    fun placeholder(label: String, widthPx: Int? = null, heightPx: Int? = null): String {
        val style = buildString {
            if (widthPx != null) append("width:${widthPx}px;")
            if (heightPx != null) append("height:${heightPx}px;")
        }
        return "<span class=\"ov-ph\" style=\"$style\">${esc(label)}</span>"
    }

    /** Shared page chrome. [body] must already be escaped HTML. */
    fun page(title: String, css: String, body: String, script: String = ""): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(esc(title)).append("</title><style>")
        append(BASE_CSS).append(css)
        append("</style></head><body>")
        append(body)
        if (script.isNotEmpty()) append("<script>").append(script).append("</script>")
        append("</body></html>")
    }

    private const val BASE_CSS = """
:root{color-scheme:light;}
html,body{margin:0;padding:0;background:#e8eaed;font-family:Calibri,Carlito,'Segoe UI',Roboto,Arial,sans-serif;color:#202124;}
.ov-ph{display:inline-flex;align-items:center;justify-content:center;min-width:48px;min-height:24px;background:#f1f3f4;border:1px dashed #9aa0a6;color:#5f6368;font-size:12px;padding:4px;box-sizing:border-box;}
.ov-msg{max-width:560px;margin:48px auto;padding:24px;background:#fff;border-radius:12px;box-shadow:0 1px 3px rgba(0,0,0,.2);font-size:16px;line-height:1.5;}
.ov-msg h2{margin-top:0;font-size:20px;}
.ov-warn{max-width:900px;margin:8px auto;padding:8px 12px;background:#fef7e0;border:1px solid #f9ab00;border-radius:8px;font-size:13px;}
"""
}
