package com.maxrave.netease

import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

/**
 * 云盘能否上传非音频文件探针(2026-10-10 用户问):对一个小 .txt 走全链
 * check→alloc→lbs→nos PUT→info/v2→pub/v2,看服务端在哪一步拒绝;
 * 若意外落盘,结尾按 fileName 清理。
 */
class CloudNonAudioUploadProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        val testFile = File("/tmp/nonaudio_probe_test.txt")
        testFile.writeText("not audio at all - probe 2026-10-10")
        val sb = StringBuilder()
        val json = Json { prettyPrint = true }
        runBlocking {
            val cookies =
                (Json.parseToJsonElement(cookieFile.readText()) as JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val md5 = run {
                val digest = java.security.MessageDigest.getInstance("MD5")
                testFile.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf); if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            sb.appendLine("file=${testFile.name} size=${testFile.length()} md5=$md5")
            val bucket = "jd-musicrep-privatecloud-audio-public"
            val check = client.callWeApi(
                "/cloud/upload/check",
                mapOf("bitrate" to "999000", "ext" to "txt", "length" to testFile.length(), "md5" to md5, "songId" to "0", "version" to 1),
            )
            sb.appendLine("check: " + json.encodeToString(JsonObject.serializer(), check))
            val alloc = client.callWeApi(
                "/nos/token/alloc",
                mapOf("bucket" to bucket, "ext" to "txt", "filename" to testFile.name, "local" to false, "nos_product" to 3, "type" to "audio", "md5" to md5),
            )
            sb.appendLine("alloc: " + json.encodeToString(JsonObject.serializer(), alloc))
            val result = alloc.obj("result")
            val resourceId = result?.get("resourceId").nLong() ?: 0
            val token = result?.str("token")
            val objectKey = result?.str("objectKey")
            if (resourceId > 0 && !token.isNullOrEmpty() && !objectKey.isNullOrEmpty()) {
                // lbs → nos PUT
                val lbsBody = client.callWeApi("/lbs/nos", mapOf("bucket" to bucket)).obj("lbs")
                runCatching {
                    val plain = io.ktor.client.HttpClient()
                    val node = lbsBody?.str("url") ?: "http://wanproxy.127.net"
                    val put = plain.put("$node/$bucket/$objectKey?offset=0&complete=true&version=1.0") {
                        header("x-nos-token", token)
                        header(HttpHeaders.ContentType, "text/plain")
                        header(HttpHeaders.ContentLength, testFile.length())
                        setBody(testFile.readBytes())
                    }
                    sb.appendLine("nos PUT: ${put.status}")
                    plain.close()
                }.onFailure { sb.appendLine("nos PUT ERR: ${it.message}") }
                val info = client.callWeApi(
                    "/upload/cloud/info/v2",
                    mapOf("md5" to md5, "songid" to ((check["songId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""), "filename" to testFile.name, "song" to "nonaudio", "album" to "", "artist" to "", "bitrate" to "999000", "resourceId" to resourceId),
                )
                sb.appendLine("info: " + json.encodeToString(JsonObject.serializer(), info))
                val pub = client.callWeApi("/cloud/pub/v2", mapOf("songid" to ((info["songId"] ?: info["songid"]).nLong() ?: 0L).toString(), "md5" to md5))
                sb.appendLine("pub: " + json.encodeToString(JsonObject.serializer(), pub))
            } else {
                sb.appendLine("alloc 未发 token,链路在此终止")
            }
        }
        File("/tmp/cloud_nonaudio_out.txt").writeText(sb.toString())
    }
}
