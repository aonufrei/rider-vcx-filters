package com.example.vcxfilters.model

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

object VcxParser {
    private val projectLine = Regex("""Project\("\{[^}]+\}"\)\s*=\s*"([^"]*)"\s*,\s*"([^"]*)"""")

    /** Item types in a .vcxproj that are not files shown in the explorer. */
    private val nonFileItems = setOf(
        "ProjectConfiguration", "ProjectReference", "Reference", "PackageReference", "COMReference",
        "Import", "Filter", "BuildMacro", "ClCompile_Excluded",
    )

    private val skippedDirs = setOf(".git", ".vs", ".idea", "node_modules", "build", "out", "bin", "obj")

    /** Parsed projects are shared between solutions and only re-read when one of their files changes. */
    private class Cached(val stamp: Long, val root: FilterDir)

    private val cache = ConcurrentHashMap<Path, Cached>()

    /** Finds every solution below [root] and nests each one under the closest solution in a parent directory. */
    fun findSolutions(root: Path): List<VcxSolution> {
        val slns = mutableListOf<Path>()
        val projects = mutableListOf<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.fileName.toString().lowercase() in skippedDirs) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val n = file.fileName.toString().lowercase()
                if (n.endsWith(".sln")) slns.add(file) else if (n.endsWith(".vcxproj")) projects.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })

        if (slns.isEmpty()) {
            return if (projects.isEmpty()) emptyList()
            else listOf(VcxSolution("(no solution)", null, projects.sorted().mapNotNull { parseProject(it, null) }))
        }
        return nest(slns.sorted().map { parseSolution(it) })
    }

    private fun nest(all: List<VcxSolution>): List<VcxSolution> {
        val roots = mutableListOf<VcxSolution>()
        for (s in all) {
            val dir = s.file!!.parent
            val parent = all
                .filter { c -> c !== s && c.file!!.parent != dir && dir.startsWith(c.file.parent) }
                .maxByOrNull { it.file!!.parent.nameCount }
            if (parent == null) roots += s else parent.children += s
        }
        return roots
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
        val displayName = name ?: vcxproj.fileName.toString().removeSuffix(".vcxproj")
        val filtersFile = vcxproj.resolveSibling(vcxproj.fileName.toString() + ".filters")
        val stamp = mtime(vcxproj) * 31 + mtime(filtersFile)
        cache[vcxproj]?.let { if (it.stamp == stamp) return VcxProject(displayName, vcxproj, it.root) }

        val root = FilterDir("")
        val dir = vcxproj.parent
        val placed = HashSet<Path>()

        fun add(item: Element, filter: String?) {
            val include = item.getAttribute("Include")
            if ('*' in include || '?' in include) return
            val file = dir.resolve(include.replace('\\', '/')).normalize()
            if (!placed.add(file)) return
            val target = if (filter.isNullOrBlank()) root else root.child(filter)
            target.files += FileEntry(file, Files.exists(file), item.tagName, include)
        }

        try {
            if (Files.isRegularFile(filtersFile)) {
                for (item in itemElements(filtersFile)) {
                    if (item.tagName == "Filter") root.child(item.getAttribute("Include"))
                    else add(item, childText(item, "Filter"))
                }
            }
            // Files that the .filters file does not mention live in the project root, as in Visual Studio.
            for (item in itemElements(vcxproj)) {
                if (item.tagName !in nonFileItems) add(item, null)
            }
        } catch (_: Exception) {
            return null
        }
        cache[vcxproj] = Cached(stamp, root)
        return VcxProject(displayName, vcxproj, root)
    }

    private fun mtime(p: Path): Long = try {
        Files.getLastModifiedTime(p).toMillis()
    } catch (_: IOException) {
        0L
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
