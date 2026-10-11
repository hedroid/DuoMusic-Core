package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * /dj/program/detail 单节目直取形状探针(手动,只读,非 CI):
 * 播客续播跨页找回(NeteaseRadioDetailViewModel.resumePlayback miss 分支)的前置验证——
 * djProgramLikeInfo 已实证该端点健康,但只读了 liked 族字段;这里要确认 program 行
 * 是否与 byradio 列表元素同构(重点 mainSong.id 存在,toDjProgramOrNull 能完整解析)。
 * cookie 文件不存在时静默跳过。输出 /tmp/dj_program_detail_out.txt。
 */
class DjProgramDetailProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: /tmp/netease_cookies.json not found")
            return
        }
        val out = File("/tmp/dj_program_detail_out.txt")
        kotlinx.coroutines.runBlocking {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
            val cookies =
                (json as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val sb = StringBuilder()

            suspend fun section(name: String, block: suspend () -> String) {
                try {
                    sb.appendLine("== $name ==\n${block()}\n")
                } catch (e: Throwable) {
                    sb.appendLine("== $name ==\nFAILED: ${e.message}\n")
                }
            }

            // 样本来源:订阅电台(最真实)+大台对照(清音悦耳 972022490)
            val radio =
                client.userDjRadios(limit = 5).getOrNull()?.firstOrNull()
                    ?: client.djRadioToplist().getOrNull()?.firstOrNull()
            section("sample radio") { radio?.let { "${it.id}|${it.name}|programCount=${it.programCount}" } ?: "none" }

            val samples =
                if (radio != null) {
                    client.djRadioPrograms(radio.id, limit = 3, offset = 0).getOrNull()?.first.orEmpty()
                } else {
                    emptyList()
                }
            val control = client.djRadioPrograms(972022490L, limit = 2, offset = 0).getOrNull()?.first.orEmpty()

            section("byradio sample vs detail 逐字段对照") {
                val sb2 = StringBuilder()
                (samples.take(2) + control.take(1)).forEach { p ->
                    sb2.appendLine("-- program ${p.id} (${p.name.take(16)}) --")
                    sb2.appendLine("byradio : mainSongId=${p.mainSongId} durMs=${p.durationMs} serial=${p.serialNum} paid=${p.paid}")
                    val body = client.callApi("/dj/program/detail", mapOf("id" to p.id))
                    sb2.appendLine("detail  : code=${body["code"].nInt()} keys=${body.keys.joinToString(",").take(90)}")
                    val row = body.obj("program")
                    if (row == null) {
                        sb2.appendLine("detail  : NO program row")
                    } else {
                        sb2.appendLine(
                            "detail  : mainSong.id=${row.obj("mainSong")?.get("id").nLong()} mainSong.fee=${row.obj("mainSong")?.get("fee").nInt()} " +
                                "duration=${row["duration"].nLong()} serialNum=${row["serialNum"].nInt()} programFeeType=${row["programFeeType"].nInt()}",
                        )
                        val parsed = row.toDjProgramOrNull()
                        sb2.appendLine(
                            "parsed  : id=${parsed?.id} mainSongId=${parsed?.mainSongId} durMs=${parsed?.durationMs} name=${parsed?.name?.take(14)} " +
                                "radioId=${parsed?.radioId} paid=${parsed?.paid}",
                        )
                    }
                }
                sb2.toString().trim()
            }

            section("不存在节目 id 对照(下架/脏数据回落路径)") {
                val body = client.callApi("/dj/program/detail", mapOf("id" to 1L))
                "code=${body["code"].nInt()} hasProgram=${body.obj("program") != null} msg=${body.str("msg") ?: body.str("message")}"
            }

            // 模拟器"跨页找回"确定性测试的素材:第二页(offset=30)首个节目——
            // 详情页只载第一页 30 条,这个 id 必然 miss 触发 /dj/program/detail 直取
            section("故事FM 第二页节目(offset=30,跨页找回测试素材)") {
                val page2 = client.djRadioPrograms(350038649L, limit = 5, offset = 30, asc = false).getOrNull()?.first.orEmpty()
                page2.take(3).joinToString("\n") { "pid=${it.id} serial=${it.serialNum} name=${it.name.take(20)} mainSongId=${it.mainSongId}" }
            }

            out.writeText(sb.toString())
            println(sb.toString())
        }
    }
}
