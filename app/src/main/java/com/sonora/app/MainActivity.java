package com.sonora.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class MainActivity extends Activity {

    /**
     * Seule ligne a changer si l'URL du site bouge (DiagnosticActivity la lit
     * aussi). Attention : les playlists, les titres aimes et les points sont
     * gardes PAR ADRESSE dans la WebView. Changer d'adresse, c'est repartir
     * d'une bibliotheque vide pour ceux qui n'ont pas de compte Sonora Cloud.
     */
    static final String SITE_URL = "https://sonora-sandy.vercel.app";

    /** Le fond de l'application, partout : fenetre, WebView et voile. */
    private static final int FOND = 0xFF0B0B0F;

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
     */
    private static final String GREFFON =
        "(function(){var N=window.SonoraNative;if(!N)return;if(window.__snb)return;window.__snb=1;var ms="
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
          + "sig=t+\"|\"+ar+\"|\"+al+\"|\"+u;if(sig!==mMeta){mMeta=sig;N.onMeta(t,ar,al,u)}}catch(e){}var e2=etat()"
          + ";var tp=temps();var av=tp[1]-avantP;if(!e2&&avantP>=0&&av>0.3&&av<3){e2=true;srcE=\"mvt\"}avantP=t"
          + "p[1];var qd=Math.round(tp[0]*1000),qp=Math.round(tp[1]*1000);if(qd!==mD||qp!==mP){mD=qd;mP=qp;tr"
          + "y{N.onPosition(qd,qp)}catch(e){}}if(e2!==mEtat){mEtat=e2;try{N.onState(e2)}catch(e){}}try{var la"
          + "=Object.keys(H).join(\",\");if(la!==mAct){mAct=la;N.onActions(la)}}catch(e){}try{var mo=\"\";if(type"
          + "of engine!==\"undefined\"&&engine)mo=\"\"+engine;if(mo!==mMo){mMo=mo;N.onMoteur(mo)}}catch(e){}var s"
          + "=srcE+\"/\"+srcP+\"/\"+srcM;if(s!==mSrc){mSrc=s;try{N.onSource(s)}catch(e){}}}try{N.onPont()}catch(e"
          + "){}battement();setInterval(battement,1000);})();";

    /**
     * Trois chemins, essayes dans l'ordre. Le premier qui aboutit gagne et
     * renvoie son nom, ce qui sert aussi de diagnostic dans la notification.
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

    private final Handler differe = new Handler(Looper.getMainLooper());

    private BackgroundWebView webView;
    private FrameLayout root;
    private View voile;
    private boolean voileRetire = false;
    /** Entre onStart et onStop : l'ecran montre l'application. */
    private boolean visible = false;
    /** La page est morte pendant l'absence : on la recharge au retour. */
    private boolean rechargerAuRetour = false;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

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
    }

    /**
     * Le service s'arrete tout seul apres un moment sans lecture et sans
     * ecran (voir KeepAliveService). Des que la page annonce qu'elle joue, il
     * faut donc pouvoir le rallumer : c'est ce que fait cette methode, appelee
     * par le pont.
     */
    public static void assurerService() {
        final Context c = sContexte;
        if (c == null || KeepAliveService.vivant()) { return; }
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
     * MISE EN VEILLE. Appelee par le service quand rien ne joue et que l'ecran
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

    private void injecter(final WebView vue) {
        try { vue.evaluateJavascript(GREFFON, null); } catch (Throwable ignored) { }
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
     *   . un voile de la meme couleur, avec le nom de l'application, couvre
     *     tout jusqu'au PREMIER RENDU REEL de la page (onPageCommitVisible),
     *     pas seulement jusqu'a la fin du chargement.
     */
    private View construireVoile() {
        FrameLayout v = new FrameLayout(this);
        v.setBackgroundColor(FOND);
        v.setClickable(true);          // rien ne passe a travers pendant l'attente

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        TextView nom = new TextView(this);
        nom.setText("SONORA");
        nom.setTextColor(Color.WHITE);
        nom.setTextSize(28);
        nom.setLetterSpacing(0.18f);
        nom.setGravity(Gravity.CENTER);

        View trait = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1DB954);
        g.setCornerRadius(3f);
        trait.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(3));
        lp.topMargin = dp(14);
        trait.setLayoutParams(lp);

        col.addView(nom);
        col.addView(trait);

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.gravity = Gravity.CENTER;
        v.addView(col, cl);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

        root = new FrameLayout(this);
        root.setBackgroundColor(FOND);
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

        Intent i = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }

        webView.loadUrl(SITE_URL);
    }

    /**
     * Fabrique et regle la WebView. Sorti de onCreate pour pouvoir en
     * refaire une si Android tue celle-ci (voir onRenderProcessGone).
     * Toujours posee en DESSOUS du voile et du plein ecran video.
     */
    private void creerVue() {
        webView = new BackgroundWebView(this);
        sWeb = new WeakReference<WebView>(webView);
        webView.setBackgroundColor(FOND);          // sans ca : rectangle blanc
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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                super.onPageStarted(view, url, f);
                injecter(view);
                poserFond(!visible);
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
    }

    /** Le telephone est-il en mode sombre ? Lu dans la configuration Android. */
    private boolean modeSombre() {
        try {
            int f = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return f == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return true;
        }
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
     * L'utilisateur bascule clair/sombre pendant que l'app est ouverte.
     * Le manifeste declare uiMode dans configChanges : l'activite n'est pas
     * recreee, c'est donc ici, et nulle part ailleurs, qu'on l'apprend.
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

    /** Confie une adresse au navigateur du telephone. */
    private void ouvrirDehors(String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) { }
    }

    /**
     * L'ECRAN, PAS LE FOCUS. onPause arrive aussi quand un volet ou une
     * fenetre partagee passe devant : l'application est encore a l'ecran. Le
     * passage en arriere-plan, c'est onStop ; le retour, onStart.
     *
     * On n'appelle deliberement NI webView.onPause() NI pauseTimers() ici :
     * la lecture en arriere-plan doit continuer. C'est le service qui decidera
     * de la mise en veille, et seulement apres un vrai moment sans lecture
     * (voir KeepAliveService.majSieste).
     */
    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        reveiller();                       // les minuteurs repartent
        KeepAliveService.pousserPremierPlan(true);
        assurerService();                  // il a pu s'arreter pendant l'absence
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
         * puis on la passe au navigateur du telephone. Selon la version de
         * WebView, l'adresse arrive par shouldOverrideUrlLoading OU par
         * onPageStarted : on ecoute les deux, et on n'ouvre qu'une fois.
         */
        @Override
        public boolean onCreateWindow(WebView vue, boolean estDialogue,
                                      boolean gesteUtilisateur, Message message) {
            final WebView relais = new WebView(MainActivity.this);
            relais.setWebViewClient(new WebViewClient() {
                private boolean fait = false;

                private void attraper(final WebView v, String url) {
                    if (fait || url == null || url.length() == 0
                            || url.startsWith("about:")) { return; }
                    fait = true;
                    ouvrirDehors(url);
                    // Jamais detruire une WebView depuis son propre rappel.
                    v.post(new Runnable() {
                        @Override public void run() {
                            try { v.stopLoading(); v.destroy(); } catch (Throwable ignored) { }
                        }
                    });
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    attraper(v, url);
                    return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    attraper(v, r.getUrl() == null ? null : r.getUrl().toString());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                    attraper(v, url);
                }
            });
            try {
                WebView.WebViewTransport t = (WebView.WebViewTransport) message.obj;
                t.setWebView(relais);
                message.sendToTarget();
            } catch (Throwable t) {
                try { relais.destroy(); } catch (Throwable ignored) { }
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
