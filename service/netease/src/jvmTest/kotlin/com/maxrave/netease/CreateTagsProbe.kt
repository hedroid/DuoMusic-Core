package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * tags 吞写归因实验二(2026-10-11):新建歌单→立刻写标签→回读→删除。
 * 落地=旧测试歌单个体问题(用户真实歌单大概率健康);仍空=账号级吞写。
 * 建单今晨有风控 400 窗口——只打一发,失败即收(重试会续期窗口)。
 */
class CreateTagsProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        val sb = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })

            sb.appendLine("== create (one shot) ==")
            val pid =
                client.createPlaylist("tags探针-1011").fold(
                    { sb.appendLine("create ok pid=$it"); it },
                    { sb.appendLine("create ERR ${it.message}"); null },
                )
            if (pid == null || pid <= 0L) {
                File("/tmp/create_tags_out.txt").writeText(sb.toString())
                return@runBlocking
            }
            try {
                kotlinx.coroutines.delay(2000)
                sb.appendLine("== tags write on fresh playlist ==")
                client.updatePlaylistTags(pid, listOf("流行")).fold(
                    { sb.appendLine("tags ok=$it") },
                    { sb.appendLine("tags ERR ${it.message}") },
                )
                repeat(3) { round ->
                    kotlinx.coroutines.delay(8000)
                    client.playlistDetail(pid).fold(
                        { (meta, _) -> sb.appendLine("round$round(+${(round + 1) * 8}s): tags=${meta.tags}") },
                        { sb.appendLine("round$round ERR ${it.message}") },
                    )
                }
                // 顺手对旧测试歌单再写一次做同刻对照
                sb.appendLine("== same-moment control on old playlist ==")
                client.updatePlaylistTags(18405929547L, listOf("民谣")).fold(
                    { sb.appendLine("old tags ok=$it") },
                    { sb.appendLine("old tags ERR ${it.message}") },
                )
                kotlinx.coroutines.delay(8000)
                client.playlistDetail(18405929547L).fold(
                    { (meta, _) -> sb.appendLine("old round: tags=${meta.tags}") },
                    { sb.appendLine("old round ERR ${it.message}") },
                )
            } finally {
                sb.appendLine("== cleanup: delete probe playlist ==")
                client.deletePlaylist(pid).fold(
                    { sb.appendLine("delete ok=$it") },
                    { sb.appendLine("delete ERR ${it.message}") },
                )
            }
        }
        File("/tmp/create_tags_out.txt").writeText(sb.toString())
        println(sb)
    }
}
