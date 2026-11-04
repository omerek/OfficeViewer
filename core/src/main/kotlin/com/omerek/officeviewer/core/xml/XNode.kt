package com.omerek.officeviewer.core.xml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/**
 * A tiny, namespace-aware DOM built on top of XmlPull (which Android ships in the platform).
 *
 * Elements are addressed by their local name, so documents that use unusual namespace
 * prefixes still parse. Attributes are stored by local name; attributes in the
 * officeDocument relationships namespace are additionally stored as "r:<local>".
 */
class XNode(
    val name: String,
    val attrs: Map<String, String>,
) {
    val children: MutableList<XNode> = ArrayList(2)

    /** Character data that appeared directly inside this element (concatenated). */
    var text: String = ""
        internal set

    operator fun get(attr: String): String? = attrs[attr]

    fun child(name: String): XNode? = children.firstOrNull { it.name == name }

    fun children(name: String): List<XNode> = children.filter { it.name == name }

    /** Follows a path of child names, e.g. node.path("pPr", "jc"). */
    fun path(vararg names: String): XNode? {
        var cur: XNode? = this
        for (n in names) cur = cur?.child(n) ?: return null
        return cur
    }

    /** Depth-first search for every descendant with the given name. */
    fun descendants(name: String): List<XNode> {
        val out = ArrayList<XNode>()
        fun walk(n: XNode) {
            for (c in n.children) {
                if (c.name == name) out += c
                walk(c)
            }
        }
        walk(this)
        return out
    }

    fun firstDescendant(name: String): XNode? {
        for (c in children) {
            if (c.name == name) return c
            c.firstDescendant(name)?.let { return it }
        }
        return null
    }

    /** All text of `t` descendants (used for shared strings / text bodies). */
    fun allText(textElement: String = "t"): String {
        val sb = StringBuilder()
        fun walk(n: XNode) {
            if (n.name == textElement) sb.append(n.text)
            for (c in n.children) walk(c)
        }
        walk(this)
        return sb.toString()
    }

    override fun toString(): String = "<$name ${attrs}>"

    companion object {
        private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val REL_NS_STRICT = "http://purl.oclc.org/ooxml/officeDocument/relationships"

        fun parse(bytes: ByteArray): XNode {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            // Never expand DOCTYPE / external entities (XXE hardening).
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            parser.setInput(ByteArrayInputStream(bytes), null)

            val root = XNode("#document", emptyMap())
            val stack = ArrayDeque<XNode>()
            stack.addLast(root)
            val textBuf = ArrayDeque<StringBuilder>()
            textBuf.addLast(StringBuilder())

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val count = parser.attributeCount
                        val attrs = if (count == 0) emptyMap() else HashMap<String, String>(count * 2)
                        for (i in 0 until count) {
                            val local = parser.getAttributeName(i)
                            val value = parser.getAttributeValue(i)
                            val ns = parser.getAttributeNamespace(i)
                            (attrs as HashMap)
                            if (ns == REL_NS || ns == REL_NS_STRICT) {
                                attrs["r:$local"] = value
                                attrs.putIfAbsent(local, value)
                            } else {
                                attrs[local] = value
                            }
                        }
                        val node = XNode(parser.name, attrs)
                        stack.last().children += node
                        stack.addLast(node)
                        textBuf.addLast(StringBuilder())
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        parser.text?.let { textBuf.last().append(it) }
                    }
                    XmlPullParser.END_TAG -> {
                        val node = stack.removeLast()
                        node.text = textBuf.removeLast().toString()
                    }
                }
                event = parser.nextToken()
            }
            return root.children.firstOrNull() ?: root
        }
    }
}
