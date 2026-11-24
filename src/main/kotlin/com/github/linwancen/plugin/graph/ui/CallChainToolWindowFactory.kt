package com.github.linwancen.plugin.graph.ui

import com.github.linwancen.plugin.graph.parser.Parser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
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
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.WeakHashMap
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Call Chain tool window.
 *
 * The analyzer walks only downward from the selected Java method. It is
 * intentionally index-light: references are not searched upstream, and every
 * method/lambda body is visited at most once per analysis.
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

private data class CallChainOptions(
    val projectOnly: Boolean = true,
    val skipAccessors: Boolean = true,
    val skipConstructors: Boolean = true,
    val skipPrivateMethods: Boolean = false,
    val skipFrameworkCalls: Boolean = true,
    val expandAmbiguousImplementations: Boolean = false,
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
 * callerClass.callerMethod() -> calleeClass.calleeMethod()
 */
private data class SequenceEvent(
    val index: Int,
    val callerId: String,
    val callerMethod: String,
    val calleeId: String,
    val calleeMethod: String,
    val fileUrl: String?,
    val offset: Int,
    val depth: Int,
    val selfCall: Boolean,
    val cycle: Boolean,
    val callback: Boolean,
    val lambda: Boolean,
    val implementation: Boolean,
    /** Interface call whose concrete receiver could not be inferred. */
    val ambiguous: Boolean = false,
    /** Last event row belonging to this invocation, used to draw its return. */
    var returnIndex: Int = index,
)

/**
 * Sequence diagram snapshot.
 */
private data class ChainSnapshot(
    val participants: List<SequenceParticipant>,
    val events: List<SequenceEvent>,
    val truncated: Boolean,
    /** Includes invocation rows and their distinct return rows. */
    val rowCount: Int = 0,
    val ambiguousCalls: Int = 0,
)

/**
 * A method or lambda that can be expanded.
 *
 * Lambda bodies are modelled as synthetic callable nodes. This avoids creating
 * fake PSI and lets one visitor/cache path handle both methods and lambdas.
 */
private sealed class CallableNode {

    abstract val key: String
    abstract val name: String
    abstract val containingClass: PsiClass?
    abstract val body: PsiElement?
    abstract val fileUrl: String?
    abstract val offset: Int
    abstract val lambda: Boolean

    class Method(
        val method: PsiMethod,
        override val key: String,
    ) : CallableNode() {

        override val name: String
            get() = method.name

        override val containingClass: PsiClass?
            get() = method.containingClass

        override val body: PsiElement?
            get() = method.body

        override val fileUrl: String?
            get() = method.containingFile?.virtualFile?.url

        override val offset: Int
            get() = method.navigationElement.textOffset

        override val lambda: Boolean
            get() = false
    }

    class Lambda(
        val expression: PsiLambdaExpression,
        override val key: String,
        override val name: String,
        override val containingClass: PsiClass?,
        override val fileUrl: String?,
        override val offset: Int,
    ) : CallableNode() {

        override val body: PsiElement?
            get() = expression.body

        override val lambda: Boolean
            get() = true
    }
}

/**
 * One edge discovered inside a callable body.
 *
 * Resolving targets here (instead of while walking the diagram) is important:
 * the same method can be reached from many call sites, but its body is visited
 * only once.
 */
private data class CallTarget(
    val node: CallableNode,
    val callback: Boolean = false,
    val implementation: Boolean = false,
    val ambiguous: Boolean = false,
)

private data class DirectCall(
    val site: PsiElement,
    val targets: List<CallTarget>,
)

private object CallChainAnalyzer {

    fun analyze(
        project: Project,
        selected: PsiElement,
        options: CallChainOptions,
        cancellation: AnalysisCancellation,
        onPhase: (String) -> Unit = {},
    ): ChainSnapshot? {

        val target = Parser.graphTarget(project, selected) ?: selected
        val rootMethod = target as? PsiMethod ?: return null

        val context = CallChainContext(
            project = project,
            options = options,
            cancellation = cancellation,
            onPhase = onPhase,
        )

        if (!context.isAllowedMethod(rootMethod)) {
            return null
        }

        val root = context.methodNode(rootMethod)
        val rootParticipant = context.participant(root) ?: return null

        // The root must stay the first participant: the canvas uses it for the
        // root activation bar.
        context.participants[rootParticipant.id] = rootParticipant

        cancellation.check()
        val path = HashSet<String>()
        path.add(root.key)
        context.walk(root, 0, path)

        return context.snapshot()
    }
}

/**
 * Per-analysis state. All caches are local to one snapshot, so editing code or
 * changing filters can never reuse stale PSI results.
 */
private class CallChainContext(
    private val project: Project,
    private val options: CallChainOptions,
    private val cancellation: AnalysisCancellation,
    private val onPhase: (String) -> Unit,
) {

    private companion object {

        const val MAX_EVENTS = 600
        const val MAX_UNIQUE_NODES = 160
        const val MAX_IMPLEMENTATIONS_PER_CALL = 16
        const val MAX_IMPLEMENTATION_EVENTS = 30

        val FRAMEWORK_PACKAGES = listOf(
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

        val STEREOTYPE_ANNOTATIONS = listOf(
            ".Component",
            ".Service",
            ".Repository",
            ".Controller",
            ".RestController",
            ".Named",
        )

        val WHITESPACE = Regex("\\s+")
        val GETTER_BODY = Regex(
            "^return(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*$"
        )
        val SETTER_BODY = Regex(
            "^(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*=(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*$"
        )
    }

    val participants = LinkedHashMap<String, SequenceParticipant>()
    val events = ArrayList<SequenceEvent>()

    var rowCount = 0
        private set

    var truncated = false
        private set

    var ambiguousCalls = 0
        private set

    private var implementationEvents = 0

    private val normalizedExclusions = options.excludedPatterns
        .asSequence()
        .map { it.trim().removeSuffix(".*").trimEnd('.') }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    private val searchScope = GlobalSearchScope.projectScope(project)
    private val dumbMode = DumbService.isDumb(project)

    private val methodIds = HashMap<PsiMethod, String>()
    private val methodNodes = HashMap<PsiMethod, CallableNode.Method>()
    private val lambdaNodes = HashMap<String, CallableNode.Lambda>()

    private val participantCache = HashMap<String, SequenceParticipant?>()
    private val allowedCache = HashMap<String, Boolean>()
    private val displayCache = HashMap<String, Boolean>()
    private val accessorCache = HashMap<String, Boolean>()
    private val frameworkCache = HashMap<String, Boolean>()
    private val callbackCache = HashMap<String, List<PsiMethod>>()
    private val directCallCache = HashMap<String, List<DirectCall>>()
    private val implementationCache = HashMap<String, List<PsiMethod>>()
    private val implementationOverflow = HashSet<String>()

    private val expandedNodes = HashSet<String>()

    fun snapshot(): ChainSnapshot {
        return ChainSnapshot(
            participants = participants.values.toList(),
            events = events.toList(),
            truncated = truncated,
            rowCount = rowCount,
            ambiguousCalls = ambiguousCalls,
        )
    }

    fun check() {
        cancellation.check()
        ProgressManager.checkCanceled()
    }

    fun methodKey(method: PsiMethod): String {
        return methodIds.getOrPut(method) {
            try {
                val nav = method.navigationElement
                val file = nav.containingFile?.virtualFile
                    ?: method.containingFile?.virtualFile
                "${file?.url ?: "unknown"}#${nav.textOffset}"
            } catch (_: RuntimeException) {
                "invalid#${System.identityHashCode(method)}"
            }
        }
    }

    fun methodNode(method: PsiMethod): CallableNode.Method {
        return methodNodes.getOrPut(method) {
            CallableNode.Method(method, methodKey(method))
        }
    }

    fun lambdaNode(expression: PsiLambdaExpression): CallableNode.Lambda {
        val file = expression.containingFile?.virtualFile
        val offset = expression.textOffset
        val key = "${file?.url ?: "unknown"}#lambda@$offset"

        return lambdaNodes.getOrPut(key) {
            val containingClass = PsiTreeUtil.getParentOfType(
                expression,
                PsiClass::class.java,
                false,
            )
            CallableNode.Lambda(
                expression = expression,
                key = key,
                name = lambdaName(expression, offset),
                containingClass = containingClass,
                fileUrl = file?.url,
                offset = offset,
            )
        }
    }

    private fun lambdaName(expression: PsiLambdaExpression, offset: Int): String {
        val line = try {
            val file = expression.containingFile
            val document = file?.let {
                PsiDocumentManager.getInstance(project).getDocument(it)
            }
            document?.getLineNumber(offset)?.plus(1)
        } catch (_: RuntimeException) {
            null
        }
        return "λ@${line ?: (offset + 1)}"
    }

    fun participant(node: CallableNode): SequenceParticipant? {
        val containingClass = node.containingClass ?: return null
        val id = classId(containingClass)

        return participantCache.getOrPut(id) {
            val qualifiedName = containingClass.qualifiedName
            SequenceParticipant(
                id = id,
                className = qualifiedName ?: id,
                simpleName = containingClass.name
                    ?: anonymousClassName(containingClass),
            )
        }
    }

    private fun classId(containingClass: PsiClass): String {
        val qualifiedName = containingClass.qualifiedName
        if (!qualifiedName.isNullOrBlank()) {
            return qualifiedName
        }
        val file = containingClass.containingFile?.virtualFile
        return "${file?.url ?: "unknown"}#class@${containingClass.textOffset}"
    }

    private fun anonymousClassName(containingClass: PsiClass): String {
        val outer = PsiTreeUtil.getParentOfType(
            containingClass,
            PsiClass::class.java,
            true,
        )
        val base = outer?.name ?: "匿名类"
        return "$base\$anon@${lineNumber(containingClass)}"
    }

    private fun lineNumber(element: PsiElement): Int {
        return try {
            val file = element.containingFile ?: return element.textOffset
            val document = PsiDocumentManager
                .getInstance(project)
                .getDocument(file)
                ?: return element.textOffset
            document.getLineNumber(element.textOffset) + 1
        } catch (_: RuntimeException) {
            element.textOffset
        }
    }

    fun addEvent(
        caller: CallableNode,
        callee: CallableNode,
        site: PsiElement,
        depth: Int,
        cycle: Boolean,
        callback: Boolean,
        lambda: Boolean,
        implementation: Boolean,
        ambiguous: Boolean,
    ): SequenceEvent? {

        if (events.size >= MAX_EVENTS) {
            truncated = true
            return null
        }

        val callerParticipant = participant(caller) ?: return null
        val calleeParticipant = participant(callee) ?: return null

        if (!participants.containsKey(callerParticipant.id)) {
            participants[callerParticipant.id] = callerParticipant
        }
        if (!participants.containsKey(calleeParticipant.id)) {
            participants[calleeParticipant.id] = calleeParticipant
        }

        val navigation = try {
            site.navigationElement
        } catch (_: RuntimeException) {
            site
        }
        val file = navigation.containingFile?.virtualFile
            ?: site.containingFile?.virtualFile

        val index = rowCount++
        val event = SequenceEvent(
            index = index,
            callerId = callerParticipant.id,
            callerMethod = caller.name,
            calleeId = calleeParticipant.id,
            calleeMethod = callee.name,
            fileUrl = file?.url,
            offset = navigation.textOffset.coerceAtLeast(0),
            depth = depth,
            selfCall = callerParticipant.id == calleeParticipant.id,
            cycle = cycle,
            callback = callback,
            lambda = lambda,
            implementation = implementation,
            ambiguous = ambiguous,
            returnIndex = index,
        )
        events.add(event)
        return event
    }

    private fun nextRow(): Int = rowCount++

    fun walk(
        node: CallableNode,
        depth: Int,
        path: MutableSet<String>,
    ) {
        check()

        if (depth >= options.maxDepth) {
            // Do not spend a PSI pass just to prove that a deeper call exists.
            if (node.body != null) {
                truncated = true
            }
            return
        }

        if (node.key !in expandedNodes) {
            if (expandedNodes.size >= MAX_UNIQUE_NODES) {
                truncated = true
                return
            }
            expandedNodes.add(node.key)
            val ownerName = node.containingClass?.name ?: "匿名类"
            onPhase("正在分析：$ownerName.${node.name}()")
        }

        val calls = directCalls(node)
        if (calls.isEmpty()) {
            return
        }

        for (call in calls) {
            check()
            if (events.size >= MAX_EVENTS) {
                truncated = true
                return
            }

            var targets = call.targets
            if (targets.isEmpty()) {
                continue
            }

            if (targets.size > 1) {
                val remaining = MAX_IMPLEMENTATION_EVENTS - implementationEvents
                if (remaining <= 0) {
                    // Keep one edge so the call itself stays visible even after
                    // the multi-implementation budget is exhausted.
                    truncated = true
                    targets = targets.subList(0, 1)
                } else if (targets.size > remaining) {
                    targets = targets.take(remaining)
                    implementationEvents += targets.size
                    truncated = true
                } else {
                    implementationEvents += targets.size
                }
            }

            for (target in targets) {
                check()
                val callee = target.node
                val cycle = callee.key in path
                val displayable = isDisplayable(callee)

                val event = if (displayable) {
                    addEvent(
                        caller = node,
                        callee = callee,
                        site = call.site,
                        depth = depth + 1,
                        cycle = cycle,
                        callback = target.callback,
                        lambda = callee.lambda,
                        implementation = target.implementation,
                        ambiguous = target.ambiguous,
                    )
                } else {
                    null
                }

                if (displayable && event == null) {
                    if (events.size >= MAX_EVENTS) {
                        truncated = true
                        return
                    }
                    // A callable without a containing class cannot be drawn, and
                    // walking through it would only produce invisible events.
                    continue
                }

                if (!cycle && event != null && callee.body != null) {
                    path.add(callee.key)
                    try {
                        walk(callee, depth + 1, path)
                    } finally {
                        path.remove(callee.key)
                    }
                }

                if (event != null) {
                    event.returnIndex = nextRow()
                }
            }
        }
    }

    fun isAllowedMethod(method: PsiMethod): Boolean {
        if (!method.isValid) {
            return false
        }
        val key = methodKey(method)
        return allowedCache.getOrPut(key) {
            if (method.containingFile?.language?.id != "JAVA") {
                false
            } else {
                val file = method.containingFile?.virtualFile
                if (file == null || file.fileSystem !is LocalFileSystem) {
                    false
                } else {
                    !options.projectOnly ||
                        ProjectFileIndex.getInstance(project).isInContent(file)
                }
            }
        }
    }

    private fun isAllowedClass(containingClass: PsiClass): Boolean {
        val file = containingClass.containingFile?.virtualFile ?: return false
        if (file.fileSystem !is LocalFileSystem) {
            return false
        }
        return !options.projectOnly ||
            ProjectFileIndex.getInstance(project).isInContent(file)
    }

    private fun isDisplayable(node: CallableNode): Boolean {
        if (node.lambda) {
            val containingClass = node.containingClass ?: return false
            if (!isAllowedClass(containingClass) ||
                isExcludedClass(containingClass) ||
                (options.skipFrameworkCalls && isFrameworkClass(containingClass))
            ) {
                return false
            }
            return true
        }

        val method = (node as? CallableNode.Method)?.method ?: return false
        val key = methodKey(method)
        return displayCache.getOrPut(key) {
            if (!isAllowedMethod(method)) {
                false
            } else if (isExcluded(method)) {
                false
            } else if (options.skipAccessors && isTrivialAccessor(method)) {
                false
            } else if (options.skipConstructors && method.isConstructor) {
                false
            } else if (options.skipPrivateMethods &&
                method.hasModifierProperty(PsiModifier.PRIVATE)
            ) {
                false
            } else if (options.skipFrameworkCalls && isFrameworkMethod(method)) {
                false
            } else {
                true
            }
        }
    }

    private fun isExcluded(method: PsiMethod): Boolean {
        val containingClass = method.containingClass ?: return false
        return isExcludedClass(containingClass)
    }

    private fun isExcludedClass(containingClass: PsiClass): Boolean {
        val qualifiedName = effectiveQualifiedName(containingClass) ?: return false
        return normalizedExclusions.any { pattern ->
            qualifiedName == pattern ||
                qualifiedName.startsWith("$pattern.") ||
                qualifiedName.startsWith("$pattern$")
        }
    }

    private fun isFrameworkMethod(method: PsiMethod): Boolean {
        val key = methodKey(method)
        return frameworkCache.getOrPut(key) {
            isFrameworkClass(method.containingClass)
        }
    }

    private fun isFrameworkClass(containingClass: PsiClass?): Boolean {
        val qualifiedName = effectiveQualifiedName(containingClass) ?: return false
        return FRAMEWORK_PACKAGES.any { qualifiedName.startsWith(it) }
    }

    /**
     * Anonymous classes have no qualified name. Use the nearest named enclosing
     * class so package/class filters still apply to callbacks and lambdas.
     */
    private fun effectiveQualifiedName(containingClass: PsiClass?): String? {
        var current = containingClass
        while (current != null) {
            val qualifiedName = current.qualifiedName
            if (!qualifiedName.isNullOrBlank()) {
                return qualifiedName
            }
            current = PsiTreeUtil.getParentOfType(
                current,
                PsiClass::class.java,
                true,
            )
        }
        return null
    }

    private fun isTrivialAccessor(method: PsiMethod): Boolean {
        val key = methodKey(method)
        return accessorCache.getOrPut(key) {
            val name = method.name
            val getter = isGetterName(name)
            val setter = isSetterName(name)
            if (!getter && !setter) {
                false
            } else {
                val statements = method.body?.statements
                if (statements == null || statements.size != 1) {
                    false
                } else {
                    val text = statements[0].text
                        .replace(WHITESPACE, "")
                        .removeSuffix(";")
                    if (getter) {
                        GETTER_BODY.matches(text)
                    } else {
                        SETTER_BODY.matches(text)
                    }
                }
            }
        }
    }

    private fun isGetterName(name: String): Boolean {
        if (name.length > 3 && name.startsWith("get")) {
            return isAccessorSuffix(name[3])
        }
        if (name.length > 2 && name.startsWith("is")) {
            return isAccessorSuffix(name[2])
        }
        return false
    }

    private fun isSetterName(name: String): Boolean {
        return name.length > 3 &&
            name.startsWith("set") &&
            isAccessorSuffix(name[3])
    }

    private fun isAccessorSuffix(c: Char): Boolean {
        return c == '_' || c.isUpperCase()
    }

    fun directCalls(node: CallableNode): List<DirectCall> {
        directCallCache[node.key]?.let { return it }
        check()
        val calls = try {
            DirectCallCollector(this, node).collect()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: RuntimeException) {
            emptyList()
        }
        directCallCache[node.key] = calls
        return calls
    }

    fun resolveTargets(call: PsiCallExpression): List<CallTarget> {
        val method = safe { call.resolveMethod() } ?: return emptyList()
        if (!method.isValid) {
            return emptyList()
        }

        if (!isDispatchable(method)) {
            return listOf(CallTarget(methodNode(method)))
        }

        val inferred = inferConcrete(call, method)
        if (inferred != null) {
            return listOf(
                if (isAllowedMethod(inferred)) {
                    resolvedTarget(inferred, method)
                } else {
                    CallTarget(methodNode(method))
                }
            )
        }

        val implementations = findImplementations(method)
        if (implementations.isEmpty()) {
            return listOf(CallTarget(methodNode(method)))
        }
        if (implementations.size == 1) {
            return listOf(resolvedTarget(implementations[0], method))
        }

        val contextual = inferFromCallSite(call, method, implementations)
        if (contextual != null && isAllowedMethod(contextual)) {
            return listOf(resolvedTarget(contextual, method))
        }

        if (!options.expandAmbiguousImplementations) {
            ambiguousCalls++
            return listOf(CallTarget(node = methodNode(method), ambiguous = true))
        }

        if (implementationOverflow.contains(methodKey(method)) ||
            implementations.size >= MAX_IMPLEMENTATIONS_PER_CALL
        ) {
            truncated = true
        }

        val targets = ArrayList<CallTarget>(implementations.size)
        for (implementation in implementations) {
            targets.add(resolvedTarget(implementation, method))
        }
        return targets
    }

    private fun isDispatchable(method: PsiMethod): Boolean {
        if (method.isConstructor) {
            return false
        }
        if (method.hasModifierProperty(PsiModifier.STATIC) ||
            method.hasModifierProperty(PsiModifier.PRIVATE) ||
            method.hasModifierProperty(PsiModifier.FINAL)
        ) {
            return false
        }
        val containingClass = method.containingClass ?: return false
        return containingClass.isInterface ||
            method.hasModifierProperty(PsiModifier.ABSTRACT)
    }

    /**
     * SequenceDiagram's "smart interface" idea: infer the concrete receiver
     * from the qualifier type, a local variable/field initializer, or a cast.
     */
    private fun inferConcrete(
        call: PsiCallExpression,
        method: PsiMethod,
    ): PsiMethod? {
        val qualifier = (call as? PsiMethodCallExpression)
            ?.methodExpression
            ?.qualifierExpression

        val candidates = ArrayList<PsiClass>(3)
        if (qualifier != null) {
            if (qualifier.text == "super") {
                return null
            }
            addConcreteClass(candidates, safe { qualifier.type })

            var unwrapped = safe {
                PsiUtil.skipParenthesizedExprDown(qualifier)
            }
            val cast = unwrapped
            if (cast is PsiTypeCastExpression) {
                addConcreteClass(candidates, safe { cast.type })
                val operand = cast.operand
                unwrapped = safe {
                    PsiUtil.skipParenthesizedExprDown(operand)
                }
            }
            val expression = unwrapped
            if (expression is PsiReferenceExpression) {
                val variable = safe { expression.resolve() } as? PsiVariable
                if (variable != null) {
                    addConcreteClass(candidates, safe { variable.type })
                    addConcreteClass(candidates, safe { variable.initializer?.type })
                }
            } else if (expression is PsiNewExpression) {
                addConcreteClass(candidates, safe { expression.type })
            }
        } else {
            val currentClass = safe {
                PsiTreeUtil.getParentOfType(call, PsiClass::class.java, false)
            }
            addConcreteClass(candidates, currentClass)
        }

        for (candidate in candidates) {
            val implementation = findImplementationInClass(candidate, method)
            if (implementation != null) {
                return implementation
            }
        }
        return null
    }

    /**
     * Last resort before giving up: use bean names, assignments and this/super
     * context to pick one implementation instead of drawing every candidate.
     */
    private fun inferFromCallSite(
        call: PsiCallExpression,
        method: PsiMethod,
        implementations: List<PsiMethod>,
    ): PsiMethod? {
        val qualifier = (call as? PsiMethodCallExpression)
            ?.methodExpression
            ?.qualifierExpression

        if (qualifier == null || qualifier.text == "this") {
            val current = safe {
                PsiTreeUtil.getParentOfType(call, PsiClass::class.java, false)
            } ?: return null
            return findImplementationInHierarchy(current, method)
        }

        if (qualifier.text == "super") {
            val current = safe {
                PsiTreeUtil.getParentOfType(call, PsiClass::class.java, false)
            } ?: return null
            val superClass = safe { current.superClass } ?: return null
            return findImplementationInHierarchy(superClass, method)
        }

        val variable = resolveVariable(qualifier)
        if (variable != null) {
            val byBeanName = findImplementationByBeanName(variable, implementations)
            if (byBeanName != null) {
                return byBeanName
            }

            val byAssignment = findImplementationByAssignment(
                variable,
                call,
                implementations,
            )
            if (byAssignment != null) {
                return byAssignment
            }
        }

        return findImplementationFromReturn(qualifier, implementations)
    }

    private fun resolvedTarget(
        method: PsiMethod,
        declared: PsiMethod,
    ): CallTarget {
        return CallTarget(
            node = methodNode(method),
            implementation = !sameMethod(method, declared),
        )
    }

    private fun resolveVariable(expression: PsiExpression): PsiVariable? {
        var current = safe { PsiUtil.skipParenthesizedExprDown(expression) }
        val cast = current as? PsiTypeCastExpression
        if (cast != null) {
            val operand = safe { cast.operand }
            current = operand?.let { safe { PsiUtil.skipParenthesizedExprDown(it) } }
        }
        val reference = current as? PsiReferenceExpression ?: return null
        return safe { reference.resolve() } as? PsiVariable
    }

    private fun findImplementationInHierarchy(
        start: PsiClass,
        method: PsiMethod,
    ): PsiMethod? {
        var current: PsiClass? = start
        while (current != null) {
            val found = findImplementationInClass(current, method)
            if (found != null) {
                return found
            }
            val parent = current
            current = safe { parent.superClass }
        }
        return null
    }

    private fun findImplementationByBeanName(
        variable: PsiVariable,
        implementations: List<PsiMethod>,
    ): PsiMethod? {
        val names = LinkedHashSet<String>()
        annotationString(variable, "value", "Qualifier")?.let { names.add(it) }
        annotationString(variable, "name", "Resource")?.let { names.add(it) }
        annotationString(variable, "value", "Resource")?.let { names.add(it) }
        val variableName = variable.name
        if (!variableName.isNullOrBlank()) {
            names.add(variableName)
        }
        if (names.isEmpty()) {
            return null
        }

        val normalized = names.map { normalizeBeanName(it) }
        for (implementation in implementations) {
            val owner = implementation.containingClass ?: continue
            val beanNames = beanNamesOf(owner)
            if (normalized.any { name -> beanNames.any { it == name } }) {
                return implementation
            }
        }

        val fuzzy = implementations.filter { implementation ->
            val simple = implementation.containingClass?.name ?: return@filter false
            val bean = normalizeBeanName(simple)
            val stripped = bean.removeSuffix("impl")
            normalized.any { name -> bean.startsWith(name) || stripped.startsWith(name) }
        }
        return fuzzy.singleOrNull()
    }

    private fun findImplementationByAssignment(
        variable: PsiVariable,
        call: PsiCallExpression,
        implementations: List<PsiMethod>,
    ): PsiMethod? {
        val body = safe {
            PsiTreeUtil.getParentOfType(call, PsiMethod::class.java, false)?.body
        } ?: return null

        var found: PsiMethod? = null
        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                if (found == null) {
                    val left = safe {
                        (expression.lExpression as? PsiReferenceExpression)?.resolve()
                    }
                    if (left == variable) {
                        found = findImplementationByType(
                            safe { expression.rExpression?.type },
                            implementations,
                        )
                    }
                }
                super.visitAssignmentExpression(expression)
            }
        })
        return found
    }

    private fun findImplementationFromReturn(
        expression: PsiExpression,
        implementations: List<PsiMethod>,
    ): PsiMethod? {
        val receiverCall = expression as? PsiMethodCallExpression ?: return null
        val callee = safe { receiverCall.resolveMethod() } ?: return null
        val body = callee.body ?: return null

        var found: PsiMethod? = null
        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReturnStatement(statement: PsiReturnStatement) {
                if (found == null) {
                    found = findImplementationByType(
                        safe { statement.returnValue?.type },
                        implementations,
                    )
                }
                super.visitReturnStatement(statement)
            }
        })
        return found
    }

    private fun findImplementationByType(
        type: PsiType?,
        implementations: List<PsiMethod>,
    ): PsiMethod? {
        val clazz = type?.let { safe { PsiUtil.resolveClassInType(it) } }
            ?: return null
        if (clazz.isInterface || (safe { PsiUtil.isAbstractClass(clazz) } ?: false)) {
            return null
        }
        return implementations.firstOrNull { it.containingClass == clazz }
    }

    private fun annotationString(
        variable: PsiVariable,
        attribute: String,
        annotationSuffix: String,
    ): String? {
        for (annotation in variable.annotations) {
            val qualifiedName = annotation.qualifiedName ?: continue
            if (!qualifiedName.endsWith(".$annotationSuffix")) {
                continue
            }
            val value = safe { annotation.findAttributeValue(attribute) }
                ?: continue
            val string = (value as? PsiLiteralExpression)?.value as? String
            if (!string.isNullOrBlank()) {
                return string
            }
        }
        return null
    }

    private fun beanNamesOf(containingClass: PsiClass): Set<String> {
        val names = LinkedHashSet<String>()
        val simpleName = containingClass.name ?: return names
        names.add(normalizeBeanName(simpleName))

        for (annotation in containingClass.annotations) {
            val qualifiedName = annotation.qualifiedName ?: continue
            if (!STEREOTYPE_ANNOTATIONS.any { qualifiedName.endsWith(it) }) {
                continue
            }
            val value = safe { annotation.findAttributeValue("value") }
            val string = (value as? PsiLiteralExpression)?.value as? String
            if (!string.isNullOrBlank()) {
                names.add(normalizeBeanName(string))
            }
        }
        return names
    }

    private fun normalizeBeanName(name: String): String {
        if (name.isEmpty()) {
            return name
        }
        return Character.toLowerCase(name[0]) + name.substring(1)
    }

    private fun addConcreteClass(
        candidates: MutableList<PsiClass>,
        containingClass: PsiClass?,
    ) {
        if (containingClass == null) {
            return
        }
        if (containingClass.isInterface || PsiUtil.isAbstractClass(containingClass)) {
            return
        }
        if (candidates.none { it == containingClass }) {
            candidates.add(containingClass)
        }
    }

    private fun addConcreteClass(
        candidates: MutableList<PsiClass>,
        type: PsiType?,
    ) {
        if (type == null) {
            return
        }
        addConcreteClass(candidates, safe { PsiUtil.resolveClassInType(type) })
    }

    private fun findImplementationInClass(
        containingClass: PsiClass,
        method: PsiMethod,
    ): PsiMethod? {
        if (containingClass.isInterface || PsiUtil.isAbstractClass(containingClass)) {
            return null
        }
        val owner = method.containingClass ?: return null
        val inherits = safe { containingClass.isInheritor(owner, true) } ?: false
        if (!inherits) {
            return null
        }

        val found = safe {
            containingClass.findMethodsBySignature(method, true)
        } ?: return null

        for (candidate in found) {
            if (candidate.body == null) {
                continue
            }
            if (candidate.hasModifierProperty(PsiModifier.ABSTRACT)) {
                continue
            }
            if (sameMethod(candidate, method)) {
                continue
            }
            return candidate
        }
        return null
    }

    private fun sameMethod(first: PsiMethod, second: PsiMethod): Boolean {
        return methodKey(first) == methodKey(second)
    }

    private fun findImplementations(method: PsiMethod): List<PsiMethod> {
        val key = methodKey(method)
        implementationCache[key]?.let { return it }

        if (dumbMode || isFrameworkMethod(method) || !isAllowedMethod(method)) {
            implementationCache[key] = emptyList()
            return emptyList()
        }

        val result = LinkedHashMap<String, PsiMethod>()
        try {
            val query = OverridingMethodsSearch.search(method, searchScope, true)
            for (implementation in query) {
                check()
                if (implementation == null) {
                    continue
                }
                if (!implementation.isValid || implementation.isConstructor) {
                    continue
                }
                if (implementation.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    continue
                }
                if (implementation.body == null) {
                    continue
                }
                if (!isAllowedMethod(implementation) ||
                    !isDisplayable(methodNode(implementation))
                ) {
                    continue
                }

                val implementationKey = methodKey(implementation)
                if (result.containsKey(implementationKey)) {
                    continue
                }
                result[implementationKey] = implementation
                if (result.size >= MAX_IMPLEMENTATIONS_PER_CALL) {
                    implementationOverflow.add(key)
                    break
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: RuntimeException) {
            // Index may be unavailable while editing. Falling back to the
            // declared target still produces a useful diagram.
        }

        val implementations = result.values.toList()
        implementationCache[key] = implementations
        return implementations
    }

    fun callbackMethods(anonymousClass: PsiClass): List<PsiMethod> {
        val key = classId(anonymousClass)
        callbackCache[key]?.let { return it }

        val methods = ArrayList<PsiMethod>()
        for (method in anonymousClass.methods) {
            check()
            if (method.isConstructor) {
                continue
            }
            if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
                continue
            }
            val body = method.body ?: continue
            if (body.text.isBlank()) {
                continue
            }
            val name = method.name
            if (name == "toString" || name == "equals" || name == "hashCode") {
                continue
            }
            val overrides = try {
                method.findSuperMethods().isNotEmpty()
            } catch (_: RuntimeException) {
                false
            }
            if (overrides) {
                methods.add(method)
            }
        }
        callbackCache[key] = methods
        return methods
    }

    fun resolveMethodReference(
        expression: PsiMethodReferenceExpression,
    ): PsiMethod? {
        return safe { expression.resolve() } as? PsiMethod
    }
}

private inline fun <T> safe(block: () -> T?): T? {
    return try {
        block()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (_: RuntimeException) {
        null
    }
}

private class AnalysisCancellation {

    @Volatile
    private var canceled = false

    fun cancel() {
        canceled = true
    }

    fun isCanceled(): Boolean {
        return canceled
    }

    fun check() {
        if (canceled) {
            throw ProcessCanceledException()
        }
        ProgressManager.checkCanceled()
    }
}

/**
 * One PSI pass per callable. Nested lambdas and anonymous-class bodies are not
 * walked here; they become separate callable nodes and are cached separately.
 */
private class DirectCallCollector(
    private val context: CallChainContext,
    private val owner: CallableNode,
) {

    private val result = ArrayList<DirectCall>()

    fun collect(): List<DirectCall> {
        val body = owner.body ?: return emptyList()
        val ownerMethod = (owner as? CallableNode.Method)?.method

        body.accept(object : JavaRecursiveElementVisitor() {

            override fun visitCallExpression(expression: PsiCallExpression) {
                context.check()

                if (expression is PsiNewExpression &&
                    expression.anonymousClass != null
                ) {
                    // Constructor arguments still belong to the enclosing call;
                    // the anonymous class body is skipped by visitClass().
                    super.visitCallExpression(expression)
                    return
                }

                val targets = ArrayList<CallTarget>(2)
                targets.addAll(context.resolveTargets(expression))
                attachInline(expression, targets)
                if (targets.isNotEmpty()) {
                    result.add(DirectCall(expression, dedupe(targets)))
                }
                super.visitCallExpression(expression)
            }

            override fun visitLambdaExpression(expression: PsiLambdaExpression) {
                // Lambda bodies are synthetic nodes. Their calls must not be
                // attributed to the enclosing method.
            }

            override fun visitClass(aClass: PsiClass) {
                if (aClass != owner.containingClass) {
                    return
                }
                super.visitClass(aClass)
            }

            override fun visitMethod(method: PsiMethod) {
                if (ownerMethod != null && method != ownerMethod) {
                    return
                }
                super.visitMethod(method)
            }
        })

        return result
    }

    /**
     * Attach direct lambda / anonymous-class / method-reference arguments to
     * this call. This is O(number of arguments), not a rescan of every subtree.
     */
    private fun attachInline(
        call: PsiCallExpression,
        targets: MutableList<CallTarget>,
    ) {
        val arguments = call.argumentList?.expressions ?: return
        for (argument in arguments) {
            context.check()

            var expression = safe {
                PsiUtil.skipParenthesizedExprDown(argument)
            }
            if (expression is PsiTypeCastExpression) {
                val operand = expression.operand
                expression = safe {
                    PsiUtil.skipParenthesizedExprDown(operand)
                }
            }

            val candidate = expression
            when (candidate) {
                is PsiLambdaExpression -> {
                    addTarget(
                        targets,
                        CallTarget(
                            node = context.lambdaNode(candidate),
                            callback = true,
                        ),
                    )
                }
                is PsiNewExpression -> {
                    val anonymousClass = candidate.anonymousClass
                    if (anonymousClass != null) {
                        for (callbackMethod in context.callbackMethods(anonymousClass)) {
                            addTarget(
                                targets,
                                CallTarget(
                                    node = context.methodNode(callbackMethod),
                                    callback = true,
                                ),
                            )
                        }
                    }
                }
                is PsiMethodReferenceExpression -> {
                    val method = context.resolveMethodReference(candidate)
                    if (method != null) {
                        addTarget(
                            targets,
                            CallTarget(
                                node = context.methodNode(method),
                                callback = true,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun addTarget(
        targets: MutableList<CallTarget>,
        target: CallTarget,
    ) {
        if (targets.any {
                it.node.key == target.node.key &&
                    it.callback == target.callback
            }
        ) {
            return
        }
        targets.add(target)
    }

    private fun dedupe(targets: List<CallTarget>): List<CallTarget> {
        if (targets.size <= 1) {
            return targets
        }
        val seen = HashSet<String>(targets.size)
        val unique = ArrayList<CallTarget>(targets.size)
        for (target in targets) {
            val key =
                "${target.node.key}#${target.callback}#${target.implementation}#${target.ambiguous}"
            if (seen.add(key)) {
                unique.add(target)
            }
        }
        return unique
    }
}

/**
 * Call Chain panel.
 */
private class CallChainPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    private val status = JLabel(
        "点击 Graph 中的 Java 方法节点，查看下游调用链"
    )

    private val canvas = CallChainCanvas(project)

    private val scrollPane = JScrollPane(canvas)

    private var lastElement: PsiElement? = null

    private var analysisSequence = 0

    private var cancellation: AnalysisCancellation? = null

    private val projectOnly = JCheckBox("仅项目代码", true)

    private val skipAccessors = JCheckBox("过滤 getter/setter", true)

    private val skipConstructors = JCheckBox("过滤构造方法", true)

    private val skipPrivateMethods = JCheckBox("过滤 private 方法", false)

    private val skipFrameworkCalls = JCheckBox("过滤框架调用", true)

    private val expandAmbiguous = JCheckBox("展开接口多实现", false)

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

        const val KEY_EXPAND_AMBIGUOUS =
            "draw.graph.callchain.expandAmbiguous"

        const val KEY_MAX_DEPTH =
            "draw.graph.callchain.maxDepth"

        const val KEY_EXCLUDED_PATTERNS =
            "draw.graph.callchain.excludedPatterns"

        val PATTERN_SEPARATOR = Regex("[,;\\s]+")
    }

    init {
        loadSettings()

        val northPanel = JPanel()
        northPanel.layout = BoxLayout(
            northPanel,
            BoxLayout.Y_AXIS,
        )

        val toolbar = JPanel(BorderLayout(8, 0))
        toolbar.border = BorderFactory.createEmptyBorder(5, 8, 3, 8)
        toolbar.add(status, BorderLayout.CENTER)

        val refreshButton = JButton("刷新")
        refreshButton.addActionListener {
            lastElement?.let { analyze(it) }
        }
        toolbar.add(refreshButton, BorderLayout.EAST)
        northPanel.add(toolbar)

        val filterPanel = JPanel(
            FlowLayout(FlowLayout.LEFT, 10, 3)
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
            expandAmbiguous,
        ).forEach { checkbox ->
            checkbox.addActionListener { onOptionChanged() }
            filterPanel.add(checkbox)
        }
        northPanel.add(filterPanel)

        val advancedPanel = JPanel(
            FlowLayout(FlowLayout.LEFT, 8, 4)
        )
        advancedPanel.border = BorderFactory.createTitledBorder(
            "高级设置"
        )

        advancedPanel.add(JLabel("最大调用层数："))
        maxDepthSpinner.preferredSize = Dimension(65, 26)
        maxDepthSpinner.addChangeListener { onOptionChanged() }
        advancedPanel.add(maxDepthSpinner)

        advancedPanel.add(JLabel("排除包/类："))
        excludedPatternsField.preferredSize = Dimension(300, 26)
        excludedPatternsField.toolTipText =
            "多个规则使用逗号、分号或空格分隔，例如 com.example.common,com.example.service.UserService"
        excludedPatternsField.addActionListener { onOptionChanged() }
        advancedPanel.add(excludedPatternsField)

        val applyButton = JButton("应用")
        applyButton.addActionListener { onOptionChanged() }
        advancedPanel.add(applyButton)

        northPanel.add(advancedPanel)

        add(northPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun onOptionChanged() {
        saveSettings()
        lastElement?.let { analyze(it) }
    }

    /**
     * A previous result may have left the scroll pane far down/right.
     * A new snapshot starts at the diagram origin so class headers and the
     * first invocation rows are visible immediately.
     */
    private fun resetCanvasViewport() {
        scrollPane.viewport.viewPosition = Point(0, 0)
        scrollPane.revalidate()
        scrollPane.repaint()
    }

    private fun loadSettings() {
        val properties = PropertiesComponent.getInstance(project)

        projectOnly.isSelected =
            properties.getBoolean(KEY_PROJECT_ONLY, true)
        skipAccessors.isSelected =
            properties.getBoolean(KEY_SKIP_ACCESSORS, true)
        skipConstructors.isSelected =
            properties.getBoolean(KEY_SKIP_CONSTRUCTORS, true)
        skipPrivateMethods.isSelected =
            properties.getBoolean(KEY_SKIP_PRIVATE, false)
        skipFrameworkCalls.isSelected =
            properties.getBoolean(KEY_SKIP_FRAMEWORK, true)
        expandAmbiguous.isSelected =
            properties.getBoolean(KEY_EXPAND_AMBIGUOUS, false)

        maxDepthSpinner.value =
            properties.getInt(KEY_MAX_DEPTH, 5).coerceIn(1, 30)

        excludedPatternsField.text =
            properties.getValue(KEY_EXCLUDED_PATTERNS, "")
    }

    private fun saveSettings() {
        if (project.isDisposed) {
            return
        }

        val properties = PropertiesComponent.getInstance(project)

        properties.setValue(KEY_PROJECT_ONLY, projectOnly.isSelected)
        properties.setValue(KEY_SKIP_ACCESSORS, skipAccessors.isSelected)
        properties.setValue(KEY_SKIP_CONSTRUCTORS, skipConstructors.isSelected)
        properties.setValue(KEY_SKIP_PRIVATE, skipPrivateMethods.isSelected)
        properties.setValue(KEY_SKIP_FRAMEWORK, skipFrameworkCalls.isSelected)
        properties.setValue(KEY_EXPAND_AMBIGUOUS, expandAmbiguous.isSelected)
        properties.setValue(
            KEY_MAX_DEPTH,
            (maxDepthSpinner.value as Number).toInt().toString(),
        )
        properties.setValue(
            KEY_EXCLUDED_PATTERNS,
            excludedPatternsField.text.trim(),
        )
    }

    private fun currentOptions(): CallChainOptions {
        val patterns = excludedPatternsField.text
            .split(PATTERN_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return CallChainOptions(
            projectOnly = projectOnly.isSelected,
            skipAccessors = skipAccessors.isSelected,
            skipConstructors = skipConstructors.isSelected,
            skipPrivateMethods = skipPrivateMethods.isSelected,
            skipFrameworkCalls = skipFrameworkCalls.isSelected,
            expandAmbiguousImplementations = expandAmbiguous.isSelected,
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

        cancellation?.cancel()
        val currentCancellation = AnalysisCancellation()
        cancellation = currentCancellation

        status.text = "正在分析下游方法调用链…"

        ApplicationManager.getApplication()
            .executeOnPooledThread {

                try {
                    val snapshot = ReadAction.compute<
                            ChainSnapshot?,
                            RuntimeException
                            > {
                        if (!element.isValid ||
                            currentCancellation.isCanceled()
                        ) {
                            null
                        } else {
                            CallChainAnalyzer.analyze(
                                project = project,
                                selected = element,
                                options = options,
                                cancellation = currentCancellation,
                            ) { phase ->
                                SwingUtilities.invokeLater {
                                    if (!project.isDisposed &&
                                        sequence == analysisSequence
                                    ) {
                                        status.text = phase
                                    }
                                }
                            }
                        }
                    }

                    SwingUtilities.invokeLater {
                        if (project.isDisposed ||
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
                            resetCanvasViewport()
                            status.text = if (element.isValid) {
                                "当前功能仅支持项目内 Java 方法"
                            } else {
                                "目标方法已失效，请重新选择"
                            }
                        } else {
                            canvas.setSnapshot(snapshot)
                            resetCanvasViewport()

                            val suffix = buildString {
                                if (snapshot.truncated) {
                                    append(" · 已达到分析上限")
                                }
                                if (snapshot.ambiguousCalls > 0) {
                                    append(" · 多实现未展开 ")
                                    append(snapshot.ambiguousCalls)
                                    append(" 处")
                                }
                            }
                            status.text =
                                "类 ${snapshot.participants.size} · " +
                                    "调用 ${snapshot.events.size}" +
                                    suffix
                        }
                    }
                } catch (e: ProcessCanceledException) {
                    SwingUtilities.invokeLater {
                        if (!project.isDisposed &&
                            sequence == analysisSequence &&
                            !currentCancellation.isCanceled()
                        ) {
                            status.text =
                                "分析被 IDEA 中断（索引/写操作或任务取消），点击刷新重试"
                        }
                    }
                    throw e
                } catch (e: Throwable) {
                    SwingUtilities.invokeLater {
                        if (project.isDisposed ||
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
 * SequenceDiagram-style canvas with activation bars.
 *
 * Columns = Java classes. Rows = invocation order. Solid arrows = method
 * calls; dashed arrows = returns. Layout, fonts and clickable rectangles are
 * prepared once per snapshot and every paint is clipped to the viewport.
 */
private class CallChainCanvas(
    private val project: Project,
) : JPanel() {

    private data class Hit(
        val event: SequenceEvent,
        val bounds: Rectangle,
    )

    private var snapshot: ChainSnapshot? = null

    private val participantIndices = HashMap<String, Int>()
    private var participantXs = IntArray(0)
    private var hits: List<Hit> = emptyList()
    private var preferred = Dimension(1100, 600)
    private var lineStart = 0
    private var rowCount = 0

    private val headerHeight = 62
    private val rowHeight = 72
    private val columnWidth = 250
    private val leftPadding = 30
    private val topPadding = 38
    private val activationWidth = 12
    private val headerWidth = 200

    private var baseFont: Font? = null
    private var headerFont = Font(Font.DIALOG, Font.BOLD, 12)
    private var labelFont = Font(Font.DIALOG, Font.PLAIN, 11)
    private var labelBoldFont = Font(Font.DIALOG, Font.BOLD, 11)
    private var smallFont = Font(Font.DIALOG, Font.PLAIN, 9)
    private var legendFont = Font(Font.DIALOG, Font.PLAIN, 10)

    init {
        background = BACKGROUND

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val hit = hits.firstOrNull {
                    it.bounds.contains(e.point)
                } ?: return

                val event = hit.event
                val url = event.fileUrl ?: return
                val file = VirtualFileManager
                    .getInstance()
                    .findFileByUrl(url)
                    ?: return

                val maxOffset = file.length
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()

                OpenFileDescriptor(
                    project,
                    file,
                    event.offset.coerceIn(0, maxOffset),
                ).navigate(true)
            }
        })
    }

    fun setSnapshot(value: ChainSnapshot) {
        snapshot = value
        layoutDiagram(value)
        revalidate()
        repaint()
    }

    private fun layoutDiagram(value: ChainSnapshot) {
        val count = value.participants.size
        participantIndices.clear()
        participantXs = IntArray(count)

        for (index in 0 until count) {
            val participant = value.participants[index]
            participantIndices[participant.id] = index
            participantXs[index] =
                leftPadding + index * columnWidth + columnWidth / 2
        }

        lineStart = topPadding + headerHeight - 12
        rowCount = value.rowCount

        val preferredHeight = max(
            600,
            lineStart + rowCount * rowHeight + rowHeight + 90,
        )
        preferred = Dimension(
            max(1100, leftPadding * 2 + max(1, count) * columnWidth),
            preferredHeight,
        )

        // Clickable regions are rebuilt only when a new snapshot arrives.
        val built = ArrayList<Hit>(value.events.size)
        for (event in value.events) {
            val callerX = xOf(event.callerId) ?: continue
            val calleeX = xOf(event.calleeId) ?: continue
            val y = lineStart + (event.index + 1) * rowHeight
            val bounds = if (event.selfCall) {
                Rectangle(callerX - 4, y - 28, 245, 65)
            } else {
                Rectangle(
                    min(callerX, calleeX),
                    y - 26,
                    abs(calleeX - callerX),
                    48,
                )
            }
            built.add(Hit(event, bounds))
        }
        hits = built
    }

    private fun xOf(participantId: String): Int? {
        val index = participantIndices[participantId] ?: return null
        if (index < 0 || index >= participantXs.size) {
            return null
        }
        return participantXs[index]
    }

    override fun getPreferredSize(): Dimension {
        return preferred
    }

    private fun updateFonts() {
        val base = font ?: return
        if (base == baseFont) {
            return
        }
        baseFont = base
        headerFont = base.deriveFont(Font.BOLD, 12f)
        labelFont = base.deriveFont(Font.PLAIN, 11f)
        labelBoldFont = base.deriveFont(Font.BOLD, 11f)
        smallFont = base.deriveFont(Font.PLAIN, 9f)
        legendFont = base.deriveFont(Font.PLAIN, 10f)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val data = snapshot ?: return
        val g2 = g.create() as Graphics2D

        try {
            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON,
            )
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            updateFonts()

            if (data.participants.isEmpty()) {
                g2.color = Color.GRAY
                g2.drawString(
                    "没有找到符合条件的项目 Java 方法调用",
                    30,
                    40,
                )
                return
            }

            val clip = g2.clipBounds
            val lineEnd = max(
                height,
                lineStart + rowCount * rowHeight + rowHeight + 30,
            )

            drawHeaders(g2, data, clip)
            drawLifelines(g2, clip, lineEnd)

            val rootX = participantXs[0]
            drawActivationBar(
                g = g2,
                x = rootX,
                startY = lineStart,
                endY = lineEnd,
                depth = 0,
                root = true,
            )

            drawActivations(g2, data, clip)
            drawCalls(g2, data, clip)
            drawReturns(g2, data, clip)

            if (data.events.isEmpty()) {
                g2.color = Color.GRAY
                g2.font = labelFont
                g2.drawString(
                    "当前方法没有找到符合条件的调用",
                    30,
                    lineEnd + 25,
                )
            }

            drawLegend(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun drawHeaders(
        g2: Graphics2D,
        data: ChainSnapshot,
        clip: Rectangle,
    ) {
        val headerY = topPadding
        if (headerY > clip.y + clip.height ||
            headerY + 42 < clip.y
        ) {
            return
        }

        g2.font = headerFont
        val metrics = g2.fontMetrics

        for (index in data.participants.indices) {
            val centerX = participantXs[index]
            val left = centerX - headerWidth / 2
            if (left > clip.x + clip.width ||
                left + headerWidth < clip.x
            ) {
                continue
            }

            g2.color = HEADER_COLOR
            g2.fillRoundRect(
                left,
                headerY,
                headerWidth,
                42,
                10,
                10,
            )

            g2.color = HEADER_BORDER_COLOR
            g2.stroke = BAR_STROKE
            g2.drawRoundRect(
                left,
                headerY,
                headerWidth,
                42,
                10,
                10,
            )

            g2.color = HEADER_TEXT_COLOR
            val text = fitName(
                data.participants[index].simpleName,
                metrics,
                headerWidth - 14,
            )
            g2.drawString(
                text,
                centerX - metrics.stringWidth(text) / 2,
                headerY + 26,
            )
        }
    }

    private fun fitName(
        text: String,
        metrics: FontMetrics,
        maxWidth: Int,
    ): String {
        if (metrics.stringWidth(text) <= maxWidth) {
            return text
        }

        var end = text.length.coerceAtMost(24)
        while (end > 1) {
            val candidate = text.substring(0, end) + "…"
            if (metrics.stringWidth(candidate) <= maxWidth) {
                return candidate
            }
            end--
        }
        return "…"
    }

    private fun drawLifelines(
        g2: Graphics2D,
        clip: Rectangle,
        lineEnd: Int,
    ) {
        if (lineStart > clip.y + clip.height ||
            lineEnd < clip.y
        ) {
            return
        }

        g2.color = LINE_COLOR
        g2.stroke = LIFELINE_STROKE

        for (x in participantXs) {
            if (x < clip.x - 2 ||
                x > clip.x + clip.width + 2
            ) {
                continue
            }
            g2.drawLine(x, lineStart, x, lineEnd)
        }
    }

    private fun drawActivations(
        g2: Graphics2D,
        data: ChainSnapshot,
        clip: Rectangle,
    ) {
        for (event in data.events) {
            val calleeX = xOf(event.calleeId) ?: continue
            val startY =
                lineStart + (event.index + 1) * rowHeight - 7
            val endY = lineStart +
                (event.returnIndex + 1) * rowHeight +
                rowHeight / 2

            if (endY < clip.y ||
                startY > clip.y + clip.height
            ) {
                continue
            }

            drawActivationBar(
                g = g2,
                x = calleeX,
                startY = startY,
                endY = endY,
                depth = event.depth,
                root = false,
                cycle = event.cycle,
            )
        }
    }

    private fun drawCalls(
        g2: Graphics2D,
        data: ChainSnapshot,
        clip: Rectangle,
    ) {
        g2.stroke = CALL_STROKE

        for (event in data.events) {
            val y = lineStart + (event.index + 1) * rowHeight

            if (y > clip.y + clip.height + rowHeight) {
                break
            }
            if (y < clip.y - rowHeight) {
                continue
            }

            val callerX = xOf(event.callerId) ?: continue
            val calleeX = xOf(event.calleeId) ?: continue

            val color = if (event.cycle) CYCLE_COLOR else CALL_COLOR
            g2.color = color

            val label = labelFor(event)

            if (event.selfCall) {
                drawSelfCall(g2, callerX, y)
                drawCallLabel(
                    g = g2,
                    text = label,
                    x = callerX + 55,
                    y = y - 9,
                    bold = false,
                )
            } else {
                val direction = if (calleeX > callerX) 1 else -1
                val startX = callerX +
                    direction * (activationWidth / 2 + 2)
                val endX = calleeX -
                    direction * (activationWidth / 2 + 2)

                g2.drawLine(startX, y, endX, y)
                drawArrowHead(g2, endX, y, direction)

                g2.font = labelBoldFont
                val labelWidth = g2.fontMetrics.stringWidth(label)
                drawCallLabel(
                    g = g2,
                    text = label,
                    x = (callerX + calleeX) / 2 - labelWidth / 2,
                    y = y - 9,
                    bold = true,
                )
            }

            g2.color = DEPTH_COLOR
            g2.font = smallFont
            g2.drawString("L${event.depth}", 8, y + 4)
        }
    }

    private fun drawReturns(
        g2: Graphics2D,
        data: ChainSnapshot,
        clip: Rectangle,
    ) {
        g2.stroke = RETURN_STROKE
        var currentColor: Color? = null

        for (event in data.events) {
            val callerX = xOf(event.callerId) ?: continue
            val calleeX = xOf(event.calleeId) ?: continue
            val y = lineStart +
                (event.returnIndex + 1) * rowHeight +
                rowHeight / 2

            if (y < clip.y - rowHeight ||
                y > clip.y + clip.height + rowHeight
            ) {
                continue
            }

            val color = if (event.cycle) CYCLE_COLOR else RETURN_COLOR
            if (color != currentColor) {
                g2.color = color
                currentColor = color
            }

            if (event.selfCall) {
                val loopWidth = 44
                val x = calleeX + loopWidth
                g2.drawLine(x, y - 5, x, y + 5)
                g2.drawLine(x, y + 5, calleeX, y + 5)
                drawArrowHead(g2, calleeX, y + 5, -1)
            } else {
                val direction = if (callerX > calleeX) 1 else -1
                val startX = calleeX +
                    direction * (activationWidth / 2 + 2)
                val endX = callerX -
                    direction * (activationWidth / 2 + 2)

                g2.drawLine(startX, y, endX, y)
                drawArrowHead(g2, endX, y, direction)
            }
        }
    }

    private fun drawActivationBar(
        g: Graphics2D,
        x: Int,
        startY: Int,
        endY: Int,
        depth: Int,
        root: Boolean,
        cycle: Boolean = false,
    ) {
        val safeEndY = max(startY + 18, endY)
        val offset = if (root) 0 else depth.coerceAtMost(12) * 3
        val barX = x - activationWidth / 2 + offset
        val barHeight = safeEndY - startY
        if (barHeight <= 0) {
            return
        }

        g.color = if (cycle) CYCLE_BAR_COLOR else ACTIVATION_COLOR
        g.fillRect(barX, startY, activationWidth, barHeight)

        g.color = if (cycle) CYCLE_COLOR else ACTIVATION_BORDER_COLOR
        g.stroke = BAR_STROKE
        g.drawRect(barX, startY, activationWidth, barHeight)
    }

    private fun drawCallLabel(
        g: Graphics2D,
        text: String,
        x: Int,
        y: Int,
        bold: Boolean,
    ) {
        g.font = if (bold) labelBoldFont else labelFont
        val metrics = g.fontMetrics
        val width = metrics.stringWidth(text)
        val height = metrics.height

        g.color = BACKGROUND
        g.fillRoundRect(
            x - 4,
            y - height + 3,
            width + 8,
            height + 3,
            5,
            5,
        )

        g.color = TEXT_COLOR
        g.drawString(text, x, y)
    }

    private fun drawSelfCall(
        g: Graphics2D,
        x: Int,
        y: Int,
    ) {
        val loopWidth = 44
        val loopHeight = 26

        g.drawLine(x, y, x + loopWidth, y)
        g.drawLine(x + loopWidth, y, x + loopWidth, y + loopHeight)
        g.drawLine(x + loopWidth, y + loopHeight, x, y + loopHeight)
        drawArrowHead(g, x, y + loopHeight, -1)
    }

    private fun drawArrowHead(
        g: Graphics2D,
        x: Int,
        y: Int,
        direction: Int,
    ) {
        g.drawLine(x, y, x - direction * 8, y - 4)
        g.drawLine(x, y, x - direction * 8, y + 4)
    }

    private fun labelFor(event: SequenceEvent): String {
        val suffix = when {
            event.lambda -> " [λ]"
            event.callback -> " [回调]"
            event.ambiguous -> " [接口·多实现]"
            event.implementation -> " [实现]"
            else -> ""
        }
        return "${event.callerMethod}() → ${event.calleeMethod}()$suffix"
    }

    private fun drawLegend(g: Graphics2D) {
        val x = 20
        val y = 15

        g.font = legendFont

        g.color = ACTIVATION_COLOR
        g.fillRoundRect(x, y, 12, 18, 3, 3)

        g.color = ACTIVATION_BORDER_COLOR
        g.stroke = BAR_STROKE
        g.drawRoundRect(x, y, 12, 18, 3, 3)

        g.color = TEXT_COLOR
        g.drawString("方法激活条", x + 18, y + 13)

        g.color = RETURN_COLOR
        g.stroke = RETURN_STROKE
        g.drawLine(x + 115, y + 9, x + 150, y + 9)
        drawArrowHead(g, x + 115, y + 9, -1)

        g.color = TEXT_COLOR
        g.drawString("方法返回", x + 156, y + 13)
        g.drawString("[接口·多实现] 未唯一推断", x + 240, y + 13)
    }

    private companion object {

        val BACKGROUND = Color(0xFA, 0xFB, 0xFD)
        val HEADER_COLOR = Color(0xE8, 0xF0, 0xFA)
        val HEADER_BORDER_COLOR = Color(0x8B, 0xA5, 0xC4)
        val HEADER_TEXT_COLOR = Color(0x25, 0x35, 0x4A)
        val LINE_COLOR = Color(0xB5, 0xBF, 0xCC)
        val CALL_COLOR = Color(0x3B, 0x82, 0xD6)
        val RETURN_COLOR = Color(0x7B, 0x87, 0x94)
        val ACTIVATION_COLOR = Color(0xD9, 0xE9, 0xFA)
        val ACTIVATION_BORDER_COLOR = Color(0x5B, 0x91, 0xC9)
        val TEXT_COLOR = Color(0x37, 0x4B, 0x63)
        val CYCLE_COLOR = Color(0xD9, 0x77, 0x06)
        val CYCLE_BAR_COLOR = Color(0xFE, 0xE2, 0xC2)
        val DEPTH_COLOR = Color(0x8B, 0x95, 0xA5)

        val LIFELINE_STROKE = BasicStroke(
            1f,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_ROUND,
            10f,
            floatArrayOf(5f, 5f),
            0f,
        )

        val CALL_STROKE = BasicStroke(
            1.6f,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
        )

        val RETURN_STROKE = BasicStroke(
            1.2f,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_ROUND,
            10f,
            floatArrayOf(5f, 4f),
            0f,
        )

        val BAR_STROKE = BasicStroke(1f)
    }
}
