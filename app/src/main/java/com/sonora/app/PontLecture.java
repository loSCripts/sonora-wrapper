package com.sonora.app;

import android.content.Context;
import android.content.res.Configuration;
import android.webkit.JavascriptInterface;

/**
 * Ce que n'importe quelle page peut demander a l'application SANS jeton :
 * des lectures, jamais une action (v2.5). Le pont est visible de toutes les
 * frames, publicites comprises (doc Android) ; tout ce qui agit est donc
 * dans JsBridge, derriere le jeton. DiagnosticActivity n'expose que ceci.
 */
public class PontLecture {

    /** Ce que sait faire cette APK : la page ne propose que ce qui existe. */
    private static final String CAPACITES =
            "{\"telechargerYt\":true,\"fichier\":true,\"partager\":true,\"theme\":true}";

    final Context ctx;

    public PontLecture(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /**
     * "light" ou "dark" : le VRAI reglage clair/sombre du telephone, lu dans
     * la configuration Android.
     *
     * PIEGE. On ne peut pas demander ca au navigateur depuis la page. Une
     * WebView ne repond pas a prefers-color-scheme d'apres le reglage du
     * telephone mais d'apres le theme de l'application qui l'heberge, et sa
     * facon d'en decider change avec la version de WebView installee -- donc
     * d'un constructeur a l'autre. Le site (themeSysLight dans app.html)
     * appelle cette methode en premier : sans elle, il retombe sur la
     * reponse de la WebView, celle qui se trompe.
     */
    @JavascriptInterface
    public String modeSysteme() {
        try {
            int f = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return f == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        } catch (Throwable t) {
            return "";
        }
    }

    @JavascriptInterface
    public String versionApk() {
        return BuildConfig.VERSION_NAME;
    }

    @JavascriptInterface
    public String capacites() {
        return CAPACITES;
    }
}
