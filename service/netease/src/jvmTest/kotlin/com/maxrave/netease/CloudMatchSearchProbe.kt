package com.maxrave.netease

import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

/**
 * 匹配候选搜索通道对比(2026-10-11 三轮):cloudsearch/pc 对 VIP 原曲(周杰伦系列)
 * 只回翻唱/DJ 版,原版不上榜——官方"信息纠正"流程有专用搜索 /api/search/match/new,
 * 本探针验证其形状与可用性;同时对照"歌名+艺人"的 cloudsearch 是否能把原版带出来。
 * 输出 /tmp/cloud_match_search.txt。
 */
class CloudMatchSearchProbe {
    private fun JsonObject.peek(prefix: String, out: StringBuilder) {
        val result = obj("result") ?: obj("data")
        val songs = result?.array("songs") ?: array("songs")
        out.appendLine("$prefix code=${this["code"]} keys=${keys.take(8)} songs=${songs?.size}")
        songs?.take(10)?.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            // 老端点 search/get 用 artists 字段,cloudsearch 用 ar
            val ar =
                o.array("ar")?.mapNotNull { (it as? JsonObject) }
                    ?: o.array("artists")?.mapNotNull { (it as? JsonObject) }
            out.appendLine(
                "   ${o["id"]} ${o.str("name")} | ${
                    ar?.joinToString("/") { "${it["id"]}:${it.str("name")}" }
                } | fee=${o["fee"]}",
            )
        }
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
            val q = "屋顶"

            // A) cloudsearch 艺人词对照
            runCatching {
                client.callWeApi("/cloudsearch/pc", mapOf("s" to "$q 周杰伦", "type" to 1, "limit" to 20, "offset" to 0))
                    .peek("A cloudsearch(歌名+艺人)", out)
            }.onFailure { out.appendLine("A threw: ${it.message}") }

            // B) 官方匹配专用搜索 eapi(头缺 csrf 也不影响?试两种)
            runCatching {
                client.callEApi(
                    "/api/search/match/new",
                    mapOf("s" to q, "limit" to 20, "offset" to 0, "type" to 1),
                ).peek("B eapi search/match/new {s,limit,offset,type}", out)
            }.onFailure { out.appendLine("B threw: ${it.message}") }

            // C) 同端点 weapi 通道
            runCatching {
                client.callWeApi("/search/match/new", mapOf("s" to q, "limit" to 20, "offset" to 0, "type" to 1))
                    .peek("C weapi search/match/new", out)
            }.onFailure { out.appendLine("C threw: ${it.message}") }

            // D) 老搜索端点 search/get(网页版通道)
            runCatching {
                client.callWeApi("/search/get", mapOf("s" to q, "type" to 1, "limit" to 20, "offset" to 0))
                    .peek("D weapi search/get", out)
            }.onFailure { out.appendLine("D threw: ${it.message}") }

            // E) cloudsearch 屋顶(找温岚/周杰伦原版)
            runCatching {
                client.callWeApi("/cloudsearch/pc", mapOf("s" to q, "type" to 1, "limit" to 20, "offset" to 0))
                    .peek("E cloudsearch 屋顶", out)
            }.onFailure { out.appendLine("E threw: ${it.message}") }

            File("/tmp/cloud_match_search.txt").writeText(out.toString())
        }
    }
}
