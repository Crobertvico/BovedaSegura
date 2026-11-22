package pe.bovedasegura.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import pe.bovedasegura.R
import pe.bovedasegura.data.Item
import pe.bovedasegura.data.ItemType
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.Vault
import pe.bovedasegura.util.Domains
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Datos de guardado pendientes, solo en memoria (no viajan en el Intent). */
object PendingSaves {
    data class Save(val pkg: String, val domain: String?, val username: String, val password: String)
    val map = ConcurrentHashMap<String, Save>()
}

class BsAutofillService : AutofillService() {

    override fun onConnected() {
        super.onConnected()
        Prefs.init(applicationContext)
        Prefs.autofillWasEnabled = true
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
            val parsed = Parser.parse(structure)
            if (parsed.isEmpty || parsed.packageName == packageName) return callback.onSuccess(null)
            callback.onSuccess(buildResponse(this, parsed))
        } catch (e: Exception) {
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
            val p = Parser.parse(structure)
            val pw = p.passwordValue.orEmpty()
            if (pw.isEmpty()) return callback.onSuccess()
            val user = p.usernameValue.orEmpty()
            // Si ya existe exactamente igual, no molestamos.
            if (Vault.isUnlocked) {
                val ex = Vault.findExisting(p.webDomain, p.packageName, user)
                if (ex != null && ex.password == pw) return callback.onSuccess()
            }
            val token = UUID.randomUUID().toString()
            PendingSaves.map[token] = PendingSaves.Save(p.packageName, p.webDomain, user, pw)
            if (Build.VERSION.SDK_INT >= 28) {
                val intent = Intent(this, SaveActivity::class.java).putExtra("token", token)
                val pi = PendingIntent.getActivity(this, token.hashCode(), intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                callback.onSuccess(pi.intentSender)
            } else {
                startActivity(Intent(this, SaveActivity::class.java).putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                callback.onSuccess()
            }
        } catch (e: Exception) {
            callback.onSuccess()
        }
    }

    companion object {
        fun presentation(ctx: Context, title: String, subtitle: String?): RemoteViews {
            val rv = RemoteViews(ctx.packageName, android.R.layout.simple_list_item_2)
            rv.setTextViewText(android.R.id.text1, title)
            rv.setTextViewText(android.R.id.text2, subtitle ?: "")
            return rv
        }

        fun authIntent(ctx: Context, mode: String, itemId: String? = null): IntentSender {
            val i = Intent(ctx, AutofillAuthActivity::class.java).putExtra("mode", mode)
            if (itemId != null) i.putExtra("itemId", itemId)
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getActivity(ctx, (mode + itemId).hashCode(), i, flags).intentSender
        }

        fun matching(parsed: ParsedFields): List<Item> = Vault.current().items.filter { it.deletedAt == null && (it.username.isNotEmpty() || it.password.isNotEmpty()) }
            .filter { item ->
                (parsed.webDomain != null && (Domains.matches(item.url, parsed.webDomain) || item.packages.contains(parsed.webDomain))) ||
                    (parsed.webDomain == null && parsed.packageName in item.packages) ||
                    (parsed.webDomain == null && item.type == ItemType.LOGIN && Domains.registrable(item.url).let { r -> r.isNotEmpty() && parsed.packageName.contains(r.substringBefore('.')) && r.substringBefore('.').length >= 4 })
            }
            .sortedByDescending { it.lastUsed }

        @Suppress("DEPRECATION")
        fun dataset(ctx: Context, parsed: ParsedFields, item: Item, withAuth: Boolean): Dataset {
            val b = Dataset.Builder(presentation(ctx, item.title, item.username.ifEmpty { "••••••" }))
            parsed.usernameIds.forEach { b.setValue(it, AutofillValue.forText(item.username)) }
            parsed.passwordIds.forEach { b.setValue(it, AutofillValue.forText(item.password)) }
            if (withAuth) b.setAuthentication(authIntent(ctx, "dataset", item.id))
            return b.build()
        }

        fun saveInfo(parsed: ParsedFields): SaveInfo? {
            if (parsed.passwordIds.isEmpty()) return null
            val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (parsed.usernameIds.isNotEmpty()) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
            val req = parsed.passwordIds.toTypedArray()
            val b = SaveInfo.Builder(type, req)
            if (parsed.usernameIds.isNotEmpty()) b.setOptionalIds(parsed.usernameIds.toTypedArray())
            return b.build()
        }

        @Suppress("DEPRECATION")
        fun buildResponse(ctx: Context, parsed: ParsedFields): FillResponse {
            val rb = FillResponse.Builder()
            val ids: Array<AutofillId> = parsed.allIds.toTypedArray()
            if (!Vault.isUnlocked) {
                rb.setAuthentication(ids, authIntent(ctx, "unlock"), presentation(ctx, "Bóveda Segura", "Toca para desbloquear"))
            } else {
                matching(parsed).take(5).forEach { item ->
                    // Si la cuenta tiene 2FA, pasa por la actividad para copiar el código justo después.
                    rb.addDataset(dataset(ctx, parsed, item, withAuth = item.totp.isNotBlank()))
                }
                val search = Dataset.Builder(presentation(ctx, "Buscar en Bóveda Segura…", "Elegir otra cuenta"))
                ids.forEach { search.setValue(it, null) }
                search.setAuthentication(authIntent(ctx, "pick"))
                rb.addDataset(search.build())
            }
            saveInfo(parsed)?.let { rb.setSaveInfo(it) }
            return rb.build()
        }
    }
}
