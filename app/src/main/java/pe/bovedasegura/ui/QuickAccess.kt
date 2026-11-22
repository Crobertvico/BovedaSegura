package pe.bovedasegura.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import pe.bovedasegura.autofill.PickList
import pe.bovedasegura.data.Item
import pe.bovedasegura.data.Vault
import pe.bovedasegura.util.ScreenGuard
import pe.bovedasegura.util.Totp

/** Acceso rápido desde la barra de notificaciones: copiar usuario, contraseña o 2FA en dos toques. */
class QuickAccessActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)
    private var selected by mutableStateOf<Item?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenGuard.protect(this)
        unlocked = Vault.isUnlocked
        setContent {
            BovedaTheme {
                val sel = selected
                when {
                    !unlocked -> UnlockScreen(subtitle = "Acceso rápido", onUnlocked = { unlocked = true })
                    sel == null -> PickList("Copiar desde la bóveda", onPick = { selected = it }, onCancel = { finish() })
                    else -> Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(sel.title, style = MaterialTheme.typography.headlineSmall)
                            Text("Copia y vuelve a la app donde estabas. El portapapeles se borra solo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (sel.username.isNotEmpty()) OutlinedButton(onClick = { copySecret(this@QuickAccessActivity, "Usuario", sel.username) }, modifier = Modifier.fillMaxWidth()) { Text("Copiar usuario") }
                            if (sel.password.isNotEmpty()) Button(onClick = { copySecret(this@QuickAccessActivity, "Contraseña", sel.password); Vault.markUsed(sel.id); moveTaskToBack(true) }, modifier = Modifier.fillMaxWidth()) { Text("Copiar contraseña") }
                            if (sel.totp.isNotBlank()) OutlinedButton(onClick = { Totp.codeFor(sel.totp)?.let { copySecret(this@QuickAccessActivity, "Código 2FA", it) }; moveTaskToBack(true) }, modifier = Modifier.fillMaxWidth()) { Text("Copiar código 2FA") }
                            OutlinedButton(onClick = { selected = null }, modifier = Modifier.fillMaxWidth()) { Text("Elegir otra cuenta") }
                        }
                    }
                }
            }
        }
    }
}

class QuickTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val i = Intent(this, QuickAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
