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

    @JavascriptInterface
    public void onState(boolean enLecture) {
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
