/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.launcher3.pm;

import static com.android.launcher3.Utilities.ATLEAST_U;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.dagger.LauncherAppSingleton;
import com.android.launcher3.dagger.LauncherBaseAppComponent;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.UserBadgeDrawable;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.util.DaggerSingletonObject;
import com.android.launcher3.util.DaggerSingletonTracker;
import com.android.launcher3.util.FlagOp;
import com.android.launcher3.util.SafeCloseable;
import com.android.launcher3.util.SimpleBroadcastReceiver;
import com.android.launcher3.util.UserIconInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import javax.inject.Inject;

/**
 * Class which manages a local cache of user handles to avoid system rpc
 */
@LauncherAppSingleton
public class UserCache {

    public static DaggerSingletonObject<UserCache> INSTANCE =
            new DaggerSingletonObject<>(LauncherBaseAppComponent::getUserCache);

    public static final String ACTION_PROFILE_ADDED = ATLEAST_U
            ? Intent.ACTION_PROFILE_ADDED : Intent.ACTION_MANAGED_PROFILE_ADDED;
    public static final String ACTION_PROFILE_REMOVED = ATLEAST_U
            ? Intent.ACTION_PROFILE_REMOVED : Intent.ACTION_MANAGED_PROFILE_REMOVED;

    public static final String ACTION_PROFILE_UNLOCKED = ATLEAST_U
            ? Intent.ACTION_PROFILE_ACCESSIBLE : Intent.ACTION_MANAGED_PROFILE_UNLOCKED;
    public static final String ACTION_PROFILE_LOCKED = ATLEAST_U
            ? Intent.ACTION_PROFILE_INACCESSIBLE : Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE;
    public static final String ACTION_PROFILE_AVAILABLE = "android.intent.action.PROFILE_AVAILABLE";
    public static final String ACTION_PROFILE_UNAVAILABLE =
            "android.intent.action.PROFILE_UNAVAILABLE";

    /** Returns an instance of UserCache bound to the context provided. */
    public static UserCache getInstance(Context context) {
        return INSTANCE.get(context);
    }

    private final List<BiConsumer<UserHandle, String>> mUserEventListeners = new ArrayList<>();
    private final SimpleBroadcastReceiver mUserChangeReceiver;
    private final ApiWrapper mApiWrapper;

    @NonNull
    private Map<UserHandle, UserIconInfo> mUserToSerialMap;

    @NonNull
    private Map<UserHandle, List<String>> mUserToPreInstallAppMap;

    @Inject
    public UserCache(
            @ApplicationContext Context context,
            DaggerSingletonTracker tracker,
            ApiWrapper apiWrapper
    ) {
        mApiWrapper = apiWrapper;
        mUserChangeReceiver = new SimpleBroadcastReceiver(context,
                MODEL_EXECUTOR, this::onUsersChanged);
        mUserToSerialMap = Collections.emptyMap();
        mUserToPreInstallAppMap = Collections.emptyMap();
        MODEL_EXECUTOR.execute(this::initAsync);
        tracker.addCloseable(() -> mUserChangeReceiver.unregisterReceiverSafely());
    }

    @WorkerThread
    private void initAsync() {
        mUserChangeReceiver.register(
                Intent.ACTION_MANAGED_PROFILE_AVAILABLE,
                Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE,
                Intent.ACTION_MANAGED_PROFILE_REMOVED,
                ACTION_PROFILE_ADDED,
                ACTION_PROFILE_REMOVED,
                ACTION_PROFILE_UNLOCKED,
                ACTION_PROFILE_LOCKED,
                ACTION_PROFILE_AVAILABLE,
                ACTION_PROFILE_UNAVAILABLE);
        updateCache(null, null);
    }

    @AnyThread
    private void onUsersChanged(Intent intent) {
        UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
        String action = intent.getAction();

        // This receiver already runs on MODEL_EXECUTOR. Refresh before dispatching the event so
        // model tasks never observe the previous profile snapshot. Locking a hidden profile can
        // make platform metadata briefly unavailable while the profile process is being stopped.
        updateCache(user, action);
        if (user == null || action == null) {
            return;
        }
        mUserEventListeners.forEach(l -> l.accept(user, action));
    }

    @WorkerThread
    private void updateCache(@Nullable UserHandle changedUser, @Nullable String action) {
        Map<UserHandle, UserIconInfo> queriedUsers;
        try {
            queriedUsers = mApiWrapper.queryAllUsers();
        } catch (RuntimeException e) {
            // A profile can become inaccessible between getUserProfiles() and the per-user binder
            // calls. Keep the last valid snapshot and let the next profile broadcast retry.
            return;
        }

        Map<UserHandle, UserIconInfo> updatedUsers = mergeUserProfilesForLifecycle(
                mUserToSerialMap, queriedUsers, changedUser, action);
        mUserToSerialMap = updatedUsers;
        mUserToPreInstallAppMap = fetchPreInstallApps(updatedUsers);
    }

    @WorkerThread
    private Map<UserHandle, List<String>> fetchPreInstallApps(
            Map<UserHandle, UserIconInfo> users) {
        Map<UserHandle, List<String>> userToPreInstallApp = new ArrayMap<>();
        users.forEach((userHandle, userIconInfo) -> {
            // Fetch only for private profile, as other profiles have no usages yet.
            List<String> preInstallApp = userIconInfo.isPrivate()
                    ? mApiWrapper.getPreInstalledSystemPackages(userHandle)
                    : new ArrayList<>();
            userToPreInstallApp.put(userHandle, preInstallApp);
        });
        return userToPreInstallApp;
    }

    /**
     * Keeps a private profile addressable while Android transitions it to an inaccessible state.
     *
     * <p>Some platform builds temporarily omit the profile, or return incomplete metadata, while
     * handling {@link Intent#ACTION_PROFILE_INACCESSIBLE} and {@link #ACTION_PROFILE_UNAVAILABLE}.
     * Losing its last known type here also loses the handle used to request quiet mode off, which
     * leaves a hidden Private Space with no recovery path in the launcher. An explicit removal is
     * the only event that is allowed to evict the cached private profile.
     */
    @VisibleForTesting
    static Map<UserHandle, UserIconInfo> mergeUserProfilesForLifecycle(
            Map<UserHandle, UserIconInfo> previousUsers,
            Map<UserHandle, UserIconInfo> queriedUsers,
            @Nullable UserHandle changedUser,
            @Nullable String action) {
        Map<UserHandle, UserIconInfo> mergedUsers = new ArrayMap<>();
        if (queriedUsers != null) {
            mergedUsers.putAll(queriedUsers);
        }
        if (changedUser != null && isExplicitProfileRemoval(changedUser, changedUser, action)) {
            mergedUsers.remove(changedUser);
        }

        previousUsers.forEach((user, previousInfo) -> {
            if (!previousInfo.isPrivate() || isExplicitProfileRemoval(user, changedUser, action)) {
                return;
            }
            UserIconInfo queriedInfo = mergedUsers.get(user);
            if (queriedInfo == null || !queriedInfo.isPrivate()) {
                mergedUsers.put(user, previousInfo);
            }
        });
        return mergedUsers;
    }

    private static boolean isExplicitProfileRemoval(UserHandle cachedUser,
            @Nullable UserHandle changedUser, @Nullable String action) {
        if (!cachedUser.equals(changedUser)) {
            return false;
        }
        return ACTION_PROFILE_REMOVED.equals(action)
                || Intent.ACTION_MANAGED_PROFILE_REMOVED.equals(action);
    }

    /**
     * Adds a listener for user additions and removals
     */
    public SafeCloseable addUserEventListener(BiConsumer<UserHandle, String> listener) {
        mUserEventListeners.add(listener);
        return () -> mUserEventListeners.remove(listener);
    }

    /**
     * @see UserManager#getSerialNumberForUser(UserHandle)
     */
    public long getSerialNumberForUser(UserHandle user) {
        return getUserInfo(user).userSerial;
    }

    /**
     * Returns the user properties for the provided user or default values
     */
    @NonNull
    public UserIconInfo getUserInfo(UserHandle user) {
        UserIconInfo info = mUserToSerialMap.get(user);
        return info == null ? new UserIconInfo(user, UserIconInfo.TYPE_MAIN) : info;
    }

    /**
     * @see UserManager#getUserForSerialNumber(long)
     */
    public UserHandle getUserForSerialNumber(long serialNumber) {
        return mUserToSerialMap
                .entrySet()
                .stream()
                .filter(entry -> serialNumber == entry.getValue().userSerial)
                .findFirst()
                .map(Map.Entry::getKey)
                .orElse(Process.myUserHandle());
    }

    @VisibleForTesting
    public void putToCache(UserHandle userHandle, UserIconInfo info) {
        mUserToSerialMap.put(userHandle, info);
    }

    @VisibleForTesting
    public void putToPreInstallCache(UserHandle userHandle, List<String> preInstalledApps) {
        mUserToPreInstallAppMap.put(userHandle, preInstalledApps);
    }

    /**
     * @see UserManager#getUserProfiles()
     */
    public List<UserHandle> getUserProfiles() {
        return List.copyOf(mUserToSerialMap.keySet());
    }

    /**
     * Returns the pre-installed apps for a user.
     */
    @NonNull
    public List<String> getPreInstallApps(UserHandle user) {
        List<String> preInstallApp = mUserToPreInstallAppMap.get(user);
        return preInstallApp == null ? new ArrayList<>() : preInstallApp;
    }

    /**
     * Get a non-themed {@link UserBadgeDrawable} based on the provided {@link UserHandle}.
     */
    @Nullable
    public static UserBadgeDrawable getBadgeDrawable(Context context, UserHandle userHandle) {
        return (UserBadgeDrawable) BitmapInfo.LOW_RES_INFO.withFlags(UserCache.getInstance(context)
                        .getUserInfo(userHandle).applyBitmapInfoFlags(FlagOp.NO_OP))
                .getBadgeDrawable(context, false /* isThemed */, null);
    }
}
