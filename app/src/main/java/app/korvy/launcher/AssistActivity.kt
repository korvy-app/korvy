package app.korvy.launcher

import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.korvy.launcher.ui.Bubble
import app.korvy.launcher.ui.rememberCardActions
import app.korvy.launcher.ui.LauncherTheme
import app.korvy.launcher.ui.MicButton
import app.korvy.launcher.ui.MicState
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import app.korvy.launcher.platform.Orientation
import app.korvy.launcher.ui.SpeakToggle

/**
 * The assistant panel shown over any app when the user holds the power button (this app must be the phone's
 * "Digital assistant app"). It answers Android's ACTION_ASSIST and shares the conversation with the launcher.
 */
class AssistActivity : ComponentActivity() {
    private val mic = MicState()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val app = application as LauncherApp
        if (android.os.Build.VERSION.SDK_INT >= 26) window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        Orientation.follow(this)
        setContent {
            val settings by app.settingsState.collectAsStateWithLifecycle()
            val chat by app.assistant.chat.collectAsStateWithLifecycle()
            val busy by app.assistant.busy.collectAsStateWithLifecycle()
            var input by remember { mutableStateOf("") }
            val context = LocalContext.current
            val cardActions = rememberCardActions(app.assistant)
            val readingAloud by app.assistant.readingAloud.collectAsStateWithLifecycle()
            val speechState by app.assistant.speech.collectAsStateWithLifecycle()
            // Opening an app or a web page from here: get out of the way.
            LaunchedEffect(Unit) { app.assistant.leftForApp.drop(1).collect { finish() } }
            LauncherTheme(animations = settings.animations ?: true) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                        .clickable(remember { MutableInteractionSource() }, null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(remember { MutableInteractionSource() }, null) {} // taps inside don't close it
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .navigationBarsPadding().imePadding().padding(12.dp)
                            .testTag("assist_panel"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Assistant", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            SpeakToggle(settings.speak,
                                { m -> app.scope.launch { app.settings.setSpeak(m) } }, { app.assistant.sayHello() })
                            IconButton(onClick = {
                                context.startActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_ASSISTANT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                finish()
                            }) { Icon(Icons.Outlined.OpenInFull, "Open full screen") }
                            IconButton(onClick = { finish() }) { Icon(Icons.Outlined.Close, "Close") }
                        }
                        val list = rememberLazyListState()
                        val recent = chat.takeLast(6)
                        LaunchedEffect(recent.size, recent.lastOrNull()?.text) { if (recent.isNotEmpty()) list.animateScrollToItem(recent.lastIndex) }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(recent, key = { it.id }) { m -> Bubble(m, actions = cardActions, onSpeak = app.assistant::readAloud, speech = if (readingAloud == m.id) speechState else null, openLink = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }) }
                        }
                        val status = mic.error ?: if (mic.listening) (mic.partial.ifBlank { "Listening…" }) else null
                        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (mic.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { v -> if (v.contains('\n')) { val q = v.replace('\n', ' ').trim(); input = ""; if (q.isNotEmpty()) mic.error = null; app.assistant.ask(q) } else input = v },
                                placeholder = { Text("Ask or say something") }, maxLines = 3, shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.weight(1f).testTag("assist_input"),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { mic.error = null; app.assistant.ask(input); input = "" } }),
                            )
                            MicButton(mic, onResult = { app.assistant.ask(it, byVoice = true) })
                            if (busy) IconButton(onClick = { app.assistant.stop() }) { Icon(Icons.Outlined.Stop, "Stop") }
                            else IconButton(onClick = { if (input.isNotBlank()) { mic.error = null; app.assistant.ask(input); input = "" } }) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
                        }
                    }
                }
            }
        }
    }

    private var firstResume = true

    /** Start listening once the panel is actually on screen (asking for the mic earlier can be refused). */
    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            (application as LauncherApp).assistant.warmUp()
            if ((application as LauncherApp).settingsState.value.listenOnAssist || intent.getBooleanExtra(EXTRA_LISTEN, false)) mic.requestStart()
        }
    }

    /** Holding the power button again while the panel is open: listen again. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mic.requestStart()
    }

    override fun onStop() {
        super.onStop()
        (application as LauncherApp).assistant.speaker.stop()
        if (!isChangingConfigurations) finish() // a fresh panel each time
    }

    companion object {
        /** Start listening right away (the wake word opened the panel). */
        const val EXTRA_LISTEN = "listen"
    }
}
