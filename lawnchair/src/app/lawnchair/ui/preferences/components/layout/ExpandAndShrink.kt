package app.lawnchair.ui.preferences.components.layout

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Animates the appearance and disappearance of [content] via an expanding and shrinking animation, respectively.
 * @param visible Defines whether the content should be visible
 * @param content Content to appear or disappear based on the value of [visible]
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpandAndShrink(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    val motionScheme = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = if (animationsEnabled) {
            expandVertically(animationSpec = motionScheme.defaultSpatialSpec()) +
                fadeIn(animationSpec = motionScheme.defaultEffectsSpec())
        } else {
            EnterTransition.None
        },
        exit = if (animationsEnabled) {
            shrinkVertically(animationSpec = motionScheme.defaultSpatialSpec()) +
                fadeOut(animationSpec = motionScheme.defaultEffectsSpec())
        } else {
            ExitTransition.None
        },
        content = content,
        modifier = modifier,
    )
}
