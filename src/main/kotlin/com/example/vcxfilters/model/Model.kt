package com.example.vcxfilters.model

import java.nio.file.Path

class FileEntry(val path: Path, val exists: Boolean) {
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

class VcxProject(val name: String, val file: Path, val root: FilterDir)

class VcxSolution(val name: String, val file: Path?, val projects: List<VcxProject>)
