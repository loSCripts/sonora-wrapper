package com.sonora.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class MainActivity extends Activity {

    /** Seule ligne a changer si l'URL du site bouge. */
    private static final String SITE_URL = "https://sonora-sandy.vercel.app";

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
     * onPause/onResume ci-dessous). Un battement par seconde qui ne sert a
     * rien, c'est du processeur reveille pour rien pendant des heures.
     */
    private static final String GREFFON =
        "(function(){var N=window.SonoraNative;if(!N)return;if(window.__snb)return;window.__snb=1;var"
          + " ms=navigator.mediaSession||null;var H={};if(ms&&ms.setActionHandler){var o=ms.setActionHand"
          + "ler.bind(ms);ms.setActionHandler=function(a,f){if(f){H[a]=f}else{delete H[a]}try{o(a,f)}catc"
          + "h(e){}};}window.__snbCall=function(a,d){var f=H[a];if(!f)return false;try{f({action:a,seekOf"
          + "fset:d,seekTime:d})}catch(e){}return true};window.__snbHas=function(a){return !!H[a]};var pd"
          + "=0,pp=0;if(ms&&ms.setPositionState){var sp=ms.setPositionState.bind(ms);ms.setPositionState="
          + "function(s){try{sp(s)}catch(e){}try{if(s){pd=s.duration||0;pp=s.position||0}}catch(e){}};}va"
          + "r T=null;try{if(typeof window.session===\"function\"){var oses=window.session;window.session=f"
          + "unction(t){try{if(t&&typeof t===\"object\")T=t}catch(e){}var r=oses.apply(this,arguments);try{"
          + "battement()}catch(e){}return r};}}catch(e){}var srcE=\"?\",srcP=\"?\",srcM=\"?\";function elAudio("
          + "){try{return document.getElementById(\"audio\")}catch(e){return null}}function piste(){var c=n"
          + "ull;try{if(typeof cur===\"function\")c=cur()}catch(e){}if(c&&typeof c===\"object\"&&(c.title||c."
          + "artist||c.art)){srcM=\"cur\";return c}if(T&&(T.title||T.artist||T.art)){srcM=\"session\";return "
          + "T}return null;}function etat(){try{if(typeof engine!==\"undefined\"){if(engine===\"yt\"&&typeof "
          + "YTP!==\"undefined\"&&YTP&&YTP.getPlayerState){srcE=\"yt\";return YTP.getPlayerState()===1}if(eng"
          + "ine===\"audio\"){var a=elAudio();if(a){srcE=\"audio\";return !a.paused}}}}catch(e){}try{var b=do"
          + "cument.getElementById(\"playBtn\");if(b){var l=(b.getAttribute(\"aria-label\")||\"\").toLowerCase("
          + ");if(l){srcE=\"dom\";return l.indexOf(\"pause\")===0}}}catch(e){}try{if(ms){srcE=\"ms\";return ms."
          + "playbackState===\"playing\"}}catch(e){}srcE=\"?\";return false;}function temps(){var d=0,p=0;try"
          + "{if(typeof len===\"function\")d=len()||0}catch(e){}try{if(typeof pos===\"function\")p=pos()||0}c"
          + "atch(e){}if(isFinite(d)&&d>0){srcP=\"site\";return [d,isFinite(p)?p:0]}if(pd>0){srcP=\"ms\";retu"
          + "rn [pd,pp]}try{var a=elAudio();if(a&&isFinite(a.duration)&&a.duration>0){srcP=\"audio\";return"
          + " [a.duration,a.currentTime||0]}}catch(e){}srcP=\"?\";return [0,0];}var mMeta=\"\",mEtat=null,mAc"
          + "t=\"\",mSrc=\"\",mMo=\"\",n=0,avantP=-1;function battement(){n++;if(window.__snbFond&&mEtat===fals"
          + "e&&n%5!==0)return;if(n%10===0){mMeta=\"\";mEtat=null;mAct=\"\";mSrc=\"\";mMo=\"\"}try{var t=\"\",ar=\"\""
          + ",al=\"\",u=\"\";var pc=piste();if(pc){t=pc.title||\"\";ar=pc.artist||\"\";al=pc.album||\"\";u=pc.art||"
          + "\"\"}else{var v=ms?ms.metadata:null;if(v){srcM=\"ms\";t=v.title||\"\";ar=v.artist||\"\";al=v.album||"
          + "\"\";if(v.artwork&&v.artwork.length)u=v.artwork[v.artwork.length-1].src||\"\"}else srcM=\"?\";}var"
          + " sig=t+\"|\"+ar+\"|\"+al+\"|\"+u;if(sig!==mMeta){mMeta=sig;N.onMeta(t,ar,al,u)}}catch(e){}var e2=et"
          + "at();var tp=temps();var av=tp[1]-avantP;if(!e2&&avantP>=0&&av>0.3&&av<3){e2=true;srcE=\"mvt\"}"
          + "avantP=tp[1];try{N.onPosition(Math.round(tp[0]*1000),Math.round(tp[1]*1000))}catch(e){}if(e2"
          + "!==mEtat){mEtat=e2;try{N.onState(e2)}catch(e){}}try{var la=Object.keys(H).join(\",\");if(la!=="
          + "mAct){mAct=la;N.onActions(la)}}catch(e){}try{var mo=\"\";if(typeof engine!==\"undefined\"&&engin"
          + "e)mo=\"\"+engine;if(mo!==mMo){mMo=mo;N.onMoteur(mo)}}catch(e){}var s=srcE+\"/\"+srcP+\"/\"+srcM;if"
          + "(s!==mSrc){mSrc=s;try{N.onSource(s)}catch(e){}}}try{N.onPont()}catch(e){}battement();setInte"
          + "rval(battement,1000);})();";

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

    private static WeakReference<WebView> sWeb = new WeakReference<>(null);
    private static Context sContexte;

    private final Handler differe = new Handler(Looper.getMainLooper());

    private BackgroundWebView webView;
    private FrameLayout root;
    private View voile;
    private boolean voileRetire = false;
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
     */
    private void poserFond(boolean fond) {
        final WebView w = webView;
        if (w == null) { return; }
        try {
            w.evaluateJavascript("window.__snbFond=" + (fond ? "true" : "false") + ";", null);
        } catch (Throwable ignored) { }
    }

    private void injecter(final WebView vue) {
        try { vue.evaluateJavascript(GREFFON, null); } catch (Throwable ignored) { }
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

        webView = new BackgroundWebView(this);
        sWeb = new WeakReference<WebView>(webView);
        webView.setBackgroundColor(FOND);          // sans ca : rectangle blanc
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        voile = construireVoile();
        root.addView(voile, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        differe.postDelayed(new Runnable() {
            @Override public void run() { retirerVoile(); }
        }, VOILE_MAX_MS);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);

        // Permet au site de se reconnaitre dans l'APK (navInfo/notifMessage)
        s.setUserAgentString(s.getUserAgentString() + " SonoraAPK/2.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new JsBridge(), "SonoraNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                super.onPageStarted(view, url, f);
                injecter(view);
                poserFond(false);
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
                poserFond(false);
                // Le lecteur s'installe apres le chargement : deux rappels
                // suffisent a rattraper les demarrages lents.
                injecterPlusTard(view, 1500);
                injecterPlusTard(view, 5000);
            }
        });

        webView.setWebChromeClient(new FullscreenChromeClient());

        Intent i = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }

        webView.loadUrl(SITE_URL);
    }

    /**
     * On n'appelle deliberement NI webView.onPause() NI pauseTimers() ici :
     * la lecture en arriere-plan doit continuer. C'est le service qui decidera
     * de la mise en veille, et seulement apres un vrai moment sans lecture
     * (voir KeepAliveService.majSieste).
     */
    @Override
    protected void onPause() {
        super.onPause();
        poserFond(true);
        KeepAliveService.pousserPremierPlan(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reveiller();                       // les minuteurs repartent
        KeepAliveService.pousserPremierPlan(true);
        assurerService();                  // il a pu s'arreter pendant l'absence
        if (webView != null) { injecter(webView); }
        poserFond(false);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        differe.removeCallbacksAndMessages(null);
        stopService(new Intent(this, KeepAliveService.class));
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private class FullscreenChromeClient extends WebChromeClient {

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
        webView.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }
}
