package app.lawnchair

import android.app.Application
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FeedBridgeConnectionPolicyTest {

    @Test
    fun productionWithoutBridge_neverFallsBackToDirectGoogleBinding() {
        val result = resolveFeedConnectionKind(
            requiresBridge = true,
            bridgeAvailable = false,
            directOverlayAvailable = true,
        )

        assertThat(result).isEqualTo(FeedConnectionKind.UNAVAILABLE)
    }

    @Test
    fun compatibleBridge_isPreferredEvenWhenDirectBindingIsAllowed() {
        val result = resolveFeedConnectionKind(
            requiresBridge = false,
            bridgeAvailable = true,
            directOverlayAvailable = true,
        )

        assertThat(result).isEqualTo(FeedConnectionKind.BRIDGE)
    }

    @Test
    fun debuggableOrSystemBuild_canUseResolvableDirectGoogleService() {
        val result = resolveFeedConnectionKind(
            requiresBridge = false,
            bridgeAvailable = false,
            directOverlayAvailable = true,
        )

        assertThat(result).isEqualTo(FeedConnectionKind.DIRECT)
    }

    @Test
    fun bridgeProbeAndBindIntent_identifyTheLauncherAndSameTarget() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = FeedBridge.createOverlayIntent(context, FeedBridge.FIRST_PARTY_FEED_PACKAGE)

        assertThat(intent.action).isEqualTo(FeedBridge.OVERLAY_ACTION)
        assertThat(intent.`package`).isEqualTo(FeedBridge.FIRST_PARTY_FEED_PACKAGE)
        assertThat(intent.data?.scheme).isEqualTo("app")
        assertThat(intent.data?.host).isEqualTo(context.packageName)
        assertThat(intent.data?.port).isEqualTo(Process.myUid())
        assertThat(intent.data?.getQueryParameter("v")).isEqualTo("7")
        assertThat(intent.data?.getQueryParameter("cv")).isEqualTo("9")
    }

    @Test
    fun firstPartyContract_matchesCompanionManifest() {
        assertThat(FeedBridge.FIRST_PARTY_FEED_PACKAGE)
            .isEqualTo("dev.launcher.expressive.feed")
        assertThat(FeedBridge.FIRST_PARTY_CONNECT_PERMISSION)
            .isEqualTo("dev.launcher.expressive.feed.permission.CONNECT")
    }
}
