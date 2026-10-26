package io.github.loscripts.sensormaps.test

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import java.util.concurrent.TimeUnit

/** Démarre et arrête les deux méthodes de mesure : le réveil de 15 minutes et la détection d'immobilité. */
object Planif {
    private const val TRAVAIL = "reveil-15-min"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("test", Context.MODE_PRIVATE)

    fun actif(ctx: Context) = prefs(ctx).getBoolean("actif", false)

    fun debut(ctx: Context) = prefs(ctx).getLong("debut", 0L)

    fun demarrer(ctx: Context) {
        prefs(ctx).edit().putBoolean("actif", true).putLong("debut", System.currentTimeMillis()).apply()
        // 15 minutes : le plus court intervalle accepté par Android pour un réveil répété
        val requete = PeriodicWorkRequestBuilder<TravailReveil>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(TRAVAIL, ExistingPeriodicWorkPolicy.UPDATE, requete)
        Journal.ecrire(ctx, "Test démarré : réveil toutes les 15 minutes programmé")
        inscrireImmobile(ctx)
    }

    fun arreter(ctx: Context) {
        prefs(ctx).edit().putBoolean("actif", false).apply()
        WorkManager.getInstance(ctx).cancelUniqueWork(TRAVAIL)
        desinscrireImmobile(ctx)
        Journal.ecrire(ctx, "Test arrêté")
    }

    fun permissionActivite(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) ==
        PackageManager.PERMISSION_GRANTED

    // L'autorisation est vérifiée juste avant par permissionActivite()
    @SuppressLint("MissingPermission")
    fun inscrireImmobile(ctx: Context) {
        if (!permissionActivite(ctx)) {
            Journal.ecrire(ctx, "Autorisation « activité physique » refusée : la détection d'immobilité ne peut pas marcher")
            return
        }
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
        )
        val app = ctx.applicationContext
        try {
            ActivityRecognition.getClient(app)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), intentImmobile(app))
                .addOnSuccessListener { Journal.ecrire(app, "Détection d'immobilité active") }
                .addOnFailureListener { e -> Journal.ecrire(app, "Détection d'immobilité refusée : ${e.message}") }
        } catch (e: SecurityException) {
            Journal.ecrire(app, "Détection d'immobilité refusée : ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun desinscrireImmobile(ctx: Context) {
        if (!permissionActivite(ctx)) return
        try {
            ActivityRecognition.getClient(ctx.applicationContext)
                .removeActivityTransitionUpdates(intentImmobile(ctx.applicationContext))
        } catch (_: SecurityException) {
        }
    }

    private fun intentImmobile(ctx: Context): PendingIntent {
        val intent = Intent(ctx, RecepteurImmobile::class.java)
        // Les services Google ajoutent leur résultat dans l'intention : elle doit rester modifiable
        val drapeaux = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(ctx, 0, intent, drapeaux)
    }
}
