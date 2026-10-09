package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 云盘歌曲菜单有效性探针(2026-10-10):
 * songDetail(公开性)/simiSong(电台+相似)/like 往返已验证;本探针补
 * "添加到歌单"对云盘 id 的可逆往返(测试歌单 add→del)。
 */
class CloudMenuValidityProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        val out = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val ids = listOf(2162278106L, 2717195764L, 2147202623L)
            for (id in ids) {
                out.appendLine("== $id ==")
                client.songDetail(listOf(id)).fold(
                    { songs -> out.appendLine("detail: ${songs.size} ${songs.firstOrNull()?.let { it.name + " / " + it.artists }}") },
                    { out.appendLine("detail ERR: ${it.message}") },
                )
                client.similarSongs(id, limit = 5).fold(
                    { songs -> out.appendLine("simi: ${songs.size} ${songs.take(2).map { it.name }}") },
                    { out.appendLine("simi ERR: ${it.message}") },
                )
            }
            // 点赞可逆往返(阿爸)
            val target = 2162278106L
            out.appendLine("like(true): " + client.likeSong(target, true).getOrDefault(false))
            out.appendLine("unlike: " + client.likeSong(target, false).getOrDefault(false))
            kotlinx.coroutines.delay(800)
            out.appendLine("re-like: " + client.likeSong(target, true).getOrDefault(false))
            // 添加到歌单可逆往返:找"测试歌单1"(早年测试残留),add→del
            val uid = client.callWeApi("/user/account", emptyMap())
                .obj("profile")?.get("userId").nLong()
            if (uid != null) {
                val pl = client.userPlaylists(uid).getOrNull().orEmpty()
                val testPl = pl.firstOrNull { it.name.contains("测试歌单") }
                if (testPl != null) {
                    out.appendLine("test playlist: ${testPl.id} ${testPl.name}")
                    out.appendLine("add cloud song(以父之名 2717195764): " +
                        client.addToPlaylist(testPl.id, listOf(2717195764L)).getOrDefault(false))
                    kotlinx.coroutines.delay(800)
                    out.appendLine("del: " +
                        client.removeFromPlaylist(testPl.id, listOf(2717195764L)).getOrDefault(false))
                } else {
                    out.appendLine("no test playlist found: " + pl.take(5).map { it.name })
                }
            } else {
                out.appendLine("loginStatus failed")
            }
        }
        File("/tmp/cloud_menu_validity.txt").writeText(out.toString())
    }
}
