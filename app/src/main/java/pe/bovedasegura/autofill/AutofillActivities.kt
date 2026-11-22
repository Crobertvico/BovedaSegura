@file:OptIn(ExperimentalMaterial3Api::class)

package pe.bovedasegura.autofill

import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import pe.bovedasegura.data.Item
import pe.bovedasegura.data.ItemType
import pe.bovedasegura.data.Vault
import pe.bovedasegura.ui.BovedaTheme
import pe.bovedasegura.ui.ItemRow
import pe.bovedasegura.ui.PasswordInput
import pe.bovedasegura.ui.UnlockScreen
import pe.bovedasegura.util.Domains
import pe.bovedasegura.util.ScreenGuard
import pe.bovedasegura.util.SecureClipboard
import pe.bovedasegura.util.Totp
import java.util.UUID

/** Lista de cuentas con búsqueda, reutilizada por el autocompletado y el acceso rápido. */
@Composable
fun PickList(title: String, initialQuery: String = "", onPick: (Item) -> Unit, onCancel: () -> Unit) {
    var q by remember { mutableStateOf(initialQuery) }
    val list = Vault.current().items.filter { it.deletedAt == null && (it.username.isNotEmpty() || it.password.isNotEmpty() || it.totp.isNotEmpty()) }
        .filter { q.isBlank() || it.title.contains(q, true) || it.username.contains(q, true) || it.url.contains(q, true) }
        .sortedByDescending { it.lastUsed }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(title = { Text(title) }, actions = { androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancelar") } })
            OutlinedTextField(q, { q = it }, placeholder = { Text("Buscar cuenta") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxSize()) { items(list, key = { it.id }) { item -> ItemRow(item) { onPick(item) } } }
        }
    }
}

class AutofillAuthActivity : FragmentActivity() {
    private var structure: AssistStructure? = null
    private var unlocked by mutableStateOf(false)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenGuard.protect(this)
        structure = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        else intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        if (structure == null) { setResult(Activity.RESULT_CANCELED); finish(); return }
        unlocked = Vault.isUnlocked
        val mode = intent.getStringExtra("mode") ?: "unlock"
        if (unlocked && mode != "pick") { complete(mode); return }
        setContent {
            BovedaTheme {
                if (!unlocked) UnlockScreen(subtitle = "Desbloquea para autocompletar", onUnlocked = {
                    unlocked = true
                    if (mode != "pick") complete(mode)
                })
                else {
                    val parsed = remember { Parser.parse(structure!!) }
                    PickList("Elegir cuenta", Domains.registrable(parsed.webDomain).substringBefore('.'), onPick = { item -> pick(parsed, item) }, onCancel = { setResult(Activity.RESULT_CANCELED); finish() })
                }
            }
        }
    }

    private fun complete(mode: String) {
        val parsed = Parser.parse(structure!!)
        val reply = Intent()
        when (mode) {
            "dataset" -> {
                val item = Vault.current().items.find { it.id == intent.getStringExtra("itemId") }
                if (item == null) { setResult(Activity.RESULT_CANCELED); finish(); return }
                copyTotp(item)
                Vault.markUsed(item.id)
                reply.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, BsAutofillService.dataset(this, parsed, item, withAuth = false))
            }
            else -> reply.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, BsAutofillService.buildResponse(this, parsed))
        }
        setResult(Activity.RESULT_OK, reply)
        finish()
    }

    private fun pick(parsed: ParsedFields, item: Item) {
        // Aprende: la próxima vez esta app o sitio sugerirá la cuenta directamente.
        val link = parsed.webDomain?.let { Domains.registrable(it) } ?: parsed.packageName
        if (link.isNotEmpty() && (parsed.webDomain == null || !Domains.matches(item.url, parsed.webDomain))) Vault.addPackageToItem(item.id, link)
        copyTotp(item)
        Vault.markUsed(item.id)
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, BsAutofillService.dataset(this, parsed, item, withAuth = false)))
        finish()
    }

    private fun copyTotp(item: Item) {
        if (item.totp.isBlank()) return
        Totp.codeFor(item.totp)?.let { SecureClipboard.copy(this, "Código 2FA", it, Vault.current().settings.clipboardSeconds) }
    }
}

class SaveActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenGuard.protect(this)
        val token = intent.getStringExtra("token")
        val save = token?.let { PendingSaves.map.remove(it) }
        if (save == null) { finish(); return }
        unlocked = Vault.isUnlocked
        setContent {
            BovedaTheme {
                if (!unlocked) UnlockScreen(subtitle = "Desbloquea para guardar la contraseña", onUnlocked = { unlocked = true })
                else SaveContent(save) { finish() }
            }
        }
    }

    @Composable
    private fun SaveContent(s: PendingSaves.Save, done: () -> Unit) {
        val existing = remember { Vault.findExisting(s.domain, s.pkg, s.username) }
        val defaultTitle = remember { s.domain?.let { Domains.registrable(it) } ?: runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.pkg, 0)).toString() }.getOrDefault(s.pkg) }
        var title by remember { mutableStateOf(existing?.title ?: defaultTitle) }
        var pw by remember { mutableStateOf(s.password) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (existing != null) "Actualizar contraseña" else "Guardar en Bóveda Segura", style = MaterialTheme.typography.headlineSmall)
                if (existing != null) Text("Ya tienes \"${existing.title}\" con el usuario ${existing.username}. Se actualizará la contraseña y la anterior quedará en el historial.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(title, { title = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Usuario: ${s.username.ifEmpty { "(sin usuario)" }}", fontSize = 14.sp)
                PasswordInput(pw, { pw = it }, "Contraseña")
                Button(onClick = {
                    if (existing != null) {
                        Vault.upsertItem(existing.copy(title = title, fields = existing.fields + ("password" to pw), packages = if (s.domain == null && s.pkg !in existing.packages) existing.packages + s.pkg else existing.packages))
                    } else {
                        val now = System.currentTimeMillis()
                        Vault.upsertItem(Item(
                            id = UUID.randomUUID().toString(), type = ItemType.LOGIN, title = title,
                            fields = mapOf("username" to s.username, "password" to pw, "url" to (s.domain ?: "")),
                            packages = if (s.domain == null) listOf(s.pkg) else emptyList(),
                            created = now, updated = now, pwChanged = now
                        ))
                    }
                    done()
                }, modifier = Modifier.fillMaxWidth()) { Text(if (existing != null) "Actualizar" else "Guardar") }
                OutlinedButton(onClick = done, modifier = Modifier.fillMaxWidth()) { Text("Ahora no") }
            }
        }
    }
}
