package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 挂载程度可预测性探针(2026-10-10 用户问):云盘 songId 是否等于真实曲库 id?
 * 假设:完整挂载的歌(歌词/simi 可用)其 songId 就是真实库 id;半挂载的是云端
 * 另发 id。用 cloudsearch 查同名歌拿真实 id,与云盘 id 对比+歌词可用性交叉。
 */
class CloudMountPredictorProbe {
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
            data class Case(val label: String, val cloudId: Long, val query: String)
            val cases =
                listOf(
                    Case("倒影(ar id=0,歌词可用)", 2147202623L, "倒影 周杰伦"),
                    Case("以父之名(ar id=0,歌词不可用)", 2717195764L, "以父之名 周杰伦"),
                    Case("阿爸(完整挂载,歌词可用)", 2162278106L, "阿爸 leizhyu-3"),
                )
            for (c in cases) {
                val body = client.callWeApi(
                    "/cloudsearch/pc",
                    mapOf("s" to c.query, "type" to 1, "limit" to 3, "offset" to 0),
                )
                val songs = (body.obj("result")?.get("songs") as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject) }
                    .orEmpty()
                val ids = songs.mapNotNull { it["id"].nLong() to (it.str("name") ?: "") }
                out.appendLine("${c.label}: 云盘id=${c.cloudId} 搜索库id=$ids 一致=${ids.any { it.first == c.cloudId }}")
            }
        }
        File("/tmp/cloud_mount_predictor.txt").writeText(out.toString())
    }
}
