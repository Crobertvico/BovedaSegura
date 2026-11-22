package pe.bovedasegura.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import pe.bovedasegura.crypto.DeviceKeys
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.Vault
import pe.bovedasegura.data.b64
import pe.bovedasegura.data.unb64

object Biometrics {
    private const val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun info(title: String, subtitle: String? = null) = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { if (subtitle != null) setSubtitle(subtitle) }
        .setNegativeButtonText("Usar contraseña")
        .setAllowedAuthenticators(STRONG)
        .build()

    private fun prompt(activity: FragmentActivity, onOk: (BiometricPrompt.AuthenticationResult) -> Unit, onFail: (String?) -> Unit) =
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk(result)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        })

    /** Activa el desbloqueo biométrico guardando la llave de la bóveda real cifrada por el Keystore. */
    fun enroll(activity: FragmentActivity, onDone: (Boolean, String?) -> Unit) {
        val key = Vault.vaultKeyForBiometric() ?: return onDone(false, "Solo disponible en tu bóveda principal")
        val cipher = try { DeviceKeys.bioEncryptCipher() } catch (e: Exception) { return onDone(false, e.message) }
        prompt(activity, { r ->
            try {
                val c = r.cryptoObject?.cipher ?: throw IllegalStateException("sin cifrador")
                val ct = c.doFinal(key)
                Prefs.bioIv = c.iv.b64()
                Prefs.bioBlob = ct.b64()
                Prefs.biometricEnabled = true
                key.fill(0)
                onDone(true, null)
            } catch (e: Exception) { onDone(false, e.message) }
        }, { msg -> key.fill(0); onDone(false, msg) }).authenticate(info("Activar desbloqueo biométrico"), BiometricPrompt.CryptoObject(cipher))
    }

    fun disable() {
        Prefs.biometricEnabled = false
        Prefs.bioIv = null
        Prefs.bioBlob = null
        DeviceKeys.deleteBioKey()
    }

    /** Devuelve la llave de la bóveda si la huella fue correcta; null si se canceló o la llave se invalidó. */
    fun unlock(activity: FragmentActivity, onResult: (ByteArray?, String?) -> Unit) {
        val iv = Prefs.bioIv?.unb64()
        val blob = Prefs.bioBlob?.unb64()
        if (!Prefs.biometricEnabled || iv == null || blob == null) return onResult(null, null)
        val cipher = DeviceKeys.bioDecryptCipher(iv)
        if (cipher == null) {
            disable()
            return onResult(null, "Se registró una huella nueva. Por seguridad, ingresa tu contraseña y vuelve a activar la biometría.")
        }
        prompt(activity, { r ->
            try { onResult(r.cryptoObject?.cipher?.doFinal(blob), null) } catch (e: Exception) { onResult(null, e.message) }
        }, { msg -> onResult(null, msg) }).authenticate(info("Desbloquear Bóveda Segura"), BiometricPrompt.CryptoObject(cipher))
    }

    /** Confirmación simple (sin llave) para acciones sensibles. */
    fun confirm(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        prompt(activity, { onResult(true) }, { onResult(false) }).authenticate(info(title))
    }
}
