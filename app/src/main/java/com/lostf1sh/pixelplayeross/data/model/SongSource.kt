package com.lostf1sh.pixelplayeross.data.model

/**
 * Where a [Song] comes from. The database stores the same information as an int in
 * `songs.source_type`; this enum keeps the UI from having to know those magic numbers.
 *
 * A song with a cloud source still plays, but it needs the network (and, for YouTube,
 * the stream proxy), which is what the badge in the song row is warning about.
 */
enum class SongSource {
    LOCAL,
    NAVIDROME,
    JELLYFIN,
    YOUTUBE;

    /** True when playback needs a network connection. */
    val requiresNetwork: Boolean
        get() = this != LOCAL
}