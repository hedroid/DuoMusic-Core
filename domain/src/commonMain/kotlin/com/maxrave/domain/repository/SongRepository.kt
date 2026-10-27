package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.comments.YouTubeComment
import com.maxrave.domain.data.model.comments.YouTubeCommentsPage
import com.maxrave.domain.data.model.download.DownloadProgress
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.YouTubeLikeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

interface SongRepository {
    /** Official YTM video candidates for a song, with its linked rendition first when available. */
    suspend fun findMusicVideos(videoId: String, title: String, artist: String?): List<Track>

    fun getAllSongs(limit: Int): Flow<List<SongEntity>>

    suspend fun setInLibrary(
        videoId: String,
        inLibrary: LocalDateTime,
    )

    fun getSongsByListVideoId(listVideoId: List<String>): Flow<List<SongEntity>>

    fun getDownloadedSongs(): Flow<List<SongEntity>?>

    fun getDownloadingSongs(): Flow<List<SongEntity>?>

    /**
     * 下载管理页数据源:一切有下载活动的行——在途(state 1/2,含手动暂停)、完成(3,含
     * 旧缓存代)、文件丢失(路径在但文件被外部删)。Room 变更自动重发;实时进度/暂停/
     * 失败等 DownloadManager 侧状态由 DownloadHandler.downloads 流补充,两流在 VM 合并。
     */
    fun getDownloadActivitySongs(): Flow<List<SongEntity>>

    fun getPreparingSongs(): Flow<List<SongEntity>>

    fun getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId: List<String>): Flow<List<String>>

    fun getLikedSongs(): Flow<List<SongEntity>>

    /** Liked songs crediting [channelId] anywhere in their artist list; re-emits on every change. */
    fun getLikedSongsByArtist(channelId: String): Flow<List<SongEntity>>

    /**
     * Queue every liked song that is not already offline, and return how many were queued.
     *
     * Used when the user switches auto-download on: liking a song from then on downloads it, and
     * this catches up on everything they liked before. Songs already downloaded are skipped, so
     * turning the setting off and on again costs nothing.
     */
    suspend fun downloadAllLikedSongs(): Int

    /**
     * One Smart downloads pass. Keeps the [count] songs scored highest from the last 30 days of
     * local tracking downloaded, marking each with [SongEntity.SMART_DOWNLOAD], and removes the
     * oldest marked songs once more than [count] are kept. Songs the user downloaded themselves carry
     * no mark and are never touched.
     *
     * @return how many songs were newly queued for download.
     */
    suspend fun runSmartDownloads(count: Int): Int

    /** Smart downloads switched off: removes every marked song, except the ones the user now keeps. */
    suspend fun clearSmartDownloads()

    /**
     * Erase the local listening history and drop every song row nothing refers to any more.
     *
     * A song is kept when its id appears anywhere else at all: in an album, a YouTube playlist, a
     * local playlist, the saved queue, a podcast, or `set_video_id`. Everything left is a row the
     * app accumulated just by seeing the track once, and it takes its lyrics, translations, cached
     * format and extra info with it.
     *
     * @return how many songs were removed.
     */
    suspend fun clearHistoryAndOrphanedSongs(): Int

    fun getCanvasSong(max: Int): Flow<List<SongEntity>>

    fun getSongById(id: String): Flow<SongEntity?>

    fun getSongAsFlow(id: String): Flow<SongEntity?>

    fun insertSong(songEntity: SongEntity): Flow<Long>

    /**
     * Repairs an already-stored row whose metadata was frozen id-less by an older mapping
     * (netease mix-page feeds): real artist ids, a real album name+id. `existing` is the
     * stored row the caller just read; `fresh` carries what the queue knows. Returns the
     * merged row — only missing fields are filled, good data is never overwritten.
     */
    suspend fun backfillSongMetadata(
        fresh: SongEntity,
        existing: SongEntity,
    ): SongEntity

    fun updateThumbnailsSongEntity(
        thumbnail: String,
        videoId: String,
    ): Flow<Int>

    /**
     * Corrects `song.videoType` for a row that predates, or was written before, the parsers started
     * carrying YouTube's real `MUSIC_VIDEO_TYPE_*`. Callers must pass a value they actually know —
     * writing an unknown over a correct one loses information.
     */
    fun updateVideoTypeSongEntity(
        videoType: String,
        videoId: String,
    ): Flow<Int>

    suspend fun updateListenCount(videoId: String)

    suspend fun resetTotalPlayTime(videoId: String)

    suspend fun updateLikeStatus(
        videoId: String,
        likeStatus: Int,
    )

    /**
     * 只写本地红心,不推任何账号。播放页把云端红心合并进显示时回填本地行用——
     * 走 [updateLikeStatus] 会把"采纳云端状态"误当作用户点赞推回云端。
     */
    suspend fun setLikedLocal(
        videoId: String,
        likeStatus: Int,
    )

    fun updateSongInLibrary(
        inLibrary: LocalDateTime,
        videoId: String,
    ): Flow<Int>

    suspend fun updateDurationSeconds(
        durationSeconds: Int,
        videoId: String,
    )

    /**
     * 只改版权态一列(影响行数,0=无此行)。song 行的 isAvailable 是首次插入值的冻结快照
     * (insertSong 为 IGNORE),灰歌"播放实证/探针实证"的回写必须走 UPDATE 才能落库。
     */
    suspend fun updateIsAvailable(
        available: Boolean,
        videoId: String,
    ): Int

    fun getMostPlayedSongs(): Flow<List<SongEntity>>

    suspend fun updateDownloadState(
        videoId: String,
        downloadState: Int,
    )


    /** 文件式下载:写/清音频文件绝对路径(null=清列),返回影响行数(0=无此行) */
    suspend fun updateDownloadedFilePath(
        videoId: String,
        path: String?,
    ): Int

    /** 播客归类回填:仅当 neteaseProgramId 还空时写(播客起播处补历史行) */
    suspend fun updateNeteaseProgramIdIfNull(
        videoId: String,
        programId: Long?,
    ): Int

    /** 文件式下载:写/清视频文件绝对路径(null=清列),返回影响行数(0=无此行) */
    suspend fun updateDownloadedVideoFilePath(
        videoId: String,
        path: String?,
    ): Int

    /** 同名冲突判定:该绝对路径已被哪首歌占用(返回 videoId,null=未占用) */
    suspend fun getSongIdByDownloadedPath(path: String): String?

=======
    suspend fun insertSongInfo(songInfo: SongInfoEntity)

    suspend fun getSongInfoEntity(videoId: String): Flow<SongInfoEntity?>

    suspend fun recoverQueue(temp: List<Track>)

    suspend fun removeQueue()

    suspend fun getSavedQueue(): Flow<List<QueueEntity>?>

    fun getContinueTrack(
        playlistId: String,
        continuation: String,
        fromPlaylist: Boolean = false,
    ): Flow<Pair<ArrayList<Track>?, String?>>

    fun getSongInfo(videoId: String): Flow<SongInfoEntity?>

    suspend fun getLikeStatus(videoId: String): Flow<YouTubeLikeStatus>

    /** Reads the source account state without changing SimpMusic's local favourite. */
    suspend fun getRemoteLikeStatus(videoId: String): Boolean?

    /** Writes only the source account state; local favourite remains independent.
     *  Result carries the failure cause so the UI can tell a netease rate limit (405)
     *  apart from a plain failure — success(false) means the source rejected the write. */
    suspend fun setRemoteLikeStatus(
        videoId: String,
        liked: Boolean,
    ): Result<Boolean>

    suspend fun addToYouTubeLiked(mediaId: String?): Flow<Int>

    suspend fun addToYouTubeDisliked(mediaId: String?): Flow<Int>

    suspend fun removeFromYouTubeLiked(mediaId: String?): Flow<Int>

    fun downloadToFile(
        track: Track,
        path: String,
        videoId: String,
        isVideo: Boolean,
    ): Flow<DownloadProgress>

    fun getRelatedData(videoId: String): Flow<Resource<Pair<List<Track>, String?>>>

    /**
     * A track's comments, from YouTube Music's Comments tab: the first page and its header.
     * Success with null when the track has none (comments turned off, or a live stream with only its chat).
     */
    fun getComments(videoId: String): Flow<Resource<YouTubeCommentsPage?>>

    /** Whatever a comments token loads: the next page, a thread's replies, or more of them. */
    fun getCommentsPage(token: String): Flow<Resource<YouTubeCommentsPage>>

    /** Success with null when YouTube's answer did not carry the new comment back. */
    fun postComment(
        createCommentParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>>

    /** Success with null when YouTube's answer did not carry the new reply back. */
    fun postCommentReply(
        createReplyParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>>

    /** Like, dislike, or undo either: [action] is the token the comment carries for it. */
    fun performCommentAction(action: String): Flow<Resource<Unit>>

    fun getRadioFromEndpoint(endpoint: YouTubeWatchEndpoint): Flow<Resource<Pair<List<Track>, String?>>>
}
