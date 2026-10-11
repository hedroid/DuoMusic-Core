package com.maxrave.netease

/**
 * eapi 反作弊 token(社区称 checkToken v2,易盾 Watchman)来源。
 *
 * 动态生成=易盾 tool.min.js 在浏览器环境 initWatchman(productNumber) 后按业务 key
 * getToken(参考 kukume/neteasemusic-edgeone-makers _shared/lib/checkToken.ts 的形状,
 * 常量同源)。Android 实现见 composeApp androidMain 的 WatchmanTokenSource
 * (headless WebView,与 NeteaseFingerprint.android.kt 同模式);jvm/ios 无实现。
 *
 * 语义铁律:**任何失败必须返回 null 而非抛异常**——token 是增强项,生成失败时调用方
 * 降级为不带 token 的纯 eapi(ncm-api-rs 出厂形状,2026-10-11 实测可用),绝不阻塞写路径。
 */
interface NeteaseAntiCheatSource {
    suspend fun token(businessKey: String): String?

    companion object {
        /** 官网 core.js 各业务对应的 Watchman business key(kukume constants.ts BIZ_KEYS) */
        const val BUSINESS_KEY_PLAYLIST = "bd5d2f973ef74cd2a61325a412ae54d9"
        const val BUSINESS_KEY_CHECKIN = "8e7511ade5944e82b820212dcb2a7e01"
        const val BUSINESS_KEY_LOGIN = "0b0cdd23ed1144a0b78de049edc09824"
    }
}
