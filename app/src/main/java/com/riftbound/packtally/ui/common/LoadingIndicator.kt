package com.riftbound.packtally.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Canonical loading state for the app. Use this anywhere data is being fetched
 * instead of rolling a one-off `CircularProgressIndicator`.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier.fillMaxSize(),
    label: String? = null,
) {
    val description = label ?: "Loading"
    Box(
        modifier = modifier.semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
            if (label != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Small inline variant — for use inside Rows next to a label. */
@Composable
fun InlineLoader(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier
            .size(20.dp)
            .semantics {
                contentDescription = "Loading"
                liveRegion = LiveRegionMode.Polite
            },
        strokeWidth = 2.dp,
    )
}
