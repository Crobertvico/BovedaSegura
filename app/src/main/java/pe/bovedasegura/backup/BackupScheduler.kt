package pe.bovedasegura.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.Vault
import java.util.concurrent.TimeUnit

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!Vault.isSetUp) return@withContext Result.success()
        val r = when (inputData.getString("type")) {
            "media" -> BackupManager.backupMedia(applicationContext)
            else -> BackupManager.backupVault(applicationContext)
        }
        if (r.ok) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

object BackupScheduler {
    private const val VAULT_DAILY = "vault_daily"
    private const val VAULT_CHANGE = "vault_change"
    private const val MEDIA_PERIODIC = "media_periodic"
    private const val MEDIA_CHANGE = "media_change"

    fun schedulePeriodic(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (Prefs.vaultBackupUri != null) {
            wm.enqueueUniquePeriodicWork(
                VAULT_DAILY, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                    .setInputData(workDataOf("type" to "vault"))
                    .setInitialDelay(1, TimeUnit.HOURS)
                    .build()
            )
        }
        scheduleMedia(ctx)
    }

    private fun mediaConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (Prefs.mediaWifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
        .setRequiresCharging(Prefs.mediaChargingOnly)
        .build()

    fun scheduleMedia(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (Prefs.mediaBackupUri == null) { wm.cancelUniqueWork(MEDIA_PERIODIC); return }
        val hours = when (Prefs.mediaFrequency) { "WEEKLY" -> 24L * 7; "DAILY" -> 24L; else -> 0L }
        if (hours == 0L) { wm.cancelUniqueWork(MEDIA_PERIODIC); return }
        wm.enqueueUniquePeriodicWork(
            MEDIA_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(hours, TimeUnit.HOURS)
                .setInputData(workDataOf("type" to "media"))
                .setConstraints(mediaConstraints())
                .build()
        )
    }

    /** Se llama en cada cambio de la bóveda: a los 5 cambios lanza un respaldo. */
    fun noteChange(ctx: Context) {
        val n = Prefs.changeCounter + 1
        Prefs.changeCounter = n
        if (n >= 5 && Prefs.vaultBackupUri != null) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                VAULT_CHANGE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>().setInputData(workDataOf("type" to "vault")).setInitialDelay(2, TimeUnit.MINUTES).build()
            )
        }
    }

    /** Tras importar imágenes, si la frecuencia es "al agregar". */
    fun noteMediaAdded(ctx: Context) {
        if (Prefs.mediaBackupUri == null || Prefs.mediaFrequency != "ON_CHANGE") return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            MEDIA_CHANGE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().setInputData(workDataOf("type" to "media"))
                .setConstraints(mediaConstraints()).setInitialDelay(5, TimeUnit.MINUTES).build()
        )
    }

    fun backupOverdue(): Boolean = Prefs.vaultBackupUri == null ||
        System.currentTimeMillis() - Prefs.lastVaultBackupOk > 3L * 24 * 3600 * 1000
}
