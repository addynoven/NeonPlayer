package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.EnhancedSongListItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Immutable
internal data class LibrarySongPlaybackUiState(
    val isCurrentSong: Boolean = false,
    val isPlaying: Boolean = false,
    val isStreamLoading: Boolean = false
)

/**
 * Whether the row for [songId] should show the loading indicator. Two phases produce the same
 * silence for the user, so they share one flag: the tap-to-resolution phase, where the stream
 * URL is still being fetched and the player has nothing to buffer yet ([preparingSongId]), and
 * the buffering phase that follows once the item is handed to the player ([isBuffering]).
 */
internal fun isSongStreamLoading(
    songId: String,
    currentSongId: String?,
    isBuffering: Boolean,
    preparingSongId: String?
): Boolean = preparingSongId == songId || (currentSongId == songId && isBuffering)

/**
 * True from the moment a song is tapped until its audio actually starts: first the stream
 * resolution phase (nothing has been handed to the player yet), then the buffering phase.
 * The two are indistinguishable to the user — either way the tap produced silence — so the
 * list shows one continuous indicator instead of going blank between them.
 */
@Composable
internal fun rememberSongStreamLoading(songId: String, playerViewModel: PlayerViewModel): Boolean {
    val isLoading by remember(songId, playerViewModel) {
        combine(
            playerViewModel.stablePlayerState,
            playerViewModel.playerUiState
                .map { it.preparingSongId }
                .distinctUntilChanged()
        ) { player, preparingSongId ->
            isSongStreamLoading(
                songId = songId,
                currentSongId = player.currentSong?.id,
                isBuffering = player.isBuffering,
                preparingSongId = preparingSongId
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    return isLoading
}

@OptIn(UnstableApi::class)
@Composable
internal fun LibraryPlaybackAwareSongItem(
    song: Song,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    albumArtSize: Dp = 50.dp,
    customShape: Shape? = null,
    showAlbumArt: Boolean = true,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onSwipeToQueue: (() -> Unit)? = null,
    onMoreOptionsClick: (Song) -> Unit,
    onClick: () -> Unit
) {
    val playbackUiState by remember(song.id, playerViewModel) {
        combine(
            playerViewModel.stablePlayerState,
            playerViewModel.playerUiState
                .map { it.preparingSongId }
                .distinctUntilChanged()
        ) { player, preparingSongId ->
            val isCurrentSong = player.currentSong?.id == song.id
            LibrarySongPlaybackUiState(
                isCurrentSong = isCurrentSong,
                isPlaying = isCurrentSong && player.isPlaying,
                isStreamLoading = isSongStreamLoading(
                    songId = song.id,
                    currentSongId = player.currentSong?.id,
                    isBuffering = player.isBuffering,
                    preparingSongId = preparingSongId
                )
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = LibrarySongPlaybackUiState())

    EnhancedSongListItem(
        modifier = modifier,
        song = song,
        customShape = customShape,
        isPlaying = playbackUiState.isPlaying,
        isCurrentSong = playbackUiState.isCurrentSong,
        isLoading = false,
        isStreamLoading = playbackUiState.isStreamLoading,
        albumArtSize = albumArtSize,
        showAlbumArt = showAlbumArt,
        isSelected = isSelected,
        selectionIndex = selectionIndex,
        isSelectionMode = isSelectionMode,
        onLongPress = onLongPress,
        onSwipeToQueue = onSwipeToQueue,
        onMoreOptionsClick = onMoreOptionsClick,
        onClick = onClick
    )
}
