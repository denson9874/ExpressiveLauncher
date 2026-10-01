package app.lawnchair.allapps.views

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import app.lawnchair.launcher
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.util.runOnMainThread
import com.android.launcher3.BubbleTextView
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings
import com.android.launcher3.R
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.IconProvider
import com.android.launcher3.icons.LauncherIcons
import com.android.launcher3.icons.cache.CacheLookupFlag.Companion.DEFAULT_LOOKUP_FLAG
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.model.data.PackageItemInfo
import com.android.launcher3.model.data.SearchActionItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.touch.ItemClickHandler
import com.android.launcher3.touch.ItemLongClickListener
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors
import com.android.systemui.util.dpToPx

class SearchResultIcon(context: Context, attrs: AttributeSet?) :
    BubbleTextView(context, attrs),
    SearchResultView,
    View.OnClickListener,
    View.OnLongClickListener {

    private val launcher = context.launcher
    private var boundId = ""
    private var flags = 0
    private var allowLongClick = false
    private var callback: ((info: ItemInfoWithIcon) -> Unit)? = null

    private val searchResultMargin = resources.getDimensionPixelSize(R.dimen.search_result_margin)
    private var defaultPaddingLeft = -1
    private var defaultPaddingRight = -1

    override fun onFinishInflate() {
        super.onFinishInflate()
        setLongPressTimeoutFactor(1f)
        onFocusChangeListener = launcher.focusHandler
        setOnClickListener(this)
        setOnLongClickListener(this)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            launcher.deviceProfile.allAppsProfile.cellHeightPx,
        )
        defaultPaddingLeft = paddingLeft
        defaultPaddingRight = paddingRight
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (defaultPaddingLeft == -1) {
            defaultPaddingLeft = paddingLeft
            defaultPaddingRight = paddingRight
        }
        val isLayoutHorizontal = compoundDrawablesRelative[0] != null || compoundDrawablesRelative[2] != null
        if (isLayoutHorizontal) {
            if (paddingLeft != defaultPaddingLeft || paddingRight != defaultPaddingRight) {
                setPadding(defaultPaddingLeft, paddingTop, defaultPaddingRight, paddingBottom)
            }
        } else if (width > 0) {
            val desiredWidth = iconSize + 48.dpToPx(resources)
            if (desiredWidth < width) {
                val inset = ((width - desiredWidth) / 2).toInt()
                if (paddingLeft != inset || paddingRight != inset) {
                    setPadding(inset, paddingTop, inset, paddingBottom)
                }
            } else {
                if (paddingLeft != defaultPaddingLeft || paddingRight != defaultPaddingRight) {
                    setPadding(defaultPaddingLeft, paddingTop, defaultPaddingRight, paddingBottom)
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override val isQuickLaunch get() = hasFlag(flags, SearchResultView.FLAG_QUICK_LAUNCH)
    override val titleText: CharSequence? get() = text

    override fun launch(): Boolean {
        ItemClickHandler.INSTANCE.onClick(this)
        return true
    }

    override fun bind(target: SearchTargetCompat, shortcuts: List<SearchTargetCompat>) {
        boundId = target.id
        flags = getFlags(target.extras)
        reset()
        setForceHideDot(true)
        // Only an action row waiting for its icon hides it; every other bind shows it.
        setIconVisible(true)

        val extras = target.extras
        val iconComponentKey = extras.getString(SearchResultView.EXTRA_ICON_COMPONENT_KEY)
            ?.let { ComponentKey.fromString(it) }
        when {
            target.searchAction != null -> {
                allowLongClick = false
                bindFromAction(target, iconComponentKey == null)
            }

            target.shortcutInfo != null -> {
                allowLongClick = true
                bindFromShortcutInfo(target.id, target.shortcutInfo)
            }

            else -> {
                allowLongClick = true
                val className = extras.getString("class").orEmpty()
                val componentName = ComponentName(target.packageName, className)
                bindFromApp(componentName, target.userHandle)
            }
        }
        if (iconComponentKey != null) {
            bindIconComponentKey(iconComponentKey)
        }
    }

    fun bind(target: SearchTargetCompat, callback: (info: ItemInfoWithIcon) -> Unit) {
        this.callback = callback
        bind(target, emptyList())
        if (!hasFlag(flags, SearchResultView.FLAG_HIDE_ICON)) {
            isVisible = true
            val lp = layoutParams as ViewGroup.MarginLayoutParams
            val size = iconSize + compoundDrawablePadding
            lp.width = size
            lp.height = size
            lp.marginStart = searchResultMargin
        } else {
            isInvisible = true
            val lp = layoutParams as ViewGroup.MarginLayoutParams
            lp.width = 0
            lp.marginStart = 0
        }
    }

    private fun bindFromAction(target: SearchTargetCompat, bindIcon: Boolean) {
        val action = target.searchAction ?: return
        val info = SearchActionItemInfo(
            action.icon,
            target.packageName,
            target.userHandle,
            action.title,
            true,
        )
        if (action.intent != null) {
            info.intent = action.intent
        }
        if (action.pendingIntent != null) {
            info.pendingIntent = action.pendingIntent
        }
        val extras = action.extras
        if (extras != null) {
            if (extras.getBoolean("should_start_for_result") || target.resultType == 16) {
                info.setFlags(SearchActionItemInfo.FLAG_SHOULD_START_FOR_RESULT)
            } else if (extras.getBoolean("should_start")) {
                info.setFlags(SearchActionItemInfo.FLAG_SHOULD_START)
            }
            if (extras.getBoolean("badge_with_package")) {
                info.setFlags(SearchActionItemInfo.FLAG_BADGE_WITH_PACKAGE)
            }
            if (extras.getBoolean("badge_with_component_name")) {
                info.setFlags(SearchActionItemInfo.FLAG_BADGE_WITH_COMPONENT_NAME)
            }
            if (extras.getBoolean("primary_icon_from_title")) {
                info.setFlags(SearchActionItemInfo.FLAG_PRIMARY_ICON_FROM_TITLE)
            }
        }
        notifyApplied(info)
        if (bindIcon) {
            val iconKey = searchActionIconKey(
                packageName = target.packageName,
                user = target.userHandle.toString(),
                layoutType = target.layoutType,
                icon = action.icon,
                primaryIconFromTitle = info.hasFlags(SearchActionItemInfo.FLAG_PRIMARY_ICON_FROM_TITLE),
                badged = info.hasFlags(SearchActionItemInfo.FLAG_BADGE_WITH_COMPONENT_NAME) ||
                    info.hasFlags(SearchActionItemInfo.FLAG_BADGE_WITH_PACKAGE),
                theme = "${System.identityHashCode(launcher)}:" +
                    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK),
            )
            // Rows such as "Search on Google" and web suggestions rebind on every keystroke with
            // the same icon: reuse the one loaded last time in the same frame.
            val cached = iconKey?.let { ACTION_ICON_CACHE.get(it) }
            if (cached != null) {
                info.bitmap = cached
                applyFromItemInfoWithIcon(info)
                return
            }
            // A recycled row would show its previous icon (e.g. the Play Store's) until this one
            // loads, so hide it. Load off the model thread: there it queued behind the search
            // itself and rows stayed iconless for ~200 ms.
            setIconVisible(false)
            val targetId = boundId
            val applyLoaded = {
                runOnMainThread {
                    if (iconKey != null) ACTION_ICON_CACHE.put(iconKey, info.bitmap)
                    if (boundId == targetId) {
                        applyFromItemInfoWithIcon(info)
                        setIconVisible(true)
                    }
                }
            }
            val loadOnModelThread = {
                // The package icon fallback reads the icon cache, which only allows its own thread.
                Executors.MODEL_EXECUTOR.handler.postAtFrontOfQueue {
                    populateSearchActionItemInfo(target, info)
                    applyLoaded()
                }
            }
            if (iconKey == null) {
                loadOnModelThread()
            } else {
                Executors.UI_HELPER_EXECUTOR.handler.postAtFrontOfQueue {
                    if (loadActionIcon(action.icon, info)) applyLoaded() else loadOnModelThread()
                }
            }
        }
    }

    private fun bindIconComponentKey(iconComponentKey: ComponentKey) {
        val appInfo = launcher.appsView.appsStore.getApp(iconComponentKey)
        if (appInfo == null) {
            isVisible = false
            return
        }
        icon = appInfo.newIcon(context, 0)
    }

    private fun bindFromApp(componentName: ComponentName, user: UserHandle) {
        val appInfo = launcher.appsView.appsStore.getApp(ComponentKey(componentName, user))
        if (appInfo == null) {
            isVisible = false
            return
        }
        applyFromApplicationInfo(appInfo)
        notifyApplied(appInfo)
    }

    private fun bindFromShortcutInfo(targetId: String, shortcutInfo: ShortcutInfo) {
        val si = WorkspaceItemInfo(shortcutInfo, launcher)
        si.container = LauncherSettings.Favorites.CONTAINER_ALL_APPS
        // App shortcut rows (Maps > Home, Work) rebind on every keystroke. Reuse the icon loaded
        // last time instead of showing an empty placeholder until the model thread reloads it.
        val iconKey = searchShortcutIconKey(
            packageName = shortcutInfo.`package`,
            id = shortcutInfo.id,
            user = shortcutInfo.userHandle.toString(),
            lastChanged = shortcutInfo.lastChangedTimestamp,
            theme = "${System.identityHashCode(launcher)}:" +
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK),
        )
        val cached = ACTION_ICON_CACHE.get(iconKey)
        if (cached != null) si.bitmap = cached
        applyFromWorkspaceItem(si)
        notifyApplied(si)
        if (cached != null) return
        setIconVisible(false)
        val cache = LauncherAppState.getInstance(launcher).iconCache
        Executors.MODEL_EXECUTOR.handler.postAtFrontOfQueue {
            cache.getShortcutIcon(si, shortcutInfo)
            runOnMainThread {
                ACTION_ICON_CACHE.put(iconKey, si.bitmap)
                if (boundId == targetId) {
                    applyFromWorkspaceItem(si)
                    setIconVisible(true)
                }
            }
        }
    }

    private fun notifyApplied(info: ItemInfoWithIcon) {
        callback?.invoke(info)
        callback = null
    }

    override fun onClick(v: View) {
        ItemClickHandler.INSTANCE.onClick(v)
    }

    /** Loads an action icon that needs no package icon; false when its drawable can't load. */
    private fun loadActionIcon(icon: Icon?, info: SearchActionItemInfo): Boolean {
        val drawable = icon?.loadDrawable(context) ?: return false
        LauncherIcons.obtain(context).use { li ->
            info.bitmap = li.createBadgedIconBitmap(drawable, BaseIconFactory.IconOptions().setUser(info.user))
        }
        return true
    }

    private fun populateSearchActionItemInfo(
        target: SearchTargetCompat,
        info: SearchActionItemInfo,
    ) {
        val action = target.searchAction!!
        LauncherIcons.obtain(context).use { li ->
            val icon = action.icon

            // Lazy: most action icons never need the package icon, and its lookup takes the cache lock.
            val packageIcon by lazy { getPackageIcon(target.packageName, target.userHandle) }

            info.bitmap = when {
                info.hasFlags(SearchActionItemInfo.FLAG_PRIMARY_ICON_FROM_TITLE) ->
                    li.createIconBitmap("${info.title}", packageIcon.color)

                icon == null -> packageIcon

                else -> icon.loadDrawable(context)?.let { li.createBadgedIconBitmap(it, BaseIconFactory.IconOptions().setUser(info.user)) } ?: packageIcon
            }
            if (info.hasFlags(SearchActionItemInfo.FLAG_BADGE_WITH_COMPONENT_NAME) && target.extras.containsKey("class")) {
                try {
                    val iconProvider = IconProvider(context)
                    val componentName =
                        ComponentName(target.packageName, target.extras.getString("class")!!)
                    val activityInfo = context.packageManager.getActivityInfo(componentName, 0)
                    val activityIcon = iconProvider.getIcon(activityInfo)
                    val bitmap = li.createIconBitmap(activityIcon, 1f)
                    val bitmapInfo = BitmapInfo.of(bitmap, packageIcon.color)
                    // Lawnchair-TODO-Postmerge: AOSP removed it -- 393bc59246f0f88f62b9879000d57fde36cdb214
//                    info.bitmap = li.badgeBitmap(info.bitmap.icon, bitmapInfo)
                } catch (_: PackageManager.NameNotFoundException) {
                }
            } else if (info.hasFlags(SearchActionItemInfo.FLAG_BADGE_WITH_PACKAGE) && info.bitmap != packageIcon) {
//                info.bitmap = li.badgeBitmap(info.bitmap.icon, packageIcon)
            }
        }
    }

    private fun getPackageIcon(packageName: String, user: UserHandle): BitmapInfo {
        val las = LauncherAppState.getInstance(context)
        val info = PackageItemInfo(packageName, user)
        info.user = user
        las.iconCache.getTitleAndIcon(info, DEFAULT_LOOKUP_FLAG)
        return info.bitmap
    }

    override fun onLongClick(v: View): Boolean {
        if (!allowLongClick) {
            return false
        }
        return ItemLongClickListener.INSTANCE_ALL_APPS.onLongClick(v)
    }

    fun hasFlag(flag: Int) = hasFlag(flags, flag)
}
