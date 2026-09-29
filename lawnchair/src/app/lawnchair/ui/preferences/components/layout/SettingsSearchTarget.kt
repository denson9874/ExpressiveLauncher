package app.lawnchair.ui.preferences.components.layout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.destinations.SettingsSearch
import kotlinx.coroutines.delay

/**
 * The setting a settings-search result asked to reveal. The first row on the destination screen
 * whose label matches scrolls into view and pulses once, then the request is cleared. A screen
 * with no such row (a Pro-locked group, a hidden option) simply never answers, and the request
 * expires instead of firing on a later screen.
 */
@Stable
class SettingsSearchTarget {
    private var pending by mutableStateOf<String?>(null)

    /** Bumped on every request so the host can time the request out. */
    var requestCount by mutableIntStateOf(0)
        private set

    val hasPending: Boolean get() = pending != null

    fun request(label: String) {
        pending = SettingsSearch.normalize(label)
        requestCount++
    }

    fun isPending(label: String): Boolean = pending?.let { it == SettingsSearch.normalize(label) } == true

    fun consume(label: String) {
        if (isPending(label)) pending = null
    }

    fun clear() {
        pending = null
    }

    companion object {
        /** Long enough for the screen change animation and a first frame, short enough not to linger. */
        const val EXPIRY_MS = 4_000L
        internal const val SETTLE_MS = 350L
    }
}

/** Null outside the settings UI (previews, tests), where rows simply don't react to a search. */
val LocalSettingsSearchTarget = staticCompositionLocalOf<SettingsSearchTarget?> { null }

/**
 * Marks a row as the destination of a settings-search result with the same [label]: it scrolls into
 * view with some context around it and pulses once. Rows that are not the target pay nothing beyond
 * a few remembered objects; the measuring, scrolling and drawing modifiers are attached only while
 * the row is the target.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.settingsSearchTarget(label: String): Modifier {
    val target = LocalSettingsSearchTarget.current
    val isTarget = target?.isPending(label) == true
    val requester = remember { BringIntoViewRequester() }
    val pulse = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(isTarget) {
        if (!isTarget || target == null) return@LaunchedEffect
        // Let the screen finish entering and measuring its rows before scrolling to one.
        delay(SettingsSearchTarget.SETTLE_MS)
        // Ask for a region taller than the row so it lands with neighbours above and below it.
        val margin = size.height * 2f
        requester.bringIntoView(Rect(0f, -margin, size.width.toFloat(), size.height + margin))
        repeat(2) {
            pulse.animateTo(1f, tween(durationMillis = 220))
            pulse.animateTo(0f, tween(durationMillis = 520))
        }
        // Clear last: consuming flips isTarget, which would cancel this effect mid-pulse.
        target.consume(label)
    }

    if (!isTarget && pulse.value == 0f) return this

    val tint = MaterialTheme.colorScheme.primary
    return this
        .onSizeChanged { size = it }
        .bringIntoViewRequester(requester)
        .drawWithContent {
            drawContent()
            if (pulse.value > 0f) {
                drawRoundRect(
                    color = tint.copy(alpha = 0.18f * pulse.value),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                )
            }
        }
}
