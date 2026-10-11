package com.maxrave.netease

import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

/**
 * 云盘歌曲信息纠正(songmatch)端点探针(2026-10-11):
 * 官方端点 weapi /cloud/user/song/match {userId, songId, adjustSongId}(api-enhanced
 * module/cloud_match.js 的 /api 前缀被 request 的 uri.substr(5) 剥掉,真实路径无 /api)。
 * adjustSongId=0 = 解除匹配(社区文档)。
 *
 * 测试对象只挑 ③未匹配私人文件(ar 整个缺席)——匹配后 unmatch 可精确还原原始状态,
 * 不碰 ①②(②是服务端指纹匹配的产物,一旦 unmatch 无法客户端重建)。
 * 输出 /tmp/cloud_match_probe.txt(jvmTest stdout 不显示,写文件)。
 */
class CloudMatchProbe {
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

    /** 拉全量云盘条目(原始 body,保留 ar 形状;600 条封顶防刷) */
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
            out.appendLine("entries pulled=${entries.size} totalCount=${entries.size}")

            val privates = entries.filter { e ->
                val ar = e.simpleSong?.array("ar")
                ar == null || ar.isEmpty()
            }
            val halfMounted = entries.filter { e ->
                val ar = e.simpleSong?.array("ar")
                ar != null && ar.isNotEmpty() && ar.all {
                    ((it as? JsonObject)?.get("id")?.nLong() ?: 0L) <= 0L
                }
            }
            out.appendLine("③未匹配=${privates.size} ②半挂载=${halfMounted.size}")
            privates.take(8).forEach { out.appendLine("  ③: songId=${it.songId} file=${it.fileName}") }
            halfMounted.take(3).forEach { out.appendLine("  ②: songId=${it.songId} file=${it.fileName}") }

            val target = privates.firstOrNull() ?: run {
                out.appendLine("no private file found, abort")
                File("/tmp/cloud_match_probe.txt").writeText(out.toString())
                return@runBlocking
            }
            out.appendLine("== 测试对象: songId=${target.songId} file=${target.fileName}")

            // 1) 基线:对未匹配条目 unmatch(asid=0)——预期报错或无变化,拿错误形状
            runCatching {
                val r = client.callWeApi(
                    "/cloud/user/song/match",
                    mapOf("userId" to uid, "songId" to target.songId, "adjustSongId" to 0L),
                )
                out.appendLine("baseline unmatch on ③: code=${r["code"]} keys=${r.keys} msg=${r.str("message")}")
            }.onFailure { out.appendLine("baseline unmatch on ③ threw: $it") }

            // 2) 搜目标曲(cloudsearch 文件名去扩展名)
            val query = target.fileName.substringBeforeLast('.').replace('_', ' ')
            val search = client.callWeApi(
                "/cloudsearch/pc",
                mapOf("s" to query, "type" to 1, "limit" to 5, "offset" to 0),
            )
            val songs =
                (search.obj("result")?.array("songs") ?: emptyList())
                    .mapNotNull { it as? JsonObject }
            songs.forEach { it.dumpSong("  候选", out) }
            val asid = songs.firstOrNull()?.get("id")?.nLong()
            out.appendLine("== 选定目标 asid=$asid (query=$query)")
            if (asid == null) {
                File("/tmp/cloud_match_probe.txt").writeText(out.toString())
                return@runBlocking
            }

            // 3) match!
            runCatching {
                val r = client.callWeApi(
                    "/cloud/user/song/match",
                    mapOf("userId" to uid, "songId" to target.songId, "adjustSongId" to asid),
                )
                out.appendLine("MATCH: code=${r["code"]} keys=${r.keys} msg=${r.str("message")}")
            }.onFailure { out.appendLine("MATCH threw: $it") }

            // 4) 重拉列表看条目变化(按 fileName 定位:匹配后 songId 可能换)
            kotlinx.coroutines.delay(1500)
            val afterEntries = pullEntries(client)
            val after = afterEntries.firstOrNull { it.fileName == target.fileName }
            val afterByOldId = afterEntries.firstOrNull { it.songId == target.songId }
            out.appendLine("== 匹配后(按文件名): songId=${after?.songId}(原${target.songId})")
            after?.simpleSong?.dumpSong("  新simpleSong", out)
            val oldIdGone = afterByOldId?.fileName ?: "(已不存在=条目id已换)"
            out.appendLine("== 按原songId再找: $oldIdGone")

            // 5) unmatch 还原(asid=0)——用匹配后的当前 id
            val unmatchId = after?.songId ?: target.songId
            runCatching {
                val r = client.callWeApi(
                    "/cloud/user/song/match",
                    mapOf("userId" to uid, "songId" to unmatchId, "adjustSongId" to 0L),
                )
                out.appendLine("UNMATCH(id=$unmatchId): code=${r["code"]} msg=${r.str("message")}")
            }.onFailure { out.appendLine("UNMATCH threw: $it") }

            // 6) 终态重拉:确认还原
            kotlinx.coroutines.delay(1500)
            val restoredEntries = pullEntries(client)
            val restored = restoredEntries.firstOrNull { it.fileName == target.fileName }
            out.appendLine(
                "== 还原终态: songId=${restored?.songId}(原${target.songId} 一致=${restored?.songId == target.songId})",
            )
            restored?.simpleSong?.dumpSong("  还原simpleSong", out)

            File("/tmp/cloud_match_probe.txt").writeText(out.toString())
        }
    }
}
