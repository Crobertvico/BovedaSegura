package pe.bovedasegura.util

import java.util.Locale

object Domains {
    private val secondLevel = setOf("com", "gob", "edu", "org", "net", "co", "ac", "nom", "mil")

    /** Host sin "www." a partir de una URL o texto. */
    fun host(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var s = input.trim().lowercase(Locale.ROOT)
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        s = s.substringAfter('@')
        return s.removePrefix("www.")
    }

    /** Dominio registrable aproximado: "banco.com.pe", "google.com". */
    fun registrable(input: String?): String {
        val h = host(input)
        if (h.isEmpty()) return ""
        if (h.all { it.isDigit() || it == '.' }) return h
        val parts = h.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val tld = parts.last()
        val sld = parts[parts.size - 2]
        return if (tld.length == 2 && sld in secondLevel) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    fun matches(itemUrl: String?, webDomain: String?): Boolean {
        val a = registrable(itemUrl)
        val b = registrable(webDomain)
        return a.isNotEmpty() && a == b
    }
}
