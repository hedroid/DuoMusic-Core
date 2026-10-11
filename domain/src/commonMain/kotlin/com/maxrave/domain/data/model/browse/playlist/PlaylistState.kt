package com.maxrave.domain.data.model.browse.playlist

import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint

data class PlaylistState(
    val id: String,
    val title: String,
    val isRadio: Boolean,
    val author: Author,
    val thumbnail: String? = null,
    val description: String? = null,
    val year: String,
    val trackCount: Int = 0,
    val radioEndpoint: YouTubeWatchEndpoint? = null,
    val shuffleEndpoint: YouTubeWatchEndpoint? = null,
    /** 官方标签名(网易源;编辑歌单信息弹窗预选与页头展示,YT 恒空) */
    val tags: List<String> = emptyList(),
)