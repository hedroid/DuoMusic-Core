package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.AlbumItem
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * 专辑空壳形状探针(2026-10-11):用户报通知页点 YT 专辑"有的能开有的报错"
 * (degraded album response)。本探针用 app 同款请求路径(登录 cookie+WEB_REMIX)
 * 分类:①用户报错专辑(The Cranberries - Songs For Fall)连测两次看一致性;
 * ②FEmusic_new_releases 货架(与通知页同源人群=近期新发行)抽 10 张逐张
 * album() 分类,输出 OK(title/songCount) vs FAIL(message)。
 * 跑法: ./gradlew :kotlinYtmusicScraper:jvmTest --tests "*AlbumShapeProbe*"
 * (cookie 放 /tmp/yt_cookie.txt=app 的 ytdlp-cookie.txt 转 header 串)
 */
class AlbumShapeProbe {
    @Test
    fun albumShape() =
        runBlocking {
            val out = File("/tmp/album_shape_probe.txt")
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
            // app 同款初始化:先取 visitorData 再发业务请求(CommonRepositoryImpl 同构)
            runCatching {
                yt.visitorData()?.let {
                    yt.visitorData = it
                    log("visitorData fetched: ${it.length} chars")
                }
            }.onFailure { log("visitorData fetch fail: ${it.message}") }

            repeat(2) { i ->
                yt.album("MPREb_1GKkTpx5AJf")
                    .onSuccess { p -> log("CRANBERRIES#$i OK title='${p.album.title}' songs=${p.songs.size}") }
                    .onFailure { e -> log("CRANBERRIES#$i FAIL ${e.message}") }
            }

            // 对照组:Room 里 app 真实打开成功过的 YT 专辑(同 egress 同上下文)
            listOf("MPREb_Kcmgu3xJAgL" to "八度空間", "MPREb_M78uXenkeHU" to "The Moment").forEach { (cid, cname) ->
                yt.album(cid)
                    .onSuccess { p -> log("CONTROL[$cname] OK title='${p.album.title}' songs=${p.songs.size}") }
                    .onFailure { e -> log("CONTROL[$cname] FAIL ${e.message}") }
            }

            // 对照总体:YT 艺人专辑列表(app"更多专辑"同款 browse+params),与通知页人群同构
            val artists =
                mapOf(
                    "sun" to "UCLFS9T3qlBLQsVlLASHC1ug",
                    "faye" to "UCnAmvOfJxmcMd5q48BEIuPQ",
                )
            for ((aname, aid) in artists) {
                var listed: List<AlbumItem> = emptyList()
                for (attempt in 1..3) {
                    val r = yt.browse(browseId = aid, params = "ggMIegYIARoCAQI=")
                    val albums = r.getOrNull()?.items?.firstOrNull()?.items.orEmpty().filterIsInstance<AlbumItem>()
                    if (r.isSuccess && albums.isNotEmpty()) {
                        listed = albums
                        break
                    }
                    log("ARTIST $aname list attempt$attempt fail: ${r.exceptionOrNull()?.message?.take(80)}")
                    Thread.sleep(6000)
                }
                log("ARTIST $aname shelfAlbums=${listed.size}")
                listed.take(6).forEach { a ->
                    yt.album(a.browseId)
                        .onSuccess { p -> log("AR[$aname] ${a.browseId} OK '${p.album.title}' songs=${p.songs.size}") }
                        .onFailure { e -> log("AR[$aname] ${a.browseId} FAIL '${a.title}' -> ${e.message}") }
                }
            }
            log("DONE")
        }
}
