package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 收藏/取消收藏 eapi 通道 + desc/tags 完整协议头实证(2026-10-11 callEApi 升级后)。
 * 全部可逆:公共歌单收藏往返结束还原初始态;desc/tags 写测试歌单(残留即测试内容)。
 */
class SubscribeEapiProbe {
    /** 社区静态 checkToken(Enhanced config.json 与 ncm-api-rs CHECK_TOKEN 三家同值),仅探针对照 */
    private val communityStaticToken =
        "9ca17ae2e6ffcda170e2e6ee8af14fbabdb988f225b3868eb2c15a879b9a83d2" +
            "74a790ac8ff54a97b889d5d42af0feaec3b92af58cff99c470a7eafd88f75e" +
            "839a9ea7c14e909da883e83fb692a3abdb6b92adee9e"

    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        if (!writeProbeEnabled("subscribe_eapi")) return
        val sb = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })

            // 0. eapi 读回归哨兵:新协议头下播客家族仍 200
            sb.appendLine("== eapi regression: djradio/category/get ==")
            runCatching {
                val r = client.callEApi("/djradio/category/get", emptyMap())
                sb.appendLine("code=${r["code"]} categories=${(r["categories"] as? kotlinx.serialization.json.JsonArray)?.size}")
            }.onFailure { sb.appendLine("regression ERR ${it.message}") }

            // 1. 收藏往返(公共歌单 3778678=云音乐热歌榜,结束还原)
            val pid = 3778678L
            suspend fun subscribedNow(): String? =
                runCatching {
                    val d = client.callWeApi("/v6/playlist/detail", mapOf("id" to pid, "n" to 0))
                    val pl = d["playlist"] as? kotlinx.serialization.json.JsonObject
                    pl?.get("subscribed").toString()
                }.getOrElse { "ERR ${it.message}" }

            sb.appendLine("== subscribe round-trip (eapi, no token) ==")
            val before = subscribedNow()
            sb.appendLine("subscribed before=$before")
            var subOk =
                client.subscribePlaylist(pid, subscribe = true).fold(
                    { sb.appendLine("SUB ok") ; true },
                    { sb.appendLine("SUB ERR ${it.message}") ; false },
                )
            if (!subOk) {
                sb.appendLine("== retry SUB with community static token ==")
                client.antiCheatSource =
                    object : NeteaseAntiCheatSource {
                        override suspend fun token(businessKey: String): String? = communityStaticToken
                    }
                subOk =
                    client.subscribePlaylist(pid, subscribe = true).fold(
                        { sb.appendLine("SUB(token) ok") ; true },
                        { sb.appendLine("SUB(token) ERR ${it.message}") ; false },
                    )
            }
            kotlinx.coroutines.delay(1500)
            sb.appendLine("subscribed after SUB=${subscribedNow()}")
            // 还原:回到订阅前的状态(默认纯 eapi 形状,token 置空)
            client.antiCheatSource = null
            val restoreSubscribe = before == "true"
            sb.appendLine("== restore (${if (restoreSubscribe) "re-subscribe" else "unsubscribe"}, plain eapi) ==")
            client.subscribePlaylist(pid, subscribe = restoreSubscribe).fold(
                { sb.appendLine("restore ok") },
                { sb.appendLine("restore ERR ${it.message}") },
            )
            kotlinx.coroutines.delay(1500)
            val afterRestore = subscribedNow()
            sb.appendLine("subscribed after restore=$afterRestore (before=$before)")

            // 2. albumDetail 回归(特意迁 eapi 的读路径,新协议头不能回归)
            sb.appendLine("== eapi regression: albumDetail ==")
            client.albumDetail(372081313L).fold(
                { (album, songs) ->
                    sb.appendLine("album=${album.name} songs=${songs.size} descLen=${album.description?.length}")
                },
                { sb.appendLine("albumDetail ERR ${it.message}") },
            )

            // 3. desc:完整协议头下的单笔写入 + 回读(测试歌单)
            val editPid = 18405929547L
            sb.appendLine("== desc with full eapi header ==")
            client.updatePlaylistDescription(editPid, "探针描述-1011完整头").fold(
                { sb.appendLine("desc ok=$it") },
                { sb.appendLine("desc ERR ${it.message}") },
            )

            // 4. tags 攻坚矩阵:plain(已知 200 不落地)/静态 token/数组形状/body csrf
            suspend fun readTags(): String? =
                client.playlistDetail(editPid).fold(
                    { (meta, _) -> meta.tags.toString() },
                    { "ERR ${it.message}" },
                )
            suspend fun writeTagsRaw(extra: Map<String, Any?>, token: String? = null) {
                client.antiCheatSource =
                    token?.let {
                        object : NeteaseAntiCheatSource {
                            override suspend fun token(businessKey: String): String? = it
                        }
                    }
                runCatching {
                    val r =
                        client.callEApi(
                            "/playlist/tags/update",
                            mapOf("id" to editPid.toString(), "tags" to "华语,流行") + extra,
                            antiCheatToken = token,
                        )
                    sb.appendLine("tags write code=${r["code"]} msg=${r["message"]} extra=$extra token=${token != null}")
                }.onFailure { sb.appendLine("tags write ERR ${it.message}") }
                client.antiCheatSource = null
            }
            sb.appendLine("== tags variant A: plain ==")
            writeTagsRaw(emptyMap())
            sb.appendLine("== tags variant B: static token ==")
            writeTagsRaw(emptyMap(), token = communityStaticToken)
            kotlinx.coroutines.delay(30_000)
            sb.appendLine("tags after A/B(+30s)=${readTags()}")
            sb.appendLine("== tags variant C: array shape ==")
            runCatching {
                val arr = kotlinx.serialization.json.JsonArray(
                    listOf("华语", "流行").map { kotlinx.serialization.json.JsonPrimitive(it) },
                )
                val r = client.callEApi("/playlist/tags/update", mapOf("id" to editPid.toString(), "tags" to arr))
                sb.appendLine("tags C code=${r["code"]} msg=${r["message"]}")
            }.onFailure { sb.appendLine("tags C ERR ${it.message}") }
            sb.appendLine("== tags variant D: body csrf_token ==")
            writeTagsRaw(mapOf("csrf_token" to (cookies["__csrf"] ?: "")))
            kotlinx.coroutines.delay(30_000)
            sb.appendLine("tags after C/D(+30s)=${readTags()}")
            kotlinx.coroutines.delay(60_000)
            sb.appendLine("tags final(+90s)=${readTags()} desc=${client.playlistDetail(editPid).getOrNull()?.first?.description}")
        }
        File("/tmp/subscribe_eapi_probe_out.txt").writeText(sb.toString())
        println(sb)
    }
}
