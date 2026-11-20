package com.github.linwancen.plugin.graph.parser

import com.github.linwancen.plugin.common.TaskTool
import com.github.linwancen.plugin.common.text.Skip
import com.github.linwancen.plugin.graph.parser.relfile.RelFile
import com.github.linwancen.plugin.graph.settings.DrawGraphProjectState
import com.github.linwancen.plugin.graph.ui.DrawGraphBundle
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

abstract class ParserLang<F : PsiElement> : Parser() {

    protected abstract fun funClass(): Class<F>

    /**
     * Constructor or get/set
     */
    protected abstract fun skipFun(state: DrawGraphProjectState, func: F): Boolean

    protected abstract fun toSign(func: F): String?

    protected abstract fun funMap(funMap: MutableMap<String, String>, func: F)

    protected abstract fun classMap(func: F, relData: RelData): MutableMap<String, String>?

    protected abstract fun callList(func: F, call: Boolean): List<F>

    protected open fun callOccurrences(func: F): List<CallOccurrence<F>> =
        callList(func, true).map { CallOccurrence(it, null, null, null) }

    /** interface impl */
    protected open fun fileCall(
        callSetMap: MutableMap<String, MutableSet<String>>,
        usageSetMap: MutableMap<String, MutableSet<String>>,
        psiFile: PsiFile,
    ) {
    }

    override fun srcImpl(project: Project, relData: RelData, files: List<VirtualFile>, indicator: ProgressIndicator?) {
        val state = DrawGraphProjectState.of(project)
        val callSetMap = mutableMapOf<String, MutableSet<String>>()
        val psiManager = PsiManager.getInstance(project)
        val relFiles = if (files.size != 1) files else RelFile.relFileOf(project, files)
        val parserMap = parserMap()
        val taskTool = if (indicator == null) null else TaskTool(indicator, relFiles.size)
        for ((index, file) in relFiles.withIndex()) {
            if (taskTool != null) {
                taskTool.beforeNext(index, file.path) ?: return
            }
            val psiFile = psiManager.findFile(file) ?: continue
            val usageService = findParser(psiFile, parserMap) ?: continue
            if (usageService is ParserLang<*>) {
                usageService.fileImpl(psiFile, state, file, relData, callSetMap)
            }
        }
        regCall(callSetMap, relData)
    }

    fun fileImpl(
        psiFile: PsiFile, state: DrawGraphProjectState, file: VirtualFile,
        relData: RelData, callSetMap: MutableMap<String, MutableSet<String>>
    ) {
        val funcs = PsiTreeUtil.findChildrenOfType(psiFile, funClass())
        for (func in funcs) {
            val sign = funcSign(state, func, file, relData) ?: continue
            // override fun
            val callSet = callSetMap.computeIfAbsent(sign) { mutableSetOf() }
            val occurrences = callOccurrences(func).toMutableList()
            val occurrenceTargets = occurrences.mapNotNull { it.target }.toSet()
            callList(func, true)
                .filterNot { it in occurrenceTargets }
                .forEach { occurrences.add(CallOccurrence(it, null, null, null)) }
            val calls = mutableListOf<String>()
            for (occurrence in occurrences) {
                val target = occurrence.target
                val targetSign = if (target == null) {
                    registerUnresolvedTarget(sign, occurrence, relData, callSetMap)
                } else {
                    if (skipFun(state, target)) {
                        continue
                    }
                    val path = target.containingFile?.virtualFile?.path
                    val inProject = path != null && isProjectPath(psiFile.project, path)
                    if (state.skipLib && (path == null || path.contains('!') || !inProject)) {
                        continue
                    }
                    val resolvedSign = toSign(target) ?: continue
                    if (Skip.skip(resolvedSign, state.includePattern, state.excludePattern)) {
                        continue
                    }
                    if (!registerResolvedTarget(target, resolvedSign, relData, callSetMap)) {
                        continue
                    }
                    relData.regCall(
                        sign,
                        resolvedSign,
                        occurrence.sourcePath,
                        occurrence.sourceOffset,
                        occurrence.sourceLine,
                        if (occurrence.sourceOffset == null) "dispatch" else "resolved",
                    )
                    resolvedSign
                } ?: continue
                calls.add(targetSign)
            }
            callSet.addAll(calls)
            relData.callOrderMap[sign] = calls.toMutableList()
        }
    }

    private fun isProjectPath(project: Project, path: String): Boolean =
        project.basePath?.let { root ->
            val normalizedRoot = root.replace('\\', '/').trimEnd('/')
            val normalizedPath = path.replace('\\', '/').trimEnd('/')
            normalizedPath.equals(normalizedRoot, ignoreCase = !SystemInfo.isFileSystemCaseSensitive) ||
                normalizedPath.startsWith("$normalizedRoot/", ignoreCase = !SystemInfo.isFileSystemCaseSensitive)
        } == true

    private fun registerResolvedTarget(
        target: F,
        sign: String,
        relData: RelData,
        callSetMap: MutableMap<String, MutableSet<String>>,
    ): Boolean {
        if (relData.itemMap.containsKey(sign)) {
            callSetMap.putIfAbsent(sign, mutableSetOf())
            return true
        }
        val path = target.containingFile?.virtualFile?.path
        val inProject = path != null && isProjectPath(target.project, path)
        val inLibrary = path == null || path.contains('!') || !inProject
        val map = mutableMapOf("sign" to sign, "status" to if (inLibrary) "external" else "not-scanned")
        try {
            funMap(map, CommentUtils.byteToSrc(target))
        } catch (_: Exception) {
            map["name"] = sign
        }
        val anchor = if (inLibrary) {
            map["name"]?.substringAfterLast(' ') ?: ""
        } else {
            "@${target.textOffset}"
        }
        val classSign = classMap(target, relData)?.get("sign") ?: sign.substringBefore('#')
        map["link"] = when {
            path != null && !path.contains('!') -> "$path#@${target.textOffset}"
            else -> "$classSign#$anchor"
        }
        relData.itemMap[sign] = map
        callSetMap.putIfAbsent(sign, mutableSetOf())
        return true
    }

    private fun registerUnresolvedTarget(
        callerSign: String,
        occurrence: CallOccurrence<F>,
        relData: RelData,
        callSetMap: MutableMap<String, MutableSet<String>>,
        depth: Int = 1,
    ): String? {
        val name = occurrence.unresolvedName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val suffix = occurrence.sourceOffset?.toString() ?: name.hashCode().toString()
        val sign = "$callerSign#${occurrence.resolution}@$suffix"
        val status = occurrence.resolution
        val suffixLabel = "[${DrawGraphBundle.message("call.target.$status")}]"
        relData.itemMap.putIfAbsent(
            sign,
            mutableMapOf(
                "sign" to sign,
                "name" to "$name $suffixLabel",
                "status" to status,
                "link" to occurrence.sourcePath?.let { "$it#@${occurrence.sourceOffset ?: 0}" }.orEmpty(),
            ),
        )
        callSetMap.putIfAbsent(sign, mutableSetOf())
        relData.regCall(
            callerSign,
            sign,
            occurrence.sourcePath,
            occurrence.sourceOffset,
            occurrence.sourceLine,
            status,
            depth,
        )
        return sign
    }

    private fun funcSign(state: DrawGraphProjectState, func: F, file: VirtualFile?, relData: RelData): String? {
        if (skipFun(state, func)) {
            return null
        }
        val srcFun = CommentUtils.byteToSrc(func)
        var path = srcFun.containingFile?.virtualFile?.path ?: file?.path
        val inJar = path == null || path.contains('!')
        if (inJar && state.skipLib && !state.autoLoad) {
            return null
        }
        val sign = toSign(func) ?: return null
        if (Skip.skip(sign, state.includePattern, state.excludePattern)) {
            return null
        }
        val funMap = mutableMapOf<String, String>()
        funMap["sign"] = sign
        try {
            funMap(funMap, srcFun)
        } catch (_: Exception) {
            // lombok toString() byteToSrc return PsiAnno
            return null
        }
        val name = funMap["name"]
        val classMap = classMap(srcFun, relData)
        if (inJar) {
            classMap?.let { path = it["sign"] }
        }
        path?.let {
            val anchor = if (inJar) {
                name?.substring(name.lastIndexOf(' ') + 1) ?: ""
            } else {
                "@${srcFun.textOffset}"
            }
            funMap["link"] = "$it#$anchor"
        }
        if (classMap == null) {
            relData.regParentChild(funMap)
        } else {
            path?.let { classMap["link"] = "$path#" }
            relData.regParentChild(classMap, funMap)
        }
        return sign
    }

    override fun callImpl(
        project: Project,
        relData: RelData,
        psiElement: PsiElement,
        isCall: Boolean,
        indicator: ProgressIndicator?
    ) {
        val state = DrawGraphProjectState.of(project)
        val callSetMap = mutableMapOf<String, MutableSet<String>>()
        val func = PsiTreeUtil.getParentOfType(psiElement, funClass(), false) ?: return
        val rootSign = funcSign(state, func, func.containingFile?.virtualFile, relData) ?: return
        relData.callTreeRootSign = rootSign
        relData.callTreeForward = isCall
        recursiveOccurrences(1, func, isCall, mutableSetOf(), indicator) { level, usage, occurrence ->
            val call = occurrence.target
            if (call == null) {
                val usageSign = toSign(usage) ?: return@recursiveOccurrences
                val unresolvedSign = registerUnresolvedTarget(usageSign, occurrence, relData, callSetMap, level)
                    ?: return@recursiveOccurrences
                callSetMap.computeIfAbsent(usageSign) { mutableSetOf() }.add(unresolvedSign)
                relData.callOrderMap.computeIfAbsent(usageSign) { mutableListOf() }.add(unresolvedSign)
                return@recursiveOccurrences
            }
            val callFile = call.containingFile?.virtualFile
            if (state.skipLib && (callFile == null || !isProjectPath(project, callFile.path))) {
                return@recursiveOccurrences
            }
            val usageSign = toSign(usage) ?: return@recursiveOccurrences
            val callSign = funcSign(state, call, callFile, relData) ?: return@recursiveOccurrences
            val callSet = callSetMap.computeIfAbsent(if (isCall) usageSign else callSign) { mutableSetOf() }
            callSet.add(if (isCall) callSign else usageSign)
            if (isCall) {
                relData.callOrderMap.computeIfAbsent(usageSign) { mutableListOf() }.add(callSign)
            }
            val callerSign = if (isCall) usageSign else callSign
            val targetSign = if (isCall) callSign else usageSign
            relData.regCall(
                callerSign,
                targetSign,
                occurrence.sourcePath ?: callFile?.path,
                occurrence.sourceOffset,
                occurrence.sourceLine,
                if (occurrence.sourceOffset == null) {
                    if (isCall) "dispatch" else "resolved"
                } else {
                    occurrence.resolution
                },
                level,
            )
        }
        regCall(callSetMap, relData, true)
    }

    private fun recursiveOccurrences(
        level: Int,
        usage: F,
        isCall: Boolean,
        set: MutableSet<F>,
        indicator: ProgressIndicator?,
        callBack: (level: Int, usage: F, occurrence: CallOccurrence<F>) -> Unit,
    ) {
        if (!set.add(usage)) {
            return
        }
        val occurrences = if (isCall) {
            val direct = callOccurrences(usage).toMutableList()
            val directTargets = direct.mapNotNull { it.target }.toSet()
            callList(usage, true)
                .filterNot { it in directTargets }
                .forEach { direct.add(CallOccurrence(it, null, null, null)) }
            direct
        } else {
            callList(usage, false).map { CallOccurrence(it, null, null, null) }
        }
        val taskTool = if (indicator == null) null else TaskTool(indicator, occurrences.size)
        for ((index, occurrence) in occurrences.withIndex()) {
            if (taskTool != null) {
                taskTool.beforeNext(index, "$level ${occurrence.target ?: occurrence.unresolvedName}") ?: return
            }
            callBack.invoke(level, usage, occurrence)
            occurrence.target?.let { recursiveOccurrences(level + 1, it, isCall, set, indicator, callBack) }
        }
    }

    open fun recursiveCall(
        level: Int,
        usage: F,
        isCall: Boolean,
        set: MutableSet<F>,
        indicator: ProgressIndicator?,
        callBack: (level: Int, usage: F, call: F) -> Unit,
    ) {
        if (!set.add(usage)) {
            return
        }
        val callList = callList(usage, isCall)
        val taskTool = if (indicator == null) null else TaskTool(indicator, callList.size)
        for ((index, call) in callList.withIndex()) {
            if (taskTool != null) {
                taskTool.beforeNext(index, "$level $call") ?: return
            }
            callBack.invoke(level, usage, call)
            recursiveCall(level + 1, call, isCall, set, indicator, callBack)
        }
    }
}