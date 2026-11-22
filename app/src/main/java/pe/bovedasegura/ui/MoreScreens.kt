@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pe.bovedasegura.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.bovedasegura.AutoLock
import pe.bovedasegura.backup.BackupManager
import pe.bovedasegura.backup.BackupScheduler
import pe.bovedasegura.crypto.SecretKeyCodec
import pe.bovedasegura.data.Item
import pe.bovedasegura.data.ItemType
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.Vault
import pe.bovedasegura.data.VaultData
import pe.bovedasegura.util.Csv
import pe.bovedasegura.util.EmergencyKit
import pe.bovedasegura.util.Strength
import java.util.UUID

@Composable
fun MoreScreen(nav: NavHostController) {
    val decoy = Vault.isDecoy
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Herramientas") })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            MenuRow(Icons.Filled.AutoAwesome, "Generador de contraseñas", "Contraseñas y frases de paso") { nav.go("generator") }
            MenuRow(Icons.Filled.Backup, "Respaldos", "Bóveda ${ago(Prefs.lastVaultBackupOk)} · Multimedia ${if (Prefs.mediaBackupUri == null) "sin configurar" else ago(Prefs.lastMediaBackupOk)}") { nav.go("backups") }
            MenuRow(Icons.Filled.Keyboard, "Autocompletado", if (autofillEnabled(LocalContext.current)) "Activo" else "Desactivado · configurar") { nav.go("autofill") }
            if (!decoy) {
                MenuRow(Icons.Filled.FlightTakeoff, "Modo viaje", if (Vault.current().settings.travelActive) "Activo" else "Oculta cuentas al cruzar fronteras", tint = Violet) { nav.go("travel") }
                MenuRow(Icons.Filled.VisibilityOff, "Bóveda señuelo", if (Vault.current().settings.decoyConfigured) "Configurada" else "Contraseña de coacción", tint = Violet) { nav.go("decoy") }
            }
            MenuRow(Icons.Filled.FileUpload, "Importar y exportar", "Chrome, 1Password, RoboForm, Keeper, Bitwarden, CSV") { nav.go("importexport") }
            MenuRow(Icons.Filled.PictureAsPdf, "Kit de emergencia", "Clave secreta y recuperación") { nav.go("kit") }
            MenuRow(Icons.Filled.Security, "Seguridad", "Bloqueo, autodestrucción, biometría, portapapeles") { nav.go("security") }
            MenuRow(Icons.Filled.Key, "Cambiar contraseña maestra") { nav.go("password") }
            MenuRow(Icons.Filled.Delete, "Papelera", "Se vacía sola a los 30 días") { nav.go("trash") }
            MenuRow(Icons.Filled.Lock, "Bloquear ahora") { Vault.lock() }
            Spacer(Modifier.height(12.dp))
            Text("Bóveda Segura · sin permiso de Internet · AES-256-GCM · Argon2id", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ============================================================ RESPALDOS

@Composable
fun BackupsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var freq by remember { mutableStateOf(Prefs.mediaFrequency) }
    var wifi by remember { mutableStateOf(Prefs.mediaWifiOnly) }
    var charge by remember { mutableStateOf(Prefs.mediaChargingOnly) }
    val vaultTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        AutoLock.suspended = false
        if (uri != null) { persistTree(ctx, uri); Prefs.vaultBackupUri = uri.toString(); BackupScheduler.schedulePeriodic(ctx); tick++ }
    }
    val mediaTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        AutoLock.suspended = false
        if (uri != null) { persistTree(ctx, uri); Prefs.mediaBackupUri = uri.toString(); BackupScheduler.scheduleMedia(ctx); tick++ }
    }
    val mediaRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        AutoLock.suspended = false
        if (uri != null) {
            persistTree(ctx, uri)
            busy = "Restaurando imágenes…"
            scope.launch { val r = withContext(Dispatchers.IO) { BackupManager.restoreMedia(ctx, uri.toString()) }; busy = null; msg = r.message; tick++ }
        }
    }
    val data by Vault.data.collectAsState()
    val pendingMedia = data?.files?.count { it.group == pe.bovedasegura.data.BackupGroup.MEDIA && !pe.bovedasegura.data.FileVault.exists(it) } ?: 0
    val vaultDocs = data?.files?.filter { it.group == pe.bovedasegura.data.BackupGroup.VAULT && it.deletedAt == null }.orEmpty()
    val mediaFiles = data?.files?.filter { it.group == pe.bovedasegura.data.BackupGroup.MEDIA && it.deletedAt == null }.orEmpty()

    Column(Modifier.fillMaxSize()) {
        BackBar("Respaldos", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            key(tick) {
                if (Vault.isDecoy) Banner("Estás en una bóveda secundaria: los respaldos automáticos protegen ambas sin revelar cuál es cuál.", Icons.Filled.Info, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                InfoCard {
                    Text("Respaldo 1 · Bóveda (.vault)", style = MaterialTheme.typography.titleMedium)
                    Text("Cuentas, 2FA, identidades, notas, historial, configuración, ${vaultDocs.size} documento(s): PDF, Word, cámara segura y escaneos.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    StatusText("Último respaldo", if (Prefs.lastVaultBackupOk > 0) "${formatDate(Prefs.lastVaultBackupOk)} · ${formatBytes(Prefs.lastVaultBackupSize)}" else "Nunca")
                    StatusText("Estado", Prefs.lastVaultBackupMsg ?: "—")
                    StatusText("Frecuencia", "Diaria y tras 5 cambios")
                    StatusText("Versiones", "7 diarias + 4 semanales")
                    StatusText("Carpeta", if (Prefs.vaultBackupUri == null) "Sin elegir" else Uri.decode(Uri.parse(Prefs.vaultBackupUri).lastPathSegment ?: ""))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(enabled = busy == null && Prefs.vaultBackupUri != null, onClick = {
                            busy = "Creando y verificando respaldo…"
                            scope.launch { val r = withContext(Dispatchers.IO) { BackupManager.backupVault(ctx) }; busy = null; msg = r.message; tick++ }
                        }) { Text("Respaldar ahora") }
                        OutlinedButton(onClick = { AutoLock.suspended = true; vaultTree.launch(null) }) { Text("Carpeta") }
                    }
                    val versions = remember(tick) { runCatching { BackupManager.listVaultBackups(ctx).size }.getOrDefault(0) }
                    Text("$versions versión(es) guardadas", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                InfoCard {
                    Text("Respaldo 2 · Multimedia", style = MaterialTheme.typography.titleMedium)
                    Text("${mediaFiles.size} imagen(es) importadas. Incremental: solo copia lo nuevo. Lo borrado se conserva 30 días.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    StatusText("Último respaldo", if (Prefs.lastMediaBackupOk > 0) "${formatDate(Prefs.lastMediaBackupOk)} · ${formatBytes(Prefs.lastMediaBackupSize)}" else "Nunca")
                    StatusText("Estado", Prefs.lastMediaBackupMsg ?: "—")
                    StatusText("Carpeta", if (Prefs.mediaBackupUri == null) "Sin elegir" else Uri.decode(Uri.parse(Prefs.mediaBackupUri).lastPathSegment ?: ""))
                    Text("Frecuencia", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ON_CHANGE" to "Al agregar", "DAILY" to "Diaria", "WEEKLY" to "Semanal").forEach { (k, l) ->
                            Chip(l, freq == k) { freq = k; Prefs.mediaFrequency = k; BackupScheduler.scheduleMedia(ctx) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Solo con Wi-Fi", Modifier.weight(1f)); Switch(wifi, { wifi = it; Prefs.mediaWifiOnly = it; BackupScheduler.scheduleMedia(ctx) }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Solo mientras carga", Modifier.weight(1f)); Switch(charge, { charge = it; Prefs.mediaChargingOnly = it; BackupScheduler.scheduleMedia(ctx) }) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(enabled = busy == null && Prefs.mediaBackupUri != null, onClick = {
                            busy = "Respaldando imágenes…"
                            scope.launch { val r = withContext(Dispatchers.IO) { BackupManager.backupMedia(ctx) }; busy = null; msg = r.message; tick++ }
                        }) { Text("Respaldar ahora") }
                        OutlinedButton(onClick = { AutoLock.suspended = true; mediaTree.launch(null) }) { Text("Carpeta") }
                    }
                    if (pendingMedia > 0) OutlinedButton(onClick = { AutoLock.suspended = true; mediaRestore.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Restaurar $pendingMedia imagen(es) pendientes") }
                }
                busy?.let { Busy(it) }
                msg?.let { Banner(it, Icons.Filled.Info, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Los respaldos son copias de los archivos cifrados. Para abrirlos se necesitan tu contraseña maestra y la clave secreta del Kit de emergencia. Bóveda Segura no se conecta a Internet: si eliges una carpeta de Google Drive, es la app de Drive la que la sube.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusText(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, fontSize = 13.sp)
    }
}

// ============================================================ MODO VIAJE

@Composable
fun TravelScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val data by Vault.data.collectAsState()
    val d = data ?: return
    val active = d.settings.travelActive
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val safe = d.items.count { it.travelSafe && it.deletedAt == null }
    val hidden = d.items.count { !it.travelSafe && it.deletedAt == null } + d.files.count { !it.travelSafe }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Modo viaje", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Al activarlo, todo lo que no esté marcado como \"seguro para viaje\" se saca de la bóveda y se guarda en un contenedor cifrado aparte, con su propia clave de viaje. Si alguien te obliga a abrir la app, solo verá lo esencial y nada indica que el modo esté activo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!active) {
                InfoCard { Text("Seguirán visibles: $safe cuenta(s)"); Text("Se ocultarán: $hidden elemento(s)") }
                Text("Marca cuentas como seguras desde Editar › \"Segura para viaje\". Los archivos se ocultan todos.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PasswordInput(pw, { pw = it; msg = null }, "Clave de viaje (distinta a la maestra)")
                PasswordInput(pw2, { pw2 = it; msg = null }, "Repite la clave de viaje")
                Banner("Sin esta clave no podrás recuperar lo ocultado. La biometría se desactiva durante el viaje.", Icons.Filled.Warning, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                Button(enabled = !busy, onClick = {
                    when {
                        pw.length < 8 -> msg = "Usa al menos 8 caracteres"
                        pw != pw2 -> msg = "Las claves no coinciden"
                        else -> { busy = true; scope.launch { val ok = Vault.enableTravel(pw.toCharArray()); busy = false; msg = if (ok) "Modo viaje activado" else "No se pudo activar"; pw = ""; pw2 = "" } }
                    }
                }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Activar modo viaje") }
            } else {
                Banner("Modo viaje activo", Icons.Filled.FlightTakeoff, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                PasswordInput(pw, { pw = it; msg = null }, "Clave de viaje")
                Button(enabled = !busy && pw.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch { val ok = Vault.disableTravel(pw.toCharArray()); busy = false; msg = if (ok) "Todo de vuelta en tu bóveda" else "Clave de viaje incorrecta"; pw = "" }
                }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Desactivar y recuperar todo") }
            }
            if (busy) Busy("Procesando…")
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

// ============================================================ BÓVEDA SEÑUELO

@Composable
fun DecoyScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val data by Vault.data.collectAsState()
    val configured = data?.settings?.decoyConfigured == true
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Bóveda señuelo", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Una segunda contraseña abre una bóveda distinta, con datos inocentes. Si alguien te obliga a desbloquear la app, usa esa contraseña. Ambas bóvedas tienen el mismo aspecto y existen siempre en el disco, así que no se puede saber si hay una real detrás.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!configured) {
                PasswordInput(pw, { pw = it; msg = null }, "Contraseña de la bóveda señuelo")
                PasswordInput(pw2, { pw2 = it; msg = null }, "Repítela")
                Text("Se creará con algunas cuentas de ejemplo. Luego puedes entrar con esa contraseña y agregar más para que parezca real.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !busy, onClick = {
                    when {
                        pw.length < 8 -> msg = "Usa al menos 8 caracteres"
                        pw != pw2 -> msg = "No coinciden"
                        else -> {
                            busy = true
                            scope.launch {
                                val ok = Vault.configureDecoy(pw.toCharArray(), sampleDecoy())
                                busy = false
                                msg = if (ok) "Bóveda señuelo lista" else "Esa contraseña no se puede usar (¿es igual a la maestra?)"
                                pw = ""; pw2 = ""
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Crear bóveda señuelo") }
            } else {
                Banner("Bóveda señuelo configurada", Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) { Text("Eliminar bóveda señuelo") }
            }
            if (busy) Busy("Creando…")
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (confirmRemove) ConfirmDialog("Eliminar bóveda señuelo", "Su contenido se borrará y la contraseña de coacción dejará de funcionar.", "Eliminar", true, { confirmRemove = false }) {
        Vault.removeDecoy(); confirmRemove = false
    }
}

private fun sampleDecoy(): VaultData {
    val now = System.currentTimeMillis()
    fun login(t: String, u: String, url: String, cat: String) = Item(UUID.randomUUID().toString(), ItemType.LOGIN, t, mapOf("username" to u, "password" to pe.bovedasegura.util.PasswordGen.generate(pe.bovedasegura.util.GenOptions(length = 14)), "url" to url), category = cat, created = now - 200L * 86400000, updated = now - 30L * 86400000, pwChanged = now - 90L * 86400000)
    return VaultData(items = listOf(
        login("Netflix", "usuario.correo@gmail.com", "netflix.com", "Personal"),
        login("Spotify", "usuario.correo@gmail.com", "spotify.com", "Personal"),
        login("Facebook", "usuario.correo@gmail.com", "facebook.com", "Redes"),
        Item(UUID.randomUUID().toString(), ItemType.WIFI, "Wi-Fi casa", mapOf("ssid" to "MiCasa_5G", "password" to "casa2024wifi"), category = "Hogar", created = now, updated = now, pwChanged = now),
    ))
}

// ============================================================ AUTOCOMPLETADO

@Composable
fun AutofillSetupScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val enabled = remember(tick) { autofillEnabled(ctx) }
    Column(Modifier.fillMaxSize()) {
        BackBar("Autocompletado", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banner(if (enabled) "Bóveda Segura es tu servicio de autocompletado" else "El autocompletado está desactivado", if (enabled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer, if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                if (enabled) "Revisar" else "Activar") { openAutofillSettings(ctx); tick++ }
            OutlinedButton(onClick = { tick++ }, modifier = Modifier.fillMaxWidth()) { Text("Volver a comprobar") }
            SectionTitle("Paso 1 · Android")
            Text("Ajustes › Sistema › Idiomas y entrada › Servicio de autocompletado › Bóveda Segura. El botón de arriba te lleva directo.")
            SectionTitle("Paso 2 · Google Chrome")
            Text("Chrome usa su propio gestor salvo que se lo indiques:\n1. Abre Chrome › menú ⋮ › Configuración.\n2. Entra a \"Autocompletar con otro servicio\" (o \"Servicios de Autocompletar\").\n3. Elige \"Autocompletar con otro servicio\" y reinicia Chrome si lo pide.")
            SectionTitle("Si una app no muestra sugerencias")
            Text("• Toca \"Buscar en Bóveda Segura…\" en la sugerencia: al elegir la cuenta, la app queda vinculada y la próxima vez aparece sola.\n• En la cuenta, puedes escribir el paquete de la app en \"Apps vinculadas\".\n• Usa el acceso rápido: desliza la barra de notificaciones, edita los mosaicos y agrega \"Bóveda\" para copiar usuario, contraseña o código 2FA en dos toques.")
            SectionTitle("Cuentas con 2FA")
            Text("Al autocompletar una cuenta que tiene código 2FA, Bóveda Segura copia el código al portapapeles para que solo tengas que pegarlo.")
            HorizontalDivider()
            Text("Si Android reinicia el autocompletado tras una actualización, verás un aviso en Inicio con el botón Reparar.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================ IMPORTAR / EXPORTAR

@Composable
fun ImportExportScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reauth by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        AutoLock.suspended = false
        if (uri != null) {
            busy = true
            scope.launch {
                msg = try {
                    withContext(Dispatchers.IO) { if (Prefs.vaultBackupUri != null) BackupManager.backupVault(ctx) }
                    val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) } }
                    val r = withContext(Dispatchers.Default) { Csv.importText(text) }
                    "Importadas ${r.added} cuenta(s). ${r.skippedDuplicates} duplicada(s) omitida(s).\nBorra el archivo CSV original: contiene tus contraseñas sin cifrar."
                } catch (e: Exception) { "No se pudo importar: ${e.message}" }
                busy = false
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        AutoLock.suspended = false
        if (uri != null) scope.launch {
            msg = try {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "w")!!.use { it.write(Csv.export(Vault.current().items).toByteArray()) } }
                "CSV exportado. Recuerda borrarlo cuando termines."
            } catch (e: Exception) { "Error: ${e.message}" }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("Importar y exportar", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Importar contraseñas")
            Text("Exporta un CSV desde tu gestor actual y elígelo aquí. Se detectan automáticamente los formatos de Chrome / Google, 1Password, RoboForm, Keeper y Bitwarden. Los duplicados se omiten y antes de importar se hace un respaldo.")
            Button(enabled = !busy, onClick = { AutoLock.suspended = true; importer.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Elegir archivo CSV") }
            SectionTitle("Exportar")
            Text("Para respaldar usa Más › Respaldos (cifrado). El CSV sirve para migrar a otra app y queda SIN cifrar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !busy, onClick = { warn = true }, modifier = Modifier.fillMaxWidth()) { Text("Exportar CSV sin cifrar") }
            if (busy) Busy("Importando…")
            msg?.let { Banner(it, Icons.Filled.Info, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (warn) ConfirmDialog("Exportar sin cifrar", "Cualquiera que tenga el archivo podrá leer todas tus contraseñas. ¿Continuar?", "Continuar", true, { warn = false }) { warn = false; reauth = true }
    if (reauth) ReauthDialog("Confirma para exportar todas tus contraseñas en texto plano.", { reauth = false }) { reauth = false; AutoLock.suspended = true; exporter.launch("BovedaSegura_export.csv") }
}

// ============================================================ KIT DE EMERGENCIA

@Composable
fun KitScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reveal by remember { mutableStateOf(false) }
    var reauth by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var rotate by remember { mutableStateOf(false) }
    var master by remember { mutableStateOf("") }
    var decoyPw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val decoyConfigured = Vault.current().settings.decoyConfigured
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        AutoLock.suspended = false
        if (uri != null) scope.launch {
            msg = try { withContext(Dispatchers.IO) { EmergencyKit.write(ctx, uri, SecretKeyCodec.format(Vault.secretKey())) }; Prefs.kitSaved = true; "Kit guardado" } catch (e: Exception) { "Error: ${e.message}" }
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Kit de emergencia", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tu clave secreta y tu contraseña maestra abren tus respaldos en cualquier teléfono. La clave no se guarda en los respaldos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoCard(container = MaterialTheme.colorScheme.primaryContainer) {
                Text(if (reveal) SecretKeyCodec.format(Vault.secretKey()) else "BSP-•••••-•••••-•••••-•••••-••••••", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (reveal) reveal = false else reauth = "reveal" }) { Text(if (reveal) "Ocultar" else "Mostrar") }
                Button(onClick = { reauth = "save" }) { Text("Descargar PDF") }
            }
            if (!Vault.isDecoy) {
                SectionTitle("Generar una clave secreta nueva")
                Text("Úsalo si perdiste el kit o crees que alguien lo vio. Los respaldos anteriores seguirán abriéndose solo con la clave antigua; los nuevos usarán la nueva.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!rotate) OutlinedButton(onClick = { rotate = true }, enabled = !Vault.current().settings.travelActive) { Text("Generar clave nueva") }
                else {
                    PasswordInput(master, { master = it }, "Contraseña maestra")
                    if (decoyConfigured) PasswordInput(decoyPw, { decoyPw = it }, "Contraseña de la bóveda señuelo")
                    Button(enabled = !busy && master.isNotEmpty(), onClick = {
                        busy = true
                        scope.launch {
                            val r = Vault.rotateSecretKey(master.toCharArray(), if (decoyConfigured) decoyPw.toCharArray() else null)
                            busy = false
                            if (r != null) {
                                master = ""; decoyPw = ""; rotate = false; reveal = true
                                msg = "Clave nueva generada. Descarga el kit nuevo ahora y crea un respaldo."
                                withContext(Dispatchers.IO) { BackupManager.backupVault(ctx) }
                            } else msg = "Contraseña incorrecta"
                        }
                    }) { Text("Confirmar") }
                }
                if (busy) Busy("Recifrando…")
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
    reauth?.let { what ->
        ReauthDialog("Confirma para ver o guardar tu clave secreta.", { reauth = null }) {
            reauth = null
            if (what == "reveal") reveal = true else { AutoLock.suspended = true; saver.launch("BovedaSegura_Kit_de_emergencia.pdf") }
        }
    }
}

// ============================================================ SEGURIDAD

@Composable
fun SecuritySettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var autoLock by remember { mutableIntStateOf(Prefs.autoLockSeconds) }
    var destruct by remember { mutableIntStateOf(Prefs.selfDestructAfter) }
    var bio by remember { mutableStateOf(Prefs.biometricEnabled) }
    var clip by remember { mutableIntStateOf(Vault.current().settings.clipboardSeconds) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmDestruct by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackBar("Seguridad", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Bloqueo automático")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Al salir", 30 to "30 s", 60 to "1 min", 180 to "3 min", 300 to "5 min").forEach { (s, l) -> Chip(l, autoLock == s) { autoLock = s; Prefs.autoLockSeconds = s } }
            }
            SectionTitle("Desbloqueo biométrico")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Huella o rostro")
                    Text(if (Biometrics.available(ctx)) "La llave se guarda en el chip seguro y se invalida si registras una huella nueva" else "Este teléfono no tiene biometría fuerte configurada", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(bio, { on ->
                    if (on) {
                        val act = ctx.findActivity() as? FragmentActivity
                        when {
                            Vault.isDecoy || Vault.current().settings.travelActive -> msg = "No disponible en este modo"
                            act != null -> Biometrics.enroll(act) { ok, m -> bio = ok; if (!ok) msg = m }
                        }
                    } else { Biometrics.disable(); bio = false }
                }, enabled = Biometrics.available(ctx))
            }
            SectionTitle("Autodestrucción")
            Text("Borra la bóveda de este teléfono tras varios intentos fallidos. Entre intentos hay esperas crecientes desde el tercer fallo.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Desactivada", 5 to "5 intentos", 10 to "10 intentos").forEach { (n, l) -> Chip(l, destruct == n) { if (n == 0) { destruct = 0; Prefs.selfDestructAfter = 0 } else confirmDestruct = n } }
            }
            SectionTitle("Portapapeles")
            Text("Lo que copies se borra automáticamente y se marca como sensible.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60, 90).forEach { s -> Chip("$s s", clip == s) { clip = s; Vault.updateSettings { it.copy(clipboardSeconds = s) } } }
            }
            SectionTitle("Protecciones siempre activas")
            listOf(
                "Sin permiso de Internet",
                "Capturas y grabación de pantalla bloqueadas",
                "Vista en blanco en apps recientes",
                "Toques bloqueados bajo ventanas superpuestas",
                "Respaldo automático de Android desactivado",
                "Cifrado AES-256-GCM por registro y por archivo",
                "Argon2id + clave secreta de 128 bits",
            ).forEach { Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(it, fontSize = 14.sp) } }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    confirmDestruct?.let { n ->
        ConfirmDialog("Activar autodestrucción", "Tras $n intentos fallidos se borrará TODO lo de este teléfono. Solo podrás recuperarlo desde tus respaldos con el Kit de emergencia. Asegúrate de tener un respaldo reciente.", "Activar", true, { confirmDestruct = null }) {
            destruct = n; Prefs.selfDestructAfter = n; confirmDestruct = null
        }
    }
}

// ============================================================ CAMBIAR CONTRASEÑA

@Composable
fun ChangePasswordScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf("") }
    var n1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Cambiar contraseña maestra", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PasswordInput(cur, { cur = it; msg = null }, "Contraseña actual")
            PasswordInput(n1, { n1 = it; msg = null }, "Nueva contraseña")
            StrengthBar(n1)
            PasswordInput(n2, { n2 = it; msg = null }, "Repite la nueva", isError = n2.isNotEmpty() && n1 != n2)
            Text("Los respaldos anteriores seguirán abriéndose con la contraseña antigua. Se creará un respaldo nuevo al terminar.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled = !busy, onClick = {
                when {
                    n1.length < 10 || Strength.score(n1) < 2 -> msg = "La nueva contraseña es demasiado débil"
                    n1 != n2 -> msg = "No coinciden"
                    else -> {
                        busy = true
                        scope.launch {
                            val ok = Vault.changePassword(cur.toCharArray(), n1.toCharArray())
                            if (ok) withContext(Dispatchers.IO) { BackupManager.backupVault(ctx) }
                            busy = false
                            msg = if (ok) "Contraseña cambiada" else "La contraseña actual es incorrecta"
                            if (ok) { cur = ""; n1 = ""; n2 = "" }
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Cambiar") }
            if (busy) Busy("Recifrando…")
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
