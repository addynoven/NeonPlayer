package com.lostf1sh.pixelplayeross.data.youtube

import android.content.Context
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SongSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Remembers the track list of YouTube playlists that were opened, so the playlist screen still
 * has something to show without a network. Only the list is stored; audio is still streamed.
 *
 * The cache is a directory of small JSON files, one per playlist. That keeps it independent of
 * the library database, which has no business holding tracks the user never saved.
 */
@Singleton
class YouTubePlaylistCache @Inject constructor(
    @ApplicationContext context: Context
) {
    private val directory = File(context.filesDir, DIRECTORY_NAME)

    private val _changes = MutableStateFlow(0L)

    /** Bumped after every write, so a playlist opened on one screen shows up on another. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    suspend fun read(playlistId: String): YouTubePlaylist? = withContext(Dispatchers.IO) {
        val file = fileFor(playlistId)
        if (!file.isFile) return@withContext null
        runCatching { YouTubePlaylistJson.decode(file.readText()) }
            .onFailure { Timber.w(it, "Dropping unreadable playlist cache entry %s", playlistId) }
            .getOrNull()
    }

    suspend fun write(playlist: YouTubePlaylist) = withContext(Dispatchers.IO) {
        if (playlist.songs.isEmpty()) return@withContext
        runCatching {
            directory.mkdirs()
            val file = fileFor(playlist.id)
            val temp = File(directory, playlist.id + ".json.tmp")
            temp.writeText(YouTubePlaylistJson.encode(playlist))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            trimToLimit()
        }.onSuccess { _changes.update { it + 1 } }
            .onFailure { Timber.w(it, "Could not cache YouTube playlist %s", playlist.id) }
    }

    /** Every playlist that is still cached, newest first. */
    suspend fun list(): List<YouTubePlaylist> = withContext(Dispatchers.IO) {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedByDescending(File::lastModified)
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching { YouTubePlaylistJson.decode(file.readText()) }.getOrNull()
        }
    }

    private fun fileFor(playlistId: String) = File(directory, playlistId + ".json")

    /** Playlists are cheap to refetch, so keep only the most recently opened ones. */
    private fun trimToLimit() {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedByDescending(File::lastModified)
            ?: return
        files.drop(MAX_CACHED_PLAYLISTS).forEach(File::delete)
    }

    private companion object {
        const val DIRECTORY_NAME = "youtube_playlists"
        const val MAX_CACHED_PLAYLISTS = 40
    }
}

/**
 * JSON shape of a cached playlist. Kept deliberately separate from [Song] so a change to the model
 * cannot silently break entries that are already on disk.
 */
@Serializable
internal data class CachedPlaylist(
    val id: String,
    val title: String,
    val songs: List<CachedSong>
)

@Serializable
internal data class CachedSong(
    val uri: String,
    val title: String,
    val artist: String,
    val albumArtUri: String? = null,
    val durationMs: Long = 0L,
    val mimeType: String? = null
)

internal object YouTubePlaylistJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(playlist: YouTubePlaylist): String = json.encodeToString(
        CachedPlaylist.serializer(),
        CachedPlaylist(
            id = playlist.id,
            title = playlist.title,
            songs = playlist.songs.map { song ->
                CachedSong(
                    uri = song.contentUriString,
                    title = song.title,
                    artist = song.artist,
                    albumArtUri = song.albumArtUriString,
                    durationMs = song.duration,
                    mimeType = song.mimeType
                )
            }
        )
    )

    fun decode(payload: String): YouTubePlaylist? {
        val cached = json.decodeFromString(CachedPlaylist.serializer(), payload)
        if (cached.id.isBlank() || cached.songs.isEmpty()) return null
        return YouTubePlaylist(
            id = cached.id,
            title = cached.title,
            songs = cached.songs
                .filter { song -> song.uri.startsWith(YOUTUBE_URI_PREFIX) }
                .map { song ->
                    Song(
                        id = songIdFor(song.uri),
                        title = song.title,
                        artist = song.artist,
                        artistId = 0L,
                        album = YOUTUBE_ALBUM,
                        albumId = 0L,
                        albumArtist = song.artist,
                        path = song.uri,
                        contentUriString = song.uri,
                        albumArtUriString = song.albumArtUri,
                        duration = song.durationMs,
                        genre = YOUTUBE_ALBUM,
                        mimeType = song.mimeType ?: "audio/opus",
                        bitrate = 160,
                        sampleRate = 48000,
                        sourceType = SongSource.YOUTUBE
                    )
                }
        ).takeIf { it.songs.isNotEmpty() }
    }

    private const val YOUTUBE_URI_PREFIX = "youtube://"
    private const val YOUTUBE_ALBUM = "YouTube Music"

    /** Matches the id the repository derives for the same video, so queue highlighting lines up. */
    private fun songIdFor(uri: String): String =
        YouTubeIds.songId(uri.removePrefix(YOUTUBE_URI_PREFIX)).toString()
}