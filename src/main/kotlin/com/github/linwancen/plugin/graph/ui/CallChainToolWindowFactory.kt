
package com.github.linwancen.plugin.graph.ui

import com.github.linwancen.plugin.graph.parser.Parser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
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
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.util.WeakHashMap
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
 * Call Chain tool window.
 *
 * Only analyzes Java methods.
 * The existing Graph tool window is not modified.
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

        private val panels =
            WeakHashMap<Project, CallChainPanel>()

        @JvmStatic
        fun showFor(
            project: Project,
            element: PsiElement,
        ) {
            ApplicationManager.getApplication().invokeLater {

                if (project.isDisposed || !element.isValid) {
                    return@invokeLater
                }

                val toolWindow = ToolWindowManager
                    .getInstance(project)
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
 * Call Chain options.
 */
private data class CallChainOptions(
    val projectOnly: Boolean = true,
    val skipAccessors: Boolean = true,
    val skipConstructors: Boolean = true,
    val skipPrivateMethods: Boolean = false,
    val skipFrameworkCalls: Boolean = true,
    val maxDepth: Int = 5,
    val excludedPatterns: List<String> = emptyList(),
)

/**
 * A class shown as one sequence-diagram column.
 */
private data class SequenceParticipant(
    val id: String,
    val className: String,
    val simpleName: String,
)

/**
 * One method invocation event.
 *
 * The event represents:
 *
 * callerClass.callerMethod()
 *     -> calleeClass.calleeMethod()
 */
private data class SequenceEvent(
    val index: Int,
    val callerId: String,
    val callerClass: String,
    val callerMethod: String,
    val calleeId: String,
    val calleeClass: String,
    val calleeMethod: String,
    val fileUrl: String?,
    val offset: Int,
    val depth: Int,
    val selfCall: Boolean,
    val cycle: Boolean,
)

/**
 * Sequence diagram snapshot.
 */
private data class ChainSnapshot(
    val participants: List<SequenceParticipant>,
    val events: List<SequenceEvent>,
    val truncated: Boolean,
)

private object CallChainAnalyzer {

    private const val MAX_EVENTS = 500

    private val frameworkPackages = listOf(
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

    fun analyze(
        project: Project,
        selected: PsiElement,
        options: CallChainOptions,
    ): ChainSnapshot? {

        val target = Parser.graphTarget(project, selected)
            ?: selected

        val root = target as? PsiMethod
            ?: return null

        if (!isProjectJavaMethod(project, root)) {
            return null
        }

        val rootClass = participant(root)
            ?: return null

        val participants =
            linkedMapOf<String, SequenceParticipant>()

        participants[rootClass.id] = rootClass

        val events = mutableListOf<SequenceEvent>()

        val parserMap = Parser.parserMap()

        var truncated = false

        val expanded = mutableSetOf<String>()

        /**
         * Find project Java methods called by this method.
         *
         * Dependency methods are discarded here.
         * Compiled PSI is never recursively traversed.
         */
        fun directCallees(method: PsiMethod): List<PsiMethod> {

            if (!isProjectJavaMethod(project, method)) {
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

                            val targetMethod =
                                graphTarget as? PsiMethod
                                    ?: continue

                            if (!targetMethod.isValid) {
                                continue
                            }

                            if (targetMethod == method) {
                                continue
                            }

                            // Java only.
                            if (
                                targetMethod.containingFile
                                    ?.language?.id != "JAVA"
                            ) {
                                continue
                            }

                            // Only project source methods.
                            if (
                                !isProjectJavaMethod(
                                    project,
                                    targetMethod,
                                )
                            ) {
                                continue
                            }

                            val id = methodId(targetMethod)

                            result.putIfAbsent(
                                id,
                                targetMethod,
                            )
                        }
                    } catch (_: RuntimeException) {
                        // Ignore incomplete PSI references.
                    }

                    super.visitElement(element)
                }
            })

            return result.values.toList()
        }

        /**
         * Decide whether a method should be visible.
         *
         * A hidden method is still recursively analyzed.
         */
        fun shouldDisplay(method: PsiMethod): Boolean {

            if (
                isExcludedClassOrPackage(
                    method,
                    options.excludedPatterns,
                )
            ) {
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
                isFrameworkMethod(method)
            ) {
                return false
            }

            return true
        }

        /**
         * Recursive forward traversal.
         *
         * callerClass/callerMethod identify the nearest visible
         * invocation context.
         */
        fun walk(
            method: PsiMethod,
            callerClass: SequenceParticipant,
            callerMethod: String,
            depth: Int,
            path: Set<String>,
        ) {

            if (depth >= options.maxDepth) {
                if (directCallees(method).isNotEmpty()) {
                    truncated = true
                }
                return
            }

            val currentId = methodId(method)

            // Avoid expanding the same method repeatedly.
            if (!expanded.add(currentId)) {
                return
            }

            val callees = directCallees(method)

            for (callee in callees) {

                if (events.size >= MAX_EVENTS) {
                    truncated = true
                    return
                }

                if (!callee.isValid) {
                    continue
                }

                val calleeId = methodId(callee)

                val calleeClass = participant(callee)
                    ?: continue

                val calleeMethod =
                    callee.name ?: "<anonymous>"

                val cycle = calleeId in path

                val visible = shouldDisplay(callee)

                if (visible) {

                    participants.putIfAbsent(
                        calleeClass.id,
                        calleeClass,
                    )

                    val nav = callee.navigationElement

                    val file = nav.containingFile?.virtualFile
                        ?: callee.containingFile?.virtualFile

                    events.add(
                        SequenceEvent(
                            index = events.size,
                            callerId = callerClass.id,
                            callerClass = callerClass.simpleName,
                            callerMethod = callerMethod,
                            calleeId = calleeClass.id,
                            calleeClass = calleeClass.simpleName,
                            calleeMethod = calleeMethod,
                            fileUrl = file?.url,
                            offset = nav.textOffset.coerceAtLeast(0),
                            depth = depth + 1,
                            selfCall = callerClass.id == calleeClass.id,
                            cycle = cycle,
                        )
                    )

                    if (cycle) {
                        continue
                    }

                    walk(
                        method = callee,
                        callerClass = calleeClass,
                        callerMethod = calleeMethod,
                        depth = depth + 1,
                        path = path + calleeId,
                    )

                } else {

                    // Hidden project method:
                    // continue traversing without adding a column or event.
                    if (cycle) {
                        continue
                    }

                    walk(
                        method = callee,
                        callerClass = callerClass,
                        callerMethod = callerMethod,
                        depth = depth + 1,
                        path = path + calleeId,
                    )
                }
            }
        }

        walk(
            method = root,
            callerClass = rootClass,
            callerMethod = root.name ?: "<root>",
            depth = 0,
            path = setOf(methodId(root)),
        )

        return ChainSnapshot(
            participants = participants.values.toList(),
            events = events.toList(),
            truncated = truncated,
        )
    }

    /**
     * Only methods in project Java source roots are allowed.
     *
     * This excludes JDK, Maven/Gradle dependencies and external libraries.
     */
    private fun isProjectJavaMethod(
        project: Project,
        method: PsiMethod,
    ): Boolean {

        if (
            method.containingFile?.language?.id != "JAVA"
        ) {
            return false
        }

        val file = method.containingFile?.virtualFile
            ?: return false

        if (file.fileSystem !is LocalFileSystem) {
            return false
        }

        return ProjectFileIndex
            .getInstance(project)
            .isInContent(file)
    }

    private fun methodId(method: PsiMethod): String {

        val nav = method.navigationElement

        val file = nav.containingFile?.virtualFile
            ?: method.containingFile?.virtualFile

        return "${file?.url ?: "unknown"}#${nav.textOffset}"
    }

    private fun participant(
        method: PsiMethod,
    ): SequenceParticipant? {

        val containingClass = method.containingClass
            ?: return null

        val qualifiedName = containingClass.qualifiedName
            ?: return null

        val simpleName = containingClass.name
            ?: qualifiedName.substringAfterLast('.')

        return SequenceParticipant(
            id = qualifiedName,
            className = qualifiedName,
            simpleName = simpleName,
        )
    }

    /**
     * Package and class exclusion.
     *
     * com.example.common
     * com.example.service.UserService
     * com.example.util.*
     */
    private fun isExcludedClassOrPackage(
        method: PsiMethod,
        patterns: List<String>,
    ): Boolean {

        val qualifiedName = method.containingClass
            ?.qualifiedName
            ?: return false

        return patterns.any { raw ->

            val pattern = raw
                .trim()
                .removeSuffix(".*")
                .trimEnd('.')

            pattern.isNotEmpty() && (
                    qualifiedName == pattern ||
                            qualifiedName.startsWith("$pattern.") ||
                            qualifiedName.startsWith("$pattern$")
                    )
        }
    }

    private fun isFrameworkMethod(
        method: PsiMethod,
    ): Boolean {

        val qualifiedName = method.containingClass
            ?.qualifiedName
            ?: return false

        return frameworkPackages.any {
            qualifiedName.startsWith(it)
        }
    }

    /**
     * Only hide trivial JavaBean accessors.
     */
    private fun isTrivialAccessor(
        method: PsiMethod,
    ): Boolean {

        val name = method.name

        val kind = when {
            name.matches(Regex("^get[A-Z_].*")) -> "get"
            name.matches(Regex("^is[A-Z_].*")) -> "is"
            name.matches(Regex("^set[A-Z_].*")) -> "set"
            else -> return false
        }

        val body = method.body ?: return false

        val statements = body.statements

        if (statements.size != 1) {
            return false
        }

        val statement = statements[0].text
            .replace(Regex("\\s+"), "")
            .removeSuffix(";")

        return when (kind) {

            "get", "is" ->
                Regex(
                    "^return(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*$"
                ).matches(statement)

            "set" ->
                Regex(
                    "^(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*=(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*$"
                ).matches(statement)

            else -> false
        }
    }
}

/**
 * Call Chain panel.
 */
private class CallChainPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    private val status = JLabel(
        "点击 Graph 中的 Java 方法节点，查看正向调用时序图"
    )

    private val canvas = CallChainCanvas(project)

    private var lastElement: PsiElement? = null

    private var analysisSequence = 0

    private val projectOnly = JCheckBox(
        "仅项目代码",
        true,
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
        "过滤框架调用",
        true,
    )

    private val maxDepthSpinner = JSpinner(
        SpinnerNumberModel(5, 1, 30, 1)
    )

    private val excludedPatternsField = JTextField(32)

    private companion object {

        const val KEY_PROJECT_ONLY =
            "draw.graph.callchain.projectOnly"

        const val KEY_SKIP_ACCESSORS =
            "draw.graph.callchain.skipAccessors"

        const val KEY_SKIP_CONSTRUCTORS =
            "draw.graph.callchain.skipConstructors"

        const val KEY_SKIP_PRIVATE =
            "draw.graph.callchain.skipPrivate"

        const val KEY_SKIP_FRAMEWORK =
            "draw.graph.callchain.skipFramework"

        const val KEY_MAX_DEPTH =
            "draw.graph.callchain.maxDepth"

        const val KEY_EXCLUDED_PATTERNS =
            "draw.graph.callchain.excludedPatterns"
    }

    init {

        loadSettings()

        val northPanel = JPanel()

        northPanel.layout = javax.swing.BoxLayout(
            northPanel,
            javax.swing.BoxLayout.Y_AXIS,
        )

        // Toolbar.
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

        val refreshButton = JButton("刷新")

        refreshButton.addActionListener {
            lastElement?.let { analyze(it) }
        }

        toolbar.add(
            refreshButton,
            BorderLayout.EAST,
        )

        northPanel.add(toolbar)

        // Basic filters.
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

        listOf(
            projectOnly,
            skipAccessors,
            skipConstructors,
            skipPrivateMethods,
            skipFrameworkCalls,
        ).forEach { checkbox ->

            checkbox.addActionListener {
                saveSettings()
                lastElement?.let { analyze(it) }
            }

            filterPanel.add(checkbox)
        }

        northPanel.add(filterPanel)

        // Advanced options.
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

        advancedPanel.add(
            JLabel("最大调用层数：")
        )

        maxDepthSpinner.preferredSize = Dimension(
            65,
            26,
        )

        maxDepthSpinner.addChangeListener {
            saveSettings()
            lastElement?.let { analyze(it) }
        }

        advancedPanel.add(maxDepthSpinner)

        advancedPanel.add(
            JLabel("排除包/类：")
        )

        excludedPatternsField.preferredSize = Dimension(
            300,
            26,
        )

        excludedPatternsField.toolTipText =
            "多个规则使用逗号、分号或空格分隔，例如 com.example.common,com.example.service.UserService"

        excludedPatternsField.addActionListener {
            saveSettings()
            lastElement?.let { analyze(it) }
        }

        advancedPanel.add(excludedPatternsField)

        val applyButton = JButton("应用")

        applyButton.addActionListener {
            saveSettings()
            lastElement?.let { analyze(it) }
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
     * Load project-specific settings.
     */
    private fun loadSettings() {

        val properties = PropertiesComponent
            .getInstance(project)

        projectOnly.isSelected =
            properties.getBoolean(
                KEY_PROJECT_ONLY,
                true,
            )

        skipAccessors.isSelected =
            properties.getBoolean(
                KEY_SKIP_ACCESSORS,
                true,
            )

        skipConstructors.isSelected =
            properties.getBoolean(
                KEY_SKIP_CONSTRUCTORS,
                true,
            )

        skipPrivateMethods.isSelected =
            properties.getBoolean(
                KEY_SKIP_PRIVATE,
                false,
            )

        skipFrameworkCalls.isSelected =
            properties.getBoolean(
                KEY_SKIP_FRAMEWORK,
                true,
            )

        maxDepthSpinner.value =
            properties.getInt(
                KEY_MAX_DEPTH,
                5,
            ).coerceIn(1, 30)

        excludedPatternsField.text =
            properties.getValue(
                KEY_EXCLUDED_PATTERNS,
                "",
            )
    }

    /**
     * Save project-specific settings.
     */
    private fun saveSettings() {

        if (project.isDisposed) {
            return
        }

        val properties = PropertiesComponent
            .getInstance(project)

        properties.setValue(
            KEY_PROJECT_ONLY,
            projectOnly.isSelected,
        )

        properties.setValue(
            KEY_SKIP_ACCESSORS,
            skipAccessors.isSelected,
        )

        properties.setValue(
            KEY_SKIP_CONSTRUCTORS,
            skipConstructors.isSelected,
        )

        properties.setValue(
            KEY_SKIP_PRIVATE,
            skipPrivateMethods.isSelected,
        )

        properties.setValue(
            KEY_SKIP_FRAMEWORK,
            skipFrameworkCalls.isSelected,
        )

        properties.setValue(
            KEY_MAX_DEPTH,
            (maxDepthSpinner.value as Number).toInt(),
        )

        properties.setValue(
            KEY_EXCLUDED_PATTERNS,
            excludedPatternsField.text.trim(),
        )
    }

    private fun currentOptions(): CallChainOptions {

        val patterns = excludedPatternsField.text
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
            excludedPatterns = patterns,
        )
    }

    fun analyze(element: PsiElement) {

        if (project.isDisposed || !element.isValid) {
            return
        }

        lastElement = element

        saveSettings()

        val options = currentOptions()

        val sequence = ++analysisSequence

        status.text = "正在分析 Java 正向调用时序图…"

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

                        if (
                            project.isDisposed ||
                            sequence != analysisSequence
                        ) {
                            return@invokeLater
                        }

                        if (snapshot == null) {

                            canvas.setSnapshot(
                                ChainSnapshot(
                                    participants = emptyList(),
                                    events = emptyList(),
                                    truncated = false,
                                )
                            )

                            status.text =
                                "当前功能仅支持项目内 Java 方法"

                        } else {

                            canvas.setSnapshot(snapshot)

                            val suffix = if (snapshot.truncated) {
                                " · 已达到分析上限"
                            } else {
                                ""
                            }

                            status.text =
                                "类 ${snapshot.participants.size} · " +
                                        "调用 ${snapshot.events.size}" +
                                        suffix
                        }
                    }

                } catch (e: Throwable) {

                    SwingUtilities.invokeLater {

                        if (
                            project.isDisposed ||
                            sequence != analysisSequence
                        ) {
                            return@invokeLater
                        }

                        status.text =
                            "分析失败：" +
                                    (e.message ?: e.javaClass.simpleName)
                    }
                }
            }
    }
}

/**
 * SequenceDiagram-style canvas.
 *
 * Columns = Java classes.
 * Rows = invocation order.
 * Arrows = method calls.
 */
private class CallChainCanvas(
    private val project: Project,
) : JPanel() {

    private var snapshot: ChainSnapshot? = null

    private val eventBounds =
        linkedMapOf<Int, java.awt.Rectangle>()

    private val participantX =
        linkedMapOf<String, Int>()

    private val headerHeight = 62
    private val rowHeight = 68
    private val columnWidth = 230
    private val leftPadding = 35
    private val topPadding = 18

    private val headerColor = Color(
        0xE8,
        0xF0,
        0xFA,
    )

    private val lineColor = Color(
        0xB5,
        0xBF,
        0xCC,
    )

    private val callColor = Color(
        0x3B,
        0x82,
        0xD6,
    )

    init {

        background = Color(
            0xFA,
            0xFB,
            0xFD,
        )

        preferredSize = Dimension(
            1100,
            600,
        )

        addMouseListener(object : MouseAdapter() {

            override fun mouseClicked(e: MouseEvent) {

                val hit = eventBounds.entries
                    .firstOrNull {
                        it.value.contains(e.point)
                    } ?: return

                val event = snapshot
                    ?.events
                    ?.firstOrNull {
                        it.index == hit.key
                    } ?: return

                val url = event.fileUrl ?: return

                val file = VirtualFileManager
                    .getInstance()
                    .findFileByUrl(url)
                    ?: return

                OpenFileDescriptor(
                    project,
                    file,
                    event.offset.coerceAtMost(
                        file.length.toInt()
                    ),
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

        val data = snapshot
            ?: return Dimension(1100, 600)

        val columnCount = max(
            1,
            data.participants.size,
        )

        return Dimension(
            max(
                1100,
                leftPadding * 2 +
                        columnCount * columnWidth,
            ),
            max(
                600,
                topPadding +
                        headerHeight +
                        data.events.size * rowHeight +
                        80,
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

        g2.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )

        eventBounds.clear()
        participantX.clear()

        if (data.participants.isEmpty()) {

            g2.color = Color.GRAY
            g2.drawString(
                "没有找到符合条件的项目 Java 方法调用",
                30,
                40,
            )

            g2.dispose()
            return
        }

        // -------------------------------------------------
        // 1. Calculate participant columns.
        // -------------------------------------------------

        data.participants.forEachIndexed { index, participant ->

            val x = leftPadding +
                    index * columnWidth +
                    columnWidth / 2

            participantX[participant.id] = x
        }

        // -------------------------------------------------
        // 2. Draw class headers.
        // -------------------------------------------------

        val headerY = topPadding

        for (participant in data.participants) {

            val centerX = participantX[participant.id]
                ?: continue

            val width = 184
            val height = 42

            val x = centerX - width / 2

            g2.color = headerColor

            g2.fillRoundRect(
                x,
                headerY,
                width,
                height,
                10,
                10,
            )

            g2.color = Color(
                0x8B,
                0xA5,
                0xC4,
            )

            g2.stroke = BasicStroke(1f)

            g2.drawRoundRect(
                x,
                headerY,
                width,
                height,
                10,
                10,
            )

            g2.color = Color(
                0x25,
                0x35,
                0x4A,
            )

            g2.font = font.deriveFont(
                Font.BOLD,
                12f,
            )

            val metrics: FontMetrics =
                g2.fontMetrics

            val text = participant.simpleName

            val displayName = if (
                metrics.stringWidth(text) > width - 14
            ) {
                text.take(20) + "…"
            } else {
                text
            }

            g2.drawString(
                displayName,
                centerX -
                        metrics.stringWidth(displayName) / 2,
                headerY + 26,
            )
        }

        // -------------------------------------------------
        // 3. Draw lifelines.
        // -------------------------------------------------

        val lineStart = headerY + headerHeight - 12

        val lineEnd = max(
            height,
            lineStart +
                    data.events.size * rowHeight +
                    30,
        )

        g2.color = lineColor

        g2.stroke = BasicStroke(
            1f,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_ROUND,
            10f,
            floatArrayOf(5f, 5f),
            0f,
        )

        for (participant in data.participants) {

            val x = participantX[participant.id]
                ?: continue

            g2.drawLine(
                x,
                lineStart,
                x,
                lineEnd,
            )
        }

        // -------------------------------------------------
        // 4. Draw invocation arrows in source order.
        // -------------------------------------------------

        for (event in data.events) {

            val callerX = participantX[event.callerId]
                ?: continue

            val calleeX = participantX[event.calleeId]
                ?: continue

            val y = lineStart +
                    (event.index + 1) * rowHeight

            g2.color = if (event.cycle) {
                Color(0xD9, 0x77, 0x06)
            } else {
                callColor
            }

            g2.stroke = BasicStroke(
                1.5f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )

            if (event.selfCall) {

                drawSelfCall(
                    g2,
                    callerX,
                    y,
                    event.cycle,
                )

                val label = event.calleeMethod

                g2.color = Color(
                    0x37,
                    0x4B,
                    0x63,
                )

                g2.font = font.deriveFont(
                    11f,
                )

                g2.drawString(
                    label,
                    callerX + 20,
                    y - 10,
                )

                eventBounds[event.index] =
                    java.awt.Rectangle(
                        callerX - 4,
                        y - 27,
                        190,
                        48,
                    )

            } else {

                val startX = callerX
                val endX = calleeX

                val direction = if (
                    endX > startX
                ) {
                    1
                } else {
                    -1
                }

                val arrowStart = startX +
                        direction * 5

                val arrowEnd = endX -
                        direction * 5

                g2.drawLine(
                    arrowStart,
                    y,
                    arrowEnd,
                    y,
                )

                drawArrowHead(
                    g2,
                    arrowEnd,
                    y,
                    direction,
                )

                // Method name above the arrow.
                val label = event.calleeMethod

                g2.color = Color(
                    0x37,
                    0x4B,
                    0x63,
                )

                g2.font = font.deriveFont(
                    11f,
                    Font.BOLD,
                )

                val metrics = g2.fontMetrics

                val labelWidth = metrics.stringWidth(label)

                val labelX = (
                        startX + endX
                        ) / 2 - labelWidth / 2

                g2.drawString(
                    label,
                    labelX,
                    y - 8,
                )

                // Clickable area around the invocation.
                eventBounds[event.index] =
                    java.awt.Rectangle(
                        minOf(startX, endX),
                        y - 24,
                        kotlin.math.abs(endX - startX),
                        42,
                    )
            }

            // Depth marker.
            g2.color = Color(
                0x8B,
                0x95,
                0xA5,
            )

            g2.font = font.deriveFont(9f)

            g2.drawString(
                "L${event.depth}",
                8,
                y + 4,
            )
        }

        // -------------------------------------------------
        // 5. Draw root method label.
        // -------------------------------------------------

        val first = data.events.firstOrNull()

        if (first == null) {

            g2.color = Color.GRAY

            g2.drawString(
                "当前方法没有找到符合过滤条件的调用",
                30,
                lineEnd + 25,
            )
        }

        g2.dispose()
    }

    /**
     * Draw a self-call loop.
     */
    private fun drawSelfCall(
        g: Graphics2D,
        x: Int,
        y: Int,
        cycle: Boolean,
    ) {

        val loopWidth = 48
        val loopHeight = 30

        val path = Path2D.Float()

        path.moveTo(
            x.toFloat(),
            y,
        )

        path.lineTo(
            (x + loopWidth).toFloat(),
            y,
        )

        path.lineTo(
            (x + loopWidth).toFloat(),
            (y + loopHeight).toFloat(),
        )

        path.lineTo(
            x.toFloat(),
            (y + loopHeight).toFloat(),
        )

        g.draw(path)

        // Arrow points back to the same lifeline.
        val arrow = Path2D.Float()

        arrow.moveTo(
            x.toFloat(),
            (y + loopHeight).toFloat(),
        )

        arrow.lineTo(
            (x + 7).toFloat(),
            (y + loopHeight - 5).toFloat(),
        )

        arrow.moveTo(
            x.toFloat(),
            (y + loopHeight).toFloat(),
        )

        arrow.lineTo(
            (x + 7).toFloat(),
            (y + loopHeight + 5).toFloat(),
        )

        g.draw(arrow)
    }

    /**
     * Draw an arrow head.
     */
    private fun drawArrowHead(
        g: Graphics2D,
        x: Int,
        y: Int,
        direction: Int,
    ) {

        val arrow = Path2D.Float()

        arrow.moveTo(
            x.toFloat(),
            y.toFloat(),
        )

        arrow.lineTo(
            (x - direction * 8).toFloat(),
            (y - 4).toFloat(),
        )

        arrow.moveTo(
            x.toFloat(),
            y.toFloat(),
        )

        arrow.lineTo(
            (x - direction * 8).toFloat(),
            (y + 4).toFloat(),
        )

        g.draw(arrow)
    }
}
