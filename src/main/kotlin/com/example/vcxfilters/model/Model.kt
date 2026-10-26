package com.example.vcxfilters.model

import java.nio.file.Path

/**
 * A file listed in a project. [itemType] (ClCompile, ClInclude, ...) and [include] (the raw Include attribute)
 * are kept so the file can be re-created in the .filters file when it is moved to another filter.
 */
class FileEntry(val path: Path, val exists: Boolean, val itemType: String, val include: String) {
    val name: String get() = path.fileName?.toString() ?: path.toString()
}

/** A filter (virtual folder). Children are kept sorted the way Visual Studio shows them: folders first. */
class FilterDir(val name: String) {
    val dirs = sortedMapOf<String, FilterDir>(String.CASE_INSENSITIVE_ORDER)
    val files = mutableListOf<FileEntry>()

    fun child(path: String): FilterDir {
        var cur = this
        for (part in path.split('\\', '/').filter { it.isNotEmpty() }) {
            cur = cur.dirs.getOrPut(part) { FilterDir(part) }
        }
        return cur
    }
}

class VcxProject(val name: String, val file: Path, val root: FilterDir) {
    /** The .vcxproj.filters file next to the project; it may not exist yet. */
    val filtersFile: Path get() = file.resolveSibling(file.fileName.toString() + ".filters")
}

/** [children] are solutions located in subdirectories of this solution's directory. */
class VcxSolution(val name: String, val file: Path?, val projects: List<VcxProject>) {
    val children = mutableListOf<VcxSolution>()
}
