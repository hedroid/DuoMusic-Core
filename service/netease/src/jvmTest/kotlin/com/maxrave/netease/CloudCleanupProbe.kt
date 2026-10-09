package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/** 清理实测期间上传到用户真实云盘的测试文件(按文件名匹配——匹配后 songId 会变,按 id 删不可靠) */
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
            val testNames =
                setOf(
                    "upload_test.mp3", "25-梁咏琪.mp3", "sadtest-upload.mp3",
                    "bass-test.flac", "final-test.mp3",
                    "batch1.flac", "batch2.flac", "batch3.flac", "batch4.flac", "delid-test.mp3",
                    // 2026-10-09 行内上传队列实测(含改字节合成文件)
                    "测试上传-A.mp3", "测试上传-B.mp3",
                    "A Sad Me In Your Eyes-昨夜派对（L.N Party）.mp3",
                    "我是真的爱上你_-_王杰.flac", "爱情讯息_-_yihuik苡慧.flac",
                )
            // 拉全量(分页),按 fileName 匹配删除
            val all = mutableListOf<NeteaseCloudFile>()
            var offset = 0
            while (true) {
                val page = client.cloudDiskFiles(limit = 200, offset = offset).getOrNull() ?: break
                all += page.files
                if (page.files.isEmpty() || offset + page.files.size >= page.totalCount) break
                offset += page.files.size
            }
            sb.appendLine("scanned=${all.size}")
            for (f in all) {
                if (f.fileName in testNames) {
                    val ok = client.deleteCloudDiskSong(f.songId).getOrDefault(false)
                    sb.appendLine("del ${f.songId} ${f.fileName} -> $ok")
                }
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

private typealias NeteaseCloudFile = com.maxrave.netease.model.NeteaseCloudFile
