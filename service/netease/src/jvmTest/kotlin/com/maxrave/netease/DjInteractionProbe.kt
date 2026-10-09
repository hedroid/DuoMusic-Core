package com.maxrave.netease

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Manual real-account probe. The marker must exist before cookies or a client are touched. */
class DjInteractionProbe {
    @Test
    fun cleanupTemporaryComment() {
        if (!File("/tmp/dj_comment_cleanup.enabled").exists()) {
            println("skip: /tmp/dj_comment_cleanup.enabled absent")
            return
        }
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        runBlocking {
            val cookies = (Json.parseToJsonElement(cookieFile.readText()) as JsonObject).entries.associate { (key, value) ->
                key to (value as JsonPrimitive).content
            }
            val client = NeteaseClient(cookieProvider = { cookies })
            val programId = 3080522427L
            val threadId = "A_DJ_1_$programId"
            suspend fun temporary() = client.songCommentsV2(0, sortType = 3, cursor = "0", threadId = threadId)
                .getOrThrow().comments.firstOrNull { it.content.startsWith("SimpMusic 接口测试 ") }
            val comment = temporary() ?: return@runBlocking
            client.commentDelete(programId, comment.commentId ?: error("id missing"), threadId).getOrThrow()
            var cleared = false
            for (attempt in 0 until 10) {
                delay(800)
                if (temporary() == null) { cleared = true; break }
            }
            assertEquals(true, cleared, "temporary comment remains")
            println("temporary comment deleted and absent on readback")
        }
    }
    @Test
    fun readOnlyState() {
        if (!File("/tmp/dj_interaction_probe.enabled").exists()) {
            println("skip: /tmp/dj_interaction_probe.enabled absent")
            return
        }
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: cookie file absent")
            return
        }
        runBlocking {
            val cookies = (Json.parseToJsonElement(cookieFile.readText()) as JsonObject).entries.associate { (key, value) ->
                key to (value as JsonPrimitive).content
            }
            val client = NeteaseClient(cookieProvider = { cookies })
            val programId = 3080522427L
            val info = client.djProgramLikeInfo(programId).getOrThrow()
            println("program liked=${info.liked} likes=${info.likedCount} comments=${info.commentCount}")
            val comments = client.songCommentsV2(0, sortType = 3, cursor = "0", threadId = "A_DJ_1_$programId").getOrThrow()
            println("comments total=${comments.totalCount} pageSize=${comments.comments.size} firstLiked=${comments.comments.firstOrNull()?.liked} firstId=${comments.comments.firstOrNull()?.commentId}")
            assertEquals(info.commentCount.toInt(), comments.totalCount, "program detail and comment thread totals differ")
            println("temporary comments=${comments.comments.filter { it.content.startsWith("SimpMusic 接口测试 ") }.map { "${it.commentId}|${it.content}" }}")
        }
    }

    /** One reversible toggle per target. Never run from the full test suite without the marker. */
    @Test
    fun reversibleCommentLike() {
        if (!File("/tmp/dj_comment_like_probe.enabled").exists()) {
            println("skip: /tmp/dj_comment_like_probe.enabled absent")
            return
        }
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: cookie file absent")
            return
        }
        runBlocking {
            val cookies = (Json.parseToJsonElement(cookieFile.readText()) as JsonObject).entries.associate { (key, value) ->
                key to (value as JsonPrimitive).content
            }
            val client = NeteaseClient(cookieProvider = { cookies })
            val programId = 3080522427L
            val threadId = "A_DJ_1_$programId"
            suspend fun comment() = client.songCommentsV2(0, sortType = 3, cursor = "0", threadId = threadId).getOrThrow().comments.first()
            suspend fun awaitComment(expected: Boolean): Boolean {
                repeat(10) {
                    if (comment().liked == expected) return true
                    delay(800)
                }
                return false
            }
            val initialComment = comment()
            val commentId = initialComment.commentId ?: error("comment id missing")
            try {
                client.commentLike(programId, commentId, !initialComment.liked, threadId).getOrThrow()
                assertEquals(true, awaitComment(!initialComment.liked), "comment toggle not confirmed")
            } finally {
                client.commentLike(programId, commentId, initialComment.liked, threadId).getOrThrow()
                assertEquals(true, awaitComment(initialComment.liked), "comment state not restored")
                println("comment like toggled and restored")
            }
        }
    }

    /** Public comment write: explicit marker, unique text, and mandatory deletion/readback. */
    @Test
    fun temporaryCommentPost() {
        if (!File("/tmp/dj_comment_post_probe.enabled").exists()) {
            println("skip: /tmp/dj_comment_post_probe.enabled absent")
            return
        }
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        runBlocking {
            val cookies = (Json.parseToJsonElement(cookieFile.readText()) as JsonObject).entries.associate { (key, value) ->
                key to (value as JsonPrimitive).content
            }
            val client = NeteaseClient(cookieProvider = { cookies })
            val programId = 3080522427L
            val threadId = "A_DJ_1_$programId"
            val content = "SimpMusic 接口测试 ${System.currentTimeMillis()}（测试后删除）"
            suspend fun findCommentId(): Long? = client.songCommentsV2(0, sortType = 3, cursor = "0", threadId = threadId)
                .getOrThrow().comments.firstOrNull { it.content == content }?.commentId
            var commentId: Long? = null
            var accepted = false
            try {
                commentId = client.commentAdd(programId, content, threadId).getOrThrow()
                accepted = true
                for (attempt in 0 until 10) {
                    if (commentId == null) commentId = findCommentId()
                    if (commentId != null) break
                    delay(800)
                }
                check(commentId != null) { "posted comment id missing; manual cleanup required: $content" }
                var visible = false
                for (attempt in 0 until 10) {
                    if (findCommentId() == commentId) { visible = true; break }
                    delay(800)
                }
                check(visible) { "post response id not visible yet; cleanup will still run: $content" }
                println("post accepted with comment id")
            } finally {
                val id = commentId ?: findCommentId()
                if (id != null) {
                    var deleted = false
                    for (attempt in 0 until 10) {
                        if (client.commentDelete(programId, id, threadId).isSuccess) { deleted = true; break }
                        delay(800)
                    }
                    check(deleted) { "temporary comment deletion failed: $content" }
                    var absent = false
                    for (attempt in 0 until 10) {
                        delay(800)
                        if (findCommentId() == null) { absent = true; break }
                    }
                    assertEquals(true, absent, "temporary comment still visible")
                    println("temporary comment deleted and absent on readback")
                } else if (accepted) {
                    error("temporary comment id unknown; manual cleanup required: $content")
                }
            }
        }
    }
}
