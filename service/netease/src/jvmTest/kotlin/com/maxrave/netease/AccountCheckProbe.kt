package com.maxrave.netease

import java.io.File
import kotlin.test.Test

/** 登录态快查:account/get + 一个 eapi 读,输出到 /tmp(只读,零写操作) */
class AccountCheckProbe {
    @Test
    fun check() {
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
            sb.appendLine("MUSIC_U tail=${cookies["MUSIC_U"]?.takeLast(8)} __csrf=${cookies["__csrf"]?.take(6)}")
            runCatching {
                val r = client.callWeApi("/w/nuser/account/get", mapOf("noCheckToken" to true))
                val profile = r["profile"] as? kotlinx.serialization.json.JsonObject
                sb.appendLine(
                    "account/get code=${r["code"]} profile=${profile?.get("nickname")} uid=${profile?.get("userId")}",
                )
            }.onFailure { sb.appendLine("account ERR ${it.message}") }
            runCatching {
                val r = client.callEApi("/djradio/category/get", emptyMap())
                sb.appendLine("eapi djradio/category code=${r["code"]}")
            }.onFailure { sb.appendLine("eapi ERR ${it.message}") }
        }
        File("/tmp/account_check_out.txt").writeText(sb.toString())
        println(sb)
    }
}
