/*
 * Copyright 2026, Expressive Launcher contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.about

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.launcher3.R
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

internal const val ABOUT_EASTER_EGG_TARGET_TAG = "about-easter-egg-target"
internal const val ABOUT_EASTER_EGG_CELEBRATION_TAG = "about-easter-egg-celebration"

internal data class AboutEasterEggTapResult(
    val nextTapCount: Int,
    @StringRes val messageResId: Int,
    val showCelebration: Boolean,
)

internal fun nextAboutEasterEggTap(
    currentTapCount: Int,
    dailyMessageResIds: List<Int>,
): AboutEasterEggTapResult {
    require(dailyMessageResIds.size == ABOUT_EASTER_EGG_TAP_COUNT) {
        "The About easter egg requires exactly $ABOUT_EASTER_EGG_TAP_COUNT messages"
    }
    val nextTap = currentTapCount.coerceIn(0, ABOUT_EASTER_EGG_TAP_COUNT - 1) + 1
    return AboutEasterEggTapResult(
        nextTapCount = if (nextTap == ABOUT_EASTER_EGG_TAP_COUNT) 0 else nextTap,
        messageResId = dailyMessageResIds[nextTap - 1],
        showCelebration = nextTap == ABOUT_EASTER_EGG_TAP_COUNT,
    )
}

@Composable
internal fun AboutThankYouCelebration(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val context = LocalContext.current
    val applicationIcon = remember(context) {
        context.packageManager.getApplicationIcon(context.applicationInfo)
    }
    val applicationIconPainter = rememberDrawablePainter(applicationIcon)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val focusRequester = remember { FocusRequester() }
    val celebrationTitle = stringResource(R.string.about_easter_egg_title)
    val particleColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.inversePrimary,
    )

    LaunchedEffect(visible) {
        if (visible) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 2_700, easing = FastOutSlowInEasing),
            )
            delay(900)
            currentOnDismiss()
        }
    }

    if (visible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LaunchedEffect(Unit) {
                // The dialog is a separate accessibility window; focus plus an assertive live
                // region ensures the thank-you is announced instead of leaving focus behind.
                focusRequester.requestFocus()
            }

            AnimatedVisibility(
                visible = true,
                modifier = modifier.fillMaxSize(),
                enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.88f),
                exit = fadeOut(tween(320)) + scaleOut(targetScale = 1.08f),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
                        .focusRequester(focusRequester)
                        .clickable(
                            onClickLabel = stringResource(R.string.about_easter_egg_dismiss),
                            onClick = onDismiss,
                        )
                        .semantics {
                            paneTitle = celebrationTitle
                            liveRegion = LiveRegionMode.Assertive
                        }
                        .testTag(ABOUT_EASTER_EGG_CELEBRATION_TAG),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val animatedProgress = progress.value
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val maxRadius = size.minDimension * 0.7f
                        repeat(PARTICLE_COUNT) { index ->
                            val angle = (2.0 * PI * index / PARTICLE_COUNT) +
                                (index % 5) * 0.17
                            val speed = 0.55f + (index % 7) * 0.065f
                            val radius = maxRadius * animatedProgress * speed
                            val x = centerX + cos(angle).toFloat() * radius
                            val y = centerY + sin(angle).toFloat() * radius -
                                size.height * 0.12f * animatedProgress * animatedProgress
                            val alpha = (1f - animatedProgress * 0.82f).coerceIn(0f, 1f)
                            val color = particleColors[index % particleColors.size].copy(alpha = alpha)
                            if (index % 3 == 0) {
                                drawCircle(
                                    color = color,
                                    radius = 5f + (index % 4) * 3f,
                                    center = androidx.compose.ui.geometry.Offset(x, y),
                                )
                            } else {
                                val tail = 12f + (index % 5) * 5f
                                drawLine(
                                    color = color,
                                    start = androidx.compose.ui.geometry.Offset(x, y),
                                    end = androidx.compose.ui.geometry.Offset(
                                        x - cos(angle).toFloat() * tail,
                                        y - sin(angle).toFloat() * tail,
                                    ),
                                    strokeWidth = 7f,
                                )
                            }
                        }
                    }

                    val entrance = (progress.value / 0.18f).coerceIn(0f, 1f)
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 12.dp,
                        shadowElevation = 18.dp,
                        modifier = Modifier
                            .padding(28.dp)
                            .width(420.dp)
                            .graphicsLayer {
                                alpha = entrance
                                scaleX = 0.72f + entrance * 0.28f
                                scaleY = 0.72f + entrance * 0.28f
                                rotationZ = (1f - entrance) * -8f
                            },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 30.dp, vertical = 36.dp),
                        ) {
                            val pulse = 1f + sin(progress.value * PI.toFloat() * 7f) * 0.08f
                            Image(
                                painter = applicationIconPainter,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(112.dp)
                                    .graphicsLayer {
                                        rotationZ = progress.value * 720f
                                        scaleX = pulse
                                        scaleY = pulse
                                    }
                                    .clip(CircleShape)
                                    .drawBehind {
                                        drawCircle(
                                            color = lerp(
                                                particleColors[0],
                                                particleColors[1],
                                                progress.value,
                                            ).copy(alpha = 0.26f),
                                            radius = size.minDimension * 0.62f,
                                        )
                                    },
                            )
                            Spacer(Modifier.size(24.dp))
                            Text(
                                text = stringResource(R.string.about_easter_egg_title),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                text = stringResource(R.string.about_easter_egg_thank_you),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal const val ABOUT_EASTER_EGG_TAP_COUNT = 5
private const val PARTICLE_COUNT = 52
