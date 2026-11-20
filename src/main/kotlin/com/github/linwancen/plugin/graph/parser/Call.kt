package com.github.linwancen.plugin.graph.parser

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

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
                val resolved = try {
                    reference.resolve()
                } catch (_: Throwable) {
                    null
                } ?: return@mapNotNull null
                reference.element.textOffset to resolved
            }
        }
        .sortedBy { it.first }
        .distinctBy { it.first to it.second }
        .map { it.second }
}