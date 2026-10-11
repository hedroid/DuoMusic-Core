/*
 * NetEase private-API HTTP client, ported from NeriPlayer (GPL-3.0)
 * https://github.com/cwuom/NeriPlayer — core/api/netease/NeteaseClient.kt,
 * reworked for Ktor/KMP with cookie persistence delegated to the host app.
 */
package com.maxrave.netease

import com.maxrave.netease.crypto.XeapiCipher
import com.maxrave.netease.crypto.XeapiCrypto
import com.maxrave.netease.model.NeteaseAccount
import com.maxrave.netease.model.NeteaseQrSession
import com.maxrave.netease.model.NeteaseQrStatus
import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Cookie 持久化交由宿主 App(DataStore),client 只负责:
 *  - 请求时: 持久 cookie(MUSIC_U/__csrf...) + 会话种子(os/appver/NMTID...)合并成 Cookie 头
 *  - 响应时: 解析 Set-Cookie 合并回内存副本,再整体回写 [cookieSaver]
 */
class NeteaseClient(
    private val cookieProvider: suspend () -> Map<String, String> = { emptyMap() },
    private val cookieSaver: suspend (Map<String, String>) -> Unit = { _ -> },
) {
    private val http =
        HttpClient(getEngine()) {
            expectSuccess = false
            install(io.ktor.client.plugins.HttpTimeout) {
                // 30s(2026-09-23 调大,原 15s):灰歌探针(songDetail)超时会返回 null,
                // 动作分流落到 legacy 路径(误弹"网络超时"toast+暂停);取流链还有内部退避,别抢跑
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
            // 上传进度回调(onUpload 扩展)依赖该插件;对未挂监听的请求零开销
            install(io.ktor.client.plugins.BodyProgress)
        }
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG = "NeteaseClient"
    }
    private val deviceId: String = NeteaseCrypto.generateDeviceId()

    private val cookieMutex = Mutex()
    private var sessionCookies: Map<String, String> = emptyMap()

    /** 会话是否经 seedCookies/replaceCookies 显式播种过(登录收尾/切账号);
     *  mergeSetCookies 的落盘门控唯一依据(见其注释) */
    @Volatile
    private var sessionSeeded = false

    // eapi 官方客户端 UA(Enhanced/ncm-api-rs 的 chooseUserAgent('api','iphone') 同款,
    // 2026-10-11 换掉原安卓 Chrome 浏览器 UA:eapi 端点按官方 App 协议头校验,
    // 浏览器形状的 UA 属于残缺请求头——desc/update 405 的头号嫌疑)
    private val eapiUa = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"

    private val desktopUa =
        "Mozilla/5.0 (Macintosh; Intel Mac 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/129.0.0.0 Safari/537.36"

    fun deviceId(): String = deviceId

    /** 用持久化 cookie 重建会话(App 启动时调用一次即可,后续请求自动带上)。 */
    suspend fun seedCookies(persisted: Map<String, String>) =
        cookieMutex.withLock {
            sessionCookies = persisted
            sessionSeeded = true
        }

    suspend fun currentCookies(): Map<String, String> = cookieMutex.withLock { sessionCookies }

    suspend fun replaceCookies(cookies: Map<String, String>) {
        // 与 mergeSetCookies 同序:内存替换和持久化都在锁内,防在途响应的 DataStore 写入
        // 与本路径乱序完成时旧快照覆盖新快照(logout/登录替换与响应并发正是这个场景)
        cookieMutex.withLock {
            sessionCookies = cookies
            sessionSeeded = true
            cookieSaver(cookies)
        }
    }

    suspend fun logout() = replaceCookies(emptyMap())

    fun hasLoginCookie(cookies: Map<String, String>): Boolean = cookies["MUSIC_U"]?.isNotEmpty() == true

    // ------------------------------------------------------------------ envelopes

    suspend fun callWeApi(
        path: String,
        params: Map<String, Any?>,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject {
        val withCsrf =
            if (!params.containsKey("csrf_token") && sessionCsrf().isNotEmpty()) {
                params + ("csrf_token" to sessionCsrf())
            } else {
                params
            }
        return post(
            url = "https://music.163.com/weapi$path",
            form = NeteaseCrypto.weApiEncrypt(withCsrf),
            extraHeaders = extraHeaders,
        )
    }

    /**
     * eapi 动态反作弊 token(易盾 Watchman 反爬,社区称 checkToken v2)。null=不带——
     * 与 ncm-api-rs 出厂形状一致(纯 eapi 无 token);社区静态值三家仓(months 未变)
     * 仅作探针对照,不默认启用;将来做 Android WebView 生成器后由此注入。
     */
    @Volatile
    var antiCheatToken: String? = null

    /**
     * eapi 协议头(Enhanced util/request.js 与 ncm-api-rs request.rs 逐字段一致,
     * 2026-10-11 对照定案):同一份 map 双重复用——序列化进加密体的 `header` 字段,
     * 并 URL 编码后**整体替换** Cookie 头(会话种子/浏览器 cookie 一概不带,
     * MUSIC_U 由本 map 承载鉴权)。
     */
    private fun eapiProtocolHeader(
        csrf: String,
        musicU: String?,
        antiCheatToken: String?,
    ): LinkedHashMap<String, String> {
        val header = LinkedHashMap<String, String>()
        header["osver"] = "Microsoft-Windows-10-Professional-build-19045-64bit"
        header["deviceId"] = deviceId
        header["os"] = "pc"
        header["appver"] = "3.1.17.204416"
        header["versioncode"] = "140"
        header["mobilename"] = ""
        header["buildver"] = (kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000).toString()
        header["resolution"] = "1920x1080"
        header["__csrf"] = csrf
        header["channel"] = "netease"
        header["requestId"] =
            "${kotlin.time.Clock.System.now().toEpochMilliseconds()}_" +
                kotlin.random.Random.nextInt(1000).toString().padStart(4, '0')
        antiCheatToken?.let { header["X-antiCheatToken"] = it }
        musicU?.let { header["MUSIC_U"] = it }
        // 未登录(游客)时社区用 MUSIC_A 匿名 token 顶上,别让匿名 eapi 读(albumDetail 等)丢鉴权
        if (musicU == null) anonymousToken?.let { header["MUSIC_A"] = it }
        return header
    }

    /** 游客匿名 token(可由宿主注入;社区 generateConfig 走 register_anonimous 落盘 MUSIC_A) */
    @Volatile
    var anonymousToken: String? = null

    suspend fun callEApi(
        path: String,
        params: Map<String, Any?>,
        host: String = "https://interface.music.163.com",
        withAntiCheatToken: Boolean = false,
    ): JsonObject {
        val session = cookieProvider() + sessionCookies
        val token = if (withAntiCheatToken) antiCheatToken else null
        val header = eapiProtocolHeader(csrf = sessionCsrf(), musicU = session["MUSIC_U"], antiCheatToken = token)
        val payload =
            params +
                ("header" to header) +
                // e_r=false = 响应明文(社区 encryptResponse:false 同款),true 则服务端回加密体
                ("e_r" to false)
        val body =
            post(
                url = "$host/eapi$path",
                // 摘要里的路径必须是 /api/...(NeriPlayer 传的是完整 encodedPath 再替换),
                // 之前直接传 path 少了 /api 前缀,服务端校验过不了
                form = NeteaseCrypto.eApiEncrypt("/eapi$path", payload),
                profile = HeaderProfile.EAPI,
                cookieOverride = header.entries.joinToString("; ") { (k, v) ->
                    "${k.encodeURLParameter()}=${v.encodeURLParameter()}"
                },
            )
        if (path.contains("login")) {
            // 只记 code/message/cookie 键名,不落敏感值
            com.maxrave.logger.Logger.d(
                TAG,
                "eapi$path code=${(body["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content} " +
                    "msg=${(body["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content} " +
                    "bodyCookie=${(body["cookie"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(40)}",
            )
        }
        return body
    }

    /** 明文 API POST(NeriPlayer CryptoMode.API):不加密的表单直传 music.163.com/api,带浏览器头 */
    suspend fun callApi(
        path: String,
        params: Map<String, Any?>,
    ): JsonObject =
        post(
            url = "https://music.163.com/api$path",
            form = params.mapValues { (_, v) -> v?.toString() ?: "" },
        )

    // ===== xeapi(官方 App 9.5.x 的写操作通道:发评论/回复/删除) =====

    private val xeapiMutex = Mutex()
    private var xeapiKeyState: XeapiCrypto.PublicKeyState? = null
    private var xeapiUa: String? = null

    /**
     * 设备注册取公钥(明文表单,签名=HMAC-SHA256(静态密钥,timestamp+nonce)):
     * /api/gorilla/anti/crawler/security/key/get。公钥状态只做内存缓存——
     * 同 deviceId 冷启动重注册即可,服务端会返回(或沿用)长期设备密钥 sk。
     */
    private suspend fun getOrRegisterXeapiKey(): XeapiCrypto.PublicKeyState {
        xeapiKeyState?.let { return it }
        val deviceId = XeapiCipher.deviceId()
        val nonce = (1..16).map { kotlin.random.Random.nextInt(10) }.joinToString("")
        val timestamp = (kotlin.time.Clock.System.now().toEpochMilliseconds()).toString()
        val response =
            post(
                url = "https://interface.music.163.com/api/gorilla/anti/crawler/security/key/get",
                form =
                    linkedMapOf(
                        "appVersion" to XeapiCrypto.APP_VERSION,
                        "currentKeyVersion" to "",
                        "deviceId" to deviceId,
                        "nonce" to nonce,
                        "os" to "android",
                        "requestType" to "active",
                        "signature" to XeapiCrypto.sign(timestamp, nonce),
                        "t1" to "",
                        "t2" to "",
                        "timestamp" to timestamp,
                        "uid" to "",
                    ),
                extraHeaders =
                    mapOf(
                        "User-Agent" to "NeteaseMusic/${XeapiCrypto.APP_VERSION}.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android ${XeapiCipher.osVersion()}; ${XeapiCipher.deviceModel()})",
                        "Cookie" to "deviceId=$deviceId",
                    ),
            )
        val code = response["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code != 200) {
            error("xeapi key fetch code=$code")
        }
        val encryptedData =
            response["data"]?.jsonObject?.get("encryptedData")?.jsonPrimitive?.content
                ?: error("xeapi key fetch missing encryptedData")
        val state = XeapiCrypto.decryptPublicKeyResponse(encryptedData)
        if (state.publicKey.isEmpty()) {
            error("xeapi key fetch empty publicKey")
        }
        xeapiKeyState = state
        return state
    }

    /**
     * xeapi 写通道:发评论/回复/删除。响应体是 AES-ECB 加密二进制(可能 gzip),
     * 解密后才是明文 JSON;密钥版本过期等失败原样抛给调用方。
     */
    suspend fun callXeApi(
        path: String,
        data: LinkedHashMap<String, String>,
    ): JsonObject =
        xeapiMutex.withLock {
            val state = getOrRegisterXeapiKey()
            val deviceId = XeapiCipher.deviceId()
            val osVer = XeapiCipher.osVersion()
            val appVer = XeapiCrypto.APP_VERSION
            val buildVer = (kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000).toString()
            if (xeapiUa == null) {
                xeapiUa =
                    "NeteaseMusic/$appVer.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android $osVer; ${XeapiCipher.deviceModel()})"
            }
            val form = XeapiCrypto.assembleRequest("/api$path", data, state, deviceId, osVer)
            val userCookie = buildCookieHeader()
            val musicU = Regex("MUSIC_U=([^;]+)").find(userCookie)?.groupValues?.get(1)
            val cookie =
                "os=android; osver=$osVer; appver=$appVer; deviceId=$deviceId; sDeviceId=$deviceId; buildver=$buildVer" +
                    if (userCookie.isNotEmpty()) "; $userCookie" else ""
            val response =
                http.post("https://interface3.music.163.com/xeapi$path") {
                    header(HttpHeaders.Cookie, cookie)
                    header(HttpHeaders.UserAgent, xeapiUa!!)
                    header("X-Client-Enc-State", "ENCRYPTED")
                    header("x-aeapi", "true")
                    header("x-deviceid", deviceId)
                    header("x-sdeviceid", deviceId)
                    header("x-os", "android")
                    header("x-osver", osVer)
                    header("x-appver", appVer)
                    header("x-buildver", buildVer)
                    musicU?.let { header("x-music-u", it) }
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("B", form.b)
                                append("S", form.s)
                                append("R", form.r)
                            },
                        ),
                    )
                }
            val text = XeapiCrypto.decryptResponseBody(response.readBytes())
            json.parseToJsonElement(text).jsonObject
        }

    /** 明文 API GET,返回 JSON */
    suspend fun callApiGet(
        path: String,
        query: Map<String, Any?>,
    ): JsonObject =
        json.parseToJsonElement(
            getText(path, query.mapValues { (_, v) -> v?.toString() ?: "" }),
        ).jsonObject

    /**
     * 展开 163cn.tv 分享短链:Ktor 默认跟随重定向,拿最终落地 URL。
     * 落地后用 [NeteaseLinkParser.recognizeExpanded] 再识别。
     */
    suspend fun expandShortLink(shortUrl: String): Result<String> =
        runCatching {
            val response =
                http.get(shortUrl) {
                    header(HttpHeaders.UserAgent, desktopUa)
                }
            response.request.url.toString()
        }

    /** 明文 GET,返回原始响应体(相似歌单要从 playlist 页 HTML 里抓) */
    suspend fun getText(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): String {
        val response =
            http.get("https://music.163.com$path") {
                header(HttpHeaders.Cookie, buildCookieHeader())
                header("Referer", "https://music.163.com")
                header(HttpHeaders.UserAgent, desktopUa)
                query.forEach { (k, v) -> parameter(k, v) }
            }
        return response.bodyAsText()
    }

    // ===== 云盘上传的 nos 对象存储通道(不走 music.163.com) =====

    /**
     * nos 上传节点发现(GET wanproxy.127.net/lbs):返回 upload[0] 直传节点。
     * 每次上传前现查——节点会轮换,缓存没意义。
     */
    suspend fun nosUploadNode(bucket: String): Result<String> =
        runCatching {
            val response = http.get("https://wanproxy.127.net/lbs?version=1.0&bucketname=$bucket")
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            (body["upload"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: error("lbs response has no upload node: ${response.bodyAsText().take(120)}")
        }

    /**
     * nos 对象直传(POST {node}/{bucket}/{objectKey}?offset=0&complete=true&version=1.0):
     * 单请求 10 分钟超时覆盖(全局 30s 对几十 MB 上传必砍);body 流式(平台层提供
     * ByteReadChannel,避免整文件进内存——ktro get() 的同款教训)。
     */
    suspend fun nosPutObject(
        uploadNode: String,
        bucket: String,
        objectKey: String,
        nosToken: String,
        contentMd5: String,
        contentType: String,
        contentLength: Long,
        body: io.ktor.utils.io.ByteReadChannel,
        /** 上传字节进度(实发字节,总长);频繁回调,调用方自行节流 */
        onUpload: (suspend (bytesSent: Long, total: Long) -> Unit)? = null,
    ): Result<Unit> =
        runCatching {
            val encodedKey = objectKey.replace("/", "%2F")
            val response =
                http.post("$uploadNode/$bucket/$encodedKey?offset=0&complete=true&version=1.0") {
                    timeout {
                        requestTimeoutMillis = 600_000
                    }
                    header("x-nos-token", nosToken)
                    header("Content-MD5", contentMd5)
                    header(HttpHeaders.ContentType, contentType)
                    header(HttpHeaders.ContentLength, contentLength.toString())
                    if (onUpload != null) {
                        onUpload { sent, total ->
                            onUpload(sent, total ?: contentLength)
                        }
                    }
                    setBody(body)
                }
            val text = response.bodyAsText()
            com.maxrave.logger.Logger.w(
                "CloudUpload",
                "nos put ${response.status.value} expect=${contentLength}B resp=${text.take(160)}",
            )
            check(response.status.value in 200..299) {
                "nos put ${response.status.value}: ${text.take(200)}"
            }
        }

    /**
     * nos 小对象直传(歌单封面等 KB 级 JPEG;ncm-api-rs avatar_upload 同款固定节点,
     * 不带 Content-MD5、不走 lbs 发现)。大文件走 [nosPutObject]。
     */
    suspend fun nosPutSmallObject(
        bucket: String,
        objectKey: String,
        nosToken: String,
        contentType: String,
        bytes: ByteArray,
    ): Result<Unit> =
        runCatching {
            val encodedKey = objectKey.replace("/", "%2F")
            val response =
                http.post("https://nosup-hz1.127.net/$bucket/$encodedKey?offset=0&complete=true&version=1.0") {
                    header("x-nos-token", nosToken)
                    header(HttpHeaders.ContentType, contentType)
                    setBody(bytes)
                }
            val text = response.bodyAsText()
            com.maxrave.logger.Logger.w(
                "CoverUpload",
                "nos put ${response.status.value} ${bytes.size}B resp=${text.take(160)}",
            )
            check(response.status.value in 200..299) {
                "nos put ${response.status.value}: ${text.take(200)}"
            }
        }

    /** NeriPlayer buildRequest 的两种头模式:eapi 模拟官方客户端,不吃浏览器头 */
    internal enum class HeaderProfile {
        WEAPI,
        EAPI,
    }

    internal suspend fun post(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap(),
        profile: HeaderProfile = HeaderProfile.WEAPI,
        /** eapi 专用:协议头 map 整体替换 Cookie(社区同款);null=常规会话 cookie */
        cookieOverride: String? = null,
    ): JsonObject {
        val response =
            http.post(url) {
                header(HttpHeaders.Cookie, cookieOverride ?: buildCookieHeader())
                if (profile == HeaderProfile.WEAPI) {
                    header(HttpHeaders.Origin, "https://music.163.com")
                    header("Referer", "https://music.163.com")
                    header(HttpHeaders.UserAgent, desktopUa)
                    header("x-os", "web")
                } else {
                    // eapi 不带 Referer/Origin(社区两家实现一致),UA=官方客户端串
                    header(HttpHeaders.UserAgent, eapiUa)
                }
                extraHeaders.forEach { (k, v) -> header(k, v) }
                setBody(
                    FormDataContent(
                        Parameters.build {
                            form.forEach { (k, v) -> append(k, v) }
                        },
                    ),
                )
            }
        // 合并 Set-Cookie(登录类接口靠它拿 MUSIC_U)
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        com.maxrave.logger.Logger.d(
            TAG,
            "POST $url -> ${response.status.value}, setCookies=${setCookies.map { it.substringBefore('=') }}",
        )
        if (setCookies.isNotEmpty()) {
            mergeSetCookies(setCookies)
        }
        val text = response.bodyAsText()
        if (!response.status.value.let { it in 200..299 }) {
            // 端点级频控/账号风控窗口的实测形状是 HTTP 405(记忆:重试会续期窗口)。
            // 抛专属类型让 UI 层能给出"操作过于频繁"而非笼统"操作失败";
            // 其余非 2xx 维持 ktor 异常原样。
            if (response.status.value == 405) throw NeteaseRateLimitException(url)
            throw ClientRequestException(response, "HTTP ${response.status.value}: ${text.take(200)}")
        }
        if (url.contains("login") || url.contains("captcha")) {
            val bodyObj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            com.maxrave.logger.Logger.d(
                TAG,
                "login-body code=${(bodyObj?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content} " +
                    "msg=${(bodyObj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content} " +
                    "hasCookieField=${bodyObj?.containsKey("cookie") == true}",
            )
        }
        val bodyObj = json.parseToJsonElement(text).jsonObject
        // 写操作被服务端拒绝(HTTP 200 + 业务 code!=200)时留痕:真机建单/关注/收藏失败
        // 的唯一线索来源(release 包 kermit 默认进 logcat,D 级 POST 行已验证可见)
        val bodyCode = (bodyObj["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (bodyCode != null && bodyCode != "200") {
            println(
                "QQQ netease rejected: $url code=$bodyCode " +
                    "msg=${(bodyObj["message"] ?: bodyObj["msg"])?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }}",
            )
        }
        return bodyObj
    }

    private suspend fun buildCookieHeader(): String =
        (cookieProvider() + sessionCookies + sessionSeeds())
            .entries
            .joinToString("; ") { "${it.key}=${it.value}" }

    private fun sessionSeeds(): Map<String, String> =
        mapOf(
            "os" to "pc",
            "appver" to "8.10.35",
            "osver" to "Microsoft Windows 10",
            "deviceId" to deviceId,
            "NMTID" to (sessionCookies["NMTID"] ?: deviceId.replace("-", "").take(24)),
        )

    private suspend fun sessionCsrf(): String = (cookieProvider() + sessionCookies)["__csrf"] ?: ""

    private suspend fun mergeSetCookies(setCookies: List<String>) {
        cookieMutex.withLock {
            // 持久化门控(2026-10-11 登录被抹事故,两轮):seedCookies 只在登录/切账号时调用,
            // 正常运行时 sessionCookies 恒空——把"空会话+Set-Cookie"整笔落盘会用 {"NMTID":...}
            // 残片覆盖 DataStore 里的完整 cookie(MUSIC_U 被抹,app 登出;触发器=任意带
            // Set-Cookie 的响应,如 eapi 写被拒/成功后下发新 NMTID)。
            // **门控必须看"显式播种"标志而非 sessionCookies.isNotEmpty()**:第一次 Set-Cookie
            // 就会把内存会话变非空,第二次即过门——门就形同虚设(二轮实测又炸一次)。
            // 仅播种过的会话(=登录收尾/切账号,刻意只要新 cookie 集)才允许落盘;
            // 未播种会话的 Set-Cookie 只进内存,重启即散,DataStore 原值不动。
            val m = sessionCookies.toMutableMap()
            setCookies.forEach { raw ->
                val nameValue = raw.substringBefore(';').trim()
                val name = nameValue.substringBefore('=').trim()
                val value = nameValue.substringAfter('=', "").trim()
                if (name.isNotEmpty()) m[name] = value
            }
            sessionCookies = m
            if (sessionSeeded) {
                // 持久化必须也在锁内:两个并发响应的合并顺序是串行的,锁外写 DataStore
                // 乱序完成时旧快照会覆盖新快照。
                cookieSaver(m)
            }
        }
    }

    // ------------------------------------------------------------------ login flows

    /**
     * NeriPlayer ensureWeapiSession:登录前先 GET 一次首页,收割服务端签发的 __csrf/NMTID
     * 种子 cookie —— 风控(-462)会拒绝带着"自造 cookie"直接提交的登录。
     */
    suspend fun ensureWeapiSession() {
        if (sessionCookies["__csrf"]?.isNotEmpty() == true || cookieProvider()["__csrf"]?.isNotEmpty() == true) return
        runCatching {
            val response =
                http.get("https://music.163.com/") {
                    header(HttpHeaders.UserAgent, desktopUa)
                }
            response.headers.getAll(HttpHeaders.SetCookie).orEmpty().let {
                if (it.isNotEmpty()) mergeSetCookies(it)
            }
            com.maxrave.logger.Logger.d(
                TAG,
                "weapi warmup -> ${response.status.value}, sessionCookies=${sessionCookies.keys}",
            )
        }
    }

    /** 创建扫码登录会话 */
    suspend fun createQrSession(): Result<NeteaseQrSession> =
        runCatching {
            val body =
                callWeApi(
                    "/login/qrcode/unikey",
                    mapOf("type" to 1, "noCheckToken" to true),
                )
            val code = body["code"].primitiveInt() ?: -1
            val key = body["unikey"].primitiveContent().orEmpty()
            check(code == 200 && key.isNotEmpty()) { "QR unikey failed, code=$code" }
            NeteaseQrSession(
                key = key,
                qrContent = "https://music.163.com/login?codekey=$key",
            )
        }

    /**
     * 轮询扫码状态。803 成功时 Set-Cookie 已被 [post] 合并进会话并回写持久层。
     * TODO(NETEASE_RISK_CONTROL): NeriPlayer 的易盾 ydDeviceToken(WebView 设备指纹)+ 登录链 ID
     * 能提高风控通过率,首版不带;若扫码 803 后仍被判定异常登录,在 Android expect/actual 里补
     * headless WebView 方案。
     */
    suspend fun checkQrLogin(key: String): Result<NeteaseQrStatus> =
        runCatching {
            val body =
                callWeApi(
                    "/login/qrcode/client/login",
                    mapOf("type" to 1, "noCheckToken" to true, "key" to key),
                    extraHeaders = mapOf("x-loginmethod" to "QrCode"),
                )
            when (body["code"].primitiveInt() ?: -1) {
                801 -> NeteaseQrStatus.WaitingForScan
                802 -> NeteaseQrStatus.ScannedWaitingForConfirm
                803 -> {
                    // 803 的 cookie 同时出现在 Set-Cookie 头和 body.cookie;引擎(尤其 HTTP/2)可能
                    // 不透传全部 Set-Cookie,以 body 兜底合并,避免"扫码成功但拿不到 MUSIC_U"
                    val bodyCookie = (body["cookie"] as? JsonPrimitive)?.content.orEmpty()
                    val fromBody =
                        bodyCookie.split(";")
                            .mapNotNull { part ->
                                val name = part.substringBefore('=', "").trim()
                                val value = part.substringAfter('=', "").trim()
                                if (name.isEmpty()) null else name to value
                            }.toMap()
                    val merged = currentCookies() + fromBody
                    com.maxrave.logger.Logger.d(
                        TAG,
                        "QR 803 confirmed, headerCookies=${currentCookies().keys} bodyCookies=${fromBody.keys}",
                    )
                    NeteaseQrStatus.Confirmed(merged)
                }
                else -> NeteaseQrStatus.Expired // 800 及未知码一律按过期重新生成
            }
        }

    /** 手机号+密码登录(密码传明文,内部 md5) */
    suspend fun loginByPhone(
        phone: String,
        password: String,
        countryCode: String = "86",
    ): Result<JsonObject> =
        runCatching {
            callEApi(
                "/w/login/cellphone",
                mapOf(
                    "phone" to phone,
                    "countrycode" to countryCode,
                    "remember" to true.toString(),
                    "password" to NeteaseCrypto.md5Hex(password),
                    "type" to "1",
                ),
            )
        }

    /** 发送短信验证码 */
    suspend fun sendCaptcha(
        phone: String,
        countryCode: String = "86",
    ): Result<JsonObject> =
        runCatching {
            callWeApi(
                "/sms/captcha/sent",
                mapOf("cellphone" to phone, "ctcode" to countryCode),
            )
        }

    /** 手机号+验证码登录(weapi,与网页版同通道;响应 Set-Cookie 与 body.cookie 都会被收下) */
    suspend fun loginByCaptcha(
        phone: String,
        captcha: String,
        countryCode: String = "86",
    ): Result<JsonObject> =
        runCatching {
            ensureWeapiSession()
            callWeApi(
                "/w/login/cellphone",
                mapOf(
                    "phone" to phone,
                    "countrycode" to countryCode,
                    "remember" to true.toString(),
                    "type" to "1",
                    "captcha" to captcha,
                ),
            )
        }

    /** 校验当前会话并取账号摘要;cookie 无效时返回 null */
    suspend fun getAccountStatus(): Result<NeteaseAccount?> =
        runCatching {
            val body = callWeApi("/w/nuser/account/get", mapOf("noCheckToken" to true))
            // 业务码分流(2026-09-30 三轮 CR):301=明确未登录;200 但无 profile=游客态
            // (实测形状)——两者都是不可重试的"已登出";其余非 200(临时风控/服务异常)
            // 按可重试失败抛。曾一律折叠成 null(profile 缺席即 null)→上层把瞬时风控也判成
            // 登出:不重试+清缓存,账号摘要一抖就误杀。
            val code = (body["code"] as? JsonPrimitive)?.content
            if (code == "301") throw NeteaseNotLoggedInException("account/get code=301 (not logged in)")
            check(code == null || code == "200") { "account/get rejected: code=$code" }
            val profile =
                body["profile"] as? JsonObject
                    ?: throw NeteaseNotLoggedInException("account/get: code=$code but no profile (guest)")
            val account = body["account"] as? JsonObject
            NeteaseAccount(
                userId = profile["userId"].nLong() ?: 0L,
                nickname = profile["nickname"].primitiveContent() ?: "NetEase user",
                avatarUrl = profile["avatarUrl"].primitiveContent(),
                vipType = account?.get("vipType").nInt() ?: 0,
            )
        }

    /** 把浏览器导出的原始 cookie 串("k=v; k2=v2" 或纯 MUSIC_U 值)规范化 */
    fun parseRawCookies(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (!trimmed.contains('=') && trimmed.isNotEmpty()) {
            return mapOf("MUSIC_U" to trimmed)
        }
        return trimmed
            .split(';')
            .mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty()) null else name to value
            }.toMap()
    }
}

private fun JsonElement?.primitiveContent(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonElement?.primitiveInt(): Int? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

/** 网易写端点 HTTP 405(端点级频控/账号风控窗口):UI 层据此给"操作过于频繁"专属提示,
 *  与笼统失败区分。窗口内反复重试会续期,提示用户静置而不是再戳。 */
class NeteaseRateLimitException(
    val url: String,
) : Exception("netease rate limited: $url")

/** 网易已登出/账号失效(account/get 无 profile 或 userId=0):与网络抖动、风控等可重试
 *  失败区分——重试链看到它应立即收手并清空缓存列表(留着旧账号的歌单只会加错地方)。 */
class NeteaseNotLoggedInException(
    message: String,
) : Exception(message)
