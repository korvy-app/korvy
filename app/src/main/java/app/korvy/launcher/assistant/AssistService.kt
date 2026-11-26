package app.korvy.launcher.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.View
import app.korvy.launcher.AssistActivity
import app.korvy.launcher.LauncherApp
import app.korvy.launcher.core.ScreenText

/**
 * Korvy as Android's full digital assistant: holding the power button over an app starts a [AssistSession],
 * which (when the user allows "Use text from screen" in Android and in Korvy) receives that app's visible text,
 * then opens Korvy's assistant panel ([AssistActivity]) with it. Nothing runs until the user summons it.
 */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** Android requires an assistant to name a speech recognizer; Korvy recognises speech in its own panel instead. */
class NoRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) { runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

class AssistSession(context: Context) : VoiceInteractionSession(context) {
    private val main = Handler(Looper.getMainLooper())
    private var opened = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        opened = false
        // Without "Use text from screen" Android sends no screen: open right away. Otherwise wait briefly for it.
        if (showFlags and SHOW_WITH_ASSIST == 0) open(null, null) else main.postDelayed({ open(null, null) }, 1500)
    }

    override fun onHandleAssist(state: AssistState) {
        if (!state.isFocused) return
        read(state.assistStructure, state.assistContent)
    }

    @Deprecated("Android 9 and older deliver the screen here")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        if (Build.VERSION.SDK_INT >= 29) return // onHandleAssist(AssistState) is used instead
        read(structure, content)
    }

    private fun read(structure: AssistStructure?, content: AssistContent?) {
        val pkg = structure?.activityComponent?.packageName
        open(pkg, structure)
    }

    private fun open(pkg: String?, structure: AssistStructure?) {
        if (opened) return
        opened = true
        main.removeCallbacksAndMessages(null)
        val app = context.applicationContext as LauncherApp
        val i = Intent(context, AssistActivity::class.java)
        // Any problem reading the screen: open the panel without it (this runs in the launcher's process).
        runCatching {
            if (pkg != null && structure != null && app.settingsState.value.readScreen && pkg != context.packageName && !isHidden(app, pkg)) {
                val (texts, selection) = collect(structure)
                val text = ScreenText.compact(if (selection != null) listOf(selection) else texts)
                if (text.isNotBlank()) {
                    i.putExtra(AssistActivity.EXTRA_SCREEN_TEXT, text)
                        .putExtra(AssistActivity.EXTRA_SCREEN_APP, label(pkg))
                        .putExtra(AssistActivity.EXTRA_SCREEN_SELECTED, selection != null)
                }
            }
        }.onFailure { android.util.Log.w("AssistSession", "couldn't read the screen", it) }
        runCatching { if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(i) else context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        hide()
    }

    /** Hidden and locked apps must never reach the assistant, not even their screen. */
    private fun isHidden(app: LauncherApp, pkg: String) =
        app.privacy.load().hidden.keys.any { it.substringBefore('/') == pkg }

    private fun label(pkg: String) = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Visible texts in reading order, and the selected text if the user selected some. Skips passwords and cards. */
    private fun collect(structure: AssistStructure): Pair<List<String>, String?> {
        val out = ArrayList<String>()
        var selection: String? = null
        fun visit(n: AssistStructure.ViewNode, depth: Int) {
            if (depth > 60 || out.size > 400) return
            if (n.visibility != View.VISIBLE) return
            if (sensitive(n)) return
            val text = n.text?.toString()
            if (!text.isNullOrBlank()) {
                out += text
                val a = n.textSelectionStart; val b = n.textSelectionEnd
                if (a in 0 until b && b <= text.length) selection = text.substring(a, b)
            } else n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until n.childCount) visit(n.getChildAt(i), depth + 1)
        }
        for (w in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(w).rootViewNode, 0)
        return out to selection?.takeIf { it.isNotBlank() }
    }

    private fun sensitive(n: AssistStructure.ViewNode): Boolean {
        val type = n.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        val cls = type and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return true
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        val hints = n.autofillHints.orEmpty().map { it.lowercase() }
        return hints.any { it.contains("password") || it.contains("creditcard") || it.contains("cvc") || it.contains("cvv") ||
            it.contains("otp") || it.contains("smsotp") || it.contains("security") }
    }
}
