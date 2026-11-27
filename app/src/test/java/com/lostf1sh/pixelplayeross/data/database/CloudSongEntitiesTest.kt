package com.lostf1sh.pixelplayeross.data.database

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.SongSource
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeIds
import org.junit.jupiter.api.Test

/**
 * A streamed track has to become a row before the library and History can find it, and `songs`
 * refuses rows whose artist and album are missing. These cover the pair the insert now builds.
 */
class CloudSongEntitiesTest {

    private fun song(
        id: String = "7001121404743",
        artist: String = "LYRICAL BAM HINDI",
        album: String = "YouTube Music",
        genre: String? = null,
        dateAdded: Long = 0L
    ) = Song(
        id = id,
        title = "Tu Jaane Na",
        artist = artist,
        artistId = 0L,
        album = album,
        albumId = 0L,
        albumArtist = null,
        path = "youtube://_d2Yux449ds",
        contentUriString = "youtube://_d2Yux449ds",
        albumArtUriString = "https://i.ytimg.com/vi/_d2Yux449ds/hq720.jpg",
        duration = 338_000L,
        genre = genre,
        mimeType = null,
        bitrate = null,
        sampleRate = null,
        dateAdded = dateAdded,
        sourceType = SongSource.LOCAL
    )

    @Test
    fun `the song row gets the parent ids the artist and album rows use`() {
        val rows = requireNotNull(song().toCloudSongRows(nowMillis = 42L))

        assertThat(rows.song.artistId).isEqualTo(rows.artist.id)
        assertThat(rows.song.albumId).isEqualTo(rows.album.id)
        assertThat(rows.artist.id).isEqualTo(YouTubeIds.artistId("LYRICAL BAM HINDI"))
        assertThat(rows.album.id).isEqualTo(YouTubeIds.albumId("YouTube Music"))
    }

    @Test
    fun `the song row is stored like a cloud track, not a local file`() {
        val rows = requireNotNull(song().toCloudSongRows())

        assertThat(rows.song.parentDirectoryPath).isEqualTo(CLOUD_SONG_PARENT_DIRECTORY)
        assertThat(rows.song.filePath).isEqualTo("youtube://_d2Yux449ds")
        assertThat(rows.song.sourceType).isEqualTo(SourceType.YOUTUBE)
    }

    @Test
    fun `blank metadata falls back to the cloud defaults`() {
        val rows = requireNotNull(song(artist = "", album = "", genre = "   ").toCloudSongRows())

        assertThat(rows.artist.name).isEqualTo(CLOUD_SONG_UNKNOWN_ARTIST)
        assertThat(rows.album.title).isEqualTo(CLOUD_SONG_UNKNOWN_ALBUM)
        assertThat(rows.song.genre).isEqualTo(CLOUD_SONG_UNKNOWN_ALBUM)
    }

    @Test
    fun `a song without a usable id has no rows`() {
        assertThat(song(id = "youtube:_d2Yux449ds").toCloudSongRows()).isNull()
    }

    @Test
    fun `a missing date added is stamped with the insert time`() {
        val rows = requireNotNull(song(dateAdded = 0L).toCloudSongRows(nowMillis = 42L))

        assertThat(rows.song.dateAdded).isEqualTo(42L)
        assertThat(rows.album.dateAdded).isEqualTo(42L)
    }
}
