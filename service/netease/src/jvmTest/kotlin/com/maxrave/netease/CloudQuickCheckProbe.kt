package com.maxrave.netease

import java.io.File
import kotlin.test.Test

class CloudQuickCheckProbe {
    @Test
    fun check() {
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
            client.cloudDiskFiles(limit = 10).fold(
                { p ->
                    sb.appendLine("total=${p.totalCount} used=${p.usedSizeBytes} max=${p.maxSizeBytes}")
                    p.files.take(4).forEach { sb.appendLine("${it.songId} | ${it.fileName} | ${it.song?.name} | ${it.song?.artists}") }
                },
                { sb.appendLine("ERR ${it.message}") },
            )
        }
        File("/tmp/cloud_check_out.txt").writeText(sb.toString())
        println(sb)
    }
}
