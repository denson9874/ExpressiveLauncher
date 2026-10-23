/*
 * Copyright 2026, Lawnchair
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

package app.lawnchair.gestures.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.ui.util.addIf
import app.lawnchair.util.ProvideLifecycleState
import java.security.MessageDigest

class LawnchairShortcutActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LawnchairTheme {
                EdgeToEdge()
                val windowSizeClass = calculateWindowSizeClass(this)

                val isExpandedScreen =
                    windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded &&
                        windowSizeClass.heightSizeClass in
                        setOf(WindowHeightSizeClass.Expanded, WindowHeightSizeClass.Medium)

                ProvideLifecycleState {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier.addIf(isExpandedScreen) { requiredWidth(640.dp) },
                        ) {
                            val context = LocalContext.current

                            @Suppress("DEPRECATION")
                            val resultReceiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
                            CreateActionsScreen(
                                onSelect = {
                                    if (resultReceiver != null) {
                                        val bundle = Bundle().apply {
                                            putString(EXTRA_HANDLER, GestureHandlerConfig.toString(it))
                                        }
                                        resultReceiver.send(RESULT_OK, bundle)
                                    } else {
                                        saveChanges(context, it)
                                    }
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveChanges(context: Context, selectedHandler: GestureHandlerConfig) {
        val shortcutManager =
            ContextCompat.getSystemService(applicationContext, ShortcutManager::class.java)
                ?: return

        val shortcutInfo = ShortcutInfo.Builder(
            context,
            gestureShortcutId(selectedHandler),
        )
            .apply {
                setShortLabel(selectedHandler.getDisplayLabel(context))
                setIcon(selectedHandler.getIcon(context))
                setIntent(
                    Intent(context, RunHandlerActivity::class.java).apply {
                        action = START_ACTION
                        putExtra(EXTRA_HANDLER, GestureHandlerConfig.toString(selectedHandler))
                    },
                )
            }.build()

        val intent = shortcutManager.createShortcutResultIntent(shortcutInfo)
        setResult(RESULT_OK, intent)
    }

    companion object {
        const val START_ACTION = "app.lawnchair.START_ACTION"
        const val EXTRA_HANDLER = "app.lawnchair.EXTRA_HANDLER"
        const val EXTRA_RESULT_RECEIVER = "app.lawnchair.EXTRA_RESULT_RECEIVER"
        const val GESTURE_SHORTCUT_ID_PREFIX = "gesture:"

        /**
         * The pinned shortcut ID for [handler]: the prefix plus a SHA-256 of its JSON form.
         *
         * The ID used to embed the handler's toString, and an "Open app" handler's ComponentKey
         * put '#' in it, which made a renamed shortcut's label key unreadable. The JSON form is the
         * stored format, so the ID stays the same across releases and re-pinning keeps it.
         * Shortcuts pinned earlier keep their old ID.
         */
        fun gestureShortcutId(handler: GestureHandlerConfig): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(GestureHandlerConfig.toString(handler).toByteArray())
            return "$GESTURE_SHORTCUT_ID_PREFIX:" + digest.joinToString("") { "%02x".format(it) }
        }

        fun shouldSkipShortcutBadge(context: Context, si: ShortcutInfo): Boolean {
            val value = context.packageName == si.`package` &&
                si.id.startsWith(GESTURE_SHORTCUT_ID_PREFIX)

            return value
        }
    }
}
