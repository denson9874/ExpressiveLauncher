package app.lawnchair.ui.preferences.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lawnchair.FeedBridge
import app.lawnchair.feed.ExpressiveFeedSetup
import app.lawnchair.feed.ExpressiveFeedSetup.Kind
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import com.android.launcher3.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the user-facing setup; the included service never has a launcher icon or a feed Activity. */
@Composable
fun ExpressiveFeedPreferences() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val enabled = preferenceManager2().enableFeed.getAdapter()
    val provider = preferenceManager().feedProvider.getAdapter()
    var status by remember { mutableStateOf<ExpressiveFeedSetup.Status?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var setupRequested by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(refresh) {
        status = withContext(Dispatchers.IO) { ExpressiveFeedSetup.inspect(context) }
    }
    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart in setOf(
                        FeedBridge.GOOGLE_APP_PACKAGE,
                        FeedBridge.FIRST_PARTY_FEED_PACKAGE,
                    )
                ) {
                    refresh++
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch {
            // Installer result codes differ by Android version. Verify the installed package itself.
            val installed = withContext(Dispatchers.IO) { ExpressiveFeedSetup.inspect(context) }
            status = installed
            if (setupRequested && installed.kind == Kind.READY) {
                provider.onChange(FeedBridge.FIRST_PARTY_FEED_PACKAGE)
                enabled.onChange(true)
                message = null
            } else if (setupRequested) {
                message = R.string.expressive_feed_setup_cancelled
            }
            setupRequested = false
            refresh++
        }
    }
    val install: () -> Unit = {
        scope.launch {
            busy = true
            message = null
            try {
                val prepared = withContext(Dispatchers.IO) { ExpressiveFeedSetup.prepareInstall(context) }
                installer.launch(prepared.intent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ExpressiveFeedSetup", "Unable to prepare Discover support", error)
                setupRequested = false
                message = R.string.expressive_feed_setup_failed
            } finally {
                busy = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (setupRequested && ExpressiveFeedSetup.canRequestPackageInstalls(context)) {
            install()
        } else {
            setupRequested = false
            message = R.string.expressive_feed_permission_needed
        }
    }
    val beginSetup: () -> Unit = {
        setupRequested = true
        message = null
        try {
            if (ExpressiveFeedSetup.canRequestPackageInstalls(context)) {
                install()
            } else {
                permission.launch(ExpressiveFeedSetup.installationPermissionIntent(context))
            }
        } catch (error: RuntimeException) {
            Log.w("ExpressiveFeedSetup", "Unable to open install settings", error)
            setupRequested = false
            message = R.string.expressive_feed_setup_failed
        }
    }
    val openDetails: (String) -> Unit = { packageName ->
        try {
            context.startActivity(ExpressiveFeedSetup.appDetailsIntent(packageName))
        } catch (error: RuntimeException) {
            Log.w("ExpressiveFeedSetup", "Unable to open app details", error)
            message = R.string.expressive_feed_setup_failed
        }
    }
    val kind = status?.kind
    // Resolve migrated Lawnfeed/Pixel/Google selections exactly as the active overlay does.
    val effectiveProvider = remember(status, refresh, provider.state.value) {
        FeedBridge.getInstance(context).resolveConnection()?.packageName
    }
    val otherProvidersAvailable = remember(refresh) {
        FeedBridge.getAvailableProviders(context).any {
            it.packageName != FeedBridge.FIRST_PARTY_FEED_PACKAGE && it.packageName != FeedBridge.GOOGLE_APP_PACKAGE
        }
    }
    PreferenceGroup(heading = stringResource(R.string.expressive_feed_title)) {
        if (kind == Kind.READY || kind == Kind.UPDATE_AVAILABLE) {
            SwitchPreference(
                checked = enabled.state.value && effectiveProvider == FeedBridge.FIRST_PARTY_FEED_PACKAGE,
                onCheckedChange = {
                    if (it) provider.onChange(FeedBridge.FIRST_PARTY_FEED_PACKAGE)
                    enabled.onChange(it)
                },
                label = stringResource(R.string.expressive_feed_show),
                description = stringResource(
                    if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                        R.string.expressive_feed_swipe_left
                    } else {
                        R.string.expressive_feed_swipe_right
                    },
                ),
            )
        }
        when (kind) {
            null -> FeedSetupAction(R.string.expressive_feed_checking, enabled = false)
            Kind.HELPER_MISSING, Kind.UPDATE_AVAILABLE -> FeedSetupAction(
                title = if (busy) R.string.expressive_feed_preparing else if (kind == Kind.UPDATE_AVAILABLE) {
                    R.string.expressive_feed_update
                } else {
                    R.string.expressive_feed_setup
                },
                description = if (kind == Kind.UPDATE_AVAILABLE) R.string.expressive_feed_update_description else R.string.expressive_feed_setup_description,
                enabled = !busy,
                onClick = beginSetup,
            )
            Kind.GOOGLE_MISSING -> FeedSetupAction(
                R.string.expressive_feed_google_install,
                R.string.expressive_feed_google_description,
            ) {
                val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${FeedBridge.GOOGLE_APP_PACKAGE}"))
                try {
                    context.startActivity(store)
                } catch (_: RuntimeException) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${FeedBridge.GOOGLE_APP_PACKAGE}")))
                    } catch (_: RuntimeException) {
                        message = R.string.expressive_feed_setup_failed
                    }
                }
            }
            Kind.GOOGLE_DISABLED -> FeedSetupAction(R.string.expressive_feed_google_enable, R.string.expressive_feed_google_description) {
                openDetails(FeedBridge.GOOGLE_APP_PACKAGE)
            }
            Kind.HELPER_DISABLED -> FeedSetupAction(R.string.expressive_feed_support_enable, R.string.expressive_feed_support_disabled) {
                openDetails(FeedBridge.FIRST_PARTY_FEED_PACKAGE)
            }
            Kind.HELPER_INCOMPATIBLE -> FeedSetupAction(R.string.expressive_feed_support_attention, R.string.expressive_feed_support_incompatible) {
                openDetails(FeedBridge.FIRST_PARTY_FEED_PACKAGE)
            }
            Kind.HELPER_UNAVAILABLE -> FeedSetupAction(R.string.expressive_feed_support_attention, R.string.expressive_feed_support_unavailable) {
                openDetails(FeedBridge.FIRST_PARTY_FEED_PACKAGE)
            }
            Kind.BUNDLE_INVALID -> FeedSetupAction(R.string.expressive_feed_retry, R.string.expressive_feed_setup_unavailable) { refresh++ }
            Kind.READY -> Unit
        }
        message?.let {
            PreferenceTemplate(title = { Text(stringResource(it)) })
        }
    }
    // Preserve controls for compatible custom providers already installed by the user.
    if (otherProvidersAvailable) {
        PreferenceGroup(heading = stringResource(R.string.expressive_feed_other_feeds)) {
            if (effectiveProvider != null && effectiveProvider != FeedBridge.FIRST_PARTY_FEED_PACKAGE &&
                effectiveProvider != FeedBridge.GOOGLE_APP_PACKAGE
            ) {
                SwitchPreference(adapter = enabled, label = stringResource(R.string.expressive_feed_show_selected))
            }
            key(refresh) { FeedPreference() }
        }
    }
}

@Composable
private fun FeedSetupAction(
    title: Int,
    description: Int? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    PreferenceTemplate(
        title = { Text(stringResource(title)) },
        description = description?.let { { Text(stringResource(it)) } },
        enabled = enabled,
        onClick = onClick,
    )
}
