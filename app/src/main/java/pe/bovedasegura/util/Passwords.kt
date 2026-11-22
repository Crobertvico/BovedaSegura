package pe.bovedasegura.util

import pe.bovedasegura.crypto.Crypto
import pe.bovedasegura.data.Item
import pe.bovedasegura.data.ItemType
import kotlin.math.ln

data class GenOptions(
    val length: Int = 20,
    val upper: Boolean = true,
    val lower: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val noAmbiguous: Boolean = true,
    val passphrase: Boolean = false,
    val words: Int = 4,
    val separator: String = "-",
)

object PasswordGen {
    private const val UP = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LO = "abcdefghijklmnopqrstuvwxyz"
    private const val DI = "0123456789"
    private const val SY = "!@#$%&*?-_=+.:;"
    private const val AMBIG = "0O1lI|"

    fun generate(o: GenOptions): String = if (o.passphrase) passphrase(o) else chars(o)

    private fun pick(s: String) = s[Crypto.rng.nextInt(s.length)]

    private fun chars(o: GenOptions): String {
        fun clean(s: String) = if (o.noAmbiguous) s.filter { it !in AMBIG } else s
        val sets = buildList {
            if (o.upper) add(clean(UP)); if (o.lower) add(clean(LO))
            if (o.digits) add(clean(DI)); if (o.symbols) add(SY)
        }.ifEmpty { listOf(clean(LO)) }
        val all = sets.joinToString("")
        val out = CharArray(o.length.coerceAtLeast(sets.size)) { pick(all) }
        // Garantiza al menos un carácter de cada grupo elegido.
        val positions = out.indices.shuffled(Crypto.rng)
        sets.forEachIndexed { i, s -> out[positions[i]] = pick(s) }
        return String(out)
    }

    private fun passphrase(o: GenOptions): String {
        val w = (1..o.words).map { WordList.words[Crypto.rng.nextInt(WordList.words.size)].replaceFirstChar { c -> c.uppercase() } }
        return w.joinToString(o.separator) + o.separator + (10 + Crypto.rng.nextInt(90))
    }
}

/** Estimación de fortaleza 0..4 y bits aproximados. */
object Strength {
    private val common = setOf(
        "123456", "123456789", "12345678", "password", "qwerty", "123123", "111111", "abc123", "1234567", "contraseña",
        "iloveyou", "admin", "welcome", "000000", "654321", "1234", "12345", "password1", "qwerty123", "dragon",
        "monkey", "letmein", "football", "superman", "princesa", "teamo", "peru123", "123qwe", "sunshine", "master"
    )

    fun bits(pw: String): Double {
        if (pw.isEmpty()) return 0.0
        if (pw.lowercase() in common) return 5.0
        var pool = 0
        if (pw.any { it.isLowerCase() }) pool += 26
        if (pw.any { it.isUpperCase() }) pool += 26
        if (pw.any { it.isDigit() }) pool += 10
        if (pw.any { !it.isLetterOrDigit() }) pool += 20
        val unique = pw.toSet().size
        val effLen = minOf(pw.length.toDouble(), unique * 1.5)
        var b = effLen * ln(pool.coerceAtLeast(10).toDouble()) / ln(2.0)
        if (Regex("(.)\\1{2,}").containsMatchIn(pw)) b -= 10
        if (Regex("(0123|1234|2345|3456|4567|5678|6789|abcd|qwer|asdf)", RegexOption.IGNORE_CASE).containsMatchIn(pw)) b -= 12
        return b.coerceAtLeast(1.0)
    }

    fun score(pw: String): Int {
        val b = bits(pw)
        return when {
            b < 28 -> 0
            b < 40 -> 1
            b < 60 -> 2
            b < 80 -> 3
            else -> 4
        }
    }

    fun label(score: Int) = when (score) { 0 -> "Muy débil"; 1 -> "Débil"; 2 -> "Aceptable"; 3 -> "Fuerte"; else -> "Muy fuerte" }
}

data class HealthIssue(val item: Item, val kind: String, val detail: String)

data class HealthReport(val score: Int, val weak: List<Item>, val reused: List<Item>, val old: List<Item>, val no2fa: List<Item>, val total: Int)

object Health {
    fun analyze(items: List<Item>): HealthReport {
        val withPw = items.filter { it.deletedAt == null && it.password.isNotEmpty() }
        val weak = withPw.filter { Strength.score(it.password) <= 1 }
        val groups = withPw.groupBy { it.password }.filter { it.value.size > 1 }
        val reused = groups.values.flatten()
        val yearAgo = System.currentTimeMillis() - 365L * 24 * 3600 * 1000
        val old = withPw.filter { it.pwChanged < yearAgo }
        val no2fa = withPw.filter { it.type == ItemType.LOGIN && it.totp.isBlank() }
        val total = withPw.size
        val score = if (total == 0) 100 else {
            val penalty = weak.size * 3.0 + reused.size * 2.5 + old.size * 1.0 + no2fa.size * 0.5
            val max = total * 7.0
            (100 - penalty / max * 100).toInt().coerceIn(0, 100)
        }
        return HealthReport(score, weak, reused, old, no2fa, total)
    }
}
