package com.lostf1sh.pixelplayeross.data.model

import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.data.youtube.YouTubePlaylistSummary

@Immutable
sealed interface SearchResultItem {
    data class SongItem(val song: Song) : SearchResultItem
    data class AlbumItem(val album: Album) : SearchResultItem
    data class ArtistItem(val artist: Artist) : SearchResultItem
    data class PlaylistItem(val playlist: Playlist) : SearchResultItem

    /**
     * A playlist that lives on YouTube rather than in the library. Tapping it queues the whole
     * list, so it is not a [PlaylistItem]: there is no local playlist to open or edit.
     */
    data class YouTubePlaylistItem(val playlist: YouTubePlaylistSummary) : SearchResultItem
}
