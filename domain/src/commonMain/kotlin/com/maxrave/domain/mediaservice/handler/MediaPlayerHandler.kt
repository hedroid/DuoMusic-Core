package com.maxrave.domain.mediaservice.handler

import com.maxrave.common.NETEASE_ARTIST_RADIO_PLAYLIST_ID_PREFIX
import com.maxrave.common.NETEASE_FM_PLAYLIST_ID
import com.maxrave.common.NETEASE_HEART_RADIO_QUEUE_NAME
import com.maxrave.common.NETEASE_PODCAST_QUEUE_PREFIX
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.utils.isEndlessRadioQueueId
import kotlinx.coroutines.flow.StateFlow

/**
 * Handler interface for managing media playback service operations
 */
interface MediaPlayerHandler {
    // Core player interface
    val player: MediaPlayerInterface

    // State flows
    val simpleMediaState: StateFlow<SimpleMediaState>
    val nowPlaying: StateFlow<GenericMediaItem?>
    val queueData: StateFlow<QueueData?>
    val controlState: StateFlow<ControlState>
    val nowPlayingState: StateFlow<NowPlayingTrackState>
    val sleepTimerState: StateFlow<SleepTimerState>
    val skipSegments: StateFlow<List<SponsorSkipSegments>?>
    val format: StateFlow<NewFormatEntity?>
    val currentSongIndex: StateFlow<Int>
    val castState: StateFlow<GenericCastState>

    // Listeners
    var onUpdateNotification: (List<GenericCommandButton>) -> Unit
    var pushPlayerError: (PlayerError) -> Unit
    var showToast: (ToastType) -> Unit

    /**
     * Latest synced lyric lines for the current track, pushed by the UI layer whenever they load
     * or clear. While the "notification lyrics" setting is on, the handler tracks the active line
     * from the playback position and feeds it into the current media item's artist slot, which is
     * the second line every renderer reads (media card, lock screen, OEM capsules such as
     * Samsung's Now Bar). Null restores the real artist.
     */
    fun updateLyricLines(lines: List<Line>?)

    // Playback control
    suspend fun onPlayerEvent(playerEvent: PlayerEvent)

    fun toggleRadio()

    fun toggleLike()

    fun like(liked: Boolean)

    fun resetSongAndQueue()

    // Sleep timer
    fun sleepStart(minutes: Int)

    fun sleepStop()

    // Media management
    fun removeMediaItem(position: Int)

    fun addMediaItem(
        mediaItem: GenericMediaItem,
        playWhenReady: Boolean = true,
    )

    fun clearMediaItems()

    fun addMediaItemList(mediaItemList: List<GenericMediaItem>)

    fun playMediaItemInMediaSource(index: Int)

    fun currentSongIndex(): Int

    // Index of the currently playing item in the current queue data (shuffled or not)
    fun currentOrderIndex(): Int

    suspend fun swap(
        from: Int,
        to: Int,
    )

    fun resetCrossfade()

    // Queue management
    fun shufflePlaylist(randomTrackIndex: Int = 0)

    fun loadMore()

    /** 关无尽开关:裁掉电台追加的歌、恢复原队列身份(对齐 YTM autoplay)。无快照时无操作。 */
    fun restoreOriginalQueueAfterEndless() {}

    fun getRelated(videoId: String)

    fun setQueueData(queueData: QueueData.Data)

    fun getCurrentMediaItem(): GenericMediaItem?

    // Track operations
    suspend fun moveItemUp(position: Int)

    suspend fun moveItemDown(position: Int)

    /** Moves the queue track at [position] to play right after the current one. */
    suspend fun moveItemToPlayNext(position: Int)

    fun addFirstMediaItemToIndex(
        mediaItem: GenericMediaItem?,
        index: Int,
    )

    fun reset()

    suspend fun load(
        downloaded: Int = 0,
        index: Int? = null,
    )

    /** Returns true when at least one track was actually appended; false means every
     * track was already in the queue and nothing changed (isAddToQueue dedup). */
    suspend fun loadMoreCatalog(
        listTrack: ArrayList<Track>,
        isAddToQueue: Boolean = false,
    ): Boolean

    suspend fun updateCatalog(
        downloaded: Int = 0,
        index: Int? = null,
    ): Boolean

    fun addQueueToPlayer()

    fun loadPlaylistOrAlbum(index: Int? = null)

    fun setCurrentSongIndex(index: Int)

    suspend fun playNext(track: Track)

    suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int? = null,
    )

    // Playback information
    fun getPlayerDuration(): Long

    fun getProgress(): Long

    fun startProgressUpdate()

    fun stopProgressUpdate()

    fun startBufferedUpdate()

    fun stopBufferedUpdate()

    // Settings
    fun mayBeNormalizeVolume()

    fun mayBeSaveRecentSong(runBlocking: Boolean = false)

    fun mayBeSavePlaybackState(runBlocking: Boolean = false)

    fun mayBeRestoreQueue()

    /**
     * Restores the persisted queue and starts playing it.
     *
     * This is the background playback resumption path: a `play` command that arrives with an
     * empty player and no UI attached (a Bluetooth or headset button, a Samsung Routine, the
     * system resumption chip). Unlike [mayBeRestoreQueue] it must end with the player actually
     * playing, and it must get there quickly - see the implementation for why.
     *
     * Returns false when there is nothing to resume.
     */
    suspend fun restoreQueueAndPlay(): Boolean

    // Lifecycle
    fun shouldReleaseOnTaskRemoved(): Boolean

    fun release()
}

// State classes and enums - these would need to be defined in domain layer
sealed class RepeatState {
    data object None : RepeatState()

    data object All : RepeatState()

    data object One : RepeatState()
}

sealed class PlayerEvent {
    data object PlayPause : PlayerEvent()

    data object Backward : PlayerEvent()

    data object Forward : PlayerEvent()

    data object Stop : PlayerEvent()

    data object Next : PlayerEvent()

    data object Previous : PlayerEvent()

    /**
     * Always advances to the previous media item, bypassing the 3-second
     * "seek to start of current track" behaviour of [Previous]. Used by UI
     * affordances like the artwork pager swipe.
     */
    data object SkipToPrevious : PlayerEvent()

    data object Shuffle : PlayerEvent()

    data object Repeat : PlayerEvent()

    data class UpdateProgress(
        val newProgress: Float,
    ) : PlayerEvent()

    data object ToggleLike : PlayerEvent()

    data class UpdateVolume(
        val newVolume: Float,
    ) : PlayerEvent()
}

sealed class SimpleMediaState {
    data object Initial : SimpleMediaState()

    /**
     * Idle after an error (or an unavailable-song action left the player parked):
     * unlike [Initial] this must NOT show a loading spinner — the toast already
     * told the user why playback stopped, and the UI should freeze the timeline
     * as-is until they act.
     */
    data object Stopped : SimpleMediaState()

    data object Ended : SimpleMediaState()

    data class Ready(
        val duration: Long,
    ) : SimpleMediaState()

    data class Loading(
        val bufferedPercentage: Int,
        val duration: Long,
    ) : SimpleMediaState()

    data class Progress(
        val progress: Long,
    ) : SimpleMediaState()

    data class Buffering(
        val position: Long,
    ) : SimpleMediaState()
}

data class ControlState(
    val isPlaying: Boolean,
    val isShuffle: Boolean,
    val repeatState: RepeatState,
    val isLiked: Boolean,
    val isNextAvailable: Boolean,
    val isPreviousAvailable: Boolean,
    val isCrossfading: Boolean,
    val volume: Float, // 0f..1f
)

data class NowPlayingTrackState(
    val mediaItem: GenericMediaItem,
    val track: Track?,
    val songEntity: SongEntity?,
) {
    fun isNotEmpty(): Boolean = this != initial()

    companion object {
        fun initial(): NowPlayingTrackState =
            NowPlayingTrackState(
                mediaItem = GenericMediaItem.EMPTY,
                track = null,
                songEntity = null,
            )
    }
}

data class SleepTimerState(
    val isDone: Boolean,
    val timeRemaining: Int,
)

/** 原生电台锁定提示的分流种类，详见 [QueueData.Data.endlessRadioLockKind]。 */
enum class EndlessRadioLockKind { FM, HEART_RADIO, ARTIST_RADIO, RADIO }

data class QueueData(
    val queueState: StateSource = StateSource.STATE_CREATED,
    val data: Data = Data(),
) {
    data class Data(
        val listTracks: List<Track> = arrayListOf(),
        val firstPlayedTrack: Track? = null,
        val playlistId: String? = null,
        val playlistName: String? = null,
        val playlistType: PlaylistType? = null,
        val continuation: String? = null,
        // 普通队列(歌单/专辑等)被无尽钩子转成电台后置 true：这种队列的电台身份是
        // 开关赋予的，UI 无尽开关保持可关(关=裁回原队列)；原生电台(见下)则锁定为开。
        // 不持久化——重启后 restore 快照本就丢失，转换态随之失效，行为与显示一致。
        val radioConvertedFromQueue: Boolean = false,
        /**
         * 云盘队列的"标记纪元"：setQueueData 时按"曲目里是否任一行带
         * neteaseCloudDiskMounted 标记"定型，此后续批追加/移除歌曲都不翻动
         * （Data.copy 透传）。true=标记纪元队列——无标记的行是后来插入的普通歌；
         * false=升级前保存的旧快照(标记集体缺席)——无标记的行按云盘歌对待。
         * 不能在 UI 侧按当前曲目现算 any{标记}:旧快照经分页续批追加带标记的
         * 云盘行后 any 翻 true，旧云盘行会被误判成插入的普通歌(私人文件门控
         * 整组失效)；反向同理，云盘歌被移光只剩普通歌时 any 翻 false 会把普通
         * 歌误判成云盘歌。纪元只描述"这队队列以什么形态建立"，与当下内容无关。
         * 不持久化——恢复时 setQueueData 按恢复行重新定型(旧快照全无标记→
         * false，语义自洽)。
         */
        val cloudDiskFlagEra: Boolean = false,
        /**
         * 本队列的云盘标记来自"恢复旧快照时的回填"而非云盘页装载:回填只能证明
         * "这是云盘队列的保存"(队列级出处),证明不了逐行归属——升级前的保存可能
         * 混着"添加到队列"插入的普通歌,回填会给它们也补上云盘标记。菜单门控吃
         * 回填值无害(误伤方向=保守);但**不可逆的"从云盘中删除"只认页面出处**,
         * 播放页删除入口见本标记一律抑制。随身份键持久化(cloudDiskRestore-
         * Backfilled),防回填标记经一次保存落盘后、下轮恢复读不回出处;从云盘页
         * 重新起播(setQueueData 装载页面曲目)即清零,删除入口随之恢复。
         */
        val cloudDiskRestoreBackfilled: Boolean = false,
        /**
         * 旧快照(纪元 false)恢复时的**原始行注册表**:恢复那一刻队列里全部行的
         * videoId。旧快照的 null 行没有可靠逐行归属,但"恢复后新插入"与"恢复时
         * 就在"可以精确区分——UI 的 null 行判定:纪元队列=插入普通歌;非纪元且
         * 在注册表=原始行按云盘歌对待(菜单门控/形状启发式保守判私人);非纪元且
         * 不在注册表=恢复后插入的普通歌(不吃任何云盘门控)。**随身份键持久化**:
         * 恢复优先吃持久值,上一轮会话插入并保存的普通歌永远在表外(跨重启不再被
         * 重新纳入"原始行");只有首次恢复(持久键缺席)按"整队=原始行"建档。云盘页
         * 重载后的保存写空(纪元队列不消费注册表)。
         */
        val cloudDiskRestoredOriginalIds: Set<String> = emptySet(),
    ) {
        /**
         * 播客队列唯一判定:只认显式前缀。队列身份三键持久化(fd62b5a)后恢复路径
         * 同样带前缀;更早的旧快照回退 SAVED_QUEUE 占位会被判否——那是一次性
         * 升级过渡窗口,宁可漏判也不再从曲目形状猜:旧"全数字+单一 album.id"
         * 指纹与普通网易专辑整队完全同形,会把歌曲功能(红心/歌词/菜单/无尽开关)
         * 整组误伤(CR-22)。UI 六处门控必须统一走本属性,禁止再地摊指纹。
         */
        val isNeteasePodcastQueue: Boolean
            get() = playlistId?.startsWith(NETEASE_PODCAST_QUEUE_PREFIX) == true

        /**
         * 原生电台形态(无尽不依赖开关)：YT 电台/FM/网易电台哨兵，且不是普通队列
         * 转换来的。UI 的无尽开关对它锁定为开(点击提示无法关闭)；core 的无尽
         * 钩子(转电台/换种子续链)对它豁免 endlessQueue 开关门。
         */
        val isEndlessRadioQueue: Boolean
            get() = endlessRadioLockKind != null

        /**
         * 锁定 toast 的文案分流(用户定案句式"XX不能关闭无尽队列")：私人FM/红心电台/
         * 艺人电台各自点名，其余电台形态(单曲电台/混音等)统一 [EndlessRadioLockKind.RADIO]。
         * 红心电台与私人FM同挂 FM 哨兵，靠队列名区分；艺人电台=网易艺人哨兵或 YT 的
         * RDAT 前缀(PlaylistId.kt 口径)。非原生电台(含普通队列转来的)返回 null=不锁定。
         */
        val endlessRadioLockKind: EndlessRadioLockKind?
            get() {
                if (radioConvertedFromQueue) return null
                val id = playlistId ?: return null
                return when {
                    id == NETEASE_FM_PLAYLIST_ID ->
                        if (playlistName == NETEASE_HEART_RADIO_QUEUE_NAME) {
                            EndlessRadioLockKind.HEART_RADIO
                        } else {
                            EndlessRadioLockKind.FM
                        }
                    id.startsWith(NETEASE_ARTIST_RADIO_PLAYLIST_ID_PREFIX) ||
                        id.startsWith("RDAT") -> EndlessRadioLockKind.ARTIST_RADIO
                    id.isEndlessRadioQueueId() -> EndlessRadioLockKind.RADIO
                    else -> null
                }
            }
    }

    enum class StateSource {
        STATE_CREATED,
        STATE_INITIALIZING,
        STATE_INITIALIZED,
        STATE_ERROR,
    }

    fun addTrackList(tracks: Collection<Track>): QueueData {
        val temp = this.data.listTracks.toMutableList()
        temp.addAll(tracks)
        return this.copy(
            data =
                this.data.copy(
                    listTracks = temp,
                ),
        )
    }

    fun addToIndex(
        track: Track,
        index: Int,
    ): QueueData {
        val temp = this.data.listTracks.toMutableList()
        temp.add(index, track)
        return this.copy(
            data =
                this.data.copy(
                    listTracks = temp,
                ),
        )
    }

    fun removeFirstTrackForPlaylistAndAlbum(): QueueData {
        val temp = this.data.listTracks.toMutableList()
        temp.removeAt(0)
        return this.copy(
            data =
                this.data.copy(
                    listTracks = temp,
                    firstPlayedTrack = null,
                ),
        )
    }

    fun removeTrackWithIndex(index: Int): QueueData {
        val temp = this.data.listTracks.toMutableList()
        temp.removeAt(index)
        return this.copy(
            data =
                this.data.copy(
                    listTracks = temp,
                ),
        )
    }

    fun setContinuation(continuation: String): QueueData =
        this.copy(
            data =
                this.data.copy(
                    continuation = continuation,
                ),
        )

    fun isLocalPlaylist(): Boolean = this.data.playlistType == PlaylistType.LOCAL_PLAYLIST

    fun isRadio(): Boolean = this.data.playlistType == PlaylistType.RADIO

    /** True for both plain playlists and albums — an album queue is a playlist that knows its origin. */
    fun isPlaylist(): Boolean =
        this.data.playlistType == PlaylistType.PLAYLIST ||
            this.data.playlistType == PlaylistType.ALBUM
}

/**
 * Why "Add to queue" is unavailable. It appends to the END of the queue, and two kinds of queue never
 * reach one: a radio or mix, which YouTube keeps extending, and any queue while Endless queue is on,
 * which the app keeps extending. Only Play next has somewhere to put a song there.
 */
enum class AddToQueueBlock {
    RADIO,
    ENDLESS_QUEUE,
}

/**
 * The reason Add to queue is blocked for this queue, or null when it is not.
 *
 * Decided by the id alone, not the type: a mix opened from its playlist page is queued as
 * [PlaylistType.PLAYLIST] and still never ends. [AddToQueueBlock.RADIO] wins when both apply, since
 * turning Endless queue off would not lift the block then.
 */
fun QueueData?.addToQueueBlock(endlessQueue: Boolean): AddToQueueBlock? =
    when {
        // isEndlessRadioQueueId = upstream 的宽口径（裸 RD 减策展）+ 网易电台哨兵
        // （FM/单曲电台/艺人电台按设计无论开关都无尽，同样没有队尾可加）
        this?.data?.playlistId?.isEndlessRadioQueueId() == true -> AddToQueueBlock.RADIO
        endlessQueue -> AddToQueueBlock.ENDLESS_QUEUE
        else -> null
    }

enum class PlaylistType {
    PLAYLIST,

    /**
     * A queue loaded from an album. Behaves exactly like [PLAYLIST] everywhere else — it exists so
     * playback can tell that the tracks were sequenced together deliberately, which is what lets
     * crossfade step aside inside an album while still fading into whatever is queued after it.
     */
    ALBUM,
    LOCAL_PLAYLIST,
    RADIO,
}

sealed class ToastType(
    extra: String? = null,
) {
    data object ExplicitContent : ToastType()

    data class PlayerError(
        val error: String,
    ) : ToastType(error)

    /** 网易灰歌按"无版权歌曲动作"设置自动处理后的结果提示 */
    data object UnavailableSongPaused : ToastType()

    data object UnavailableSongSkipped : ToastType()

    data object UnavailableSongSwitched : ToastType()

    /** 回退 YT 模式没搜到可用版本,已退回自动跳过 */
    data object UnavailableSongSwitchFailed : ToastType()

    /** 整队都不可播放,防循环护栏停住(暂停),没有可继续的歌 */
    data object UnavailableQueueExhausted : ToastType()

    data class SponsorBlockSkip(
        val category: String,
    ) : ToastType(category)
}