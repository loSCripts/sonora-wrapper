package com.sonora.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

public class MainActivity extends Activity {

    /** Seule ligne a changer si l'URL du site bouge. */
    private static final String SITE_URL = "https://sonora-sandy.vercel.app";

    /**
     * Greffon injecte dans la page.
     *
     * Il n'ecrase rien : il enveloppe setActionHandler et les proprietes
     * metadata / playbackState / setPositionState pour recopier vers Android
     * ce que le site declare deja, puis passe la main au code d'origine.
     * Zero modification du site.
     */
    private static final String GREFFON =
        "(function(){if(window.__snb)return;window.__snb=1;"
      + "var ms=navigator.mediaSession;if(!ms)return;var H={};"
      + "var o=ms.setActionHandler.bind(ms);"
      + "ms.setActionHandler=function(a,f){if(f){H[a]=f}else{delete H[a]}"
      + "try{o(a,f)}catch(e){}p()};"
      + "window.__snbCall=function(a,d){var f=H[a];if(f){try{"
      + "f({action:a,seekOffset:d,seekTime:d})}catch(e){}}};"
      + "function p(){try{SonoraNative.onActions(Object.keys(H).join(','))}catch(e){}}"
      + "var P=Object.getPrototypeOf(ms);"
      + "var dm=Object.getOwnPropertyDescriptor(P,'metadata');"
      + "if(dm&&dm.set){Object.defineProperty(ms,'metadata',{configurable:true,"
      + "get:function(){return dm.get.call(ms)},"
      + "set:function(v){dm.set.call(ms,v);try{var u='';"
      + "if(v&&v.artwork&&v.artwork.length){u=v.artwork[v.artwork.length-1].src}"
      + "SonoraNative.onMeta(v&&v.title?v.title:'',v&&v.artist?v.artist:'',"
      + "v&&v.album?v.album:'',u)}catch(e){}}})}"
      + "var dp=Object.getOwnPropertyDescriptor(P,'playbackState');"
      + "if(dp&&dp.set){Object.defineProperty(ms,'playbackState',{configurable:true,"
      + "get:function(){return dp.get.call(ms)},"
      + "set:function(v){dp.set.call(ms,v);try{"
      + "SonoraNative.onState(v==='playing')}catch(e){}}})}"
      + "if(ms.setPositionState){var sp=ms.setPositionState.bind(ms);"
      + "ms.setPositionState=function(s){try{sp(s)}catch(e){}try{if(s)"
      + "SonoraNative.onPosition(Math.round((s.duration||0)*1000),"
      + "Math.round((s.position||0)*1000))}catch(e){}}}"
      + "})();";

    private static WeakReference<WebView> sWeb = new WeakReference<>(null);

    private BackgroundWebView webView;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    /** Appele par le service quand on touche un bouton de la notification. */
    public static void appelerJs(String action, int secondes) {
        final WebView w = sWeb.get();
        if (w == null) {
            return;
        }
        final String js = "window.__snbCall&&window.__snbCall('"
                + action + "'," + secondes + ")";
        w.post(new Runnable() {
            @Override
            public void run() {
                w.evaluateJavascript(js, null);
            }
        });
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

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new JsBridge(), "SonoraNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                super.onPageStarted(view, url, f);
                view.evaluateJavascript(GREFFON, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Filet : si onPageStarted etait trop tot, on recommence.
                view.evaluateJavascript(GREFFON, null);
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
