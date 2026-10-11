package com.maxrave.netease

import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

/**
 * songmatch 四轮矩阵(2026-10-11):UI 实测屋顶(②半挂载)match 目标 5257138 被
 * 400"当前歌曲不支持匹配"拒绝——对照三轮(③私人文件 match 200 成功)。验证:
 * A ②条目 songId vs simpleSong.id 是否一致(sid 口径)
 * B ② match 复现 400
 * C ② unmatch 后立刻 match 同一目标(解除→重配路径)
 * D 另一首②(大头贴)同样流程交叉验证
 * E ①完整挂载(阿爸)match 会不会也被拒
 * 输出 /tmp/cloud_match_matrix.txt。
 */
class CloudMatchMatrixProbe {
    private data class Entry(val songId: Long, val fileName: String, val simpleSong: JsonObject?)

    private fun JsonObject.dumpSong(prefix: String, out: StringBuilder) {
        val ar = array("ar")?.mapNotNull { it as? JsonObject }
        out.appendLine(
            "$prefix id=${this["id"]} name=${str("name")} ar=${
                ar?.joinToString(",") { a -> "${a["id"]}:${a.str("name")}" }
            } al=${obj("al")?.get("id")}",
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

    private suspend fun tryMatch(
        client: NeteaseClient,
        uid: Long?,
        sid: Long,
        asid: Long,
        label: String,
        out: StringBuilder,
    ) {
        runCatching {
            val r = client.callWeApi(
                "/cloud/user/song/match",
                mapOf("userId" to uid, "songId" to sid, "adjustSongId" to asid),
            )
            out.appendLine("$label: code=${r["code"]} msg=${r.str("message")}")
        }.onFailure { out.appendLine("$label threw: $it") }
    }

    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        if (!writeProbeEnabled("cloud_match_matrix")) return
        val out = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val uid = client.getAccountStatus().getOrNull()?.userId

            val entries = pullEntries(client)
            val roof = entries.firstOrNull { it.fileName.contains("屋顶") }
            val roofId = roof?.songId ?: return@runBlocking
            out.appendLine("屋顶当前: songId=$roofId simple=${roof.simpleSong?.get("id")}")

            // 1) 目标 fee 事实
            val detail = client.callApi("/v3/song/detail", mapOf("c" to "[{\"id\":5257138},{\"id\":26632226},{\"id\":2001032548},{\"id\":461811019},{\"id\":157360}]"))
            (detail.array("songs") ?: emptyList()).forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                out.appendLine("fee事实: ${o["id"]} ${o.str("name")} fee=${o["fee"]}")
            }

            // 2) manual-① 条目 + fee=1 目标(157360 吴宗宪/温岚屋顶)
            tryMatch(client, uid, roofId, 157360L, "屋顶(manual①)→fee1目标", out)
            kotlinx.coroutines.delay(1500)

            // 3) manual-① 条目 + fee=0 目标(已占用除外)——再拿一个 fee0(南来的风? 用搜索)
            //    直接复用 461811019 会"已在云盘存在"(自己占着),先解除再试
            tryMatch(client, uid, roofId, 0L, "屋顶unmatch(还原+清手动)", out)
            kotlinx.coroutines.delay(2000)
            val restored = pullEntries(client).firstOrNull { it.fileName == roof?.fileName }
            out.appendLine("unmatch后: songId=${restored?.songId}")
            restored?.simpleSong?.dumpSong("  ", out)

            File("/tmp/cloud_match_matrix.txt").writeText(out.toString())
        }
    }
}
