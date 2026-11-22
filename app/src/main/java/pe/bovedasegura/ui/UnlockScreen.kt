package pe.bovedasegura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.UnlockResult
import pe.bovedasegura.data.Vault

@Composable
fun UnlockScreen(subtitle: String = "Ingresa tu contraseña maestra", autoBiometric: Boolean = true, onUnlocked: () -> Unit, onWiped: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lockMs by remember { mutableLongStateOf(Vault.lockoutRemaining()) }

    LaunchedEffect(lockMs) {
        if (lockMs > 0) { delay(1000); lockMs = Vault.lockoutRemaining() }
    }

    fun tryBio() {
        val act = ctx.findActivity() as? FragmentActivity ?: return
        Biometrics.unlock(act) { key, msg ->
            if (key != null) scope.launch {
                busy = true
                if (Vault.unlockWithKey(key)) onUnlocked() else error = "No se pudo abrir con biometría"
                busy = false
            } else if (msg != null && !msg.contains("contraseña", true) && !msg.contains("cancel", true)) error = msg
        }
    }

    LaunchedEffect(Unit) {
        if (autoBiometric && Prefs.biometricEnabled && Biometrics.available(ctx) && lockMs == 0L) tryBio()
    }

    fun submit() {
        if (pw.isEmpty() || busy) return
        busy = true; error = null
        scope.launch {
            when (val r = Vault.unlock(pw.toCharArray())) {
                is UnlockResult.Ok -> { pw = ""; onUnlocked() }
                is UnlockResult.Wrong -> {
                    pw = ""
                    error = buildString {
                        append("Contraseña incorrecta")
                        if (r.lockedForMs > 0) append(". Espera ${r.lockedForMs / 1000} s")
                        if (r.remainingBeforeWipe != null) append(". Quedan ${r.remainingBeforeWipe} intentos antes del borrado")
                    }
                    lockMs = Vault.lockoutRemaining()
                }
                is UnlockResult.LockedOut -> { lockMs = r.ms }
                is UnlockResult.Wiped -> onWiped()
            }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
            }
            Text("Bóveda Segura", style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            PasswordInput(pw, { pw = it; error = null }, "Contraseña maestra", isError = error != null)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center)
            if (lockMs > 0) Text("Demasiados intentos. Espera ${lockMs / 1000} s", color = MaterialTheme.colorScheme.tertiary, fontSize = 13.sp)
            Button(onClick = { submit() }, enabled = !busy && lockMs == 0L && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (busy) "Abriendo…" else "Desbloquear")
            }
            if (Prefs.biometricEnabled && Biometrics.available(ctx)) {
                TextButton(onClick = { tryBio() }, enabled = !busy && lockMs == 0L) {
                    Icon(Icons.Filled.Fingerprint, null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Usar huella o rostro")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Sin conexión a Internet · Cifrado AES-256", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Filled.WifiOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
