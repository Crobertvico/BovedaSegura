package pe.bovedasegura.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import java.io.File

object SecureClipboard {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** Copia marcando el contenido como sensible y lo borra a los [seconds] segundos. */
    fun copy(ctx: Context, label: String, text: String, seconds: Int = 30, toast: Boolean = true) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            else putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            try {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            } catch (_: Exception) {}
        }
        pending = r
        handler.postDelayed(r, seconds * 1000L)
        if (toast && Build.VERSION.SDK_INT < 33) {
            Toast.makeText(ctx, "$label copiado. Se borrará en $seconds s", Toast.LENGTH_SHORT).show()
        }
    }
}

object ScreenGuard {
    /** Bloquea capturas, grabación y vista previa en Recientes; filtra toques bajo ventanas superpuestas. */
    fun protect(activity: Activity) {
        activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        activity.window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) {
            try { activity.window.setHideOverlayWindows(true) } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try { activity.setRecentsScreenshotEnabled(false) } catch (_: Exception) {}
        }
    }
}

object RootCheck {
    private val paths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/vendor/bin/su",
        "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su", "/system/app/Superuser.apk",
        "/system/bin/.ext/su", "/cache/su", "/data/adb/magisk", "/system/sbin/su"
    )

    fun isLikelyRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        return paths.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }
}

/** Archivos temporales descifrados: se borran al volver a la app y al iniciar. */
object TempFiles {
    fun dir(ctx: Context, name: String): File = File(ctx.cacheDir, name).apply { mkdirs() }
    fun cleanup(ctx: Context) {
        listOf("open", "pdf", "share").forEach { File(ctx.cacheDir, it).deleteRecursively() }
    }
}
