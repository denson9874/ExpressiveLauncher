package app.lawnchair.folder.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FolderWidgetStyleTest {
    private val d = FolderWidgetDefaults(backgroundColor = 0x112233, backgroundOpacity = 0.6f, cornerRadiusPx = 84f)
    private val custom = FolderWidgetStyle(
        columns = 4, showLabels = false, backgroundColor = 0xFF0000,
        backgroundOpacity = 0.2f, cornerRadiusPx = 10f, iconScale = 0.8f,
    )

    @Test fun freeUser_getsDefaultsForProFields() {
        val r = custom.resolve(isPro = false, defaults = d)
        assertThat(r.columns).isEqualTo(4)
        assertThat(r.showLabels).isFalse()
        assertThat(r.backgroundColor).isEqualTo(0x112233)
        assertThat(r.backgroundOpacity).isEqualTo(0.6f)
        assertThat(r.cornerRadiusPx).isEqualTo(84f)
        assertThat(r.iconScale).isEqualTo(1f)
    }

    @Test fun proUser_getsCustomValues() {
        val r = custom.resolve(isPro = true, defaults = d)
        assertThat(r.backgroundColor).isEqualTo(0xFF0000)
        assertThat(r.iconScale).isEqualTo(0.8f)
    }

    @Test fun values_areClamped() {
        val r = FolderWidgetStyle(columns = 12, backgroundOpacity = 3f, iconScale = 2f, cornerRadiusPx = -5f)
            .resolve(isPro = true, defaults = d)
        assertThat(r.columns).isEqualTo(6)
        assertThat(r.backgroundOpacity).isEqualTo(1f)
        assertThat(r.iconScale).isEqualTo(1.1f)
        assertThat(r.cornerRadiusPx).isEqualTo(0f)
    }

    @Test fun defaults_matchTheSpec() {
        val r = FolderWidgetStyle().resolve(isPro = false, defaults = d)
        assertThat(r.showLabels).isTrue()
        assertThat(r.showHeader).isTrue()
        assertThat(r.showNameBelow).isFalse()
        assertThat(r.columns).isNull()
    }
}
