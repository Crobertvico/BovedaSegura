package pe.bovedasegura.util

import pe.bovedasegura.data.Item
import pe.bovedasegura.data.ItemType
import pe.bovedasegura.data.Vault
import java.util.UUID

data class ImportSummary(val added: Int, val skippedDuplicates: Int, val total: Int)

/** Importación desde Chrome/Google, 1Password, RoboForm, Keeper, Bitwarden y CSV genérico. */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val ch = s[i]
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { sb.append('"'); i++ } else inQ = false
                } else sb.append(ch)
            } else when (ch) {
                '"' -> inQ = true
                ',' -> { row.add(sb.toString()); sb.clear() }
                '\r' -> {}
                '\n' -> { row.add(sb.toString()); sb.clear(); rows.add(row); row = ArrayList() }
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun idx(header: List<String>, vararg names: String): Int {
        val h = header.map { it.trim().lowercase() }
        for (n in names) { val i = h.indexOf(n); if (i >= 0) return i }
        return -1
    }

    fun toItems(text: String): List<Item> {
        val rows = parse(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        val hl = header.map { it.trim().lowercase() }
        val known = setOf("name", "title", "url", "username", "password", "login_username", "login_password", "pwd", "login", "note", "notes")
        val hasHeader = hl.count { it in known } >= 2
        val now = System.currentTimeMillis()
        if (!hasHeader) {
            // Keeper exporta sin encabezado: Carpeta, Título, Usuario, Contraseña, Web, Notas
            return rows.filter { it.size >= 4 }.map { r ->
                item(r.getOrElse(1) { "" }, r.getOrElse(4) { "" }, r.getOrElse(2) { "" }, r.getOrElse(3) { "" }, r.getOrElse(5) { "" }, "", r.getOrElse(0) { "" }, now)
            }
        }
        val iTitle = idx(header, "name", "title")
        val iUrl = idx(header, "url", "login_uri", "website address", "website", "matchurl")
        val iUser = idx(header, "username", "login_username", "login", "user name", "email")
        val iPass = idx(header, "password", "login_password", "pwd")
        val iNotes = idx(header, "note", "notes", "extra")
        val iTotp = idx(header, "otpauth", "login_totp", "totp", "one-time password")
        val iFolder = idx(header, "folder", "grouping", "category")
        return rows.drop(1).mapNotNull { r ->
            fun g(i: Int) = if (i >= 0) r.getOrElse(i) { "" } else ""
            if (g(iPass).isEmpty() && g(iUser).isEmpty()) null
            else item(g(iTitle), g(iUrl), g(iUser), g(iPass), g(iNotes), g(iTotp), g(iFolder), now)
        }
    }

    private fun item(title: String, url: String, user: String, pass: String, notes: String, totp: String, folder: String, now: Long): Item {
        val t = title.ifBlank { Domains.registrable(url).ifBlank { "Sin nombre" } }
        val cat = when {
            folder.contains("banc", true) || folder.contains("bank", true) -> "Bancos"
            folder.contains("trab", true) || folder.contains("work", true) -> "Trabajo"
            folder.contains("social", true) || folder.contains("red", true) -> "Redes"
            folder.contains("mail", true) || folder.contains("correo", true) -> "Correo"
            else -> "Otros"
        }
        return Item(
            id = UUID.randomUUID().toString(), type = ItemType.LOGIN, title = t,
            fields = mapOf("username" to user, "password" to pass, "url" to url),
            notes = notes, totp = totp, category = cat, created = now, updated = now, pwChanged = now
        )
    }

    fun importText(text: String): ImportSummary {
        val incoming = toItems(text)
        val existing = Vault.current().items
        val toAdd = ArrayList<Item>()
        var dup = 0
        for (inc in incoming) {
            val isDup = (existing + toAdd).any { e ->
                e.type == ItemType.LOGIN && e.username == inc.username && e.password == inc.password &&
                    Domains.registrable(e.url) == Domains.registrable(inc.url)
            }
            if (isDup) dup++ else toAdd += inc
        }
        if (toAdd.isNotEmpty()) Vault.update { d -> d.copy(items = (d.items + toAdd).sortedBy { it.title.lowercase() }) }
        return ImportSummary(toAdd.size, dup, incoming.size)
    }

    private fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun export(items: List<Item>): String {
        val sb = StringBuilder("name,url,username,password,note,totp,category,type\n")
        items.filter { it.deletedAt == null }.forEach { i ->
            sb.append(listOf(i.title, i.url, i.username, i.password, i.notes, i.totp, i.category, i.type.name).joinToString(",") { esc(it) }).append('\n')
        }
        return sb.toString()
    }
}
