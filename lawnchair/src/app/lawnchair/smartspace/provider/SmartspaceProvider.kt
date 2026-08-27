package app.lawnchair.smartspace.provider

import android.app.Activity
import android.content.Context
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.Smartspace
import app.lawnchair.util.dropWhileBusy
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
            if (it.requiresSetup.isNotEmpty()) {
                listOf(setupTarget) + it.targets
            } else {
                it.targets
            }
        }
    val previewTargets = state
        .map { it.targets }

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

    suspend fun startSetup(activity: Activity) {
        state
            .map { it.requiresSetup }
            .dropWhileBusy()
            .collect { sources ->
                sources.forEach {
                    it.startSetup(activity)
                    it.onSetupDone()
                }
            }
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
