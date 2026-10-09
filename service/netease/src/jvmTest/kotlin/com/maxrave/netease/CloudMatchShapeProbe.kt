package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 云盘条目"匹配/未匹配"形状探针(2026-10-09 CR:菜单门控判据考证):
 * 对每个文件打 songId / simpleSong.id / ar(id,name) / al(id,name) / dt / fee,
 * 找出"未匹配曲库"条目的可判别特征(id 是否仍是库 id、ar 是否只有名字)。
 */
class CloudMatchShapeProbe {
    @Test
    fun dump() {
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
            out.appendLine("== /v1/cloud/get raw entries ==")
            // 直接拿原始 JSON,不经模型,防解析层丢字段
            val body = client.callWeApi("/v1/cloud/get", mapOf("limit" to 30, "offset" to 0))
            val data = (body["data"] as? kotlinx.serialization.json.JsonArray) ?: run {
                out.appendLine("no data: code=${body["code"]}"); return@runBlocking
            }
            data.take(30).forEachIndexed { i, el ->
                val o = el as kotlinx.serialization.json.JsonObject
                val songId = o["songId"].toString()
                val fn = o["fileName"]?.let { it.toString().trim('"') } ?: ""
                val ss = o["simpleSong"] as? kotlinx.serialization.json.JsonObject
                if (ss == null) {
                    out.appendLine("#$i songId=$songId file=$fn | NO simpleSong")
                } else {
                    val sId = ss["id"].toString()
                    val ar = (ss["ar"] as? kotlinx.serialization.json.JsonArray)?.map { a ->
                        val ao = a as kotlinx.serialization.json.JsonObject
                        "${ao["id"]}:${ao["name"]}"
                    }
                    val al = ss["al"] as? kotlinx.serialization.json.JsonObject
                    val alStr = al?.let { "${it["id"]}:${it["name"]}" } ?: "null"
                    val dt = ss["dt"].toString()
                    val fee = ss["fee"].toString()
                    out.appendLine("#$i songId=$songId ssId=$sId ar=$ar al=$alStr dt=$dt fee=$fee file=$fn")
                }
            }
        }
        File("/tmp/cloud_match_shape.txt").writeText(out.toString())
    }
}
