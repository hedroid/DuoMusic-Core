package com.maxrave.domain.data.model.browse.album

import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.FeedbackTokens
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val album: Album?,
    val artists: List<Artist>?,
    val duration: String?,
    val durationSeconds: Int?,
    val isAvailable: Boolean,
    val isExplicit: Boolean,
    val likeStatus: String?,
    val thumbnails: List<Thumbnail>?,
    val title: String,
    val videoId: String,
    val videoType: String?,
    val category: String?,
    val feedbackTokens: FeedbackTokens?,
    val resultType: String?,
    val year: String? = null,
    /**
     * Localized view-count text ("432K views"), for the artist Videos shelf. Carried here because
     * that shelf renders [Track]s; it used to ride in [videoType], which is why that column filled
     * up with view counts. Defaulted so existing persisted queue JSON still decodes.
     */
    val views: String? = null,
    /**
     * 网易播客节目 id(节目≠可播歌曲,videoId 是 mainSong.id)。电台详情页的收听位置记忆
     * 直接从队列 Track O(1) 读取——core 续页追加的节目不在详情 VM 的已载列表里,
     * 没有它播到第 2 页以后记忆就断(CR-26)。仅播客构造路径填充,其余默认 null;
     * Defaulted 保证旧持久化队列 JSON 仍可解码。
     */
    val neteaseProgramId: Long? = null,
    /**
     * 网易云盘条目挂载态,仅云盘构造路径填充(null=非云盘条目):true=已匹配曲库
     * (①完整元数据/②仅名字),false=私人文件(③未匹配,或 song 整个缺席走文件名
     * 兜底)。显式标记而非 UI 侧形状推断——队列行的 artists 会被 catalog 重建给
     * 空艺人行回填"Various Artists",私人文件的空艺人形状在重建后丢失,推断必漏;
     * 标记随 Track.copy 存活重建、随序列化存活持久化。UI 侧两个消费点:播放页
     * "从云盘删除"入口(非 null=确属云盘)与"加歌单"门控(false=私人文件置灰)。
     * Defaulted 保证旧持久化队列 JSON(无此字段)仍可解码。
     */
    val neteaseCloudDiskMounted: Boolean? = null,
)