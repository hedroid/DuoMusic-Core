package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/** 上传一首测试歌(delid-test.mp3,秒传)供 app 内删除 UI 实测 */
class CloudDeleteIdProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        val testFile = File("/tmp/upload_test.mp3")
        if (!cookieFile.exists() || !testFile.exists()) return
        val sb = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val md5 = java.security.MessageDigest.getInstance("MD5").let { d ->
                testFile.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = input.read(buf); if (n <= 0) break; d.update(buf, 0, n) }
                }
                d.digest().joinToString("") { "%02x".format(it) }
            }
            val upload = client.uploadToCloudDisk(
                fileName = "delid-test.mp3", ext = "mp3", sizeBytes = testFile.length(), md5 = md5,
                song = "delid-test", album = "", artist = "", contentType = "audio/mpeg",
                openBody = { io.ktor.utils.io.ByteReadChannel("".encodeToByteArray()) },
            )
            sb.appendLine("upload: ${upload.getOrNull()} ${upload.exceptionOrNull()?.message}")
        }
        File("/tmp/cloud_delid_out.txt").writeText(sb.toString())
        println(sb)
    }
}
