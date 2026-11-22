@file:OptIn(ExperimentalMaterial3Api::class)

package pe.bovedasegura.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import pe.bovedasegura.backup.BackupScheduler
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.Vault
import pe.bovedasegura.util.Health
import pe.bovedasegura.util.RootCheck

fun autofillEnabled(ctx: android.content.Context): Boolean = try {
    ctx.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
} catch (e: Exception) { false }

fun openAutofillSettings(ctx: android.content.Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${ctx.packageName}")))
    } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
fun HomeScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val data by Vault.data.collectAsState()
    val d = data ?: return
    val report = remember(d.items) { Health.analyze(d.items) }
    val af = remember { autofillEnabled(ctx) }
    var rootDismissed by remember { mutableStateOf(Prefs.rootWarningDismissed) }
    val rooted = remember { RootCheck.isLikelyRooted() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Inicio") }, actions = { IconButton(onClick = { Vault.lock() }) { Icon(Icons.Filled.Lock, "Bloquear") } })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.settings.travelActive) Banner("Modo viaje activo: solo ves las cuentas marcadas como seguras para viajar.", Icons.Filled.FlightTakeoff, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, "Gestionar") { nav.go("travel") }
            if (!Vault.isDecoy && BackupScheduler.backupOverdue()) Banner(
                if (Prefs.vaultBackupUri == null) "No tienes respaldo configurado." else "Tu último respaldo exitoso fue ${ago(Prefs.lastVaultBackupOk)}.",
                Icons.Filled.Backup, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Revisar"
            ) { nav.go("backups") }
            if (!af && Prefs.autofillWasEnabled) Banner("Android desactivó tu autocompletado (suele pasar tras una actualización).", Icons.Filled.Warning, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, "Reparar") { openAutofillSettings(ctx) }
            if (rooted && !rootDismissed) Banner("Este teléfono parece estar rooteado. Otras apps podrían saltarse las protecciones de Android.", Icons.Filled.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Entendido") { Prefs.rootWarningDismissed = true; rootDismissed = true }

            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(report.score)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Puntaje de seguridad", style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            report.total == 0 -> "Agrega tus cuentas para analizarlas"
                            report.score >= 85 -> "Excelente. Sigue así."
                            report.score >= 60 -> "Bien, pero hay cosas por mejorar"
                            else -> "Atención: varias cuentas están en riesgo"
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        Text("${report.total} contraseñas analizadas", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }

            SectionTitle("Qué arreglar primero")
            IssueRow("Débiles", report.weak.size, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) { nav.go("health/weak") }
            IssueRow("Repetidas en varias cuentas", report.reused.size, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) { nav.go("health/reused") }
            IssueRow("Sin cambiar hace más de un año", report.old.size, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) { nav.go("health/old") }
            IssueRow("Sin verificación en dos pasos (2FA)", report.no2fa.size, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) { nav.go("health/no2fa") }

            SectionTitle("Estado")
            InfoCard {
                StatusLine(af, if (af) "Autocompletado activo" else "Autocompletado desactivado") { nav.go("autofill") }
                StatusLine(Prefs.lastVaultBackupOk > 0 && !BackupScheduler.backupOverdue(), "Respaldo bóveda: ${ago(Prefs.lastVaultBackupOk)}") { nav.go("backups") }
                StatusLine(Prefs.mediaBackupUri != null, if (Prefs.mediaBackupUri == null) "Respaldo multimedia sin configurar" else "Respaldo multimedia: ${ago(Prefs.lastMediaBackupOk)}") { nav.go("backups") }
                StatusLine(Prefs.biometricEnabled, if (Prefs.biometricEnabled) "Desbloqueo biométrico activo" else "Biometría desactivada") { nav.go("security") }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun ScoreRing(score: Int) {
    val color = when { score >= 85 -> Teal; score >= 60 -> Amber; else -> Danger }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(92.dp)) {
            val st = Stroke(width = 22f, cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, false, topLeft = Offset(11f, 11f), size = Size(size.width - 22f, size.height - 22f), style = st)
            drawArc(color, -90f, 360f * score / 100f, false, topLeft = Offset(11f, 11f), size = Size(size.width - 22f, size.height - 22f), style = st)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text("de 100", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IssueRow(label: String, count: Int, bg: Color, fg: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = fg, modifier = Modifier.weight(1f))
        Text("$count", color = fg, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp)
    }
}
