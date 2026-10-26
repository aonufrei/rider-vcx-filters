package com.example.vcxfilters.model

import java.nio.charset.StandardCharsets

/**
 * Adds files to a .vcxproj. This edits the text instead of re-serializing the XML so that the diff in version
 * control is exactly the one new line (plus a new ItemGroup when the project has none of that item type yet).
 */
object ProjectEditor {
    private val itemGroup = Regex("""(?s)<ItemGroup(?:\s[^>]*?)?(?<!/)>(.*?)</ItemGroup>""")
    private val targetsImport = Regex("""(?m)^[ \t]*<Import\s+Project="\$\(VCTargetsPath\)\\Microsoft\.Cpp\.targets"""")

    fun itemTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "cpp", "c", "cc", "cxx", "c++", "cppm", "ixx" -> "ClCompile"
        "h", "hpp", "hh", "hxx", "h++", "inl", "ipp" -> "ClInclude"
        "rc" -> "ResourceCompile"
        else -> "None"
    }

    /** True when [bytes] is UTF-16, which this editor does not handle. */
    fun isUtf16(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())

    fun addItem(original: ByteArray, itemType: String, include: String): ByteArray {
        val text = String(original, StandardCharsets.UTF_8)
        val nl = if ("\r\n" in text) "\r\n" else "\n"
        val line = """<$itemType Include="${escape(include)}" />"""
        val sameType = Regex("""<$itemType[\s/>]""")

        // Labelled groups (ProjectConfigurations and the like) never hold sources.
        val group = itemGroup.findAll(text)
            .filter { !it.value.substringBefore('>').contains("Label=") }
            .lastOrNull { sameType.containsMatchIn(it.groupValues[1]) }

        val result = if (group != null) {
            val closeIdx = group.range.last + 1 - "</ItemGroup>".length
            val lineStart = text.lastIndexOf('\n', closeIdx - 1) + 1
            if (text.substring(lineStart, closeIdx).isBlank()) {
                val lastItem = sameType.findAll(group.groupValues[1]).last()
                val itemIdx = group.groups[1]!!.range.first + lastItem.range.first
                val indent = text.substring(text.lastIndexOf('\n', itemIdx - 1) + 1, itemIdx).takeIf { it.isBlank() } ?: "    "
                text.substring(0, lineStart) + indent + line + nl + text.substring(lineStart)
            } else {
                text.substring(0, closeIdx) + line + text.substring(closeIdx)
            }
        } else {
            val block = "  <ItemGroup>$nl    $line$nl  </ItemGroup>$nl"
            val at = targetsImport.find(text)?.range?.first
                ?: text.lastIndexOf("</Project>").takeIf { it >= 0 }
                ?: throw IllegalStateException("Not a project file")
            text.substring(0, at) + block + text.substring(at)
        }
        return result.toByteArray(StandardCharsets.UTF_8)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
