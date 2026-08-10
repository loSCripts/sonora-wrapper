package com.sonora.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.lang.ref.WeakReference;

public class MainActivity extends Activity {

    /** Seule ligne a changer si l'URL du site bouge. */
    private static final String SITE_URL = "https://sonora-sandy.vercel.app";

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
     * mediaSession ne sert plus que de repli. Le greffon publie aussi la
     * source reellement utilisee (ex. "yt/site"), affichee en petit dans la
     * notification : si quelque chose ne remonte pas, on voit lequel des
     * etages a lache sans avoir a brancher un cable.
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
          + "t=\"\",mSrc=\"\",mMo=\"\",n=0,avantP=-1;function battement(){n++;if(n%10===0){mMeta=\"\";mEtat=null;"
          + "mAct=\"\";mSrc=\"\";mMo=\"\"}try{var t=\"\",ar=\"\",al=\"\",u=\"\";var pc=piste();if(pc){t=pc.title||\"\";ar"
          + "=pc.artist||\"\";al=pc.album||\"\";u=pc.art||\"\"}else{var v=ms?ms.metadata:null;if(v){srcM=\"ms\";t"
          + "=v.title||\"\";ar=v.artist||\"\";al=v.album||\"\";if(v.artwork&&v.artwork.length)u=v.artwork[v.art"
          + "work.length-1].src||\"\"}else srcM=\"?\";}var sig=t+\"|\"+ar+\"|\"+al+\"|\"+u;if(sig!==mMeta){mMeta=si"
          + "g;N.onMeta(t,ar,al,u)}}catch(e){}var e2=etat();var tp=temps();var av=tp[1]-avantP;if(!e2&&av"
          + "antP>=0&&av>0.3&&av<3){e2=true;srcE=\"mvt\"}avantP=tp[1];try{N.onPosition(Math.round(tp[0]*100"
          + "0),Math.round(tp[1]*1000))}catch(e){}if(e2!==mEtat){mEtat=e2;try{N.onState(e2)}catch(e){}}tr"
          + "y{var la=Object.keys(H).join(\",\");if(la!==mAct){mAct=la;N.onActions(la)}}catch(e){}try{var m"
          + "o=\"\";if(typeof engine!==\"undefined\"&&engine)mo=\"\"+engine;if(mo!==mMo){mMo=mo;N.onMoteur(mo)}"
          + "}catch(e){}var s=srcE+\"/\"+srcP+\"/\"+srcM;if(s!==mSrc){mSrc=s;try{N.onSource(s)}catch(e){}}}tr"
          + "y{N.onPont()}catch(e){}battement();setInterval(battement,1000);})();";


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

    private final Handler differe = new Handler(Looper.getMainLooper());

    private BackgroundWebView webView;
    private FrameLayout root;
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
     * Le greffon se protege lui-meme contre la double installation ET refuse
     * de s'installer tant que le pont n'existe pas. On peut donc le renvoyer
     * autant de fois qu'on veut : c'est le filet contre les pages qui se
     * construisent en plusieurs temps.
     */
    private void injecter(final WebView vue) {
        try { vue.evaluateJavascript(GREFFON, null); } catch (Throwable ignored) { }
    }

    private void injecterPlusTard(final WebView vue, long delai) {
        differe.postDelayed(new Runnable() {
            @Override public void run() { injecter(vue); }
        }, delai);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        setContentView(root);

        webView = new BackgroundWebView(this);
        sWeb = new WeakReference<WebView>(webView);
        root.addView(webView, new FrameLayout.LayoutParams(
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

        // Permet au site de se reconnaitre dans l'APK (navInfo/notifMessage)
        s.setUserAgentString(s.getUserAgentString() + " SonoraAPK/1.7");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new JsBridge(), "SonoraNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                super.onPageStarted(view, url, f);
                injecter(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injecter(view);
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
     * On n'appelle deliberement NI webView.onPause() NI pauseTimers() ici.
     */
    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) { injecter(webView); }
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
