package com.hiddenoutlier.launcher.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Every online source the app can contact, with the only hosts it may reach. Online features are off by default
 * (Settings → Online features); each source has its own switch. Nothing else in the app opens connections.
 */
enum class OnlineSource(val title: String, val hosts: Set<String>, val detail: String, val optIn: Boolean = false) {
    MODELS("Model downloads", setOf("huggingface.co", "hf.co"), // and their download CDNs (*.huggingface.co, *.hf.co)
        "Downloads the assistant model you pick from Hugging Face."),
    VOICES("Voice downloads", setOf("github.com", "githubusercontent.com"), // and *.githubusercontent.com (release files)
        "Downloads natural voices (Kokoro, Piper) from the sherpa-onnx project's releases on GitHub."),
    WEATHER("Weather", setOf("api.open-meteo.com", "geocoding-api.open-meteo.com"),
        "Open-Meteo: free, no account. Sends the place name you ask about."),
    NEWS("News headlines", setOf("feeds.npr.org", "feeds.bbci.co.uk"),
        "Reads the public NPR and BBC news feeds."),
    STOCKS("Stock prices", setOf("query1.finance.yahoo.com"),
        "Yahoo Finance's public quote service (unofficial). Yahoo sees which tickers you ask about."),
    ANSWERS("Quick answers", setOf("api.duckduckgo.com"),
        "DuckDuckGo instant answers for web searches the assistant runs. No account, no tracking."),
    /** Opt-in even when Online features is on: it sends what you type, letter by letter. */
    SUGGESTIONS("Web search suggestions", setOf("duckduckgo.com"),
        "Suggestions from DuckDuckGo while you type in search. Sends each letter you type, so it stays off unless you turn it on.",
        optIn = true),
}

class OfflineException(val source: OnlineSource) : IOException("${source.title} is turned off in Settings → Online features")

/** The single gate for network access. [allowed] reflects the user's switches at the time of the call. */
object Net {
    private const val UA = "Launcher/0.3 (privacy launcher; no tracking)"

    fun check(source: OnlineSource, url: String, allowed: (OnlineSource) -> Boolean): URL {
        if (!allowed(source)) throw OfflineException(source)
        val u = URL(url)
        if (u.protocol != "https" || source.hosts.none { u.host == it || u.host.endsWith(".$it") }) {
            throw IOException("Blocked: ${u.host} is not one of ${source.title}'s listed hosts")
        }
        return u
    }

    suspend fun getText(source: OnlineSource, url: String, allowed: (OnlineSource) -> Boolean): String = withContext(Dispatchers.IO) {
        val c = open(source, url, allowed)
        try {
            if (c.responseCode !in 200..299) throw IOException("${source.title}: HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Opens a connection, following redirects only to hosts of the same source (checked at every hop). */
    fun open(source: OnlineSource, url: String, allowed: (OnlineSource) -> Boolean, range: Long = 0): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = check(source, current, allowed).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
            val code = c.responseCode
            if (code in 300..399) {
                current = URL(URL(current), c.getHeaderField("Location")).toString()
                c.disconnect()
            } else return c
        }
        throw IOException("Too many redirects")
    }
}
