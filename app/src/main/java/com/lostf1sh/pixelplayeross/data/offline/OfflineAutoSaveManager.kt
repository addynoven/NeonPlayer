package com.lostf1sh.pixelplayeross.data.offline

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Keeps the songs a listener actually cares about on the phone without them tapping download.
 *
 * Two triggers, both opt-out in settings: a heart saves the track, and a track that was listened
 * to for long enough joins a small rolling cache of recent songs. Everything saved this way is
 * marked with the origin that created it, so [CloudOfflineRepository.trim] can throw away the
 * automatic copies without touching files the listener downloaded on purpose.
 */
@Singleton
class OfflineAutoSaveManager @Inject constructor(
    private val repository: CloudOfflineRepository,
    private val preferencesRepository: UserPreferencesRepository
) {
    /**
     * The player only hands [ListeningStatsTracker] a song id, and YouTube songs are not in the
     * library database, so the last songs seen by the player are kept here; a track can only be
     * auto-saved in the minutes after it played anyway.
     */
    private val rememberedSongs = LinkedHashMap<String, Song>()

    fun rememberSong(song: Song) {
        if (!CloudOfflineRepository.isCloudSong(song)) return
        synchronized(rememberedSongs) {
            rememberedSongs.remove(song.id)
            rememberedSongs[song.id] = song
            while (rememberedSongs.size > REMEMBERED_SONGS) {
                rememberedSongs.remove(rememberedSongs.keys.first())
            }
        }
    }

    /** Hearting saves the track; un-hearting only deletes a copy that the heart created. */
    suspend fun onFavoriteChanged(song: Song, isFavorite: Boolean) {
        if (!CloudOfflineRepository.isCloudSong(song)) return
        runCatching {
            if (isFavorite) {
                if (!preferencesRepository.autoSaveFavoritesFlow.first()) return
                repository.enqueue(song, OfflineTrackOrigin.FAVORITE)
            } else {
                val existing = repository.find(song.contentUriString) ?: return
                if (existing.origin != OfflineTrackOrigin.FAVORITE) return
                repository.remove(song)
            }
            trim()
        }.onFailure { error ->
            Timber.w(error, "Auto-save failed for %s", song.title)
        }
    }

    /** Called when a playback session ends, with the time actually spent on the track. */
    suspend fun onListened(songId: String, listenedMs: Long) {
        if (listenedMs < MIN_LISTENED_MS) return
        runCatching {
            val keep = preferencesRepository.recentOfflineCountFlow.first()
            if (keep <= 0) return
            val song = synchronized(rememberedSongs) { rememberedSongs[songId] } ?: return
            repository.enqueue(song, OfflineTrackOrigin.RECENT)
            trim()
        }.onFailure { error ->
            Timber.w(error, "Could not keep the recent track %s offline", songId)
        }
    }

    /** Drops automatic copies until the cache fits the size and count set in settings. */
    suspend fun trim() {
        runCatching {
            val limitBytes = preferencesRepository.offlineStorageLimitMbFlow.first()
                .toLong() * BYTES_PER_MEGABYTE
            val recentKeep = preferencesRepository.recentOfflineCountFlow.first()
            val removed = repository.trim(limitBytes = limitBytes, recentKeep = recentKeep)
            if (removed > 0) {
                Timber.d("Trimmed %d auto-saved tracks from the offline cache", removed)
            }
        }.onFailure { error ->
            Timber.w(error, "Could not trim the offline cache")
        }
    }

    private companion object {
        const val MIN_LISTENED_MS = 30_000L
        const val REMEMBERED_SONGS = 64
        const val BYTES_PER_MEGABYTE = 1024L * 1024L
    }
}