package com.lostf1sh.pixelplayeross.data.youtube

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class YouTubePlaylistIdTest {

    @Test
    fun `reads id from a playlist link`() {
        assertThat(extractYouTubePlaylistId("https://www.youtube.com/playlist?list=PLAFA884C3B5DC349D"))
            .isEqualTo("PLAFA884C3B5DC349D")
    }

    @Test
    fun `reads id from a music youtube link`() {
        assertThat(extractYouTubePlaylistId("https://music.youtube.com/playlist?list=OLAK5uy_kExampleId123"))
            .isEqualTo("OLAK5uy_kExampleId123")
    }

    @Test
    fun `reads id from a watch link that carries a list`() {
        val link = "https://www.youtube.com/watch?v=abc123&list=PLeTrn4AsfVQyVNHMymfdRYls046TEZU5H&index=4"
        assertThat(extractYouTubePlaylistId(link)).isEqualTo("PLeTrn4AsfVQyVNHMymfdRYls046TEZU5H")
    }

    @Test
    fun `strips the browse id prefix`() {
        assertThat(extractYouTubePlaylistId("VLPLeTrn4AsfVQyVNHMymfdRYls046TEZU5H"))
            .isEqualTo("PLeTrn4AsfVQyVNHMymfdRYls046TEZU5H")
    }

    @Test
    fun `accepts a bare playlist id`() {
        assertThat(extractYouTubePlaylistId("  PLAFA884C3B5DC349D  ")).isEqualTo("PLAFA884C3B5DC349D")
    }

    @Test
    fun `ignores a plain song link`() {
        assertThat(extractYouTubePlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isNull()
    }

    @Test
    fun `ignores a search query`() {
        assertThat(extractYouTubePlaylistId("need for speed most wanted soundtrack")).isNull()
    }

    @Test
    fun `ignores blank input`() {
        assertThat(extractYouTubePlaylistId("   ")).isNull()
    }
}
