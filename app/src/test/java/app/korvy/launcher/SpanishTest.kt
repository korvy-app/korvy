package app.korvy.launcher

import app.korvy.launcher.core.Action
import app.korvy.launcher.core.ActionType
import app.korvy.launcher.core.AgentCore
import app.korvy.launcher.core.Calc
import app.korvy.launcher.core.Lesson
import app.korvy.launcher.core.Spanish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanishTest {
    private fun cmd(es: String): Action? = Spanish.toEnglish(es)?.let { AgentCore.quickParse(it) }
    private fun check(es: String, type: ActionType, arg: String) {
        val a = cmd(es)
        assertEquals("$es → ${Spanish.toEnglish(es)}", Action(type, arg), a)
    }

    @Test fun appsCallsAndTexts() {
        check("abre la cámara", ActionType.OPEN_APP, "cámara")
        check("Abre WhatsApp", ActionType.OPEN_APP, "WhatsApp")
        check("llama a Jen", ActionType.CALL, "Jen")
        check("llámale a mamá", ActionType.CALL, "mamá")
        check("escríbele a Jen que voy tarde", ActionType.TEXT, "Jen: voy tarde")
        check("mándale un mensaje a Lukas: ya llegué", ActionType.TEXT, "Lukas: ya llegué")
        check("envía un mensaje a Ana diciendo nos vemos a las 8", ActionType.TEXT, "Ana: nos vemos a las 8")
        check("escríbele a Jen voy en camino", ActionType.TEXT, "Jen voy en camino")
        assertEquals("Camera", Spanish.appSynonym("la cámara"))
    }

    @Test fun ridesDirectionsMusic() {
        check("pídeme un Uber al aeropuerto", ActionType.RIDE, "uber: el aeropuerto")
        check("llámame un taxi a la estación", ActionType.RIDE, "uber: la estación")
        check("consígueme un Lyft hasta Costco", ActionType.RIDE, "lyft: Costco")
        check("cómo llego a Costco", ActionType.NAVIGATE, "Costco")
        check("llévame al centro", ActionType.NAVIGATE, "el centro")
        check("pon algo de jazz", ActionType.PLAY, "jazz")
        check("reproduce Bad Bunny", ActionType.PLAY, "Bad Bunny")
    }

    @Test fun alarmsAndTimers() {
        check("pon una alarma a las 7 de la mañana", ActionType.ALARM, "7:00am")
        check("pon una alarma para las 8 de la noche", ActionType.ALARM, "8:00pm")
        check("despiértame a las 6 y media", ActionType.ALARM, "6:30")
        check("alarma a las 7 menos cuarto de la mañana", ActionType.ALARM, "6:45am")
        check("pon una alarma a las siete mañana", ActionType.ALARM, "7:00")
        check("pon una alarma al mediodía", ActionType.ALARM, "noon")
        check("pon un temporizador de 5 minutos", ActionType.TIMER, "300")
        check("temporizador de una hora y media", ActionType.TIMER, "5400")
        check("pon un temporizador de 1 hora y 30 minutos", ActionType.TIMER, "5400")
        check("temporizador de media hora", ActionType.TIMER, "1800")
        check("avísame en 10 segundos", ActionType.TIMER, "10")
        // An alarm it can't read must not turn into music.
        assertNull(cmd("pon una alarma cuando salga el sol"))
    }

    @Test fun onlineThings() {
        check("¿qué tiempo hace en Madrid?", ActionType.WEATHER, "Madrid")
        check("cómo está el clima hoy", ActionType.WEATHER, "")
        check("clima en Ciudad de México", ActionType.WEATHER, "Ciudad de México")
        check("noticias", ActionType.NEWS, "")
        check("noticias sobre tecnología", ActionType.NEWS, "tecnología")
        check("¿a cuánto están las acciones de Nvidia?", ActionType.STOCK, "nvidia".let { "Nvidia" })
        check("precio de la acción de AAPL", ActionType.STOCK, "AAPL")
        check("busca recetas de tamales", ActionType.WEB_SEARCH, "recetas de tamales")
        check("¿qué puedes hacer?", ActionType.HELP, "")
        check("oye Korvy, abre Spotify por favor", ActionType.OPEN_APP, "Spotify")
    }

    @Test fun maths() {
        assertEquals("18% of 64 is 11.52.", Calc.answer(Spanish.toEnglish("18 por ciento de 64")!!))
        assertEquals("18% of 64 is 11.52.", Calc.answer(Spanish.toEnglish("cuánto es el 18% de 64")!!))
        assertEquals("5 + 3 * 2 = 11", Calc.answer(Spanish.toEnglish("cuánto es 5 más 3 por 2")!!))
        assertEquals("10 / 4 = 2.5", Calc.answer(Spanish.toEnglish("calcula 10 entre 4")!!))
        assertTrue(Calc.answer(Spanish.toEnglish("propina del 15% sobre 40")!!)!!.contains("$6.00"))
    }

    @Test fun teaching() {
        assertEquals(Lesson.Note("soy vegetariano"), Lesson.parse(Spanish.toEnglish("recuerda que soy vegetariano")!!))
        assertEquals(Lesson.Alias("Jen", "Jennifer López"), Lesson.parse(Spanish.toEnglish("cuando diga Jen me refiero a Jennifer López")!!))
        val p = Lesson.parse(Spanish.toEnglish("cuando diga buenas noches, pon un temporizador de 8 horas y pon sonido de lluvia")!!)
        assertEquals(Lesson.NewPhrase("buenas noches", listOf("pon un temporizador de 8 horas", "pon sonido de lluvia")), p)
        // Each saved step still runs as a Spanish command.
        assertEquals(Action(ActionType.TIMER, "28800"), cmd("pon un temporizador de 8 horas"))
        assertEquals(Lesson.Forget("el té"), Lesson.parse(Spanish.toEnglish("olvida el té")!!))
        assertEquals(Lesson.Show, Lesson.parse(Spanish.toEnglish("¿qué recuerdas?")!!))
    }

    @Test fun notSpanishCommands() {
        // English and open questions are left alone (the English parser or the model handles them).
        assertNull(Spanish.toEnglish("open camera"))
        assertNull(Spanish.toEnglish("what is the capital of France"))
        assertNull(Spanish.toEnglish("¿por qué el cielo es azul?"))
        assertNull(Spanish.toEnglish("cuánto es la vida"))
    }
}
