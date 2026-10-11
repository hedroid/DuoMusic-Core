package com.maxrave.netease

import java.io.File

/**
 * 写探针门控(2026-10-11 CR P1):/tmp/netease_cookies.json 在本机长期存在,
 * **cookie 文件在 ≠ 执行授权**——跑整个 :netease:jvmTest 时全部 @Test 都会执行,
 * 无门控的写探针会直接改动真实账号(建/删歌单、改描述/标签/封面、收藏、点赞、
 * 评论增删/点赞、云盘上传/匹配/删除、MV 订阅)。
 *
 * 形态沿用 DjInteractionProbe 先例:每个写探针一个独立 marker 文件
 * `/tmp/netease_probe_<name>.enabled`,默认不存在=打印原因并跳过;
 * `touch /tmp/netease_probe_<name>.enabled` 后才真正执行。只读探针不接门控。
 */
internal fun writeProbeEnabled(name: String): Boolean {
    val marker = File("/tmp/netease_probe_$name.enabled")
    if (marker.exists()) return true
    println("skip: write probe '$name' disabled (touch ${marker.path} to authorize real-account writes)")
    return false
}
