package com.github.linwancen.plugin.graph.parser

import com.intellij.lang.Language
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.slf4j.LoggerFactory
import kotlin.reflect.full.createInstance

abstract class Parser {

    protected abstract fun id(): String

    protected abstract fun srcImpl(
        project: Project,
        relData: RelData,
        files: List<VirtualFile>,
        indicator: ProgressIndicator?
    )

    open fun callImpl(
        project: Project,
        relData: RelData,
        psiElement: PsiElement,
        isCall: Boolean,
        indicator: ProgressIndicator?
    ) {}

    open fun classImpl(
        project: Project,
        relData: RelData,
        psiElement: PsiElement,
        indicator: ProgressIndicator?,
    ) {
        callImpl(project, relData, psiElement, true, indicator)
    }

    open fun nameToElementImpl(project: Project, name: String): PsiElement? {
        return null
    }

    open fun graphClassInfo(element: PsiElement): Pair<String, String> {
        val file = element.containingFile?.virtualFile
        val name = file?.name?.let { it.substringBeforeLast('.', it) } ?: element.text.take(40)
        return (file?.path ?: name) to name
    }

    open fun graphTarget(element: PsiElement): PsiElement? = element

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java)
        private val optionalParserClasses = listOf(
            "com.github.linwancen.plugin.graph.parser.kotlin.KotlinParser",
            "com.github.linwancen.plugin.graph.parser.python.PythonParser",
            "com.github.linwancen.plugin.graph.parser.go.GoParser",
            "com.github.linwancen.plugin.graph.parser.rust.RustParser",
            "com.github.linwancen.plugin.graph.parser.js.JsParser",
            "com.github.linwancen.plugin.graph.parser.js.HtmlParser",
            "com.github.linwancen.plugin.graph.parser.js.VueParser",
            "com.github.linwancen.plugin.graph.parser.dart.DartParser",
        )

        fun parserMap(): MutableMap<String, Parser> {
            val epn: ExtensionPointName<Parser> = ExtensionPointName.create("com.github.linwancen.drawgraph.parser")
            val parserMap = mutableMapOf<String, Parser>()
            val parserList = try {
                epn.extensionList
            } catch (e: LinkageError) {
                log.info("skip graph parser extensions because a language dependency is unavailable", e)
                emptyList()
            }
            parserList.forEach { parser ->
                try {
                    val parserId = parser.id()
                    log.info("load graph parser {} -> {}", parserId, parser)
                    parserMap.putIfAbsent(parserId, parser)
                } catch (e: LinkageError) {
                    log.info(
                        "skip graph parser {} because its language dependency is unavailable",
                        parser.javaClass.name,
                        e,
                    )
                } catch (e: Exception) {
                    log.info("skip graph parser {} because it could not be initialized", parser.javaClass.name, e)
                }
            }
            optionalParserClasses.forEach { className ->
                try {
                    val parserClass = Class.forName(className, false, Parser::class.java.classLoader).kotlin
                    val parser = parserClass.createInstance() as Parser
                    val parserId = parser.id()
                    if (parserMap.putIfAbsent(parserId, parser) == null) {
                        log.info("add load optional graph parser {} -> {}", parserId, parser)
                    }
                } catch (_: ClassNotFoundException) {
                    log.debug("skip optional graph parser because {} is unavailable", className)
                } catch (e: Exception) {
                    log.info("skip optional graph parser {} because it could not be initialized", className, e)
                } catch (e: LinkageError) {
                    log.info("skip optional graph parser {} because its dependencies are unavailable", className, e)
                }
            }
            return parserMap
        }

        @JvmStatic
        fun findParser(element: PsiElement, map: Map<String, Parser>): Parser? {
            var language: Language = element.language
            while (true) {
                val impl: Parser? = map[language.id]
                if (impl != null) {
                    return impl
                }
                language = language.baseLanguage ?: return null
            }
        }

        /**
         * need DumbService.getInstance(project).runReadActionInSmartMo
         */
        @JvmStatic
        fun src(project: Project, relData: RelData, files: List<VirtualFile>, indicator: ProgressIndicator?) {
            val parserMap = parserMap()
            for (file in files) {
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: continue
                val usageService = findParser(psiFile, parserMap) ?: continue
                // not one by one because pom.xml find all files
                usageService.srcImpl(project, relData, files, indicator)
                // only one lang srcImpl all and return
                return
            }
        }

        /**
         * need DumbService.getInstance(project).runReadActionInSmartMo
         */
        @JvmStatic
        fun call(
            project: Project,
            relData: RelData,
            psiElement: PsiElement,
            call: Boolean,
            indicator: ProgressIndicator
        ) {
            val parserMap = parserMap()
            var language = psiElement.language
            language.baseLanguage?.let { language = it }
            val usageService = parserMap[language.id] ?: return
            usageService.callImpl(project, relData, psiElement, call, indicator)
        }

        @JvmStatic
        fun classGraph(project: Project, relData: RelData, psiElement: PsiElement, indicator: ProgressIndicator?) {
            val parser = findParser(psiElement, parserMap()) ?: return
            parser.classImpl(project, relData, psiElement, indicator)
        }

        /**
         * need DumbService.getInstance(project).runReadActionInSmartMo
         */
        @JvmStatic
        fun nameToElement(project: Project, name: String): PsiElement? {
            val epn: ExtensionPointName<Parser> = ExtensionPointName.create("com.github.linwancen.drawgraph.parser")
            val parserList = epn.extensionList
            for (it in parserList) {
                val element = it.nameToElementImpl(project, name)
                if (element != null) {
                    return element
                }
            }
            return null
        }

        @JvmStatic
        fun graphClassInfo(project: Project, element: PsiElement): Pair<String, String> {
            val parser = findParser(element, parserMap()) ?: return Pair(
                element.containingFile?.virtualFile?.path ?: element.text,
                element.containingFile?.name?.let { it.substringBeforeLast('.', it) } ?: "Graph",
            )
            return parser.graphClassInfo(element)
        }

        @JvmStatic
        fun graphTarget(project: Project, element: PsiElement): PsiElement? {
            val parser = findParser(element, parserMap()) ?: return element
            return parser.graphTarget(element)
        }
    }

    fun regCall(
        callSetMap: Map<String, Set<String>>,
        relData: RelData,
        all: Boolean = false,
    ) {
        for ((usage, callList) in callSetMap) {
            for (call in callList) {
                if (all || callSetMap.containsKey(call)) {
                    relData.regCall(usage, call)
                }
            }
        }
    }

    fun regUsage(
        callSetMap: Map<String, Set<String>>,
        usageSetMap: Map<String, Set<String>>,
        relData: RelData,
        all: Boolean = false,
    ) {
        for ((call, usageList) in usageSetMap) {
            for (usage in usageList) {
                if (all || callSetMap.containsKey(usage)) {
                    relData.regCall(usage, call)
                }
            }
        }
    }
}