package com.sonora.app;

import android.webkit.JavascriptInterface;

public class JsBridge {

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
