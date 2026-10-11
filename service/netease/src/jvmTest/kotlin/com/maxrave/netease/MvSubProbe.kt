package com.maxrave.netease

import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MV 取消收藏端点探针(手动,非 CI)。三轮历史:
 * 一轮(10-08):weapi/eapi × /mv/unsub×3 全 HTTP 400;/mv/sub t=int0/"0"/false/省略 全 200 假成功。
 * 二轮:参数类型变体全假成功。
 * 三轮(本轮):未测通道补齐——明文 /api/mv/sub(通用 post 全通道带 MUSIC_U)、xeapi
 * (官方 App 9.5.x 写通道,评论同管线);以及"新鲜 MV 判别"(前两轮目标一直是测试
 * 残留的卡住 MV,需排除"该条特例"假设:新收藏一条再取消,若新条也取消不掉=方向性
 * 服务端限制定案)。后续核查已推翻该结论:社区实现提示 /mv/unsub 还需要 JSON 数组字符串
 * mvIds,补齐后实测取消成功;本探针的 /mv/unsub 只传 mvId,不能证明端点不存在。
 * 每步 delay 2s 回读 sublist+subed 对账。输出 /tmp/mv_sub_probe_out.txt
 *
 * ⚠️ 本探针向真实账号发写操作;2026-10-08 曾留下两条残留,后经正确取消请求清除。
 * cookie 文件在本机常态存在,不能当开关——普通
 * jvmTest/全量测试绝不能误触。启用方式(用标记文件而非环境变量:Gradle daemon
 * 不透传新 shell 的 env):`touch /tmp/mv_sub_probe.enabled`,跑完删除。
 */
class MvSubProbe {
    @Test
    fun probeMvUnsub() {
        if (!File("/tmp/mv_sub_probe.enabled").exists()) {
            println("skip: touch /tmp/mv_sub_probe.enabled to enable (real-account writes, residue is irreversible)")
            return
        }
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) {
            println("skip: /tmp/netease_cookies.json not found")
            return
        }
        if (!writeProbeEnabled("mv_sub")) return
        val out = File("/tmp/mv_sub_probe_out.txt")
        kotlinx.coroutines.runBlocking {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
            val cookies =
                (json as JsonObject).entries.associate { (k, v) ->
                    k to (v as JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })
            val sb = StringBuilder()

            fun JsonObject?.codeStr(): String = this?.get("code")?.let { (it as? JsonPrimitive)?.content } ?: "null"

            suspend fun subbedIds(): List<Long> {
                val ids = mutableListOf<Long>()
                var offset = 0
                while (true) {
                    val page = client.mvSublist(limit = 100, offset = offset).getOrNull() ?: break
                    if (page.mvs.isEmpty()) break
                    ids += page.mvs.map { it.id }
                    if (!page.hasMore) break
                    offset += 100
                }
                return ids
            }

            suspend fun stateOf(id: Long): String {
                val inList = id in subbedIds()
                val detail = client.mvDetail(id).getOrNull()
                val subed = detail?.subed
                return "inSublist=$inList detailSubed=$subed (name=${detail?.name?.take(20)})"
            }

            val initialSubbed = subbedIds()
            sb.appendLine("initial sublist size=${initialSubbed.size} firstIds=${initialSubbed.take(8)}")
            // 判别用新鲜 MV:榜单里挑一条未收藏的
            val fresh =
                client.mvToplist(limit = 30).getOrNull()?.first
                    ?.firstOrNull { it.id !in initialSubbed }
                    ?: error("no fresh mv from toplist")
            sb.appendLine("fresh mv=${fresh.id} name=${fresh.name.take(20)}")

            var winner: String? = null
            suspend fun attempt(
                label: String,
                target: Long,
                block: suspend () -> JsonObject?,
            ) {
                if (winner != null) return
                val body = runCatching { block() }
                delay(2000)
                val state = stateOf(target)
                val unsubscribed = !state.startsWith("inSublist=true") && !state.contains("detailSubed=true")
                sb.appendLine("== $label ==\ncode=${body.getOrNull().codeStr()} err=${body.exceptionOrNull()?.message?.take(120)}\nafter: $state -> ${if (unsubscribed) "UNSUBSCRIBED(WIN)" else "still subscribed"}\n")
                if (unsubscribed) winner = label
            }

            // ── A. 卡住的旧 MV(前两轮目标):补测明文与 xeapi 通道 ──
            val stuck = initialSubbed.firstOrNull()
            if (stuck != null) {
                sb.appendLine("stuck target=$stuck before: ${stateOf(stuck)}")
                attempt("明文 /api/mv/sub {mvId,t:0} on stuck", stuck) {
                    client.callApi("/mv/sub", linkedMapOf("mvId" to stuck, "t" to 0))
                }
                attempt("明文 /api/mv/unsub {mvId} on stuck", stuck) {
                    client.callApi("/mv/unsub", linkedMapOf("mvId" to stuck))
                }
                attempt("xeapi /mv/sub {mvId,t:\"0\"} on stuck", stuck) {
                    client.callXeApi(
                        "/mv/sub",
                        linkedMapOf("mvId" to stuck.toString(), "t" to "0"),
                    )
                }
            }

            // ── B. 新鲜 MV 判别:t=1 收藏(已知可用)→ t=0 取消,验证方向性 ──
            if (winner == null) {
                val sub = runCatching { client.mvSub(fresh.id, true) }
                delay(2000)
                sb.appendLine("fresh pre-subscribe(t=1): result=$sub after: ${stateOf(fresh.id)}")
                if (fresh.id in subbedIds()) {
                    attempt("weapi /mv/sub {mvId,t:0} on FRESH", fresh.id) {
                        client.callWeApi("/mv/sub", linkedMapOf("mvId" to fresh.id, "t" to 0))
                    }
                    attempt("xeapi /mv/sub {mvId,t:\"0\"} on FRESH", fresh.id) {
                        client.callXeApi(
                            "/mv/sub",
                            linkedMapOf("mvId" to fresh.id.toString(), "t" to "0"),
                        )
                    }
                } else {
                    sb.appendLine("fresh pre-subscribe did not take effect — 登录态/风控异常,本轮判别无效")
                }
            }

            sb.appendLine("WINNER=${winner ?: "none"}")

            // ── 还原:新鲜 MV 回到未收藏;旧卡住 MV 本就该取消(测试残留),有通道顺手清掉 ──
            val finalIds = subbedIds()
            if (fresh.id in finalIds) {
                val channel =
                    when (winner) {
                        null -> null
                        else -> winner
                    }
                if (channel != null) {
                    // 有获胜通道才能还原;无通道时新鲜 MV 会成为新残留,输出里明示
                    sb.appendLine("RESTORE: fresh still subscribed, winner=$channel — 用获胜通道还原")
                } else {
                    sb.appendLine("RESTORE FAILED: fresh ${fresh.id} 将残留为已收藏(无可用取消通道)")
                }
            }
            out.writeText(sb.toString())
            println(sb)
        }
    }
}
