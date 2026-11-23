
package com.github.linwancen.plugin.graph.ui

import com.github.linwancen.plugin.graph.parser.Parser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.ui.content.ContentFactory
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * Bottom IDEA tool window.
 *
 * The existing Graph tool window is not modified.
 *
 * Current call-chain analysis supports Java methods only.
 */
class CallChainToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = CallChainPanel(project)
        panels[project] = panel

        val content = ContentFactory.SERVICE
            .getInstance()
            .createContent(panel, "", false)

        toolWindow.contentManager.addContent(content)
    }

    companion object {
        private val panels = java.util.WeakHashMap<Project, CallChainPanel>()

        /**
         * Called by the existing Graph node-click action.
         *
         * Activates the bottom Call Chain panel without replacing Graph.
         */
        @JvmStatic
        fun showFor(project: Project, element: PsiElement) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !element.isValid) {
                    return@invokeLater
                }

                val toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow("Call Chain")
                    ?: return@invokeLater

                toolWindow.activate {
                    panels[project]?.analyze(element)
                }
            }
        }
    }
}

/**
 * Call-chain filter options.
 */
private data class CallChainOptions(
    /** Only display project methods. */
    val projectOnly: Boolean = false,

    /** Ignore trivial getter/setter methods. */
    val skipAccessors: Boolean = true,

    /** Ignore constructors. */
    val skipConstructors: Boolean = true,

    /** Ignore private methods. */
    val skipPrivateMethods: Boolean = false,

    /** Ignore framework, logging and common utility methods. */
    val skipFrameworkCalls: Boolean = true,

    /** Maximum call depth. */
    val maxDepth: Int = 12,

    /** Excluded package names and fully qualified class names. */
    val excludedPatterns: List<String> = emptyList(),
)

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

    private const val MAX_NODES = 500

    /**
     * Analyze the selected Java method's forward call chain.
     *
     * Returns null when the selected element is not a Java method.
     */
    fun analyze(
        project: Project,
        selected: PsiElement,
        options: CallChainOptions,
    ): ChainSnapshot? {

        // Resolve the selected element to its graph target first.
        val target = Parser.graphTarget(project, selected) ?: selected

        // This feature currently supports Java methods only.
        val root = target as? PsiMethod ?: return null

        /**
         * Check whether a PSI method belongs to a Java file.
         */
        fun isJavaMethod(method: PsiMethod): Boolean {
            return method.containingFile?.language?.id == "JAVA"
        }

        if (!isJavaMethod(root)) {
            return null
        }

        val rootNode = node(project, root, 0, true)

        val nodes = linkedMapOf<String, ChainNode>()
        nodes[rootNode.id] = rootNode

        val edges = linkedMapOf<Pair<String, String>, ChainEdge>()

        var truncated = false

        val parserMap = Parser.parserMap()

        /**
         * Find methods called inside the current Java method body.
         *
         * Only local source files are traversed.
         * Compiled PSI from JARs is never walked.
         */
        fun directCallees(method: PsiMethod): List<PsiMethod> {

            if (!isJavaMethod(method)) {
                return emptyList()
            }

            val methodFile = method.containingFile?.virtualFile
                ?: return emptyList()

            if (methodFile.fileSystem !is LocalFileSystem) {
                return emptyList()
            }

            val body = method.body ?: return emptyList()

            val result = linkedMapOf<String, PsiMethod>()

            body.accept(object : PsiRecursiveElementWalkingVisitor() {

                override fun visitElement(element: PsiElement) {

                    try {
                        for (reference in element.references) {

                            val resolved = reference.resolve()
                                ?: continue

                            if (resolved is PsiPackage) {
                                continue
                            }

                            val parser = Parser.findParser(
                                resolved,
                                parserMap,
                            ) ?: continue

                            val graphTarget = parser.graphTarget(resolved)
                                ?: continue

                            val targetMethod = graphTarget as? PsiMethod
                                ?: continue

                            if (!isJavaMethod(targetMethod)) {
                                continue
                            }

                            if (targetMethod == method || !targetMethod.isValid) {
                                continue
                            }

                            val targetNode = node(
                                project,
                                targetMethod,
                                0,
                                false,
                            )

                            result.putIfAbsent(
                                targetNode.id,
                                targetMethod,
                            )
                        }
                    } catch (_: RuntimeException) {
                        // Broken or incomplete PSI should not abort analysis.
                    }

                    super.visitElement(element)
                }
            })

            return result.values.toList()
        }

        /**
         * Add a visible node.
         */
        fun addNode(
            method: PsiMethod,
            depth: Int,
            isRoot: Boolean = false,
        ): ChainNode {

            val candidate = node(
                project,
                method,
                depth,
                isRoot,
            )

            val existing = nodes[candidate.id]

            if (existing != null) {
                return existing
            }

            nodes[candidate.id] = candidate

            return candidate
        }

        /**
         * Check whether the method belongs to a framework or utility library.
         *
         * Matching is based on the containing class package.
         * Method names alone are never used for filtering.
         */
        fun isFrameworkOrUtilityMethod(method: PsiMethod): Boolean {

            val qualifiedName = method.containingClass?.qualifiedName
                ?: return false

            val excludedPackages = listOf(
                "java.",
                "javax.",
                "jakarta.",
                "kotlin.",
                "kotlinx.",
                "org.springframework.",
                "org.apache.",
                "org.slf4j.",
                "ch.qos.logback.",
                "com.fasterxml.",
                "com.google.",
                "org.hibernate.",
                "org.mybatis.",
                "cn.hutool.",
            )

            return excludedPackages.any {
                qualifiedName.startsWith(it)
            }
        }

        /**
         * Check whether the method belongs to a user-excluded package or class.
         *
         * Examples:
         * com.example.common
         * com.example.service.UserService
         * com.example.util.*
         */
        fun isExcludedClassOrPackage(
            method: PsiMethod,
            patterns: List<String>,
        ): Boolean {

            if (patterns.isEmpty()) {
                return false
            }

            val qualifiedName = method.containingClass?.qualifiedName
                ?: return false

            return patterns.any { rawPattern ->

                val pattern = rawPattern
                    .trim()
                    .removeSuffix(".*")
                    .trimEnd('.')

                if (pattern.isEmpty()) {
                    false
                } else {
                    qualifiedName == pattern ||
                            qualifiedName.startsWith("$pattern.") ||
                            qualifiedName.startsWith("$pattern$")
                }
            }
        }

        /**
         * Decide whether a method should be displayed.
         *
         * Important:
         * This only controls visibility.
         * Hidden project methods are still traversed.
         */
        fun shouldDisplay(
            method: PsiMethod,
            candidate: ChainNode,
        ): Boolean {

            if (
                isExcludedClassOrPackage(
                    method,
                    options.excludedPatterns,
                )
            ) {
                return false
            }

            if (options.projectOnly && candidate.external) {
                return false
            }

            if (
                options.skipAccessors &&
                isTrivialAccessor(method)
            ) {
                return false
            }

            if (
                options.skipConstructors &&
                method.isConstructor
            ) {
                return false
            }

            if (
                options.skipPrivateMethods &&
                method.hasModifierProperty(PsiModifier.PRIVATE)
            ) {
                return false
            }

            if (
                options.skipFrameworkCalls &&
                isFrameworkOrUtilityMethod(method)
            ) {
                return false
            }

            return true
        }

        // Methods already expanded in the forward direction.
        val expandedForward = mutableSetOf(rootNode.id)

        /**
         * Forward call-chain traversal.
         *
         * visibleParentId:
         * The nearest visible parent node.
         *
         * Hidden project methods are not displayed, but their descendants
         * are still analyzed.
         */
        fun walkForward(
            method: PsiMethod,
            visibleParentId: String,
            depth: Int,
            path: Set<String>,
        ) {

            if (depth >= options.maxDepth) {
                truncated = true
                return
            }

            for (callee in directCallees(method)) {

                if (!callee.isValid) {
                    continue
                }

                val candidate = node(
                    project,
                    callee,
                    depth + 1,
                    false,
                )

                val cycle = candidate.id in path

                // Handle cycles.
                if (cycle) {

                    if (shouldDisplay(callee, candidate)) {

                        if (
                            candidate.id !in nodes &&
                            nodes.size >= MAX_NODES
                        ) {
                            truncated = true
                            return
                        }

                        val targetNode = addNode(
                            callee,
                            depth + 1,
                        )

                        edges.putIfAbsent(
                            visibleParentId to targetNode.id,
                            ChainEdge(
                                from = visibleParentId,
                                to = targetNode.id,
                                cycle = true,
                            ),
                        )
                    }

                    continue
                }

                val visible = shouldDisplay(
                    callee,
                    candidate,
                )

                // Hidden method.
                if (!visible) {

                    // External library methods are leaves.
                    if (candidate.external) {
                        continue
                    }

                    // Avoid repeated expansion of hidden methods.
                    if (candidate.id in expandedForward) {
                        continue
                    }

                    expandedForward.add(candidate.id)

                    // Do not add the hidden node to the canvas.
                    // Continue traversal to find visible descendants.
                    walkForward(
                        method = callee,
                        visibleParentId = visibleParentId,
                        depth = depth + 1,
                        path = path + candidate.id,
                    )

                    continue
                }

                // Respect the node limit.
                if (
                    candidate.id !in nodes &&
                    nodes.size >= MAX_NODES
                ) {
                    truncated = true
                    return
                }

                val targetNode = addNode(
                    callee,
                    depth + 1,
                )

                // Connect the nearest visible parent and child.
                edges.putIfAbsent(
                    visibleParentId to targetNode.id,
                    ChainEdge(
                        from = visibleParentId,
                        to = targetNode.id,
                    ),
                )

                // External dependencies remain leaf nodes.
                if (targetNode.external) {
                    continue
                }

                if (targetNode.id in expandedForward) {
                    continue
                }

                expandedForward.add(targetNode.id)

                walkForward(
                    method = callee,
                    visibleParentId = targetNode.id,
                    depth = depth + 1,
                    path = path + targetNode.id,
                )
            }
        }

        // Start from the selected Java method.
        walkForward(
            method = root,
            visibleParentId = rootNode.id,
            depth = 0,
            path = setOf(rootNode.id),
        )

        return ChainSnapshot(
            rootId = rootNode.id,
            nodes = nodes.values.toList(),
            edges = edges.values.toList(),
            truncated = truncated,
        )
    }

    /**
     * Returns true only for trivial JavaBean accessors.
     *
     * Name alone is not enough:
     * getUserInfo() and isOrderValid() remain visible
     * when they contain real logic.
     */
    private fun isTrivialAccessor(element: PsiElement): Boolean {

        val name = (element as? PsiNamedElement)?.name
            ?: return false

        val kind = when {
            name.matches(Regex("^get[A-Z_].*")) -> "get"
            name.matches(Regex("^is[A-Z_].*")) -> "is"
            name.matches(Regex("^set[A-Z_].*")) -> "set"
            else -> return false
        }

        var body = element.text ?: return false

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
            val equals = body.indexOf('=')
            if (equals >= 0) {
                body = body.substring(equals + 1)
            }
        }

        return when (kind) {

            "get", "is" ->
                Regex(
                    "^(?:return)?(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*$"
                ).matches(body)

            "set" ->
                Regex(
                    "^(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*=(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*$"
                ).matches(body)

            else -> false
        }
    }

    private fun node(
        project: Project,
        element: PsiElement,
        depth: Int,
        root: Boolean,
    ): ChainNode {

        val nav = element.navigationElement

        val file = nav.containingFile?.virtualFile
            ?: element.containingFile?.virtualFile

        val offset = nav.textOffset.coerceAtLeast(0)
        val url = file?.url

        val id = (url ?: "psi:${element.language.id}") + "#" + offset

        val path = file?.path?.replace('\\', '/')

        val base = project.basePath
            ?.replace('\\', '/')
            ?.trimEnd('/')

        val external = path == null ||
                base == null ||
                !(
                        path.equals(base, ignoreCase = true) ||
                                path.startsWith("$base/", ignoreCase = true)
                        )

        val name = (element as? PsiNamedElement)?.name
            ?: element.text.lineSequence()
                .firstOrNull()
                ?.trim()
                ?.take(100)
            ?: element.javaClass.simpleName

        val fileName = file?.name ?: "External symbol"

        val line = try {
            val text = element.containingFile.text

            1 + text
                .take(element.textOffset.coerceAtMost(text.length))
                .count { it == '\n' }

        } catch (_: RuntimeException) {
            0
        }

        return ChainNode(
            id = id,
            label = name.ifBlank {
                element.javaClass.simpleName
            },
            detail = if (line > 0) {
                "$fileName : $line"
            } else {
                fileName
            },
            fileUrl = url,
            offset = offset,
            external = external,
            depth = depth,
            root = root,
        )
    }
}

/**
 * Call-chain tool window panel.
 */
private class CallChainPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    private val status = JLabel(
        "点击 Graph 中的 Java 方法节点，查看正向调用链"
    )

    private val canvas = CallChainCanvas(project)

    private var lastElement: PsiElement? = null

    // Basic filters.
    private val projectOnly = JCheckBox(
        "仅项目代码",
        false,
    )

    private val skipAccessors = JCheckBox(
        "过滤 getter/setter",
        true,
    )

    private val skipConstructors = JCheckBox(
        "过滤构造方法",
        true,
    )

    private val skipPrivateMethods = JCheckBox(
        "过滤 private 方法",
        false,
    )

    private val skipFrameworkCalls = JCheckBox(
        "过滤框架/日志/工具调用",
        true,
    )

    // Maximum call depth: 1-30, default 12.
    private val maxDepthSpinner = JSpinner(
        SpinnerNumberModel(12, 1, 30, 1)
    )

    // Multiple package/class exclusion rules.
    private val excludedPatternsField = JTextField(35)

    init {

        val northPanel = JPanel()

        northPanel.layout = javax.swing.BoxLayout(
            northPanel,
            javax.swing.BoxLayout.Y_AXIS,
        )

        // First row: status and refresh.
        val toolbar = JPanel(
            BorderLayout(8, 0)
        )

        toolbar.border = BorderFactory.createEmptyBorder(
            5,
            8,
            3,
            8,
        )

        toolbar.add(
            status,
            BorderLayout.CENTER,
        )

        val refresh = JButton("刷新")

        refresh.addActionListener {
            lastElement?.let {
                analyze(it)
            }
        }

        toolbar.add(
            refresh,
            BorderLayout.EAST,
        )

        northPanel.add(toolbar)

        // Second row: basic filters.
        val filterPanel = JPanel(
            FlowLayout(
                FlowLayout.LEFT,
                10,
                3,
            )
        )

        filterPanel.border = BorderFactory.createTitledBorder(
            "调用链过滤"
        )

        val checkboxes = listOf(
            projectOnly,
            skipAccessors,
            skipConstructors,
            skipPrivateMethods,
            skipFrameworkCalls,
        )

        checkboxes.forEach { checkbox ->

            checkbox.addActionListener {
                lastElement?.let {
                    analyze(it)
                }
            }

            filterPanel.add(checkbox)
        }

        northPanel.add(filterPanel)

        // Third row: advanced options.
        val advancedPanel = JPanel(
            FlowLayout(
                FlowLayout.LEFT,
                8,
                4,
            )
        )

        advancedPanel.border = BorderFactory.createTitledBorder(
            "高级设置"
        )

        // Maximum call depth.
        advancedPanel.add(
            JLabel("最大调用层数：")
        )

        maxDepthSpinner.preferredSize = Dimension(
            65,
            26,
        )

        advancedPanel.add(maxDepthSpinner)

        maxDepthSpinner.addChangeListener {
            lastElement?.let {
                analyze(it)
            }
        }

        // Excluded packages/classes.
        advancedPanel.add(
            JLabel("排除包/类：")
        )

        excludedPatternsField.toolTipText =
            "多个规则使用逗号、分号或空格分隔。例如：com.example.common,com.example.service.UserService"

        excludedPatternsField.preferredSize = Dimension(
            300,
            26,
        )

        advancedPanel.add(excludedPatternsField)

        // Press Enter to apply.
        excludedPatternsField.addActionListener {
            lastElement?.let {
                analyze(it)
            }
        }

        val applyButton = JButton("应用")

        applyButton.addActionListener {
            lastElement?.let {
                analyze(it)
            }
        }

        advancedPanel.add(applyButton)

        northPanel.add(advancedPanel)

        add(
            northPanel,
            BorderLayout.NORTH,
        )

        add(
            JScrollPane(canvas),
            BorderLayout.CENTER,
        )
    }

    /**
     * Read current UI options.
     * Called on EDT before background analysis starts.
     */
    private fun currentOptions(): CallChainOptions {

        val excludedPatterns = excludedPatternsField.text
            .split(Regex("[,;\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return CallChainOptions(
            projectOnly = projectOnly.isSelected,
            skipAccessors = skipAccessors.isSelected,
            skipConstructors = skipConstructors.isSelected,
            skipPrivateMethods = skipPrivateMethods.isSelected,
            skipFrameworkCalls = skipFrameworkCalls.isSelected,
            maxDepth = (maxDepthSpinner.value as Number).toInt(),
            excludedPatterns = excludedPatterns,
        )
    }

    fun analyze(element: PsiElement) {

        if (project.isDisposed || !element.isValid) {
            return
        }

        lastElement = element

        val options = currentOptions()

        status.text = "正在分析 Java 正向调用链…"

        ApplicationManager.getApplication()
            .executeOnPooledThread {

                try {

                    val snapshot = ReadAction.compute<
                            ChainSnapshot?,
                            RuntimeException
                            > {

                        if (!element.isValid) {
                            null
                        } else {
                            CallChainAnalyzer.analyze(
                                project,
                                element,
                                options,
                            )
                        }
                    }

                    SwingUtilities.invokeLater {

                        if (project.isDisposed) {
                            return@invokeLater
                        }

                        if (snapshot == null) {

                            canvas.setSnapshot(
                                ChainSnapshot(
                                    rootId = "",
                                    nodes = emptyList(),
                                    edges = emptyList(),
                                    truncated = false,
                                )
                            )

                            status.text =
                                "当前调用链功能暂时仅支持 Java 方法"

                        } else {

                            canvas.setSnapshot(snapshot)

                            val suffix = if (snapshot.truncated) {
                                "（已达到分析上限）"
                            } else {
                                ""
                            }

                            status.text =
                                "节点 ${snapshot.nodes.size} · " +
                                        "连线 ${snapshot.edges.size} · " +
                                        "Java 正向调用链$suffix"
                        }
                    }

                } catch (e: Throwable) {

                    SwingUtilities.invokeLater {

                        if (project.isDisposed) {
                            return@invokeLater
                        }

                        status.text =
                            "调用链分析失败：" +
                                    (e.message ?: e.javaClass.simpleName)
                    }
                }
            }
    }
}

/**
 * Call-chain canvas.
 *
 * Keeps the original node rendering and source navigation behavior.
 */
private class CallChainCanvas(
    private val project: Project,
) : JPanel() {

    private var snapshot: ChainSnapshot? = null

    private val boxes = linkedMapOf<
            String,
            java.awt.Rectangle
            >()

    init {

        background = Color(
            0xFA,
            0xFB,
            0xFD,
        )

        preferredSize = Dimension(
            1000,
            600,
        )

        addMouseListener(object : MouseAdapter() {

            override fun mouseClicked(e: MouseEvent) {

                val hit = boxes.entries.firstOrNull {
                    it.value.contains(e.point)
                } ?: return

                val node = snapshot?.nodes?.firstOrNull {
                    it.id == hit.key
                } ?: return

                val url = node.fileUrl ?: return

                val file = VirtualFileManager.getInstance()
                    .findFileByUrl(url)
                    ?: return

                OpenFileDescriptor(
                    project,
                    file,
                    node.offset.coerceAtMost(file.length.toInt()),
                ).navigate(true)
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

        if (nodes.isEmpty()) {
            return Dimension(1000, 600)
        }

        // Compatible with older Kotlin collection APIs.
        val minDepth = nodes.map { it.depth }.min() ?: 0
        val maxDepth = nodes.map { it.depth }.max() ?: 0

        val layers = nodes.groupBy {
            it.depth
        }

        val maxRows = layers.values
            .map { it.size }
            .max() ?: 1

        return Dimension(
            max(
                1000,
                (maxDepth - minDepth + 1) * 250 + 100,
            ),
            max(
                600,
                maxRows * 100 + 100,
            ),
        )
    }

    override fun paintComponent(g: Graphics) {

        super.paintComponent(g)

        val data = snapshot ?: return

        val g2 = g.create() as Graphics2D

        g2.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON,
        )

        boxes.clear()

        if (data.nodes.isEmpty()) {
            g2.dispose()
            return
        }

        val minDepth = data.nodes
            .map { it.depth }
            .min() ?: 0

        val layers = data.nodes
            .groupBy { it.depth }
            .toSortedMap()

        val positions = linkedMapOf<
                String,
                java.awt.Rectangle
                >()

        // Calculate node positions.
        for ((depth, layer) in layers) {

            val x = 35 + (depth - minDepth) * 250

            layer.sortedBy {
                it.label
            }.forEachIndexed { index, node ->

                val y = 35 + index * 100

                val rect = java.awt.Rectangle(
                    x,
                    y,
                    205,
                    62,
                )

                positions[node.id] = rect
                boxes[node.id] = rect
            }
        }

        // Draw edges.
        for (edge in data.edges) {

            val from = positions[edge.from] ?: continue
            val to = positions[edge.to] ?: continue

            val x1 = from.x + from.width
            val y1 = from.y + from.height / 2

            val x2 = to.x
            val y2 = to.y + to.height / 2

            g2.color = if (edge.cycle) {
                Color(0xD9, 0x77, 0x06)
            } else {
                Color(0x91, 0x9B, 0xAA)
            }

            g2.stroke = if (edge.cycle) {

                BasicStroke(
                    1.5f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    10f,
                    floatArrayOf(5f, 4f),
                    0f,
                )

            } else {
                BasicStroke(1.3f)
            }

            val curve = Path2D.Float()

            curve.moveTo(
                x1.toFloat(),
                y1.toFloat(),
            )

            val middle = (x1 + x2) / 2f

            curve.curveTo(
                middle,
                y1.toFloat(),
                middle,
                y2.toFloat(),
                x2.toFloat(),
                y2.toFloat(),
            )

            g2.draw(curve)

            // Arrow.
            val arrow = Path2D.Float()

            arrow.moveTo(
                x2.toFloat(),
                y2.toFloat(),
            )

            arrow.lineTo(
                (x2 - 8).toFloat(),
                (y2 - 4).toFloat(),
            )

            arrow.lineTo(
                (x2 - 8).toFloat(),
                (y2 + 4).toFloat(),
            )

            arrow.closePath()

            g2.fill(arrow)
        }

        // Draw nodes.
        for (node in data.nodes) {

            val rect = positions[node.id] ?: continue

            g2.color = when {

                node.root -> Color(
                    0xD9,
                    0xF5,
                    0xE3,
                )

                node.external -> Color(
                    0xFF,
                    0xF1,
                    0xD6,
                )

                else -> Color.WHITE
            }

            g2.fillRoundRect(
                rect.x,
                rect.y,
                rect.width,
                rect.height,
                12,
                12,
            )

            g2.color = when {

                node.root -> Color(
                    0x16,
                    0xA3,
                    0x4A,
                )

                node.external -> Color(
                    0xD9,
                    0x77,
                    0x06,
                )

                else -> Color(
                    0xA8,
                    0xB0,
                    0xBC,
                )
            }

            g2.stroke = BasicStroke(
                if (node.root) 2f else 1f
            )

            g2.drawRoundRect(
                rect.x,
                rect.y,
                rect.width,
                rect.height,
                12,
                12,
            )

            // Method name.
            g2.color = Color(
                0x20,
                0x28,
                0x33,
            )

            g2.font = font.deriveFont(
                Font.BOLD,
                12f,
            )

            g2.drawString(
                node.label.take(27),
                rect.x + 10,
                rect.y + 24,
            )

            // Method location.
            g2.color = Color(
                0x70,
                0x78,
                0x86,
            )

            g2.font = font.deriveFont(10f)

            val marker = when {
                node.root -> "当前方法"
                node.external -> "依赖库 · 停止递归"
                else -> node.detail
            }

            g2.drawString(
                marker.take(31),
                rect.x + 10,
                rect.y + 44,
            )
        }

        g2.dispose()
    }
}
