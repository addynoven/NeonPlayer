package com.lostf1sh.pixelplayeross.data.database

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeIds

/**
 * Rows needed to keep a streamed track in the library tables.
 *
 * `songs` enforces foreign keys on `artist_id` and `album_id`, so a cloud song cannot be inserted
 * on its own. The synthetic ids come from the names, like the YouTube dashboard's save path, which
 * keeps one artist and one album row per name instead of one per track.
 */
internal data class CloudSongRows(
    val song: SongEntity,
    val artist: ArtistEntity,
    val album: AlbumEntity
)

internal const val CLOUD_SONG_PARENT_DIRECTORY = "/Cloud/YouTube"
internal const val CLOUD_SONG_UNKNOWN_ARTIST = "Unknown Artist"
internal const val CLOUD_SONG_UNKNOWN_ALBUM = "YouTube Music"

internal fun Song.toCloudSongRows(nowMillis: Long = System.currentTimeMillis()): CloudSongRows? {
    val numericId = id.toLongOrNull() ?: return null
    val artistName = artist.ifBlank { CLOUD_SONG_UNKNOWN_ARTIST }
    val albumName = album.ifBlank { CLOUD_SONG_UNKNOWN_ALBUM }
    val artistId = YouTubeIds.artistId(artistName)
    val albumId = YouTubeIds.albumId(albumName)
    val entity = toEntityWithoutPaths().copy(
        id = numericId,
        artistId = artistId,
        artistName = artistName,
        albumId = albumId,
        albumName = albumName,
        albumArtist = albumArtist ?: artistName,
        filePath = contentUriString,
        parentDirectoryPath = CLOUD_SONG_PARENT_DIRECTORY,
        genre = genre?.takeIf { it.isNotBlank() } ?: CLOUD_SONG_UNKNOWN_ALBUM,
        dateAdded = dateAdded.takeIf { it > 0L } ?: nowMillis
    )
    return CloudSongRows(
        song = entity,
        artist = ArtistEntity(
            id = artistId,
            name = artistName,
            trackCount = 0
        ),
        album = AlbumEntity(
            id = albumId,
            title = albumName,
            artistName = artistName,
            artistId = artistId,
            albumArtUriString = albumArtUriString,
            songCount = 0,
            dateAdded = nowMillis,
            year = 0,
            albumArtist = albumArtist ?: artistName
        )
    )
}
