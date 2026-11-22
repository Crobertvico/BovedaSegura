package pe.bovedasegura.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Llaves guardadas en el chip seguro del teléfono (Android Keystore). Nunca salen del hardware.
 *  - DEVICE: protege la clave secreta en el disco del propio teléfono.
 *  - BIOMETRIC: exige huella/rostro en cada uso; se invalida si se registra una huella nueva.
 */
object DeviceKeys {
    private const val DEVICE_ALIAS = "bs_device_v1"
    private const val BIO_ALIAS = "bs_bio_v1"

    private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun deviceKey(): SecretKey {
        val store = ks()
        (store.getKey(DEVICE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(DEVICE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun deviceSeal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, deviceKey())
        return c.iv + c.doFinal(plain)
    }

    fun deviceOpen(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    // ---------- Biometría ----------
    private fun createBioKey(): SecretKey {
        val b = KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(b.build())
        return gen.generateKey()
    }

    /** Cipher listo para cifrar (activar biometría). Requiere autenticación biométrica para usarse. */
    fun bioEncryptCipher(): Cipher {
        deleteBioKey()
        val key = createBioKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Cipher para descifrar. Devuelve null si la llave fue invalidada (huella nueva). */
    fun bioDecryptCipher(iv: ByteArray): Cipher? {
        return try {
            val key = ks().getKey(BIO_ALIAS, null) as? SecretKey ?: return null
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteBioKey(); null
        } catch (e: Exception) {
            null
        }
    }

    fun deleteBioKey() {
        try { ks().deleteEntry(BIO_ALIAS) } catch (_: Exception) {}
    }

    fun deleteAll() {
        try { ks().deleteEntry(BIO_ALIAS) } catch (_: Exception) {}
        try { ks().deleteEntry(DEVICE_ALIAS) } catch (_: Exception) {}
    }
}
