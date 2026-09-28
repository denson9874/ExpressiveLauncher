package dev.launcher.expressive.feed;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.pm.PackageManager;

import org.junit.Test;

public final class FeedCallerPolicyTest {
    @Test
    public void releaseLauncher_sameSignature_isAllowed() {
        assertThat(FeedCallerPolicy.isAllowedCaller(
                new String[] {FeedCallerPolicy.LAUNCHER_PACKAGE},
                PackageManager.SIGNATURE_MATCH,
                FeedCallerPolicy.LAUNCHER_PACKAGE)).isTrue();
    }

    @Test
    public void playEdition_isNotAcceptedEvenWithAMatchingSignature() {
        // The Play edition and its name-based signer exemption were retired for FOSS-only builds.
        final String playPackage = "com.denson9874.Expressive_Launcher_L3";
        assertThat(FeedCallerPolicy.isAllowedCaller(
                new String[] {playPackage}, PackageManager.SIGNATURE_MATCH, playPackage)).isFalse();
        assertThat(FeedCallerPolicy.isAllowedCaller(
                new String[] {playPackage}, PackageManager.SIGNATURE_NO_MATCH, playPackage)).isFalse();
    }

    @Test
    public void debugLauncher_sameSignature_isAllowed() {
        assertThat(FeedCallerPolicy.isAllowedCaller(
                new String[] {FeedCallerPolicy.DEBUG_LAUNCHER_PACKAGE},
                PackageManager.SIGNATURE_MATCH,
                FeedCallerPolicy.DEBUG_LAUNCHER_PACKAGE)).isTrue();
    }

    @Test
    public void matchingPackage_differentSignature_isRejected() {
        assertThat(FeedCallerPolicy.isAllowedCaller(
                new String[] {FeedCallerPolicy.LAUNCHER_PACKAGE},
                PackageManager.SIGNATURE_NO_MATCH,
                FeedCallerPolicy.LAUNCHER_PACKAGE)).isFalse();
    }

    @Test
    public void spoofedOrUnrelatedPackage_isRejected() {
        assertThat(FeedCallerPolicy.isAllowedCaller(
                new String[] {"example.attacker"},
                PackageManager.SIGNATURE_MATCH,
                FeedCallerPolicy.LAUNCHER_PACKAGE)).isFalse();
        assertThat(FeedCallerPolicy.isAllowedCaller(
                new String[] {FeedCallerPolicy.LAUNCHER_PACKAGE},
                PackageManager.SIGNATURE_MATCH,
                "example.attacker")).isFalse();
    }

    @Test
    public void bindFlags_areRestrictedAndAlwaysAutoCreate() {
        final int unsafeFlag = Context.BIND_ALLOW_ACTIVITY_STARTS;
        final int requested = Context.BIND_IMPORTANT | Context.BIND_WAIVE_PRIORITY | unsafeFlag;

        assertThat(FeedCallerPolicy.sanitizeBindFlags(requested))
                .isEqualTo(Context.BIND_AUTO_CREATE
                        | Context.BIND_IMPORTANT
                        | Context.BIND_WAIVE_PRIORITY);
    }
}
