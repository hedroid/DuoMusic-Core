package com.maxrave.netease

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.util.cio.toByteReadChannel
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test

/**
 * 云盘上传链路探针(手动,非 CI):check → alloc → lbs → POST nos → info/v2 → pub/v2 → verify → del。
 * 形状参考 NeteaseCloudMusicApiEnhanced plugins/songUpload.js(官方 App 同款)。
 * /tmp/netease_cookies.json + /tmp/upload_test.mp3 存在才跑;产物 /tmp/cloud_upload_out.txt;
 * 结束自清理(/cloud/del),不往真实账号留垃圾。
 */
class CloudUploadProbe {
    @Test
    fun probeCloudUpload() {
        val cookieFile = File("/tmp/netease_cookies.json")
        val testFile = File("/tmp/upload_test.mp3")
        if (!cookieFile.exists() || !testFile.exists()) {
            println("skip: cookie(${cookieFile.exists()}) or testfile(${testFile.exists()}) missing")
            return
        }
        val out = File("/tmp/cloud_upload_out.txt")
        val sb = StringBuilder()
        val json = Json { prettyPrint = true }
        runBlocking {
            val cookies =
                (Json.parseToJsonElement(cookieFile.readText()) as JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val http = HttpClient(com.maxrave.ktorext.getEngine())

            suspend fun section(name: String, block: suspend () -> String) {
                try {
                    sb.appendLine("== $name ==\n${block()}\n")
                } catch (e: Throwable) {
                    sb.appendLine("== $name ==\nFAILED: ${e.message}\n")
                }
            }

            val md5 = run {
                val digest = java.security.MessageDigest.getInstance("MD5")
                testFile.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            val size = testFile.length()
            val fileName = testFile.name
            val ext = fileName.substringAfterLast('.', "mp3")
            val bucket = "jd-musicrep-privatecloud-audio-public"
            sb.appendLine("md5=$md5 size=$size ext=$ext")

            val before = client.cloudDiskFiles(limit = 3).getOrNull()
            sb.appendLine("before: total=${before?.totalCount}")

            // 1. check:needUpload=false 表示云端已有同 md5(秒传)
            var songId = ""
            var needUpload = true
            section("check") {
                val body = client.callWeApi(
                    "/cloud/upload/check",
                    mapOf(
                        "bitrate" to "999000",
                        "ext" to "",
                        "length" to size,
                        "md5" to md5,
                        "songId" to "0",
                        "version" to 1,
                    ),
                )
                json.encodeToString(JsonObject.serializer(), body).also {
                    songId = (body["songId"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    needUpload = (body["needUpload"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                }
            }
            sb.appendLine("parsed: songId=$songId needUpload=$needUpload")
            if (songId.isEmpty()) {
                sb.appendLine("no songId, abort")
                out.writeText(sb.toString()); println(sb)
                return@runBlocking
            }

            // 2. alloc:nos 上传令牌(bucket 带 -public 后缀)
            var objectKey = ""
            var nosToken = ""
            var resourceId = ""
            section("alloc") {
                val body = client.callWeApi(
                    "/nos/token/alloc",
                    mapOf(
                        "bucket" to bucket,
                        "ext" to ext,
                        "filename" to fileName,
                        "local" to false,
                        "nos_product" to 3,
                        "type" to "audio",
                        "md5" to md5,
                    ),
                )
                json.encodeToString(JsonObject.serializer(), body).also {
                    val result = body.obj("result")
                    objectKey = result?.str("objectKey").orEmpty()
                    nosToken = result?.str("token").orEmpty()
                    resourceId = result?.get("resourceId").nLong()?.toString() ?: ""
                }
            }
            sb.appendLine("parsed: objectKey=${objectKey.take(40)} token=${nosToken.take(20)}… resourceId=$resourceId")

            if (needUpload && nosToken.isNotEmpty()) {
                // 3. lbs:上传节点
                var uploadNode = ""
                section("lbs") {
                    val resp = http.get("https://wanproxy.127.net/lbs?version=1.0&bucketname=$bucket")
                    val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    uploadNode = (body["upload"] as? kotlinx.serialization.json.JsonArray)
                        ?.firstOrNull()?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                        .orEmpty()
                    resp.bodyAsText().take(400) + "\nnode=$uploadNode"
                }
                if (uploadNode.isEmpty()) {
                    sb.appendLine("no upload node, abort")
                    out.writeText(sb.toString()); println(sb)
                    return@runBlocking
                }

                // 4. POST 上传(流式 body,复现 app 的 toByteReadChannel 路径;校验服务端
                //    实收字节数 offset==size,防"空对象上传成功但转码丢弃"静默失败)
                section("nosPost") {
                    val encodedKey = objectKey.replace("/", "%2F")
                    val resp =
                        http.post("$uploadNode/$bucket/$encodedKey?offset=0&complete=true&version=1.0") {
                            header("x-nos-token", nosToken)
                            header("Content-MD5", md5)
                            header(HttpHeaders.ContentType, "audio/mpeg")
                            header(HttpHeaders.ContentLength, size.toString())
                            setBody(testFile.inputStream().toByteReadChannel())
                        }
                    "status=${resp.status.value} body=${resp.bodyAsText().take(300)}"
                }
            }

            // 5. info/v2:提交元数据(songid 小写 d);响应 songId/songIdLong=真正的发布 id(数字)
            var publishSongId = ""
            section("info") {
                val body = client.callWeApi(
                    "/upload/cloud/info/v2",
                    mapOf(
                        "md5" to md5,
                        "songid" to songId,
                        "filename" to fileName,
                        "song" to "上传探针测试曲",
                        "album" to "SimpMusic探针",
                        "artist" to "SimpMusic",
                        "bitrate" to "999000",
                        "resourceId" to resourceId,
                    ),
                )
                json.encodeToString(JsonObject.serializer(), body).also {
                    publishSongId = (body["songId"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                }
            }
            sb.appendLine("parsed: publishSongId=$publishSongId")

            // 6. pub/v2:发布 id 用 info 响应的数字 songId
            section("pub") {
                val body = client.callWeApi("/cloud/pub/v2", mapOf("songid" to publishSongId))
                json.encodeToString(JsonObject.serializer(), body)
            }

            kotlinx.coroutines.delay(3000)

            // 7. verify
            section("after") {
                client.cloudDiskFiles(limit = 5).fold(
                    { p ->
                        "total=${p.totalCount}\n" + p.files.joinToString("\n") {
                            "${it.songId} | ${it.fileName} | song=${it.song?.name} ar=${it.song?.artists}"
                        }
                    },
                    { "ERR ${it.message}" },
                )
            }

            // 8. cleanup:cloud/del 参数形状多试(数组体/直接值/长id)
            val delId = client.cloudDiskFiles(limit = 30).getOrNull()
                ?.files?.firstOrNull { it.song?.name == "上传探针测试曲" }?.songId ?: publishSongId.toLongOrNull() ?: 0L
            sb.appendLine("delId=$delId")
            section("del") {
                val variants =
                    listOf(
                        "arrJson" to mapOf("songIds" to "[$delId]"),
                        "plain" to mapOf("songIds" to delId),
                        "weapiArr" to mapOf("songIds" to "[$delId]"),
                    )
                buildString {
                    for ((label, params) in variants) {
                        val body =
                            if (label == "weapiArr") client.callWeApi("/cloud/del", params)
                            else client.callApi("/cloud/del", params)
                        appendLine("[$label] " + json.encodeToString(JsonObject.serializer(), body))
                    }
                }
            }
            kotlinx.coroutines.delay(2000)
            section("afterDel") {
                client.cloudDiskFiles(limit = 30).fold(
                    { p ->
                        val probeLeft = p.files.any { it.song?.name == "上传探针测试曲" }
                        "total=${p.totalCount} probeFileLeft=$probeLeft"
                    },
                    { "ERR ${it.message}" },
                )
            }

            out.writeText(sb.toString())
            println(sb)
        }
    }
}
