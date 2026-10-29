package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/** Bounded JSON paths used by imported sources; filters never execute JavaScript. */
internal object LegacyJsonPath {
    fun select(root: Any, raw: String): List<Any> {
        val path = raw.trim().removePrefix("$")
        require(path.length <= 2048) { "JSON 路径过长" }
        var nodes = listOf(root)
        var offset = 0
        fun children(node: Any): List<Any> = when (node) {
            is JSONArray -> (0 until node.length()).map { node.get(it) }
            is JSONObject -> node.keys().asSequence().map { node.get(it) }.toList()
            else -> emptyList()
        }.filter { it != JSONObject.NULL }
        fun field(node: Any, key: String): List<Any> = when {
            key == "*" -> children(node)
            node is JSONObject -> listOfNotNull(node.opt(key)).filter { it != JSONObject.NULL }
            node is JSONArray && key.toIntOrNull() != null -> listOfNotNull(node.opt(key.toInt())).filter { it != JSONObject.NULL }
            else -> emptyList()
        }
        while (offset < path.length) {
            val recursive = path.startsWith("..", offset)
            if (recursive) offset += 2 else if (path[offset] == '.') offset++
            require(offset < path.length) { "JSON 路径不完整" }
            if (path[offset] == '[') {
                val end = path.indexOf(']', offset)
                require(end >= 0) { "JSON 路径括号未闭合" }
                val token = path.substring(offset + 1, end).trim()
                val exists = Regex("\\?\\(@\\.([A-Za-z0-9_-]+)\\)").matchEntire(token)
                nodes = if (exists != null) nodes.flatMap { if (it is JSONArray) children(it) else listOf(it) }
                    .filter { it is JSONObject && it.has(exists.groupValues[1]) && !it.isNull(exists.groupValues[1]) }
                else {
                    val key = token.removeSurrounding("'").removeSurrounding("\"")
                    require(key == "*" || key.toIntOrNull() != null || Regex("[A-Za-z_][A-Za-z0-9_-]*").matches(key)) { "暂不支持 JSON 路径表达式：$token" }
                    nodes.flatMap { field(it, key) }
                }
                offset = end + 1
            } else {
                val match = Regex("[A-Za-z0-9_*-]+").find(path, offset)
                require(match != null && match.range.first == offset) { "暂不支持 JSON 路径表达式：${raw.take(80)}" }
                val key = match.value
                offset = match.range.last + 1
                nodes = if (!recursive) nodes.flatMap { field(it, key) } else buildList {
                    var visited = 0
                    fun visit(node: Any, depth: Int) {
                        require(depth <= 64 && ++visited <= 50_000) { "JSON 路径遍历超过上限" }
                        if (node is JSONObject) addAll(field(node, key))
                        require(size <= 5000) { "JSON 路径结果超过上限" }
                        children(node).forEach { visit(it, depth + 1) }
                    }
                    nodes.forEach { visit(it, 0) }
                }
            }
            require(nodes.size <= 5000) { "JSON 路径结果超过上限" }
        }
        return nodes
    }
}
