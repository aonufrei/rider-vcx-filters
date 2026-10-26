package com.example.vcxfilters.ui

import com.example.vcxfilters.model.FileEntry
import com.example.vcxfilters.model.FilterDir
import com.example.vcxfilters.model.FiltersEditor
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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.IconUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DropMode
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** What a tree node represents. [key] identifies the node across reloads so expansion can be restored. */
private sealed class Item(val key: String) {
    class Root : Item("")
    class Solution(val sln: VcxSolution, parentKey: String) : Item("$parentKey/sln:" + (sln.file ?: sln.name))
    class Project(val project: VcxProject, parentKey: String) : Item("$parentKey/prj:" + project.name)
    class Dir(val dir: FilterDir, parentKey: String) : Item("$parentKey/dir:" + dir.name)
    class File(val entry: FileEntry, parentKey: String) : Item("$parentKey/file:" + entry.name)
}

class FiltersPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val root = DefaultMutableTreeNode(Item.Root())
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** First tree node for each file path, used by "Select Opened File". */
    private var fileNodes = HashMap<Path, DefaultMutableTreeNode>()
    private val pendingExpand = HashSet<String>()
    private var dragged: List<DefaultMutableTreeNode> = emptyList()
    private var autoscroll: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(AUTOSCROLL_KEY, false)
        set(v) = PropertiesComponent.getInstance(project).setValue(AUTOSCROLL_KEY, v, false)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ItemRenderer()
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
                override fun actionPerformed(e: AnActionEvent) = scheduleReload(0)
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
                if (events.any { isRelevant(it.path) }) scheduleReload(500)
            }
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (autoscroll) event.newFile?.let { selectFile(it) }
            }
        })
        scheduleReload(0)
    }

    private fun isRelevant(path: String): Boolean {
        val p = path.lowercase()
        return p.endsWith(".sln") || p.endsWith(".vcxproj") || p.endsWith(".vcxproj.filters")
    }

    private fun scheduleReload(delayMs: Int) {
        val base = project.basePath ?: return
        alarm.cancelAllRequests()
        alarm.addRequest({
            val solutions = VcxParser.findSolutions(Path.of(base))
            ApplicationManager.getApplication().invokeLater({ rebuild(solutions) }, ModalityState.any(), project.disposed)
        }, delayMs)
    }

    private fun rebuild(solutions: List<VcxSolution>) {
        val expanded = HashSet<String>(pendingExpand)
        pendingExpand.clear()
        for (i in 0 until tree.rowCount) {
            val path = tree.getPathForRow(i)
            if (tree.isExpanded(path)) expanded += keyOf(path.lastPathComponent as DefaultMutableTreeNode)
        }
        val selected = tree.selectionPaths?.map { keyOf(it.lastPathComponent as DefaultMutableTreeNode) }?.toSet().orEmpty()
        val firstLoad = root.childCount == 0

        root.removeAllChildren()
        fileNodes = HashMap()
        for (sln in solutions) addSolution(root, sln, "")
        model.reload()

        if (firstLoad) {
            // Default view: solutions and projects open, filters collapsed.
            TreeUtil.expand(tree, 2)
        } else {
            val toSelect = mutableListOf<TreePath>()
            restore(root, expanded, selected, toSelect)
            if (toSelect.isNotEmpty()) tree.selectionPaths = toSelect.toTypedArray()
        }
    }

    private fun addSolution(parent: DefaultMutableTreeNode, sln: VcxSolution, parentKey: String) {
        val item = Item.Solution(sln, parentKey)
        val node = DefaultMutableTreeNode(item)
        parent.add(node)
        for (prj in sln.projects) {
            val pItem = Item.Project(prj, item.key)
            val pNode = DefaultMutableTreeNode(pItem)
            node.add(pNode)
            fill(pNode, prj.root, pItem.key)
        }
        for (child in sln.children) addSolution(node, child, item.key)
    }

    private fun fill(parent: DefaultMutableTreeNode, dir: FilterDir, parentKey: String) {
        for (child in dir.dirs.values) {
            val item = Item.Dir(child, parentKey)
            val node = DefaultMutableTreeNode(item)
            parent.add(node)
            fill(node, child, item.key)
        }
        for (f in dir.files.sortedBy { it.name.lowercase() }) {
            val node = DefaultMutableTreeNode(Item.File(f, parentKey))
            parent.add(node)
            fileNodes.putIfAbsent(f.path, node)
        }
    }

    private fun restore(node: DefaultMutableTreeNode, expanded: Set<String>, selected: Set<String>, out: MutableList<TreePath>) {
        val key = keyOf(node)
        val path = TreePath(node.path)
        if (key in expanded) tree.expandPath(path)
        if (key in selected && node !== root) out += path
        for (i in 0 until node.childCount) restore(node.getChildAt(i) as DefaultMutableTreeNode, expanded, selected, out)
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

    private fun projectOf(node: DefaultMutableTreeNode): VcxProject? =
        (projectNodeOf(node)?.userObject as? Item.Project)?.project

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
                else java.nio.file.Files.write(filtersFile, updated)
            }
            if (vf == null) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filtersFile)
        } catch (e: Exception) {
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder("Could not update ${filtersFile.fileName}: ${e.message}", MessageType.ERROR, null)
                .createBalloon().showInCenterOf(tree)
            return
        }
        if (expand != null) pendingExpand += keyOf(expand)
        scheduleReload(0)
    }

    private fun selectedNode(): DefaultMutableTreeNode? = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun validateFilterName(parent: DefaultMutableTreeNode, name: String, self: FilterDir?): String? {
        val dirs = when (val i = parent.userObject) {
            is Item.Project -> i.project.root.dirs
            is Item.Dir -> i.dir.dirs
            else -> return null
        }
        return when {
            name.isBlank() -> "The name must not be empty"
            name.any { it in "\\/:*?\"<>|" } -> "The name must not contain \\ / : * ? \" < > |"
            dirs[name]?.let { it !== self } == true -> "A filter named '$name' already exists here"
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

    private inner class MoveHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent) = MOVE

        override fun createTransferable(c: JComponent): Transferable? {
            val nodes = tree.selectionPaths?.map { it.lastPathComponent as DefaultMutableTreeNode }.orEmpty()
            if (nodes.isEmpty() || nodes.any { it.userObject !is Item.File }) return null
            // Moving between projects would mean editing two projects; only one at a time is supported.
            if (nodes.map { projectNodeOf(it) }.distinct().size != 1) return null
            dragged = nodes
            return StringSelection(nodes.joinToString("\n") { (it.userObject as Item.File).entry.path.toString() })
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop || !support.isDataFlavorSupported(DataFlavor.stringFlavor) || dragged.isEmpty()) return false
            val path = (support.dropLocation as? JTree.DropLocation)?.path ?: return false
            val target = dropTarget(path.lastPathComponent as DefaultMutableTreeNode) ?: return false
            return projectNodeOf(target) === projectNodeOf(dragged.first())
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val path = (support.dropLocation as JTree.DropLocation).path ?: return false
            val target = dropTarget(path.lastPathComponent as DefaultMutableTreeNode) ?: return false
            val files = dragged.filter { it.parent !== target }
            dragged = emptyList()
            if (files.isNotEmpty()) moveFiles(files, target)
            return true
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
                    val vf = LocalFileSystem.getInstance().findFileByNioFile(item.entry.path)
                    icon = if (vf != null) IconUtil.getIcon(vf, 0, project) else AllIcons.FileTypes.Any_type
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
