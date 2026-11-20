package com.github.linwancen.plugin.graph.printer

import com.github.linwancen.plugin.graph.parser.RelData
import com.github.linwancen.plugin.graph.settings.DrawGraphAppState
import com.github.linwancen.plugin.graph.ui.DrawGraphBundle
import com.intellij.execution.CommandLineUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ScriptRunnerUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.util.ExceptionUtil
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.function.Consumer


class PrinterGraphviz : Printer() {

    val sb = StringBuilder(
        """digraph{
${if (DrawGraphAppState.of().lr) "rankdir=LR" else ""}
fontname = "Microsoft YaHei,Consolas"
node [shape = "record", style="rounded,filled", fillcolor = "#F1F1F1", fontname = "Microsoft YaHei,Consolas"]
edge [arrowhead = "empty", fontname = "Microsoft YaHei,Consolas"]
graph [compound=true, ordering=out]

"""
    )
    val js = StringBuilder()
    var nodeId = 1
    var clusterId = 1

    override fun beforeGroup(groupMap: MutableMap<String, String>) {
        sb.append(
            "subgraph \"cluster_${sign(groupMap["sign"] ?: return)}\" {\n" +
                    "  edge [\"dir\"=\"none\"]\n"
                    + "  graph [style=\"rounded\"]\n"
        )
        label(groupMap, false)
        sb.append("\n")
    }

    override fun afterGroup(groupMap: MutableMap<String, String>) {
        sb.append("}\n\n")
    }

    override fun item(itemMap: MutableMap<String, String>) {
        sb.append("  \"cluster_${sign(itemMap["sign"] ?: return)}\"")
        label(itemMap, true)
        sb.append("\n")
    }

    private fun label(map: Map<String, String>, isItem: Boolean) {
        itemName(map) ?: return
        if (isItem) {
            sb.append('[')
        } else {
            sb.append("  ")
        }
        sb.append("label =\" ")
        addLine(map["@1"], sb, true)
        addLine(map["@2"], sb, true)
        addLine(map["@3"], sb, true)
        sb.append("${itemName(map)}\"")
        if (isItem) {
            when (map["status"]) {
                "external" -> sb.append(", style=\"dashed,filled\", color=\"#777777\"")
                "not-scanned" -> sb.append(", style=\"dashed,filled\", color=\"#f9a825\"")
                "unresolved" -> sb.append(", style=\"dashed,filled\", color=\"#c62828\"")
                "dynamic" -> sb.append(", style=\"dashed,filled\", color=\"#00838f\"")
            }
            sb.append(']')
        }
        val id = if (isItem) "node${nodeId++}" else "clust${clusterId++}"
        if (isItem) {
            js.append("  document.getElementById(\"$id\").classList.add(\"searchMethod\")\n")
        }
        val action = if (isItem && map["status"] !in setOf("unresolved", "dynamic")) {
            "openGraph"
        } else {
            "navigate"
        }
        js.append("  document.getElementById(\"$id\").onclick = function(){ $action(\"${map["link"]}\") }\n")
    }

    override fun call(usageSign: String, callSign: String) {
        sb.append("\n\"cluster_${sign(usageSign)}\" -> \"cluster_${sign(callSign)}\"")
    }

    override fun call(usageSign: String, callSign: String, order: Int) {
        sb.append("\n\"cluster_${sign(usageSign)}\" -> \"cluster_${sign(callSign)}\" [label=\"$order\"]")
    }

    override fun call(
        usageSign: String,
        callSign: String,
        order: Int,
        callSite: RelData.CallSite?,
    ) {
        val label = callSite?.sourceLine?.let { "$order @L$it" } ?: order.toString()
        sb.append("\n\"cluster_${sign(usageSign)}\" -> \"cluster_${sign(callSign)}\" [label=\"$label\"]")
    }

    override fun toSrc(relData: RelData): Pair<String, String> {
        printerData(relData)
        relData.callTreeRootSign?.let { rootSign ->
            val depthBySign = mutableMapOf(rootSign to 0)
            val queue = ArrayDeque<String>()
            queue.add(rootSign)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val currentDepth = depthBySign.getValue(current)
                val neighbors = if (relData.callTreeForward) {
                    relData.callSet.filter { it.first == current }.map { it.second }
                } else {
                    relData.callSet.filter { it.second == current }.map { it.first }
                }
                for (neighbor in neighbors) {
                    if (depthBySign.putIfAbsent(neighbor, currentDepth + 1) == null) {
                        queue.addLast(neighbor)
                    }
                }
            }
            depthBySign.entries.groupBy { it.value }.forEach { (_, entries) ->
                if (entries.size > 1) {
                    sb.append("\n  { rank=same; ")
                    sb.append(entries.joinToString("; ") { "\"cluster_${sign(it.key)}\"" })
                    sb.append(" }\n")
                }
            }
        }
        sb.append("\n}")
        return Pair(sb.toString(), js.toString())
    }

    companion object {

        @JvmStatic
        fun build(data: PrinterData, func: Consumer<String>?) {
            object : Task.Backgroundable(data.project, "draw Graphviz") {
                override fun run(indicator: ProgressIndicator) {
                    val src = data.src ?: return
                    if (StringUtils.isBlank(src)) {
                        return
                    }
                    val path = DrawGraphAppState.of().tempPath
                    try {
                        File(path).mkdirs()
                        val dotPath = "$path/graphviz.dot"
                        Files.write(Path.of(dotPath), src.toByteArray(StandardCharsets.UTF_8))
                        if (func == null) {
                            return
                        }
                        val commandLine =
                            CommandLineUtil.toCommandLine("dot", arrayListOf("-Tsvg", "-Tpng", "-O", dotPath))
                        val generalCommandLine = GeneralCommandLine(commandLine)
                        generalCommandLine.charset = StandardCharsets.UTF_8
                        generalCommandLine.setWorkDirectory(path)
                        val commandLineOutputStr = ScriptRunnerUtil.getProcessOutput(generalCommandLine)
                        val svgFile = "$path/graphviz.dot.svg"
                        val svg = Files.readString(Path.of(svgFile), StandardCharsets.UTF_8)
                        func.accept(
                            // language="html"
                            """
<!-- <embed src="file:///$path/graphviz.dot.svg" type="image/svg+xml" /> -->
$svg
${GraphSearchHtml.fragment()}
<br>
<script>
  function openGraph(link) {
    callJava('openGraph:' + link)
  }
  function navigate(link) {
    callJava('navigate:' + link)
  }
  function openDevtools() {
    callJava('openDevtools')
  }
  function callJava(cmd) {
    window.java({
      request: cmd,
      onSuccess(response){
        console.log(response);
      },
      onFailure(error_code,error_message){
        console.log(error_code,error_message);
      }
    });
  }
  window.onload = function addEvent() {

${data.js ?: ""}
  }
</script>
<button onclick='openDevtools()'>openDevtools</button>
<br>
${DrawGraphBundle.message("graphviz.msg")}
<br>
$commandLineOutputStr
"""
                        )
                        return
                    } catch (e: Exception) {
                        func?.accept(
                            // language="html"
                            """
<embed src="file:///$path/graphviz.dot.svg" type="image/svg+xml" />
<br>
${DrawGraphBundle.message("graphviz.msg")}
<br>
${ExceptionUtil.getThrowableText(e)}
"""
                        )
                    }
                }
            }.queue()
        }
    }
}