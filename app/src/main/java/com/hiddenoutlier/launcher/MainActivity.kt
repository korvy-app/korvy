package com.hiddenoutlier.launcher

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
import com.hiddenoutlier.launcher.platform.Auth
import com.hiddenoutlier.launcher.platform.Device
import com.hiddenoutlier.launcher.platform.StatsSampler
import com.hiddenoutlier.launcher.ui.Host
import com.hiddenoutlier.launcher.ui.LauncherViewModel
import com.hiddenoutlier.launcher.ui.Root

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
        setContent { Root(vm, host) }
        if (intent?.action == ACTION_OPEN_ASSISTANT) vm.openAssistant()
        if (intent?.action == ACTION_OPEN_MODELS) vm.open(com.hiddenoutlier.launcher.ui.Screen.MODELS)
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
        if (intent.action == ACTION_OPEN_MODELS) vm.open(com.hiddenoutlier.launcher.ui.Screen.MODELS)
    }

    /** Leaving for another app: come back to a clean home screen, not the app list or search. */
    override fun onStop() {
        super.onStop()
        vm.onStopped()
    }

    companion object {
        const val ACTION_OPEN_ASSISTANT = "com.hiddenoutlier.launcher.OPEN_ASSISTANT"
        const val ACTION_OPEN_MODELS = "com.hiddenoutlier.launcher.OPEN_MODELS"
    }

    override fun onResume() {
        super.onResume()
        vm.refreshSuggestions() // the time of day has probably changed
        vm.refreshDefaultHome()
        vm.refreshContactsGranted()
    }
}
