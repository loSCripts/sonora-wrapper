package com.sonora.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Service de premier plan minimal.
 *
 * Empecher visibilitychange ne suffit pas sur Android : sans notification
 * persistante, le systeme (et surtout la gestion agressive de la batterie
 * chez Oppo/ColorOS) gele ou tue le processus quelques secondes apres le
 * passage en arriere-plan, et le son s'arrete quand meme.
 *
 * Ce service ne fait rien d'autre qu'exister pour maintenir le processus
 * vivant. Il n'y a pas de controles de lecture dans la notification :
 * elle sert uniquement d'ancre.
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "sonora_playback";
    private static final int NOTIF_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Lecture en arriere-plan",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        Notification n = b
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Lecture active en arriere-plan")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIF_ID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
