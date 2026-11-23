package com.github.linwancen.plugin.graph.ui

import com.github.linwancen.plugin.graph.parser.Parser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.ui.content.ContentFactory
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * Bottom IDEA tool window. The existing right-side Graph tool window is not modified.
 *
 * Analysis is PSI-based so it can work with every language for which Draw Graph has
 * a Parser implementation and IntelliJ can resolve references.
 */
class CallChainToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CallChainPanel(project)
        panels[project] = panel
        val content = ContentFactory.SERVICE.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        private val panels = java.util.WeakHashMap<Project, CallChainPanel>()

        /**
         * Called only for the Graph's openGraph: node-click action.
         * It activates the bottom panel without closing or replacing Graph.
         */
        @JvmStatic
        fun showFor(project: Project, element: PsiElement) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !element.isValid) return@invokeLater
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Call Chain")
                    ?: return@invokeLater
                toolWindow.activate {
                    panels[project]?.analyze(element)
                }
            }
        }
    }
}

private data class ChainNode(
    val id: String,
    val label: String,
    val detail: String,
    val fileUrl: String?,
    val offset: Int,
    val external: Boolean,
    val depth: Int,
    val root: Boolean = false,
)

private data class ChainEdge(
    val from: String,
    val to: String,
    val cycle: Boolean = false,
)

private data class ChainSnapshot(
    val rootId: String,
    val nodes: List<ChainNode>,
    val edges: List<ChainEdge>,
    val truncated: Boolean,
)

private object CallChainAnalyzer {
    private const val MAX_DEPTH = 12
    private const val MAX_NODES = 500

    fun analyze(project: Project, selected: PsiElement): ChainSnapshot {
        val root = Parser.graphTarget(project, selected) ?: selected
        val rootNode = node(project, root, 0, true)
        val nodes = linkedMapOf(rootNode.id to rootNode)
        val edges = linkedMapOf<Pair<String, String>, ChainEdge>()
        var truncated = false
        val parserMap = Parser.parserMap()

        fun directCallees(method: PsiElement): List<PsiElement> {
            // Compiled PSI (ClsMethodImpl), including classes loaded from JARs,
            // must never be traversed with PsiRecursiveElementWalkingVisitor.
            // IntelliJ explicitly rejects walking visitors on compiled PSI because
            // getNextSibling() is very expensive there. Library methods are leaves.
            val methodFile = method.containingFile?.virtualFile ?: return emptyList()
            if (methodFile.fileSystem !is LocalFileSystem) return emptyList()

            val result = linkedMapOf<String, PsiElement>()
            method.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    // Resolve references in the selected method body.
                    try {
                        for (reference in element.references) {
                            val resolved = reference.resolve() ?: continue

                            // 忽略包、模块等非方法级解析结果
                            if (resolved is PsiPackage) continue

                            val parser = Parser.findParser(resolved, parserMap) ?: continue
                            val target = parser.graphTarget(resolved) ?: continue
                            if (target == method || !target.isValid) continue
                            // Ignore trivial JavaBean/Kotlin accessors before adding
                            // them to the graph, so traversal does not expand noise.
                            if (isTrivialAccessor(target)) continue
                            val targetNode = node(project, target, 0, false)
                            result.putIfAbsent(targetNode.id, target)
                        }
                    } catch (_: RuntimeException) {
                        // Broken/incomplete PSI should not abort the whole graph.
                    }
                    super.visitElement(element)
                }
            })
            return result.values.toList()
        }

        fun directCallers(method: PsiElement): List<PsiElement> {
            val result = linkedMapOf<String, PsiElement>()
            val scope = GlobalSearchScope.projectScope(project)
            for (reference in ReferencesSearch.search(method, scope).findAll()) {
                val usage = reference.element
                val usageFile = usage.containingFile?.virtualFile ?: continue
                if (usageFile.fileSystem !is LocalFileSystem) continue
                val parser = Parser.findParser(usage, parserMap) ?: continue
                val caller = parser.graphTarget(usage) ?: continue
                if (caller == method || !caller.isValid) continue
                // Accessor-only callers usually add no meaningful business context.
                if (isTrivialAccessor(caller)) continue
                val callerNode = node(project, caller, 0, false)
                result.putIfAbsent(callerNode.id, caller)
            }
            return result.values.toList()
        }

        fun addNode(element: PsiElement, depth: Int, isRoot: Boolean = false): ChainNode {
            val candidate = node(project, element, depth, isRoot)
            val old = nodes[candidate.id]
            if (old == null) {
                if (nodes.size >= MAX_NODES) {
                    truncated = true
                    return candidate
                }
                nodes[candidate.id] = candidate
                return candidate
            }
            return old
        }

        val expandedForward = mutableSetOf(rootNode.id)
        val expandedBackward = mutableSetOf(rootNode.id)

        fun walkForward(method: PsiElement, depth: Int, path: Set<String>) {
            if (depth >= MAX_DEPTH) {
                truncated = true
                return
            }
            for (callee in directCallees(method)) {
                val target = addNode(callee, depth + 1)
                val cycle = target.id in path
                edges.putIfAbsent(methodId(project, method) to target.id, ChainEdge(methodId(project, method), target.id, cycle))
                if (target.external || cycle || target.id in expandedForward) continue
                if (nodes.size >= MAX_NODES) {
                    truncated = true
                    return
                }
                expandedForward.add(target.id)
                walkForward(callee, depth + 1, path + target.id)
            }
        }

        fun walkBackward(method: PsiElement, depth: Int, path: Set<String>) {
            if (depth >= MAX_DEPTH) {
                truncated = true
                return
            }
            for (caller in directCallers(method)) {
                val source = addNode(caller, -(depth + 1))
                val currentId = methodId(project, method)
                val cycle = source.id in path
                edges.putIfAbsent(source.id to currentId, ChainEdge(source.id, currentId, cycle))
                if (source.external || cycle || source.id in expandedBackward) continue
                if (nodes.size >= MAX_NODES) {
                    truncated = true
                    return
                }
                expandedBackward.add(source.id)
                walkBackward(caller, depth + 1, path + source.id)
            }
        }

        walkForward(root, 0, setOf(rootNode.id))
        walkBackward(root, 0, setOf(rootNode.id))
        return ChainSnapshot(rootNode.id, nodes.values.toList(), edges.values.toList(), truncated)
    }

    /**
     * Returns true only for trivial JavaBean/Kotlin accessors.
     *
     * Name alone is not enough: business methods such as getUserInfo() or
     * isOrderValid() must remain in the graph when they contain real logic.
     */
    private fun isTrivialAccessor(element: PsiElement): Boolean {
        val name = (element as? PsiNamedElement)?.name ?: return false
        val kind = when {
            name.matches(Regex("^get[A-Z_].*")) -> "get"
            name.matches(Regex("^is[A-Z_].*")) -> "is"
            name.matches(Regex("^set[A-Z_].*")) -> "set"
            else -> return false
        }

        var body = element.text ?: return false
        // Remove comments and whitespace before checking for a one-line accessor.
        body = body
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)//.*$"), "")
            .replace(Regex("\\s+"), "")
            .replace(";", "")

        val open = body.indexOf('{')
        val close = body.lastIndexOf('}')
        if (open >= 0 && close > open) {
            body = body.substring(open + 1, close)
        } else {
            // Kotlin expression-body accessor, e.g. getFoo() = foo.
            val equals = body.indexOf('=')
            if (equals >= 0) body = body.substring(equals + 1)
        }

        return when (kind) {
            "get", "is" ->
                Regex("^(?:return)?(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*$")
                    .matches(body) ||
                        Regex("^return(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*$")
                            .matches(body)
            "set" ->
                Regex("^(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*=(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*$")
                    .matches(body)
            else -> false
        }
    }

    private fun methodId(project: Project, element: PsiElement): String =
        node(project, element, 0, false).id

    private fun node(project: Project, element: PsiElement, depth: Int, root: Boolean): ChainNode {
        val nav = element.navigationElement
        val file = nav.containingFile?.virtualFile ?: element.containingFile?.virtualFile
        val offset = nav.textOffset.coerceAtLeast(0)
        val url = file?.url
        val id = (url ?: "psi:${element.language.id}") + "#" + offset
        val path = file?.path?.replace('\\', '/')
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/')
        val external = path == null || base == null ||
                !(path.equals(base, ignoreCase = true) || path.startsWith("$base/", ignoreCase = true))
        val name = (element as? PsiNamedElement)?.name
            ?: element.text.lineSequence().firstOrNull()?.trim()?.take(100)
            ?: element.javaClass.simpleName
        val fileName = file?.name ?: "External symbol"
        val line = try {
            val text = element.containingFile.text
            1 + text.take(element.textOffset.coerceAtMost(text.length)).count { it == '\n' }
        } catch (_: RuntimeException) {
            0
        }
        return ChainNode(
            id = id,
            label = name.ifBlank { element.javaClass.simpleName },
            detail = if (line > 0) "$fileName : $line" else fileName,
            fileUrl = url,
            offset = offset,
            external = external,
            depth = depth,
            root = root,
        )
    }
}

private class CallChainPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val status = JLabel("点击 Graph 中的方法节点，查看正向和反向调用链")
    private val canvas = CallChainCanvas(project)
    private var lastElement: PsiElement? = null

    init {
        val toolbar = JPanel(BorderLayout(8, 0))
        toolbar.border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
        toolbar.add(status, BorderLayout.CENTER)
        val refresh = JButton("刷新")
        refresh.addActionListener { lastElement?.let { analyze(it) } }
        toolbar.add(refresh, BorderLayout.EAST)
        add(toolbar, BorderLayout.NORTH)
        add(JScrollPane(canvas), BorderLayout.CENTER)
    }

    fun analyze(element: PsiElement) {
        if (project.isDisposed || !element.isValid) return
        lastElement = element
        status.text = "正在分析调用关系…"
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val snapshot = ReadAction.compute<ChainSnapshot?, RuntimeException> {
                    if (!element.isValid) null else CallChainAnalyzer.analyze(project, element)
                }
                SwingUtilities.invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (snapshot == null) {
                        status.text = "方法已失效，请重新选择"
                    } else {
                        canvas.setSnapshot(snapshot)
                        val suffix = if (snapshot.truncated) "（已达到分析上限）" else ""
                        status.text = "节点 ${snapshot.nodes.size} · 连线 ${snapshot.edges.size} · 正向 + 反向$suffix"
                    }
                }
            } catch (e: Throwable) {
                SwingUtilities.invokeLater {
                    status.text = "调用链分析失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }
}

private class CallChainCanvas(private val project: Project) : JPanel() {
    private var snapshot: ChainSnapshot? = null
    private val boxes = linkedMapOf<String, java.awt.Rectangle>()

    init {
        background = Color(0xFA, 0xFB, 0xFD)
        preferredSize = Dimension(1000, 600)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val hit = boxes.entries.firstOrNull { it.value.contains(e.point) } ?: return
                val node = snapshot?.nodes?.firstOrNull { it.id == hit.key } ?: return
                val url = node.fileUrl ?: return
                val file = VirtualFileManager.getInstance().findFileByUrl(url) ?: return
                OpenFileDescriptor(project, file, node.offset.coerceAtMost(file.length.toInt())).navigate(true)
            }
        })
    }

    fun setSnapshot(value: ChainSnapshot) {
        snapshot = value
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val nodes = snapshot?.nodes.orEmpty()
        if (nodes.isEmpty()) return Dimension(1000, 600)
        val minDepth = nodes.map { it.depth }.min() ?: 0
        val maxDepth = nodes.map { it.depth }.max() ?: 0
        val layers = nodes.groupBy { it.depth }
        val maxRows = layers.values.map { it.size }.max() ?: 1
        return Dimension(max(1000, (maxDepth - minDepth + 1) * 250 + 100), max(600, maxRows * 100 + 100))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val data = snapshot ?: return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        boxes.clear()

        val minDepth = data.nodes.map { it.depth }.min() ?: 0
        val layers = data.nodes.groupBy { it.depth }.toSortedMap()
        val positions = linkedMapOf<String, java.awt.Rectangle>()
        for ((depth, layer) in layers) {
            val x = 35 + (depth - minDepth) * 250
            layer.sortedBy { it.label }.forEachIndexed { index, node ->
                val y = 35 + index * 100
                val rect = java.awt.Rectangle(x, y, 205, 62)
                positions[node.id] = rect
                boxes[node.id] = rect
            }
        }

        for (edge in data.edges) {
            val from = positions[edge.from] ?: continue
            val to = positions[edge.to] ?: continue
            val x1 = from.x + from.width
            val y1 = from.y + from.height / 2
            val x2 = to.x
            val y2 = to.y + to.height / 2
            g2.color = if (edge.cycle) Color(0xD9, 0x77, 0x06) else Color(0x91, 0x9B, 0xAA)
            g2.stroke = if (edge.cycle) BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(5f, 4f), 0f) else BasicStroke(1.3f)
            val curve = Path2D.Float()
            curve.moveTo(x1.toFloat(), y1.toFloat())
            val middle = (x1 + x2) / 2f
            curve.curveTo(middle, y1.toFloat(), middle, y2.toFloat(), x2.toFloat(), y2.toFloat())
            g2.draw(curve)
            val arrow = Path2D.Float()
            arrow.moveTo(x2.toFloat(), y2.toFloat())
            arrow.lineTo((x2 - 8).toFloat(), (y2 - 4).toFloat())
            arrow.lineTo((x2 - 8).toFloat(), (y2 + 4).toFloat())
            arrow.closePath()
            g2.fill(arrow)
        }

        for (node in data.nodes) {
            val r = positions[node.id] ?: continue
            g2.color = when {
                node.root -> Color(0xD9, 0xF5, 0xE3)
                node.external -> Color(0xFF, 0xF1, 0xD6)
                else -> Color.WHITE
            }
            g2.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12)
            g2.color = when {
                node.root -> Color(0x16, 0xA3, 0x4A)
                node.external -> Color(0xD9, 0x77, 0x06)
                else -> Color(0xA8, 0xB0, 0xBC)
            }
            g2.stroke = BasicStroke(if (node.root) 2f else 1f)
            g2.drawRoundRect(r.x, r.y, r.width, r.height, 12, 12)
            g2.color = Color(0x20, 0x28, 0x33)
            g2.font = font.deriveFont(Font.BOLD, 12f)
            g2.drawString(node.label.take(27), r.x + 10, r.y + 24)
            g2.color = Color(0x70, 0x78, 0x86)
            g2.font = font.deriveFont(10f)
            val marker = if (node.root) "当前方法" else if (node.external) "依赖库 · 停止递归" else node.detail
            g2.drawString(marker.take(31), r.x + 10, r.y + 44)
        }
        g2.dispose()
    }
}
