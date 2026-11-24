package app.korvy.launcher.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Plain-code maths for the assistant and search: small models get arithmetic wrong, so numbers never go to the
 * model. Handles "18% of 64", "18 percent of 64", "15% tip on 42.50", and expressions with + - * / ^ ( ) x ×.
 */
object Calc {
    private val PERCENT_OF = Regex("""^(?:what(?:'s| is)\s+)?(-?\d+(?:\.\d+)?)\s*(?:%|percent)\s+(?:of|off)\s+(-?\d+(?:\.\d+)?)\??$""", RegexOption.IGNORE_CASE)
    private val TIP = Regex("""^(?:what(?:'s| is)\s+(?:a\s+)?)?(\d+(?:\.\d+)?)\s*(?:%|percent)\s+tip\s+(?:on|for)\s+\$?(\d+(?:\.\d+)?)\??$""", RegexOption.IGNORE_CASE)
    private val EXPR_PREFIX = Regex("""^(?:what(?:'s| is)|calculate|calc|compute|=)\s*""", RegexOption.IGNORE_CASE)

    /** A sentence answering [input], or null when it isn't a calculation. */
    /** The two answer sentences, so the app can pass translated ones (format args as in strings.xml). */
    class Words(
        val percentOf: (p: String, n: String, result: String) -> String = { p, n, r -> "$p% of $n is $r." },
        val tip: (p: String, bill: String, tip: String, total: String) -> String = { p, b, t, total -> "A $p% tip on $b is $t (total $total)." },
    )

    fun answer(input: String, words: Words = Words()): String? {
        val t = input.trim().trimEnd('?', '.', '!').trim()
        PERCENT_OF.matchEntire(t)?.let { m ->
            val p = m.groupValues[1].toBigDecimal(); val n = m.groupValues[2].toBigDecimal()
            return words.percentOf(fmt(p), fmt(n), fmt(pct(p * n)))
        }
        TIP.matchEntire(t)?.let { m ->
            val p = m.groupValues[1].toBigDecimal(); val bill = m.groupValues[2].toBigDecimal()
            val tip = pct(p * bill).setScale(2, RoundingMode.HALF_UP)
            return words.tip(fmt(p), money(bill), money(tip), money(bill + tip))
        }
        val expr = EXPR_PREFIX.replace(t, "").trim()
        if (expr.none { it.isDigit() } || expr.none { it in "+-*/^x×÷" }) return null
        val v = eval(expr) ?: return null
        return "${expr.trim()} = ${fmt(v)}"
    }

    /** Evaluates an arithmetic expression; null if it isn't one. */
    fun eval(s: String): BigDecimal? = runCatching {
        val p = Parser(s.replace('×', '*').replace('÷', '/').replace(Regex("(?<=\\d)\\s*[xX]\\s*(?=[\\d(])"), "*"))
        val v = p.expr(); p.skipWs(); if (p.i != p.s.length) null else v
    }.getOrNull()

    private class Parser(val s: String) {
        var i = 0
        fun skipWs() { while (i < s.length && s[i] == ' ') i++ }
        fun expr(): BigDecimal { var v = term(); while (true) { skipWs(); v = when (s.getOrNull(i)) { '+' -> { i++; v + term() }; '-' -> { i++; v - term() }; else -> return v } } }
        fun term(): BigDecimal { var v = power(); while (true) { skipWs(); v = when (s.getOrNull(i)) { '*' -> { i++; v * power() }; '/' -> { i++; v.divide(power(), 12, RoundingMode.HALF_UP) }; else -> return v } } }
        fun power(): BigDecimal { val b = unary(); skipWs(); return if (s.getOrNull(i) == '^') { i++; b.pow(unary().intValueExact()) } else b }
        fun unary(): BigDecimal { skipWs(); return if (s.getOrNull(i) == '-') { i++; -unary() } else atom() }
        fun atom(): BigDecimal {
            skipWs()
            if (s.getOrNull(i) == '(') { i++; val v = expr(); skipWs(); require(s.getOrNull(i) == ')'); i++; return v }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
            require(i > start)
            val num = s.substring(start, i).replace(",", "").toBigDecimal()
            skipWs()
            if (s.getOrNull(i) == '%') { i++; return num.divide(BigDecimal(100)) }
            return num
        }
    }

    // Kotlin's `/` on BigDecimal keeps the left operand's scale (1152 / 100 = 12), so divide explicitly.
    private fun pct(v: BigDecimal) = v.divide(BigDecimal(100), 10, RoundingMode.HALF_UP)

    fun fmt(v: BigDecimal): String = v.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    private fun money(v: BigDecimal) = "$" + v.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
