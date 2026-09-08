package app.lawnchair.smartspace.provider

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.Smartspace
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

@LauncherAppSingleton
class SmartspaceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val dataSources = listOf(
        SmartspaceWidgetReader(context),
        BatteryStatusProvider(context),
        TorchProvider(context),
        NowPlayingProvider(context),
        OnboardingProvider(context),
    )

    private val state = dataSources
        .map { it.targets }
        .reduce { acc, flow -> flow.combine(acc) { a, b -> a + b } }
        .shareIn(
            scope,
            SharingStarted.WhileSubscribed(),
            replay = 1,
        )
    val targets = state
        .map {
            val targets = if (it.requiresSetup.isNotEmpty()) {
                listOf(setupTarget) + it.targets
            } else {
                it.targets
            }
            applyExpressiveSmartspaceTargetPolicy(targets, BuildConfig.IS_EXPRESSIVE_PRODUCT)
        }
    val previewTargets = state
        .map {
            applyExpressiveSmartspaceTargetPolicy(
                it.targets,
                BuildConfig.IS_EXPRESSIVE_PRODUCT,
            )
        }

    private val setupCoordinator = SmartspaceSetupCoordinator<SmartspaceDataSource>(
        isEnabled = { it.enabledPref.get().first() },
        requiresSetup = { it.requiresSetup() },
        onSetupDone = { it.onSetupDone() },
    )

    private val setupTarget = SmartspaceTarget(
        id = "smartspaceSetup",
        headerAction = SmartspaceAction(
            id = "smartspaceSetupAction",
            title = context.getString(R.string.smartspace_requires_setup),
            intent = PreferenceActivity.createIntent(context, Smartspace),
        ),
        score = 999f,
        featureType = SmartspaceTarget.FeatureType.FEATURE_TIPS,
    )

    suspend fun startSetup(activity: Activity, lifecycle: Lifecycle) {
        setupCoordinator.collectRequests(
            requests = state.map { it.requiresSetup },
            lifecycle = lifecycle,
            startSetup = { it.startSetup(activity) },
        )
    }

    override fun close() {
        // The provider is application-scoped. Cancelling its sharing scope releases every
        // data-source subscription and registered receiver when the app graph is torn down.
        scope.cancel()
    }

    companion object {
        @JvmField val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getSmartspaceProvider)
    }
}

/**
 * Keeps Expressive's local Smartspace fallback useful from the first frame. Pixel Launcher leads
 * with the date; onboarding and setup prompts must not displace that glanceable content. Weather
 * targets use the date card layout, so promoting them preserves live weather when it is available
 * and naturally falls back to the date-only target when it is not.
 */
internal fun applyExpressiveSmartspaceTargetPolicy(
    targets: List<SmartspaceTarget>,
    isExpressiveProduct: Boolean,
): List<SmartspaceTarget> {
    if (!isExpressiveProduct) return targets

    return targets
        .asSequence()
        .filterNot { it.featureType == SmartspaceTarget.FeatureType.FEATURE_ONBOARDING }
        .map {
            if (it.featureType == SmartspaceTarget.FeatureType.FEATURE_WEATHER) {
                it.copy(score = EXPRESSIVE_DATE_SCORE)
            } else {
                it
            }
        }
        .toList()
}

private const val EXPRESSIVE_DATE_SCORE = SmartspaceScores.SCORE_ONBOARDING + 1f
