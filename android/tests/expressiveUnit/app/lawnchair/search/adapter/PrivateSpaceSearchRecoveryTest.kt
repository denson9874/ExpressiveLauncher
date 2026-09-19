package app.lawnchair.search.adapter

import android.app.Application
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.test.core.app.ApplicationProvider
import com.android.app.search.LayoutType
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class PrivateSpaceSearchRecoveryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun queryMatcher_acceptsOnlyCompletePrivateSpaceLabel() {
        assertThat(isExactPrivateSpaceQuery("Private space", "Private space")).isTrue()
        assertThat(isExactPrivateSpaceQuery(" private SPACE ", "Private space")).isTrue()

        assertThat(isExactPrivateSpaceQuery("Private", "Private space")).isFalse()
        assertThat(isExactPrivateSpaceQuery("Private spaces", "Private space")).isFalse()
        assertThat(isExactPrivateSpaceQuery("Open Private space", "Private space")).isFalse()
        assertThat(isExactPrivateSpaceQuery("Private  space", "Private space")).isFalse()
    }

    @Test
    fun target_isNeutralSettingsTileBackedByAndroidIntent() {
        val settingsIntent = Intent("android.settings.PRIVATE_SPACE_SETTINGS")
            .setPackage("com.android.settings")

        val target = SearchTargetFactory(context)
            .createPrivateSpaceRecoveryTarget(settingsIntent)
        val action = requireNotNull(target.searchAction)

        assertThat(target.layoutType).isEqualTo(LayoutType.ICON_SLICE)
        assertThat(target.resultType).isEqualTo(SearchTargetCompat.RESULT_TYPE_SETTING_TILE)
        assertThat(target.packageName).isEqualTo(SETTINGS)
        assertThat(target.extras.keySet()).isEmpty()
        assertThat(action.title).isEqualTo(context.getString(R.string.private_space_label))
        assertThat(action.subtitle)
            .isEqualTo(context.getString(R.string.private_space_secondary_label))
        assertThat(action.intent).isSameInstanceAs(settingsIntent)
        assertThat(action.icon?.type).isEqualTo(Icon.TYPE_RESOURCE)
        assertThat(action.icon?.resId).isEqualTo(R.drawable.ic_private_space_with_background)
    }
}
