package com.example.vcxfilters.ui

import com.example.vcxfilters.model.FileEntry
import com.example.vcxfilters.model.FilterDir
import com.example.vcxfilters.model.FiltersEditor
import com.example.vcxfilters.model.ProjectEditor
import com.example.vcxfilters.model.VcxParser
import com.example.vcxfilters.model.VcxProject
import com.example.vcxfilters.model.VcxSolution
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.tree.TreeUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DropMode
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * What a tree node represents. [key] identifies the node across reloads so expansion can be restored; it is
 * computed on demand because a project can have thousands of files and most keys are never needed.
 */
private sealed class Item {
    abstract val key: String

    class Root : Item() {
        override val key = ""
    }

    class Solution(val sln: VcxSolution, private val parent: Item) : Item() {
        override val key by lazy { parent.key + "/sln:" + (sln.file ?: sln.name) }
    }

    class Project(val project: VcxProject, private val parent: Item) : Item() {
        override val key by lazy { parent.key + "/prj:" + project.name }
    }

    class Dir(val dir: FilterDir, private val parent: Item) : Item() {
        override val key by lazy { parent.key + "/dir:" + dir.name }
    }

    class File(val entry: FileEntry, private val parent: Item) : Item() {
        override val key by lazy { parent.key + "/file:" + entry.name }
    }
}

class FiltersPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private var root = DefaultMutableTreeNode(Item.Root())
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** Disk scan results; walking a big tree is slow, so it only repeats when solution or project files come and go. */
    @Volatile private var lastScan: VcxParser.Scan? = null
    @Volatile private var needRescan = true
    @Volatile private var generation = 0

    private var loaded = false

    /** First tree node for each file path, used by "Select Opened File". */
    private var fileNodes = HashMap<Path, DefaultMutableTreeNode>()
    private val pendingExpand = HashSet<String>()
    private var pendingSelect: Path? = null
    private var dragged: List<DefaultMutableTreeNode> = emptyList()
    private var autoscroll: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(AUTOSCROLL_KEY, false)
        set(v) = PropertiesComponent.getInstance(project).setValue(AUTOSCROLL_KEY, v, false)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ItemRenderer()
        tree.emptyText.text = "Scanning for solutions..."
        tree.dragEnabled = true
        tree.dropMode = DropMode.ON
        tree.transferHandler = MoveHandler()
        TreeSpeedSearch.installOn(tree, true) { path -> nodeText(path.lastPathComponent as DefaultMutableTreeNode) }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) openSelected()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
            }
        })
        PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(
                OpenAction(),
                NodeAction("New File...", AllIcons.General.Add, { it is Item.Project || it is Item.Dir || it is Item.File }) { newFile() },
                NodeAction("New Filter...", AllIcons.General.Add, { it is Item.Project || it is Item.Dir }) { newFilter() },
                NodeAction("Rename Filter...", null, { it is Item.Dir }) { renameFilter() },
                NodeAction("Remove Filter", AllIcons.General.Remove, { it is Item.Dir }) { removeFilter() },
            ),
            "VcxFiltersPopup",
        )

        setContent(ScrollPaneFactory.createScrollPane(tree))
        val group = DefaultActionGroup(
            object : AnAction("Refresh", "Re-read solutions and filters", AllIcons.Actions.Refresh), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = scheduleReload(0, rescan = true)
            },
            object : AnAction("Select Opened File", "Locate the file open in the editor", AllIcons.General.Locate), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                    if (vf == null || !selectFile(vf)) {
                        JBPopupFactory.getInstance()
                            .createHtmlTextBalloonBuilder("The opened file is not part of any project in the tree", MessageType.INFO, null)
                            .createBalloon().showInCenterOf(tree)
                    }
                }
            },
            object : ToggleAction("Always Select Opened File", "Follow the editor tab", AllIcons.General.AutoscrollFromSource), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = autoscroll
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    autoscroll = state
                    if (state) FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let { selectFile(it) }
                }
            },
            object : AnAction("Expand All", null, AllIcons.Actions.Expandall), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
            },
            object : AnAction("Collapse All", null, AllIcons.Actions.Collapseall), DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) {
                    TreeUtil.collapseAll(tree, 1)
                }
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("VcxFilters", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                var relevant = false
                var rescan = false
                for (e in events) {
                    if (!isRelevant(e.path)) continue
                    relevant = true
                    // Edits of an existing file only need a re-parse; created, deleted or renamed files need a new scan.
                    if (e !is VFileContentChangeEvent) rescan = true
                }
                if (relevant) scheduleReload(500, rescan)
            }
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (autoscroll) event.newFile?.let { selectFile(it) }
            }
        })
        scheduleReload(0, rescan = true)
    }

    private fun isRelevant(path: String): Boolean =
        path.endsWith(".sln", ignoreCase = true) || path.endsWith(".vcxproj", ignoreCase = true) ||
            path.endsWith(".vcxproj.filters", ignoreCase = true)

    private fun scheduleReload(delayMs: Int, rescan: Boolean = false) {
        val base = project.basePath ?: return
        if (rescan) needRescan = true
        alarm.cancelAllRequests()
        alarm.addRequest({
            val gen = ++generation
            try {
                // Everything expensive, including creating the tree nodes, happens off the UI thread.
                val scan = lastScan.takeIf { !needRescan } ?: VcxParser.scan(Path.of(base)).also {
                    needRescan = false
                    lastScan = it
                }
                val solutions = VcxParser.build(scan)
                val newRoot = DefaultMutableTreeNode(Item.Root())
                val files = HashMap<Path, DefaultMutableTreeNode>()
                for (sln in solutions) addSolution(newRoot, sln, newRoot.userObject as Item, files)
                ApplicationManager.getApplication().invokeLater(
                    { if (gen == generation) applyTree(newRoot, files) },
                    ModalityState.any(), project.disposed,
                )
            } catch (e: Exception) {
                thisLogger().warn("Could not load solutions", e)
            }
        }, delayMs)
    }

    private fun applyTree(newRoot: DefaultMutableTreeNode, newFileNodes: HashMap<Path, DefaultMutableTreeNode>) {
        val expanded = HashSet<String>(pendingExpand)
        pendingExpand.clear()
        for (i in 0 until tree.rowCount) {
            val path = tree.getPathForRow(i)
            if (tree.isExpanded(path)) expanded += keyOf(path.lastPathComponent as DefaultMutableTreeNode)
        }
        val selected = tree.selectionPaths?.map { keyOf(it.lastPathComponent as DefaultMutableTreeNode) }?.toSet().orEmpty()

        root = newRoot
        fileNodes = newFileNodes
        model.setRoot(newRoot)
        tree.emptyText.text = "No Visual Studio solutions or projects found"

        if (!loaded) {
            loaded = true
            // Default view: solutions and projects open, filters collapsed.
            TreeUtil.expand(tree, 2)
        } else {
            val toSelect = mutableListOf<TreePath>()
            restore(root, expanded, selected, toSelect)
            if (toSelect.isNotEmpty()) tree.selectionPaths = toSelect.toTypedArray()
        }
        pendingSelect?.let {
            pendingSelect = null
            fileNodes[it]?.let { node -> TreeUtil.selectPath(tree, TreePath(node.path)) }
        }
    }

    private fun addSolution(
        parent: DefaultMutableTreeNode, sln: VcxSolution, parentItem: Item, files: HashMap<Path, DefaultMutableTreeNode>,
    ) {
        val item = Item.Solution(sln, parentItem)
        val node = DefaultMutableTreeNode(item)
        parent.add(node)
        for (prj in sln.projects) {
            val pItem = Item.Project(prj, item)
            val pNode = DefaultMutableTreeNode(pItem)
            node.add(pNode)
            fill(pNode, prj.root, pItem, files)
        }
        for (child in sln.children) addSolution(node, child, item, files)
    }

    private fun fill(parent: DefaultMutableTreeNode, dir: FilterDir, parentItem: Item, files: HashMap<Path, DefaultMutableTreeNode>) {
        for (child in dir.dirs.values) {
            val item = Item.Dir(child, parentItem)
            val node = DefaultMutableTreeNode(item)
            parent.add(node)
            fill(node, child, item, files)
        }
        for (f in dir.files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })) {
            val node = DefaultMutableTreeNode(Item.File(f, parentItem))
            parent.add(node)
            files.putIfAbsent(f.path, node)
        }
    }

    /** Re-expands and re-selects nodes. Only the children of expanded nodes are visited. */
    private fun restore(node: DefaultMutableTreeNode, expanded: Set<String>, selected: Set<String>, out: MutableList<TreePath>) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val key = keyOf(child)
            if (key in selected) out += TreePath(child.path)
            if (key in expanded) {
                tree.expandPath(TreePath(child.path))
                restore(child, expanded, selected, out)
            }
        }
    }

    private fun keyOf(node: DefaultMutableTreeNode) = (node.userObject as Item).key

    private fun nodeText(node: DefaultMutableTreeNode): String = when (val i = node.userObject) {
        is Item.Solution -> i.sln.name
        is Item.Project -> i.project.name
        is Item.Dir -> i.dir.name
        is Item.File -> i.entry.name
        else -> ""
    }

    private fun selectedFile(): FileEntry? =
        ((tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Item.File)?.entry

    private fun openSelected() {
        val entry = selectedFile() ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.path) ?: return
        OpenFileDescriptor(project, vf).navigate(true)
    }

    /** Selects [vf] in the tree. Returns false when no project lists it. */
    private fun selectFile(vf: VirtualFile): Boolean {
        val path = try {
            vf.toNioPath().normalize()
        } catch (_: Exception) {
            return false
        }
        val node = fileNodes[path] ?: return false
        TreeUtil.selectPath(tree, TreePath(node.path))
        return true
    }

    private fun projectNodeOf(node: DefaultMutableTreeNode): DefaultMutableTreeNode? {
        var n: DefaultMutableTreeNode? = node
        while (n != null && n.userObject !is Item.Project) n = n.parent as? DefaultMutableTreeNode
        return n
    }

    /** Filter name of a Dir node ("A\B"), "" for the project node. */
    private fun filterPathOf(node: DefaultMutableTreeNode): String {
        val parts = mutableListOf<String>()
        var n: DefaultMutableTreeNode? = node
        while (n != null && n.userObject is Item.Dir) {
            parts += (n.userObject as Item.Dir).dir.name
            n = n.parent as? DefaultMutableTreeNode
        }
        return parts.asReversed().joinToString("\\")
    }

    /** Resolves what a drop on [node] means: files are dropped into the filter they sit in. */
    private fun dropTarget(node: DefaultMutableTreeNode): DefaultMutableTreeNode? = when (node.userObject) {
        is Item.Dir, is Item.Project -> node
        is Item.File -> node.parent as? DefaultMutableTreeNode
        else -> null
    }

    private fun moveFiles(files: List<DefaultMutableTreeNode>, target: DefaultMutableTreeNode) {
        val prj = projectOf(target) ?: return
        val entries = files.map { (it.userObject as Item.File).entry }
        val filter = filterPathOf(target)
        editFilters(prj, expand = target) { FiltersEditor.moveFiles(it, prj.filtersFile, entries, filter) }
    }

    /** Sub-filters of a Project or Dir node, keyed case-insensitively. */
    private fun childDirs(node: DefaultMutableTreeNode): Map<String, FilterDir> = when (val i = node.userObject) {
        is Item.Project -> i.project.root.dirs
        is Item.Dir -> i.dir.dirs
        else -> emptyMap()
    }

    private fun moveFilters(dirs: List<DefaultMutableTreeNode>, target: DefaultMutableTreeNode) {
        val prj = projectOf(target) ?: return
        val targetPath = filterPathOf(target)
        val moves = dirs.map { filterPathOf(it) to (it.userObject as Item.Dir).dir.name }
        editFilters(prj, expand = target) { original ->
            var bytes = original
            for ((old, name) in moves) {
                bytes = FiltersEditor.renameFilter(bytes, old, if (targetPath.isEmpty()) name else "$targetPath\\$name")
            }
            bytes!!
        }
    }

    private fun projectOf(node: DefaultMutableTreeNode): VcxProject? =
        (projectNodeOf(node)?.userObject as? Item.Project)?.project

    private fun showError(message: String) {
        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(message, MessageType.ERROR, null)
            .createBalloon().showInCenterOf(tree)
    }

    /** Rewrites the project's .filters file with [edit], then reloads the tree and opens [expand]. */
    private fun editFilters(prj: VcxProject, expand: DefaultMutableTreeNode?, edit: (ByteArray?) -> ByteArray) {
        val filtersFile = prj.filtersFile
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filtersFile)
            val original = if (vf != null) vf.contentsToByteArray() else FiltersEditor.read(filtersFile)
            val updated = edit(original)
            WriteAction.run<Exception> {
                if (vf != null) vf.setBinaryContent(updated)
                else Files.write(filtersFile, updated)
            }
            if (vf == null) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filtersFile)
        } catch (e: Exception) {
            showError("Could not update ${filtersFile.fileName}: ${e.message}")
            return
        }
        if (expand != null) pendingExpand += keyOf(expand)
        scheduleReload(0)
    }

    private fun selectedNode(): DefaultMutableTreeNode? = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun validateFilterName(parent: DefaultMutableTreeNode, name: String, self: FilterDir?): String? {
        return when {
            name.isBlank() -> "The name must not be empty"
            name.any { it in "\\/:*?\"<>|" } -> "The name must not contain \\ / : * ? \" < > |"
            childDirs(parent)[name]?.let { it !== self } == true -> "A filter named '$name' already exists here"
            else -> null
        }
    }

    private fun newFilter() {
        val node = selectedNode()?.takeIf { it.userObject is Item.Project || it.userObject is Item.Dir } ?: return
        val prj = projectOf(node) ?: return
        val parentPath = filterPathOf(node)
        val name = Messages.showInputDialog(
            project, "Filter name:", "New Filter", null, "",
            object : InputValidator {
                override fun checkInput(input: String) = validateFilterName(node, input.trim(), null) == null
                override fun canClose(input: String) = checkInput(input)
            },
        )?.trim() ?: return
        val full = if (parentPath.isEmpty()) name else "$parentPath\\$name"
        editFilters(prj, expand = node) { FiltersEditor.addFilter(it, full) }
    }

    private fun renameFilter() {
        val node = selectedNode()?.takeIf { it.userObject is Item.Dir } ?: return
        val dir = (node.userObject as Item.Dir).dir
        val parent = node.parent as DefaultMutableTreeNode
        val prj = projectOf(node) ?: return
        val old = filterPathOf(node)
        val name = Messages.showInputDialog(
            project, "New name for '${dir.name}':", "Rename Filter", null, dir.name,
            object : InputValidator {
                override fun checkInput(input: String) = validateFilterName(parent, input.trim(), dir) == null
                override fun canClose(input: String) = checkInput(input)
            },
        )?.trim() ?: return
        if (name == dir.name) return
        val parentPath = filterPathOf(parent)
        val new = if (parentPath.isEmpty()) name else "$parentPath\\$name"
        editFilters(prj, expand = parent) { FiltersEditor.renameFilter(it, old, new) }
    }

    private fun removeFilter() {
        val node = selectedNode()?.takeIf { it.userObject is Item.Dir } ?: return
        val dir = (node.userObject as Item.Dir).dir
        val prj = projectOf(node) ?: return
        val where = if (filterPathOf(node.parent as DefaultMutableTreeNode).isEmpty()) "the project root" else "the parent filter"
        val answer = Messages.showYesNoDialog(
            project,
            "Remove the filter '${dir.name}'? Its files and sub-filters move to $where. No files are deleted from disk.",
            "Remove Filter", Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        val name = filterPathOf(node)
        editFilters(prj, expand = node.parent as DefaultMutableTreeNode) { FiltersEditor.removeFilter(it, name) }
    }

    /**
     * Creates a file on disk and adds it to the project and to the selected filter, like Visual Studio's Add New Item.
     * The folder defaults to the one that holds the filter's existing files, otherwise the project folder.
     */
    private fun newFile() {
        val node = selectedNode()?.let { dropTarget(it) } ?: return
        val prj = projectOf(node) ?: return
        val projectDir = prj.file.parent
        val filter = filterPathOf(node)

        val fileDirs = (0 until node.childCount)
            .mapNotNull { ((node.getChildAt(it) as DefaultMutableTreeNode).userObject as? Item.File)?.entry?.path?.parent }
            .distinct()
        val baseDir = fileDirs.singleOrNull() ?: projectDir
        val shownDir = try {
            projectDir.relativize(baseDir).toString().ifEmpty { "." }
        } catch (_: IllegalArgumentException) {
            baseDir.toString()
        }
        val title = if (filter.isEmpty()) "New File in ${prj.name}" else "New File in $filter"

        val name = Messages.showInputDialog(
            project, "File name (relative to '$shownDir', may include sub-folders):", title, null, "",
            object : InputValidator {
                private fun error(input: String): String? {
                    val parts = input.trim().split('/', '\\')
                    return when {
                        input.isBlank() || parts.any { it.isBlank() } -> "empty"
                        parts.any { it == ".." || it.any { c -> c in ":*?\"<>|" } } -> "invalid"
                        Files.exists(baseDir.resolve(input.trim().replace('\\', '/'))) -> "exists"
                        else -> null
                    }
                }

                override fun checkInput(input: String) = error(input) == null
                override fun canClose(input: String) = checkInput(input)
            },
        )?.trim() ?: return

        val file = baseDir.resolve(name.replace('\\', '/')).normalize()
        val include = try {
            projectDir.relativize(file).toString().replace('/', '\\')
        } catch (_: IllegalArgumentException) {
            showError("The file must be on the same drive as the project")
            return
        }
        val itemType = ProjectEditor.itemTypeFor(file.fileName.toString())

        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            val fs = LocalFileSystem.getInstance()
            val projectVf = fs.refreshAndFindFileByNioFile(prj.file) ?: throw IllegalStateException("Project file not found")
            val projectBytes = projectVf.contentsToByteArray()
            if (ProjectEditor.isUtf16(projectBytes)) throw IllegalStateException("UTF-16 project files are not supported")
            // Both edits are computed before anything is written, so a failure leaves everything untouched.
            val newProjectBytes = ProjectEditor.addItem(projectBytes, itemType, include)

            val filtersPath = prj.filtersFile
            val filtersVf = fs.refreshAndFindFileByNioFile(filtersPath)
            val filtersBytes = if (filter.isEmpty()) null else {
                val original = if (filtersVf != null) filtersVf.contentsToByteArray() else FiltersEditor.read(filtersPath)
                FiltersEditor.moveFiles(original, filtersPath, listOf(FileEntry(file, true, itemType, include)), filter)
            }

            var created: VirtualFile? = null
            WriteAction.run<Exception> {
                val dir = VfsUtil.createDirectoryIfMissing(file.parent.toString().replace('\\', '/'))
                    ?: throw IllegalStateException("Could not create ${file.parent}")
                val vf = dir.createChildData(this, file.fileName.toString())
                if (itemType == "ClInclude" && !file.fileName.toString().endsWith(".inl", ignoreCase = true)) {
                    vf.setBinaryContent("#pragma once\n\n".toByteArray())
                }
                created = vf
                projectVf.setBinaryContent(newProjectBytes)
                if (filtersBytes != null) {
                    if (filtersVf != null) filtersVf.setBinaryContent(filtersBytes) else Files.write(filtersPath, filtersBytes)
                }
            }
            pendingSelect = file
            created?.let { OpenFileDescriptor(project, it).navigate(true) }
        } catch (e: Exception) {
            showError("Could not create $name: ${e.message}")
            return
        }
        pendingExpand += keyOf(node)
        scheduleReload(0, rescan = true)
    }

    private inner class MoveHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent) = MOVE

        override fun createTransferable(c: JComponent): Transferable? {
            val nodes = tree.selectionPaths?.map { it.lastPathComponent as DefaultMutableTreeNode }.orEmpty()
            val files = nodes.all { it.userObject is Item.File }
            if (nodes.isEmpty() || !(files || nodes.all { it.userObject is Item.Dir })) return null
            // Moving between projects would mean editing two projects; only one at a time is supported.
            if (nodes.map { projectNodeOf(it) }.distinct().size != 1) return null
            // A filter dragged together with one of its parents moves along with that parent.
            dragged = if (files) nodes else nodes.filter { n -> nodes.none { o -> o !== n && n.isNodeAncestor(o) } }
            return StringSelection(dragged.joinToString("\n") { nodeText(it) })
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop || !support.isDataFlavorSupported(DataFlavor.stringFlavor) || dragged.isEmpty()) return false
            val path = (support.dropLocation as? JTree.DropLocation)?.path ?: return false
            val target = dropTarget(path.lastPathComponent as DefaultMutableTreeNode) ?: return false
            if (projectNodeOf(target) !== projectNodeOf(dragged.first())) return false
            if (dragged.first().userObject is Item.File) return true

            // Filters cannot move into themselves, must have a free name there, and must actually change place.
            if (dragged.any { it === target || target.isNodeAncestor(it) }) return false
            val movable = dragged.filter { it.parent !== target }
            val names = movable.map { (it.userObject as Item.Dir).dir.name.lowercase() }
            return movable.isNotEmpty() && names.distinct().size == names.size && names.none { childDirs(target).containsKey(it) }
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val path = (support.dropLocation as JTree.DropLocation).path ?: return false
            val target = dropTarget(path.lastPathComponent as DefaultMutableTreeNode) ?: return false
            val nodes = dragged.filter { it.parent !== target }
            dragged = emptyList()
            if (nodes.isEmpty()) return true
            if (nodes.first().userObject is Item.File) moveFiles(nodes, target) else moveFilters(nodes, target)
            return true
        }

        override fun exportDone(source: JComponent, data: Transferable?, action: Int) {
            dragged = emptyList()
        }
    }

    private inner class OpenAction : AnAction("Open"), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedFile() != null
        }

        override fun actionPerformed(e: AnActionEvent) = openSelected()
    }

    /** A popup action shown only when the selected node satisfies [applies]. */
    private inner class NodeAction(
        text: String, icon: Icon?, private val applies: (Item) -> Boolean, private val run: () -> Unit,
    ) : AnAction(text, null, icon), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val item = selectedNode()?.userObject as? Item
            e.presentation.isEnabledAndVisible = item != null && applies(item)
        }

        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private inner class ItemRenderer : NodeRenderer() {
        private val fileTypes = FileTypeManager.getInstance()

        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is Item.Solution -> {
                    icon = AllIcons.Nodes.ModuleGroup
                    append(item.sln.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is Item.Project -> {
                    icon = AllIcons.Nodes.Module
                    append(item.project.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is Item.Dir -> {
                    icon = AllIcons.Nodes.Folder
                    append(item.dir.name)
                }
                is Item.File -> {
                    // The icon comes from the file name alone; looking each file up in the VFS while painting is slow.
                    icon = fileTypes.getFileTypeByFileName(item.entry.name).icon
                    append(
                        item.entry.name,
                        if (item.entry.exists) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.ERROR_ATTRIBUTES,
                    )
                }
                else -> {}
            }
        }
    }

    override fun dispose() {}

    private companion object {
        const val AUTOSCROLL_KEY = "vcxfilters.autoscroll"
    }
}
