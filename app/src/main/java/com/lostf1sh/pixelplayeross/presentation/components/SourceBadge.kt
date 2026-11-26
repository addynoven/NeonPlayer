package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.SongSource

/**
 * Small "where does this come from" tag for a song row. Local files get no badge;
 * cloud sources get their name so a list mixing library and YouTube results is readable.
 */
@Composable
fun SourceBadge(
    source: SongSource,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val label = when (source) {
        SongSource.LOCAL -> return
        SongSource.YOUTUBE -> stringResource(R.string.source_youtube)
        SongSource.NAVIDROME -> stringResource(R.string.source_navidrome)
        SongSource.JELLYFIN -> stringResource(R.string.source_jellyfin)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = tint.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}