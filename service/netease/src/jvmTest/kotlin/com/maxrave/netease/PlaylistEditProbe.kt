package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 自建歌单编辑探针(2026-10-11):desc/tags 的 eapi 通道 + 封面两步上传(nos token/alloc
 * → nos 直传 → cover/update)。目标=模拟器测试歌单,输出写 /tmp 供会话核对。
 */
class PlaylistEditProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        if (!writeProbeEnabled("playlist_edit")) return
        val pid = 18405929547L
        val sb = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })

            sb.appendLine("== before ==")
            client.playlistDetail(pid).fold(
                { (meta, ids) ->
                    sb.appendLine("name=${meta.name} desc=${meta.description} tags=${meta.tags} cover=${meta.coverUrl?.take(60)} tracks=${ids.size}")
                },
                { sb.appendLine("detail ERR ${it.message}") },
            )

            sb.appendLine("== desc(eapi) ==")
            client.updatePlaylistDescription(pid, "探针描述-1011").fold(
                { sb.appendLine("desc ok=$it") },
                { sb.appendLine("desc ERR ${it.message}") },
            )

            sb.appendLine("== tags(eapi) ==")
            client.updatePlaylistTags(pid, listOf("华语", "流行")).fold(
                { sb.appendLine("tags ok=$it") },
                { sb.appendLine("tags ERR ${it.message}") },
            )

            sb.appendLine("== hottags ==")
            client.hotPlaylistTags().fold(
                { sb.appendLine("hottags=${it.take(8)}") },
                { sb.appendLine("hottags ERR ${it.message}") },
            )

            sb.appendLine("== cover(两步) ==")
            val jpeg = File("/tmp/cover_test.jpg").readBytes()
            sb.appendLine("jpeg bytes=${jpeg.size}")
            client.updatePlaylistCover(pid, jpeg).fold(
                { sb.appendLine("cover ok=$it") },
                { sb.appendLine("cover ERR ${it.message}") },
            )

            sb.appendLine("== after ==")
            client.playlistDetail(pid).fold(
                { (meta, ids) ->
                    sb.appendLine("name=${meta.name} desc=${meta.description} tags=${meta.tags} cover=${meta.coverUrl?.take(80)} tracks=${ids.size}")
                },
                { sb.appendLine("detail ERR ${it.message}") },
            )
        }
        File("/tmp/playlist_edit_probe_out.txt").writeText(sb.toString())
        println(sb)
    }
}
