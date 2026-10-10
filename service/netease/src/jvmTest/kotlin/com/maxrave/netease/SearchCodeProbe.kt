package com.maxrave.netease

import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 搜索端点业务码统一校验前置探针(只读):给 searchSongs(1)/searchAlbums(10)/
 * searchArtists(100)/searchPlaylists(1000) 四个 cloudsearch 兄弟和两个异形端点
 * (明文 /search/suggest/web、weapi /search/hot)确认顶层 code 字段的形状与存在性,
 * 再决定 check 怎么写(code 缺席的端点强校验会把成功路径打爆)。
 * 匿名+带 cookie 双通道。输出 /tmp/search_code_probe_out.txt
 */
class SearchCodeProbe {
    @Test
    fun probeSearchCodes() {
        val out = File("/tmp/search_code_probe_out.txt")
        val sb = StringBuilder()

        suspend fun probeRaw(
            client: NeteaseClient,
            label: String,
            block: suspend () -> JsonObject?,
        ) {
            val body = runCatching { block() }.getOrElse {
                sb.appendLine("$label: HTTP/EXC FAIL ${it.message?.take(80)}")
                return
            }
            sb.appendLine("$label: code=${body?.get("code")} hasResult=${body?.containsKey("result")} keys=${body?.keys?.take(8)}")
        }

        kotlinx.coroutines.runBlocking {
            val anon = NeteaseClient()
            sb.appendLine("== 匿名通道 ==")
            probeRaw(anon, "cloudsearch type=1   ") { anon.callWeApi("/cloudsearch/pc", mapOf("s" to "周杰伦", "type" to 1, "limit" to 1, "offset" to 0)) }
            probeRaw(anon, "cloudsearch type=10  ") { anon.callWeApi("/cloudsearch/pc", mapOf("s" to "周杰伦", "type" to 10, "limit" to 1, "offset" to 0)) }
            probeRaw(anon, "cloudsearch type=100 ") { anon.callWeApi("/cloudsearch/pc", mapOf("s" to "周杰伦", "type" to 100, "limit" to 1, "offset" to 0)) }
            probeRaw(anon, "cloudsearch type=1000") { anon.callWeApi("/cloudsearch/pc", mapOf("s" to "周杰伦", "type" to 1000, "limit" to 1, "offset" to 0)) }
            probeRaw(anon, "search/suggest/web   ") { anon.callApi("/search/suggest/web", mapOf("s" to "FM", "limit" to 3)) }
            probeRaw(anon, "search/hot           ") { anon.callWeApi("/search/hot", mapOf("type" to 1111)) }

            val cookieFile = File("/tmp/netease_cookies.json")
            if (cookieFile.exists()) {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                val cookies =
                    (json as JsonObject).entries.associate { (k, v) ->
                        k to (v as JsonPrimitive).content
                    }
                val authed = NeteaseClient(cookieProvider = { cookies })
                sb.appendLine("== 登录通道 ==")
                probeRaw(authed, "search/suggest/web   ") { authed.callApi("/search/suggest/web", mapOf("s" to "FM", "limit" to 3)) }
                probeRaw(authed, "search/hot           ") { authed.callWeApi("/search/hot", mapOf("type" to 1111)) }

                // typed 层面:校验上线后六个端点的成功路径不被 check 误伤
                sb.appendLine("== typed 成功路径 ==")
                authed.searchSongs("周杰伦", limit = 2).fold(
                    onSuccess = { sb.appendLine("searchSongs ok items=${it.items.size}") },
                    onFailure = { sb.appendLine("searchSongs FAIL ${it.message?.take(60)}") },
                )
                authed.searchAlbums("周杰伦", limit = 2).fold(
                    onSuccess = { sb.appendLine("searchAlbums ok items=${it.items.size}") },
                    onFailure = { sb.appendLine("searchAlbums FAIL ${it.message?.take(60)}") },
                )
                authed.searchArtists("周杰伦", limit = 2).fold(
                    onSuccess = { sb.appendLine("searchArtists ok items=${it.items.size}") },
                    onFailure = { sb.appendLine("searchArtists FAIL ${it.message?.take(60)}") },
                )
                authed.searchPlaylists("周杰伦", limit = 2).fold(
                    onSuccess = { sb.appendLine("searchPlaylists ok items=${it.items.size}") },
                    onFailure = { sb.appendLine("searchPlaylists FAIL ${it.message?.take(60)}") },
                )
                authed.searchSuggest("FM", limit = 3).fold(
                    onSuccess = { sb.appendLine("searchSuggest ok songs=${it.songs.size} artists=${it.artists.size}") },
                    onFailure = { sb.appendLine("searchSuggest FAIL ${it.message?.take(60)}") },
                )
                authed.searchHot().fold(
                    onSuccess = { sb.appendLine("searchHot ok words=${it.size}") },
                    onFailure = { sb.appendLine("searchHot FAIL ${it.message?.take(60)}") },
                )
            }
            out.writeText(sb.toString())
            println(sb)
        }
    }
}
