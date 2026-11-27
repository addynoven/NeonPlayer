package com.lostf1sh.pixelplayeross.data.youtube

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.Song
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class YouTubePlaylistCacheTest {

    @Test
    fun `a cached playlist round-trips with titles, order and ids intact`() {
        val original = playlist(
            id = "PLabc",
            title = "Need For Speed",
            songs = listOf(song("vid-1", "Get Low"), song("vid-2", "Doomsday"))
        )

        val restored = YouTubePlaylistJson.decode(YouTubePlaylistJson.encode(original))

        assertThat(restored).isNotNull()
        assertThat(restored!!.id).isEqualTo("PLabc")
        assertThat(restored.title).isEqualTo("Need For Speed")
        assertThat(restored.songs.map { it.title }).containsExactly("Get Low", "Doomsday").inOrder()
        assertThat(restored.songs.map { it.contentUriString })
            .containsExactly("youtube://vid-1", "youtube://vid-2").inOrder()
    }

    @Test
    fun `a restored song keeps the id the live resolver would give it`() {
        val live = song("dQw4w9WgXcQ", "Never Gonna Give You Up")
        val restored = YouTubePlaylistJson.decode(
            YouTubePlaylistJson.encode(playlist("PL1", "Mix", listOf(live)))
        )!!.songs.single()

        assertThat(restored.id).isEqualTo(live.id)
        assertThat(restored.albumArtUriString).isEqualTo(live.albumArtUriString)
        assertThat(restored.duration).isEqualTo(live.duration)
    }

    @Test
    fun `an entry without YouTube tracks decodes to nothing`() {
        val stray = song("local-1", "Local File").copy(
            contentUriString = "content://media/external/audio/media/1"
        )
        val payload = YouTubePlaylistJson.encode(playlist("PL2", "Mixed", listOf(stray)))

        assertThat(YouTubePlaylistJson.decode(payload)).isNull()
    }

    @Test
    fun `garbage on disk is rejected instead of crashing`() {
        assertThrows<Exception> { YouTubePlaylistJson.decode("not json at all") }
    }

    private fun playlist(id: String, title: String, songs: List<Song>) =
        YouTubePlaylist(id = id, title = title, songs = songs)

    private fun song(videoId: String, title: String) = Song(
        id = YouTubeIds.songId(videoId).toString(),
        title = title,
        artist = "Some Artist",
        artistId = YouTubeIds.artistId("Some Artist"),
        album = "YouTube Music",
        albumId = YouTubeIds.albumId("YouTube Music"),
        path = "youtube://$videoId",
        contentUriString = "youtube://$videoId",
        albumArtUriString = "https://example.test/$videoId.jpg",
        duration = 123_000L,
        mimeType = "audio/opus",
        bitrate = 160,
        sampleRate = 48000,
        sourceType = com.lostf1sh.pixelplayeross.data.model.SongSource.YOUTUBE
    )
}