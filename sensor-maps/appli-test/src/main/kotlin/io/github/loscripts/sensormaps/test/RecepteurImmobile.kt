package io.github.loscripts.sensormaps.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Méthode 2 : les services Google signalent que le téléphone devient immobile.
 * On lance alors le court service de mesure. Si Android le refuse, on essaie de mesurer directement ici.
 */
class RecepteurImmobile : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val resultat = ActivityTransitionResult.extractResult(intent) ?: return
        var mesureDirecteLancee = false
        for (ev in resultat.transitionEvents) {
            if (ev.activityType != DetectedActivity.STILL) continue
            val retard = (SystemClock.elapsedRealtimeNanos() - ev.elapsedRealTimeNanos) / 1_000_000_000L
            if (ev.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                Journal.ecrire(app, "Le téléphone bouge de nouveau (signalé après $retard s)")
                continue
            }
            Resume.compter(app, "immobile_detecte")
            val etat = Texte.etat(Capteurs.etat(app))
            try {
                ContextCompat.startForegroundService(app, Intent(app, ServiceMesure::class.java))
                Journal.ecrire(app, "Téléphone immobile (signalé après $retard s) : service de mesure lancé | $etat")
            } catch (e: Exception) {
                Resume.compter(app, "immobile_refuse")
                Journal.ecrire(
                    app,
                    "Téléphone immobile (signalé après $retard s) : Android refuse le service " +
                        "(${e.javaClass.simpleName}) | $etat",
                )
                if (!mesureDirecteLancee) {
                    mesureDirecteLancee = true
                    mesureDirecte(app)
                }
            }
        }
    }

    // Plan B : mesurer pendant la réception elle-même (8 secondes, sous la limite de temps d'Android)
    private fun mesureDirecte(app: Context) {
        val attente = goAsync()
        Thread {
            try {
                val etat = Capteurs.etat(app)
                val r = Capteurs.rafale(app, 8_000L)
                Resume.noter(app, Methode.DIRECT, r)
                Journal.ecrire(app, Texte.ligne("Mesure directe sans service", etat, r))
            } finally {
                attente.finish()
            }
        }.start()
    }
}
