package com.sonora.app;

import android.content.Context;
import android.content.res.Configuration;
import android.webkit.JavascriptInterface;

public class JsBridge {

    private final Context ctx;

    public JsBridge(Context c) {
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
    public void onPont() {
        KeepAliveService.pousserPont();
    }

    @JavascriptInterface
    public void onMeta(String titre, String artiste, String album, String pochette) {
        KeepAliveService.pousserMeta(titre, artiste, album, pochette);
    }

    /**
     * Le service s'arrete tout seul apres deux minutes sans lecture et sans
     * ecran. Quand la page annonce qu'elle rejoue, il faut donc le rallumer
     * AVANT de lui parler, sinon la notification ne revient jamais et les
     * boutons de la barre disparaissent pour de bon.
     */
    @JavascriptInterface
    public void onState(boolean enLecture) {
        if (enLecture) { MainActivity.assurerService(); }
        KeepAliveService.pousserEtat(enLecture);
    }

    @JavascriptInterface
    public void onPosition(double dureeMs, double positionMs) {
        KeepAliveService.pousserPosition((long) dureeMs, (long) positionMs);
    }

    @JavascriptInterface
    public void onActions(String listeCsv) {
        KeepAliveService.pousserActions(listeCsv);
    }

    /** Moteur de lecture en cours cote site : "yt", "sc" ou "audio". */
    @JavascriptInterface
    public void onMoteur(String moteur) {
        KeepAliveService.pousserMoteur(moteur);
    }

    /** Diagnostic : d'ou viennent l'etat et la position (ex. "yt/site"). */
    @JavascriptInterface
    public void onSource(String source) {
        KeepAliveService.pousserSource(source);
    }
}
