# The rules from AOSP are located in proguard.flags file, we can just maintain Lawnchair related rules here.

# Optimization options.
-allowaccessmodification
-dontusemixedcaseclassnames
-allowaccessmodification
-keepattributes InnerClasses, *Annotation*, Signature, SourceFile, LineNumberTable

# Remove some Kotlin overhead
-processkotlinnullchecks remove

# Common rules.
-keep class android.window.** { *; }
-keep class android.view.** { *; }

-keepclassmembers class * implements android.os.Parcelable {
  public static final ** CREATOR;
}

# The system Shell sends its shared parcelables by class name (Bundle values such as the split
# bounds in the recents animation extras), and the launcher resolves them with Class.forName.
-keepnames class com.android.wm.shell.shared.** implements android.os.Parcelable

# Android 17 system callbacks that the Android 16 prebuilts lack. They are not overrides at compile
# time and nothing in the app calls them, so R8 would otherwise remove them and the system's call
# would hit the base class or throw AbstractMethodError.
-keepclassmembers class * extends android.view.IDisplayWindowListener$Stub {
  public void onDisplayAnimationsDisabledChanged(int, boolean);
}
-keepclassmembers class * extends android.app.TaskStackListener {
  public void onRecentTaskRemovedForAddTask(int);
}

# Material3's SheetState.Saver stores SheetValue directly in Compose saved state. After process
# death, Parcel restores it through Java enum serialization, which reflects on values(). R8
# otherwise removes that method and the values array, crashing Preferences restoration. Keep this
# small serialized enum's identity and members without retaining unrelated Material3 classes.
-keep enum androidx.compose.material3.SheetValue { *; }

# Lawnchair specific rules.
-keep,allowshrinking,allowoptimization class app.lawnchair.LawnchairProto$* { *; }
-keep,allowshrinking,allowoptimization class app.lawnchair.LawnchairApp { *; }
-keep,allowshrinking,allowoptimization class app.lawnchair.LawnchairLauncher { *; }
-keep,allowshrinking,allowoptimization class app.lawnchair.compatlib.** { *; }

-keep,allowshrinking,allowoptimization class com.google.protobuf.Timestamp { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# We intentionally remove it to replace Smartspacer's widget popup with our own Launcher3 popup
-dontwarn com.skydoves.balloon.*

# This shouldn't concern us much
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
