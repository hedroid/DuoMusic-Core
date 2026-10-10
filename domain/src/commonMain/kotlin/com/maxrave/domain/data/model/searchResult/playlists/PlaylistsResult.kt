package com.maxrave.domain.data.model.searchResult.playlists

import com.maxrave.common.NETEASE_RADIO_SEARCH_RESULT_TYPE
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.utils.isRadioPlaylistId

data class PlaylistsResult(
    val author: String,
    val browseId: String,
    val category: String,
    val itemCount: String,
    val resultType: String,
    val thumbnails: List<Thumbnail>,
    val title: String,
) : PlaylistType,
    SearchResultType {
    // 网易电台搜索行(NETEASE_RADIO_SEARCH_RESULT_TYPE)与 YT 播客同行共享 PODCAST
    // 类型分支(行标签"播客");点击路由不在这——SearchScreen 按 resultType 分流各自详情页
    override fun playlistType(): PlaylistType.Type =
        if (resultType == "Podcast" || resultType == NETEASE_RADIO_SEARCH_RESULT_TYPE) {
            PlaylistType.Type.PODCAST
        } else if (browseId.isRadioPlaylistId()) {
            PlaylistType.Type.RADIO
        } else {
            PlaylistType.Type.YOUTUBE_PLAYLIST
        }

    override fun objectType(): SearchResultType.Type =
        if (resultType == "Podcast" || resultType == NETEASE_RADIO_SEARCH_RESULT_TYPE) {
            SearchResultType.Type.PODCAST
        } else {
            SearchResultType.Type.PLAYLIST
        }
}