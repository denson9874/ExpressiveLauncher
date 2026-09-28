package app.lawnchair.icons.shape

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.preferences2.PreferenceManager2
import com.google.common.truth.Truth.assertThat
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderShapeMatchPolicyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun auto_followsAppIconShapeWhileFolderShapeIsUntouched() {
        val resolved = FolderShapeMatchPolicy.resolve(
            match = FolderShapeMatch.AUTO,
            iconShape = IconShape.Squircle,
            folderShape = IconShape.Circle,
            defaultFolderShape = IconShape.Circle,
        )

        assertThat(resolved).isEqualTo(IconShape.Squircle)
    }

    @Test
    fun auto_keepsACustomizedFolderShapeFromBeforeTheUpdate() {
        val resolved = FolderShapeMatchPolicy.resolve(
            match = FolderShapeMatch.AUTO,
            iconShape = IconShape.Squircle,
            folderShape = IconShape.Hexagon,
            defaultFolderShape = IconShape.Circle,
        )

        assertThat(resolved).isEqualTo(IconShape.Hexagon)
    }

    @Test
    fun explicitChoices_overrideTheAutomaticRule() {
        assertThat(
            FolderShapeMatchPolicy.resolve(
                match = FolderShapeMatch.ON,
                iconShape = IconShape.Squircle,
                folderShape = IconShape.Hexagon,
                defaultFolderShape = IconShape.Circle,
            ),
        ).isEqualTo(IconShape.Squircle)
        assertThat(
            FolderShapeMatchPolicy.resolve(
                match = FolderShapeMatch.OFF,
                iconShape = IconShape.Squircle,
                folderShape = IconShape.Circle,
                defaultFolderShape = IconShape.Circle,
            ),
        ).isEqualTo(IconShape.Circle)
    }

    @Test
    fun fromKey_fallsBackToAutoForUnknownValues() {
        assertThat(FolderShapeMatch.fromKey("on")).isEqualTo(FolderShapeMatch.ON)
        assertThat(FolderShapeMatch.fromKey("off")).isEqualTo(FolderShapeMatch.OFF)
        assertThat(FolderShapeMatch.fromKey(null)).isEqualTo(FolderShapeMatch.AUTO)
        assertThat(FolderShapeMatch.fromKey("garbage")).isEqualTo(FolderShapeMatch.AUTO)
    }

    @Test
    fun preference_defaultsToAutoSoFoldersFollowTheAppIconShape() {
        val prefs2 = PreferenceManager2.getInstance(context)
        assertThat(prefs2.folderShapeMatchesIconShape.defaultValue).isEqualTo(FolderShapeMatch.AUTO)

        // PreferenceManager2 outlives a single test, so restore an untouched install explicitly.
        prefs2.folderShape.setBlocking(prefs2.folderShape.defaultValue)
        prefs2.folderShapeMatchesIconShape.setBlocking(FolderShapeMatch.AUTO)
        prefs2.iconShape.setBlocking(IconShape.Squircle)

        assertThat(resolvedFolderShape(prefs2)).isEqualTo(IconShape.Squircle)
    }

    @Test
    fun pickingAFolderShape_stopsFollowingTheAppIconShape() {
        val prefs2 = PreferenceManager2.getInstance(context)
        prefs2.iconShape.setBlocking(IconShape.Squircle)

        prefs2.folderShape.setBlocking(IconShape.Hexagon)

        assertThat(prefs2.folderShapeMatchesIconShape.firstBlocking()).isEqualTo(FolderShapeMatch.OFF)
        assertThat(resolvedFolderShape(prefs2)).isEqualTo(IconShape.Hexagon)

        prefs2.folderShapeMatchesIconShape.setBlocking(FolderShapeMatch.ON)
        assertThat(resolvedFolderShape(prefs2)).isEqualTo(IconShape.Squircle)
    }

    // Same inputs as PreferenceManager2.effectiveFolderShape(), read from DataStore rather than
    // the asynchronously refreshed in-memory cache.
    private fun resolvedFolderShape(prefs2: PreferenceManager2) = FolderShapeMatchPolicy.resolve(
        match = prefs2.folderShapeMatchesIconShape.firstBlocking(),
        iconShape = prefs2.iconShape.firstBlocking(),
        folderShape = prefs2.folderShape.firstBlocking(),
        defaultFolderShape = prefs2.folderShape.defaultValue,
    )
}
