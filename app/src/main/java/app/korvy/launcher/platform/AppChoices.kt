package app.korvy.launcher.platform

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/** Kinds of task the assistant hands to another app. The user can pick which app does each. */
enum class AppTask(val title: String, val example: String) {
    MESSAGES("Texting", "text Jen on my way"),
    PHONE("Calls", "call Jen"),
    MAPS("Maps and directions", "directions to Costco"),
    RIDES("Rides", "get me a ride to the airport"),
    MUSIC("Music", "play some jazz"),
    BROWSER("Web pages", "open links and web searches"),
    EMAIL("Email", ""),
}

data class AppOption(val pkg: String, val label: String)

object AppChoices {
    private val rideApps = linkedMapOf("com.ubercab" to "Uber", "me.lyft.android" to "Lyft")

    /** A sample intent each task's apps must handle (used to find the installed apps that can do it). */
    fun probe(task: AppTask): Intent? = when (task) {
        AppTask.MESSAGES -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
        AppTask.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
        AppTask.MAPS -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=coffee"))
        AppTask.MUSIC -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, "jazz")
        AppTask.BROWSER -> Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        AppTask.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        AppTask.RIDES -> null
    }

    /** Installed apps that can do [task]. */
    fun options(context: Context, task: AppTask): List<AppOption> {
        val pm = context.packageManager
        if (task == AppTask.RIDES) return rideApps.filterKeys { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }.map { AppOption(it.key, it.value) }
        val intent = probe(task) ?: return emptyList()
        // Signal, WhatsApp and Telegram don't (all) register as SMS apps but can open a chat with a number.
        val extra = if (task == AppTask.MESSAGES) Messenger.installed(context).values.toList() else emptyList()
        return (pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName } + extra).distinct()
            .filter { it != context.packageName }
            .map { AppOption(it, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)) }
            .sortedBy { it.label.lowercase() }
    }

    /** The app the user picked for [task], if it's still installed. */
    fun chosen(context: Context, choices: Map<String, String>, task: AppTask): String? =
        choices[task.name]?.takeIf { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess }

    fun label(context: Context, pkg: String): String =
        runCatching { context.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
}
