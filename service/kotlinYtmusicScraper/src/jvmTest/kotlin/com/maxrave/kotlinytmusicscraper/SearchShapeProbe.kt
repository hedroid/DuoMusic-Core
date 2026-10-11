package com.maxrave.kotlinytmusicscraper

import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * 形状探针(2026-10-11):用户报 YT 源搜索"周杰伦/孙燕姿"只有歌单/播客有数据,
 * songs/videos/albums/artists 四类全空。排查结论(非 app bug):
 * YT 服务端约 2026-09/10 上线了对低信任出口 IP(数据中心/机场段)的 music 搜索降级——
 * song/video/album/featured filter 一律返回"找不到结果"(HTTP 200+messageRenderer),
 * 无 filter 搜索只剩社区实体(用户频道/社区歌单/播客);artist/community/podcast filter 仍正常。
 * 同出口下 browse 端点与 youtube.com 主站搜索不受影响;换 client(ANDROID_MUSIC/IOS/MWEB/WEB)、
 * 换 clientVersion、带登录 cookie、改 gl/hl 均不豁免。恢复=换非数据中心出口。
 * 本探针逐 filter 实测并输出 items 数量与类型分布,复跑可直接确认某出口是否被降级。
 * 跑法: ./gradlew :kotlinYtmusicScraper:jvmTest --tests "*SearchShapeProbe*"
 * (可选:把 YT cookie 放 /tmp/yt_cookie.txt 会带登录复测;注意 jvm 探针 locale 取宿主机,
 * gl=CN 出口会被服务端 403,结论以请求日志为准。)
 */
class SearchShapeProbe {
    @Test
    fun searchShape() =
        runBlocking {
            val out = File("/tmp/search_shape_probe.txt")
            out.writeText("")
            fun log(s: String) {
                out.appendText(s + "\n")
            }

            val yt = YouTube()
            yt.setProxy(true, "127.0.0.1", 7897)
            File("/tmp/yt_cookie.txt").takeIf { it.exists() }?.readText()?.trim()?.let {
                yt.cookie = it
                log("cookie loaded: ${it.length} chars")
            }

            val filters =
                mapOf(
                    "song" to YouTube.SearchFilter.FILTER_SONG,
                    "video" to YouTube.SearchFilter.FILTER_VIDEO,
                    "album" to YouTube.SearchFilter.FILTER_ALBUM,
                    "artist" to YouTube.SearchFilter.FILTER_ARTIST,
                    "featuredPlaylist" to YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST,
                    "communityPlaylist" to YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
                    "podcast" to YouTube.SearchFilter.FILTER_PODCAST,
                )
            for ((name, f) in filters) {
                val r = yt.search("周杰伦", f)
                r.onSuccess { res ->
                    val byType = res.items.groupBy { it::class.simpleName }.mapValues { it.value.size }
                    log("PROBE $name: items=${res.items.size} types=$byType podcasts=${res.listPodcast.size} continuation=${res.continuation != null}")
                }.onFailure { e ->
                    log("PROBE $name: FAILURE ${e::class.simpleName}: ${e.message}")
                }
            }
            log("DONE")
        }
}
