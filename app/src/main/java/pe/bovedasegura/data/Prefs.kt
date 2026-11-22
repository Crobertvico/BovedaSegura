package pe.bovedasegura.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferencias NO sensibles (necesarias con la bóveda bloqueada): intentos fallidos,
 * tiempos de bloqueo, carpetas de respaldo, estado de los respaldos. Nada de esto revela contenido.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("bs_prefs", Context.MODE_PRIVATE)
    }

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun long(k: String, d: Long) = sp.getLong(k, d)
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun str(k: String) = sp.getString(k, null)
    private fun put(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    var failedAttempts: Int
        get() = int("failed", 0)
        set(v) = put { putInt("failed", v) }
    var lockoutUntil: Long
        get() = long("lockout", 0)
        set(v) = put { putLong("lockout", v) }
    /** 0 = desactivada. */
    var selfDestructAfter: Int
        get() = int("selfDestruct", 0)
        set(v) = put { putInt("selfDestruct", v) }
    /** Segundos de inactividad antes de bloquear. 0 = al salir de la app. */
    var autoLockSeconds: Int
        get() = int("autoLock", 60)
        set(v) = put { putInt("autoLock", v) }
    var biometricEnabled: Boolean
        get() = bool("bio", false)
        set(v) = put { putBoolean("bio", v) }
    var bioIv: String?
        get() = str("bioIv")
        set(v) = put { putString("bioIv", v) }
    var bioBlob: String?
        get() = str("bioBlob")
        set(v) = put { putString("bioBlob", v) }
    var setupComplete: Boolean
        get() = bool("setupDone", false)
        set(v) = put { putBoolean("setupDone", v) }
    var kitSaved: Boolean
        get() = bool("kitSaved", false)
        set(v) = put { putBoolean("kitSaved", v) }
    var autofillWasEnabled: Boolean
        get() = bool("afEver", false)
        set(v) = put { putBoolean("afEver", v) }
    var rootWarningDismissed: Boolean
        get() = bool("rootDismiss", false)
        set(v) = put { putBoolean("rootDismiss", v) }

    // ----- Respaldo de bóveda -----
    var vaultBackupUri: String?
        get() = str("vbUri")
        set(v) = put { putString("vbUri", v) }
    var lastVaultBackup: Long
        get() = long("vbLast", 0)
        set(v) = put { putLong("vbLast", v) }
    var lastVaultBackupOk: Long
        get() = long("vbLastOk", 0)
        set(v) = put { putLong("vbLastOk", v) }
    var lastVaultBackupMsg: String?
        get() = str("vbMsg")
        set(v) = put { putString("vbMsg", v) }
    var lastVaultBackupSize: Long
        get() = long("vbSize", 0)
        set(v) = put { putLong("vbSize", v) }
    var lastDeepVerified: Long
        get() = long("vbDeep", 0)
        set(v) = put { putLong("vbDeep", v) }
    var changeCounter: Int
        get() = int("changes", 0)
        set(v) = put { putInt("changes", v) }

    // ----- Respaldo multimedia -----
    var mediaBackupUri: String?
        get() = str("mbUri")
        set(v) = put { putString("mbUri", v) }
    var lastMediaBackupOk: Long
        get() = long("mbLastOk", 0)
        set(v) = put { putLong("mbLastOk", v) }
    var lastMediaBackupMsg: String?
        get() = str("mbMsg")
        set(v) = put { putString("mbMsg", v) }
    var lastMediaBackupSize: Long
        get() = long("mbSize", 0)
        set(v) = put { putLong("mbSize", v) }
    /** ON_CHANGE, DAILY, WEEKLY */
    var mediaFrequency: String
        get() = str("mbFreq") ?: "DAILY"
        set(v) = put { putString("mbFreq", v) }
    var mediaWifiOnly: Boolean
        get() = bool("mbWifi", true)
        set(v) = put { putBoolean("mbWifi", v) }
    var mediaChargingOnly: Boolean
        get() = bool("mbCharge", false)
        set(v) = put { putBoolean("mbCharge", v) }
    /** JSON nombre -> momento en que dejó de existir localmente (retención de 30 días). */
    var mediaTombstones: String
        get() = str("mbTomb") ?: "{}"
        set(v) = put { putString("mbTomb", v) }

    fun clearAll() = sp.edit().clear().commit()
}
