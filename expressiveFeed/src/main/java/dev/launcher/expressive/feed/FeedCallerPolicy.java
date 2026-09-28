package dev.launcher.expressive.feed;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.Arrays;

/** Security and flag policy kept separate so its failure cases remain unit-testable. */
final class FeedCallerPolicy {
    static final String LAUNCHER_PACKAGE = "dev.launcher.expressive.l3";
    static final String DEBUG_LAUNCHER_PACKAGE = "dev.launcher.expressive.l3.debug";

    private static final int FORWARDED_BIND_FLAGS =
            Context.BIND_IMPORTANT | Context.BIND_WAIVE_PRIORITY | Context.BIND_NOT_FOREGROUND;

    private FeedCallerPolicy() {}

    static boolean isAllowedCaller(
            String[] packages,
            int signatureMatchResult,
            String claimedPackage) {
        if (packages == null || claimedPackage == null) {
            return false;
        }
        if (!LAUNCHER_PACKAGE.equals(claimedPackage)
                && !DEBUG_LAUNCHER_PACKAGE.equals(claimedPackage)) {
            return false;
        }
        if (!Arrays.asList(packages).contains(claimedPackage)) {
            return false;
        }
        // FOSS-only: the companion ships inside the launcher and trusts only its exact signer.
        return signatureMatchResult == PackageManager.SIGNATURE_MATCH;
    }

    static int sanitizeBindFlags(int requestedFlags) {
        return Context.BIND_AUTO_CREATE | (requestedFlags & FORWARDED_BIND_FLAGS);
    }
}
