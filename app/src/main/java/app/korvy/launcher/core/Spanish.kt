package app.korvy.launcher.core

/**
 * Spanish quick commands, without the model: rewrites a Spanish request into the English form the existing
 * parsers understand ("pon una alarma a las 7 de la mañana" → "set an alarm for 7:00am"). Names, places,
 * messages and song requests pass through as the user typed them. Null when it isn't a known Spanish command,
 * so the request goes on to the model as before.
 *
 * Only the sentence frame is translated, never the user's content, so it can't change who a message goes to.
 */
object Spanish {
    fun toEnglish(input: String): String? {
        val orig = clean(input)
        if (orig.isEmpty()) return null
        val f = fold(orig)
        if (f.length != orig.length) return null // folding must keep positions (it does for Latin text)
        for ((re, build) in RULES) {
            val m = re.matchEntire(f) ?: continue
            build(Match(m, orig))?.let { return it }
        }
        return null
    }

    /** Common app names in Spanish → the English label, for phones whose apps are labelled in English. */
    fun appSynonym(name: String): String? = APPS[fold(name.trim()).removePrefix("la ").removePrefix("el ").removePrefix("mi ")]

    // ---- Parts ------------------------------------------------------------------------------------------------

    /** "7", "7:30", "7 y media", "8 de la noche", "mediodía" → "7:00am"-style English clock text, or null. */
    fun clock(text: String): String? {
        var t = fold(text).trim().replace(Regex("\\s+"), " ")
        t = t.removePrefix("al ").removePrefix("el ").removePrefix("a ").removePrefix("las ").removePrefix("la ")
        if (!t.endsWith("de la manana")) t = t.removeSuffix(" manana").removeSuffix(" para manana")
        t = t.removeSuffix(" hoy").removeSuffix(" en punto").replace(" en punto ", " ")
        if (t == "mediodia" || t == "las doce del mediodia") return "noon"
        if (t == "medianoche") return "midnight"
        val m = CLOCK.matchEntire(t) ?: return null
        var h = number(m.groupValues[1]) ?: return null
        var min = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (val plus = m.groupValues[3]) {
            "" -> {}
            "media" -> min = 30
            "cuarto" -> min = 15
            else -> min = number(plus) ?: return null
        }
        when (val less = m.groupValues[4]) {
            "" -> {}
            else -> { val sub = if (less == "cuarto") 15 else number(less) ?: return null; h -= 1; min = 60 - sub }
        }
        if (h < 0) h += 12
        if (h > 23 || min !in 0..59) return null
        val part = m.groupValues[5]
        val ampm = m.groupValues[6].filter { it.isLetter() }
        val suffix = when {
            part == "manana" || part == "madrugada" || ampm == "am" -> "am"
            part == "tarde" || part == "noche" || ampm == "pm" -> "pm"
            else -> ""
        }
        if (suffix.isNotEmpty() && h !in 1..12) return null
        return "$h:${min.toString().padStart(2, '0')}$suffix"
    }

    /** "5 minutos", "una hora y media", "1 hora y 30 minutos", "media hora" → "5 minutes"-style text, or null. */
    fun duration(text: String): String? {
        var t = fold(text).trim().replace(Regex("\\s+"), " ")
        t = t.replace(Regex("\\b(?:una?|1) hora y media\\b"), "90 minutos").replace(Regex("\\bhora y media\\b"), "90 minutos")
            .replace(Regex("\\bmedia hora\\b"), "30 minutos").replace(Regex("\\bmedio minuto\\b"), "30 segundos")
            .replace(Regex("\\b(\\d+|\\p{L}+) minutos? y medio\\b")) { r -> number(r.groupValues[1])?.let { "${it * 60 + 30} segundos" } ?: r.value }
        val parts = Regex("(\\d+(?:[.,]\\d+)?|\\p{L}+)\\s+(horas?|hrs?|minutos?|mins?|segundos?|segs?)\\b").findAll(t).toList()
        if (parts.isEmpty()) return t.toIntOrNull()?.let { "$it minutes" }
        val rest = parts.fold(t) { acc, p -> acc.replace(p.value, "") }.replace(Regex("\\by\\b|,"), "").trim()
        if (rest.isNotEmpty()) return null
        val out = parts.map { p ->
            val n = p.groupValues[1].replace(',', '.').let { it.toDoubleOrNull()?.let { d -> fmt(d) } ?: number(it)?.toString() } ?: return null
            val unit = when (p.groupValues[2].first()) { 'h' -> "hours"; 'm' -> "minutes"; else -> "seconds" }
            "$n $unit"
        }
        return out.joinToString(" ")
    }

    // ---- Rules ------------------------------------------------------------------------------------------------

    private class Match(private val m: MatchResult, private val orig: String) {
        /** Group [i] from the original text (with its case and accents). */
        fun g(i: Int): String = m.groups[i]?.range?.let { orig.substring(it.first, it.last + 1).trim() } ?: ""
        fun f(i: Int): String = m.groupValues[i]
    }

    private val SEP = "(?:,\\s*|\\s+)"
    private const val PLEASE = "(?:por favor,?\\s+)?"
    private const val VERBS = "(?:pon|ponme|abre|abreme|llama|llamale|escribe|escribele|manda|mandale|envia|enviale|reproduce|toca|busca|apaga|enciende|activa|dime|di|recuerda|crea|programa|despiertame|avisame|pideme|llevame|como llego)"

    private fun re(p: String) = Regex("^$PLEASE$p$")

    /** "al aeropuerto" → "el aeropuerto" (the capture loses the article that "al" contained). */
    private fun dest(prep: String, place: String) = when {
        prep.trim().endsWith("al") -> "el $place"
        prep.trim().endsWith(" la") -> "la $place"
        else -> place
    }

    private val RULES: List<Pair<Regex, (Match) -> String?>> = listOf(
        re("(?:ayuda|ayudame|que (?:puedes|sabes) hacer|que haces|que cosas puedes hacer)") to { _ -> "what can you do" },
        re("(?:que (?:recuerdas|sabes de mi|has aprendido)|(?:muestra|muestrame|ensename) (?:tu|mi) libreta)") to { _ -> "what do you remember" },
        re("olvida(?:te)?(?: de| lo de| que)? [\"“']?(.+?)[\"”']?") to { m -> "forget ${m.g(1)}" },
        // Teaching. "cuando diga Jen me refiero a Jennifer López" (alias) before "cuando diga X, haz Y" (phrase).
        re("(?:recuerda que,? )?(?:a partir de ahora,? )?cuando (?:yo )?diga [\"“']?(.+?)[\"”']?,? (?:me refiero a|quiero decir|hablo de|significa|es) (.+)") to
            { m -> "when I say ${m.g(1)} I mean ${m.g(2)}" },
        re("(?:recuerda que,? )?(?:a partir de ahora,? )?cuando (?:yo )?diga (.+)") to { m -> phrase(m.g(1)) },
        re("(?:recuerda|recuerdalo|acuerdate de|anota|apunta|ten en cuenta)(?: que)?:? (.+)") to { m -> "remember ${m.g(1)}" },
        // Maths.
        re("(?:cuanto es |calcula )?(?:el |un )?(\\d+(?:[.,]\\d+)?) ?(?:%|por ?ciento) (?:de|del) (\\d+(?:[.,]\\d+)?)") to
            { m -> "${m.f(1).replace(',', '.')}% of ${m.f(2).replace(',', '.')}" },
        re("(?:cuanto es |calcula )?(?:una |la )?propina (?:del? )?(\\d+(?:[.,]\\d+)?) ?(?:%|por ?ciento) (?:sobre|de|para|en) \\$?(\\d+(?:[.,]\\d+)?)") to
            { m -> "${m.f(1).replace(',', '.')}% tip on ${m.f(2).replace(',', '.')}" },
        re("(?:cuanto (?:es|son|da)|calcula|calculame) (.+)") to { m -> expression(m.f(1))?.let { "what is $it" } },
        // Rides before calls ("llámame un uber" is a ride).
        re("(?:pideme|pide|pidame|consigueme|reservame|reserva|llamame|llama|necesito|quiero|busca(?:me)?) (?:un |una )?(uber|lyft|taxi|viaje|carro|coche|auto)(?: (a la|al|a|hasta|para ir al?|para) (.+))") to
            { m -> val app = when (m.f(1)) { "uber" -> "uber"; "lyft" -> "lyft"; else -> "ride" }; "get me an $app to ${dest(m.f(2), m.g(3))}" },
        // Alarms and timers before "pon …" (music).
        re("(?:(?:pon(?:me)?|programa|crea|configura|activa|ajusta) (?:una |la |mi |un )?(?:alarma|despertador)|alarma|despiertame)(?: (?:para las|para la|para el|para|a las|a la|al|a))? (.+)") to
            { m -> clock(m.g(1))?.let { "set an alarm for $it" } },
        re("(?:(?:pon(?:me)?|programa|crea|activa|inicia|arranca) (?:un |el )?temporizador|temporizador|cronometro|avisame en|recuerdame en)(?: (?:de|por|para|en))? (.+)") to
            { m -> duration(m.g(1))?.let { "timer $it" } },
        re("(?!.*\\b(?:alarma|temporizador|despertador|cronometro)\\b)(?:pon(?:me)?|reproduce|toca|quiero escuchar|escuchar) (?:algo de |un poco de |musica de |la cancion |el disco |el album )?(.+)") to { m -> "play ${m.g(1)}" },
        // Texts: "escríbele a Jen que voy tarde" / "manda un mensaje a Jen: voy tarde" / "escríbele a Jen voy tarde".
        re("(?:escribele|escribe|escribirle|mandale|manda|enviale|envia|textea(?:le)?)(?: un (?:mensaje|texto|sms|whatsapp))?(?: a| al)? (.+?)(?: que dice| que diga| diciendo| diciendole| que|:)\\s*(.+)") to
            { m -> "text ${m.g(1)}: ${m.g(2).trim('"', '“', '”')}" },
        re("(?:mensaje|un mensaje) (?:a|al|para) (.+?)(?: que dice| que diga| diciendo| que|:)\\s*(.+)") to { m -> "text ${m.g(1)}: ${m.g(2)}" },
        re("(?:escribele|escribe|escribirle|mandale|manda|enviale|envia|textea(?:le)?)(?: un (?:mensaje|texto|sms|whatsapp))? (?:a|al) (.+)") to { m -> "text ${m.g(1)}" },
        re("(?:llama(?:r|le)?|marca(?:le)?|telefonea(?:le)?|haz una llamada) (?:a |al )?(.+)") to { m -> "call ${m.g(1)}" },
        re("(?:como (?:llego|llegar|voy|ir|se llega)|llevame|indicaciones(?: para (?:ir|llegar))?|navega|navegar|ruta|direcciones)(?: (a la|al|a|hasta|para|hacia)) (.+)") to
            { m -> "directions to ${dest(m.f(1), m.g(2))}" },
        re("(?:(?:que|como|cual)(?: es| esta| estara)? )?(?:el |la )?(?:tiempo|clima|pronostico)(?: (?:hace|hay|va a hacer|esta|estara|tendremos))?(?: (?:en|de|para) (.+?))?(?: (?:hoy|manana|esta noche|ahora|ahorita|este fin de semana))?") to
            { m -> if (m.g(1).isEmpty()) "weather" else "weather in ${m.g(1)}" },
        re("(?:dime |dame |cuales son )?(?:las )?(?:ultimas )?(?:noticias|titulares)(?: de hoy)?(?: (?:de|sobre|acerca de) (.+))?") to
            { m -> if (m.g(1).isEmpty()) "news" else "news about ${m.g(1)}" },
        re("(?:(?:cual es|como esta|como estan|como van?|a cuanto estan?|cuanto valen?|dime) )?(?:el |la )?(?:precio |valor |cotizacion )?(?:de )?(?:la |las )?(?:accion|acciones) (?:de |del )?(.+?)(?: hoy| ahora)?") to
            { m -> "stock price of ${m.g(1)}" },
        re("(?:la )?cotizacion (?:de |del )?(.+?)") to { m -> "stock price of ${m.g(1)}" },
        re("(?:busca|buscar|buscame|googlea|investiga)(?: en (?:internet|la web|google))?(?: sobre| informacion sobre)? (.+)") to { m -> "search ${m.g(1)}" },
        re("(?:abre|abrir|abreme|inicia|lanza|ejecuta|arranca)(?: la| el| los| las| mi| mis)? (.+?)(?: app| aplicacion)?") to { m -> "open ${m.g(1)}" },
    )

    /** "buenas noches, pon un temporizador de 8 horas y pon sonido de lluvia" → a "when I say" lesson. */
    private fun phrase(rest: String): String? {
        val f = fold(rest)
        val quoted = Regex("^[\"“'](.+?)[\"”'],?\\s+(.+)$").matchEntire(rest)
        val (trigger, steps) = when {
            quoted != null -> quoted.groupValues[1] to quoted.groupValues[2]
            rest.contains(',') -> rest.substringBefore(',').trim() to rest.substringAfter(',').trim()
            else -> {
                val v = Regex("\\s$VERBS\\b").find(f) ?: return null
                rest.substring(0, v.range.first).trim() to rest.substring(v.range.first + 1).trim()
            }
        }
        if (trigger.isBlank() || steps.isBlank()) return null
        val parts = Regex("\\s*;\\s*|,?\\s+y\\s+(?:luego|despues)\\s+|,?\\s+(?:luego|despues)\\s+|,\\s+|\\s+y\\s+(?=$VERBS\\b)", RegexOption.IGNORE_CASE)
            .let { sep -> splitKeeping(steps, fold(steps), sep) }.filter { it.isNotBlank() }
        return "when I say \"$trigger\", ${parts.joinToString("; ")}"
    }

    /** Splits [orig] at the places [sep] matches in its folded copy [folded] (same length). */
    private fun splitKeeping(orig: String, folded: String, sep: Regex): List<String> {
        val out = mutableListOf<String>(); var start = 0
        sep.findAll(folded).forEach { out += orig.substring(start, it.range.first).trim(); start = it.range.last + 1 }
        out += orig.substring(start).trim()
        return out
    }

    /** "5 más 3 por 2" → "5 + 3 * 2"; null without digits and an operator. */
    private fun expression(f: String): String? {
        val e = f.replace(Regex("\\bmultiplicado por\\b"), "*").replace(Regex("\\bdividido (?:entre|por)\\b"), "/")
            .replace(Regex("\\belevado a\\b"), "^").replace(Regex("\\bmas\\b"), "+").replace(Regex("\\bmenos\\b"), "-")
            .replace(Regex("\\bpor\\b"), "*").replace(Regex("\\bentre\\b"), "/").replace(Regex("(?<=\\d),(?=\\d)"), ".")
            .replace(Regex("\\s+"), " ").trim()
        if (e.none { it.isDigit() } || e.none { it in "+-*/^" }) return null
        if (e.any { it.isLetter() }) return null
        return e
    }

    private fun clean(s: String): String {
        var t = s.trim().trimStart('¿', '¡').trimEnd('?', '.', '!', '¡', '¿').trim()
        t = t.replace(Regex("^(?:oye|hola|hey)(?:\\s+korvy)?,?\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^korvy,?\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",?\\s+por favor$", RegexOption.IGNORE_CASE), "")
        return t.trim()
    }

    private val FOLD = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n',
        'à' to 'a', 'è' to 'e', 'ì' to 'i', 'ò' to 'o', 'ù' to 'u')

    /** Lowercase without accents, same length as the input (so match positions map back to the original). */
    fun fold(s: String): String {
        val lower = s.lowercase()
        if (lower.length != s.length) return lower
        return buildString(lower.length) { lower.forEach { append(FOLD[it] ?: it) } }
    }

    private val CLOCK = Regex("^(\\d{1,2}|\\p{L}+)(?::(\\d{2}))?(?: y (media|cuarto|\\d{1,2}|\\p{L}+))?(?: menos (cuarto|\\d{1,2}|\\p{L}+))?" +
        "(?: (?:de la |del |en la )(manana|tarde|noche|madrugada)| ?(am|pm|a\\. ?m\\.?|p\\. ?m\\.?))?$")

    private val NUMBERS = mapOf("un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "quince" to 15,
        "veinte" to 20, "veinticinco" to 25, "treinta" to 30, "cuarenta" to 40, "cuarenta y cinco" to 45, "cincuenta" to 50,
        "sesenta" to 60, "noventa" to 90, "cien" to 100)

    private fun number(s: String): Int? = s.toIntOrNull() ?: NUMBERS[s]

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    private val APPS = mapOf("camara" to "Camera", "ajustes" to "Settings", "configuracion" to "Settings", "reloj" to "Clock",
        "calculadora" to "Calculator", "calendario" to "Calendar", "contactos" to "Contacts", "telefono" to "Phone",
        "mensajes" to "Messages", "fotos" to "Photos", "galeria" to "Gallery", "correo" to "Gmail", "mapas" to "Maps",
        "musica" to "Music", "notas" to "Notes", "archivos" to "Files", "tienda" to "Play Store", "grabadora" to "Recorder",
        "linterna" to "Flashlight", "tiempo" to "Weather", "clima" to "Weather", "navegador" to "Chrome")
}
