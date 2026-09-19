package app.lawnchair.smartspace

import android.view.LayoutInflater
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.test.SmartspaceHostActivity
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import com.kieronquinn.app.smartspacer.sdk.client.R as SmartspacerR
import org.junit.Test
import org.junit.Assume.assumeFalse
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartspacerBindingTest {

    @Test
    fun attachDetachAndFreshActivity_keepsPagerMeasurableAndDoesNotCrash() {
        repeat(2) {
            ActivityScenario.launch(SmartspaceHostActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val view = LayoutInflater.from(activity)
                        .inflate(R.layout.smartspace_smartspacer, null, false) as SmartspacerView
                    activity.setContentView(view)
                    val pager = view.findViewById<android.view.View>(SmartspacerR.id.smartspace_card_pager)
                    assertThat(pager.layoutParams.height).isGreaterThan(0)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
        }
    }

    @Test
    fun missingSmartspacerService_showsDateFirstFallback() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val smartspacerInstalled = runCatching {
            targetContext.packageManager.getPackageInfo("com.kieronquinn.app.smartspacer", 0)
        }.isSuccess
        assumeFalse("This case requires Smartspacer to be absent", smartspacerInstalled)

        ActivityScenario.launch(SmartspaceHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = LayoutInflater.from(activity)
                    .inflate(R.layout.smartspace_smartspacer, null, false) as SmartspacerView
                activity.setContentView(view)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val view = activity.findViewById<SmartspacerView>(R.id.bc_smartspace_view)
                val fallback = (0 until view.childCount)
                    .map(view::getChildAt)
                    .filterIsInstance<BcSmartspaceView>()
                    .singleOrNull()
                assertThat(
                    fallback,
                ).isNotNull()
                val date = fallback!!.findViewById<IcuDateTextView>(R.id.date)
                assertThat(date).isNotNull()
                assertThat(date.text.toString()).isNotEmpty()
                assertThat(fallback.findViewById<android.widget.TextView>(R.id.title_text)?.text)
                    .isNotEqualTo(activity.getString(R.string.onboarding_welcome))
            }
        }
    }
}
