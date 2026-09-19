package app.lawnchair.icons.iconpack

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import app.lawnchair.icons.ClockMetadata
import app.lawnchair.icons.ExtendedBitmapDrawable
import app.lawnchair.icons.picker.IconEntry
import app.lawnchair.icons.picker.IconPickerCategory
import app.lawnchair.icons.picker.IconPickerItem
import app.lawnchair.icons.picker.IconType
import com.android.launcher3.R
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

class CustomIconPack(context: Context, packPackageName: String) : IconPack(context, packPackageName) {

    private val packResources = context.packageManager.getResourcesForApplication(packPackageName)
    private val componentMap = mutableMapOf<ComponentName, IconEntry>()
    private val calendarMap = mutableMapOf<ComponentName, IconEntry>()
    private val clockMap = mutableMapOf<ComponentName, IconEntry>()
    private val clockMetas = mutableMapOf<IconEntry, ClockMetadata>()

    private val idCache = ConcurrentHashMap<String, Int>()

    override val label = context.packageManager.let { pm ->
        pm.getApplicationInfo(packPackageName, 0).loadLabel(pm).toString()
    }

    init {
        startLoad()
    }

    override fun getIcon(componentName: ComponentName) = componentMap[componentName]
    override fun getCalendar(componentName: ComponentName) = calendarMap[componentName]
    override fun getClock(entry: IconEntry) = clockMetas[entry]

    override fun getCalendars(): MutableSet<ComponentName> = calendarMap.keys
    override fun getClocks(): MutableSet<ComponentName> = clockMap.keys

    override fun getIcon(iconEntry: IconEntry, iconDpi: Int): Drawable? {
        val id = getDrawableId(iconEntry.name)
        if (id == 0) return null
        return try {
            ExtendedBitmapDrawable.wrap(
                packResources,
                packResources.getDrawableForDensity(id, iconDpi, null),
                true,
            )
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    fun createFromExternalPicker(icon: Intent.ShortcutIconResource): IconPickerItem? {
        @SuppressLint("DiscouragedApi")
        val id = packResources.getIdentifier(icon.resourceName, null, null)
        if (id == 0) return null
        val simpleName = packResources.getResourceEntryName(id)
        return IconPickerItem(packPackageName, simpleName, simpleName, IconType.Normal)
    }

    override fun loadInternal() {
        val source = getXml("appfilter") ?: return
        val parseXml = source.parser
        try {
            while (parseXml.next() != XmlPullParser.END_DOCUMENT) {
                if (parseXml.eventType != XmlPullParser.START_TAG) continue
                val name = parseXml.name
                val isCalendar = name == "calendar"
                when (name) {
                    "item", "calendar" -> {
                        var componentName: String? = parseXml["component"]
                        val drawableName = parseXml[if (isCalendar) "prefix" else "drawable"]
                        if (componentName != null && drawableName != null) {
                            componentName = normalizeIconPackComponentName(componentName)
                            val parsed = componentName?.let(ComponentName::unflattenFromString)
                            if (parsed != null) {
                                if (isCalendar) {
                                    calendarMap[parsed] = IconEntry(packPackageName, drawableName, IconType.Calendar)
                                } else {
                                    componentMap[parsed] = IconEntry(packPackageName, drawableName, IconType.Normal)
                                }
                            }
                        }
                    }

                    "dynamic-clock" -> {
                        val drawableName = parseXml["drawable"]
                        if (drawableName != null) {
                            if (parseXml is XmlResourceParser) {
                                clockMetas[IconEntry(packPackageName, drawableName, IconType.Normal)] = ClockMetadata(
                                    parseXml.getAttributeIntValue(null, "hourLayerIndex", -1),
                                    parseXml.getAttributeIntValue(null, "minuteLayerIndex", -1),
                                    parseXml.getAttributeIntValue(null, "secondLayerIndex", -1),
                                    parseXml.getAttributeIntValue(null, "defaultHour", 0),
                                    parseXml.getAttributeIntValue(null, "defaultMinute", 0),
                                    parseXml.getAttributeIntValue(null, "defaultSecond", 0),
                                )
                            }
                        }
                    }
                }
            }
            componentMap.forEach { (componentName, iconEntry) ->
                if (clockMetas.containsKey(iconEntry)) {
                    clockMap[componentName] = iconEntry
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Icon pack was removed while parsing: $packPackageName", e)
        } catch (e: XmlPullParserException) {
            Log.w(TAG, "Malformed appfilter in icon pack: $packPackageName", e)
        } catch (e: IOException) {
            Log.w(TAG, "Unable to read appfilter in icon pack: $packPackageName", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Invalid appfilter state in icon pack: $packPackageName", e)
        } finally {
            source.close()
        }
    }

    override fun getAllIcons(): Flow<List<IconPickerCategory>> = flow {
        load()

        val result = mutableListOf<IconPickerCategory>()

        var currentTitle: String? = null
        val currentItems = mutableListOf<IconPickerItem>()

        suspend fun endCategory() {
            if (currentItems.isEmpty()) return
            val title = currentTitle ?: context.getString(R.string.icon_picker_default_category)
            result.add(IconPickerCategory(title, ArrayList(currentItems)))
            currentTitle = null
            currentItems.clear()
            emit(ArrayList(result))
        }

        val source = getXml("drawable")
        val parser = source?.parser
        try {
            while (parser != null && parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "category" -> {
                        val title = parser["title"] ?: continue
                        endCategory()
                        currentTitle = title
                    }

                    "item" -> {
                        val drawableName = parser["drawable"] ?: continue
                        val resId = getDrawableId(drawableName)
                        if (resId != 0) {
                            val item = IconPickerItem(packPackageName, drawableName, drawableName, IconType.Normal)
                            currentItems.add(item)
                        }
                    }
                }
            }
        } catch (e: XmlPullParserException) {
            Log.w(TAG, "Malformed drawable list in icon pack: $packPackageName", e)
        } catch (e: IOException) {
            Log.w(TAG, "Unable to read drawable list in icon pack: $packPackageName", e)
        } finally {
            source?.close()
        }
        endCategory()
    }.flowOn(Dispatchers.IO)

    @SuppressLint("DiscouragedApi")
    private fun getDrawableId(name: String) = idCache.computeIfAbsent(name) {
        packResources.getIdentifier(name, "drawable", packPackageName)
    }

    private fun getXml(name: String): XmlSource? {
        val res: Resources
        try {
            res = context.packageManager.getResourcesForApplication(packPackageName)
            @SuppressLint("DiscouragedApi")
            val resourceId = res.getIdentifier(name, "xml", packPackageName)
            return if (0 != resourceId) {
                val parser = context.packageManager.getXml(packPackageName, resourceId, null)
                    ?: return null
                XmlSource(parser, parser::close)
            } else {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                val input = res.assets.open("$name.xml")
                try {
                    parser.setInput(input, Xml.Encoding.UTF_8.toString())
                    XmlSource(parser, input::close)
                } catch (throwable: Throwable) {
                    input.close()
                    throw throwable
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
        } catch (_: IOException) {
        } catch (_: XmlPullParserException) {
        }
        return null
    }

    private companion object {
        const val TAG = "CustomIconPack"
    }

    private class XmlSource(
        val parser: XmlPullParser,
        val close: () -> Unit,
    )
}

private operator fun XmlPullParser.get(key: String): String? = this.getAttributeValue(null, key)

internal fun normalizeIconPackComponentName(rawValue: String): String? {
    val value = rawValue.trim()
    if (value.isEmpty()) return null
    val prefix = "ComponentInfo{"
    val suffix = "}"
    return if (value.startsWith(prefix) && value.endsWith(suffix)) {
        // Some icon packs contain an empty ComponentInfo wrapper. Returning it as a
        // flattened component lets malformed metadata leak into the lookup table.
        value.substring(prefix.length, value.length - suffix.length).trim().ifEmpty { null }
    } else {
        value
    }
}
