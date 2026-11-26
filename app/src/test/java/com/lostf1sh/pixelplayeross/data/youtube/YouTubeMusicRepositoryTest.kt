package com.lostf1sh.pixelplayeross.data.youtube

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Regression tests for the YouTube "Save All" path.
 *
 * `songs.album_id` is NOT NULL with a foreign key to `albums(id)`, and Room runs with
 * `PRAGMA foreign_keys = ON`. Saving songs whose synthetic album/artist ids have no parent
 * rows fails the constraint, so `saveSongsToLibrary` must insert the parent artists and
 * albums in the same transaction (see the FK failure that made "Save All" a silent no-op).
 */
@DisplayName("saveSongsToLibrary inserts parent artists and albums")
class YouTubeMusicRepositoryTest {

    private lateinit var musicDao: MusicDao
    private lateinit var repository: YouTubeMusicRepository

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        musicDao = mockk(relaxed = true)
        val context = mockk<Context>(relaxed = true) {
            coEvery { cacheDir } returns tempDir
            coEvery { filesDir } returns tempDir
        }
        // Mockito-style strict stubbing would fail on NewPipe init; keep relaxed and only
        // verify the persistence interaction under test.
        repository = YouTubeMusicRepository(
            downloader = mockk(relaxed = true),
            musicDao = musicDao,
            okHttpClient = OkHttpClient(),
            context = context
        )
    }

    private fun song(id: String, title: String, artist: String, album: String) = Song(
        id = id,
        title = title,
        artist = artist,
        artistId = artist.hashCode().toLong(),
        album = album,
        albumId = album.hashCode().toLong(),
        path = "youtube://$id",
        contentUriString = "youtube://$id",
        albumArtUriString = null,
        duration = 180_000L,
        mimeType = "audio/opus",
        bitrate = 160,
        sampleRate = 48000
    )

    @Test
    fun `saving songs calls the transactional insertMusicData with non-empty parents`() = runTest {
        val songs = listOf(
            song("v1", "Imagine", "John Lennon", "YouTube Music"),
            song("v2", "Demons", "Imagine Dragons", "YouTube Music")
        )

        val result = repository.saveSongsToLibrary(songs)

        assertTrue(result.isSuccess, "saveSongsToLibrary must succeed when parents are provided")

        val songsSlot = slot<List<SongEntity>>()
        val albumsSlot = slot<List<AlbumEntity>>()
        val artistsSlot = slot<List<ArtistEntity>>()
        coVerify(exactly = 1) {
            musicDao.insertMusicData(
                capture(songsSlot),
                capture(albumsSlot),
                capture(artistsSlot)
            )
        }
        coVerify(exactly = 0) { musicDao.insertSongs(any()) }

        assertEquals(2, songsSlot.captured.size)
        assertTrue(
            albumsSlot.captured.isNotEmpty(),
            "Album rows must be inserted: songs.album_id has a NOT NULL foreign key to albums"
        )
        assertTrue(
            artistsSlot.captured.isNotEmpty(),
            "Artist rows must be inserted: songs.artist_id has a NOT NULL foreign key to artists"
        )

        // Every song's album/artist id must exist among the inserted parents.
        val albumIds = albumsSlot.captured.map { it.id }.toSet()
        val artistIds = artistsSlot.captured.map { it.id }.toSet()
        songsSlot.captured.forEach {
            assertTrue(albumIds.contains(it.albumId), "Song ${it.title} references missing album ${it.albumId}")
            assertTrue(artistIds.contains(it.artistId), "Song ${it.title} references missing artist ${it.artistId}")
        }
    }

    @Test
    fun `songs sharing an album are deduplicated into one album row`() = runTest {
        val songs = listOf(
            song("v1", "Track A", "Artist", "YouTube Music"),
            song("v2", "Track B", "Artist", "YouTube Music")
        )

        repository.saveSongsToLibrary(songs)

        val albumsSlot = slot<List<AlbumEntity>>()
        coVerify { musicDao.insertMusicData(any(), capture(albumsSlot), any()) }
        assertEquals(1, albumsSlot.captured.size, "Two songs of the same album must map to one album row")
    }

    @Test
    fun `a dao failure surfaces as Result failure instead of being swallowed`() = runTest {
        coEvery { musicDao.insertMusicData(any(), any(), any()) } throws
            IllegalStateException("FOREIGN KEY constraint failed")

        val result = repository.saveSongsToLibrary(listOf(song("v1", "T", "A", "Alb")))

        assertTrue(result.isFailure, "Persistence failures must be reported to the caller, not only logged")
    }
}
