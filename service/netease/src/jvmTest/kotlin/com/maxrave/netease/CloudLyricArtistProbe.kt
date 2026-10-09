package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/**
 * 云盘歌曲"艺人页/歌词时有时无"归因探针(2026-10-10 用户问):
 * 对三种形状的云盘 id 测 /song/lyric/v1(有无词)与艺人数据可达性
 * (shape①阿爸带真艺人 id=53568434;shape②以父之名/倒影 ar id=0;
 * shape③熊猫人 ar 整个缺席)——找出页面表现分层的边界。
 */
class CloudLyricArtistProbe {
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
            data class Case(val label: String, val songId: Long, val artistId: Long?)

            val cases =
                listOf(
                    Case("①阿爸(完整库元数据,ar id=53568434)", 2162278106L, 53568434L),
                    Case("②以父之名(匹配但 ar id=0)", 2717195764L, null),
                    Case("②倒影(匹配但 ar id=0)", 2147202623L, null),
                    Case("③熊猫人(未匹配)", 3347944512L, null),
                )
            for (c in cases) {
                out.appendLine("== ${c.label} songId=${c.songId} ==")
                client.lyric(c.songId).fold(
                    { l ->
                        val has = listOfNotNull(l.lrc, l.yrc).any { it.isNotBlank() }
                        out.appendLine("lyric: 有词=$has lrc=${l.lrc?.length ?: 0}字 tlyric=${l.translated?.length ?: 0}字")
                        out.appendLine("  lrc头: ${l.lrc?.take(40)?.replace("\n", "|")}")
                    },
                    { out.appendLine("lyric ERR: ${it.message}") },
                )
                if (c.artistId != null) {
                    val detail = client.callWeApi("/artist/detail", mapOf("id" to c.artistId))
                    out.appendLine("artist detail code: ${detail["code"]}")
                } else {
                    out.appendLine("artist: 无艺人 id 可导航")
                }
            }
            // 对照:真实曲库 id 的以父之名(185868)
            client.lyric(185868L).fold(
                { l -> out.appendLine("== 对照 真实库以父之名 185868: lrc=${l.lrc?.length ?: 0}字 头=${l.lrc?.take(30)?.replace("\n", "|")}") },
                { out.appendLine("对照 ERR ${it.message}") },
            )
        }
        File("/tmp/cloud_lyric_artist.txt").writeText(out.toString())
    }
}
