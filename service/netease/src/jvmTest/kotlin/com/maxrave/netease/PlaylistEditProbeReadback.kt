package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/** 只读回查:tags/cover 写后服务端可见性(排除异步延迟 vs 假成功) */
class PlaylistEditProbeReadback {
    @Test
    fun readback() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        if (!writeProbeEnabled("playlist_edit_readback")) return
        val pid = 18405929547L
        val sb = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            sb.appendLine("== raw tags update response ==")
            runCatching {
                val raw =
                    client.callEApi(
                        "/playlist/tags/update",
                        mapOf("id" to pid.toString(), "tags" to "华语"),
                    )
                sb.appendLine("raw=$raw")
            }.onFailure { sb.appendLine("raw ERR ${it.message}") }
            sb.appendLine("== detail privacy field ==")
            runCatching {
                val rawDetail =
                    client.callWeApi(
                        "/v6/playlist/detail",
                        mapOf("id" to pid, "n" to 0),
                    )
                val pl = rawDetail["playlist"] as? kotlinx.serialization.json.JsonObject
                sb.appendLine(
                    "privacy=${pl?.get("privacy")} userId=${pl?.get("userId")} specialType=${pl?.get("specialType")}",
                )
                sb.appendLine("detail keys=${pl?.keys?.toList()?.take(40)}")
                sb.appendLine("tags value=${pl?.get("tags")}")
            }.onFailure { sb.appendLine("detail ERR ${it.message}") }
            repeat(3) { round ->
                kotlinx.coroutines.delay(3000)
                client.playlistDetail(pid).fold(
                    { (meta, _) ->
                        sb.appendLine("round$round name=${meta.name} desc=${meta.description} tags=${meta.tags} cover=${meta.coverUrl?.take(70)}")
                    },
                    { sb.appendLine("round$round ERR ${it.message}") },
                )
            }
        }
        File("/tmp/playlist_readback_out.txt").writeText(sb.toString())
        println(sb)
    }
}
