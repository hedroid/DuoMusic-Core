package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 标签词表升级探针(2026-10-11):分类目录(/playlist/catalogue,官方 app 标签选择器
 * 同源体系)的规模与形状;并验证 hottags 之外的目录标签能否被 tags/update 接受。
 */
class TagsCatalogProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        if (!writeProbeEnabled("tags_catalog")) return
        val pid = 18405929547L
        val sb = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })

            client.playlistCatalog().fold(
                { groups ->
                    var total = 0
                    groups.forEach { (name, tags) ->
                        total += tags.size
                        sb.appendLine("$name(${tags.size}): ${tags.joinToString(" ") { it.name + if (it.hot) "*" else "" }}")
                    }
                    sb.appendLine("TOTAL=$total")
                },
                { sb.appendLine("catalog ERR ${it.message}") },
            )

            // 选一个 hottags 里没有的目录标签试写(hottags≈华语/流行/摇滚/民谣/电子/另类独立/轻音乐/综艺/影视原声/ACG)
            val catalog = client.playlistCatalog().getOrNull().orEmpty()
            val candidate =
                catalog.flatMap { it.second }.map { it.name }
                    .firstOrNull { it !in setOf("华语", "流行", "摇滚", "民谣", "电子", "另类/独立", "轻音乐", "综艺", "影视原声", "ACG") }
            sb.appendLine("candidate(outside hottags)=$candidate")
            if (candidate != null) {
                client.updatePlaylistTags(pid, listOf("流行", candidate)).fold(
                    { sb.appendLine("write [流行,$candidate] ok=$it") },
                    { sb.appendLine("write ERR ${it.message}") },
                )
                kotlinx.coroutines.delay(8000)
                client.playlistDetail(pid).fold(
                    { (meta, _) -> sb.appendLine("readback: tags=${meta.tags}") },
                    { sb.appendLine("readback ERR ${it.message}") },
                )
                // 还原
                client.updatePlaylistTags(pid, listOf("华语", "流行", "摇滚")).fold(
                    { sb.appendLine("restored") },
                    { sb.appendLine("restore ERR ${it.message}") },
                )
            }
        }
        File("/tmp/tags_catalog_out.txt").writeText(sb.toString())
        println(sb)
    }
}
