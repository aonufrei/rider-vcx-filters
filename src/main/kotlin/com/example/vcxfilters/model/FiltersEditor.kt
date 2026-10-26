package com.example.vcxfilters.model

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Text
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringWriter

/**
 * Rewrites a .vcxproj.filters file so that files belong to another filter. It works on the DOM and keeps
 * everything it does not touch (comments, whitespace, BOM, line endings, the XML declaration).
 */
object FiltersEditor {
    private const val MSBUILD_NS = "http://schemas.microsoft.com/developer/msbuild/2003"
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** @param original current bytes of the .filters file, or null when the project has none yet. */
    fun moveFiles(original: ByteArray?, filtersFile: Path, files: List<FileEntry>, targetFilter: String): ByteArray {
        val text = original?.let { String(it, StandardCharsets.UTF_8).removePrefix("﻿") }
        val hasBom = original != null && original.size >= 3 && original.copyOfRange(0, 3).contentEquals(BOM)
        val crlf = text?.contains("\r\n") ?: true
        val declaration = text?.let { Regex("""^\s*<\?xml[^>]*\?>""").find(it)?.value?.trim() }
            ?: """<?xml version="1.0" encoding="utf-8"?>"""

        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().let { b ->
            if (text != null) b.parse(text.byteInputStream(StandardCharsets.UTF_8))
            else b.newDocument().also {
                it.appendChild(it.createElement("Project").apply {
                    setAttribute("ToolsVersion", "4.0")
                    setAttribute("xmlns", MSBUILD_NS)
                })
            }
        }
        val project = doc.documentElement
        val dir = filtersFile.parent

        val filter = targetFilter.trim('\\')
        if (filter.isNotEmpty()) ensureFilter(doc, project, filter)

        val byPath = HashMap<Path, Element>()
        for (group in childElements(project, "ItemGroup")) {
            for (item in childElements(group, null)) {
                if (item.tagName == "Filter" || !item.hasAttribute("Include")) continue
                byPath[dir.resolve(item.getAttribute("Include").replace('\\', '/')).normalize()] = item
            }
        }

        for (entry in files) {
            var item = byPath[entry.path]
            if (item == null) {
                if (filter.isEmpty()) continue
                item = doc.createElement(entry.itemType).apply { setAttribute("Include", entry.include) }
                val group = childElements(project, "ItemGroup").lastOrNull { g -> childElements(g, entry.itemType).isNotEmpty() }
                    ?: newItemGroup(doc, project, atStart = false)
                append(group, item, "    ", "  ")
            }
            setItemFilter(doc, item, filter)
        }

        val body = StringWriter().also {
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }.transform(DOMSource(doc.documentElement), StreamResult(it))
        }.toString()

        var out = declaration + "\n" + body
        if (crlf) out = out.replace("\r\n", "\n").replace("\n", "\r\n")
        val bytes = out.toByteArray(StandardCharsets.UTF_8)
        return if (hasBom || original == null) BOM + bytes else bytes
    }

    fun read(path: Path): ByteArray? = if (Files.isRegularFile(path)) Files.readAllBytes(path) else null

    private fun setItemFilter(doc: Document, item: Element, filter: String) {
        val existing = childElements(item, "Filter").firstOrNull()
        when {
            existing != null && filter.isEmpty() -> {
                val prev = existing.previousSibling
                if (prev is Text && prev.data.isBlank()) item.removeChild(prev)
                item.removeChild(existing)
                if (childElements(item, null).isEmpty()) while (item.firstChild != null) item.removeChild(item.firstChild)
            }
            existing != null -> existing.textContent = filter
            filter.isNotEmpty() -> append(item, doc.createElement("Filter").also { it.textContent = filter }, "      ", "    ")
        }
    }

    /** Defines [filter] and each of its parents if the file does not define them yet. */
    private fun ensureFilter(doc: Document, project: Element, filter: String) {
        val defined = childElements(project, "ItemGroup").flatMap { childElements(it, "Filter") }
            .map { it.getAttribute("Include").lowercase() }.toMutableSet()
        var group = childElements(project, "ItemGroup").firstOrNull { childElements(it, "Filter").isNotEmpty() }
        var prefix = ""
        for (part in filter.split('\\').filter { it.isNotEmpty() }) {
            prefix = if (prefix.isEmpty()) part else "$prefix\\$part"
            if (!defined.add(prefix.lowercase())) continue
            val el = doc.createElement("Filter").apply {
                setAttribute("Include", prefix)
                append(this, doc.createElement("UniqueIdentifier").also {
                    it.textContent = "{" + UUID.randomUUID().toString().lowercase() + "}"
                }, "      ", "    ")
            }
            if (group == null) group = newItemGroup(doc, project, atStart = true)
            append(group, el, "    ", "  ")
        }
    }

    private fun newItemGroup(doc: Document, project: Element, atStart: Boolean): Element {
        val group = doc.createElement("ItemGroup")
        val first = childElements(project, "ItemGroup").firstOrNull()
        if (atStart && first != null) {
            project.insertBefore(group, first)
            project.insertBefore(doc.createTextNode("\n  "), first)
        } else {
            append(project, group, "  ", "")
        }
        return group
    }

    /** Appends [child] as the last element child of [parent], formatted like Visual Studio does. */
    private fun append(parent: Element, child: Element, childIndent: String, closeIndent: String) {
        val last = parent.lastChild
        if (last is Text && last.data.isBlank()) {
            parent.insertBefore(parent.ownerDocument.createTextNode("\n$childIndent"), last)
            parent.insertBefore(child, last)
        } else {
            parent.appendChild(parent.ownerDocument.createTextNode("\n$childIndent"))
            parent.appendChild(child)
            parent.appendChild(parent.ownerDocument.createTextNode("\n$closeIndent"))
        }
    }

    private fun childElements(parent: Element, tag: String?): List<Element> {
        val out = mutableListOf<Element>()
        var n: Node? = parent.firstChild
        while (n != null) {
            if (n is Element && (tag == null || n.tagName == tag)) out += n
            n = n.nextSibling
        }
        return out
    }
}
