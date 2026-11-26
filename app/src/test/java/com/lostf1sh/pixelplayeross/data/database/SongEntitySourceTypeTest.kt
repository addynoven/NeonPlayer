package com.lostf1sh.pixelplayeross.data.database

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.SongSource
import org.junit.jupiter.api.Test

/**
 * `source_type` is what tells the UI whether a row is a local file or a cloud track.
 * `toSong` used to drop it, so every song looked local and no badge could be shown.
 */
class SongEntitySourceTypeTest {

    private fun entity(sourceType: Int, contentUri: String = "content://media/1") = SongEntity(
        id = 1L,
        title = "Track",
        artistName = "Artist",
        artistId = 2L,
        albumName = "Album",
        albumId = 3L,
        contentUriString = contentUri,
        albumArtUriString = null,
        duration = 180_000L,
        genre = null,
        filePath = "/music/track.opus",
        parentDirectoryPath = "/music",
        sourceType = sourceType
    )

    @Test
    fun `local rows map to LOCAL`() {
        assertThat(entity(SourceType.LOCAL).toSong().sourceType).isEqualTo(SongSource.LOCAL)
    }

    @Test
    fun `youtube rows keep their source`() {
        val song = entity(SourceType.YOUTUBE, "youtube://dQw4w9WgXcQ").toSong()

        assertThat(song.sourceType).isEqualTo(SongSource.YOUTUBE)
        assertThat(song.sourceType.requiresNetwork).isTrue()
    }

    @Test
    fun `navidrome rows keep their source`() {
        val song = entity(SourceType.NAVIDROME, "navidrome://song-1").toSong()

        assertThat(song.sourceType).isEqualTo(SongSource.NAVIDROME)
    }

    @Test
    fun `jellyfin rows keep their source`() {
        val song = entity(SourceType.JELLYFIN, "jellyfin://item-1").toSong()

        assertThat(song.sourceType).isEqualTo(SongSource.JELLYFIN)
    }

    @Test
    fun `unknown source values fall back to local`() {
        assertThat(entity(99).toSong().sourceType).isEqualTo(SongSource.LOCAL)
    }
}