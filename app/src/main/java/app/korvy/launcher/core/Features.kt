package app.korvy.launcher.core

/**
 * Client-side feature flags for work in progress. A flag that is off hides the feature everywhere (Settings, Pro
 * list, background services) but keeps its code, so it can be tried in Developer options (About → tap the version
 * 7 times) and switched on for everyone later by changing [default]. Nothing is fetched from a server.
 */
enum class Feature(val id: String, val title: String, val note: String, val default: Boolean) {
    WAKE_WORD("wake_word", "Wake word (“Hey Korvy”)",
        "Experimental: hands-free listening. Not reliable yet.", default = false),
    ;

    /** On if switched on in Developer options, else its default. */
    fun on(enabled: Set<String>, disabled: Set<String> = emptySet()) = when (id) {
        in enabled -> true
        in disabled -> false
        else -> default
    }
}
