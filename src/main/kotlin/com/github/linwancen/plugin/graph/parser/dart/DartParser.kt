package com.github.linwancen.plugin.graph.parser.dart

import com.github.linwancen.plugin.graph.parser.Call
import com.github.linwancen.plugin.graph.parser.CallOccurrence
import com.github.linwancen.plugin.graph.parser.ParserLang
import com.github.linwancen.plugin.graph.parser.RelData
import com.github.linwancen.plugin.graph.settings.DrawGraphProjectState
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

class DartParser : ParserLang<PsiElement>() {

    override fun id(): String = "Dart"

    @Suppress("UNCHECKED_CAST")
    override fun funClass(): Class<PsiElement> =
        Class.forName(FUNCTION_CLASS, false, javaClass.classLoader) as Class<PsiElement>

    override fun skipFun(state: DrawGraphProjectState, func: PsiElement): Boolean = false

    override fun toSign(func: PsiElement): String? {
        val name = (func as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() } ?: return null
        val filePath = func.containingFile?.virtualFile?.path ?: return null
        val ownerName = enclosingClass(func)?.name
        return "$filePath#${ownerName?.let { "$it." } ?: ""}$name"
    }

    override fun funMap(funMap: MutableMap<String, String>, func: PsiElement) {
        (func as? PsiNamedElement)?.name?.let { funMap["name"] = it }
    }

    override fun classMap(func: PsiElement, relData: RelData): MutableMap<String, String>? {
        val file = func.containingFile ?: return null
        val filePath = file.virtualFile?.path ?: file.name
        val owner = enclosingClass(func)
        val name = owner?.name ?: file.name.substringBeforeLast('.', file.name)
        val sign = if (owner == null) filePath else "$filePath#${owner.name}"
        return mutableMapOf("sign" to sign, "name" to name)
    }

    override fun callList(func: PsiElement, call: Boolean): List<PsiElement> =
        Call.find(func, call, referenceClass())

    override fun callOccurrences(func: PsiElement): List<CallOccurrence<PsiElement>> {
        val functionClass = funClass()
        val references = PsiTreeUtil.findChildrenOfAnyType(func, referenceClass())
        return Call.occurrences(func, references) { target ->
            target.takeIf(functionClass::isInstance)
        }
    }

    private fun enclosingClass(element: PsiElement): PsiNamedElement? =
        generateSequence(element.parent) { it.parent }
            .filterIsInstance<PsiNamedElement>()
            .firstOrNull { it.javaClass.simpleName.contains("DartClass") }

    @Suppress("UNCHECKED_CAST")
    private fun referenceClass(): Class<out PsiElement> =
        Class.forName(REFERENCE_CLASS, false, javaClass.classLoader) as Class<out PsiElement>

    companion object {
        private const val FUNCTION_CLASS = "com.jetbrains.lang.dart.psi.DartFunctionDeclarationWithBodyOrNative"
        private const val REFERENCE_CLASS = "com.jetbrains.lang.dart.psi.DartReferenceExpression"
    }
}
