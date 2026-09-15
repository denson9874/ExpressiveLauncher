package com.android.launcher3

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.StringReader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DefaultWorkspaceNamespaceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun bundledWorkspace_readsModernResAutoAttributesAndResources() {
        val parser = context.resources.getXml(R.xml.default_workspace_4x5)
        while (parser.eventType != XmlPullParser.END_DOCUMENT && parser.name != "folder") {
            parser.next()
        }

        assertThat(parser.name).isEqualTo("folder")
        assertThat(AutoInstallsLayout.getAttributeValue(parser, "screen")).isEqualTo("0")
        assertThat(AutoInstallsLayout.getAttributeResourceValue(parser, "title", 0))
            .isEqualTo(R.string.google_folder_title)
        parser.close()
    }

    @Test
    fun partnerWorkspace_keepsLegacyLauncherNamespaceCompatibility() {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(
                StringReader(
                    """<favorite xmlns:launcher="http://schemas.android.com/apk/res-auto/com.android.launcher3" launcher:screen="3"/>""",
                ),
            )
            nextTag()
        }

        assertThat(AutoInstallsLayout.getAttributeValue(parser, "screen")).isEqualTo("3")
    }
}
