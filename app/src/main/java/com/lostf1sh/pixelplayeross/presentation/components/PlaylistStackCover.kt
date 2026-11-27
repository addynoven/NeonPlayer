package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.lostf1sh.pixelplayeross.utils.formatSongCount

/**
 * How the covers behind the front one are arranged.
 *
 * [SYMMETRICAL] fans them out left and right, [CASCADE] tucks them to one side.
 */
enum class StackStyle { SYMMETRICAL, CASCADE }

/** Never show more than three covers, however long the playlist is. */
private const val MAX_STACK_COVERS = 3

private val StackCardShape = RoundedCornerShape(12.dp)

private data class StackSlot(
    val rotationZ: Float,
    val translateXRatio: Float,
    val scale: Float,
    val zIndex: Float,
    val dim: Float
)

private fun slotsFor(style: StackStyle): List<StackSlot> = when (style) {
    StackStyle.SYMMETRICAL -> listOf(
        StackSlot(rotationZ = 0f, translateXRatio = 0f, scale = 1f, zIndex = 3f, dim = 0f),
        StackSlot(rotationZ = -11f, translateXRatio = -0.15f, scale = 0.95f, zIndex = 2f, dim = 0.18f),
        StackSlot(rotationZ = 11f, translateXRatio = 0.15f, scale = 0.94f, zIndex = 1f, dim = 0.30f)
    )
    StackStyle.CASCADE -> listOf(
        StackSlot(rotationZ = 6f, translateXRatio = 0f, scale = 1f, zIndex = 3f, dim = 0f),
        StackSlot(rotationZ = -6f, translateXRatio = -0.13f, scale = 0.95f, zIndex = 2f, dim = 0.18f),
        StackSlot(rotationZ = -18f, translateXRatio = -0.26f, scale = 0.92f, zIndex = 1f, dim = 0.30f)
    )
}

/**
 * A playlist shown as a bundle of its own track covers rather than one flat thumbnail.
 *
 * The first three [songCovers] are stacked with the first track on top; shorter playlists
 * simply show fewer cards. The back cards fan out wider while the bundle is pressed, and a
 * floating play button on the front cover turns into an equalizer while [isPlaying].
 */
@Composable
fun PlaylistStackCover(
    title: String,
    songCovers: List<String>,
    isPlaying: Boolean,
    stackStyle: StackStyle = StackStyle.SYMMETRICAL,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardSize: Dp = 92.dp,
    isLoading: Boolean = false,
    showMetadata: Boolean = true,
    playContentDescription: String? = null,
    songCount: Int = songCovers.size
) {
    val covers = remember(songCovers) {
        songCovers.mapNotNull { it.takeIf { cover -> cover.isNotBlank() } }.take(MAX_STACK_COVERS)
    }
    val slots = remember(stackStyle) { slotsFor(stackStyle) }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val fan by animateFloatAsState(
        targetValue = if (pressed || hovered) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "playlistStackFan"
    )

    val placeholderColors = listOf(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = cardSize * 1.5f, height = cardSize * 1.32f)
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            covers.forEachIndexed { index, cover ->
                StackCoverCard(
                    cover = cover,
                    slot = slots.getOrElse(index) { slots.last() },
                    fan = fan,
                    cardSize = cardSize,
                    borderColor = MaterialTheme.colorScheme.surface,
                    placeholderColors = placeholderColors
                )
            }

            StackPlayButton(
                isPlaying = isPlaying,
                isLoading = isLoading,
                cardSize = cardSize,
                buttonSize = (cardSize * 0.42f).coerceIn(26.dp, 38.dp),
                contentDescription = playContentDescription,
                onClick = onPlayClick
            )
        }

        if (showMetadata) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatSongCount(songCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StackCoverCard(
    cover: String,
    slot: StackSlot,
    fan: Float,
    cardSize: Dp,
    borderColor: Color,
    placeholderColors: List<Color>
) {
    Box(
        modifier = Modifier
            .size(cardSize)
            .zIndex(slot.zIndex)
            .graphicsLayer {
                scaleX = slot.scale
                scaleY = slot.scale
                rotationZ = slot.rotationZ * (1f + fan * 0.2f)
                translationX = slot.translateXRatio * cardSize.toPx() * (1f + fan * 0.6f)
                transformOrigin = TransformOrigin(0.5f, 0.85f)
                shape = StackCardShape
                clip = true
                shadowElevation = 6.dp.toPx()
            }
    ) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (slot.dim > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = slot.dim))
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .border(2.dp, borderColor, StackCardShape)
        )
    }
}

@Composable
private fun StackPlayButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    cardSize: Dp,
    buttonSize: Dp,
    contentDescription: String?,
    onClick: () -> Unit
) {
    val size = buttonSize
    val accent = MaterialTheme.colorScheme.primary
    val accentContent = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = Modifier
            .offset(x = cardSize / 2 - size / 3, y = cardSize / 2 - size / 3)
            .size(size)
            .zIndex(10f)
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(if (isPlaying) accent else MaterialTheme.colorScheme.surface)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
            .clickable(enabled = !isLoading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface
            )
            isPlaying -> EqualizerBars(color = accentContent)
            else -> Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** The three bars that stand in for "this playlist is the one playing right now". */
@Composable
private fun EqualizerBars(color: Color) {
    val transition = rememberInfiniteTransition(label = "playlistEqualizer")
    val bars = List(3) { index ->
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 360 + index * 110,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(index * 80)
            ),
            label = "playlistEqualizerBar$index"
        )
    }

    Row(
        modifier = Modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        bars.forEach { heightFraction ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(heightFraction.value.coerceIn(0.25f, 1f))
                    .background(color, RoundedCornerShape(1.5.dp))
            )
        }
    }
}
