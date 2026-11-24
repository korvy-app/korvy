package app.korvy.launcher.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.util.Xml
import app.korvy.launcher.core.Action
import app.korvy.launcher.core.ActionType
import app.korvy.launcher.core.AppSearch
import app.korvy.launcher.data.AppEntry
import app.korvy.launcher.platform.Net
import app.korvy.launcher.platform.OfflineException
import app.korvy.launcher.platform.OnlineSource
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.util.Locale
import app.korvy.launcher.core.AgentCore
import app.korvy.launcher.core.Speech
import app.korvy.launcher.core.TextArgs
import app.korvy.launcher.platform.AppChoices
import app.korvy.launcher.platform.AppTask
import app.korvy.launcher.platform.Location
import app.korvy.launcher.platform.Messenger

/** What an action produced: a sentence for the chat, plus optional detail lines (forecast, headlines…). */
data class ToolResult(
    val text: String,
    val lines: List<String> = emptyList(),
    val link: String? = null,
    val card: Card? = null,
    /** A web page per line (e.g. each headline's article), or null. */
    val lineLinks: List<String?> = emptyList(),
    /** What to say out loud, when different from the text plus lines. */
    val spoken: String? = null,
)

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
    /** Who this is about (for the photo). */
    val person: Person? = null,
    /** For CHOOSE_CONTACT: the people who match, and the name the user said (to remember the choice for). */
    val options: List<Person> = emptyList(),
    val alias: String? = null,
) {
    enum class Kind { CONFIRM, NEED_CONTACT, NEED_LOCATION, CHOOSE_CONTACT }
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
    private val useLocation: () -> Boolean = { true },
    private val shortcuts: PeopleShortcuts? = null,
    private val notebook: NotebookStore? = null,
    /** The app the user picked for a task, or null for Android's choice. */
    private val appFor: (AppTask) -> String? = { null },
) {
    private fun Intent.via(task: AppTask): Intent = apply { appFor(task)?.let { setPackage(it) } }
    suspend fun run(a: Action): ToolResult = try {
        when (a.type) {
            ActionType.ANSWER -> ToolResult(a.arg)
            ActionType.OPEN_APP -> openApp(a.arg)
            ActionType.WEB_SEARCH -> search(a.arg)
            ActionType.WEATHER -> weather(a.arg)
            ActionType.NEWS -> news(a.arg)
            ActionType.STOCK -> stock(a.arg)
            ActionType.TIMER -> timer(a.arg)
            ActionType.ALARM -> alarm(a.arg)
            ActionType.TEXT -> text(a.arg)
            ActionType.CALL -> call(a.arg)
            ActionType.RIDE -> ride(a.arg)
            ActionType.NAVIGATE -> navigate(a.arg)
            ActionType.PLAY -> play(a.arg)
            ActionType.HELP -> help()
            ActionType.REMEMBER -> remember(a.arg)
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

    private suspend fun weather(rawPlace: String): ToolResult {
        val placeArg = AgentCore.weatherPlace(rawPlace)
        // A named place wins; otherwise where you are (if allowed); otherwise your home town; otherwise ask.
        var coords: Pair<Double, Double>? = null
        var name: String
        var city: String
        if (placeArg.isBlank() && useLocation() && Location.granted(context)) {
            coords = Location.approximate(context)
        }
        if (coords != null) { name = "your area"; city = "your area" } else {
            val place = placeArg.ifBlank { homePlace() }
            if (place.isBlank()) return ToolResult("Which place?", card = Card(Card.Kind.NEED_LOCATION, "Weather for where you are?",
                "Allow approximate location (only rounded coordinates go to Open-Meteo), or set a home town in Settings → Online features. You can also ask “weather in Paris”."))
            val geo = JSONObject(Net.getText(OnlineSource.WEATHER,
                "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + enc(place), allowed))
            val hit = geo.optJSONArray("results")?.optJSONObject(0) ?: return ToolResult("I couldn't find a place called “$place”.")
            coords = hit.getDouble("latitude") to hit.getDouble("longitude")
            city = hit.optString("name").ifBlank { place }
            name = Speech.placeName(city, hit.optString("admin1"))
        }
        val f = fahrenheit()
        val w = JSONObject(Net.getText(OnlineSource.WEATHER,
            "https://api.open-meteo.com/v1/forecast?latitude=${coords.first}&longitude=${coords.second}" +
                "&current=temperature_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1" + if (f) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "", allowed))
        val cur = w.getJSONObject("current")
        val daily = w.getJSONObject("daily")
        val unit = if (f) "°F" else "°C"
        val sky = describe(cur.optInt("weather_code")); val temp = cur.getDouble("temperature_2m").toInt()
        val lines = listOf(
                "High ${daily.getJSONArray("temperature_2m_max").getDouble(0).toInt()}$unit · low ${daily.getJSONArray("temperature_2m_min").getDouble(0).toInt()}$unit",
                "Chance of rain ${daily.getJSONArray("precipitation_probability_max").optInt(0)}%",
                "Wind ${cur.getDouble("wind_speed_10m").toInt()} ${if (f) "mph" else "km/h"}",
                "Source: Open-Meteo",
            )
        // Spoken: just the city (no region), then the forecast lines.
        return ToolResult("$sky, $temp$unit in $name.", lines,
            spoken = Speech.forSpeech("$sky, $temp$unit in $city.", lines.take(2)))
    }

    private suspend fun news(topic: String): ToolResult {
        val feeds = listOf("https://feeds.npr.org/1001/rss.xml", "https://feeds.bbci.co.uk/news/rss.xml")
        val items = feeds.flatMap { url -> runCatching { rssItems(Net.getText(OnlineSource.NEWS, url, allowed)) }.getOrElse { if (it is OfflineException) throw it; emptyList() } }
        val words = topic.lowercase(Locale.US).split(' ').filter { it.length > 2 }
        val picked = (if (words.isEmpty()) items else items.filter { (t, _) -> words.any { t.lowercase(Locale.US).contains(it) } })
            .distinctBy { it.first }.take(6)
        if (picked.isEmpty()) return ToolResult(if (topic.isBlank()) "I couldn't get the news right now." else "No headlines about “$topic” right now.")
        val title = if (topic.isBlank()) "Top headlines:" else "Headlines about $topic:"
        // Each headline links to its article (tap to open it in the browser).
        return ToolResult(title, picked.map { "• ${it.first}" } + "Source: NPR, BBC", lineLinks = picked.map { it.second } + null,
            spoken = Speech.forSpeech(title, picked.take(3).map { it.first }))
    }

    /** (headline, article link) for each item of an RSS feed. */
    private fun rssItems(xml: String): List<Pair<String, String?>> {
        val p = Xml.newPullParser().apply { setInput(xml.reader()) }
        val out = ArrayList<Pair<String, String?>>()
        var inItem = false; var title: String? = null; var link: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when {
                p.eventType == XmlPullParser.START_TAG && p.name == "item" -> { inItem = true; title = null; link = null }
                p.eventType == XmlPullParser.START_TAG && inItem && p.name == "title" -> title = p.nextText().trim()
                p.eventType == XmlPullParser.START_TAG && inItem && p.name == "link" -> link = p.nextText().trim().takeIf { it.startsWith("https://") }
                p.eventType == XmlPullParser.END_TAG && p.name == "item" -> { inItem = false; title?.let { out += it to link } }
            }
        }
        return out
    }

    private suspend fun stock(symbolArg: String): ToolResult {
        val asked = symbolArg.trim().trim('?', '.', '!')
        if (asked.isEmpty()) return ToolResult("Which stock? Try a company or a ticker like AAPL.")
        // A ticker ("NVDA") is looked up directly; a company name ("nvidia", "Apple") is searched first.
        // If the direct lookup fails (Yahoo answers 404 for unknown tickers), search too.
        var name: String? = null
        var symbol = asked.uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '^' || it == '=' }
        val looksLikeTicker = asked.length <= 5 && !asked.contains(' ') && (asked == asked.uppercase(Locale.US) || asked.length <= 4)
        var meta: JSONObject? = if (looksLikeTicker) runCatching { chartMeta(symbol) }.getOrNull() else null
        if (meta == null) {
            val hit = findSymbol(asked) ?: return ToolResult("I couldn't find a stock called “$asked”.")
            symbol = hit.first; name = hit.second
            meta = chartMeta(symbol) ?: return ToolResult("I couldn't get a price for $symbol.")
        }
        val price = meta.getDouble("regularMarketPrice")
        val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", Double.NaN))
        val change = if (prev.isNaN()) "" else " (%+.2f%% today)".format((price - prev) / prev * 100)
        val who = name?.let { "$it ($symbol)" } ?: symbol
        return ToolResult("$who is %.2f %s%s.".format(price, meta.optString("currency", "USD"), change),
            listOf(meta.optString("fullExchangeName"), "Source: Yahoo Finance").filter { it.isNotBlank() },
            spoken = "${name ?: symbol} is %.2f %s%s.".format(price, if (meta.optString("currency", "USD") == "USD") "dollars" else meta.optString("currency"),
                if (prev.isNaN()) "" else ", %s %.1f percent today".format(if (price >= prev) "up" else "down", kotlin.math.abs((price - prev) / prev * 100))))
    }

    private suspend fun chartMeta(symbol: String): JSONObject? = JSONObject(Net.getText(OnlineSource.STOCKS,
        "https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?range=1d&interval=1d", allowed))
        .getJSONObject("chart").optJSONArray("result")?.optJSONObject(0)?.optJSONObject("meta")

    /** Company name → (ticker, name) with Yahoo's symbol search, preferring shares and funds over futures. */
    private suspend fun findSymbol(query: String): Pair<String, String?>? {
        val quotes = JSONObject(Net.getText(OnlineSource.STOCKS,
            "https://query1.finance.yahoo.com/v1/finance/search?q=${enc(query)}&quotesCount=6&newsCount=0", allowed)).optJSONArray("quotes") ?: return null
        val all = (0 until quotes.length()).mapNotNull { quotes.optJSONObject(it) }
        val best = all.firstOrNull { it.optString("quoteType") in setOf("EQUITY", "ETF", "MUTUALFUND", "INDEX", "CRYPTOCURRENCY") } ?: all.firstOrNull()
        return best?.optString("symbol")?.takeIf { it.isNotBlank() }?.let { it to best.optString("shortname").ifBlank { null } }
    }

    private fun timer(arg: String): ToolResult {
        val seconds = arg.toIntOrNull() ?: AgentCore.parseDuration(arg) ?: return ToolResult("How long should the timer be?")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        val text = when { seconds % 3600 == 0 -> "${seconds / 3600} hour"; seconds % 60 == 0 -> "${seconds / 60} minute"; else -> "$seconds second" }
        return ToolResult("Timer set for ${text}${if (text.startsWith("1 ")) "" else "s"}.")
    }

    private fun alarm(arg: String): ToolResult {
        val now = java.time.LocalTime.now()
        val (h, m) = AgentCore.parseClock(arg, now.hour * 60 + now.minute)
            ?: return ToolResult("What time should the alarm be?")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(i) }.isFailure) return ToolResult("No clock app can set alarms.")
        val time = java.time.LocalTime.of(h, m).format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
        return ToolResult("Alarm set for $time.")
    }

    // ---- Actions in other apps: always confirmed first, then handed to the user's own app ----------------------

    private fun start(i: Intent): Boolean = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); opened() }.isSuccess

    /** Finds who [who] is: a number as typed, a contact, or a card asking for contacts access / a pick. */
    private fun resolve(who: String, verb: String, then: (Person) -> ToolResult): ToolResult {
        if (TextArgs.isNumber(who)) return then(Person(who, who, ""))
        val needContact = { why: String ->
            ToolResult(why, card = Card(Card.Kind.NEED_CONTACT, "Who should I $verb?",
                "Allow contacts (they stay on this phone and are never shown to the AI model), or pick one person.",
                withContact = then))
        }
        // A saved shortcut ("Jen" → this Jennifer, "wife" → …) wins, with or without contacts access.
        shortcuts?.find(who)?.let { return then(it) }
        if (!contacts.granted()) return needContact("To $verb $who I need to know who that is.")
        val people = contacts.candidates(who)
        return when (people.size) {
            0 -> needContact("I couldn't find “$who” in your contacts.")
            1 -> then(people.first())
            else -> ToolResult("Which $who?", card = Card(Card.Kind.CHOOSE_CONTACT, "Which $who should I $verb?",
                "Tick the box to always use that person for “$who”.", options = people, alias = who, withContact = then))
        }
    }

    private fun text(arg: String): ToolResult {
        // "text Jen on WhatsApp …" picks that app for this message.
        val (via, rest) = Messenger.strip(arg)
        val parts = TextArgs.split(rest) { shortcuts?.find(it) != null || (contacts.granted() && contacts.nameExists(it)) }
        return resolve(parts.recipient, "text") { p -> textCard(p, parts.message, via) }
    }

    private fun textCard(p: Person, message: String, via: Messenger? = null): ToolResult {
        val chosen = appFor(AppTask.MESSAGES)
        val installed = Messenger.installed(context)
        val messenger = via ?: Messenger.forPackage(chosen)
        val pkg = messenger?.let { if (chosen in it.packages) chosen else installed[it] }
        if (via != null && pkg == null) return ToolResult("${via.label} isn't installed on this phone.")
        return ToolResult(
            "Ready to text ${p.name}.",
            card = Card(Card.Kind.CONFIRM, "Text ${p.name}", listOf(p.number, p.label, messenger?.label.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                if (messenger != null) "Open ${messenger.label}" else "Send", message, person = p,
                run = { m ->
                    if (messenger != null && pkg != null) messengerText(messenger, pkg, p, m.orEmpty()) else {
                        val ok = start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", m.orEmpty()).via(AppTask.MESSAGES))
                        if (ok) "Opened your messaging app with the text to ${p.name}. Tap send there." else "No messaging app found."
                    }
                }),
        )
    }

    /**
     * Opens a chat with [p] in Signal / WhatsApp / Telegram. Apps that can't be handed the message get it on the
     * clipboard. Without a full international number, the text is shared to the app to pick the chat there.
     */
    private fun messengerText(app: Messenger, pkg: String, p: Person, message: String): String {
        val name = app.label
        if (message.isNotBlank() && !app.prefills) context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Message", message))
        val e164 = e164(p.number)
        if (e164 != null && start(app.chat(e164, message).setPackage(pkg)))
            return when {
                message.isBlank() || app.prefills -> "Opened $name with ${p.name}." + if (message.isNotBlank()) " Tap send there." else ""
                else -> "Opened $name with ${p.name}. Your message is copied: paste it and tap send."
            }
        if (message.isNotBlank() && start(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message).setPackage(pkg)))
            return "Opened $name with your message. Pick ${p.name} and tap send."
        return "Couldn't open $name for ${p.name}."
    }

    /** The number in international form (+15551234567), using the phone's country for local numbers. */
    private fun e164(number: String): String? {
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        val iso = (tm?.simCountryIso?.ifBlank { null } ?: tm?.networkCountryIso?.ifBlank { null } ?: java.util.Locale.getDefault().country).uppercase()
        return android.telephony.PhoneNumberUtils.formatNumberToE164(number, iso)
    }

    private fun call(who: String) = resolve(who, "call") { p ->
        ToolResult("Ready to call ${p.name}.", card = Card(Card.Kind.CONFIRM, "Call ${p.name}",
            listOf(p.number, p.label).filter { it.isNotBlank() }.joinToString(" · "), "Call", person = p,
            run = { if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number))).via(AppTask.PHONE))) "Opening the dialer for ${p.name}." else "No phone app found." }))
    }

    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun ride(arg: String): ToolResult {
        val colon = arg.indexOf(':')
        var app = if (colon > 0) arg.substring(0, colon).trim().lowercase() else "uber"
        val dest = (if (colon > 0) arg.substring(colon + 1) else arg).trim()
        if (dest.isBlank()) return ToolResult("Where to?")
        if (colon <= 0) appFor(AppTask.RIDES)?.let { if (it == LYFT) app = "lyft" }
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
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))).via(AppTask.MAPS))) ToolResult("Opening directions to $dest.")
        else ToolResult("No maps app found.")
    }

    private fun play(what: String): ToolResult {
        if (what.isBlank()) return ToolResult("What should I play?")
        val i = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, what).putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .via(AppTask.MUSIC)
        return if (start(i)) ToolResult("Playing $what.") else ToolResult("No music app can play “$what”. Pick one in Settings → Assistant → Apps.")
    }

    /** The model wants to save a note: always ask first, and let the user edit it. */
    private fun remember(note: String): ToolResult {
        val nb = notebook ?: return ToolResult("I can't keep notes here.")
        if (note.isBlank()) return ToolResult("What should I remember?")
        if (!nb.book.value.hasRoomFor(note.length + 4))
            return ToolResult("My notebook is full. Tidy it in Settings → Assistant → Notebook, then ask again.")
        return ToolResult("Save this to my notebook?", card = Card(Card.Kind.CONFIRM, "Remember this?",
            "Kept on this phone only. You can edit it in Settings → Assistant → Notebook.", "Save", note,
            run = { m -> if (nb.save(nb.book.value.addNote(m ?: note))) "Saved. I'll keep that in mind." else "My notebook is full." }))
    }

    private fun help(): ToolResult {
        fun app(t: AppTask) = appFor(t)?.let { " (${AppChoices.label(context, it)})" }.orEmpty()
        return ToolResult("Here's what I can do:", listOf(
            "• Open apps: “open camera”",
            "• Text and call people${app(AppTask.MESSAGES)}: “text Jen on my way”, “call Jen”",
            "• Rides${app(AppTask.RIDES)}: “get me an Uber to the airport”",
            "• Directions${app(AppTask.MAPS)}: “directions to Costco”",
            "• Music${app(AppTask.MUSIC)}: “play some jazz”",
            "• Timers and maths: “timer 10 minutes”, “18% of 64”",
            "• Weather, news, stocks, quick answers (when allowed online)",
            "• Anything else: I'll answer with the model you picked",
            "• Teach me: “remember I'm vegetarian”, “when I say goodnight, set a timer for 8 hours and play rain sounds”, “when I say Jen I mean Jennifer Lopez”, “what do you remember?”",
            "I always ask before sending, calling or booking.",
        ), spoken = "I can open apps, text and call people, get rides and directions, play music, set timers, do maths, and get the weather, news and stock prices. I always ask before sending, calling or booking.")
    }

    companion object { private const val UBER = "com.ubercab"; private const val LYFT = "me.lyft.android" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun describe(code: Int) = when (code) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"; 61, 63, 65, 66, 67 -> "Rain"; 71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"; 85, 86 -> "Snow showers"; 95, 96, 99 -> "Thunderstorms"; else -> "Weather"
    }
}
