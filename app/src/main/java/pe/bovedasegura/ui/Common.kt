@file:OptIn(ExperimentalMaterial3Api::class)

package pe.bovedasegura.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pe.bovedasegura.data.Vault
import pe.bovedasegura.util.SecureClipboard
import pe.bovedasegura.util.Strength
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun formatDate(ms: Long): String = if (ms <= 0) "Nunca" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "PE")).format(Date(ms))

fun ago(ms: Long): String {
    if (ms <= 0) return "nunca"
    val d = System.currentTimeMillis() - ms
    return when {
        d < 60_000 -> "hace un momento"
        d < 3_600_000 -> "hace ${d / 60_000} min"
        d < 86_400_000 -> "hace ${d / 3_600_000} h"
        else -> "hace ${d / 86_400_000} días"
    }
}

fun copySecret(ctx: Context, label: String, value: String) {
    SecureClipboard.copy(ctx, label, value, Vault.current().settings.clipboardSeconds)
}

@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Atrás") } },
        actions = { actions() }
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surface, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Column(Modifier.padding(14.dp)) { content() } }
}

@Composable
fun Banner(text: String, icon: ImageVector, container: Color, content: Color, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(container, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = content)
        Spacer(Modifier.width(10.dp))
        Text(text, color = content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = content, fontWeight = FontWeight.Medium) }
    }
}

@Composable
fun MenuRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StrengthBar(password: String) {
    val s = Strength.score(password)
    val color = when (s) { 0 -> Danger; 1 -> Color(0xFFD85A30); 2 -> Amber; 3 -> Mint; else -> Teal }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        LinearProgressIndicator(progress = { if (password.isEmpty()) 0f else (s + 1) / 5f }, color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().height(6.dp))
        if (password.isNotEmpty()) Text("${Strength.label(s)} · ${password.length} caracteres", color = color, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun PasswordInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, imeDone: Boolean = false, isError: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Mostrar") } },
        modifier = modifier.fillMaxWidth()
    )
}

/** Fila de solo lectura con copiar y mostrar/ocultar. */
@Composable
fun FieldRow(label: String, value: String, secret: Boolean, mono: Boolean = false) {
    if (value.isEmpty()) return
    val ctx = LocalContext.current
    var visible by remember { mutableStateOf(!secret) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (visible) value else "•".repeat(value.length.coerceIn(8, 16)),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (mono || (secret && visible)) FontFamily.Monospace else null
            )
        }
        if (secret) IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Mostrar") }
        IconButton(onClick = { copySecret(ctx, label, value) }) { Icon(Icons.Filled.ContentCopy, "Copiar") }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Aceptar", danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Pide la contraseña maestra (o huella) antes de una acción sensible: exportar, ver CSV, etc. */
@Composable
fun ReauthDialog(reason: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Confirma tu identidad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reason, style = MaterialTheme.typography.bodyMedium)
                PasswordInput(pw, { pw = it; error = null }, "Contraseña maestra", isError = error != null)
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Verificando…") }
                if (Biometrics.available(ctx) && pe.bovedasegura.data.Prefs.biometricEnabled && !Vault.isDecoy) {
                    TextButton(onClick = {
                        (ctx.findActivity() as? androidx.fragment.app.FragmentActivity)?.let { act ->
                            Biometrics.confirm(act, "Confirma con tu huella") { ok -> if (ok) onOk() }
                        }
                    }) { Text("Usar huella o rostro") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && pw.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    val ok = Vault.verifyPassword(pw.toCharArray())
                    busy = false
                    if (ok) onOk() else error = "Contraseña incorrecta"
                }
            }) { Text("Confirmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") } }
    )
}

@Composable
fun Busy(text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text, color = fg, fontSize = 13.sp,
        modifier = Modifier
            .background(bg, RoundedCornerShape(16.dp))
            .then(if (!selected) Modifier.background(Color.Transparent) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
