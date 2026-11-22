package pe.bovedasegura.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateListOf
import androidx.fragment.app.FragmentActivity
import pe.bovedasegura.AutoLock
import pe.bovedasegura.util.ScreenGuard
import pe.bovedasegura.util.TempFiles

class MainActivity : FragmentActivity() {

    companion object {
        /** Archivos recibidos con "Compartir con Bóveda Segura", pendientes de importar tras desbloquear. */
        val pendingShares = mutableStateListOf<Uri>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenGuard.protect(this)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { BovedaTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Borra copias temporales que se abrieron con otras apps.
        if (!AutoLock.suspended) TempFiles.cleanup(this)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        AutoLock.touch()
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                u?.let { pendingShares.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                list?.let { pendingShares.addAll(it) }
            }
        }
    }
}
