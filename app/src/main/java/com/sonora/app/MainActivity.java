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

public class MainActivity extends Activity {

    /** Seule ligne a changer si l'URL du site bouge. */
    private static final String SITE_URL = "https://sonora-sandy.vercel.app";

    private BackgroundWebView webView;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        setContentView(root);

        webView = new BackgroundWebView(this);
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

        // Autorise la lecture audio/video sans geste utilisateur prealable,
        // sinon les iframes refusent de demarrer toutes seules.
        s.setMediaPlaybackRequiresUserGesture(false);

        // Cookies tiers : necessaires pour les lecteurs embarques en iframe.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // Tout reste dans le WebView, rien ne part vers le navigateur externe.
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new FullscreenChromeClient());

        startKeepAlive();

        webView.loadUrl(SITE_URL);
    }

    private void startKeepAlive() {
        Intent i = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    /**
     * IMPORTANT : on n'appelle deliberement NI webView.onPause()
     * NI webView.pauseTimers() ici.
     *
     * C'est precisement ce que font la plupart des wrappers WebView, et c'est
     * ce qui coupe le son. En s'abstenant, le JS et l'audio continuent de
     * tourner quand l'utilisateur quitte l'application.
     */
    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rien a reprendre : la page n'a jamais ete mise en pause.
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
        // On met l'app en arriere-plan au lieu de la detruire :
        // detruire l'activite tuerait la lecture en cours.
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

    // --- Plein ecran pour les lecteurs embarques -------------------------

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
