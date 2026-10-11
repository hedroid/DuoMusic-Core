package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/** 只读:用户真实自建歌单的 tags 字段(官方客户端年代设置的)——证明账号可持有 tags */
class RealPlaylistTagsReadProbe {
    @Test
    fun probe() {
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
            client.userPlaylists(94291755L).fold(
                { list ->
                    list.filter { it.creatorId == 94291755L }.take(12).forEach { pl ->
                        client.playlistDetail(pl.id).fold(
                            { (meta, ids) ->
                                sb.appendLine("${meta.name} | tags=${meta.tags} | tracks=${ids.size} | specialType=${meta.rawSpecialType}")
                            },
                            { sb.appendLine("${pl.name} detail ERR ${it.message}") },
                        )
                    }
                },
                { sb.appendLine("userPlaylists ERR ${it.message}") },
            )
        }
        File("/tmp/real_tags_out.txt").writeText(sb.toString())
        println(sb)
    }
}
