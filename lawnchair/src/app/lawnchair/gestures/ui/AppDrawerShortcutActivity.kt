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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import app.lawnchair.LawnchairLauncher
import app.lawnchair.gestures.config.GestureHandlerConfig
import com.android.launcher3.R

class AppDrawerShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent != null && intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            val resultIntent = createShortcutResultIntent(this)
            setResult(RESULT_OK, resultIntent)
        } else {
            startActivity(
                Intent(this, LawnchairLauncher::class.java).apply {
                    action = LawnchairShortcutActivity.START_ACTION
                    putExtra(
                        LawnchairShortcutActivity.EXTRA_HANDLER,
                        GestureHandlerConfig.toString(GestureHandlerConfig.OpenAppDrawer),
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }
        finish()
    }

    companion object {
        fun createShortcutInfo(context: Context): ShortcutInfo {
            val handler = GestureHandlerConfig.OpenAppDrawer
            val icon = try {
                handler.getIcon(context)
            } catch (_: Throwable) {
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_apps)
            }
            return ShortcutInfo.Builder(
                context,
                "${LawnchairShortcutActivity.GESTURE_SHORTCUT_ID_PREFIX}:$handler",
            )
                .setShortLabel(context.getString(R.string.app_drawer_label))
                .setIcon(icon)
                .setIntent(
                    Intent(context, RunHandlerActivity::class.java).apply {
                        action = LawnchairShortcutActivity.START_ACTION
                        putExtra(
                            LawnchairShortcutActivity.EXTRA_HANDLER,
                            GestureHandlerConfig.toString(handler),
                        )
                    },
                )
                .build()
        }

        fun createShortcutResultIntent(context: Context): Intent? {
            val shortcutManager =
                ContextCompat.getSystemService(context, ShortcutManager::class.java)
                    ?: return null
            return shortcutManager.createShortcutResultIntent(createShortcutInfo(context))
        }

        fun pinAppDrawerShortcut(context: Context): Boolean {
            val shortcutManager =
                ContextCompat.getSystemService(context, ShortcutManager::class.java)
                    ?: return false
            if (!shortcutManager.isRequestPinShortcutSupported) return false
            return shortcutManager.requestPinShortcut(createShortcutInfo(context), null)
        }
    }
}
