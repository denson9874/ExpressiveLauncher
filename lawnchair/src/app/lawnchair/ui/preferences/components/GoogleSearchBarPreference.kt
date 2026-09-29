package app.lawnchair.ui.preferences.components

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.hotseat.GoogleSearchHotseat
import app.lawnchair.hotseat.LawnchairHotseat
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.qsb.GoogleSearchBarSetup
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import com.android.launcher3.R

/**
 * Free switch for the Google app's own dock search bar (Android 17 QPR2 Pixel parity). Android's
 * one-time widget permission is requested here, when the user asks for the bar, never on Home.
 */
@Composable
fun GoogleSearchBarPreference() {
    val context = LocalContext.current
    if (!GoogleSearchHotseat.isAvailable(context)) return
    val hotseatMode = preferenceManager2().hotseatMode.getAdapter()
    var pendingWidgetId by rememberSaveable { mutableIntStateOf(-1) }
    val askAndroid = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        GoogleSearchBarSetup.onAndroidAnswered(context, pendingWidgetId, granted)
        pendingWidgetId = -1
        if (granted) hotseatMode.onChange(GoogleSearchHotseat)
    }
    SwitchPreference(
        checked = hotseatMode.state.value == GoogleSearchHotseat,
        onCheckedChange = { on ->
            if (!on) {
                hotseatMode.onChange(LawnchairHotseat)
            } else {
                when (val prepared = GoogleSearchBarSetup.prepare(context)) {
                    GoogleSearchBarSetup.Prepared.Ready -> hotseatMode.onChange(GoogleSearchHotseat)
                    is GoogleSearchBarSetup.Prepared.AskAndroid -> try {
                        pendingWidgetId = prepared.widgetId
                        askAndroid.launch(prepared.intent)
                    } catch (error: RuntimeException) {
                        Log.w("GoogleSearchBar", "Unable to ask Android for widget access", error)
                        GoogleSearchBarSetup.onAndroidAnswered(context, prepared.widgetId, granted = false)
                        pendingWidgetId = -1
                    }
                    GoogleSearchBarSetup.Prepared.Unavailable -> Unit
                }
            }
        },
        label = stringResource(id = R.string.google_search_bar_label),
        description = stringResource(id = R.string.google_search_bar_description),
    )
}
