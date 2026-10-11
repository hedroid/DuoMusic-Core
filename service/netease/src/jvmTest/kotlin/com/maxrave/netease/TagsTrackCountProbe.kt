package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * tags 吞写的"最小曲目数门槛"假设实验(2026-10-11):
 * 测试歌单(18405929547)当前仅 1 曲——先加 3 首歌再写标签回读;
 * 落地=门槛坐实,仍空=转向审核门/账号门假设。可逆:加的是测试歌单曲目。
 */
class TagsTrackCountProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        if (!writeProbeEnabled("tags_track_count")) return
        val pid = 18405929547L
        val sb = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })

            suspend fun readTags(): String =
                client.playlistDetail(pid).fold(
                    { (meta, ids) -> "tags=${meta.tags} tracks=${ids.size}" },
                    { "ERR ${it.message}" },
                )

            sb.appendLine("== before: ${readTags()} ==")

            // 加 3 首歌(搜热门歌取 id)
            val songs =
                client.searchSongs("孙燕姿 遇见", 3).getOrNull()?.items.orEmpty() +
                    client.searchSongs("周杰伦 晴天", 2).getOrNull()?.items.orEmpty()
            val ids = songs.mapNotNull { it.id }.distinct().take(3)
            sb.appendLine("candidates=${ids.size} ids=$ids")
            if (ids.isNotEmpty()) {
                client.addToPlaylist(pid, ids).fold(
                    { sb.appendLine("add ok=$it") },
                    { sb.appendLine("add ERR ${it.message}") },
                )
                kotlinx.coroutines.delay(2000)
                sb.appendLine("after add: ${readTags()}")
            }

            // 写标签(纯 eapi,无 token——协议变体此前已全部排除)
            sb.appendLine("== tags write (tracks enriched) ==")
            client.updatePlaylistTags(pid, listOf("华语", "流行")).fold(
                { sb.appendLine("tags ok=$it") },
                { sb.appendLine("tags ERR ${it.message}") },
            )
            repeat(3) { round ->
                kotlinx.coroutines.delay(8000)
                sb.appendLine("round$round(+${(round + 1) * 8}s): ${readTags()}")
            }
        }
        File("/tmp/tags_trackcount_out.txt").writeText(sb.toString())
        println(sb)
    }
}
