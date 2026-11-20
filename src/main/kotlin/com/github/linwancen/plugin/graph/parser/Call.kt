package com.github.linwancen.plugin.graph.parser

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

data class CallOccurrence<F : PsiElement>(
    val target: F?,
    val unresolvedName: String?,
    val sourcePath: String?,
    val sourceOffset: Int?,
    val sourceLine: Int? = null,
    val resolution: String = "resolved",
)

object Call {
    /**
     * ```mermaid
     * usage ..> call
     * ```
     */
    inline fun <reified F : PsiElement, R : PsiElement> find(func: F, call: Boolean, vararg refClass: Class<out R>) =
        if (call) findRefs(PsiTreeUtil.findChildrenOfAnyType(func, *refClass)).filterIsInstance<F>()
        // I don't know why cannot find usage for KotlinParser.callList in this project
        else ReferencesSearch.search(func, GlobalSearchScope.projectScope(func.project)).findAll()
            .mapNotNull { PsiTreeUtil.getParentOfType(it.element, F::class.java) }.distinct()

    /**
     * ```mermaid
     * usage ..> call
     * ```
     */
    fun <R : PsiElement> findRefs(refs: Collection<R>) = refs
        .flatMap { element ->
            element.references.mapNotNull { reference ->
                val resolved = reference.resolve() ?: return@mapNotNull null
                reference.element.textOffset to resolved
            }
        }
        .sortedBy { it.first }
        .distinctBy { it.first to it.second }
        .map { it.second }

    inline fun <reified F : PsiElement, R : PsiElement> occurrences(
        func: F,
        vararg refClass: Class<out R>,
    ): List<CallOccurrence<F>> = occurrences(
        func,
        PsiTreeUtil.findChildrenOfAnyType(func, *refClass),
    ) { resolved ->
        @Suppress("UNCHECKED_CAST")
        resolved as? F
    }

    fun <F : PsiElement, R : PsiElement> occurrences(
        func: PsiElement,
        refs: Collection<R>,
        targetOf: (PsiElement) -> F?,
    ): List<CallOccurrence<F>> {
        val functionText = func.text
        val functionStart = func.textRange.startOffset
        val containingFile = func.containingFile
        val filePath = containingFile?.virtualFile?.path
        val fileText = containingFile?.text
        val lineStarts = mutableListOf(0)
        fileText?.forEachIndexed { index, char ->
            if (char == '\n') lineStarts.add(index + 1)
        }
        return refs
            .flatMap { element ->
                element.references.mapNotNull { reference ->
                    val referenceElement = reference.element
                    val resolved = reference.resolve()
                    val target = resolved?.let(targetOf)
                    val sourceLine = fileText?.let {
                        val offset = referenceElement.textOffset.coerceIn(0, it.length)
                        val index = lineStarts.binarySearch(offset).let { found ->
                            if (found >= 0) found else -found - 2
                        }
                        index + 1
                    }
                    if (target != null) {
                        return@mapNotNull CallOccurrence<F>(
                            target, null, filePath, referenceElement.textOffset, sourceLine,
                        )
                    }
                    if (resolved != null) {
                        val resolvedType = resolved.javaClass.simpleName
                        if (resolvedType.contains("Class") || resolvedType.contains("Package") ||
                            resolvedType.contains("Namespace")
                        ) {
                            return@mapNotNull null
                        }
                    }
                    val localEnd = referenceElement.textRange.endOffset - functionStart
                    val isCall = localEnd in 0..functionText.length &&
                        functionText.substring(localEnd).trimStart().startsWith("(")
                    if (!isCall) {
                        return@mapNotNull null
                    }
                    CallOccurrence<F>(
                        null,
                        referenceElement.text,
                        filePath,
                        referenceElement.textOffset,
                        sourceLine,
                        if (resolved == null) "unresolved" else "dynamic",
                    )
                }
            }
            .sortedBy { it.sourceOffset }
            .distinctBy { it.sourceOffset to (it.target ?: it.unresolvedName) }
    }
}