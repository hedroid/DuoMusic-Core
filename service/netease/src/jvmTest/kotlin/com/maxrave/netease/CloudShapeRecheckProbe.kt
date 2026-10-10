package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/** 大头贴当前挂载形状复核(2026-10-10:播放页它显示真词,疑服务端匹配升级) */
class CloudShapeRecheckProbe {
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
            val page = client.cloudDiskFiles(limit = 200).getOrNull()
            val f = page?.files?.firstOrNull { it.songId == 2159176771L }
            if (f != null) {
                out.appendLine("大头贴: song=${f.song?.name} artists=${f.song?.artists} artistIds=${f.song?.artistIds} albumId=${f.song?.albumId}")
                client.lyric(2159176771L).fold(
                    { l -> out.appendLine("lyric: ${l.lrc?.length ?: 0}字 头=${l.lrc?.take(30)?.replace("\n", "|")}") },
                    { out.appendLine("lyric ERR ${it.message}") },
                )
            } else {
                out.appendLine("not found in first 200")
            }
        }
        File("/tmp/shape_recheck.txt").writeText(out.toString())
    }
}
