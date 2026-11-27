package app.lawnchair.ui.preferences.components.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun Chip(
    label: String,
    currentOffset: Float,
    page: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val selectedProgress = 1f - abs(currentOffset - page).coerceIn(0f, 1f)
    Chip(
        label = label,
        selectedProgress = selectedProgress,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val selectedProgress by animateFloatAsState(targetValue = if (selected) 1f else 0f, label = "")
    Chip(
        label = label,
        selectedProgress = selectedProgress,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
fun Chip(
    label: String,
    selectedProgress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val clampedProgress = selectedProgress.coerceIn(0f, 1f)
    val isSelected = clampedProgress >= 0.5f
    val containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = clampedProgress)
    val labelColor = lerp(
        MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.onSecondaryContainer,
        clampedProgress,
    )
    val outlineColor = MaterialTheme.colorScheme.outline
    val borderColor = outlineColor.copy(alpha = outlineColor.alpha * (1f - clampedProgress))

    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = containerColor,
            labelColor = labelColor,
            selectedContainerColor = containerColor,
            selectedLabelColor = labelColor,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = isSelected,
            borderColor = borderColor,
            selectedBorderColor = if (clampedProgress >= 0.99f) Color.Transparent else borderColor,
            borderWidth = 1.dp,
            selectedBorderWidth = if (clampedProgress >= 0.99f) 0.dp else 1.dp,
        ),
    )
}
