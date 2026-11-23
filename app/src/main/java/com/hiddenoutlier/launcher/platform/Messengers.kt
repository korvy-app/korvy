package com.hiddenoutlier.launcher.platform

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Chat apps that don't register as SMS apps but can open a chat with a phone number. [prefills] is false when
 * the app can't be handed the message (it's then put on the clipboard to paste).
 */
enum class Messenger(val label: String, val packages: List<String>, val prefills: Boolean) {
    SIGNAL("Signal", listOf("org.thoughtcrime.securesms"), false),
    WHATSAPP("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), true),
    TELEGRAM("Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"), false);

    /** Opens a chat with [e164] (+15551234567). */
    fun chat(e164: String, message: String): Intent = when (this) {
        SIGNAL -> Intent(Intent.ACTION_VIEW, Uri.parse("https://signal.me/#p/$e164"))
        // WhatsApp's click-to-chat link: digits only, message filled in.
        WHATSAPP -> Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${e164.removePrefix("+")}" +
            if (message.isBlank()) "" else "?text=" + Uri.encode(message)))
        TELEGRAM -> Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=${e164.removePrefix("+")}"))
    }

    companion object {
        fun forPackage(pkg: String?): Messenger? = entries.firstOrNull { pkg in it.packages }

        /** The installed package of each messenger (first one found), for Settings → Apps. */
        fun installed(context: Context): Map<Messenger, String> = entries.mapNotNull { m ->
            m.packages.firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }?.let { m to it }
        }.toMap()

        private val VIA = Regex("\\s*\\b(?:on|via|with|using|through|over)\\s+(signal|whats\\s?app|telegram)\\b", RegexOption.IGNORE_CASE)

        /** "Jen on WhatsApp running late" → (WHATSAPP, "Jen running late"). */
        fun strip(arg: String): Pair<Messenger?, String> {
            val m = VIA.find(arg) ?: return null to arg
            val which = when (m.groupValues[1].lowercase().replace(" ", "")) { "signal" -> SIGNAL; "whatsapp" -> WHATSAPP; else -> TELEGRAM }
            return which to (arg.removeRange(m.range).replace(Regex("\\s+"), " ").trim().removePrefix(":").trim())
        }
    }
}
