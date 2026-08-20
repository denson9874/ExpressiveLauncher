/*
 * Copyright 2021, Lawnchair
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

package app.lawnchair.ui.preferences.components.controls

import android.animation.ValueAnimator
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.ui.util.preview.PreviewLawnchair
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceCategory(
    label: String,
    @DrawableRes iconResource: Int,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    description: String? = null,
) {
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(LocalContext.current)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    val glyphScale by animateFloatAsState(
        targetValue = when {
            !animationsEnabled -> 1f
            isPressed -> 1.12f
            isSelected -> 1.06f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "preference category glyph scale",
    )
    val glyphRotation by animateFloatAsState(
        targetValue = if (animationsEnabled && isPressed) 6f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "preference category glyph rotation",
    )
    PreferenceTemplate(
        title = {
            Text(
                text = label,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        modifier = modifier
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp) else Color.Transparent,
            ),
        description = description?.let { { Text(text = description) } },
        startWidget = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    painter = painterResource(id = iconResource),
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = glyphScale
                            scaleY = glyphScale
                            rotationZ = glyphRotation
                        },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = {
            mMSDLPlayerWrapper.playToken(MSDLToken.TAP_LOW_EMPHASIS)
            onNavigate()
        },
        interactionSource = interactionSource,
    )
}

@PreviewLawnchair
@Composable
private fun PreferenceCategoryPreview() {
    LawnchairTheme {
        PreferenceCategory(
            label = "Example",
            description = "Example description here",
            iconResource = R.drawable.ic_general,
            onNavigate = {},
        )
    }
}
