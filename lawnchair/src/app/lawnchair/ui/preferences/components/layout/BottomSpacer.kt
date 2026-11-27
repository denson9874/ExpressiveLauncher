package app.lawnchair.ui.preferences.components.layout

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Creates a spacer that respects the [navigation bars][WindowInsets.Companion.navigationBars] and IME.
 */
@Composable
fun BottomSpacer(
    modifier: Modifier = Modifier,
) {
    Spacer(modifier = modifier.navigationBarsPadding().imePadding())
}
