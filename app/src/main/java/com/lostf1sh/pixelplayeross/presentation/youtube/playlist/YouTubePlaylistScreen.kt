package com.lostf1sh.pixelplayeross.presentation.youtube.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.PlaylistStackCover
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.ExpressiveSongListItem
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel

/**
 * One YouTube playlist as a page: its covers on top, its tracks below.
 *
 * Opening a playlist is not a decision to start listening, so nothing plays until a track (or the
 * play button on the covers) is tapped. Picking a track queues the whole playlist and starts there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubePlaylistScreen(
    playerViewModel: PlayerViewModel,
    viewModel: YouTubePlaylistViewModel,
    fallbackTitle: String?,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    val playlist = uiState.playlist
    val isThisQueuePlaying = playlist != null &&
        playerState.currentPlaylistId == playlist.id &&
        playerState.isPlaying

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = playlist?.title ?: fallbackTitle.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        when {
            playlist == null && uiState.errorMessage != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = { viewModel.retry() }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }

            playlist == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val playQueue = { playerViewModel.playYouTubePlaylistTracks(playlist) }
                        PlaylistStackCover(
                            title = playlist.title,
                            songCovers = playlist.songs
                                .mapNotNull { it.albumArtUriString }
                                .distinct()
                                .take(3),
                            isPlaying = isThisQueuePlaying,
                            songCount = playlist.songs.size,
                            onClick = playQueue,
                            onPlayClick = playQueue,
                            cardSize = 104.dp,
                            playContentDescription = stringResource(R.string.cd_play_playlist)
                        )
                    }
                }

                // Positional keys: a playlist may legitimately contain the same video twice.
                itemsIndexed(playlist.songs, key = { index, song -> "$index-${song.id}" }) { _, song ->
                    val isCurrentSong = playerState.currentSong?.id == song.id
                    ExpressiveSongListItem(
                        song = song,
                        isPlaying = isCurrentSong && playerState.isPlaying,
                        onClick = {
                            playerViewModel.playYouTubePlaylistTracks(playlist, song)
                        }
                    )
                }
            }
        }
    }
}