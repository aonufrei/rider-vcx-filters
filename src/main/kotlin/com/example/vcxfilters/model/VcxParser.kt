package com.example.vcxfilters.model

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

object VcxParser {
    private val projectLine = Regex("""Project\("\{[^}]+\}"\)\s*=\s*"([^"]*)"\s*,\s*"([^"]*)"""")

    /** Item types in a .vcxproj that are not files shown in the explorer. */
    private val nonFileItems = setOf(
        "ProjectConfiguration", "ProjectReference", "Reference", "PackageReference", "COMReference",
        "Import", "Filter", "BuildMacro", "ClCompile_Excluded",
    )

    private val skippedDirs = setOf(".git", ".vs", ".idea", "node_modules", "build", "out", "bin", "obj")

    fun findSolutions(root: Path, maxDepth: Int = 4): List<VcxSolution> {
        val slns = mutableListOf<Path>()
        val projects = mutableListOf<Path>()
        walk(root, maxDepth) { p ->
            val n = p.fileName.toString().lowercase()
            if (n.endsWith(".sln")) {
                slns.add(p)
            } else if (n.endsWith(".vcxproj")) {
                projects.add(p)
            }
        }
        val result = slns.sorted().map { parseSolution(it) }.toMutableList()
        if (result.isEmpty() && projects.isNotEmpty()) {
            result += VcxSolution("(no solution)", null, projects.sorted().mapNotNull { parseProject(it, null) })
        }
        return result
    }

    private fun walk(dir: Path, depth: Int, visit: (Path) -> Unit) {
        val entries = try {
            Files.newDirectoryStream(dir).use { it.toList() }
        } catch (_: Exception) {
            return
        }
        for (p in entries) {
            if (Files.isDirectory(p)) {
                if (depth > 0 && p.fileName.toString().lowercase() !in skippedDirs) walk(p, depth - 1, visit)
            } else visit(p)
        }
    }

    fun parseSolution(sln: Path): VcxSolution {
        val dir = sln.parent
        val projects = try {
            Files.readAllLines(sln).mapNotNull { line ->
                val m = projectLine.find(line) ?: return@mapNotNull null
                val rel = m.groupValues[2]
                if (!rel.endsWith(".vcxproj", ignoreCase = true)) return@mapNotNull null
                val file = dir.resolve(rel.replace('\\', '/')).normalize()
                if (!Files.isRegularFile(file)) null else parseProject(file, m.groupValues[1])
            }
        } catch (_: Exception) {
            emptyList()
        }
        return VcxSolution(sln.fileName.toString().removeSuffix(".sln"), sln, projects)
    }

    fun parseProject(vcxproj: Path, name: String?): VcxProject? {
        val dir = vcxproj.parent
        val root = FilterDir("")
        val placed = HashSet<Path>()

        fun add(include: String, filter: String?) {
            if ('*' in include || '?' in include) return
            val file = dir.resolve(include.replace('\\', '/')).normalize()
            if (!placed.add(file)) return
            val target = if (filter.isNullOrBlank()) root else root.child(filter)
            target.files += FileEntry(file, Files.exists(file))
        }

        try {
            val filtersFile = vcxproj.resolveSibling(vcxproj.fileName.toString() + ".filters")
            if (Files.isRegularFile(filtersFile)) {
                for (item in itemElements(filtersFile)) {
                    val include = item.getAttribute("Include")
                    if (item.tagName == "Filter") {
                        root.child(include)
                    } else {
                        add(include, childText(item, "Filter"))
                    }
                }
            }
            // Files that the .filters file does not mention live in the project root, as in Visual Studio.
            for (item in itemElements(vcxproj)) {
                if (item.tagName in nonFileItems) continue
                add(item.getAttribute("Include"), null)
            }
        } catch (_: Exception) {
            return null
        }
        return VcxProject(name ?: vcxproj.fileName.toString().removeSuffix(".vcxproj"), vcxproj, root)
    }

    private fun itemElements(xml: Path): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        val doc = Files.newInputStream(xml).use { factory.newDocumentBuilder().parse(it) }
        val out = mutableListOf<Element>()
        for (group in children(doc.documentElement, "ItemGroup")) {
            for (item in children(group, null)) {
                if (item.hasAttribute("Include") && item.getAttribute("Include").isNotBlank()) out += item
            }
        }
        return out
    }

    private fun children(parent: Element, tag: String?): List<Element> {
        val out = mutableListOf<Element>()
        var n: Node? = parent.firstChild
        while (n != null) {
            if (n is Element && (tag == null || n.tagName == tag)) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun childText(parent: Element, tag: String): String? =
        children(parent, tag).firstOrNull()?.textContent?.trim()
}
