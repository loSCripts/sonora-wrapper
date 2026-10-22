package com.sonora.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.http.HttpResponseCache;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.util.LruCache;
import android.webkit.WebSettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    /** La notification d'une pause, balayee : on s'arrete pour de bon. */
    public static final String ACT_FERMER    = "com.sonora.app.FERMER";

    /**
     * SIESTE. Sans lecture et sans ecran depuis ce delai, les minuteurs de la
     * page s'arretent (c'est leur battement qui repondait de la ligne
     * « consommation en arriere-plan » alors que rien ne jouait) et les
     * verrous sont rendus.
     *
     * v2.5 : la sieste ne LIBERE PLUS la MediaSession. Avant, le service
     * s'arretait : deux minutes apres une pause, le bouton lecture du casque
     * Bluetooth et l'ecran verrouille ne relancaient plus rien. Maintenant la
     * session reste ; la notification aussi, mais elle quitte le premier plan
     * et peut etre balayee (ce qui, la, arrete tout).
     */
    private static final long DELAI_SIESTE = 120000L;

    /**
     * L'aiguille de la notification avance toute seule (vitesse 1.0 dans
     * PlaybackState) : la republier chaque seconde, comme avant la v2.2,
     * c'etait un aller-retour avec le systeme par seconde, pendant toute
     * l'ecoute, pour dire ce qu'il savait deja. On ne republie plus que si la
     * vraie position s'ecarte de ce qu'il a calcule (saut, mise en tampon).
     */
    private static final long DERIVE_MAX_MS = 2000L;

    /**
     * VERROUS DE LECTURE. Ecran eteint, Android coupe le Wi-Fi en economie
     * et laisse s'endormir le processeur entre deux paquets : c'est ce qui
     * faisait hacher ou s'arreter la musique apres quelques minutes de poche.
     * Les lecteurs de musique gardent donc les deux eveilles PENDANT la
     * lecture, et seulement pendant. Le verrou processeur a une duree de vie
     * limitee, renouvelee par le battement de la page : si la page se tait
     * (plantage), il tombe tout seul au lieu de vider la batterie.
     */
    private static final long VERROU_DUREE_MS = 3 * 60 * 1000L;
    private static final long VERROU_RENOUVELLEMENT_MS = 60 * 1000L;

    /** Sans nouvelles de la page depuis ce delai, on cesse de croire qu'elle joue. */
    private static final long SILENCE_MAX_MS = 90 * 1000L;

    // -----------------------------------------------------------------
    //  CE QUE LA PAGE A DIT
    //  v2.5 : le service ne nait plus qu'a la premiere lecture, alors que la
    //  page parle des l'ouverture. Ce qu'elle dit est donc garde ici, hors
    //  de l'instance : la notification est juste des sa naissance, sans
    //  attendre le battement suivant. Ecrit par le pont (fil JavaBridge), lu
    //  sur le fil principal.
    // -----------------------------------------------------------------

    private static volatile String titre = "Sonora";
    private static volatile String artiste = "";
    private static volatile String album = "";
    private static volatile String moteur = "";
    private static volatile String pochetteUrl = "";
    private static volatile Bitmap pochette;
    private static volatile int couleur = 0;
    private static volatile boolean enLecture = false;
    private static volatile long dureeMs = 0;
    private static volatile long positionMs = 0;
    private static volatile Set<String> actions = Collections.emptySet();
    private static volatile long dernierSigne = 0;
    private static volatile boolean auPremierPlan = true;

    // Pour l'ecran Diagnostic : la notification ne sert plus de journal de
    // bord (v2.5), ces informations de developpeur sont allees la-bas.
    private static volatile String source = "";
    private static volatile String dernierChemin = "";
    private static volatile long pontVuA = 0;

    private static volatile KeepAliveService instance;

    /** Minuteurs qui valent avec ou sans service : sieste et silence. */
    private static final Handler principal = new Handler(Looper.getMainLooper());

    private static final Runnable dormir = new Runnable() {
        @Override public void run() {
            if (enLecture || auPremierPlan) { return; }   // la situation a change
            MainActivity.mettreEnVeille();                // minuteurs JS a l'arret
            KeepAliveService s = instance;
            if (s != null) {
                s.lacherVerrous();
                s.quitterPremierPlan();
            }
        }
    };

    /** Filet : une page qui ne donne plus signe de vie ne joue plus. */
    private static final Runnable surveillance = new Runnable() {
        @Override public void run() {
            if (!enLecture) { return; }
            if (SystemClock.elapsedRealtime() - dernierSigne > SILENCE_MAX_MS) {
                enLecture = false;
                etatChange();
                return;
            }
            principal.postDelayed(this, SILENCE_MAX_MS);
        }
    };

    // -----------------------------------------------------------------
    //  Pochettes : une seule a la fois, les recentes gardees en memoire
    // -----------------------------------------------------------------

    /**
     * Un seul fil pour toutes les pochettes. Avant : un fil par adresse, et
     * en zappant vite, dix telechargements en parallele dont un seul servait.
     */
    private static final ExecutorService filPochettes = Executors.newSingleThreadExecutor();
    private static Future<?> pochetteEnCours;
    /** 8 pochettes de 512 Ko au plus : de quoi revenir en arriere sans reseau. */
    private static final LruCache<String, Bitmap> pochettesRecentes = new LruCache<String, Bitmap>(8);
    /** L'identite de la WebView, pas un agent maison qu'un hebergeur refuse. */
    private static volatile String agentWeb = "Mozilla/5.0 (Linux; Android)";

    /**
     * Appele par MainActivity au demarrage, sur le fil principal. Le cache
     * disque des pochettes s'installe sur le fil des pochettes : pas de
     * lecture de fichier a l'ouverture de l'application.
     */
    static void preparer(final Context app) {
        try { agentWeb = WebSettings.getDefaultUserAgent(app); } catch (Throwable ignored) { }
        filPochettes.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (HttpResponseCache.getInstalled() == null) {
                        HttpResponseCache.install(new File(app.getCacheDir(), "pochettes"),
                                8L * 1024 * 1024);
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    private MediaSession session;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Le service est-il au premier plan (notification fixe) ? */
    private boolean enAvant = false;

    /** Ce que le systeme a recu en dernier : de quoi recalculer son aiguille. */
    private long publiePosition = 0;
    private long publieA = 0;
    private float publieVitesse = 0f;

    private PowerManager.WakeLock verrouCpu;
    private WifiManager.WifiLock verrouWifi;
    private volatile long verrouPrisA = 0;
    private boolean ecouteCasque = false;

    /**
     * Ecouteurs debranches ou Bluetooth coupe : Android previent juste avant
     * de basculer le son sur le haut-parleur. Tous les lecteurs mettent en
     * pause a ce moment-la, sinon la musique part a pleine voix dans la piece.
     */
    private final BroadcastReceiver casqueParti = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i != null && AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())
                    && enLecture) {
                MainActivity.appelerJs("pause", 0);
            }
        }
    };

    /** Le service tourne-t-il ? Lu par MainActivity avant de le rallumer. */
    public static boolean vivant() { return instance != null; }

    /** La page a-t-elle annonce une lecture ? (MainActivity.onStart) */
    public static boolean lectureEnCours() { return enLecture; }

    /** Pose par MainActivity a chaque onStart / onStop. */
    public static void pousserPremierPlan(boolean visible) {
        auPremierPlan = visible;
        majSieste();
    }

    /** Arme (ou desarme) la sieste ; appelable de n'importe quel fil. */
    static void majSieste() {
        principal.removeCallbacks(dormir);
        if (!enLecture && !auPremierPlan) {
            principal.postDelayed(dormir, DELAI_SIESTE);
        }
    }

    /** Lecture <-> pause. Sur le fil principal. */
    private static void etatChange() {
        KeepAliveService s = instance;
        if (s != null) {
            s.publier();
            s.majVerrous();
            if (enLecture) { s.revenirAuPremierPlan(); }
        }
        majSieste();   // la lecture reprend : plus de sieste ; elle s'arrete : on l'arme
        principal.removeCallbacks(surveillance);
        if (enLecture) { principal.postDelayed(surveillance, SILENCE_MAX_MS); }
    }

    private static void rafraichirSiVivant() {
        KeepAliveService s = instance;
        if (s != null) { s.rafraichir(); }
    }

    /** Remonte quel chemin a effectivement pilote le lecteur (diagnostic). */
    public static void pousserChemin(String c) {
        dernierChemin = c == null ? "" : c;
    }

    /** D'ou viennent l'etat et la position cote page (diagnostic). */
    public static void pousserSource(String src) {
        source = src == null ? "" : src;
    }

    public static void pousserPont() {
        pontVuA = SystemClock.elapsedRealtime();
    }

    /** Moteur de lecture du site : sert a nommer la plateforme. */
    public static void pousserMoteur(String m) {
        String n = m == null ? "" : m;
        if (!n.equals(moteur)) { moteur = n; rafraichirSiVivant(); }
    }

    public static void pousserMeta(String t, String a, String al, String url) {
        String nt = (t != null && t.length() > 0) ? t : titre;
        String na = a == null ? "" : a;
        String nal = al == null ? "" : al;
        boolean change = !nt.equals(titre) || !na.equals(artiste) || !nal.equals(album);

        titre = nt;
        artiste = na;
        album = nal;

        String propre = pochettePropre(url);
        if (propre != null && !propre.equals(pochetteUrl)) {
            pochetteUrl = propre;
            chargerPochette(propre);
        }
        if (change) { rafraichirSiVivant(); }
    }

    public static void pousserEtat(boolean lecture) {
        dernierSigne = SystemClock.elapsedRealtime();
        if (enLecture == lecture) { return; }
        enLecture = lecture;
        principal.post(new Runnable() {
            @Override public void run() { etatChange(); }
        });
    }

    /**
     * Appele a chaque battement de la page. On ne reconstruit PAS la
     * notification : la barre de progression du volet media est lue sur la
     * MediaSession. Et on ne republie l'etat que si l'aiguille calculee par
     * le systeme s'est ecartee de la vraie (voir DERIVE_MAX_MS).
     */
    public static void pousserPosition(long duree, long position) {
        dernierSigne = SystemClock.elapsedRealtime();
        final KeepAliveService s = instance;
        if (s != null && enLecture
                && dernierSigne - s.verrouPrisA > VERROU_RENOUVELLEMENT_MS) {
            s.ui.post(new Runnable() {
                @Override public void run() { s.majVerrous(); }
            });
        }
        if (duree == dureeMs && position == positionMs) { return; }
        boolean dureeChange = (duree > 0) != (dureeMs > 0);
        dureeMs = duree;
        positionMs = position;
        if (s == null) { return; }
        if (dureeChange) {
            s.rafraichir();          // apparition / disparition de la barre
        } else if (s.aiguilleDerive(position)) {
            s.majSessionSeule();     // saut ou tampon : on recale l'aiguille
        }
    }

    public static void pousserActions(String csv) {
        Set<String> nouv = new HashSet<String>();
        if (csv != null && csv.length() > 0) {
            String[] parts = csv.split(",");
            for (int i = 0; i < parts.length; i++) { nouv.add(parts[i].trim()); }
        }
        if (!nouv.equals(actions)) {
            actions = Collections.unmodifiableSet(nouv);
            rafraichirSiVivant();
        }
    }

    /** Quelques lignes pour l'ecran Diagnostic : ce que le pont a vu. */
    static String resumeDiagnostic() {
        StringBuilder r = new StringBuilder();
        r.append("   Service de lecture : ").append(vivant() ? "actif" : "arrete").append("\n");
        r.append("   En lecture         : ").append(enLecture ? "oui" : "non")
         .append("   moteur : ").append(moteur.length() > 0 ? moteur : "-").append("\n");
        r.append("   Etat lu par        : ").append(source.length() > 0 ? source : "-").append("\n");
        r.append("   Dernier bouton     : ").append(dernierChemin.length() > 0 ? dernierChemin : "-").append("\n");
        r.append("   Pont               : ");
        if (pontVuA == 0) {
            r.append("jamais vu (page ouverte ?)");
        } else {
            r.append("dernier signe il y a ")
             .append((SystemClock.elapsedRealtime() - pontVuA) / 1000).append(" s");
        }
        return r.append("\n").toString();
    }

    private boolean aiguilleDerive(long position) {
        long ecoule = SystemClock.elapsedRealtime() - publieA;
        long attendu = publiePosition + (long) (ecoule * publieVitesse);
        return Math.abs(position - attendu) > DERIVE_MAX_MS;
    }

    /** Verrous pris pendant la lecture, rendus des qu'elle s'arrete. */
    private void majVerrous() {
        if (!enLecture) {
            lacherVerrous();
            return;
        }
        try {
            if (verrouCpu == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                verrouCpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonora:lecture");
                verrouCpu.setReferenceCounted(false);
            }
            verrouCpu.acquire(VERROU_DUREE_MS);   // repousse aussi l'echeance
            verrouPrisA = SystemClock.elapsedRealtime();
        } catch (Throwable ignored) { }
        // Le verrou Wi-Fi est sans effet ecran eteint depuis Android 14, et
        // il coute de la batterie : on ne le prend que la ou il sert.
        if (Build.VERSION.SDK_INT <= 33) {
            try {
                if (verrouWifi == null) {
                    WifiManager wm = (WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    verrouWifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            "sonora:lecture");
                    verrouWifi.setReferenceCounted(false);
                }
                if (!verrouWifi.isHeld()) { verrouWifi.acquire(); }
            } catch (Throwable ignored) { }
        }
        if (!ecouteCasque) {
            try {
                registerReceiver(casqueParti,
                        new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
                ecouteCasque = true;
            } catch (Throwable ignored) { }
        }
    }

    private void lacherVerrous() {
        try { if (verrouCpu != null && verrouCpu.isHeld()) { verrouCpu.release(); } }
        catch (Throwable ignored) { }
        try { if (verrouWifi != null && verrouWifi.isHeld()) { verrouWifi.release(); } }
        catch (Throwable ignored) { }
        if (ecouteCasque) {
            try { unregisterReceiver(casqueParti); } catch (Throwable ignored) { }
            ecouteCasque = false;
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

        // Ne nait qu'a une lecture annoncee ecran allume (MainActivity) : le
        // premier plan est permis, y compris sur Android 12 et plus.
        enAvant = true;
        startForeground(NOTIF_ID, construire());
        majVerrous();
        majSieste();
    }

    /**
     * START_NOT_STICKY, plus START_STICKY. Avec STICKY, quand Android tuait
     * l'application, il relancait CE service tout seul, sans activite et sans
     * page : une notification qui ne pilotait plus rien, et qui ne
     * s'endormait jamais. Sans page, le service s'arrete.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!MainActivity.vueVivante()) {
            sEteindre();
            return START_NOT_STICKY;
        }
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
            } else if (ACT_FERMER.equals(a) && !enLecture) {
                sEteindre();
            }
        }
        return START_NOT_STICKY;
    }

    /**
     * Sieste : la notification reste (ecran verrouille, casque), mais elle
     * n'est plus fixe et le systeme peut reprendre la memoire s'il en a
     * besoin. stopForeground(0) la garde affichee.
     */
    private void quitterPremierPlan() {
        if (!enAvant) { return; }
        enAvant = false;
        try { stopForeground(0); } catch (Throwable ignored) { }
        publier();
    }

    /**
     * La lecture reprend apres une sieste. Depuis l'arriere-plan, Android 12
     * peut refuser le premier plan (sauf bouton de casque ou de
     * notification) : on garde alors une notification ordinaire, la musique,
     * elle, continue.
     */
    private void revenirAuPremierPlan() {
        if (enAvant) { return; }
        enAvant = true;
        try {
            startForeground(NOTIF_ID, construire());
        } catch (Throwable refus) {
            enAvant = false;
            publier();
        }
    }

    private void sEteindre() {
        lacherVerrous();
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) { }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        instance = null;
        ui.removeCallbacksAndMessages(null);
        lacherVerrous();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        // Rien ne doit survivre au service : une notification fixe orpheline
        // ne se balaie pas.
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        } catch (Throwable ignored) { }
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
            @Override public void run() { publier(); }
        });
    }

    /**
     * Session et notification a jour. Sur le fil principal, et jamais pour un
     * service deja detruit : c'est ce notify tardif (pochette arrivee apres
     * coup) qui laissait une notification fantome, fixe et impossible a
     * balayer.
     */
    private void publier() {
        if (instance != this) { return; }
        try {
            majSession();
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, construire());
        } catch (Throwable ignored) { }
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
            String u = pochetteUrl;
            if (u.startsWith("https://")) {
                m.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, u);
                m.putString(MediaMetadata.METADATA_KEY_ART_URI, u);
            }
            Bitmap p = pochette;
            if (p != null) {
                m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, p);
                m.putBitmap(MediaMetadata.METADATA_KEY_ART, p);
                m.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, p);
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
            boolean joue = enLecture;
            long position = positionMs;
            float vitesse = joue ? 1.0f : 0.0f;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(dispo)
                    .setState(joue ? PlaybackState.STATE_PLAYING
                                   : PlaybackState.STATE_PAUSED,
                              position, vitesse)
                    .build());
            publiePosition = position;
            publieA = SystemClock.elapsedRealtime();
            publieVitesse = vitesse;
        } catch (Throwable ignored) { }
    }

    /** Toujours renvoyer une notification, quoi qu'il arrive. */
    private Notification construire() {
        try {
            return construireMedia();
        } catch (Throwable t) {
            return construireSimple();
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
        String al = album;
        if (al.length() > 0
                && !al.equalsIgnoreCase("Sonora")
                && !al.equalsIgnoreCase(titre)
                && !al.equalsIgnoreCase(artiste)) {
            return al;
        }
        return plateforme();
    }

    /** Ligne artiste, enrichie de l'album ou de la plateforme si utile. */
    private String ligneArtiste() {
        String a = artiste.length() > 0 ? artiste : "Sonora";
        String info = infoSecondaire();
        if (info != null && !info.equalsIgnoreCase(a)) {
            return a + " • " + info;
        }
        return a;
    }

    /**
     * Fixe tant que le service est au premier plan ; apres la sieste, on peut
     * la balayer, et la balayer arrete tout (ACT_FERMER).
     */
    private Notification.Builder base() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CANAL);
        } else {
            b = new Notification.Builder(this);
        }
        int c = couleur;
        if (c != 0) {
            b.setColor(c);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { b.setColorized(true); }
        }
        Intent fermer = new Intent(this, KeepAliveService.class).setAction(ACT_FERMER);
        b.setSmallIcon(R.drawable.ic_notif)
         .setContentIntent(ouvrirApp())
         .setDeleteIntent(PendingIntent.getService(this, 6, fermer,
                 PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
         .setOngoing(enAvant)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setShowWhen(false);
        return b;
    }

    private Notification construireMedia() {
        Notification.Builder b = base()
                .setContentTitle(titre)
                .setContentText(ligneArtiste());

        Bitmap p = pochette;
        if (p != null) { b.setLargeIcon(p); }

        // Les 3 boutons de base sont TOUJOURS presents : si la page n'a pas
        // declare le gestionnaire, l'appel JS passe par les primitives du site.
        b.addAction(action(android.R.drawable.ic_media_previous, "Precedent", ACT_PREV, 1));
        int idxPlay = 1;
        b.addAction(action(
                enLecture ? android.R.drawable.ic_media_pause
                          : android.R.drawable.ic_media_play,
                enLecture ? "Pause" : "Lecture", ACT_PLAYPAUSE, 3));
        b.addAction(action(android.R.drawable.ic_media_next, "Suivant", ACT_NEXT, 5));

        Set<String> a = actions;
        if (a.contains("seekbackward")) {
            b.addAction(action(android.R.drawable.ic_media_rew, "-10 s", ACT_BACK, 2));
        }
        if (a.contains("seekforward")) {
            b.addAction(action(android.R.drawable.ic_media_ff, "+10 s", ACT_FWD, 4));
        }

        Notification.MediaStyle style = new Notification.MediaStyle();
        if (session != null) { style.setMediaSession(session.getSessionToken()); }
        style.setShowActionsInCompactView(idxPlay - 1, idxPlay, idxPlay + 1);
        b.setStyle(style);

        return b.build();
    }

    /** Repli sans MediaStyle : la v1.1 s'affichait, celle-ci s'affichera. */
    private Notification construireSimple() {
        Notification.Builder b = base()
                .setContentTitle(titre)
                .setContentText(ligneArtiste());
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

    /**
     * Android refuse le http en clair (UnknownServiceException, qui
     * s'affichait jusque dans la notification) : les favicons de radio en
     * http passent en https, ce que la plupart des hebergeurs servent aussi.
     * Ce qui n'est ni http(s) ni une image en data: est ignore.
     */
    private static String pochettePropre(String url) {
        if (url == null || url.length() == 0) { return null; }
        if (url.startsWith("https://") || url.startsWith("data:image/")) { return url; }
        if (url.startsWith("http://")) { return "https://" + url.substring(7); }
        return null;
    }

    private static synchronized void chargerPochette(final String url) {
        Bitmap deja = pochettesRecentes.get(url);
        if (deja != null) {
            poserPochette(url, deja);
            return;
        }
        // La piste a change : la pochette d'avant ne sert plus a rien.
        if (pochetteEnCours != null) { pochetteEnCours.cancel(true); }
        pochetteEnCours = filPochettes.submit(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                try {
                    byte[] octets = url.startsWith("data:")
                            ? decoderDataUrl(url)
                            : telecharger(url);
                    if (octets != null && octets.length > 0) {
                        bmp = decoderEtReduire(octets);
                    }
                } catch (Throwable ignored) {
                    // Une pochette qui manque n'est pas une erreur a montrer.
                }
                if (bmp != null) { pochettesRecentes.put(url, bmp); }
                poserPochette(url, bmp);
            }
        });
    }

    private static void poserPochette(String url, Bitmap bmp) {
        if (!url.equals(pochetteUrl)) { return; }   // piste deja changee
        pochette = bmp;
        couleur = bmp != null ? couleurDominante(bmp) : 0;
        rafraichirSiVivant();
    }

    private static byte[] telecharger(String url) throws Exception {
        HttpURLConnection co = null;
        try {
            co = (HttpURLConnection) new URL(url).openConnection();
            co.setConnectTimeout(8000);
            co.setReadTimeout(8000);
            co.setInstanceFollowRedirects(true);
            co.setRequestProperty("User-Agent", agentWeb);
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

    /**
     * Teinte dominante de la pochette, pour colorer la notification. Pas de
     * bibliotheque : on reduit l'image a 24x24, on range les pixels dans des
     * cases grossieres, et on retient la case la plus peuplee en favorisant
     * les couleurs franches. Une moyenne pure donnerait toujours du gris.
     */
    private static int couleurDominante(Bitmap source) {
        try {
            final int COTE = 24;
            Bitmap p = Bitmap.createScaledBitmap(source, COTE, COTE, true);
            int[] px = new int[COTE * COTE];
            p.getPixels(px, 0, COTE, 0, 0, COTE, COTE);
            if (p != source) { p.recycle(); }

            int[] nb = new int[4096];
            long[] sr = new long[4096];
            long[] sg = new long[4096];
            long[] sb = new long[4096];
            long[] ss = new long[4096];

            for (int i = 0; i < px.length; i++) {
                int r = (px[i] >> 16) & 0xFF, g = (px[i] >> 8) & 0xFF, b = px[i] & 0xFF;
                int haut = Math.max(r, Math.max(g, b));
                int bas = Math.min(r, Math.min(g, b));
                if (haut < 40) { continue; }              // presque noir
                if (bas > 215) { continue; }              // presque blanc
                int c = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                nb[c]++; sr[c] += r; sg[c] += g; sb[c] += b; ss[c] += (haut - bas);
            }

            int meilleur = -1;
            double score = 0;
            for (int c = 0; c < 4096; c++) {
                if (nb[c] == 0) { continue; }
                double sat = (double) ss[c] / (double) nb[c] / 255.0;
                double s2 = nb[c] * (1.0 + 3.0 * sat);
                if (s2 > score) { score = s2; meilleur = c; }
            }
            if (meilleur < 0) { return 0; }

            int r = (int) (sr[meilleur] / nb[meilleur]);
            int g = (int) (sg[meilleur] / nb[meilleur]);
            int b = (int) (sb[meilleur] / nb[meilleur]);

            // Le texte de la notification est clair : on assombrit ce qui
            // serait trop lumineux pour rester lisible.
            double lum = 0.299 * r + 0.587 * g + 0.114 * b;
            if (lum > 140) {
                double k = 140.0 / lum;
                r = (int) (r * k); g = (int) (g * k); b = (int) (b * k);
            }
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } catch (Throwable t) {
            return 0;
        }
    }
}
