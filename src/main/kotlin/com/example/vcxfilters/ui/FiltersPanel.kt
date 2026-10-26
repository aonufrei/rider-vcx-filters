package com.example.vcxfilters.ui

import com.example.vcxfilters.model.FileEntry
import com.example.vcxfilters.model.FilterDir
import com.example.vcxfilters.model.VcxParser
import com.example.vcxfilters.model.VcxProject
import com.example.vcxfilters.model.VcxSolution
import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.IconUtil
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.openapi.ui.SimpleToolWindowPanel
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** What a tree node represents. [key] identifies the node across reloads so expansion can be restored. */
private sealed class Item(val key: String) {
    class Root : Item("")
    class Solution(val sln: VcxSolution) : Item("sln:" + sln.name)
    class Project(val project: VcxProject, parentKey: String) : Item("$parentKey/prj:" + project.name)
    class Dir(val dir: FilterDir, parentKey: String) : Item("$parentKey/dir:" + dir.name)
    class File(val entry: FileEntry, parentKey: String) : Item("$parentKey/file:" + entry.name)
}

class FiltersPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val root = DefaultMutableTreeNode(Item.Root())
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ItemRenderer()
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
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(OpenAction()), "VcxFiltersPopup")

        setContent(ScrollPaneFactory.createScrollPane(tree))
        val group = DefaultActionGroup(
            object : AnAction("Refresh", "Re-read solutions and filters", AllIcons.Actions.Refresh), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = scheduleReload(0)
            },
            object : AnAction("Expand All", null, AllIcons.Actions.Expandall), DumbAware {
                override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
            },
            object : AnAction("Collapse All", null, AllIcons.Actions.Collapseall), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    TreeUtil.collapseAll(tree, 1)
                }
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("VcxFilters", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { isRelevant(it.path) }) scheduleReload(500)
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
        val expanded = HashSet<String>()
        for (i in 0 until tree.rowCount) {
            val path = tree.getPathForRow(i)
            if (tree.isExpanded(path)) expanded += ((path.lastPathComponent as DefaultMutableTreeNode).userObject as Item).key
        }
        val selected = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.let { (it.userObject as Item).key }
        val firstLoad = root.childCount == 0

        root.removeAllChildren()
        for (sln in solutions) {
            val item = Item.Solution(sln)
            val node = DefaultMutableTreeNode(item)
            root.add(node)
            for (prj in sln.projects) {
                val pItem = Item.Project(prj, item.key)
                val pNode = DefaultMutableTreeNode(pItem)
                node.add(pNode)
                fill(pNode, prj.root, pItem.key)
            }
        }
        model.reload()

        if (firstLoad) {
            // Default view: solutions and projects open, filters collapsed.
            TreeUtil.expand(tree, 2)
        } else {
            restore(root, expanded, selected)
        }
    }

    private fun fill(parent: DefaultMutableTreeNode, dir: FilterDir, parentKey: String) {
        for (child in dir.dirs.values) {
            val item = Item.Dir(child, parentKey)
            val node = DefaultMutableTreeNode(item)
            parent.add(node)
            fill(node, child, item.key)
        }
        for (f in dir.files.sortedBy { it.name.lowercase() }) {
            parent.add(DefaultMutableTreeNode(Item.File(f, parentKey)))
        }
    }

    private fun restore(node: DefaultMutableTreeNode, expanded: Set<String>, selected: String?) {
        val key = (node.userObject as Item).key
        val path = TreePath(node.path)
        if (key in expanded) tree.expandPath(path)
        if (key == selected && node !== root) tree.selectionPath = path
        for (i in 0 until node.childCount) restore(node.getChildAt(i) as DefaultMutableTreeNode, expanded, selected)
    }

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

    private inner class OpenAction : AnAction("Open"), DumbAware {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedFile() != null
        }

        override fun actionPerformed(e: AnActionEvent) = openSelected()
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
}
