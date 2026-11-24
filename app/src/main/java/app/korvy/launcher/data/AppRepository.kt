package app.korvy.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** One launchable app (an app can have several entries, and work-profile apps are separate entries). */
data class AppEntry(
    val key: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val isWorkProfile: Boolean,
)

/** Lists launchable apps through LauncherApps and keeps the list current when apps are installed or removed. */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val infos = HashMap<String, LauncherActivityInfo>()
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    // Icons are drawn small; ~70 KB each at 48 dp. 24 MB covers a few hundred apps.
    private val icons = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    var onChanged: (() -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed()
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
    }

    init {
        launcherApps.registerCallback(callback)
    }

    private fun changed() {
        icons.evictAll()
        onChanged?.invoke()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val own = context.packageName
        val list = ArrayList<AppEntry>()
        val newInfos = HashMap<String, LauncherActivityInfo>()
        for (user in launcherApps.profiles) {
            val serial = userManager.getSerialNumberForUser(user)
            val work = user != Process.myUserHandle()
            for (info in launcherApps.getActivityList(null, user)) {
                val component = info.componentName
                if (component.packageName == own) continue // don't list ourselves
                val key = "${component.flattenToShortString()}#$serial"
                newInfos[key] = info
                list += AppEntry(key, info.label.toString(), component.packageName, component, user, work)
            }
        }
        list.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        synchronized(infos) { infos.clear(); infos.putAll(newInfos) }
        _apps.value = list
    }

    /** Loads (and caches) an icon at [sizePx]. Call off the main thread. */
    fun icon(key: String, sizePx: Int): ImageBitmap? {
        icons.get(key)?.let { return it }
        val info = synchronized(infos) { infos[key] } ?: return null
        return runCatching {
            val d = info.getBadgedIcon(0) // adds the work-profile badge when needed
            d.toBitmap(sizePx, sizePx).asImageBitmap()
        }.getOrNull()?.also { icons.put(key, it) }
    }

    fun launch(app: AppEntry, bounds: Rect? = null) {
        launcherApps.startMainActivity(app.component, app.user, bounds, null)
    }

    fun openAppInfo(app: AppEntry) {
        launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
    }
}
