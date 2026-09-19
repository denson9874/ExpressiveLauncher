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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.launcher3.R
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

internal const val ABOUT_DAILY_SPARK_CARD_TAG = "about-daily-spark-card"
internal const val ABOUT_DAILY_RIDDLE_ANSWER_TAG = "about-daily-riddle-answer"

/** One complete five-tap conversation. Resource IDs are referenced directly for R8 safety. */
internal data class DailySnarkPack(
    val id: String,
    val messageResIds: List<Int>,
) {
    init {
        require(messageResIds.size == ABOUT_EASTER_EGG_TAP_COUNT) {
            "A daily snark pack must contain exactly $ABOUT_EASTER_EGG_TAP_COUNT messages"
        }
    }
}

internal data class DailyRiddle(
    val id: String,
    val questionResId: Int,
    val answerResId: Int,
)

internal data class DailyQuote(
    val id: String,
    val textResId: Int,
    val attributionResId: Int,
)

internal data class DailyAboutContent(
    val localDate: LocalDate,
    val snarkPack: DailySnarkPack,
    val riddle: DailyRiddle,
    val quote: DailyQuote,
) {
    val dayKey: Long = localDate.toEpochDay()
}

/**
 * Content is deliberately bundled with the app. Apart from making the feature work offline, direct
 * resource references prevent release resource shrinking from mistaking this content for unused
 * debug data.
 */
internal val ABOUT_DAILY_SNARK_PACKS = listOf(
    DailySnarkPack(
        id = "gentle-warning",
        messageResIds = listOf(
            R.string.about_easter_egg_hint_1,
            R.string.about_easter_egg_hint_2,
            R.string.about_easter_egg_hint_3,
            R.string.about_easter_egg_hint_4,
            R.string.about_easter_egg_hint_5,
        ),
    ),
    DailySnarkPack(
        id = "consequences",
        messageResIds = listOf(
            R.string.about_daily_snark_2_1,
            R.string.about_daily_snark_2_2,
            R.string.about_daily_snark_2_3,
            R.string.about_daily_snark_2_4,
            R.string.about_daily_snark_2_5,
        ),
    ),
    DailySnarkPack(
        id = "management",
        messageResIds = listOf(
            R.string.about_daily_snark_3_1,
            R.string.about_daily_snark_3_2,
            R.string.about_daily_snark_3_3,
            R.string.about_daily_snark_3_4,
            R.string.about_daily_snark_3_5,
        ),
    ),
    DailySnarkPack(
        id = "peer-review",
        messageResIds = listOf(
            R.string.about_daily_snark_4_1,
            R.string.about_daily_snark_4_2,
            R.string.about_daily_snark_4_3,
            R.string.about_daily_snark_4_4,
            R.string.about_daily_snark_4_5,
        ),
    ),
    DailySnarkPack(
        id = "plausible-deniability",
        messageResIds = listOf(
            R.string.about_daily_snark_5_1,
            R.string.about_daily_snark_5_2,
            R.string.about_daily_snark_5_3,
            R.string.about_daily_snark_5_4,
            R.string.about_daily_snark_5_5,
        ),
    ),
    DailySnarkPack(
        id = "legal-team",
        messageResIds = listOf(
            R.string.about_daily_snark_6_1,
            R.string.about_daily_snark_6_2,
            R.string.about_daily_snark_6_3,
            R.string.about_daily_snark_6_4,
            R.string.about_daily_snark_6_5,
        ),
    ),
    DailySnarkPack(
        id = "encore",
        messageResIds = listOf(
            R.string.about_daily_snark_7_1,
            R.string.about_daily_snark_7_2,
            R.string.about_daily_snark_7_3,
            R.string.about_daily_snark_7_4,
            R.string.about_daily_snark_7_5,
        ),
    ),
)

internal val ABOUT_DAILY_RIDDLES = listOf(
    DailyRiddle("map", R.string.about_daily_riddle_1, R.string.about_daily_riddle_answer_1),
    DailyRiddle("towel", R.string.about_daily_riddle_2, R.string.about_daily_riddle_answer_2),
    DailyRiddle("keyboard", R.string.about_daily_riddle_3, R.string.about_daily_riddle_answer_3),
    DailyRiddle("stamp", R.string.about_daily_riddle_4, R.string.about_daily_riddle_answer_4),
    DailyRiddle("clock", R.string.about_daily_riddle_5, R.string.about_daily_riddle_answer_5),
    DailyRiddle("footsteps", R.string.about_daily_riddle_6, R.string.about_daily_riddle_answer_6),
    DailyRiddle("echo", R.string.about_daily_riddle_7, R.string.about_daily_riddle_answer_7),
)

internal val ABOUT_DAILY_QUOTES = listOf(
    DailyQuote(
        "attention-back",
        R.string.about_daily_quote_1,
        R.string.about_daily_quote_attribution,
    ),
    DailyQuote(
        "software-smile",
        R.string.about_daily_quote_2,
        R.string.about_daily_quote_attribution,
    ),
    DailyQuote(
        "clarity-kindness",
        R.string.about_daily_quote_3,
        R.string.about_daily_quote_attribution,
    ),
    DailyQuote(
        "make-room",
        R.string.about_daily_quote_4,
        R.string.about_daily_quote_attribution,
    ),
    DailyQuote(
        "obvious-shortcut",
        R.string.about_daily_quote_5,
        R.string.about_daily_quote_attribution,
    ),
    DailyQuote(
        "playful-memory",
        R.string.about_daily_quote_6,
        R.string.about_daily_quote_attribution,
    ),
    DailyQuote(
        "feels-like-yours",
        R.string.about_daily_quote_7,
        R.string.about_daily_quote_attribution,
    ),
)

/** A pure, deterministic selector: the same local date always yields the same content. */
internal fun dailyAboutContent(localDate: LocalDate): DailyAboutContent = DailyAboutContent(
    localDate = localDate,
    snarkPack = ABOUT_DAILY_SNARK_PACKS.rotatingValue(localDate, offset = 0),
    riddle = ABOUT_DAILY_RIDDLES.rotatingValue(localDate, offset = 2),
    quote = ABOUT_DAILY_QUOTES.rotatingValue(localDate, offset = 4),
)

private fun <T> List<T>.rotatingValue(localDate: LocalDate, offset: Long): T {
    require(isNotEmpty()) { "Daily content catalogs must not be empty" }
    val index = Math.floorMod(localDate.toEpochDay() + offset, size.toLong()).toInt()
    return get(index)
}

/** Uses the next local date boundary, so 23-hour and 25-hour daylight-saving days are safe. */
internal fun millisUntilNextLocalMidnight(now: Instant, zoneId: ZoneId): Long {
    val nextMidnight = now
        .atZone(zoneId)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zoneId)
        .toInstant()
    return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L)
}

/**
 * Saves a short-lived integer interaction together with the local day that produced it.
 *
 * `rememberSaveable(dayKey)` resets correctly while a composition is alive, but Compose restores a
 * process-death value before it can compare the old inputs. Keeping the day in the payload prevents
 * yesterday's partial easter-egg sequence from being applied to today's content.
 */
internal fun dailyScopedIntSaver(
    currentDayKey: Long,
    validValue: (Int) -> Boolean = { true },
): Saver<Int, LongArray> = Saver(
    save = { value -> longArrayOf(currentDayKey, value.toLong()) },
    restore = { saved ->
        if (saved.size != DAILY_SAVED_VALUE_SIZE || saved[0] != currentDayKey) {
            null
        } else {
            saved[1]
                .takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                ?.toInt()
                ?.takeIf(validValue)
        }
    },
)

/** Boolean companion to [dailyScopedIntSaver], using an explicit payload for Bundle safety. */
internal fun dailyScopedBooleanSaver(currentDayKey: Long): Saver<Boolean, LongArray> = Saver(
    save = { value -> longArrayOf(currentDayKey, if (value) 1L else 0L) },
    restore = { saved ->
        if (saved.size != DAILY_SAVED_VALUE_SIZE || saved[0] != currentDayKey) {
            null
        } else {
            when (saved[1]) {
                0L -> false
                1L -> true
                else -> null
            }
        }
    },
)

internal interface AboutDateSource {
    fun now(): Instant

    fun zoneId(): ZoneId
}

private object SystemAboutDateSource : AboutDateSource {
    override fun now(): Instant = Instant.now()

    // Read the system zone for every refresh. Caching systemDefaultZone would remain stale after
    // the user travels or changes the device time zone.
    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}

private fun AboutDateSource.currentContent(): DailyAboutContent {
    val currentZone = zoneId()
    return dailyAboutContent(now().atZone(currentZone).toLocalDate())
}

@Composable
internal fun rememberDailyAboutContent(
    dateSource: AboutDateSource = SystemAboutDateSource,
): DailyAboutContent {
    var content by remember(dateSource) { mutableStateOf(dateSource.currentContent()) }
    var scheduleGeneration by remember(dateSource) { mutableLongStateOf(0L) }
    val refreshContent = {
        val refreshedContent = dateSource.currentContent()
        if (refreshedContent.dayKey != content.dayKey) {
            content = refreshedContent
        }
        // A time or time-zone change can leave the local date unchanged while moving midnight.
        scheduleGeneration++
    }
    val currentRefreshContent by rememberUpdatedState(refreshContent)

    DailyAboutRefreshEffect(onRefresh = { currentRefreshContent() })

    LaunchedEffect(dateSource, content.dayKey, scheduleGeneration) {
        delay(
            millisUntilNextLocalMidnight(
                now = dateSource.now(),
                zoneId = dateSource.zoneId(),
            ) + MIDNIGHT_SETTLE_MILLIS,
        )
        currentRefreshContent()
    }

    return content
}

@Composable
private fun DailyAboutRefreshEffect(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    DisposableEffect(context, lifecycle) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                currentOnRefresh()
            }
        }
        var receiverRegistered = false

        fun registerReceiver() {
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
            }
        }

        fun unregisterReceiver() {
            if (receiverRegistered) {
                context.unregisterReceiver(receiver)
                receiverRegistered = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> registerReceiver()
                Lifecycle.Event.ON_RESUME -> currentOnRefresh()
                Lifecycle.Event.ON_STOP -> unregisterReceiver()
                else -> Unit
            }
        }

        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registerReceiver()
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            currentOnRefresh()
        }

        onDispose {
            lifecycle.removeObserver(observer)
            unregisterReceiver()
        }
    }
}

@Composable
internal fun DailySparkCard(
    content: DailyAboutContent,
    modifier: Modifier = Modifier,
) {
    val answerVisibilitySaver = remember(content.dayKey) {
        dailyScopedBooleanSaver(content.dayKey)
    }
    var answerVisible by rememberSaveable(
        content.dayKey,
        stateSaver = answerVisibilitySaver,
    ) { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ABOUT_DAILY_SPARK_CARD_TAG),
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ),
                )
                .padding(20.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.about_daily_spark_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.about_daily_spark_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Rounded.FormatQuote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(content.quote.textResId),
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                    )
                    Text(
                        text = stringResource(
                            R.string.about_daily_quote_attribution_format,
                            stringResource(content.quote.attributionResId),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            text = stringResource(R.string.about_daily_riddle_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = stringResource(content.riddle.questionResId),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AnimatedVisibility(visible = answerVisible) {
                        Text(
                            text = stringResource(
                                R.string.about_daily_riddle_answer_format,
                                stringResource(content.riddle.answerResId),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag(ABOUT_DAILY_RIDDLE_ANSWER_TAG),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { answerVisible = !answerVisible }) {
                            Icon(
                                imageVector = if (answerVisible) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(
                                    if (answerVisible) {
                                        R.string.about_daily_riddle_hide_answer
                                    } else {
                                        R.string.about_daily_riddle_reveal_answer
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MIDNIGHT_SETTLE_MILLIS = 250L
private const val DAILY_SAVED_VALUE_SIZE = 2
