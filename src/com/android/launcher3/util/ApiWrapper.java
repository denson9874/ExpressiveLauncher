/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.util;

import static com.android.launcher3.LauncherConstants.ActivityCodes.REQUEST_HOME_ROLE;

import android.app.ActivityOptions;
import android.app.Person;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherUserInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.view.SurfaceControlViewHost;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.android.launcher3.BaseActivity;
import com.android.launcher3.BuildConfig;
import com.android.launcher3.Launcher;
import com.android.launcher3.Utilities;
import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.dagger.LauncherAppComponent;
import com.android.launcher3.dagger.LauncherAppSingleton;
import com.android.launcher3.icons.BitmapRenderer;
import com.android.launcher3.util.TouchController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.inject.Inject;

/**
 * A wrapper for the hidden API calls
 */
@LauncherAppSingleton
public class ApiWrapper {

    public static final DaggerSingletonObject<ApiWrapper> INSTANCE = new DaggerSingletonObject<>(
            LauncherAppComponent::getApiWrapper);

    protected final Context mContext;
    private final String[] mLegacyMultiInstanceSupportedApps;

    @Inject
    public ApiWrapper(@ApplicationContext Context context) {
        mContext = context;
        mLegacyMultiInstanceSupportedApps = context.getResources().getStringArray(
                com.android.launcher3.R.array.config_appsSupportMultiInstancesSplit);
    }

    /**
     * Returns the list of persons associated with the provided shortcut info
     */
    public Person[] getPersons(ShortcutInfo si) {
        return Utilities.EMPTY_PERSON_ARRAY;
    }

    public Map<String, LauncherActivityInfo> getActivityOverrides() {
        return Collections.emptyMap();
    }

    /**
     * Creates an ActivityOptions to play fade-out animation on closing targets
     */
    public ActivityOptions createFadeOutAnimOptions() {
        return ActivityOptions.makeCustomAnimation(mContext, 0, android.R.anim.fade_out);
    }

    /**
     * Returns a map of all users on the device to their corresponding UI properties
     */
    public Map<UserHandle, UserIconInfo> queryAllUsers() {
        UserManager um = mContext.getSystemService(UserManager.class);
        LauncherApps launcherApps = mContext.getSystemService(LauncherApps.class);
        Map<UserHandle, UserIconInfo> users = new ArrayMap<>();
        List<UserHandle> userManagerProfiles;
        try {
            userManagerProfiles = um == null ? Collections.emptyList() : um.getUserProfiles();
        } catch (RuntimeException e) {
            userManagerProfiles = Collections.singletonList(Process.myUserHandle());
        }
        if (userManagerProfiles == null || userManagerProfiles.isEmpty()) {
            userManagerProfiles = Collections.singletonList(Process.myUserHandle());
        }

        List<UserHandle> usersActual = userManagerProfiles;
        if (Utilities.ATLEAST_V && launcherApps != null) {
            try {
                // UserManager intentionally omits hidden profiles for ordinary apps. ROLE_HOME
                // plus ACCESS_HIDDEN_PROFILES grants access through this public launcher API.
                // Keep the valid UserManager snapshot if this independent Binder call is briefly
                // unavailable during cold start.
                List<UserHandle> launcherProfiles = launcherApps.getProfiles();
                if (launcherProfiles != null && !launcherProfiles.isEmpty()) {
                    usersActual = launcherProfiles;
                }
            } catch (RuntimeException e) {
                // Retain owner/work profiles from UserManager; a later profile broadcast retries.
            }
        }
        if (usersActual != null) {
            for (UserHandle user : usersActual) {
                try {
                    if (Utilities.ATLEAST_V && launcherApps != null) {
                        LauncherUserInfo userInfo = launcherApps.getLauncherUserInfo(user);
                        if (userInfo != null) {
                            users.put(user, new UserIconInfo(
                                    user,
                                    getUserIconType(userInfo.getUserType()),
                                    userInfo.getUserSerialNumber()));
                            continue;
                        }
                    }
                } catch (Throwable t) {
                    // Fall through to the conservative recovery classification below.
                }

                long serial = user.hashCode();
                boolean isWork = false;
                try {
                    serial = um == null ? serial : um.getSerialNumberForUser(user);
                    NoopDrawable d = new NoopDrawable();
                    isWork = d != mContext.getPackageManager().getUserBadgedIcon(d, user);
                } catch (RuntimeException e) {
                    // The stable user handle still lets Launcher recover the profile on a later
                    // broadcast; use conservative rendering metadata during this transition.
                }
                boolean isHiddenProfile = Utilities.ATLEAST_V
                        && !Process.myUserHandle().equals(user)
                        && (userManagerProfiles == null || !userManagerProfiles.contains(user));
                users.put(user, new UserIconInfo(user,
                        isHiddenProfile ? UserIconInfo.TYPE_PRIVATE
                                : isWork ? UserIconInfo.TYPE_WORK : UserIconInfo.TYPE_MAIN,
                        serial));
            }
        }
        return users;
    }

    private static int getUserIconType(@Nullable String userType) {
        if (UserManager.USER_TYPE_PROFILE_MANAGED.equals(userType)) {
            return UserIconInfo.TYPE_WORK;
        } else if (UserManager.USER_TYPE_PROFILE_CLONE.equals(userType)) {
            return UserIconInfo.TYPE_CLONED;
        } else if (UserManager.USER_TYPE_PROFILE_PRIVATE.equals(userType)) {
            return UserIconInfo.TYPE_PRIVATE;
        }
        return UserIconInfo.TYPE_MAIN;
    }

    /** Returns whether Android's public launcher metadata says this entrypoint should be hidden. */
    public boolean isPrivateSpaceHidden(UserHandle user) {
        if (!Utilities.ATLEAST_V || user == null) {
            return false;
        }
        try {
            LauncherApps launcherApps = mContext.getSystemService(LauncherApps.class);
            LauncherUserInfo userInfo = launcherApps == null
                    ? null : launcherApps.getLauncherUserInfo(user);
            return userInfo != null && userInfo.getUserConfig() != null
                    && userInfo.getUserConfig().getBoolean(
                    LauncherUserInfo.PRIVATE_SPACE_ENTRYPOINT_HIDDEN, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns the list of the system packages that are installed at user creation.
     * An empty list denotes that all system packages are installed for that user at creation.
     */
    public List<String> getPreInstalledSystemPackages(UserHandle user) {
        return Collections.emptyList();
    }

    /**
     * Returns an intent which can be used to start the App Market activity (Installer
     * Activity).
     */
    public Intent getAppMarketActivityIntent(String packageName, UserHandle user) {
        return createMarketIntent(packageName);
    }

    /**
     * Returns an intent that opens an app market inside a non-owner profile.
     *
     * <p>The base launcher cannot safely emulate this with a normal {@code market://} intent:
     * {@link Context#startActivity(Intent)} would resolve that URI in the owner profile. Quickstep
     * overrides this method with LauncherApps' profile-scoped IntentSender bridge.
     */
    @Nullable
    public Intent getPrivateProfileAppMarketActivityIntent(
            String packageName, UserHandle user) {
        return null;
    }

    /**
     * Returns an intent which can be used to start a search for a package on app market
     */
    public Intent getMarketSearchIntent(String packageName, UserHandle user) {
        // If we are search for the current user, just launch the market directly as the
        // system won't have the installer details either
        return  (Process.myUserHandle().equals(user))
                ? createMarketIntent(packageName)
                : getAppMarketActivityIntent(packageName, user);
    }

    private static Intent createMarketIntent(String packageName) {
        return new Intent(Intent.ACTION_VIEW)
                .setData(new Uri.Builder()
                        .scheme("market")
                        .authority("details")
                        .appendQueryParameter("id", packageName)
                        .build())
                .putExtra(Intent.EXTRA_REFERRER, new Uri.Builder().scheme("android-app")
                        .authority(BuildConfig.APPLICATION_ID).build());
    }

    /**
     * Returns an intent which can be used to open Private Space Settings.
     */
    @Nullable
    public Intent getPrivateSpaceSettingsIntent() {
        return null;
    }

    /**
     * Checks if an activity is flagged as non-resizeable.
     */
    public boolean isNonResizeableActivity(@NonNull LauncherActivityInfo lai) {
        // Overridden in Quickstep
        return false;
    }

    /**
     * Checks if an activity supports multi-instance.
     */
    public boolean supportsMultiInstance(@NonNull LauncherActivityInfo lai) {
        // Check app multi-instance properties after V
        if (!Utilities.ATLEAST_V) {
            return false;
        }

        // Check the legacy hardcoded allowlist first
        for (String pkg : mLegacyMultiInstanceSupportedApps) {
            if (pkg.equals(lai.getComponentName().getPackageName())) {
                return true;
            }
        }

        // Overridden in Quickstep
        return false;
    }
    /**
     * Starts an Activity which can be used to set this Launcher as the HOME app, via a consent
     * screen. In case the consent screen cannot be shown, or the user does not set current Launcher
     * as HOME app, a toast asking the user to do the latter is shown.
     */
    public void assignDefaultHomeRole(Context context) {
        RoleManager roleManager = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            roleManager = context.getSystemService(RoleManager.class);
            if (roleManager != null && roleManager.isRoleAvailable (RoleManager.ROLE_HOME)
                    && !roleManager.isRoleHeld (RoleManager.ROLE_HOME)) {
                Intent roleRequestIntent = roleManager.createRequestRoleIntent (
                        RoleManager.ROLE_HOME);
                Launcher launcher = Launcher.getLauncher (context);
                launcher.startActivityForResult (roleRequestIntent , REQUEST_HOME_ROLE);
            }
        }
    }

    @Nullable
    public TouchController createStatusBarTouchController(
            BaseActivity launcher,
            Supplier<Boolean> isEnabledCheck
    ) {
        return null;
    }

    /**
     * Checks if the shortcut is using an icon with file or URI source
     */
    public boolean isFileDrawable(@NonNull ShortcutInfo shortcutInfo) {
        return false;
    }

    /** Captures a snapshot of the host content as a bitmap */
    public Bitmap captureSnapshot(SurfaceControlViewHost host, int width, int height) {
        return BitmapRenderer.createHardwareBitmap(width, height, host.getView()::draw);
    }

    private static class NoopDrawable extends ColorDrawable {
        @Override
        public int getIntrinsicHeight() {
            return 1;
        }

        @Override
        public int getIntrinsicWidth() {
            return 1;
        }
    }
}
