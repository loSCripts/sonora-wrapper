package com.sonora.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class KeepAliveService extends Service {

    private static final String CANAL = "sonora_playback";
    private static final int NOTIF_ID = 1;

    public static final String ACT_PLAYPAUSE = "com.sonora.app.PLAYPAUSE";
    public static final String ACT_NEXT      = "com.sonora.app.NEXT";
    public static final String ACT_PREV      = "com.sonora.app.PREV";
    public static final String ACT_FWD       = "com.sonora.app.FWD";
    public static final String ACT_BACK      = "com.sonora.app.BACK";

    private static KeepAliveService instance;

    private MediaSession session;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String titre = "Sonora";
    private String artiste = "";
    private String album = "";
    private String pochetteUrl = "";
    private Bitmap pochette;
    private boolean enLecture = false;
    private boolean pontOk = false;
    private boolean aDesMeta = false;
    private long dureeMs = 0;
    private long positionMs = 0;
    private final Set<String> actions = new HashSet<String>();
    private String dernierChemin = null;

    /** Remonte quel chemin a effectivement pilote le lecteur (diagnostic). */
    public static void pousserChemin(String c) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        s.dernierChemin = c;
        s.rafraichir();
    }

    public static void pousserPont() {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        if (!s.pontOk) { s.pontOk = true; s.rafraichir(); }
    }

    public static void pousserMeta(String t, String a, String al, String url) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        s.pontOk = true;
        if (t != null && t.length() > 0) { s.titre = t; s.aDesMeta = true; }
        s.artiste = a == null ? "" : a;
        s.album = al == null ? "" : al;
        if (url != null && url.length() > 0 && !url.equals(s.pochetteUrl)) {
            s.pochetteUrl = url;
            s.chargerPochette(url);
        }
        s.rafraichir();
    }

    public static void pousserEtat(boolean lecture) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        s.pontOk = true;
        if (s.enLecture != lecture) { s.enLecture = lecture; s.rafraichir(); }
    }

    public static void pousserPosition(long duree, long position) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        s.dureeMs = duree;
        s.positionMs = position;
    }

    public static void pousserActions(String csv) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        s.pontOk = true;
        Set<String> nouv = new HashSet<String>();
        if (csv != null && csv.length() > 0) {
            String[] parts = csv.split(",");
            for (int i = 0; i < parts.length; i++) { nouv.add(parts[i].trim()); }
        }
        if (!nouv.equals(s.actions)) {
            s.actions.clear();
            s.actions.addAll(nouv);
            s.rafraichir();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CANAL, "Lecture", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(c);
        }

        session = new MediaSession(this, "Sonora");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay()          { MainActivity.appelerJs("play", 0); }
            @Override public void onPause()         { MainActivity.appelerJs("pause", 0); }
            @Override public void onSkipToNext()    { MainActivity.appelerJs("nexttrack", 0); }
            @Override public void onSkipToPrevious(){ MainActivity.appelerJs("previoustrack", 0); }
            @Override public void onFastForward()   { MainActivity.appelerJs("seekforward", 10); }
            @Override public void onRewind()        { MainActivity.appelerJs("seekbackward", 10); }
            @Override public void onStop()          { MainActivity.appelerJs("pause", 0); }
        });

        // CORRECTIF v1.3 : la session doit avoir des metadonnees ET un etat
        // AVANT d'etre rattachee a la notification. Une session vide fait
        // disparaitre la notification des deux cotes (liste + zone media).
        majSession();
        session.setActive(true);

        startForeground(NOTIF_ID, construire());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACT_PLAYPAUSE.equals(a)) {
                MainActivity.appelerJs(enLecture ? "pause" : "play", 0);
            } else if (ACT_NEXT.equals(a)) {
                MainActivity.appelerJs("nexttrack", 0);
            } else if (ACT_PREV.equals(a)) {
                MainActivity.appelerJs("previoustrack", 0);
            } else if (ACT_FWD.equals(a)) {
                MainActivity.appelerJs("seekforward", 10);
            } else if (ACT_BACK.equals(a)) {
                MainActivity.appelerJs("seekbackward", 10);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void rafraichir() {
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    majSession();
                    NotificationManager nm =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    nm.notify(NOTIF_ID, construire());
                } catch (Throwable ignored) { }
            }
        });
    }

    private void majSession() {
        if (session == null) { return; }
        try {
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, titre)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST,
                            artiste.length() > 0 ? artiste : "Sonora")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION,
                            dureeMs > 0 ? dureeMs : 1);
            if (pochette != null) {
                m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, pochette);
            }
            session.setMetadata(m.build());

            long dispo = PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_FAST_FORWARD
                    | PlaybackState.ACTION_REWIND
                    | PlaybackState.ACTION_SEEK_TO;

            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(dispo)
                    .setState(enLecture ? PlaybackState.STATE_PLAYING
                                        : PlaybackState.STATE_PAUSED,
                              positionMs, enLecture ? 1.0f : 0.0f)
                    .build());
        } catch (Throwable ignored) { }
    }

    /** Toujours renvoyer une notification, quoi qu'il arrive. */
    private Notification construire() {
        try {
            return construireMedia();
        } catch (Throwable t) {
            return construireSimple(t.getClass().getSimpleName());
        }
    }

    private PendingIntent ouvrirApp() {
        Intent o = new Intent(this, MainActivity.class);
        o.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, o,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private String sousTitre() {
        if (aDesMeta) {
            return artiste.length() > 0 ? artiste : "Sonora";
        }
        return pontOk ? "Pont connecte - en attente d'une lecture"
                      : "En attente du lecteur...";
    }

    private Notification.Builder base() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CANAL);
        } else {
            b = new Notification.Builder(this);
        }
        if (dernierChemin != null) { b.setSubText(dernierChemin); }
        b.setSmallIcon(android.R.drawable.ic_media_play)
         .setContentIntent(ouvrirApp())
         .setOngoing(true)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setShowWhen(false);
        return b;
    }

    private Notification construireMedia() {
        Notification.Builder b = base()
                .setContentTitle(titre)
                .setContentText(sousTitre());

        if (pochette != null) { b.setLargeIcon(pochette); }

        // Les 3 boutons de base sont TOUJOURS presents : si la page n'a pas
        // declare le gestionnaire, l'appel JS est simplement sans effet.
        b.addAction(action(android.R.drawable.ic_media_previous, "Precedent", ACT_PREV, 1));
        int idxPlay = 1;
        b.addAction(action(
                enLecture ? android.R.drawable.ic_media_pause
                          : android.R.drawable.ic_media_play,
                enLecture ? "Pause" : "Lecture", ACT_PLAYPAUSE, 3));
        b.addAction(action(android.R.drawable.ic_media_next, "Suivant", ACT_NEXT, 5));
        int nb = 3;

        if (actions.contains("seekbackward")) {
            b.addAction(action(android.R.drawable.ic_media_rew, "-10 s", ACT_BACK, 2));
            nb++;
        }
        if (actions.contains("seekforward")) {
            b.addAction(action(android.R.drawable.ic_media_ff, "+10 s", ACT_FWD, 4));
            nb++;
        }

        Notification.MediaStyle style = new Notification.MediaStyle();
        if (session != null) { style.setMediaSession(session.getSessionToken()); }
        style.setShowActionsInCompactView(idxPlay - 1, idxPlay, idxPlay + 1);
        b.setStyle(style);

        return b.build();
    }

    /** Repli sans MediaStyle : la v1.1 s'affichait, celle-ci s'affichera. */
    private Notification construireSimple(String cause) {
        Notification.Builder b = base()
                .setContentTitle(titre)
                .setContentText(cause == null
                        ? sousTitre()
                        : "Mode simplifie (" + cause + ")");
        b.addAction(action(android.R.drawable.ic_media_previous, "Precedent", ACT_PREV, 1));
        b.addAction(action(
                enLecture ? android.R.drawable.ic_media_pause
                          : android.R.drawable.ic_media_play,
                enLecture ? "Pause" : "Lecture", ACT_PLAYPAUSE, 3));
        b.addAction(action(android.R.drawable.ic_media_next, "Suivant", ACT_NEXT, 5));
        return b.build();
    }

    private Notification.Action action(int icone, String texte, String cle, int code) {
        Intent i = new Intent(this, KeepAliveService.class);
        i.setAction(cle);
        PendingIntent pi = PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, icone),
                texte, pi).build();
    }

    private void chargerPochette(final String url) {
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                HttpURLConnection co = null;
                try {
                    co = (HttpURLConnection) new URL(url).openConnection();
                    co.setConnectTimeout(8000);
                    co.setReadTimeout(8000);
                    co.setInstanceFollowRedirects(true);
                    InputStream in = co.getInputStream();
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    bmp = BitmapFactory.decodeStream(in, null, o);
                    in.close();
                } catch (Throwable ignored) {
                } finally {
                    if (co != null) { co.disconnect(); }
                }
                if (bmp != null) { pochette = bmp; rafraichir(); }
            }
        }).start();
    }
}
