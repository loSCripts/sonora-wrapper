package com.sonora.app;

import android.webkit.JavascriptInterface;

/**
 * Recoit les infos du lecteur depuis la page et les transmet au service.
 *
 * Le site Sonora renseigne deja navigator.mediaSession (titre, pochette,
 * boutons). Dans Chrome, Android lit ca tout seul. Dans une WebView, non :
 * rien n'est transmis au systeme. On recupere donc les memes informations
 * a la source, sans rien changer au site.
 */
public class JsBridge {

    @JavascriptInterface
    public void onMeta(String titre, String artiste, String album, String pochette) {
        KeepAliveService.pousserMeta(titre, artiste, album, pochette);
    }

    @JavascriptInterface
    public void onState(boolean enLecture) {
        KeepAliveService.pousserEtat(enLecture);
    }

    @JavascriptInterface
    public void onPosition(long dureeMs, long positionMs) {
        KeepAliveService.pousserPosition(dureeMs, positionMs);
    }

    @JavascriptInterface
    public void onActions(String listeCsv) {
        KeepAliveService.pousserActions(listeCsv);
    }
}
