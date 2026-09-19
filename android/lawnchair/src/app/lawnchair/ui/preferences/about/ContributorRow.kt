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

package app.lawnchair.ui.preferences.about

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.lawnchair.ui.placeholder.PlaceholderHighlight
import app.lawnchair.ui.placeholder.fade
import app.lawnchair.ui.placeholder.placeholder
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import coil.compose.SubcomposeAsyncImage
import com.android.launcher3.R

/**
 * Displays a row with contributor information.
 *
 * @param member The [TeamMember] data for the contributor.
 * @param modifier Optional [Modifier] for customization.
 */
@Composable
fun ContributorRow(
    member: TeamMember,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val statusText = when (member.status) {
        ContributorStatus.Active -> stringResource(R.string.contributor_status_active)
        ContributorStatus.Idle -> ""
    }
    val roleText = stringResource(member.role.descriptionResId)
    val description = if (statusText.isBlank()) roleText else "$roleText • $statusText"
    val photoModel: Any = member.photoResId
        ?: requireNotNull(member.photoUrl) { "A contributor must provide a photo resource or URL" }

    ContributorRow(
        name = member.name,
        description = description,
        photoModel = photoModel,
        onClick = {
            val webpage = member.socialUrl.toUri()
            val intent = Intent(Intent.ACTION_VIEW, webpage)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }
        },
        modifier = modifier,
    )
}

/**
 * Displays a row with contributor information.
 *
 * @param name The name of the contributor.
 * @param description The contributor's role and optional activity status.
 * @param photoModel A Coil-supported bundled resource or remote photo URL.
 * @param onClick The action to perform when the row is clicked.
 * @param modifier Optional [Modifier] for customization.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContributorRow(
    name: String,
    description: String,
    photoModel: Any,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceTemplate(
        title = { Text(text = name) },
        modifier = modifier,
        onClick = onClick,
        description = {
            Text(
                text = description,
            )
        },
        startWidget = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                SubcomposeAsyncImage(
                    model = photoModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .placeholder(
                                    visible = true,
                                    highlight = PlaceholderHighlight.fade(),
                                ),
                        )
                    },
                )
            }
        },
    )
}
