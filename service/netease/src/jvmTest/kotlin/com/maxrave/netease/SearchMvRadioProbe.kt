package com.maxrave.netease

import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 搜索页 MV/播客 tab 三轮探针(只读):直接调新端点函数 searchMvs/searchDjRadios,
 * 验证类型化解析链(封面/时长/艺人/节目数)。前两轮结论见 SearchMvRadioProbe git 历史:
 * MV 分页健康;电台硬上限 10 条无分页。输出 /tmp/search_mv_radio_probe3_out.txt
 */
class SearchMvRadioProbe3 {
    @Test
    fun probeTypedEndpoints() {
        val cookieFile = File("/tmp/netease_cookies.json")

        // 匿名通道(不带 cookie):业务码校验上线后,登录前搜索也必须 code=200
        val anonSb = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val anon = NeteaseClient()
            anon.searchMvs("周杰伦", limit = 5).fold(
                onSuccess = { anonSb.appendLine("anon searchMvs: total=${it.totalCount} items=${it.items.size} first=${it.items.firstOrNull()?.name?.take(12)}") },
                onFailure = { anonSb.appendLine("anon searchMvs FAILED: ${it.message?.take(80)}") },
            )
            anon.searchDjRadios("故事FM", limit = 5).fold(
                onSuccess = { anonSb.appendLine("anon searchDjRadios: total=${it.totalCount} items=${it.items.size} first=${it.items.firstOrNull()?.name?.take(12)}") },
                onFailure = { anonSb.appendLine("anon searchDjRadios FAILED: ${it.message?.take(80)}") },
            )
        }
        if (!cookieFile.exists()) {
            println(anonSb)
            File("/tmp/search_mv_radio_probe3_out.txt").writeText(anonSb.toString())
            return
        }
        val out = File("/tmp/search_mv_radio_probe3_out.txt")
        kotlinx.coroutines.runBlocking {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
            val cookies =
                (json as JsonObject).entries.associate { (k, v) ->
                    k to (v as JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val sb = StringBuilder()

            client.searchMvs("周杰伦", limit = 30).fold(
                onSuccess = { r ->
                    sb.appendLine("searchMvs: total=${r.totalCount} items=${r.items.size}")
                    r.items.take(3).forEach {
                        sb.appendLine(
                            "  mv id=${it.id} name=${it.name.take(16)} cover=${it.coverUrl?.take(48)} " +
                                "dur=${it.durationMs} artist=${it.artistId}:${it.artistName} play=${it.playCount}",
                        )
                    }
                    sb.appendLine("  cover缺失=${r.items.count { it.coverUrl == null }} duration=0条数=${r.items.count { it.durationMs <= 0 }}")
                },
                onFailure = { sb.appendLine("searchMvs FAILED: ${it.message}") },
            )

            client.searchDjRadios("故事FM", limit = 30).fold(
                onSuccess = { r ->
                    sb.appendLine("searchDjRadios: total=${r.totalCount} items=${r.items.size}")
                    r.items.take(3).forEach {
                        sb.appendLine(
                            "  radio id=${it.id} name=${it.name.take(12)} cover=${it.coverUrl?.take(48)} " +
                                "programs=${it.programCount} dj=${it.djNickname} sub=${it.subCount} rcmd=${it.rcmdtext?.take(16)}",
                        )
                    }
                    sb.appendLine("  cover缺失=${r.items.count { it.coverUrl == null }} dj缺失=${r.items.count { it.djNickname == null }}")
                },
                onFailure = { sb.appendLine("searchDjRadios FAILED: ${it.message}") },
            )

            out.writeText(anonSb.toString() + "\n" + sb.toString())
            println(anonSb)
            println(sb)
        }
    }
}
