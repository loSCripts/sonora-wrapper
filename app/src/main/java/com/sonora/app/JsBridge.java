package com.sonora.app;

import android.webkit.JavascriptInterface;

/**
 * window.SonoraNative, cote actions (v2.5).
 *
 * LE JETON. addJavascriptInterface publie cet objet dans TOUTES les frames,
 * y compris les cadres de publicite : avant la 2.5, une pub pouvait garder
 * les verrous allumes (onState), reecrire la notification ou faire
 * telecharger une adresse a l'application. Chaque methode qui AGIT exige
 * donc en premier argument le jeton que MainActivity pose dans le seul cadre
 * principal, et seulement quand c'est le site Sonora (window.__snbJeton,
 * renouvele a chaque page). Sans lui : rien ne se passe, "refus:jeton".
 *
 * Contrat a respecter a la lettre : le site v120 est ecrit contre lui.
 */
public class JsBridge extends PontLecture {

    private static final String REFUS = "refus:jeton";
    private static final String OK = "ok";

    private final MainActivity activite;

    public JsBridge(MainActivity a) {
        super(a);
        this.activite = a;
    }

    private boolean refuse(String jeton) {
        return !activite.jetonValide(jeton);
    }

    // -----------------------------------------------------------------
    //  Greffon de lecture (injecte par MainActivity, pas par le site)
    // -----------------------------------------------------------------

    @JavascriptInterface
    public String onPont(String jeton) {
        if (refuse(jeton)) { return REFUS; }
        KeepAliveService.pousserPont();
        return OK;
    }

    @JavascriptInterface
    public String onMeta(String jeton, String titre, String artiste, String album, String pochette) {
        if (refuse(jeton)) { return REFUS; }
        KeepAliveService.pousserMeta(titre, artiste, album, pochette);
        return OK;
    }

    /**
     * La premiere lecture fait naitre le service et sa notification (v2.5 :
     * plus rien a l'ouverture). Il faut le faire AVANT de lui parler, sinon
     * la notification ne vient jamais.
     */
    @JavascriptInterface
    public String onState(String jeton, boolean enLecture) {
        if (refuse(jeton)) { return REFUS; }
        KeepAliveService.pousserEtat(enLecture);
        if (enLecture) { MainActivity.assurerService(); }
        return OK;
    }

    @JavascriptInterface
    public String onPosition(String jeton, double dureeMs, double positionMs) {
        if (refuse(jeton)) { return REFUS; }
        KeepAliveService.pousserPosition((long) dureeMs, (long) positionMs);
        return OK;
    }

    @JavascriptInterface
    public String onActions(String jeton, String listeCsv) {
        if (refuse(jeton)) { return REFUS; }
        KeepAliveService.pousserActions(listeCsv);
        return OK;
    }

    /** Moteur de lecture en cours cote site : "yt", "sc" ou "audio". */
    @JavascriptInterface
    public String onMoteur(String jeton, String moteur) {
        if (refuse(jeton)) { return REFUS; }
        KeepAliveService.pousserMoteur(moteur);
        return OK;
    }

    /** Diagnostic : d'ou viennent l'etat et la position (ex. "yt/site/cur"). */
    @JavascriptInterface
    public String onSource(String jeton, String source) {
        if (refuse(jeton)) { return REFUS; }
        KeepAliveService.pousserSource(source);
        return OK;
    }

    // -----------------------------------------------------------------
    //  Fonctions offertes au site
    // -----------------------------------------------------------------

    /** json : {"video","mode","nom","titre","artiste"} ; voir ExtracteurYoutube. */
    @JavascriptInterface
    public String telechargerYt(String jeton, String json) {
        if (refuse(jeton)) { return REFUS; }
        return activite.extracteur().lancer(json);
    }

    @JavascriptInterface
    public String fichierDebut(String jeton, String nom, String mime) {
        if (refuse(jeton)) { return REFUS; }
        return activite.fichiers().debut(nom, mime);
    }

    @JavascriptInterface
    public String fichierMorceau(String jeton, String id, String base64) {
        if (refuse(jeton)) { return REFUS; }
        return activite.fichiers().morceau(id, base64);
    }

    @JavascriptInterface
    public String fichierFin(String jeton, String id) {
        if (refuse(jeton)) { return REFUS; }
        return activite.fichiers().fin(id);
    }

    /** navigator.share n'existe pas dans une WebView : le partage passe ici. */
    @JavascriptInterface
    public String partager(String jeton, String titre, String texte, String url) {
        if (refuse(jeton)) { return REFUS; }
        activite.partager(titre, texte, url);
        return OK;
    }

    /** Le theme choisi DANS Sonora ("light" ou "dark"), pas celui du telephone. */
    @JavascriptInterface
    public String theme(String jeton, String valeur) {
        if (refuse(jeton)) { return REFUS; }
        if (!"light".equals(valeur) && !"dark".equals(valeur)) { return "refus:valeur"; }
        activite.choisirTheme(valeur);
        return OK;
    }
}
