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
    }

    /** Pressing Home while the launcher is showing: close whatever is open and go back to the home screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.goHome()
    }

    /** Leaving for another app: come back to a clean home screen, not the app list or search. */
    override fun onStop() {
        super.onStop()
        vm.onStopped()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshSuggestions() // the time of day has probably changed
        vm.refreshDefaultHome()
    }
}
