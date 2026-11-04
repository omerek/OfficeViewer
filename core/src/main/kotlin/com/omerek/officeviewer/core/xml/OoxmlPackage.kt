package com.omerek.officeviewer.core.xml

import com.omerek.officeviewer.core.DocumentException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** An unzipped OOXML (Office Open XML) package with relationship helpers. */
class OoxmlPackage private constructor(private val parts: Map<String, ByteArray>) {

    private val xmlCache = HashMap<String, XNode?>()
    private val relCache = HashMap<String, Map<String, Relationship>>()

    val partNames: Set<String> get() = parts.keys

    fun has(name: String) = parts.containsKey(normalize(name))

    fun bytes(name: String): ByteArray? = parts[normalize(name)]

    fun xml(name: String): XNode? = xmlCache.getOrPut(normalize(name)) {
        bytes(name)?.let { XNode.parse(it) }
    }

    /** Relationships of a part, keyed by id. Targets are resolved to absolute part names. */
    fun relationships(partName: String): Map<String, Relationship> {
        val part = normalize(partName)
        return relCache.getOrPut(part) {
            val dir = part.substringBeforeLast('/', "")
            val file = part.substringAfterLast('/')
            val relsName = if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
            val root = xml(relsName) ?: return@getOrPut emptyMap()
            root.children("Relationship").mapNotNull { r ->
                val id = r["Id"] ?: return@mapNotNull null
                val target = r["Target"] ?: return@mapNotNull null
                val external = r["TargetMode"] == "External"
                val resolved = if (external) target else resolve(dir, target)
                id to Relationship(id, r["Type"] ?: "", resolved, external)
            }.toMap()
        }
    }

    /** First relationship of a part whose type ends with the given suffix, e.g. "/officeDocument". */
    fun relationshipByType(partName: String, typeSuffix: String): Relationship? =
        relationships(partName).values.firstOrNull { it.type.endsWith(typeSuffix) }

    /** The main document part, found through the package root relationships. */
    fun mainPart(): String? = relationshipByType("", "/officeDocument")?.target

    data class Relationship(val id: String, val type: String, val target: String, val external: Boolean)

    companion object {
        /** Defensive limits against zip bombs. */
        private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000

        fun open(data: ByteArray): OoxmlPackage {
            val parts = HashMap<String, ByteArray>()
            var total = 0L
            try {
                ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        if (parts.size >= MAX_ENTRIES) throw DocumentException("Too many entries in package")
                        val out = ByteArrayOutputStream()
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_TOTAL_BYTES) throw DocumentException("Package is too large when uncompressed")
                            out.write(buf, 0, n)
                        }
                        parts[normalize(entry.name)] = out.toByteArray()
                    }
                }
            } catch (e: DocumentException) {
                throw e
            } catch (e: Exception) {
                throw DocumentException("Not a valid Office Open XML package", e)
            }
            if (parts.isEmpty()) throw DocumentException("Empty package")
            return OoxmlPackage(parts)
        }

        private fun normalize(name: String): String {
            var n = name.replace('\\', '/')
            while (n.startsWith("/")) n = n.substring(1)
            // Part names are case-insensitive in OPC; most producers use consistent case
            // but we lowercase-compare via a canonical lowercase key.
            return n.lowercase()
        }

        internal fun resolve(baseDir: String, target: String): String {
            if (target.startsWith("/")) return normalize(target)
            val segments = ArrayList<String>()
            if (baseDir.isNotEmpty()) segments.addAll(baseDir.split('/'))
            for (seg in target.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                    else -> segments.add(seg)
                }
            }
            return normalize(segments.joinToString("/"))
        }
    }
}
