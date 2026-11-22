package pe.bovedasegura

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import pe.bovedasegura.backup.BackupScheduler
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.Vault
import pe.bovedasegura.util.TempFiles

class BovedaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Vault.init(this)
        TempFiles.cleanup(this)
        Vault.onChanged = { BackupScheduler.noteChange(this) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(AutoLock)
        AutoLock.start()
        if (Vault.isSetUp) BackupScheduler.schedulePeriodic(this)
    }
}

/** Bloqueo automático: al salir de la app o tras inactividad. */
object AutoLock : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var backgroundedAt = 0L
    @Volatile var lastTouch = System.currentTimeMillis()
    /** Se pone en true mientras se usa una app externa a propósito (p. ej. elegir un archivo). */
    @Volatile var suspended = false

    private val lockRunnable = Runnable { if (!suspended) Vault.lock() }
    private val ticker = object : Runnable {
        override fun run() {
            val limit = Prefs.autoLockSeconds.let { if (it == 0) 300 else it } * 1000L
            if (Vault.isUnlocked && !suspended && backgroundedAt == 0L && System.currentTimeMillis() - lastTouch > limit) {
                Vault.lock()
            }
            handler.postDelayed(this, 10_000)
        }
    }

    fun start() { handler.postDelayed(ticker, 10_000) }
    fun touch() { lastTouch = System.currentTimeMillis() }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
        if (suspended) return
        val secs = Prefs.autoLockSeconds
        if (secs == 0) Vault.lock() else handler.postDelayed(lockRunnable, secs * 1000L)
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(lockRunnable)
        val secs = Prefs.autoLockSeconds
        if (backgroundedAt > 0 && !suspended && System.currentTimeMillis() - backgroundedAt >= secs * 1000L) Vault.lock()
        backgroundedAt = 0L
        touch()
    }
}
