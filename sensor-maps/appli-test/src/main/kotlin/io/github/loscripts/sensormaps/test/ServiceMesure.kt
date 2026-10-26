package io.github.loscripts.sensormaps.test

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Le court service au premier plan : une rafale de 10 secondes, puis il s'arrête tout seul. */
class ServiceMesure : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        try {
            ServiceCompat.startForeground(this, ID_NOTIF, notification(this), type)
        } catch (e: Exception) {
            Journal.ecrire(this, "Service de mesure : Android refuse le premier plan (${e.javaClass.simpleName})")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val app = applicationContext
        Thread {
            try {
                val etat = Capteurs.etat(app)
                val r = Capteurs.rafale(app)
                Resume.noter(app, Methode.IMMOBILE, r)
                Journal.ecrire(app, Texte.ligne("Mesure par le service", etat, r))
            } finally {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    // Android 14 et plus : un service court doit s'arrêter quand Android le demande (au bout de 3 minutes)
    override fun onTimeout(startId: Int) {
        Journal.ecrire(this, "Service de mesure arrêté par Android : délai dépassé")
        stopSelf(startId)
    }

    private fun notification(ctx: Context): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Mesures du test", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Mesure en cours")
            .setContentText("Pression et champ magnétique pendant 10 secondes")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private companion object {
        const val ID_NOTIF = 1
        const val CANAL = "mesures"
    }
}
