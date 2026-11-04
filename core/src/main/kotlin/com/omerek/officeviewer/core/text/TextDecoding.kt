package com.omerek.officeviewer.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object TextDecoding {
    /** Decode bytes using BOM, then strict UTF-8, then Windows-1254 (Turkish) as the legacy fallback. */
    fun decode(bytes: ByteArray): String {
        fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xff else -1
        return when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            b(0) == 0xFF && b(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            b(0) == 0xFE && b(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                String(bytes, legacyCharset())
            }
        }
    }

    fun legacyCharset(): Charset =
        runCatching { Charset.forName("windows-1254") }.getOrElse { Charsets.ISO_8859_1 }
}
