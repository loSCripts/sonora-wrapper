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

/**
 * Service de premier plan + notification de controle du lecteur.
 *
 * Il ne pilote rien lui-meme : chaque bouton renvoie vers le gestionnaire
 * que la page a deja enregistre dans navigator.mediaSession. C'est donc
 * exactement le meme comportement que les boutons de l'interface.
 */
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
    private long dureeMs = 0;
    private long positionMs = 0;
    private final Set<String> actions = new HashSet<String>();

    // ---- Entrees appelees depuis JsBridge -------------------------

    public static void pousserMeta(String t, String a, String al, String url) {
        final KeepAliveService s = instance;
        if (s == null) {
            return;
        }
        s.titre = (t == null || t.length() == 0) ? "Sonora" : t;
        s.artiste = a == null ? "" : a;
        s.album = al == null ? "" : al;
        if (url != null && !url.equals(s.pochetteUrl)) {
            s.pochetteUrl = url;
            s.chargerPochette(url);
        }
        s.rafraichir();
    }

    public static void pousserEtat(boolean lecture) {
        final KeepAliveService s = instance;
        if (s == null) {
            return;
        }
        s.enLecture = lecture;
        s.rafraichir();
    }

    public static void pousserPosition(long duree, long position) {
        final KeepAliveService s = instance;
        if (s == null) {
            return;
        }
        s.dureeMs = duree;
        s.positionMs = position;
        s.rafraichir();
    }

    public static void pousserActions(String csv) {
        final KeepAliveService s = instance;
        if (s == null) {
            return;
        }
        s.actions.clear();
        if (csv != null && csv.length() > 0) {
            String[] parts = csv.split(",");
            for (int i = 0; i < parts.length; i++) {
                s.actions.add(parts[i].trim());
            }
        }
        s.rafraichir();
    }

    // ---- Cycle de vie ---------------------------------------------

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
            @Override
            public void onPlay() {
                MainActivity.appelerJs("play", 0);
            }

            @Override
            public void onPause() {
                MainActivity.appelerJs("pause", 0);
            }

            @Override
            public void onSkipToNext() {
                MainActivity.appelerJs("nexttrack", 0);
            }

            @Override
            public void onSkipToPrevious() {
                MainActivity.appelerJs("previoustrack", 0);
            }

            @Override
            public void onFastForward() {
                MainActivity.appelerJs("seekforward", 10);
            }

            @Override
            public void onRewind() {
                MainActivity.appelerJs("seekbackward", 10);
            }

            @Override
            public void onStop() {
                MainActivity.appelerJs("pause", 0);
            }
        });
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
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- Construction de la notification --------------------------

    private void rafraichir() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    majSession();
                    NotificationManager nm =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    nm.notify(NOTIF_ID, construire());
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void majSession() {
        if (session == null) {
            return;
        }

        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titre)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artiste)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dureeMs);
        if (pochette != null) {
            m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, pochette);
        }
        session.setMetadata(m.build());

        long dispo = PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE;
        if (actions.contains("nexttrack")) {
            dispo |= PlaybackState.ACTION_SKIP_TO_NEXT;
        }
        if (actions.contains("previoustrack")) {
            dispo |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        if (actions.contains("seekforward")) {
            dispo |= PlaybackState.ACTION_FAST_FORWARD;
        }
        if (actions.contains("seekbackward")) {
            dispo |= PlaybackState.ACTION_REWIND;
        }

        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(dispo)
                .setState(enLecture ? PlaybackState.STATE_PLAYING
                                    : PlaybackState.STATE_PAUSED,
                          positionMs, 1.0f)
                .build());
    }

    private Notification construire() {
        Intent ouvrir = new Intent(this, MainActivity.class);
        ouvrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ouvrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CANAL);
        } else {
            b = new Notification.Builder(this);
        }

        b.setContentTitle(titre)
         .setContentText(artiste.length() > 0 ? artiste : "Sonora")
         .setSmallIcon(android.R.drawable.ic_media_play)
         .setContentIntent(pi)
         .setOngoing(true)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setShowWhen(false);

        if (pochette != null) {
            b.setLargeIcon(pochette);
        }

        // Les boutons suivent ce que la page declare : on n'affiche que
        // ce qui existe vraiment.
        int nb = 0;
        int idxPlay = 0;

        if (actions.contains("previoustrack")) {
            b.addAction(action(android.R.drawable.ic_media_previous,
                    "Precedent", ACT_PREV, 1));
            nb++;
        }
        if (actions.contains("seekbackward")) {
            b.addAction(action(android.R.drawable.ic_media_rew,
                    "-10 s", ACT_BACK, 2));
            nb++;
        }

        idxPlay = nb;
        b.addAction(action(
                enLecture ? android.R.drawable.ic_media_pause
                          : android.R.drawable.ic_media_play,
                enLecture ? "Pause" : "Lecture", ACT_PLAYPAUSE, 3));
        nb++;

        if (actions.contains("seekforward")) {
            b.addAction(action(android.R.drawable.ic_media_ff,
                    "+10 s", ACT_FWD, 4));
            nb++;
        }
        if (actions.contains("nexttrack")) {
            b.addAction(action(android.R.drawable.ic_media_next,
                    "Suivant", ACT_NEXT, 5));
            nb++;
        }

        Notification.MediaStyle style = new Notification.MediaStyle();
        if (session != null) {
            style.setMediaSession(session.getSessionToken());
        }

        // Vue compacte : le bouton lecture, plus ses voisins immediats.
        if (nb >= 3) {
            int debut = idxPlay - 1;
            if (debut < 0) {
                debut = 0;
            }
            if (debut + 2 > nb - 1) {
                debut = nb - 3;
            }
            style.setShowActionsInCompactView(debut, debut + 1, debut + 2);
        } else if (nb == 2) {
            style.setShowActionsInCompactView(0, 1);
        } else {
            style.setShowActionsInCompactView(0);
        }

        b.setStyle(style);
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

    // ---- Pochette --------------------------------------------------

    private void chargerPochette(final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
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
                    if (co != null) {
                        co.disconnect();
                    }
                }
                if (bmp != null) {
                    pochette = bmp;
                    rafraichir();
                }
            }
        }).start();
    }
}
