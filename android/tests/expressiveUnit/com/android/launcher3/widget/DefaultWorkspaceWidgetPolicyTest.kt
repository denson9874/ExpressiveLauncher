package com.android.launcher3.widget

import android.app.Application
import android.content.Context
import androidx.annotation.XmlRes
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DefaultWorkspaceWidgetPolicyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun everySupportedLayout_usesTheSamePixelDefaultsWithoutPrivilegedWidgets() {
        val layouts = listOf(
            R.xml.default_workspace_3x3,
            R.xml.default_workspace_4x5,
            R.xml.default_workspace_6x5,
            R.xml.default_workspace_splitdisplay_4x6,
        )

        layouts.forEach { layout ->
            val snapshot = readLayout(layout)

            // A standard Home-role app cannot silently bind a provider on first launch. Default
            // appwidget tags created orphaned IDs before users had granted bind permission.
            assertThat(snapshot.appWidgetCount).isEqualTo(0)
            assertThat(snapshot.hotseatItemCount).isEqualTo(5)
            assertThat(snapshot.folderCount).isEqualTo(1)
            assertThat(snapshot.hotseatPackagesByRank).containsExactlyEntriesIn(
                mapOf(
                    "0" to "com.google.android.dialer",
                    "1" to "com.google.android.apps.messaging",
                    "2" to "com.google.android.contacts",
                    "3" to "com.android.chrome",
                    "4" to "com.google.android.GoogleCamera",
                ),
            )
            assertThat(snapshot.folderPackages).containsExactly(
                "com.google.android.googlequicksearchbox",
                "com.google.android.gm",
                "com.google.android.youtube",
                "com.google.android.apps.docs",
            )

            val serializedValues = snapshot.values.joinToString(separator = "\n")
            assertThat(serializedValues).contains("android.intent.action.DIAL")
            assertThat(serializedValues).contains("android.intent.category.APP_MESSAGING")
            assertThat(serializedValues).contains("android.intent.category.APP_CONTACTS")
            assertThat(serializedValues).contains("android.intent.category.APP_BROWSER")
            assertThat(serializedValues).contains("com.android.camera2")
            assertThat(serializedValues).contains("android.media.action.STILL_IMAGE_CAMERA")
            assertThat(serializedValues).contains("android.intent.category.APP_MARKET")
        }
    }

    @Test
    fun everySupportedGrid_hasCapacityForTheFiveDefaultDockApps() {
        val parser = context.resources.getXml(R.xml.device_profiles)
        val resAutoNamespace = "http://schemas.android.com/apk/res-auto"
        val supportedGrids = mutableMapOf<String, Int>()
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "grid-option") {
                val name = parser.getAttributeValue(resAutoNamespace, "name")
                val dockCount = parser.getAttributeIntValue(resAutoNamespace, "numHotseatIcons", 0)
                supportedGrids[name] = dockCount
            }
            event = parser.next()
        }
        parser.close()

        assertThat(supportedGrids.keys).containsExactly("3_by_3", "4_by_6", "6_by_5", "practical")
        assertThat(supportedGrids.values).containsNoneOf(0, 1, 2, 3, 4)
    }

    private fun readLayout(@XmlRes layout: Int): LayoutSnapshot {
        val parser = context.resources.getXml(layout)
        var event = parser.eventType
        var appWidgetCount = 0
        var folderCount = 0
        var hotseatItemCount = 0
        val values = mutableListOf<String>()
        val hotseatPackagesByRank = mutableMapOf<String, String>()
        val folderPackages = mutableSetOf<String>()
        var currentHotseatRank: String? = null
        var insideFolder = false

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val attributes = (0 until parser.attributeCount).associate {
                    parser.getAttributeName(it) to parser.getAttributeValue(it)
                }
                values += attributes.values
                when (parser.name) {
                    "appwidget" -> appWidgetCount++
                    "folder" -> {
                        folderCount++
                        insideFolder = true
                    }
                    "resolve", "favorite" -> {
                        if (attributes["container"] == "-101") {
                            hotseatItemCount++
                            currentHotseatRank = attributes["screen"]
                        }
                        attributes["packageName"]?.let { packageName ->
                            if (insideFolder) {
                                folderPackages += packageName
                            } else {
                                currentHotseatRank?.let { rank ->
                                    hotseatPackagesByRank.putIfAbsent(rank, packageName)
                                }
                            }
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "folder" -> insideFolder = false
                    "resolve" -> currentHotseatRank = null
                }
            }
            event = parser.next()
        }
        parser.close()
        return LayoutSnapshot(
            appWidgetCount,
            folderCount,
            hotseatItemCount,
            values,
            hotseatPackagesByRank,
            folderPackages,
        )
    }

    private data class LayoutSnapshot(
        val appWidgetCount: Int,
        val folderCount: Int,
        val hotseatItemCount: Int,
        val values: List<String>,
        val hotseatPackagesByRank: Map<String, String>,
        val folderPackages: Set<String>,
    )
}
