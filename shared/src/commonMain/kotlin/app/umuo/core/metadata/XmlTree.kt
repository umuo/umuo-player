package app.umuo.core.metadata

import nl.adaptivity.xmlutil.*

internal data class XmlNode(val name: String, val ns: String, val attributes: Map<String, String>,
    val children: MutableList<XmlNode> = mutableListOf(), var text: String = "") {
    fun child(name: String) = children.firstOrNull { it.name == name }
    fun value(name: String) = child(name)?.text?.trim().orEmpty()
}
/** 只接受有限大小的资料 XML，不解析 DTD/外部实体。 */
internal fun parseXml(text: String): XmlNode {
    require(text.length <= 4 * 1024 * 1024) { "XML 资料超过 4 MiB" }
    require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "不支持 DTD 或自定义实体" }
    val reader = xmlStreaming.newReader(text)
    val stack = mutableListOf<XmlNode>()
    var root: XmlNode? = null
    try {
        while (reader.hasNext()) when (reader.next()) {
            EventType.START_ELEMENT -> {
                require(stack.size < 64) { "XML 嵌套过深" }
                val node = XmlNode(reader.localName, reader.namespaceURI,
                    (0 until reader.attributeCount).associate { reader.getAttributeLocalName(it) to reader.getAttributeValue(it) })
                stack.lastOrNull()?.children?.add(node)
                if (root == null) root = node
                stack.add(node)
            }
            EventType.END_ELEMENT -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF -> stack.lastOrNull()?.let { it.text += reader.text }
            else -> Unit
        }
    } finally { reader.close() }
    return requireNotNull(root) { "空 XML" }
}
