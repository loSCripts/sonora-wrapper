package com.sonora.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    /**
     * Seule ligne a changer si l'URL du site bouge (DiagnosticActivity la lit
     * aussi). Attention : les playlists, les titres aimes et les points sont
     * gardes PAR ADRESSE dans la WebView. Changer d'adresse, c'est repartir
     * d'une bibliotheque vide pour ceux qui n'ont pas de compte Sonora Cloud.
     *
     * v2.2 : passage de sonora-sandy a sonora-musique, le site soumis a la
     * regie (codes Adsterra, sans Monetag ni pub X). Le moment est choisi :
     * la v2.2 change de cle de signature, il faut desinstaller l'ancienne
     * version de toute facon, et la bibliotheque locale part avec elle.
     */
    static final String SITE_URL = "https://sonora-musique.vercel.app";

    /** Seul hote a recevoir le jeton du pont et les fichiers locaux. */
    private static final String HOTE = Uri.parse(SITE_URL).getHost();

    /**
     * Adresses de la MEME ORIGINE que le site, servies par l'application et
     * non par Vercel : la page peut lire un fichier telecharge par
     * fetch(local) pour en garder une copie hors ligne (v2.5).
     */
    private static final String PREFIXE_FICHIER = "/__sonora_fichier/";

    static final String MESSAGE_STOCKAGE =
            "Sonora n'a pas le droit d'écrire dans Téléchargements";

    private static final int CODE_STOCKAGE = 7;

    /**
     * Une fenetre ouverte par la page qui n'a toujours recu aucune adresse
     * apres ce delai ne menait nulle part : sa WebView relais est detruite
     * au lieu de rester en memoire jusqu'a la fermeture.
     */
    private static final long DELAI_RELAIS_MS = 10000;

    /** Theme choisi DANS Sonora (JsBridge.theme), garde entre deux lancements. */
    private static final String PREFS = "sonora";
    private static final String PREF_THEME = "theme";

    /**
     * Le fond de l'application, partout : fenetre, WebView et voile. C'est
     * EXACTEMENT celui de la page (--bg de app.html, sombre et clair) : quand
     * le voile se leve, aucune couleur ne change, on ne voit donc rien.
     * (v2.4 : avant, un gris bleute #0B0B0F, meme en mode clair.)
     */
    private static final int FOND_SOMBRE = 0xFF000000;
    private static final int FOND_CLAIR = 0xFFECECED;

    private int fond() {
        return modeSombre() ? FOND_SOMBRE : FOND_CLAIR;
    }

    /**
     * Au-dela, on retire le voile meme si la page n'a rien signale. Une page
     * qui ne se peint jamais ne doit pas laisser un voile eternel.
     */
    private static final long VOILE_MAX_MS = 6000;

    /**
     * Greffon injecte dans la page. Il n'ecrase rien et ne modifie pas le site.
     *
     * CHANGEMENT v1.5 : l'etat de lecture et la position ne sont PLUS lus dans
     * navigator.mediaSession. Ils sont pris directement a la source du site :
     *
     *   titre / pochette  ->  cur(), la piste courante du site ; a defaut
     *                         l'objet passe a session(), capture au vol
     *   position / duree  ->  len() et pos(), exactement ce qui alimente la
     *                         barre de progression affichee dans l'app
     *   lecture / pause   ->  YTP.getPlayerState() pour YouTube,
     *                         l'element <audio> pour Audius,
     *                         sinon l'etiquette du bouton #playBtn
     *
     * CHANGEMENT v2.0 : le battement se met au ralenti quand l'application est
     * en arriere-plan ET que rien ne joue (window.__snbFond, pose par
     * onStart/onStop ci-dessous). Un battement par seconde qui ne sert a
     * rien, c'est du processeur reveille pour rien pendant des heures.
     *
     * CHANGEMENT v2.2 : en arriere-plan, meme PENDANT la lecture, un tour sur
     * deux suffit (la notification extrapole l'aiguille toute seule), et la
     * position ne traverse le pont que lorsqu'elle a change.
     *
     * CHANGEMENT v2.5 : chaque appel passe en premier le jeton du cadre
     * principal (J(), lu a chaque appel : il change a chaque page). Sans lui,
     * le pont refuse ; c'est ce qui le ferme aux cadres de publicite.
     */
    private static final String GREFFON =
        "(function(){var N=window.SonoraNative;if(!N)return;if(window.__snb)return;window.__snb=1;"
          + "function J(){return window.__snbJeton||\"\"}var ms="
          + "navigator.mediaSession||null;var H={};if(ms&&ms.setActionHandler){var o=ms.setActionHandler.bind"
          + "(ms);ms.setActionHandler=function(a,f){if(f){H[a]=f}else{delete H[a]}try{o(a,f)}catch(e){}};}win"
          + "dow.__snbCall=function(a,d){var f=H[a];if(!f)return false;try{f({action:a,seekOffset:d,seekTime:"
          + "d})}catch(e){}return true};window.__snbHas=function(a){return !!H[a]};var pd=0,pp=0;if(ms&&ms.se"
          + "tPositionState){var sp=ms.setPositionState.bind(ms);ms.setPositionState=function(s){try{sp(s)}ca"
          + "tch(e){}try{if(s){pd=s.duration||0;pp=s.position||0}}catch(e){}};}var T=null;try{if(typeof windo"
          + "w.session===\"function\"){var oses=window.session;window.session=function(t){try{if(t&&typeof t==="
          + "\"object\")T=t}catch(e){}var r=oses.apply(this,arguments);try{battement()}catch(e){}return r};}}ca"
          + "tch(e){}var srcE=\"?\",srcP=\"?\",srcM=\"?\";function elAudio(){try{return document.getElementById(\"au"
          + "dio\")}catch(e){return null}}function piste(){var c=null;try{if(typeof cur===\"function\")c=cur()}c"
          + "atch(e){}if(c&&typeof c===\"object\"&&(c.title||c.artist||c.art)){srcM=\"cur\";return c}if(T&&(T.tit"
          + "le||T.artist||T.art)){srcM=\"session\";return T}return null;}function etat(){try{if(typeof engine!"
          + "==\"undefined\"){if(engine===\"yt\"&&typeof YTP!==\"undefined\"&&YTP&&YTP.getPlayerState){srcE=\"yt\";re"
          + "turn YTP.getPlayerState()===1}if(engine===\"audio\"){var a=elAudio();if(a){srcE=\"audio\";return !a."
          + "paused}}}}catch(e){}try{var b=document.getElementById(\"playBtn\");if(b){var l=(b.getAttribute(\"ar"
          + "ia-label\")||\"\").toLowerCase();if(l){srcE=\"dom\";return l.indexOf(\"pause\")===0}}}catch(e){}try{if("
          + "ms){srcE=\"ms\";return ms.playbackState===\"playing\"}}catch(e){}srcE=\"?\";return false;}function tem"
          + "ps(){var d=0,p=0;try{if(typeof len===\"function\")d=len()||0}catch(e){}try{if(typeof pos===\"functi"
          + "on\")p=pos()||0}catch(e){}if(isFinite(d)&&d>0){srcP=\"site\";return [d,isFinite(p)?p:0]}if(pd>0){sr"
          + "cP=\"ms\";return [pd,pp]}try{var a=elAudio();if(a&&isFinite(a.duration)&&a.duration>0){srcP=\"audio"
          + "\";return [a.duration,a.currentTime||0]}}catch(e){}srcP=\"?\";return [0,0];}var mMeta=\"\",mEtat=null"
          + ",mAct=\"\",mSrc=\"\",mMo=\"\",n=0,avantP=-1,mD=-1,mP=-1;function battement(){n++;if(window.__snbFond&&"
          + "n%(mEtat===false?5:2)!==0)return;if(n%10===0){mMeta=\"\";mEtat=null;mAct=\"\";mSrc=\"\";mMo=\"\"}try{var"
          + " t=\"\",ar=\"\",al=\"\",u=\"\";var pc=piste();if(pc){t=pc.title||\"\";ar=pc.artist||\"\";al=pc.album||\"\";u=p"
          + "c.art||\"\"}else{var v=ms?ms.metadata:null;if(v){srcM=\"ms\";t=v.title||\"\";ar=v.artist||\"\";al=v.albu"
          + "m||\"\";if(v.artwork&&v.artwork.length)u=v.artwork[v.artwork.length-1].src||\"\"}else srcM=\"?\";}var "
          + "sig=t+\"|\"+ar+\"|\"+al+\"|\"+u;if(sig!==mMeta){mMeta=sig;N.onMeta(J(),t,ar,al,u)}}catch(e){}var e2=etat()"
          + ";var tp=temps();var av=tp[1]-avantP;if(!e2&&avantP>=0&&av>0.3&&av<3){e2=true;srcE=\"mvt\"}avantP=t"
          + "p[1];var qd=Math.round(tp[0]*1000),qp=Math.round(tp[1]*1000);if(qd!==mD||qp!==mP){mD=qd;mP=qp;tr"
          + "y{N.onPosition(J(),qd,qp)}catch(e){}}if(e2!==mEtat){mEtat=e2;try{N.onState(J(),e2)}catch(e){}}try{var la"
          + "=Object.keys(H).join(\",\");if(la!==mAct){mAct=la;N.onActions(J(),la)}}catch(e){}try{var mo=\"\";if(type"
          + "of engine!==\"undefined\"&&engine)mo=\"\"+engine;if(mo!==mMo){mMo=mo;N.onMoteur(J(),mo)}}catch(e){}var s"
          + "=srcE+\"/\"+srcP+\"/\"+srcM;if(s!==mSrc){mSrc=s;try{N.onSource(J(),s)}catch(e){}}}try{N.onPont(J())}catch(e"
          + "){}battement();setInterval(battement,1000);})();";

    /**
     * Trois chemins, essayes dans l'ordre. Le premier qui aboutit gagne et
     * renvoie son nom, ce qui sert aussi de diagnostic (ecran Diagnostic).
     *
     *  ms  : le gestionnaire mediaSession capture par le greffon
     *  fn  : les primitives du site (resume/halt/next/prev/seekAbs), qui sont
     *        ce que les gestionnaires mediaSession appellent eux-memes
     *  dom : clic sur les vrais boutons #playBtn / #next / #prev
     */
    private static final String JS_ACTION =
        "(function(a,d){"
      + "try{if(window.__snbHas&&window.__snbHas(a)){window.__snbCall(a,d);return 'ms'}}catch(e){}"
      + "try{"
      + "if(a==='play'){if(typeof resume==='function'){resume();"
      + "try{setIcons(true)}catch(e){}try{majEtatSession('playing')}catch(e){}return 'fn'}}"
      + "else if(a==='pause'){if(typeof halt==='function'){halt();"
      + "try{setIcons(false)}catch(e){}try{majEtatSession('paused')}catch(e){}return 'fn'}}"
      + "else if(a==='nexttrack'){if(typeof next==='function'){next(false);return 'fn'}}"
      + "else if(a==='previoustrack'){if(typeof prev==='function'){prev();return 'fn'}}"
      + "else if(a==='seekto'){if(typeof seekAbs==='function'){seekAbs(d);return 'fn'}}"
      + "else if(a==='seekforward'){if(typeof seekAbs==='function'){"
      + "seekAbs((typeof pos==='function'?pos():0)+d);return 'fn'}}"
      + "else if(a==='seekbackward'){if(typeof seekAbs==='function'){"
      + "seekAbs(Math.max(0,(typeof pos==='function'?pos():0)-d));return 'fn'}}"
      + "}catch(e){}"
      + "try{var m={play:'#playBtn',pause:'#playBtn',nexttrack:'#next',previoustrack:'#prev'};"
      + "var s=m[a];if(s){var b=document.querySelector(s);if(b){b.click();return 'dom'}}}catch(e){}"
      + "return 'rien';"
      + "})('%A%',%D%)";

    /**
     * Theme clair/sombre (revenu de la v2.1, perdu en v2.0). La page demande
     * le vrai reglage du telephone au pont (SonoraNative.modeSysteme) ; ce
     * greffon couvre en plus les anciennes versions du site, qui ne savent
     * interroger que matchMedia : on lui fait dire la meme chose que le pont.
     */
    private static final String GREFFON_THEME =
        "(function(){var N=window.SonoraNative;if(!N||!N.modeSysteme)return;"
      + "var s='';try{s=N.modeSysteme()}catch(e){}if(s!=='light'&&s!=='dark')return;"
      + "if(!window.__snTheme){window.__snTheme=1;var mm=window.matchMedia&&window.matchMedia.bind(window);"
      + "if(mm){window.matchMedia=function(q){var r=mm(q);try{if(/prefers-color-scheme/i.test(q)){"
      + "var v=/light/i.test(q)?(s==='light'):(/dark/i.test(q)?(s==='dark'):r.matches);"
      + "Object.defineProperty(r,'matches',{get:function(){return v},configurable:true})}}catch(e){}"
      + "return r}}}"
      + "try{if(typeof window.appliquerTheme==='function'){window.appliquerTheme();return}}catch(e){}"
      + "try{var p=null;try{p=JSON.parse(localStorage.getItem('sonora.theme'))}catch(e){}"
      + "if(p!=='light'&&p!=='dark')document.documentElement.setAttribute('data-theme',s)}catch(e){}"
      + "})();";

    private static WeakReference<WebView> sWeb = new WeakReference<>(null);
    private static Context sContexte;
    /**
     * Entre onStart et onStop : l'ecran montre l'application. Statique : le
     * pont et le service le consultent avant de faire naitre la notification.
     */
    private static volatile boolean visible = false;

    private final Handler differe = new Handler(Looper.getMainLooper());
    private final SecureRandom hasard = new SecureRandom();

    private BackgroundWebView webView;
    private FrameLayout root;
    private View voile;
    private boolean voileRetire = false;
    /** La page est morte pendant l'absence : on la recharge au retour. */
    private boolean rechargerAuRetour = false;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    /**
     * Jeton du document affiche dans le cadre principal, s'il est du site ;
     * null sinon. Renouvele a chaque onPageStarted, lu par le pont (fil
     * JavaBridge).
     */
    private volatile String jetonPage;
    /** Le chargement en cours du cadre principal a echoue. */
    private boolean erreurChargement = false;
    private String adresseEchouee;

    /** Fichiers telecharges servis sous PREFIXE_FICHIER, oublies a la fermeture. */
    private final Map<String, FichierLocal> fichiersLocaux =
            new ConcurrentHashMap<String, FichierLocal>();

    private static final class FichierLocal {
        final long telechargement;
        /** v2.6 : fichier telecharge par l'application elle-meme (sinon null). */
        final File fichier;
        final String mime;

        FichierLocal(long telechargement, File fichier, String mime) {
            this.telechargement = telechargement;
            this.fichier = fichier;
            this.mime = mime;
        }
    }

    private SharedPreferences preferences;
    private ExtracteurYoutube extracteur;
    private Telechargements fichiers;
    private EcranHorsLigne horsLigne;
    /** Demande d'autorisation de stockage en cours (Android 7 a 9). */
    private CountDownLatch reponseStockage;

    /** Appele par le service quand on touche un bouton de la notification. */
    public static void appelerJs(String action, int secondes) {
        final WebView w = sWeb.get();
        if (w == null) {
            KeepAliveService.pousserChemin("pas de vue");
            return;
        }
        final String js = JS_ACTION
                .replace("%A%", action)
                .replace("%D%", String.valueOf(secondes));
        w.post(new Runnable() {
            @Override
            public void run() {
                // Apres la sieste, les minuteurs de la page dorment : le
                // bouton du casque ou de l'ecran verrouille doit d'abord les
                // reveiller, sinon le lecteur ne repart pas.
                try { w.resumeTimers(); } catch (Throwable ignored) { }
                try {
                    w.evaluateJavascript(js, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            if (v != null) {
                                v = v.replace("\"", "").trim();
                            }
                            KeepAliveService.pousserChemin(v);
                        }
                    });
                } catch (Throwable t) {
                    KeepAliveService.pousserChemin("erreur");
                }
            }
        });
        // Si rien ne repart (une pause, par exemple), la sieste se rearme.
        KeepAliveService.majSieste();
    }

    /**
     * Le service, et sa notification, naissent a la PREMIERE lecture (v2.5),
     * plus a l'ouverture : une notification « en attente » quand on n'ecoute
     * rien n'avait rien a faire la. Et jamais depuis l'arriere-plan : Android
     * 12 l'interdit (l'exception etait avalee et la lecture continuait sans
     * commandes). Ecran ailleurs, on attend son retour : onStart s'en charge.
     */
    public static void assurerService() {
        final Context c = sContexte;
        if (c == null || !visible || KeepAliveService.vivant()) { return; }
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignored) { }
    }

    /** Le service n'a de raison d'exister que s'il y a une page a piloter. */
    public static boolean vueVivante() {
        return sWeb.get() != null;
    }

    /**
     * MISE EN VEILLE. Appelee par la sieste quand rien ne joue et que l'ecran
     * est ailleurs depuis assez longtemps.
     *
     * On appelle UNIQUEMENT pauseTimers(), et surtout PAS onPause() :
     * onPause() suspend aussi le son de la WebView, ce qui casserait la
     * lecture en arriere-plan. pauseTimers() arrete les minuteurs JavaScript,
     * donc le battement d'une seconde du greffon, et rien d'autre. Comme on ne
     * la declenche que lorsque rien ne joue, il n'y a aucun son a proteger.
     */
    public static void mettreEnVeille() {
        final WebView w = sWeb.get();
        if (w == null) { return; }
        w.post(new Runnable() {
            @Override public void run() {
                try { w.pauseTimers(); } catch (Throwable ignored) { }
            }
        });
    }

    private static void reveiller() {
        final WebView w = sWeb.get();
        if (w == null) { return; }
        w.post(new Runnable() {
            @Override public void run() {
                try { w.resumeTimers(); } catch (Throwable ignored) { }
            }
        });
    }

    /**
     * Le greffon consulte ce drapeau pour savoir s'il peut ralentir. Ni
     * document.visibilityState ni onWindowVisibilityChanged ne peuvent le dire
     * ici : BackgroundWebView masque volontairement ce signal a Chromium,
     * c'est ce qui garde le son vivant en arriere-plan.
     *
     * v2.2 : on le dit AUSSI au site (window.__sonoraFond). Sans ca, la page
     * se croyait a l'ecran en permanence : ses minuteurs d'affichage, ses
     * animations et ses sauvegardes « quand on quitte » ne s'arretaient ou ne
     * partaient jamais dans l'APK, alors qu'ils le font dans un navigateur.
     * Le son n'est pas concerne : Chromium, lui, voit toujours une page
     * visible.
     */
    private void poserFond(boolean fond) {
        final WebView w = webView;
        if (w == null) { return; }
        String v = fond ? "true" : "false";
        try {
            w.evaluateJavascript("window.__snbFond=" + v + ";"
                    + "try{if(typeof window.__sonoraFond==='function')window.__sonoraFond(" + v + ")}"
                    + "catch(e){}", null);
        } catch (Throwable ignored) { }
    }

    // -----------------------------------------------------------------
    //  Le jeton du pont (v2.5)
    // -----------------------------------------------------------------

    private static boolean pageDuSite(String url) {
        if (url == null) { return false; }
        Uri u = Uri.parse(url);
        return "https".equals(u.getScheme()) && HOTE.equals(u.getHost());
    }

    /** 128 bits de SecureRandom, en 32 caracteres hexadecimaux. */
    private String nouveauJeton() {
        byte[] o = new byte[16];
        hasard.nextBytes(o);
        StringBuilder s = new StringBuilder(32);
        for (byte b : o) {
            s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return s.toString();
    }

    /** Le jeton presente est-il celui du document affiche ? (fil JavaBridge) */
    boolean jetonValide(String jeton) {
        String j = jetonPage;
        return j != null && jeton != null
                && MessageDigest.isEqual(j.getBytes(StandardCharsets.US_ASCII),
                                         jeton.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Jeton, greffon et theme. Rien pour une page qui n'est pas le site. Le
     * jeton n'est ecrit que si le document qui recoit le script est bien de
     * l'origine du site : evaluateJavascript lance au debut d'un chargement
     * peut encore tomber dans le document PRECEDENT.
     */
    private void injecter(final WebView vue) {
        String j = jetonPage;
        if (j == null) { return; }
        String jeton = "if(location.origin==='" + SITE_URL + "')window.__snbJeton='" + j + "';";
        try { vue.evaluateJavascript(jeton + GREFFON, null); } catch (Throwable ignored) { }
        try { vue.evaluateJavascript(GREFFON_THEME, null); } catch (Throwable ignored) { }
    }

    private void injecterPlusTard(final WebView vue, long delai) {
        differe.postDelayed(new Runnable() {
            @Override public void run() { injecter(vue); }
        }, delai);
    }

    /**
     * LE VOILE, CONTRE LE FLASH BLANC.
     *
     * Une WebView est blanche tant qu'elle n'a rien peint : au demarrage on
     * voyait donc un rectangle blanc plein ecran, puis la page. Trois mesures,
     * chacune necessaire :
     *   . le fond de la fenetre est deja sombre (styles.xml) ;
     *   . la WebView elle-meme recoit ce fond (setBackgroundColor), sinon elle
     *     peint du blanc par-dessus ;
     *   . un voile de la meme couleur couvre tout jusqu'au PREMIER RENDU
     *     REEL de la page (onPageCommitVisible), pas seulement jusqu'a la fin
     *     du chargement.
     *
     * v2.4 : le voile est VIDE. Il portait « SONORA » et un trait vert ; le
     * plus souvent il ne restait affiche qu'un instant, juste le temps de voir
     * passer un logo a moitie dessine (« SO »). Un aplat de la couleur de la
     * page ne se remarque pas : l'application a l'air de s'ouvrir directement.
     */
    private View construireVoile() {
        View v = new View(this);
        v.setBackgroundColor(fond());
        v.setClickable(true);          // rien ne passe a travers pendant l'attente
        return v;
    }

    private void retirerVoile() {
        if (voileRetire || voile == null) { return; }
        voileRetire = true;
        voile.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
            @Override public void run() {
                try { root.removeView(voile); } catch (Throwable ignored) { }
                voile = null;
            }
        }).start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sContexte = getApplicationContext();
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Le fond du theme (styles.xml) suit le telephone ; celui-ci suit le
        // theme choisi dans Sonora, s'il y en a un (v2.5).
        getWindow().setBackgroundDrawable(new ColorDrawable(fond()));

        root = new FrameLayout(this);
        root.setBackgroundColor(fond());
        setContentView(root);

        creerVue();

        voile = construireVoile();
        root.addView(voile, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        differe.postDelayed(new Runnable() {
            @Override public void run() { retirerVoile(); }
        }, VOILE_MAX_MS);

        habillerSysteme();

        KeepAliveService.preparer(sContexte);
        extracteur = new ExtracteurYoutube(this);
        fichiers = new Telechargements(this);
        horsLigne = new EcranHorsLigne(this, root, new Runnable() {
            @Override public void run() { recharger(); }
        });

        // Plus de service ici (v2.5) : il nait a la premiere lecture.
        webView.loadUrl(adresseDepart(intentNeuf(savedInstanceState)));
    }

    /**
     * L'intent de lancement, seulement s'il est NEUF. Apres la mort du
     * processus, Android recree l'activite avec le meme intent
     * (savedInstanceState non nul), et une relance depuis les applications
     * recentes le rejoue aussi (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) : la
     * reprise des gouts du navigateur etait alors reproposee a chaque fois.
     */
    private Intent intentNeuf(Bundle etat) {
        Intent i = getIntent();
        if (etat != null || i == null
                || (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return null;
        }
        return i;
    }

    /**
     * Code de reprise porte par le lien « Ouvrir l'application » du site
     * (v2.3) : sonora://ouvrir?d=... emporte les gouts, les j'aime et les
     * playlists du navigateur, l'APK ayant son propre stockage, vide.
     * N'importe quelle page peut fabriquer ce lien : on n'en garde que le
     * code (lettres, chiffres, - et _), jamais une adresse a charger, et le
     * site demande a la personne avant de reprendre quoi que ce soit.
     */
    static String codeReprise(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction())) return null;
        Uri u = i.getData();
        if (u == null || !"sonora".equals(u.getScheme()) || !"ouvrir".equals(u.getHost())) return null;
        String d;
        try { d = u.getQueryParameter("d"); } catch (Throwable t) { return null; }
        if (d == null || d.length() > 200000 || !d.matches("[zj][A-Za-z0-9_-]+")) return null;
        return d;
    }

    private static String adresseDepart(Intent i) {
        String d = codeReprise(i);
        return d == null ? SITE_URL : SITE_URL + "/app.html#/importer/" + d;
    }

    /**
     * Lien « Ouvrir l'application » touche alors que Sonora tourne deja
     * (singleTask). Si l'application est deja chargee, on change seulement
     * l'ancre : pas de rechargement, la musique en cours continue.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String d = codeReprise(intent);
        if (d == null || webView == null) return;
        String ici = webView.getUrl();
        if (ici != null && ici.startsWith(SITE_URL + "/app.html")) {
            webView.evaluateJavascript("location.hash='#/importer/" + d + "'", null);
        } else {
            webView.loadUrl(SITE_URL + "/app.html#/importer/" + d);
        }
    }

    /** Nouvel essai apres une erreur : l'adresse qui a echoue, sinon l'accueil. */
    private void recharger() {
        if (webView == null) { return; }
        webView.loadUrl(adresseEchouee != null ? adresseEchouee : SITE_URL);
    }

    private void montrerHorsLigne(WebResourceRequest r, boolean serveur) {
        erreurChargement = true;
        adresseEchouee = r.getUrl().toString();
        retirerVoile();
        horsLigne.montrer(serveur, fond(), modeSombre());
    }

    /**
     * Fabrique et regle la WebView. Sorti de onCreate pour pouvoir en
     * refaire une si Android tue celle-ci (voir onRenderProcessGone).
     * Toujours posee en DESSOUS du voile et du plein ecran video.
     */
    private void creerVue() {
        webView = new BackgroundWebView(this);
        sWeb = new WeakReference<WebView>(webView);
        webView.setBackgroundColor(fond());        // sans ca : rectangle blanc
        root.addView(webView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);

        // Sans ca, window.open() et les liens target="_blank" ne font
        // ABSOLUMENT RIEN dans une WebView : elle n'a pas d'onglets, et le
        // navigateur abandonne la demande en silence. C'est par la que passent
        // TOUS les liens de publicite : sans ce reglage, un clic sur une pub
        // dans l'APK ne rapportait rien. Voir onCreateWindow plus bas.
        // (Present en v2.1, perdu en v2.0 : remis.)
        s.setSupportMultipleWindows(true);

        /* L'identite du navigateur reste celle d'origine, SANS « SonoraAPK ».
           Un agent utilisateur inhabituel est exactement ce que les pare-feux
           d'hebergeur et les regies examinent pour trier les robots : une
           visite qui ressemble a un robot est une visite que la regie ne paie
           pas. Le site reconnait l'APK a la presence de window.SonoraNative,
           ce qui est de toute facon plus sur qu'une chaine de caracteres. */

        if (Build.VERSION.SDK_INT >= 29) {
            /* La WebView sait assombrir une page toute seule. On lui retire ce
               droit : Sonora possede deja ses deux themes et sait lequel poser
               grace au pont. Deux mecanismes qui decident de la meme chose, ce
               sont deux occasions de se contredire. */
            try { s.setForceDark(WebSettings.FORCE_DARK_OFF); } catch (Throwable ignored) { }
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new JsBridge(this), "SonoraNative");

        // Liens de telechargement ordinaires (v2.5). Sans ecouteur, la WebView
        // les ignorait. Les blob: et data: n'existent que dans la page : elle
        // les envoie elle-meme par fichierDebut / fichierMorceau / fichierFin.
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String agent, String disposition,
                                        String mime, long taille) {
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    telechargerLien(url, agent, disposition, mime);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                super.onPageStarted(view, url, f);
                // Nouveau document, nouveau jeton : celui de la page d'avant
                // ne vaut plus rien, et une page qui n'est pas le site n'en
                // recoit aucun.
                jetonPage = pageDuSite(url) ? nouveauJeton() : null;
                erreurChargement = false;
                injecter(view);
                poserFond(!visible);
            }

            /**
             * Ce qu'une WebView ne sait pas ouvrir (intent:, market:, mailto:,
             * tel:...) part vers l'application concernee, et seulement apres
             * un vrai toucher. Le reste se charge normalement.
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                String schema = r.getUrl().getScheme();
                if (schema == null) { return false; }
                switch (schema) {
                    case "http": case "https": case "about": case "data": case "blob": case "javascript":
                        return false;
                    default:
                        if (r.hasGesture()) { ouvrirDehors(r.getUrl().toString()); }
                        return true;
                }
            }

            /**
             * Le PREMIER pixel reellement peint par la page. C'est le seul
             * moment ou retirer le voile ne laisse rien voir de blanc.
             * onPageFinished arrive trop tard (tout le JS a tourne) et
             * onPageStarted trop tot.
             */
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                if (!erreurChargement) { horsLigne.cacher(); }
                // Le site s'ouvre sur index.html qui redirige aussitot vers
                // app.html : on ne leve le voile qu'une fois arrive.
                if (url != null && url.contains("app.html")) { retirerVoile(); }
                else { differe.postDelayed(new Runnable() {
                    @Override public void run() { retirerVoile(); }
                }, 1400); }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injecter(view);
                poserFond(!visible);
                // Le lecteur s'installe apres le chargement : deux rappels
                // suffisent a rattraper les demarrages lents.
                injecterPlusTard(view, 1500);
                injecterPlusTard(view, 5000);
            }

            /** Pas de reseau, nom introuvable, delai depasse... (cadre principal). */
            @Override
            public void onReceivedError(WebView view, WebResourceRequest r, WebResourceError e) {
                super.onReceivedError(view, r, e);
                if (view == webView && r.isForMainFrame()) { montrerHorsLigne(r, false); }
            }

            /** Le site repond, mais en erreur serveur : meme ecran, autre texte. */
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest r,
                                            WebResourceResponse reponse) {
                super.onReceivedHttpError(view, r, reponse);
                if (view == webView && r.isForMainFrame() && reponse.getStatusCode() >= 500) {
                    montrerHorsLigne(r, true);
                }
            }

            /** Fil reseau de la WebView : sert /__sonora_fichier/<jeton>. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest r) {
                return intercepterFichier(r);
            }

            /**
             * Android a tue le moteur de la page (memoire, mise a jour de
             * WebView, plantage). Sans cette methode, il tue TOUTE
             * l'application avec lui : c'est le « Sonora s'est arrete » qui
             * arrive en arriere-plan. On jette la vue morte et on en refait
             * une. Si l'ecran est ailleurs, on attend le retour pour
             * recharger : rien ne jouait plus de toute facon, inutile de
             * consommer pour une page que personne ne regarde.
             */
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != webView) {
                    try { view.destroy(); } catch (Throwable ignored) { }
                    return true;
                }
                hideCustomView();
                root.removeView(view);
                try { view.destroy(); } catch (Throwable ignored) { }
                KeepAliveService.pousserEtat(false);
                creerVue();
                if (visible) {
                    webView.loadUrl(SITE_URL);
                } else {
                    rechargerAuRetour = true;
                }
                return true;
            }
        });

        webView.setWebChromeClient(new FullscreenChromeClient());

        /* Les requetes faites par le service worker du site ne passent PAS
           par le WebViewClient ci-dessus : sans ce relais, un ancien service
           worker encore en place enverrait /__sonora_fichier/ chez Vercel. Le
           site v120 le laisse passer, mais ce filet ne coute rien. */
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest r) {
                    return intercepterFichier(r);
                }
            });
        } catch (Throwable ignored) { }
    }

    /** /__sonora_fichier/<jeton> sur l'hote du site : le fichier ; sinon le reseau. */
    private WebResourceResponse intercepterFichier(WebResourceRequest r) {
        Uri u = r.getUrl();
        String chemin = u == null ? null : u.getPath();
        if (chemin == null || !chemin.startsWith(PREFIXE_FICHIER)
                || !"https".equals(u.getScheme()) || !HOTE.equals(u.getHost())) {
            return null;
        }
        return servirFichier(chemin.substring(PREFIXE_FICHIER.length()));
    }

    // -----------------------------------------------------------------
    //  Fichiers (v2.5)
    // -----------------------------------------------------------------

    /**
     * Rend un telechargement termine lisible par la page, a une adresse de
     * son origine. Le jeton est impossible a deviner, et l'adresse meurt avec
     * l'activite.
     */
    String exposerFichier(long telechargement, String mime) {
        String jeton = nouveauJeton();
        fichiersLocaux.put(jeton, new FichierLocal(telechargement, null, mime));
        return PREFIXE_FICHIER + jeton;
    }

    /** Meme chose pour un fichier telecharge dans l'application (v2.6). */
    String exposerFichierLocal(File fichier, String mime) {
        String jeton = nouveauJeton();
        fichiersLocaux.put(jeton, new FichierLocal(-1, fichier, mime));
        return PREFIXE_FICHIER + jeton;
    }

    private WebResourceResponse servirFichier(String jeton) {
        FichierLocal f = fichiersLocaux.get(jeton);
        if (f != null) {
            try {
                if (f.fichier != null) {
                    Map<String, String> entetes = new HashMap<String, String>();
                    entetes.put("Content-Length", String.valueOf(f.fichier.length()));
                    entetes.put("Cache-Control", "no-store");
                    return new WebResourceResponse(f.mime, null, 200, "OK", entetes,
                            new FileInputStream(f.fichier));
                }
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                ParcelFileDescriptor pfd = dm.openDownloadedFile(f.telechargement);
                Map<String, String> entetes = new HashMap<String, String>();
                long taille = pfd.getStatSize();
                if (taille >= 0) { entetes.put("Content-Length", String.valueOf(taille)); }
                entetes.put("Cache-Control", "no-store");
                return new WebResourceResponse(f.mime, null, 200, "OK", entetes,
                        new ParcelFileDescriptor.AutoCloseInputStream(pfd));
            } catch (Throwable efface) {
                // Supprime depuis l'explorateur de fichiers : simple 404.
            }
        }
        // Jamais transmis a Vercel : un jeton essaye au hasard reste ici.
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    /**
     * Lien de telechargement ordinaire : confie a DownloadManager, vers
     * Telechargements/Sonora. Dans un fil a part : sur Android 7 a 9, il
     * faut peut-etre attendre la reponse a la demande d'autorisation.
     */
    private void telechargerLien(final String url, final String agent,
                                 final String disposition, final String mime) {
        new Thread(new Runnable() {
            @Override public void run() {
                String message;
                if (!attendreStockage()) {
                    message = MESSAGE_STOCKAGE;
                } else {
                    String nom = Telechargements.nomPropre(
                            URLUtil.guessFileName(url, disposition, mime), "telechargement");
                    try {
                        Telechargements.confier(MainActivity.this, url, nom, nom, "Sonora", mime, agent);
                        message = "Téléchargement lancé : Téléchargements/Sonora";
                    } catch (Throwable t) {
                        message = "Le téléchargement n'a pas pu démarrer";
                    }
                }
                final String m = message;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "sonora-lien").start();
    }

    /**
     * Android 7 a 9 : ecrire dans Telechargements demande une autorisation,
     * posee au premier enregistrement et jamais au lancement. Ne bloque pas :
     * la reponse liberera ceux qui l'attendent (attendreStockage).
     */
    void demanderStockage() {
        if (Build.VERSION.SDK_INT >= 29 || stockageAutorise()) { return; }
        ouvrirDemande();
    }

    /**
     * Bloquant, jusqu'a la reponse de la personne : fil du pont ou d'un
     * telechargement, jamais le fil principal.
     */
    boolean attendreStockage() {
        if (Build.VERSION.SDK_INT >= 29 || stockageAutorise()) { return true; }
        try {
            ouvrirDemande().await(2, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return stockageAutorise();
    }

    private boolean stockageAutorise() {
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Une seule question a la fois, quel que soit le nombre de demandeurs. */
    private synchronized CountDownLatch ouvrirDemande() {
        if (reponseStockage == null || reponseStockage.getCount() == 0) {
            reponseStockage = new CountDownLatch(1);
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                            CODE_STOCKAGE);
                }
            });
        }
        return reponseStockage;
    }

    private synchronized void finDeDemande() {
        if (reponseStockage != null) { reponseStockage.countDown(); }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] resultats) {
        super.onRequestPermissionsResult(code, permissions, resultats);
        if (code == CODE_STOCKAGE) { finDeDemande(); }
    }

    ExtracteurYoutube extracteur() { return extracteur; }

    Telechargements fichiers() { return fichiers; }

    /**
     * Rappel de telechargement YouTube vers la page, dans le cadre principal
     * seulement, et seulement si c'est le site : le message porte l'adresse
     * locale du fichier.
     */
    void rappelerPage(JSONObject o) {
        final String js = "if(location.origin==='" + SITE_URL + "'&&window.__sonoraTelechargement)"
                + "window.__sonoraTelechargement(" + o + ")";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                WebView w = webView;
                if (w == null || jetonPage == null) { return; }
                try { w.evaluateJavascript(js, null); } catch (Throwable ignored) { }
            }
        });
    }

    /** navigator.share n'existe pas dans une WebView : le menu Android, ici. */
    void partager(String titre, String texte, String url) {
        String corps = texte == null ? "" : texte.trim();
        if (url != null && url.length() > 0 && !corps.contains(url)) {
            corps = corps.length() > 0 ? corps + "\n" + url : url;
        }
        Intent envoi = new Intent(Intent.ACTION_SEND);
        envoi.setType("text/plain");
        if (titre != null && titre.length() > 0) { envoi.putExtra(Intent.EXTRA_SUBJECT, titre); }
        envoi.putExtra(Intent.EXTRA_TEXT, corps);
        final Intent choix = Intent.createChooser(envoi,
                titre != null && titre.length() > 0 ? titre : "Partager");
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try { startActivity(choix); } catch (Throwable ignored) { }
            }
        });
    }

    // -----------------------------------------------------------------
    //  Theme
    // -----------------------------------------------------------------

    /**
     * Sombre ou clair ? D'abord le theme choisi DANS Sonora (v2.5) : celui
     * qui l'a mis en clair sur un telephone sombre voyait un aplat clair au
     * lancement, puis une page noire, ou l'inverse. A defaut, le telephone.
     */
    private boolean modeSombre() {
        String choisi = preferences == null ? null : preferences.getString(PREF_THEME, null);
        if ("dark".equals(choisi)) { return true; }
        if ("light".equals(choisi)) { return false; }
        try {
            int f = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return f == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * La page annonce son theme. Les barres suivent tout de suite ; fenetre,
     * voile et fond le prendront au prochain lancement (il n'y a rien a
     * voir dessous tant que la page est affichee).
     */
    void choisirTheme(String valeur) {
        preferences.edit().putString(PREF_THEME, valeur).apply();
        runOnUiThread(new Runnable() {
            @Override public void run() { habillerSysteme(); }
        });
    }

    /**
     * Barre d'etat et barre de navigation assorties au mode en cours.
     * Sans ca, un telephone en mode clair affichait une page blanche sous une
     * barre noire, et l'heure devenait illisible des que la barre passait au
     * blanc : le systeme ne sait pas ce que la page a decide de dessiner.
     */
    private void habillerSysteme() {
        boolean sombre = modeSombre();
        try {
            getWindow().setStatusBarColor(sombre ? Color.BLACK : Color.WHITE);
            getWindow().setNavigationBarColor(sombre ? Color.BLACK : Color.WHITE);
        } catch (Throwable ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                View d = getWindow().getDecorView();
                int f = d.getSystemUiVisibility();
                if (sombre) {
                    f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (sombre) {
                        f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                d.setSystemUiVisibility(f);
            } catch (Throwable ignored) { }
        }
    }

    /**
     * Un reglage du telephone change pendant que l'app est ouverte : mode
     * clair/sombre, taille du texte, langue, densite, clavier... Le manifeste
     * les declare tous dans configChanges : l'activite n'est pas recreee (la
     * page, donc la musique, survit), c'est ici qu'on l'apprend. La WebView
     * recoit elle-meme le changement et remet sa mise en page a jour.
     */
    @Override
    public void onConfigurationChanged(Configuration nouvelle) {
        super.onConfigurationChanged(nouvelle);
        habillerSysteme();
        if (webView != null) {
            injecter(webView);
            try {
                webView.evaluateJavascript(
                        "try{if(window.__sonoraThemeMaj)window.__sonoraThemeMaj();}catch(e){}",
                        null);
            } catch (Throwable ignored) { }
        }
    }

    /**
     * Confie une adresse au telephone : le navigateur pour http(s),
     * l'application concernee pour le reste (Play Store, mail...).
     *
     * intent:// (v2.5) : un tel lien peut nommer N'IMPORTE QUEL composant,
     * meme non exporte, d'une application installee, ou de Sonora elle-meme.
     * On en retire composant et selecteur, on remet les drapeaux a zero (pas
     * de droit d'acces accorde au passage), et on exige BROWSABLE : seules
     * les activites faites pour etre ouvertes depuis un lien repondent.
     */
    private void ouvrirDehors(String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        Intent i;
        try {
            if (url.startsWith("intent:")) {
                i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                i.setComponent(null);
                i.setSelector(null);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
        } catch (Throwable mauvais) {
            return;
        }
        String schema = i.getScheme();
        if (getPackageName().equals(i.getPackage())
                || "sonora".equals(schema) || "file".equals(schema) || "content".equals(schema)
                || "javascript".equals(schema) || "data".equals(schema) || "about".equals(schema)) {
            return;
        }
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException absent) {
            // Application absente : la page de secours prevue par le lien.
            String secours = i.getStringExtra("browser_fallback_url");
            if (secours != null && (secours.startsWith("https://") || secours.startsWith("http://"))) {
                ouvrirDehors(secours);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * L'ECRAN, PAS LE FOCUS. onPause arrive aussi quand un volet ou une
     * fenetre partagee passe devant : l'application est encore a l'ecran. Le
     * passage en arriere-plan, c'est onStop ; le retour, onStart.
     *
     * On n'appelle deliberement NI webView.onPause() NI pauseTimers() ici :
     * la lecture en arriere-plan doit continuer. C'est la sieste qui decidera
     * de la mise en veille, et seulement apres un vrai moment sans lecture
     * (voir KeepAliveService.majSieste).
     */
    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        reveiller();                       // les minuteurs repartent
        KeepAliveService.pousserPremierPlan(true);
        // Une lecture annoncee pendant l'absence attendait l'ecran pour
        // faire naitre sa notification.
        if (KeepAliveService.lectureEnCours()) { assurerService(); }
        if (rechargerAuRetour && webView != null) {
            rechargerAuRetour = false;
            webView.loadUrl(SITE_URL);
        }
        poserFond(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) { injecter(webView); }
    }

    @Override
    protected void onStop() {
        visible = false;
        poserFond(true);
        KeepAliveService.pousserPremierPlan(false);
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        differe.removeCallbacksAndMessages(null);
        // Avant d'arreter le service : il ne doit plus croire qu'une page
        // l'attend (vueVivante), meme si la vue tarde a etre ramassee.
        sWeb = new WeakReference<WebView>(null);
        jetonPage = null;
        // La prochaine page repart d'un etat « rien ne joue » : sinon son
        // onStart ferait renaitre une notification de lecture fantome.
        KeepAliveService.pousserEtat(false);
        extracteur.arreter();
        fichiers.oublier();
        fichiersLocaux.clear();
        horsLigne.liberer();
        finDeDemande();
        stopService(new Intent(this, KeepAliveService.class));
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private class FullscreenChromeClient extends WebChromeClient {

        /**
         * La page demande une nouvelle fenetre (target="_blank", window.open).
         *
         * Une WebView n'a pas d'onglets : sans ce relais, la demande est
         * simplement abandonnee et le clic ne produit RIEN. On fabrique une
         * WebView jetable dont le seul role est d'attraper l'adresse au vol,
         * puis on la passe au navigateur du telephone.
         *
         * v2.5 : seulement apres un vrai toucher. Sans cette regle, une
         * publicite pouvait ouvrir Chrome toute seule, sans que personne ait
         * touche l'ecran (setJavaScriptCanOpenWindowsAutomatically est actif).
         */
        @Override
        public boolean onCreateWindow(WebView vue, boolean estDialogue,
                                      boolean gesteUtilisateur, Message message) {
            if (!gesteUtilisateur) { return false; }
            Relais relais = new Relais();
            try {
                WebView.WebViewTransport t = (WebView.WebViewTransport) message.obj;
                t.setWebView(relais.vue);
                message.sendToTarget();
            } catch (Throwable t) {
                relais.run();
                return false;
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            webView.setVisibility(View.GONE);
            root.addView(customView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    }

    /**
     * WebView jetable d'une fenetre demandee par la page. Selon la version de
     * WebView, l'adresse arrive par shouldOverrideUrlLoading OU par
     * onPageStarted : on ecoute les deux, et on n'ouvre qu'une fois. Si rien
     * n'arrive (fenetre vide, pub qui ne mene nulle part), elle est detruite
     * apres DELAI_RELAIS_MS. Tout se passe sur le fil principal.
     */
    private final class Relais extends WebViewClient implements Runnable {
        final WebView vue = new WebView(MainActivity.this);
        private boolean fini = false;

        Relais() {
            vue.setWebViewClient(this);
            differe.postDelayed(this, DELAI_RELAIS_MS);
        }

        /** Delai ecoule sans adresse. */
        @Override public void run() {
            if (fini) { return; }
            fini = true;
            detruire();
        }

        private void attraper(String url) {
            if (fini || url == null || url.length() == 0 || url.startsWith("about:")) { return; }
            fini = true;
            differe.removeCallbacks(this);
            ouvrirDehors(url);
            // Jamais detruire une WebView depuis son propre rappel.
            vue.post(new Runnable() {
                @Override public void run() { detruire(); }
            });
        }

        private void detruire() {
            try { vue.stopLoading(); vue.destroy(); } catch (Throwable ignored) { }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            attraper(r.getUrl() == null ? null : r.getUrl().toString());
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
            attraper(url);
        }
    }

    private void hideCustomView() {
        if (customView == null) {
            return;
        }
        root.removeView(customView);
        customView = null;
        if (webView != null) { webView.setVisibility(View.VISIBLE); }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }
}
