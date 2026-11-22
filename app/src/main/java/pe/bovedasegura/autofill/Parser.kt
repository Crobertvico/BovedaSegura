package pe.bovedasegura.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

data class ParsedFields(
    val packageName: String,
    val webDomain: String?,
    val usernameIds: List<AutofillId>,
    val passwordIds: List<AutofillId>,
    val usernameValue: String?,
    val passwordValue: String?,
) {
    val allIds: List<AutofillId> get() = usernameIds + passwordIds
    val isEmpty: Boolean get() = usernameIds.isEmpty() && passwordIds.isEmpty()
}

/**
 * Detecta campos de usuario y contraseña. Combina pistas de autocompletado, tipo de entrada,
 * atributos HTML y nombres/ids, en español e inglés. Si hay contraseña pero no usuario,
 * toma el campo de texto inmediatamente anterior.
 */
object Parser {
    private val userWords = listOf("user", "usuario", "login", "email", "correo", "mail", "username", "account", "cuenta", "dni", "documento", "phone", "telefono", "celular", "identifier")
    private val passWords = listOf("password", "contrase", "clave", "pass", "pwd", "pin", "secret")
    private val skipWords = listOf("search", "buscar", "captcha", "otp", "code", "codigo", "token", "2fa", "verification")

    private class Node(val id: AutofillId, val isPassword: Boolean, val isUser: Boolean, val isTextLike: Boolean, val value: String?)

    fun parse(structure: AssistStructure): ParsedFields {
        val nodes = ArrayList<Node>()
        var webDomain: String? = null
        for (i in 0 until structure.windowNodeCount) {
            visit(structure.getWindowNodeAt(i).rootViewNode, nodes) { d -> if (webDomain == null && !d.isNullOrBlank()) webDomain = d }
        }
        val pw = nodes.filter { it.isPassword }
        var users = nodes.filter { it.isUser && !it.isPassword }
        if (users.isEmpty() && pw.isNotEmpty()) {
            val firstPw = nodes.indexOf(pw.first())
            nodes.subList(0, firstPw).lastOrNull { it.isTextLike && !it.isPassword }?.let { users = listOf(it) }
        }
        return ParsedFields(
            packageName = structure.activityComponent?.packageName ?: "",
            webDomain = webDomain,
            usernameIds = users.map { it.id }.take(1),
            passwordIds = pw.map { it.id }.take(2),
            usernameValue = users.firstOrNull()?.value,
            passwordValue = pw.firstOrNull()?.value,
        )
    }

    private fun visit(n: AssistStructure.ViewNode, out: MutableList<Node>, onDomain: (String?) -> Unit) {
        onDomain(n.webDomain)
        val id = n.autofillId
        if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT && n.visibility == View.VISIBLE) {
            val hints = n.autofillHints?.map { it.lowercase() }.orEmpty()
            val html = n.htmlInfo
            val htmlType = html?.attributes?.firstOrNull { it.first == "type" }?.second?.lowercase()
            val htmlName = html?.attributes?.filter { it.first == "name" || it.first == "id" || it.first == "autocomplete" }?.joinToString(" ") { it.second ?: "" }?.lowercase().orEmpty()
            val text = listOfNotNull(n.idEntry, n.hint, htmlName, n.contentDescription?.toString()).joinToString(" ").lowercase()
            val inType = n.inputType
            val cls = inType and InputType.TYPE_MASK_CLASS
            val variation = inType and InputType.TYPE_MASK_VARIATION
            val isPwInput = (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val isEmailInput = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
            val skip = skipWords.any { w -> text.contains(w) } && !passWords.any { w -> text.contains(w) }
            val isPassword = !skip && (hints.any { h -> h.contains("password") } || htmlType == "password" || isPwInput || (passWords.any { w -> text.contains(w) } && cls == InputType.TYPE_CLASS_TEXT && hints.none { h -> h.contains("username") || h.contains("email") }))
            val isUser = !skip && !isPassword && (hints.any { h -> h.contains("username") || h.contains("email") || h.contains("phone") } || htmlType == "email" || isEmailInput || userWords.any { w -> text.contains(w) })
            val isTextLike = (cls == InputType.TYPE_CLASS_TEXT || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_NUMBER || htmlType == "text" || htmlType == "tel") && !skip
            out += Node(id, isPassword, isUser, isTextLike, n.autofillValue?.takeIf { v -> v.isText }?.textValue?.toString())
        }
        for (i in 0 until n.childCount) visit(n.getChildAt(i), out, onDomain)
    }
}
