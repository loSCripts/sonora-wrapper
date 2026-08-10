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
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class KeepAliveService extends Service {

    private static final String CANAL = "sonora_playback";
    private static final int NOTIF_ID = 1;

    /**
     * Cote de la pochette envoyee au systeme. Une MediaSession voyage par
     * Binder : au-dela du megaoctet la transaction est refusee et la
     * notification perd d'un coup titre, artiste ET image. 512 en RGB_565
     * fait 512 Ko, large marge, et reste net sur un ecran verrouille.
     */
    private static final int POCHETTE_MAX = 512;

    /** Au-dela, on ne telecharge meme pas : ce n'est pas une pochette. */
    private static final int POIDS_MAX = 6 * 1024 * 1024;

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
    private String moteur = "";
    private String pochetteUrl = "";
    private Bitmap pochette;
    private String pochetteErreur;
    private boolean enLecture = false;
    private boolean pontOk = false;
    private boolean aDesMeta = false;
    private long dureeMs = 0;
    private long positionMs = 0;
    private final Set<String> actions = new HashSet<String>();
    private String dernierChemin = null;
    private String source = null;

    /** Remonte quel chemin a effectivement pilote le lecteur (diagnostic). */
    public static void pousserChemin(String c) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        s.dernierChemin = c;
        s.rafraichir();
    }

    /** D'ou viennent l'etat et la position cote page (diagnostic). */
    public static void pousserSource(String src) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        s.pontOk = true;
        if (src != null && !src.equals(s.source)) {
            s.source = src;
            s.rafraichir();
        }
    }

    /** Moteur de lecture du site : sert a nommer la plateforme. */
    public static void pousserMoteur(String m) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        String n = m == null ? "" : m;
        if (!n.equals(s.moteur)) { s.moteur = n; s.rafraichir(); }
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

        String nt = (t != null && t.length() > 0) ? t : s.titre;
        String na = a == null ? "" : a;
        String nal = al == null ? "" : al;
        boolean change = !nt.equals(s.titre) || !na.equals(s.artiste) || !nal.equals(s.album);

        s.titre = nt;
        s.artiste = na;
        s.album = nal;
        if (t != null && t.length() > 0) { s.aDesMeta = true; }

        if (url != null && url.length() > 0 && !url.equals(s.pochetteUrl)) {
            s.pochetteUrl = url;
            s.pochetteErreur = null;
            s.chargerPochette(url);
        }
        if (change) { s.rafraichir(); }
    }

    public static void pousserEtat(boolean lecture) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        s.pontOk = true;
        if (s.enLecture != lecture) { s.enLecture = lecture; s.rafraichir(); }
    }

    /**
     * Appele environ une fois par seconde. On ne reconstruit PAS la
     * notification : la barre de progression du volet media est lue sur la
     * MediaSession, il suffit donc de republier l'etat de lecture.
     */
    public static void pousserPosition(long duree, long position) {
        final KeepAliveService s = instance;
        if (s == null) { return; }
        s.pontOk = true;
        if (duree == s.dureeMs && position == s.positionMs) { return; }
        boolean dureeChange = (duree > 0) != (s.dureeMs > 0);
        s.dureeMs = duree;
        s.positionMs = position;
        if (dureeChange) {
            s.rafraichir();          // apparition / disparition de la barre
        } else {
            s.majSessionSeule();     // simple avance de l'aiguille
        }
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
            /**
             * Glissement du doigt sur la barre. On deplace l'aiguille tout de
             * suite pour que le geste reponde a l'instant, puis on demande le
             * saut au lecteur : le battement d'une seconde confirmera.
             */
            @Override public void onSeekTo(long pos) {
                if (pos >= 0 && (dureeMs <= 0 || pos <= dureeMs)) {
                    positionMs = pos;
                    majEtat();
                }
                MainActivity.appelerJs("seekto", (int) (pos / 1000));
            }
        });

        // La session doit avoir des metadonnees ET un etat AVANT d'etre
        // rattachee a la notification, sinon SystemUI ne l'affiche nulle part.
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

    /** Republie l'etat de lecture seul (pas de notification reconstruite). */
    private void majSessionSeule() {
        ui.post(new Runnable() {
            @Override public void run() {
                try { majEtat(); } catch (Throwable ignored) { }
            }
        });
    }

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
            String artistePlein = artiste.length() > 0 ? artiste : "Sonora";

            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, titre)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artistePlein)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, artistePlein)
                    // Certaines surfaces (ecran verrouille, Android Auto,
                    // surcouches constructeur) lisent les champs DISPLAY_*
                    // plutot que TITLE/ARTIST : on remplit les deux jeux.
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, titre)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artistePlein)
                    // -1 = duree inconnue : Android masque la barre au lieu
                    // d'en afficher une bloquee sur 0:00.
                    .putLong(MediaMetadata.METADATA_KEY_DURATION,
                            dureeMs > 0 ? dureeMs : -1L);

            String info = infoSecondaire();
            if (info != null) {
                m.putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, info);
            }
            if (pochetteUrl != null && pochetteUrl.startsWith("http")) {
                m.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, pochetteUrl);
                m.putString(MediaMetadata.METADATA_KEY_ART_URI, pochetteUrl);
            }
            if (pochette != null) {
                m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, pochette);
                m.putBitmap(MediaMetadata.METADATA_KEY_ART, pochette);
                m.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, pochette);
            }
            session.setMetadata(m.build());
        } catch (Throwable ignored) { }
        majEtat();
    }

    private void majEtat() {
        if (session == null) { return; }
        try {
            long dispo = PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_FAST_FORWARD
                    | PlaybackState.ACTION_REWIND
                    | PlaybackState.ACTION_SEEK_TO;

            // La vitesse 1.0 en lecture permet a Android d'extrapoler la
            // position entre deux envois : l'aiguille avance en continu et
            // les deux compteurs aux extremites de la barre suivent.
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

    private String plateforme() {
        if ("yt".equals(moteur)) { return "YouTube"; }
        if ("sc".equals(moteur)) { return "SoundCloud"; }
        return null;
    }

    /** L'album quand il apprend quelque chose, sinon la plateforme. */
    private String infoSecondaire() {
        if (album != null && album.length() > 0
                && !album.equalsIgnoreCase("Sonora")
                && !album.equalsIgnoreCase(titre)
                && !album.equalsIgnoreCase(artiste)) {
            return album;
        }
        return plateforme();
    }

    /** Ligne artiste, enrichie de l'album ou de la plateforme si utile. */
    private String ligneArtiste() {
        if (!aDesMeta) {
            return pontOk ? "Pont connecte - en attente d'une lecture"
                          : "En attente du lecteur...";
        }
        String a = artiste.length() > 0 ? artiste : "Sonora";
        String info = infoSecondaire();
        if (info != null && !info.equalsIgnoreCase(a)) {
            return a + " \u2022 " + info;
        }
        return a;
    }

    /**
     * Le petit texte du haut ne sert plus de journal de bord : il ne montre
     * quelque chose que lorsqu'un etage a lache, pour ne pas manger la place
     * d'une information utile quand tout va bien.
     */
    private String panne() {
        if (source != null && source.startsWith("?")) { return "source " + source; }
        if (pochetteErreur != null) { return "pochette " + pochetteErreur; }
        if ("rien".equals(dernierChemin) || "pas de vue".equals(dernierChemin)) {
            return "bouton sans effet";
        }
        return null;
    }

    private Notification.Builder base() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CANAL);
        } else {
            b = new Notification.Builder(this);
        }
        String p = panne();
        if (p != null) { b.setSubText(p); }
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
                .setContentText(ligneArtiste());

        if (pochette != null) { b.setLargeIcon(pochette); }

        // Les 3 boutons de base sont TOUJOURS presents : si la page n'a pas
        // declare le gestionnaire, l'appel JS passe par les primitives du site.
        b.addAction(action(android.R.drawable.ic_media_previous, "Precedent", ACT_PREV, 1));
        int idxPlay = 1;
        b.addAction(action(
                enLecture ? android.R.drawable.ic_media_pause
                          : android.R.drawable.ic_media_play,
                enLecture ? "Pause" : "Lecture", ACT_PLAYPAUSE, 3));
        b.addAction(action(android.R.drawable.ic_media_next, "Suivant", ACT_NEXT, 5));

        if (actions.contains("seekbackward")) {
            b.addAction(action(android.R.drawable.ic_media_rew, "-10 s", ACT_BACK, 2));
        }
        if (actions.contains("seekforward")) {
            b.addAction(action(android.R.drawable.ic_media_ff, "+10 s", ACT_FWD, 4));
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
                        ? ligneArtiste()
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

    // -----------------------------------------------------------------
    //  Pochette
    // -----------------------------------------------------------------

    private void chargerPochette(final String url) {
        new Thread(new Runnable() {
            @Override public void run() {
                String erreur = null;
                Bitmap bmp = null;
                try {
                    byte[] octets = url.startsWith("data:")
                            ? decoderDataUrl(url)
                            : telecharger(url);
                    if (octets == null || octets.length == 0) {
                        erreur = "vide";
                    } else {
                        bmp = decoderEtReduire(octets);
                        if (bmp == null) { erreur = "illisible"; }
                    }
                } catch (Throwable t) {
                    erreur = etiquette(t);
                }
                if (!url.equals(pochetteUrl)) { return; }   // piste deja changee
                if (bmp != null) {
                    pochette = bmp;
                    pochetteErreur = null;
                } else {
                    pochetteErreur = erreur;
                }
                rafraichir();
            }
        }).start();
    }

    private static String etiquette(Throwable t) {
        String m = t.getMessage();
        if (m != null && m.length() > 0 && m.length() < 40) { return m; }
        return t.getClass().getSimpleName();
    }

    /** Certains hebergeurs refusent une requete sans navigateur declare. */
    private byte[] telecharger(String url) throws Exception {
        HttpURLConnection co = null;
        try {
            co = (HttpURLConnection) new URL(url).openConnection();
            co.setConnectTimeout(8000);
            co.setReadTimeout(8000);
            co.setInstanceFollowRedirects(true);
            co.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SonoraAPK");
            co.setRequestProperty("Accept", "image/*,*/*");
            int code = co.getResponseCode();
            if (code >= 400) { throw new Exception(String.valueOf(code)); }
            InputStream in = co.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] tampon = new byte[16384];
            int lu, total = 0;
            while ((lu = in.read(tampon)) > 0) {
                total += lu;
                if (total > POIDS_MAX) { in.close(); throw new Exception("trop lourde"); }
                out.write(tampon, 0, lu);
            }
            in.close();
            return out.toByteArray();
        } finally {
            if (co != null) { co.disconnect(); }
        }
    }

    private static byte[] decoderDataUrl(String url) {
        int v = url.indexOf(',');
        if (v < 0) { return null; }
        String charge = url.substring(v + 1);
        if (url.substring(0, v).contains("base64")) {
            return Base64.decode(charge, Base64.DEFAULT);
        }
        return charge.getBytes();
    }

    /**
     * Deux passes : la premiere ne lit que les dimensions, la seconde decode
     * deja reduit. On ne charge donc jamais une image de 1280x720 en memoire
     * pour la retrecir ensuite.
     */
    private static Bitmap decoderEtReduire(byte[] octets) {
        BitmapFactory.Options mesure = new BitmapFactory.Options();
        mesure.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(octets, 0, octets.length, mesure);

        int cote = Math.max(mesure.outWidth, mesure.outHeight);
        int pas = 1;
        while (cote > 0 && cote / pas > POCHETTE_MAX * 2) { pas *= 2; }

        BitmapFactory.Options lecture = new BitmapFactory.Options();
        lecture.inSampleSize = pas;
        lecture.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap brut = BitmapFactory.decodeByteArray(octets, 0, octets.length, lecture);
        if (brut == null) { return null; }

        int grand = Math.max(brut.getWidth(), brut.getHeight());
        if (grand <= POCHETTE_MAX) { return brut; }

        float k = (float) POCHETTE_MAX / (float) grand;
        int l = Math.max(1, Math.round(brut.getWidth() * k));
        int h = Math.max(1, Math.round(brut.getHeight() * k));
        Bitmap reduit = Bitmap.createScaledBitmap(brut, l, h, true);
        if (reduit != brut) { brut.recycle(); }
        return reduit;
    }
}
