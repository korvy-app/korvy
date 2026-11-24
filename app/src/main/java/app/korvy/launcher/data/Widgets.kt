package app.korvy.launcher.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** A widget on the home screen: its id and height (0 small, 1 medium, 2 large). */
data class PlacedWidget(val id: Int, val size: Int = 1)

/**
 * Home-screen widgets: a stack between the clock and the app grid. Stored on the phone only (not backed up).
 * Widgets are drawn by their own apps through Android's widget host; this app only places them.
 */
class Widgets(context: Context) {
    private val app = context.applicationContext
    val manager: AppWidgetManager = AppWidgetManager.getInstance(app)
    val host = object : AppWidgetHost(app, HOST_ID) {
        override fun onCreateView(c: Context, id: Int, info: AppWidgetProviderInfo?): AppWidgetHostView = WidgetView(c)
    }
    private val prefs = app.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    private val _placed = MutableStateFlow(load())
    val placed: StateFlow<List<PlacedWidget>> = _placed
    /** Waiting for a widget's own setup screen to finish. */
    var pendingConfigure: Int? = null

    private fun load(): List<PlacedWidget> = prefs.getString("placed", "").orEmpty().split(',').mapNotNull { e ->
        val p = e.split(':'); p.getOrNull(0)?.toIntOrNull()?.let { PlacedWidget(it, p.getOrNull(1)?.toIntOrNull() ?: 1) }
    }

    private fun save(list: List<PlacedWidget>) {
        _placed.value = list
        prefs.edit().putString("placed", list.joinToString(",") { "${it.id}:${it.size}" }).apply()
    }

    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    /** Widgets that can be added, leaving out this app's and those of hidden apps. */
    fun providers(hiddenPackages: Set<String>): List<AppWidgetProviderInfo> =
        manager.installedProviders.filter { it.provider.packageName != app.packageName && it.provider.packageName !in hiddenPackages }

    fun add(id: Int, info: AppWidgetProviderInfo? = info(id)) {
        // Start at the size that best fits what the widget asks for.
        val minH = (info?.minHeight ?: 0) / app.resources.displayMetrics.density
        val size = when { minH > 200 -> 2; minH > 120 -> 1; else -> 0 }
        save(_placed.value.filterNot { it.id == id } + PlacedWidget(id, size))
    }

    fun remove(id: Int) { host.deleteAppWidgetId(id); save(_placed.value.filterNot { it.id == id }) }
    fun resize(id: Int, size: Int) = save(_placed.value.map { if (it.id == id) it.copy(size = size) else it })
    fun move(id: Int, by: Int) {
        val l = _placed.value.toMutableList(); val i = l.indexOfFirst { it.id == id }; val j = i + by
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i)); save(l)
    }

    /** Frees widget ids Android still holds for us that aren't placed (e.g. an abandoned setup). */
    fun cleanUp() {
        val placed = _placed.value.map { it.id }.toSet() + listOfNotNull(pendingConfigure)
        runCatching { host.appWidgetIds.filter { it !in placed }.forEach { host.deleteAppWidgetId(it) } }
        // A widget whose app was uninstalled has no provider any more.
        save(_placed.value.filter { manager.getAppWidgetInfo(it.id) != null })
    }

    companion object {
        const val HOST_ID = 1024
        const val REQ_CONFIGURE = 4242
        val HEIGHTS_DP = intArrayOf(110, 180, 270)
    }
}

/** A widget's view; holding it opens our menu (resize, move, remove) instead of passing the touch on. */
class WidgetView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { longPressed = false; downX = ev.x; downY = ev.y; postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return longPressed
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) longPressed = false
            return true
        }
        return super.onTouchEvent(ev)
    }
}
