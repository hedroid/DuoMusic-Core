package com.maxrave.data.repository

import com.maxrave.common.NETEASE_ARTIST_RADIO_PLAYLIST_ID_PREFIX
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 网易没有 YTM 的 RDAT 接口；每页穿插该艺人的热门歌曲与种子歌的相似歌曲。 */
object NeteaseArtistRadio {
    private const val SOURCE_PAGE_SIZE = 15

    data class Cursor(val artistOffset: Int, val similarOffset: Int) {
        fun encode(): String = "$artistOffset:$similarOffset"

        companion object {
            fun parse(value: String?): Cursor? {
                val parts = value?.split(':') ?: return null
                if (parts.size != 2) return null
                val artist = parts[0].toIntOrNull() ?: return null
                val similar = parts[1].toIntOrNull() ?: return null
                if (artist < -1 || similar < -1) return null
                return Cursor(artist, similar)
            }
        }
    }

    data class Page(val tracks: List<Track>, val continuation: String?)

    fun queueId(artistId: String, seedId: String): String =
        "$NETEASE_ARTIST_RADIO_PLAYLIST_ID_PREFIX${artistId}_$seedId"

    fun idsFromQueueId(queueId: String): Pair<String, String>? {
        if (!queueId.startsWith(NETEASE_ARTIST_RADIO_PLAYLIST_ID_PREFIX)) return null
        val ids = queueId.removePrefix(NETEASE_ARTIST_RADIO_PLAYLIST_ID_PREFIX).split('_')
        if (ids.size != 2 || ids.any { it.toLongOrNull() == null }) return null
        return ids[0] to ids[1]
    }

    fun mix(artistSongs: List<Track>, similarSongs: List<Track>): List<Track> {
        val result = ArrayList<Track>(artistSongs.size + similarSongs.size)
        val seen = HashSet<String>()
        for (index in 0 until maxOf(artistSongs.size, similarSongs.size)) {
            artistSongs.getOrNull(index)?.takeIf { seen.add(it.videoId) }?.let(result::add)
            similarSongs.getOrNull(index)?.takeIf { seen.add(it.videoId) }?.let(result::add)
        }
        return result
    }

    suspend fun firstPage(
        artistSongs: List<Track>,
        repository: NeteaseRepositoryImpl,
    ): Page? {
        val seed = artistSongs.firstOrNull() ?: return null
        val artistPage = artistSongs.take(SOURCE_PAGE_SIZE)
        val related = withContext(Dispatchers.IO) {
            repository.getSongRadio(seed.videoId, limit = SOURCE_PAGE_SIZE, offset = 0)?.songs
        }
        val relatedTracks = related?.map { it.toTrack() }.orEmpty()
        val cursor = Cursor(
            // The artist screen can show fewer than one radio page even when the full catalog has more.
            artistOffset = artistPage.size,
            similarOffset = when {
                related == null -> 0 // Failed request: retry this source on the next page.
                related.size >= SOURCE_PAGE_SIZE -> related.size
                else -> -1
            },
        )
        return Page(
            tracks = mix(artistPage, relatedTracks),
            continuation = cursor.takeUnless { it.artistOffset == -1 && it.similarOffset == -1 }?.encode(),
        )
    }

    suspend fun nextPage(
        artistId: String,
        seedId: String,
        cursor: Cursor,
        repository: NeteaseRepositoryImpl,
    ): Page? = coroutineScope {
        val artistRequest =
            if (cursor.artistOffset >= 0) {
                async(Dispatchers.IO) {
                    repository.getArtistSongsPage(
                        artistId = artistId,
                        order = "hot",
                        offset = cursor.artistOffset,
                        limit = SOURCE_PAGE_SIZE,
                    ).getOrNull()
                }
            } else null
        val similarRequest =
            if (cursor.similarOffset >= 0) {
                async(Dispatchers.IO) {
                    repository.getSongRadio(seedId, limit = SOURCE_PAGE_SIZE, offset = cursor.similarOffset)?.songs
                }
            } else null
        val artistPage = artistRequest?.await()
        val similarPage = similarRequest?.await()
        if (artistPage == null && similarPage == null && (artistRequest != null || similarRequest != null)) {
            return@coroutineScope null
        }
        val next = Cursor(
            artistOffset = when {
                cursor.artistOffset < 0 -> -1
                artistPage == null -> cursor.artistOffset
                artistPage.second && artistPage.first.isNotEmpty() -> cursor.artistOffset + artistPage.first.size
                else -> -1
            },
            similarOffset = when {
                cursor.similarOffset < 0 -> -1
                similarPage == null -> cursor.similarOffset
                similarPage.size >= SOURCE_PAGE_SIZE -> cursor.similarOffset + similarPage.size
                else -> -1
            },
        )
        Page(
            tracks = mix(artistPage?.first?.map { it.toTrack() }.orEmpty(), similarPage?.map { it.toTrack() }.orEmpty()),
            continuation = next.takeUnless { it.artistOffset == -1 && it.similarOffset == -1 }?.encode(),
        )
    }
}
