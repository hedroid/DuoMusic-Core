package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/** 清理实测期间上传到用户真实云盘的测试文件(还原 242 首) */
class CloudCleanupProbe {
    @Test
    fun cleanup() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        val sb = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val testIds =
                setOf(
                    3445922611L, // 25-梁咏琪.mp3(探针)
                    3445952627L, // A Sad Me(sadtest/app)
                    406387978L, // bass-test.flac
                    471151469L, // bass 19:23 轮(若单独成条)
                    3445967170L, // final-test.mp3 (Auf)
                )
            for (id in testIds) {
                val ok = client.deleteCloudDiskSong(id).getOrDefault(false)
                sb.appendLine("del $id -> $ok")
            }
            kotlinx.coroutines.delay(2500)
            client.cloudDiskFiles(limit = 5).fold(
                { p ->
                    sb.appendLine("after: total=${p.totalCount}")
                    p.files.take(3).forEach { sb.appendLine("${it.songId} | ${it.fileName}") }
                },
                { sb.appendLine("verify ERR ${it.message}") },
            )
        }
        File("/tmp/cloud_cleanup_out.txt").writeText(sb.toString())
        println(sb)
    }
}
