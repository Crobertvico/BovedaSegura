package pe.bovedasegura.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.bovedasegura.AutoLock
import pe.bovedasegura.backup.BackupManager
import pe.bovedasegura.backup.BackupScheduler
import pe.bovedasegura.crypto.SecretKeyCodec
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.UnlockResult
import pe.bovedasegura.data.Vault
import pe.bovedasegura.util.EmergencyKit
import pe.bovedasegura.util.Strength

fun persistTree(ctx: Context, uri: Uri) {
    try {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    } catch (_: Exception) {}
}

fun qrOptions(prompt: String): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt(prompt)
    .setBeepEnabled(false)
    .setOrientationLocked(false)

private enum class Step { WELCOME, PASSWORD, CREATING, KIT, BACKUP, BIOMETRIC, R_FILE, R_SECRET, R_PASSWORD, R_MEDIA }

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (Vault.isSetUp && Vault.isUnlocked) Step.KIT else Step.WELCOME) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var secretText by remember { mutableStateOf("") }
    var kitSaved by remember { mutableStateOf(Prefs.kitSaved) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    var backupOk by remember { mutableStateOf(Prefs.lastVaultBackupOk > 0) }
    var mediaChosen by remember { mutableStateOf(Prefs.mediaBackupUri != null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restored by remember { mutableStateOf(false) }

    val kitLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        AutoLock.suspended = false
        if (uri != null) scope.launch {
            try {
                val s = SecretKeyCodec.format(Vault.secretKey())
                withContext(Dispatchers.IO) { EmergencyKit.write(ctx, uri, s) }
                kitSaved = true; Prefs.kitSaved = true
            } catch (e: Exception) { error = "No se pudo guardar: ${e.message}" }
        }
    }
    val vaultTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        AutoLock.suspended = false
        if (uri != null) {
            persistTree(ctx, uri)
            Prefs.vaultBackupUri = uri.toString()
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { BackupManager.backupVault(ctx) }
                busy = false
                backupOk = r.ok
                backupMsg = if (r.ok) "Primer respaldo creado y verificado (${formatBytes(r.bytes)})" else r.message
            }
        }
    }
    val mediaTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        AutoLock.suspended = false
        if (uri != null) {
            persistTree(ctx, uri)
            Prefs.mediaBackupUri = uri.toString()
            mediaChosen = true
            if (restored) {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { BackupManager.restoreMedia(ctx, uri.toString()) }
                    busy = false
                    backupMsg = r.message
                }
            }
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        AutoLock.suspended = false
        if (uri != null) { restoreUri = uri; step = Step.R_SECRET }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        AutoLock.suspended = false
        r.contents?.let { secretText = it }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                Step.WELCOME -> {
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    }
                    Text("Bóveda Segura", style = MaterialTheme.typography.headlineMedium)
                    Text("Tus contraseñas, códigos 2FA y documentos, cifrados y solo en tu teléfono. Sin servidores, sin Internet, sin suscripciones.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { step = Step.PASSWORD }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Crear bóveda nueva") }
                    OutlinedButton(onClick = { step = Step.R_FILE }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Filled.Restore, null); Spacer(Modifier.size(8.dp)); Text("Restaurar desde respaldo")
                    }
                }

                Step.PASSWORD -> {
                    Text("Crea tu contraseña maestra", style = MaterialTheme.typography.headlineSmall)
                    Text("Es la única que tendrás que recordar. Usa una frase larga, por ejemplo cuatro palabras y un número.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordInput(pw, { pw = it; error = null }, "Contraseña maestra")
                    StrengthBar(pw)
                    PasswordInput(pw2, { pw2 = it; error = null }, "Repite la contraseña", isError = pw2.isNotEmpty() && pw2 != pw)
                    Banner("Si la olvidas, nadie podrá recuperar tu bóveda. Ni siquiera con el Kit de emergencia.", Icons.Filled.Warning, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = {
                            when {
                                pw.length < 10 -> error = "Usa al menos 10 caracteres"
                                Strength.score(pw) < 2 -> error = "La contraseña es demasiado débil"
                                pw != pw2 -> error = "Las contraseñas no coinciden"
                                else -> {
                                    step = Step.CREATING
                                    scope.launch {
                                        Vault.create(pw.toCharArray())
                                        pw = ""; pw2 = ""
                                        step = Step.KIT
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("Continuar") }
                    TextButton(onClick = { step = Step.WELCOME }) { Text("Atrás") }
                }

                Step.CREATING -> Busy("Creando tu bóveda cifrada…")

                Step.KIT -> {
                    Text("Tu Kit de emergencia", style = MaterialTheme.typography.headlineSmall)
                    Text("Esta es tu clave secreta. Junto con tu contraseña maestra, es lo único que abre tus respaldos. Guarda el PDF e imprímelo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfoCard(container = MaterialTheme.colorScheme.primaryContainer) {
                        Text(runCatching { SecretKeyCodec.format(Vault.secretKey()) }.getOrDefault("—"), fontFamily = FontFamily.Monospace, fontSize = 17.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
                    }
                    Button(onClick = { AutoLock.suspended = true; kitLauncher.launch("BovedaSegura_Kit_de_emergencia.pdf") }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Filled.PictureAsPdf, null); Spacer(Modifier.size(8.dp)); Text(if (kitSaved) "Guardar otra copia del PDF" else "Guardar Kit de emergencia (PDF)")
                    }
                    if (kitSaved) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(6.dp)); Text("Kit guardado. Imprímelo y guárdalo lejos del teléfono.")
                    }
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { error = null; step = Step.BACKUP }, enabled = kitSaved, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Continuar") }
                }

                Step.BACKUP -> {
                    Text("Respaldos", style = MaterialTheme.typography.headlineSmall)
                    Text("Tus datos solo existen en este teléfono y en tus respaldos. Elige una carpeta (por ejemplo, en Google Drive) para el respaldo de la bóveda: cuentas, 2FA, PDF, Word y fotos de la cámara segura.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { AutoLock.suspended = true; vaultTree.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Filled.Backup, null); Spacer(Modifier.size(8.dp)); Text(if (backupOk) "Cambiar carpeta de la bóveda" else "Elegir carpeta de la bóveda")
                    }
                    if (busy) Busy("Creando y verificando el primer respaldo…")
                    backupMsg?.let { Banner(it, if (backupOk) Icons.Filled.CheckCircle else Icons.Filled.Warning, if (backupOk) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer, if (backupOk) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer) }
                    SectionTitle("Imágenes (opcional)")
                    Text("Las imágenes que importes al cofre van en un respaldo aparte, incremental. Puedes elegir otra carpeta o configurarlo después.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    OutlinedButton(onClick = { AutoLock.suspended = true; mediaTree.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.size(8.dp)); Text(if (mediaChosen) "Carpeta multimedia elegida ✓" else if (restored) "Restaurar imágenes desde carpeta" else "Elegir carpeta multimedia")
                    }
                    Text("Consejo: las carpetas de Google Drive se suben solas a la nube a través de la app de Drive. Bóveda Segura nunca se conecta a Internet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { step = if (Biometrics.available(ctx)) Step.BIOMETRIC else { finish(ctx, onDone); step } }, enabled = backupOk && !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Continuar") }
                }

                Step.BIOMETRIC -> {
                    Icon(Icons.Filled.Fingerprint, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    Text("¿Desbloquear con huella o rostro?", style = MaterialTheme.typography.headlineSmall)
                    Text("Tu contraseña maestra seguirá siendo necesaria cada cierto tiempo y tras reiniciar. La llave queda guardada en el chip seguro del teléfono.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        val act = ctx.findActivity() as? FragmentActivity
                        if (act != null) Biometrics.enroll(act) { ok, msg -> if (ok) finish(ctx, onDone) else error = msg }
                    }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Activar") }
                    TextButton(onClick = { finish(ctx, onDone) }, modifier = Modifier.fillMaxWidth()) { Text("Ahora no") }
                }

                Step.R_FILE -> {
                    Text("Restaurar desde respaldo", style = MaterialTheme.typography.headlineSmall)
                    Text("Necesitas tres cosas: tu archivo .vault, tu Kit de emergencia (clave secreta) y tu contraseña maestra.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { AutoLock.suspended = true; restorePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Elegir archivo .vault") }
                    TextButton(onClick = { step = Step.WELCOME }) { Text("Atrás") }
                }

                Step.R_SECRET -> {
                    Text("Clave secreta", style = MaterialTheme.typography.headlineSmall)
                    Text("Escanea el QR de tu Kit de emergencia o escribe la clave.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { AutoLock.suspended = true; scanner.launch(qrOptions("Escanea el QR del Kit de emergencia")) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("Escanear QR")
                    }
                    OutlinedTextField(secretText, { secretText = it; error = null }, label = { Text("BSP-XXXXX-XXXXX-…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { if (SecretKeyCodec.parse(secretText) == null) error = "La clave no tiene el formato correcto" else step = Step.R_PASSWORD }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Continuar") }
                }

                Step.R_PASSWORD -> {
                    Text("Contraseña maestra", style = MaterialTheme.typography.headlineSmall)
                    PasswordInput(pw, { pw = it; error = null }, "Contraseña maestra")
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    if (busy) Busy("Verificando y restaurando…")
                    Button(enabled = !busy && pw.isNotEmpty(), onClick = {
                        val secret = SecretKeyCodec.parse(secretText) ?: return@Button
                        val uri = restoreUri ?: return@Button
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { BackupManager.restoreVault(ctx, uri, pw.toCharArray(), secret) }
                            if (!r.ok) { error = r.message; busy = false; return@launch }
                            val u = Vault.unlock(pw.toCharArray())
                            busy = false
                            if (u is UnlockResult.Ok) {
                                pw = ""; restored = true
                                Prefs.kitSaved = true; kitSaved = true
                                backupMsg = "Bóveda restaurada. Elige la carpeta de respaldos para seguir protegido."
                                step = Step.BACKUP
                            } else error = "No se pudo abrir la bóveda restaurada"
                        }
                    }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Restaurar") }
                }

                Step.R_MEDIA -> {}
            }
        }
    }
}

private fun finish(ctx: Context, onDone: () -> Unit) {
    Prefs.setupComplete = true
    BackupScheduler.schedulePeriodic(ctx)
    onDone()
}
