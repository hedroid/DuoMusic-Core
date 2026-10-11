package com.maxrave.netease

import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

/**
 * songmatch 二轮(2026-10-11):一轮实证 ①sid 必须用条目当前 id(unmatch 会触发
 * 重指纹换 id);②unmatch 对未匹配文件也是 200 且触发重匹配。本轮用最后的 ③
 * 千山万水.mp3 验证"匹配成功"形态:sid=当前 id → match 真实曲库条目 → 重拉看
 * id 换轨/ar 真实 id(①)/歌词可用性 → unmatch 看终态。输出 /tmp/cloud_match_probe2.txt。
 */
class CloudMatchProbe2 {
    private data class Entry(val songId: Long, val fileName: String, val simpleSong: JsonObject?)

    private fun JsonObject.dumpSong(prefix: String, out: StringBuilder) {
        val ar = array("ar")?.mapNotNull { it as? JsonObject }
        out.appendLine(
            "$prefix id=${this["id"]} name=${str("name")} dt=${this["dt"]} " +
                "ar=${
                    ar?.joinToString(",") { a -> "${a["id"]}:${a.str("name")}" }
                } al=${obj("al")?.get("id")}:${obj("al")?.str("name")}",
        )
    }

    private suspend fun pullEntries(client: NeteaseClient): MutableList<Entry> {
        val entries = mutableListOf<Entry>()
        var offset = 0
        var count = 0
        do {
            val body = client.callWeApi("/v1/cloud/get", mapOf("limit" to 200, "offset" to offset))
            count = body["count"].nInt() ?: 0
            val arr = body.array("data") ?: break
            arr.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val songId = obj["songId"].nLong() ?: return@forEach
                entries.add(Entry(songId, obj.str("fileName") ?: "?", obj.obj("simpleSong")))
            }
            offset += 200
        } while (offset < count && offset < 600)
        return entries
    }

    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        if (!writeProbeEnabled("cloud_match2")) return
        val out = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val uid = client.getAccountStatus().getOrNull()?.userId
            out.appendLine("uid=$uid")

            val entries = pullEntries(client)
            val target = entries.firstOrNull { it.fileName == "千山万水.mp3" } ?: run {
                out.appendLine("千山万水.mp3 not found")
                File("/tmp/cloud_match_probe2.txt").writeText(out.toString())
                return@runBlocking
            }
            out.appendLine("== 目标(当前): songId=${target.songId}")
            target.simpleSong?.dumpSong("  原simpleSong", out)

            // 搜真实曲库条目
            val search = client.callWeApi(
                "/cloudsearch/pc",
                mapOf("s" to "千山万水", "type" to 1, "limit" to 8, "offset" to 0),
            )
            val songs =
                (search.obj("result")?.array("songs") ?: emptyList())
                    .mapNotNull { it as? JsonObject }
            songs.forEach { it.dumpSong("  候选", out) }
            // 选 ar id 真实且名字精确的候选;没有就选第一个 ar id>0 的
            val chosen =
                songs.firstOrNull { it.str("name") == "千山万水" && (it.array("ar")?.size ?: 0) > 0 }
                    ?: songs.firstOrNull {
                        ((it.array("ar")?.firstOrNull() as? JsonObject)?.get("id")?.nLong() ?: 0L) > 0L
                    }
            val asid = chosen?.get("id")?.nLong()
            out.appendLine("== 选定 asid=$asid")
            if (asid == null) {
                File("/tmp/cloud_match_probe2.txt").writeText(out.toString())
                return@runBlocking
            }

            // match(用当前 id)
            runCatching {
                val r = client.callWeApi(
                    "/cloud/user/song/match",
                    mapOf("userId" to uid, "songId" to target.songId, "adjustSongId" to asid),
                )
                out.appendLine("MATCH: code=${r["code"]} keys=${r.keys} msg=${r.str("message")}")
            }.onFailure { out.appendLine("MATCH threw: $it") }

            kotlinx.coroutines.delay(2000)
            val after = pullEntries(client).firstOrNull { it.fileName == "千山万水.mp3" }
            out.appendLine("== 匹配后: songId=${after?.songId}(原${target.songId})")
            after?.simpleSong?.dumpSong("  新simpleSong", out)

            // 匹配后的歌词可用性(lrc 长度)
            val newId = after?.songId ?: target.songId
            runCatching {
                val r = client.callWeApi("/song/lyric/v1", mapOf("id" to newId, "cv" to 1, "lv" to 1, "kv" to 1, "rv" to 1, "tv" to -1))
                val lrc = r.obj("lrc")?.str("lyric")
                out.appendLine("lyric(newId=$newId): lrcLen=${lrc?.length} head=${lrc?.take(60)}")
            }.onFailure { out.appendLine("lyric threw: $it") }

            // unmatch 看终态(服务端会不会立刻重新指纹匹配回去)
            runCatching {
                val r = client.callWeApi(
                    "/cloud/user/song/match",
                    mapOf("userId" to uid, "songId" to newId, "adjustSongId" to 0L),
                )
                out.appendLine("UNMATCH: code=${r["code"]} msg=${r.str("message")}")
            }.onFailure { out.appendLine("UNMATCH threw: $it") }

            kotlinx.coroutines.delay(2000)
            val restored = pullEntries(client).firstOrNull { it.fileName == "千山万水.mp3" }
            out.appendLine("== unmatch 终态: songId=${restored?.songId}")
            restored?.simpleSong?.dumpSong("  终态simpleSong", out)

            // 如果终态不再是刚才手动匹配的条目,再查一次它当前 id 的歌词,看解除后词还在不在
            val finalId = restored?.songId ?: newId
            if (finalId != newId) {
                runCatching {
                    val r = client.callWeApi("/song/lyric/v1", mapOf("id" to finalId, "cv" to 1, "lv" to 1, "kv" to 1, "rv" to 1, "tv" to -1))
                    val lrc = r.obj("lrc")?.str("lyric")
                    out.appendLine("lyric(finalId=$finalId): lrcLen=${lrc?.length} head=${lrc?.take(60)}")
                }.onFailure { out.appendLine("lyric threw: $it") }
            }

            File("/tmp/cloud_match_probe2.txt").writeText(out.toString())
        }
    }
}
