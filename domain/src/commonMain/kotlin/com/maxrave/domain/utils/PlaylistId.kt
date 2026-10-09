package com.maxrave.domain.utils

import com.maxrave.common.NETEASE_ARTIST_RADIO_PLAYLIST_ID_PREFIX
import com.maxrave.common.NETEASE_FM_PLAYLIST_ID
import com.maxrave.common.NETEASE_RADIO_PLAYLIST_ID_PREFIX

/**
 * Prefixes of YouTube Music playlist ids that are really radios: YouTube keeps generating tracks
 * for them and never stops handing out a continuation token, so their track list has no end.
 *
 * - `RDAMVM` — radio seeded from a video
 * - `RDEM`   — radio seeded from an endpoint
 * - `RDAT`   — artist radio
 * - `RDTM`   — personalised mixes such as Replay Mix, Supermix and Discover Mix (`RDTMAK5uy_…`)
 *
 * Deliberately not the bare `RD` prefix: `RDCLAK5uy_…` is a curated YouTube Music playlist with a
 * finite track list, and calling it a radio would hide its download button.
 */
private val RADIO_PLAYLIST_ID_PREFIXES = listOf("RDAMVM", "RDEM", "RDAT", "RDTM")

private val RADIO_MIX_ID_PREFIXES = listOf("RDAT", "RDTM")

/**
 * Whether this playlist id belongs to a radio. Anything paging through such a playlist must stop
 * on its own terms — waiting for YouTube to run out of continuations never happens.
 */
fun String.isRadioPlaylistId(): Boolean = RADIO_PLAYLIST_ID_PREFIXES.any { startsWith(it) }

/**
 * Whether this id is a queue YouTube put together rather than one the user picked: any radio, and
 * the curated mixes too.
 *
 * Deliberately the bare `RD` prefix, which is wider than [isRadioPlaylistId] on purpose — that one
 * excludes `RDCLAK5uy_…` so a curated mix keeps its download button, but for deciding whether a
 * queue was auto-generated a mix counts exactly like a radio.
 *
 * The `VL` is stripped first because a queue's `playlistId` is the id YouTube plays from and never
 * wears that prefix, while the same radio stored in the library is browsed as `VLRDAMVM…`.
 */
fun String.isRadioQueueId(): Boolean = removePrefix("VL").startsWith("RD")

fun String.isRadioMix(): Boolean = RADIO_MIX_ID_PREFIXES.any { startsWith(it) }

/**
 * 队列身份层面的"电台形态"：这类队列的语义就是无限——YT 电台（四前缀，见
 * [isRadioPlaylistId]）分页永不断尾，网易 FM/单曲电台/艺人电台凭哨兵续批。
 * 它们的无尽不依赖 endlessQueue 开关：core 无尽钩子对其豁免开关门，UI 的
 * 无尽开关对其锁定为开（点了提示无法关闭）。刻意不含 `RDCLAK5uy_…` 策展
 * 歌单（有限列表，开关语义正常）与播客/MV 哨兵（播完即止，另一套门控）。
 */
fun String.isEndlessRadioQueueId(): Boolean =
    this == NETEASE_FM_PLAYLIST_ID ||
        startsWith(NETEASE_ARTIST_RADIO_PLAYLIST_ID_PREFIX) ||
        startsWith(NETEASE_RADIO_PLAYLIST_ID_PREFIX) ||
        removePrefix("VL").isRadioPlaylistId()