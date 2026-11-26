package app.korvy.launcher

import app.korvy.launcher.R
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.stringResource
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
        takeScreen(intent)
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
            val screen by app.assistant.screen.collectAsStateWithLifecycle()
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
                            Text(stringResource(R.string.assistant), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            SpeakToggle(settings.speak,
                                { m -> app.scope.launch { app.settings.setSpeak(m) } }, { app.assistant.sayHello() })
                            IconButton(onClick = {
                                keepScreen = true // the conversation (and the screen it's about) continues full screen
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
                        // The screen the assistant was called over: shown so the user knows, removable with ✕.
                        screen?.let { sc ->
                            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.InputChip(
                                    selected = true, onClick = { app.assistant.screen.value = null },
                                    label = { Text(if (sc.selected) stringResource(R.string.screen_chip_selected, sc.appLabel) else stringResource(R.string.screen_chip, sc.appLabel), maxLines = 1) },
                                    trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.screen_clear), Modifier.size(16.dp)) },
                                    modifier = Modifier.testTag("screen_chip"),
                                )
                                if (chat.isEmpty() || chat.last().pending.not() && !busy) {
                                    val sumQ = stringResource(R.string.screen_summarise_q); val keyQ = stringResource(R.string.screen_key_points)
                                    androidx.compose.material3.SuggestionChip(onClick = { app.assistant.ask(sumQ) }, label = { Text(stringResource(R.string.screen_summarise)) },
                                        modifier = Modifier.testTag("screen_summarise"))
                                    androidx.compose.material3.SuggestionChip(onClick = { app.assistant.ask(keyQ) }, label = { Text(keyQ) })
                                }
                            }
                        }
                        val status = mic.error ?: if (mic.listening) (mic.partial.ifBlank { "Listening…" }) else null
                        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (mic.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { v -> if (v.contains('\n')) { val q = v.replace('\n', ' ').trim(); input = ""; if (q.isNotEmpty()) mic.error = null; app.assistant.ask(q) } else input = v },
                                placeholder = { Text(stringResource(R.string.ask_or_say_something)) }, maxLines = 3, shape = RoundedCornerShape(24.dp),
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

    /** Holding the power button again while the panel is open: listen again (over a possibly new screen). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeScreen(intent)
        mic.requestStart()
    }

    /** Set when going full screen: the screen stays attached to the conversation there. */
    private var keepScreen = false

    /** The screen text the assistant session read (see AssistSession), or none: replaces the previous one. */
    private fun takeScreen(i: Intent?) {
        val text = i?.getStringExtra(EXTRA_SCREEN_TEXT)
        (application as LauncherApp).assistant.screen.value = text?.let {
            app.korvy.launcher.core.ScreenContext(i.getStringExtra(EXTRA_SCREEN_APP).orEmpty(), it, i.getBooleanExtra(EXTRA_SCREEN_SELECTED, false))
        }
    }

    override fun onStop() {
        super.onStop()
        if (!keepScreen) (application as LauncherApp).assistant.screen.value = null // only for this panel
        (application as LauncherApp).assistant.speaker.stop()
        if (!isChangingConfigurations) finish() // a fresh panel each time
    }

    companion object {
        /** Start listening right away (the wake word opened the panel). */
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_SCREEN_TEXT = "screen_text"
        const val EXTRA_SCREEN_APP = "screen_app"
        const val EXTRA_SCREEN_SELECTED = "screen_selected"
    }
}
