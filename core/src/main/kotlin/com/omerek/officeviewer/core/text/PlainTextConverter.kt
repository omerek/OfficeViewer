package com.omerek.officeviewer.core.text

import com.omerek.officeviewer.core.DocumentFormat
import com.omerek.officeviewer.core.Html
import com.omerek.officeviewer.core.RenderResult

object PlainTextConverter {
    fun convert(bytes: ByteArray, title: String): RenderResult {
        val text = TextDecoding.decode(bytes)
        val body = "<pre class=\"txt\">${Html.esc(text)}</pre>"
        val css = "html,body{background:#fff;}.txt{white-space:pre-wrap;word-wrap:break-word;margin:0;padding:16px;font-family:monospace;font-size:14px;}"
        return RenderResult(Html.page(title, css, body), DocumentFormat.TXT, 1)
    }
}
