package com.github.linwancen.plugin.graph.parser

class RelData {
    data class CallSite(
        val sourcePath: String?,
        val sourceOffset: Int?,
        val sourceLine: Int?,
        val resolution: String,
    )

    data class CallRelation(
        val callerSign: String,
        val targetSign: String,
        val callSite: CallSite,
        val depth: Int,
    )

    val parentChildMap = mutableMapOf<String, MutableSet<String>>()
    /** child print in parentChildMap root, so filter */
    val childSet = mutableSetOf<String>()
    val itemMap = mutableMapOf<String, MutableMap<String, String>>()
    val callSet = mutableSetOf<Pair<String, String>>()
    val callOrderMap = mutableMapOf<String, MutableList<String>>()
    val callRelations = mutableListOf<CallRelation>()
    var callTreeRootSign: String? = null
    var callTreeForward: Boolean = true

    fun regParentChild(parentMap: MutableMap<String, String>, childMap: MutableMap<String, String>? = null) {
        val parent = parentMap["sign"] ?: return
        itemMap[parent] = parentMap
        val child = childMap?.get("sign") ?: return
        if (itemMap[child]?.containsKey("status") == true) {
            itemMap[child] = childMap
        } else {
            itemMap.putIfAbsent(child, childMap)
        }
        parentChildMap.computeIfAbsent(parent) { mutableSetOf() }.add(child)
        childSet.add(child)
    }

    fun regCall(usageSign: String, callSign: String) {
        callSet.add(Pair(usageSign, callSign))
    }

    fun regCall(
        callerSign: String,
        targetSign: String,
        sourcePath: String?,
        sourceOffset: Int?,
        sourceLine: Int?,
        resolution: String,
        depth: Int = 1,
    ) {
        regCall(callerSign, targetSign)
        callRelations.add(
            CallRelation(callerSign, targetSign, CallSite(sourcePath, sourceOffset, sourceLine, resolution), depth)
        )
    }
}