package app.korvy.launcher

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import app.korvy.launcher.platform.Auth
import app.korvy.launcher.platform.Device
import app.korvy.launcher.platform.StatsSampler
import app.korvy.launcher.ui.Host
import app.korvy.launcher.ui.LauncherViewModel
import app.korvy.launcher.ui.Root
import app.korvy.launcher.data.Widgets
import app.korvy.launcher.platform.Orientation
import app.korvy.launcher.ui.Screen

/** The home screen. FragmentActivity (not ComponentActivity) because the lock prompt needs it. */
class MainActivity : FragmentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private val homeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light status-bar icons: the launcher always sits over a picture.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val host = Host(
            authenticate = { title, ok -> Auth.authenticate(this, title, ok) },
            canLock = { Auth.available(this) },
            toast = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
            setDefaultHome = { runCatching { homeRole.launch(Device.homeRoleIntent(this)) } },
            isDefaultHome = { Device.isDefaultHome(this) },
            stats = StatsSampler(this),
        )
        Orientation.follow(this)
        setContent { Root(vm, host) }
        // Free widget ids left behind (e.g. a widget whose app was uninstalled); after the first frame.
        window.decorView.post { runCatching { (application as LauncherApp).widgets.cleanUp() } }
        if (intent?.action == ACTION_OPEN_ASSISTANT) vm.openAssistant()
        if (intent?.action == ACTION_OPEN_MODELS) vm.open(Screen.MODELS)
        // Privacy: keep autofill services (e.g. Google's) out of everything typed here: searches, questions.
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }

    /** Pressing Home while the launcher is showing: close whatever is open and go back to the home screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed in another app (we're not in front): close everything instantly, no animation to tap into.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.goHome(instant = !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        if (intent.action == ACTION_OPEN_ASSISTANT) vm.openAssistant()
        if (intent.action == ACTION_OPEN_MODELS) vm.open(Screen.MODELS)
    }

    /** Leaving for another app: come back to a clean home screen, not the app list or search. */
    override fun onStop() {
        super.onStop()
        vm.onStopped()
        runCatching { (application as LauncherApp).widgets.host.stopListening() }
    }

    override fun onStart() {
        super.onStart()
        // Widgets update only while the home screen can be seen.
        runCatching { (application as LauncherApp).widgets.host.startListening() }
    }

    /** A widget's own setup screen finished (started by the widget picker). */
    @Deprecated("Widget setup still reports through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != Widgets.REQ_CONFIGURE) return
        val w = (application as LauncherApp).widgets
        val id = w.pendingConfigure ?: return
        w.pendingConfigure = null
        if (resultCode == RESULT_OK) w.add(id) else w.host.deleteAppWidgetId(id)
    }

    companion object {
        const val ACTION_OPEN_ASSISTANT = "app.korvy.launcher.OPEN_ASSISTANT"
        const val ACTION_OPEN_MODELS = "app.korvy.launcher.OPEN_MODELS"
    }

    override fun onResume() {
        super.onResume()
        vm.refreshSuggestions() // the time of day has probably changed
        vm.refreshDefaultHome()
        vm.refreshContactsGranted()
        window.decorView.post { (application as LauncherApp).syncWakeWord() } // after the first frame
    }
}
