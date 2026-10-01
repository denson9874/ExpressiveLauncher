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

package app.lawnchair.smartspace.provider

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.edit
import app.lawnchair.pro.ProManager
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.util.broadcastReceiverFlow
import com.android.launcher3.R
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Quotes, encouragement, holidays and, for Cakey Edition, Laura's greetings and special days.
 * The message is shown on the date card's second line; see [attachGlanceMessage].
 */
class GlanceMessagesProvider(context: Context) :
    SmartspaceDataSource(
        context,
        R.string.smartspace_glance_messages,
        { smartspaceGlanceMessages },
        providerDescription = R.string.smartspace_glance_messages_description,
    ) {

    // Resolved lazily on the collector's (main) thread.
    private val isCakey: Flow<Boolean> = flow { emitAll(ProManager.INSTANCE.get(context).isCakey) }

    // TIME_TICK arrives every minute while the screen is on, so the message is current as soon as
    // the user looks, even after the device slept through a boundary.
    private val clock: Flow<Unit> = broadcastReceiverFlow(
        context,
        IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        },
    )
        .map { }
        .onStart { emit(Unit) }

    override val internalTargets: Flow<List<SmartspaceTarget>> = combine(isCakey, clock) { cakey, _ ->
        selectGlanceMessage(LocalDateTime.now(), cakey, region())
    }
        .distinctUntilChanged()
        .map { listOf(glanceMessageTarget(context.getString(it.textResId))) }

    /** Cakey Edition turns daily messages on once. Laura can still switch them off afterwards. */
    suspend fun enableOnceForCakey() {
        ProManager.INSTANCE.get(context).isCakey.first { it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CAKEY_ENABLED_ONCE, false)) return
        enabledPref.set(true)
        prefs.edit { putBoolean(KEY_CAKEY_ENABLED_ONCE, true) }
    }

    private fun region(): String = context.resources.configuration.locales[0].country
        .ifEmpty { Locale.getDefault().country }

    private companion object {
        const val PREFS_NAME = "expressive_glance_messages"
        const val KEY_CAKEY_ENABLED_ONCE = "cakey_enabled_once"
    }
}

internal const val GLANCE_MESSAGE_TARGET_ID = "glanceMessage"

/** Rendered as its own card only when there is no date card to carry it. */
internal fun glanceMessageTarget(text: CharSequence) = SmartspaceTarget(
    id = GLANCE_MESSAGE_TARGET_ID,
    headerAction = SmartspaceAction(id = "glanceMessageAction", title = text),
    featureType = SmartspaceTarget.FeatureType.FEATURE_TIPS,
    glanceMessage = text,
)

/** Moves the message onto the date card, so it reads under the date instead of as another page. */
internal fun attachGlanceMessage(targets: List<SmartspaceTarget>): List<SmartspaceTarget> {
    val messageIndex = targets.indexOfFirst { it.id == GLANCE_MESSAGE_TARGET_ID }
    val dateIndex = targets.indexOfFirst {
        it.featureType == SmartspaceTarget.FeatureType.FEATURE_WEATHER
    }
    if (messageIndex < 0 || dateIndex < 0) return targets
    val message = targets[messageIndex].glanceMessage
    return targets.mapIndexedNotNull { index, target ->
        when (index) {
            messageIndex -> null
            dateIndex -> target.copy(glanceMessage = message)
            else -> target
        }
    }
}
