package com.lostf1sh.pixelplayeross.data.youtube

import kotlin.math.absoluteValue

/**
 * The synthetic ids YouTube tracks and artists get so they can live in the same tables as local
 * music. They have to be stable: a song loaded from the playlist cache must carry the same id as
 * the same song loaded from the network, or the queue would not know which row is playing.
 */
internal object YouTubeIds {
    private const val SONG_OFFSET = 7_000_000_000_000L
    private const val ARTIST_OFFSET = 7_100_000_000_000L
    private const val ALBUM_OFFSET = 7_200_000_000_000L
    private const val RANGE = 1_000_000_000_000L

    fun songId(videoId: String): Long = SONG_OFFSET + (videoId.hashCode().toLong().absoluteValue % RANGE)

    fun artistId(artistName: String): Long =
        ARTIST_OFFSET + (artistName.hashCode().toLong().absoluteValue % RANGE)

    fun albumId(albumName: String): Long =
        ALBUM_OFFSET + (albumName.hashCode().toLong().absoluteValue % RANGE)
}