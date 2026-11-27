package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.utils.getSegmentedItemShape
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.flowOf

/**
 * Everything the player has actually played, newest first, including tracks that were never saved
 * to the library before this tab existed. Streamed songs resolve through the library database, so
 * a track only needs to have played once to be found here afterwards.
 */
@Composable
internal fun LibraryHistoryTab(
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    onMoreOptionsClick: (Song) -> Unit
) {
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val orderedIds = remember(playbackHistory) {
        playbackHistory
            .sortedByDescending { it.timestamp }
            .map { it.songId }
            .distinct()
    }
    val songsFlow = remember(orderedIds) {
        if (orderedIds.isEmpty()) flowOf(emptyList()) else playerViewModel.observeSongs(orderedIds)
    }
    val songs by songsFlow.collectAsStateWithLifecycle(initialValue = emptyList<Song>())
    val orderedSongs = remember(orderedIds, songs) {
        if (songs.isEmpty()) {
            emptyList()
        } else {
            val songsById = songs.associateBy { it.id }
            orderedIds.mapNotNull { songsById[it] }
        }
    }

    if (orderedSongs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.library_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val historyQueueName = stringResource(R.string.library_history_queue_name)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap
        )
    ) {
        itemsIndexed(
            items = orderedSongs,
            key = { _, song -> song.id },
            contentType = { _, _ -> "history_song" }
        ) { index, song ->
            LibraryPlaybackAwareSongItem(
                song = song,
                playerViewModel = playerViewModel,
                customShape = getSegmentedItemShape(index, orderedSongs.size),
                onMoreOptionsClick = onMoreOptionsClick,
                onClick = { playerViewModel.playSongs(orderedSongs, song, historyQueueName) }
            )
        }
    }
}
