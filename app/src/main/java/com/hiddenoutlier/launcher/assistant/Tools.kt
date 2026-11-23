package com.hiddenoutlier.launcher.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.util.Xml
import com.hiddenoutlier.launcher.core.Action
import com.hiddenoutlier.launcher.core.ActionType
import com.hiddenoutlier.launcher.core.AppSearch
import com.hiddenoutlier.launcher.data.AppEntry
import com.hiddenoutlier.launcher.platform.Net
import com.hiddenoutlier.launcher.platform.OfflineException
import com.hiddenoutlier.launcher.platform.OnlineSource
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.util.Locale

/** What an action produced: a sentence for the chat, plus optional detail lines (forecast, headlines…). */
data class ToolResult(val text: String, val lines: List<String> = emptyList(), val link: String? = null, val card: Card? = null)

/**
 * Something the user must confirm (or a contact the assistant needs) before anything is sent, called or booked.
 * [run] does it, with the message as the user left it; [withContact] continues with a contact chosen in the picker.
 */
data class Card(
    val kind: Kind,
    val title: String,
    val detail: String,
    val confirmLabel: String = "OK",
    val message: String? = null,
    val run: (message: String?) -> String = { "" },
    val withContact: (Person) -> ToolResult = { ToolResult("") },
) {
    enum class Kind { CONFIRM, NEED_CONTACT }
}

/**
 * Carries out an [Action]. Results are formatted by plain code (no second model pass), which keeps answers fast
 * and factual. Network goes through [Net] only, so the user's Online switches always apply.
 */
class Tools(
    private val context: Context,
    private val apps: () -> List<AppEntry>, // visible apps only: hidden apps are never offered to the assistant
    private val launch: (AppEntry) -> Unit,
    private val webSearch: (String) -> Unit,
    private val allowed: (OnlineSource) -> Boolean,
    private val homePlace: () -> String,
    private val fahrenheit: () -> Boolean,
    private val contacts: Contacts = Contacts(context),
    private val opened: () -> Unit = {},
) {
    suspend fun run(a: Action): ToolResult = try {
        when (a.type) {
            ActionType.ANSWER -> ToolResult(a.arg)
            ActionType.OPEN_APP -> openApp(a.arg)
            ActionType.WEB_SEARCH -> search(a.arg)
            ActionType.WEATHER -> weather(a.arg)
            ActionType.NEWS -> news(a.arg)
            ActionType.STOCK -> stock(a.arg)
            ActionType.TIMER -> timer(a.arg)
            ActionType.TEXT -> text(a.arg)
            ActionType.CALL -> call(a.arg)
            ActionType.RIDE -> ride(a.arg)
            ActionType.NAVIGATE -> navigate(a.arg)
        }
    } catch (e: OfflineException) {
        ToolResult("${e.source.title} is turned off. You can turn it on in Settings → Online features.")
    } catch (e: Exception) {
        ToolResult("Sorry, that didn't work: ${e.message ?: "unknown error"}")
    }

    private fun openApp(name: String): ToolResult {
        val app = AppSearch.rank(name, apps()) { it.label }.firstOrNull() ?: return ToolResult("I couldn't find an app called “$name”.")
        launch(app)
        return ToolResult("Opening ${app.label}.")
    }

    private suspend fun search(q: String): ToolResult {
        if (!allowed(OnlineSource.ANSWERS)) { webSearch(q); return ToolResult("Searching the web for “$q” in your browser.") }
        val json = JSONObject(Net.getText(OnlineSource.ANSWERS,
            "https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=" + enc(q), allowed))
        val abstract = json.optString("AbstractText").ifBlank { json.optString("Answer") }.ifBlank { json.optString("Definition") }
        if (abstract.isBlank()) { webSearch(q); return ToolResult("No quick answer, so I opened a web search for “$q”.") }
        val src = json.optString("AbstractSource").ifBlank { "DuckDuckGo" }
        return ToolResult(abstract, listOf("Source: $src"), json.optString("AbstractURL").ifBlank { null })
    }

    private suspend fun weather(placeArg: String): ToolResult {
        val place = placeArg.ifBlank { homePlace() }
        if (place.isBlank()) return ToolResult("Which place? Add your home town in Settings → Online features → Weather, or ask “weather in Paris”.")
        val geo = JSONObject(Net.getText(OnlineSource.WEATHER,
            "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + enc(place), allowed))
        val hit = geo.optJSONArray("results")?.optJSONObject(0) ?: return ToolResult("I couldn't find a place called “$place”.")
        val f = fahrenheit()
        val w = JSONObject(Net.getText(OnlineSource.WEATHER,
            "https://api.open-meteo.com/v1/forecast?latitude=${hit.getDouble("latitude")}&longitude=${hit.getDouble("longitude")}" +
                "&current=temperature_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1" + if (f) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "", allowed))
        val cur = w.getJSONObject("current")
        val daily = w.getJSONObject("daily")
        val unit = if (f) "°F" else "°C"
        val name = listOf(hit.optString("name"), hit.optString("admin1")).filter { it.isNotBlank() }.joinToString(", ")
        return ToolResult(
            "${describe(cur.optInt("weather_code"))}, ${cur.getDouble("temperature_2m").toInt()}$unit in $name.",
            listOf(
                "High ${daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()}$unit · low ${daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()}$unit",
                "Chance of rain ${daily.getJSONArray("precipitation_probability_max").optInt(0)}%",
                "Wind ${cur.getDouble("wind_speed_10m").toInt()} ${if (f) "mph" else "km/h"}",
                "Source: Open-Meteo",
            ),
        )
    }

    private suspend fun news(topic: String): ToolResult {
        val feeds = listOf("https://feeds.npr.org/1001/rss.xml", "https://feeds.bbci.co.uk/news/rss.xml")
        val items = feeds.flatMap { url -> runCatching { rssTitles(Net.getText(OnlineSource.NEWS, url, allowed)) }.getOrElse { if (it is OfflineException) throw it; emptyList() } }
        val words = topic.lowercase(Locale.US).split(' ').filter { it.length > 2 }
        val picked = if (words.isEmpty()) items else items.filter { t -> words.any { t.lowercase(Locale.US).contains(it) } }
        if (picked.isEmpty()) return ToolResult(if (topic.isBlank()) "I couldn't get the news right now." else "No headlines about “$topic” right now.")
        return ToolResult(if (topic.isBlank()) "Top headlines:" else "Headlines about $topic:", picked.distinct().take(6).map { "• $it" } + "Source: NPR, BBC")
    }

    private fun rssTitles(xml: String): List<String> {
        val p = Xml.newPullParser().apply { setInput(xml.reader()) }
        val out = ArrayList<String>()
        var inItem = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "item") inItem = true
            if (p.eventType == XmlPullParser.START_TAG && p.name == "title" && inItem) out += p.nextText().trim()
            if (p.eventType == XmlPullParser.END_TAG && p.name == "item") inItem = false
        }
        return out
    }

    private suspend fun stock(symbolArg: String): ToolResult {
        val symbol = symbolArg.trim().uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '.' || it == '-' }
        if (symbol.isEmpty()) return ToolResult("Which stock? Try a ticker like AAPL.")
        val json = JSONObject(Net.getText(OnlineSource.STOCKS,
            "https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?range=1d&interval=1d", allowed))
        val meta = json.getJSONObject("chart").optJSONArray("result")?.optJSONObject(0)?.optJSONObject("meta")
            ?: return ToolResult("I couldn't find the ticker $symbol.")
        val price = meta.getDouble("regularMarketPrice")
        val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", Double.NaN))
        val change = if (prev.isNaN()) "" else " (%+.2f%% today)".format((price - prev) / prev * 100)
        return ToolResult("$symbol is %.2f %s%s.".format(price, meta.optString("currency", "USD"), change),
            listOf(meta.optString("fullExchangeName"), "Source: Yahoo Finance").filter { it.isNotBlank() })
    }

    private fun timer(arg: String): ToolResult {
        val seconds = arg.toIntOrNull() ?: com.hiddenoutlier.launcher.core.AgentCore.parseDuration(arg) ?: return ToolResult("How long should the timer be?")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        val text = when { seconds % 3600 == 0 -> "${seconds / 3600} hour"; seconds % 60 == 0 -> "${seconds / 60} minute"; else -> "$seconds second" }
        return ToolResult("Timer set for ${text}${if (text.startsWith("1 ")) "" else "s"}.")
    }

    // ---- Actions in other apps: always confirmed first, then handed to the user's own app ----------------------

    private fun start(i: Intent): Boolean = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); opened() }.isSuccess

    /** Finds who [who] is: a number as typed, a contact, or a card asking for contacts access / a pick. */
    private fun resolve(who: String, verb: String, then: (Person) -> ToolResult): ToolResult {
        if (com.hiddenoutlier.launcher.core.TextArgs.isNumber(who)) return then(Person(who, who, ""))
        val needContact = { why: String ->
            ToolResult(why, card = Card(Card.Kind.NEED_CONTACT, "Who should I $verb?",
                "Allow contacts (they stay on this phone and are never shown to the AI model), or pick one person.",
                withContact = then))
        }
        if (!contacts.granted()) return needContact("To $verb $who I need to know who that is.")
        val p = contacts.best(who) ?: return needContact("I couldn't find “$who” in your contacts.")
        return then(p)
    }

    private fun text(arg: String): ToolResult {
        val parts = com.hiddenoutlier.launcher.core.TextArgs.split(arg) { contacts.granted() && contacts.nameExists(it) }
        return resolve(parts.recipient, "text") { p -> textCard(p, parts.message) }
    }

    private fun textCard(p: Person, message: String) = ToolResult(
        "Ready to text ${p.name}.",
        card = Card(Card.Kind.CONFIRM, "Text ${p.name}", listOf(p.number, p.label).filter { it.isNotBlank() }.joinToString(" · "), "Send", message,
            run = { m ->
                val ok = start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", m.orEmpty()))
                if (ok) "Opened your messaging app with the text to ${p.name}. Tap send there." else "No messaging app found."
            }),
    )

    private fun call(who: String) = resolve(who, "call") { p ->
        ToolResult("Ready to call ${p.name}.", card = Card(Card.Kind.CONFIRM, "Call ${p.name}",
            listOf(p.number, p.label).filter { it.isNotBlank() }.joinToString(" · "), "Call",
            run = { if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number))))) "Opening the dialer for ${p.name}." else "No phone app found." }))
    }

    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun ride(arg: String): ToolResult {
        val colon = arg.indexOf(':')
        var app = if (colon > 0) arg.substring(0, colon).trim().lowercase() else "uber"
        val dest = (if (colon > 0) arg.substring(colon + 1) else arg).trim()
        if (dest.isBlank()) return ToolResult("Where to?")
        if (app != "lyft" && !installed(UBER) && installed(LYFT)) app = "lyft"
        return if (app == "lyft") ToolResult("Ready to open Lyft.", card = Card(Card.Kind.CONFIRM, "Lyft to $dest",
            "Opens Lyft and copies the destination so you can paste it. You confirm the ride in Lyft.", "Open Lyft",
            run = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Destination", dest))
                val i = context.packageManager.getLaunchIntentForPackage(LYFT) ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://ride.lyft.com"))
                if (start(i)) "Opened Lyft. “$dest” is copied; paste it as the destination." else "Couldn't open Lyft."
            }))
        else ToolResult("Ready to open Uber.", card = Card(Card.Kind.CONFIRM, "Uber to $dest",
            "Opens Uber with the destination filled in. You choose and confirm the ride in Uber.", "Open Uber",
            run = {
                // Uber's universal link: opens the app if installed, otherwise m.uber.com.
                val url = "https://m.uber.com/ul/?action=setPickup&pickup=my_location" +
                    "&dropoff[formatted_address]=" + Uri.encode(dest) + "&dropoff[nickname]=" + Uri.encode(dest)
                if (start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Opened Uber with “$dest” as the destination." else "Couldn't open Uber."
            }))
    }

    private fun navigate(dest: String): ToolResult {
        if (dest.isBlank()) return ToolResult("Where to?")
        // geo: works with any maps app the user has (Google Maps, Organic Maps, OsmAnd…).
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))) ToolResult("Opening directions to $dest.")
        else ToolResult("No maps app found.")
    }

    companion object { private const val UBER = "com.ubercab"; private const val LYFT = "me.lyft.android" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun describe(code: Int) = when (code) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"; 61, 63, 65, 66, 67 -> "Rain"; 71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"; 85, 86 -> "Snow showers"; 95, 96, 99 -> "Thunderstorms"; else -> "Weather"
    }
}
