package com.github.linwancen.plugin.graph.ui

import com.github.linwancen.plugin.common.psi.LangUtils
import com.github.linwancen.plugin.common.psi.PsiUnSaveUtils
import com.github.linwancen.plugin.common.vfile.ChildFileUtils
import com.github.linwancen.plugin.graph.parser.Parser
import com.github.linwancen.plugin.graph.parser.RelData
import com.github.linwancen.plugin.graph.settings.DrawGraphAppState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import java.util.Collections
import java.util.WeakHashMap
import org.slf4j.LoggerFactory

object RelController {
    private val LOG = LoggerFactory.getLogger(this::class.java)

    private sealed class GraphRequest {
        data class Files(val files: Array<VirtualFile>) : GraphRequest()
        data class Element(val element: PsiElement, val call: Boolean) : GraphRequest()
        data class ClassElement(val element: PsiElement) : GraphRequest()
        data class InjectedElement(val element: PsiElement) : GraphRequest()
    }

    private val requestByWindow =
        Collections.synchronizedMap(WeakHashMap<GraphWindow, GraphRequest>())

    @JvmStatic
    fun reload(project: Project) {
        val window = GraphWindowFactory.activeWindow(project) ?: return
        reload(project, window)
    }

    @JvmStatic
    fun reload(project: Project, window: GraphWindow) {
        val target = if (window.isPrimary) window.selectedGraphWindow() ?: return else window
        when (val request = requestByWindow[target] ?: return) {
            is GraphRequest.Files -> forFile(project, target, request.files, false)
            is GraphRequest.Element -> forElement(project, target, request.element, request.call)
            is GraphRequest.ClassElement -> forClass(project, target, request.element)
            is GraphRequest.InjectedElement -> forInjectedElement(project, target, request.element)
        }
    }

    /**
     * call by Action and Listener and reload()
     */
    @JvmStatic
    fun forFile(project: Project, files: Array<VirtualFile>, fromAction: Boolean) {
        GraphWindowFactory.openFilesTab(project, files)
    }

    @JvmStatic
    fun forFile(project: Project, window: GraphWindow, files: Array<VirtualFile>, fromAction: Boolean) {
        try {
            requestByWindow[window] = GraphRequest.Files(files)
            if (fromAction) {
                // let it init
                window.toolWindow.activate(null)
            }
            if (!fromAction && !window.toolWindow.isVisible) {
                return
            }
            // for before 201.6668.113
            if (files.size == 1 && !files[0].isDirectory) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    buildSrc(project, window, files, null)
                }
            } else {
                object : Task.Backgroundable(project, "draw parse") {
                    override fun run(indicator: ProgressIndicator) {
                        buildSrc(project, window, files, indicator)
                    }
                }.queue()
            }
        } catch (e: Throwable) {
            LOG.info("RelController.forFile() catch Throwable but log to record.", e)
        }
    }

    @JvmStatic
    private fun buildSrc(project: Project, window: GraphWindow, files: Array<VirtualFile>, indicator: ProgressIndicator?) {
        try {
            val fileList = if (files.size == 1 && !files[0].isDirectory) {
                files.toList()
            } else {
                window.closeAutoLoad()
                val map = ChildFileUtils.recurExtChildFile(files)
                map.remove("class")
                val mostExt = ChildFileUtils.mostExt(map) ?: return
                val mostExtChildFile = mostExt.value
                when (mostExt.key) {
                    "java" -> map["kt"]?.let(mostExtChildFile::addAll)
                    "kt" -> map["java"]?.let(mostExtChildFile::addAll)
                    else -> LOG.info("most ext:{} size:{}", mostExt.key, mostExtChildFile.size)
                }
                mostExtChildFile
            }
            val relData = RelData()
            if (DrawGraphAppState.of().htmlPreview && files.size == 1 && files[0].name.endsWith(".html")) {
                HtmlFileController.forHtmlFiles(project, window, files)
                return
            }
            DumbService.getInstance(project).runReadActionInSmartMode {
                Parser.src(project, relData, fileList, indicator)
            }
            if (relData.itemMap.isEmpty()) {
                PlantUmlFileController.forPlantUMLFiles(project, window, files)
                HtmlFileController.forHtmlFiles(project, window, files)
                return
            }
            RelDataController.dataToWindow(project, window, relData, true)
        } catch (e: Throwable) {
            LOG.info("RelController.buildSrc() catch Throwable but log to record.", e)
        }
    }

    /**
     * call by Action and reload()
     */
    @JvmStatic
    fun forElement(project: Project, psiElement: PsiElement, call: Boolean) {
        GraphWindowFactory.openMethodTab(project, psiElement, call)
    }

    @JvmStatic
    fun forElement(project: Project, window: GraphWindow, psiElement: PsiElement, call: Boolean) {
        try {
            requestByWindow[window] = GraphRequest.Element(psiElement, call)
            ToolWindowManager.getInstance(project).getToolWindow("Graph")?.activate(null)
            object : Task.Backgroundable(project, "draw ${if (call) "call" else "usage"}") {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        window.closeAutoLoad()
                        val relData = RelData()
                        DumbService.getInstance(project).runReadActionInSmartMode {
                            Parser.call(project, relData, psiElement, call, indicator)
                        }
                        RelDataController.dataToWindow(project, window, relData, call)
                    } catch (e: Throwable) {
                        LOG.info("RelController.forElement() Thread catch Throwable but log to record.", e)
                    }
                }
            }.queue()
        } catch (e: Throwable) {
            LOG.info("RelController.forElement() catch Throwable but log to record.", e)
        }
    }

    @JvmStatic
    fun forClass(project: Project, window: GraphWindow, psiElement: PsiElement) {
        try {
            requestByWindow[window] = GraphRequest.ClassElement(psiElement)
            ToolWindowManager.getInstance(project).getToolWindow("Graph")?.activate(null)
            object : Task.Backgroundable(project, "draw class graph") {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val relData = RelData()
                        DumbService.getInstance(project).runReadActionInSmartMode {
                            Parser.classGraph(project, relData, psiElement, indicator)
                        }
                        RelDataController.dataToWindow(project, window, relData, true)
                    } catch (e: Throwable) {
                        LOG.info("RelController.forClass() Thread catch Throwable but log to record.", e)
                    }
                }
            }.queue()
        } catch (e: Throwable) {
            LOG.info("RelController.forClass() catch Throwable but log to record.", e)
        }
    }

    /**
     * call by Action and reload()
     */
    @JvmStatic
    fun forInjectedElement(project: Project, psiFile: PsiElement) {
        val host = GraphWindowFactory.activeWindow(project) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val (info, file, offset) = ApplicationManager.getApplication().runReadAction(
                Computable<Triple<Pair<String, String>, VirtualFile?, Int>> {
                    Triple(
                        Parser.graphClassInfo(project, psiFile),
                        psiFile.containingFile?.virtualFile,
                        psiFile.textOffset,
                    )
                },
            )
            val target = host.openClassTab(
                info.first,
                info.second,
                file,
                offset,
            )
            host.toolWindow.activate(null)
            forInjectedElement(project, target, psiFile)
        }
    }

    @JvmStatic
    fun forInjectedElement(project: Project, window: GraphWindow, psiFile: PsiElement) {
        try {
            requestByWindow[window] = GraphRequest.InjectedElement(psiFile)
            DumbService.getInstance(project).runReadActionInSmartMode {
                val src = PsiUnSaveUtils.getText(psiFile)
                if (LangUtils.matchBaseLanguageId(psiFile, "HTML") != null) {
                    HtmlFileController.forHtmlSrc(window, src)
                    return@runReadActionInSmartMode
                }
                PlantUmlFileController.forPlantUMLSupportFile(psiFile, project, window, src)
            }
        } catch (e: Throwable) {
            LOG.info("RelController.forInjectedElement() catch Throwable but log to record.", e)
        }
    }
}