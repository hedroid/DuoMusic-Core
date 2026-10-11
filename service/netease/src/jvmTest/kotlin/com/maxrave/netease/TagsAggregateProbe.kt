package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * tags 写聚合端点实验(2026-10-11):WearMusic 逆出的官方 app 形状——/playlist/update
 * 聚合端点,响应按子路径键(如 "/api/playlist/tags/update")返回各子操作结果。
 * 官方 app 实测能改标签(用户 2026-10-11 验证),而我们单打 /playlist/tags/update 被吞写。
 */
class TagsAggregateProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        if (!writeProbeEnabled("tags_aggregate")) return
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
                    { (meta, _) -> "tags=${meta.tags}" },
                    { "ERR ${it.message}" },
                )
            sb.appendLine("== before: ${readTags()} ==")

            // A: 仅 id+tags
            runCatching {
                val r =
                    client.callEApi(
                        "/playlist/update",
                        mapOf("id" to pid.toString(), "tags" to "华语,流行"),
                    )
                sb.appendLine("A keys=${r.keys}")
                sb.appendLine("A full=${r.toString().take(500)}")
            }.onFailure { sb.appendLine("A ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after A: ${readTags()}")

            // B: 带 timestamp(WearMusic 同款附带字段)
            if (!readTags().contains("华语")) {
                runCatching {
                    val r =
                        client.callEApi(
                            "/playlist/update",
                            mapOf(
                                "id" to pid.toString(),
                                "tags" to "华语,流行",
                                "timestamp" to System.currentTimeMillis().toString(),
                            ),
                        )
                    sb.appendLine("B keys=${r.keys} full=${r.toString().take(500)}")
                }.onFailure { sb.appendLine("B ERR ${it.message}") }
                kotlinx.coroutines.delay(8000)
                sb.appendLine("after B: ${readTags()}")
            }

            // C: tags 数组形状
            if (!readTags().contains("华语")) {
                runCatching {
                    val arr = kotlinx.serialization.json.JsonArray(
                        listOf("华语", "流行").map { kotlinx.serialization.json.JsonPrimitive(it) },
                    )
                    val r = client.callEApi("/playlist/update", mapOf("id" to pid.toString(), "tags" to arr))
                    sb.appendLine("C keys=${r.keys} full=${r.toString().take(500)}")
                }.onFailure { sb.appendLine("C ERR ${it.message}") }
                kotlinx.coroutines.delay(8000)
                sb.appendLine("after C: ${readTags()}")
            }
            // D: 安卓 App 身份打单端点(tags 是 app 独占功能,PC/web 身份疑被软拒)
            runCatching {
                val r =
                    client.callEApi(
                        "/playlist/tags/update",
                        mapOf("id" to pid.toString(), "tags" to "华语,流行"),
                        profile = NeteaseClient.EapiDeviceProfile.ANDROID,
                    )
                sb.appendLine("D(android) full=${r.toString().take(300)}")
            }.onFailure { sb.appendLine("D ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after D(android profile): ${readTags()}")

            // E: 安卓 App 身份打聚合端点
            runCatching {
                val r =
                    client.callEApi(
                        "/playlist/update",
                        mapOf("id" to pid.toString(), "tags" to "华语,流行"),
                        profile = NeteaseClient.EapiDeviceProfile.ANDROID,
                    )
                sb.appendLine("E(android aggregate) full=${r.toString().take(300)}")
            }.onFailure { sb.appendLine("E ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after E(android aggregate): ${readTags()}")
            // F: /api/batch(ncm-api-rs playlist_update.rs 逆出的官方 app 形状:
            // body={"<子路径>":"<json字符串参数>"};只带 tags 子请求防误清 name/desc)
            runCatching {
                val r =
                    client.callEApi(
                        "/batch",
                        mapOf(
                            "/api/playlist/tags/update" to """{"id":$pid,"tags":"华语,流行"}""",
                        ),
                    )
                sb.appendLine("F(batch) full=${r.toString().take(400)}")
            }.onFailure { sb.appendLine("F ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after F(batch): ${readTags()}")
            // G: 官方三件套完整 batch(name/desc 用现值防清空;ncm-api-rs playlist_update 同款)
            runCatching {
                val r =
                    client.callEApi(
                        "/batch",
                        mapOf(
                            "/api/playlist/update/name" to """{"id":$pid,"name":"测试歌单1"}""",
                            "/api/playlist/desc/update" to """{"id":$pid,"desc":"app－verify3"}""",
                            "/api/playlist/tags/update" to """{"id":$pid,"tags":"华语,流行"}""",
                        ),
                    )
                sb.appendLine("G(full batch) full=${r.toString().take(400)}")
            }.onFailure { sb.appendLine("G ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after G: ${readTags()}")

            // H: 安卓身份+完整 batch(最贴近官方 app)
            runCatching {
                val r =
                    client.callEApi(
                        "/batch",
                        mapOf(
                            "/api/playlist/update/name" to """{"id":$pid,"name":"测试歌单1"}""",
                            "/api/playlist/desc/update" to """{"id":$pid,"desc":"app－verify3"}""",
                            "/api/playlist/tags/update" to """{"id":$pid,"tags":"华语,流行"}""",
                        ),
                        profile = NeteaseClient.EapiDeviceProfile.ANDROID,
                    )
                sb.appendLine("H(android full batch) full=${r.toString().take(400)}")
            }.onFailure { sb.appendLine("H ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after H: ${readTags()}")

            // 词表:hottags 完整响应(标签是否有 id;华语/流行是否仍在官方词表)
            runCatching {
                val r = client.callApi("/playlist/hottags", emptyMap())
                sb.appendLine("hottags full=${r.toString().take(600)}")
            }.onFailure { sb.appendLine("hottags ERR ${it.message}") }

            // 长程回读(2 分钟)
            repeat(4) { round ->
                kotlinx.coroutines.delay(30_000)
                sb.appendLine("long$round(+${(round + 1) * 30}s): ${readTags()}")
            }
            // I: 判别实验——同笔 batch 带"改动的 name"(落=name 通道通,tags 单独被门;
            //    不落=整个 batch 写被环境软拒),结束还原 name
            suspend fun readNameAndTags(): String =
                client.playlistDetail(pid).fold(
                    { (meta, _) -> "name=${meta.name} tags=${meta.tags}" },
                    { "ERR ${it.message}" },
                )
            runCatching {
                val r =
                    client.callEApi(
                        "/batch",
                        mapOf(
                            "/api/playlist/update/name" to """{"id":$pid,"name":"测试歌单1X"}""",
                            "/api/playlist/tags/update" to """{"id":$pid,"tags":"华语,流行"}""",
                        ),
                    )
                sb.appendLine("I(name canary) full=${r.toString().take(300)}")
            }.onFailure { sb.appendLine("I ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after I: ${readNameAndTags()}")
            // J: tags 传 ID(hottags:华语=5001 流行=1)
            runCatching {
                val r =
                    client.callEApi(
                        "/batch",
                        mapOf(
                            "/api/playlist/tags/update" to """{"id":$pid,"tags":"5001,1"}""",
                        ),
                    )
                sb.appendLine("J(tags ids) full=${r.toString().take(300)}")
            }.onFailure { sb.appendLine("J ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after J: ${readNameAndTags()}")
            kotlinx.coroutines.delay(30_000)
            sb.appendLine("final(+30s): ${readNameAndTags()}")
            // 还原 name
            client.updatePlaylistName(pid, "测试歌单1").fold(
                { sb.appendLine("name restored") },
                { sb.appendLine("name restore ERR ${it.message}") },
            )
            // K: 分号分隔(hua0512 注释:tags must be a string separated by ;)
            runCatching {
                val r =
                    client.callEApi(
                        "/batch",
                        mapOf(
                            "/api/playlist/tags/update" to """{"id":$pid,"tags":"华语;流行"}""",
                        ),
                    )
                sb.appendLine("K(semicolon) full=${r.toString().take(300)}")
            }.onFailure { sb.appendLine("K ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after K: ${readNameAndTags()}")

            // L: 单端点+分号
            runCatching {
                val r =
                    client.callEApi(
                        "/playlist/tags/update",
                        mapOf("id" to pid.toString(), "tags" to "华语;流行"),
                    )
                sb.appendLine("L(single;semicolon) full=${r.toString().take(200)}")
            }.onFailure { sb.appendLine("L ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after L: ${readNameAndTags()}")

            // M: weapi /batch/ 子对象形状(hua0512 同款)
            runCatching {
                val r =
                    client.callWeApi(
                        "/batch/",
                        mapOf(
                            "/api/playlist/tags/update" to
                                kotlinx.serialization.json.buildJsonObject {
                                    put("id", kotlinx.serialization.json.JsonPrimitive(pid.toString()))
                                    put("tags", kotlinx.serialization.json.JsonPrimitive("华语;流行"))
                                },
                        ),
                    )
                sb.appendLine("M(weapi batch obj) full=${r.toString().take(300)}")
            }.onFailure { sb.appendLine("M ERR ${it.message}") }
            kotlinx.coroutines.delay(8000)
            sb.appendLine("after M: ${readNameAndTags()}")
        }
        File("/tmp/tags_aggregate_out.txt").writeText(sb.toString())
        println(sb)
    }
}
