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

package app.lawnchair.ui.preferences.pro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val CAKEY_PARTICLE_COUNT = 64

private val CakeyPalette = listOf(
    Color(0xFFFF4081), // Strawberry Pink
    Color(0xFFFF80AB), // Soft Pink
    Color(0xFFE040FB), // Sweet Violet
    Color(0xFFFFD54F), // Warm Vanilla Gold
    Color(0xFFFFF0F5), // Lavender Blush
    Color(0xFFFF8A80), // Peach Coral
)

@Composable
fun CakeyCelebrationDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val context = LocalContext.current
    val msdlPlayer = remember { MSDLPlayerWrapper.INSTANCE.get(context) }

    LaunchedEffect(Unit) {
        msdlPlayer.playToken(MSDLToken.SUCCESS)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2_200, easing = FastOutSlowInEasing),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = true,
            modifier = modifier.fillMaxSize(),
            enter = fadeIn(tween(260)) + scaleIn(initialScale = 0.90f),
            exit = fadeOut(tween(220)) + scaleOut(targetScale = 1.05f),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC150A10)),
            ) {
                // Festive Confetti, Heart & Sparkle Particle Canvas
                Canvas(Modifier.fillMaxSize()) {
                    val p = progress.value
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val maxRadius = size.minDimension * 0.75f

                    repeat(CAKEY_PARTICLE_COUNT) { i ->
                        val angle = (2.0 * PI * i / CAKEY_PARTICLE_COUNT) + (i % 7) * 0.22
                        val speed = 0.5f + (i % 9) * 0.07f
                        val radius = maxRadius * p * speed
                        val x = centerX + cos(angle).toFloat() * radius
                        val y = centerY + sin(angle).toFloat() * radius - (size.height * 0.14f * p * p)
                        val alpha = (1f - p * 0.75f).coerceIn(0f, 1f)
                        val color = CakeyPalette[i % CakeyPalette.size].copy(alpha = alpha)

                        when (i % 4) {
                            0, 1 -> {
                                // Shimmering circular confetti
                                drawCircle(
                                    color = color,
                                    radius = 6f + (i % 5) * 3f,
                                    center = Offset(x, y),
                                )
                            }
                            2 -> {
                                // Fluttering ribbon lines
                                val tail = 14f + (i % 6) * 4f
                                drawLine(
                                    color = color,
                                    start = Offset(x, y),
                                    end = Offset(
                                        x - cos(angle).toFloat() * tail,
                                        y - sin(angle).toFloat() * tail,
                                    ),
                                    strokeWidth = 7f,
                                )
                            }
                            3 -> {
                                // Petite accent diamonds
                                drawCircle(
                                    color = color,
                                    radius = 4f,
                                    center = Offset(x, y),
                                )
                            }
                        }
                    }
                }

                // Central Romantic Card
                val entrance = (progress.value / 0.22f).coerceIn(0f, 1f)
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 14.dp,
                    shadowElevation = 24.dp,
                    modifier = Modifier
                        .padding(24.dp)
                        .width(420.dp)
                        .graphicsLayer {
                            this.alpha = entrance
                            scaleX = 0.78f + entrance * 0.22f
                            scaleY = 0.78f + entrance * 0.22f
                        },
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    ) {
                        // Pulsing Cupcake & Heart Emblem
                        val pulse = 1f + sin(progress.value * PI.toFloat() * 6f) * 0.06f
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(96.dp)
                                .graphicsLayer {
                                    scaleX = pulse
                                    scaleY = pulse
                                }
                                .clip(CircleShape)
                                .drawBehind {
                                    drawCircle(
                                        color = lerp(CakeyPalette[0], CakeyPalette[1], progress.value).copy(alpha = 0.22f),
                                        radius = size.minDimension * 0.65f,
                                    )
                                },
                        ) {
                            Text(
                                text = "🍰",
                                fontSize = 52.sp,
                            )
                        }

                        Spacer(Modifier.height(18.dp))

                        // Title
                        Text(
                            text = stringResource(R.string.cakey_celebration_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(Modifier.height(16.dp))

                        // Heartfelt Message Card
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.cakey_celebration_message),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontStyle = FontStyle.Italic,
                                        lineHeight = 22.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // Action Button
                        Button(
                            onClick = {
                                msdlPlayer.playToken(MSDLToken.TAP_HIGH_EMPHASIS)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CakeyPalette[0],
                                contentColor = Color.White,
                            ),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.cakey_celebration_button),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
