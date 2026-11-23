package com.github.linwancen.plugin.graph.ui

import com.github.linwancen.plugin.graph.printer.InstallMermaid
import com.github.linwancen.plugin.graph.parser.Parser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class GraphWindowFactory : ToolWindowFactory {

    override fun init(toolWindow: ToolWindow) {
        val title: String = DrawGraphBundle.message("window", arrayOfNulls<Any>(0))
        toolWindow.stripeTitle = title
        toolWindow.title = title
    }

    companion object {
        @JvmStatic
        val winMap = mutableMapOf<Project, GraphWindow>()

        @JvmStatic
        fun activeWindow(project: Project): GraphWindow? {
            return winMap[project]
        }

        @JvmStatic
        fun openMethodTab(project: Project, element: PsiElement, call: Boolean = true) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val host = winMap[project] ?: run {
                    ToolWindowManager.getInstance(project).getToolWindow("Graph")
                        ?.activate { openMethodTab(project, element, call) }
                    return@invokeLater
                }
                val resolved = ApplicationManager.getApplication().runReadAction(
                    Computable<Triple<PsiElement, Pair<String, String>, Pair<VirtualFile?, Int>>?> {
                        val methodTarget = Parser.graphTarget(project, element) ?: return@Computable null
                        Triple(
                            methodTarget,
                            Parser.graphClassInfo(project, methodTarget),
                            methodTarget.containingFile?.virtualFile to methodTarget.textOffset,
                        )
                    },
                ) ?: return@invokeLater
                val (targetElement, info, source) = resolved
                val (file, offset) = source
                val existing = host.findClassTab(info.first)
                val target = host.openClassTab(info.first, info.second, file, offset)
                host.toolWindow.activate(null)
                // 只有目标类对应的 Tab 不存在时，才需要首次生成调用关系图
                if (existing == null) {
                    RelController.forClass(project, target, targetElement)
                }
            }
        }

        @JvmStatic
        fun openFileTab(project: Project, file: VirtualFile, refreshExisting: Boolean = false) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !file.isValid) return@invokeLater
                val host = winMap[project] ?: return@invokeLater
                val editorManager = FileEditorManager.getInstance(project)
                val offset = if (editorManager.selectedEditor?.file == file) {
                    editorManager.selectedTextEditor?.caretModel?.offset ?: 0
                } else {
                    0
                }
                val result = ApplicationManager.getApplication().runReadAction(
                    Computable<Pair<Pair<String, String>, Int>?> {
                        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@Computable null
                        val validOffset = offset.coerceIn(0, psiFile.textLength.coerceAtLeast(0))
                        val element = psiFile.findElementAt(validOffset) ?: psiFile
                        Parser.graphClassInfo(project, element) to validOffset
                    },
                ) ?: return@invokeLater
                val (info, validOffset) = result
                val existing = host.findClassTab(info.first)
                val page = host.openClassTab(info.first, info.second, file, validOffset)
                host.toolWindow.activate(null)
                if (existing == null || refreshExisting) {
                    RelController.forFile(project, page, arrayOf(file), true)
                }
            }
        }

        @JvmStatic
        fun openSelectedFile(project: Project) {
            val editor = FileEditorManager.getInstance(project).selectedEditor ?: return
            openFileTab(project, editor.file ?: return)
        }

        @JvmStatic
        fun refreshSelectedEditor(project: Project) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val editorManager = FileEditorManager.getInstance(project)
                val editor = editorManager.selectedTextEditor ?: return@invokeLater
                val file = editorManager.selectedEditor?.file ?: return@invokeLater
                val offset = editor.caretModel.offset
                val element = ApplicationManager.getApplication().runReadAction(
                    Computable<PsiElement?> {
                        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@Computable null
                        psiFile.findElementAt(offset) ?: psiFile
                    },
                ) ?: return@invokeLater
                val method = ApplicationManager.getApplication().runReadAction(
                    Computable<PsiElement?> { Parser.graphTarget(project, element) },
                )
                if (method != null) {
                    openMethodTab(project, method, true)
                } else {
                    openFileTab(project, file, true)
                }
            }
        }

        @JvmStatic
        fun openFilesTab(project: Project, files: Array<VirtualFile>) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || files.isEmpty()) return@invokeLater
                val host = winMap[project] ?: run {
                    ToolWindowManager.getInstance(project).getToolWindow("Graph")
                        ?.activate { openFilesTab(project, files) }
                    return@invokeLater
                }
                val file = files.first()
                val offset = 0
                val info = ApplicationManager.getApplication().runReadAction(
                    Computable<Pair<String, String>?> {
                        val psiFile = if (!file.isDirectory) PsiManager.getInstance(project).findFile(file) else null
                        val element = psiFile?.findElementAt(0) ?: psiFile
                        element?.let { Parser.graphClassInfo(project, it) }
                    },
                ) ?: (file.path to file.name.substringBeforeLast('.', file.name))
                val key = if (files.size == 1) info.first else files.joinToString("|") { it.path }
                val title = if (files.size == 1) info.second else file.name
                val existing = host.findClassTab(key)
                val page = host.openClassTab(key, title, file, offset)
                host.toolWindow.activate(null)
                if (existing == null) {
                    RelController.forFile(project, page, files, true)
                }
            }
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        InstallMermaid.checkAndInstall()
        val authorWindow = GraphWindow(project, toolWindow)
        val iterator = winMap.iterator()
        for (entry in iterator) {
            if (entry.key.isDisposed) {
                iterator.remove()
            }
        }
        winMap[project] = authorWindow
        // 222.2680.4 delete SERVICE
        val contentFactory = ContentFactory.SERVICE.getInstance()
        val content = contentFactory.createContent(authorWindow.mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
        openSelectedFile(project)
    }
}
