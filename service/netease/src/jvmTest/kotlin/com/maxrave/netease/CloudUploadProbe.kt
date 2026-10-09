package com.maxrave.netease

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

/** 复现 batch3 info/v2 404(uploadStatus:9):check/alloc/info 三步打全形状 */
class CloudUploadProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        val testFile = File("/tmp/upload_test.mp3")
        if (!cookieFile.exists() || !testFile.exists()) return
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
            sb.appendLine("md5=$md5 size=${testFile.length()}")
            val bucket = "jd-musicrep-privatecloud-audio-public"
            val check = client.callWeApi(
                "/cloud/upload/check",
                mapOf("bitrate" to "999000", "ext" to "", "length" to testFile.length(), "md5" to md5, "songId" to "0", "version" to 1),
            )
            sb.appendLine("check: " + json.encodeToString(JsonObject.serializer(), check))
            val alloc = client.callWeApi(
                "/nos/token/alloc",
                mapOf("bucket" to bucket, "ext" to "flac", "filename" to testFile.name, "local" to false, "nos_product" to 3, "type" to "audio", "md5" to md5),
            )
            sb.appendLine("alloc: " + json.encodeToString(JsonObject.serializer(), alloc))
            val hexSongId = (check["songId"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val resourceId = (alloc.obj("result")?.get("resourceId")).nLong() ?: 0
            val info = client.callWeApi(
                "/upload/cloud/info/v2",
                mapOf("md5" to md5, "songid" to hexSongId, "filename" to testFile.name, "song" to "batch3", "album" to "", "artist" to "", "bitrate" to "999000", "resourceId" to resourceId),
            )
            sb.appendLine("info: " + json.encodeToString(JsonObject.serializer(), info))
        }
        File("/tmp/cloud_upload_out.txt").writeText(sb.toString())
        println(sb)
    }
}
