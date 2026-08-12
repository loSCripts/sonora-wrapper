package com.sonora.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ECRAN DE DIAGNOSTIC — s'ouvre par un appui long sur l'icone Sonora.
 *
 * POURQUOI IL EXISTE
 * Le site marche parfaitement dans un navigateur et deraille une fois
 * empaquete dans l'APK. Tant que rien ne mesure la difference sur le
 * telephone qui echoue, tout ce qu'on peut dire de la cause reste une
 * supposition. Cet ecran ne suppose rien.
 *
 * CE QU'IL FAIT, ET QUI EST TOUT L'INTERET
 * Chaque adresse est demandee DEUX FOIS : une fois par Java
 * (HttpURLConnection, hors de la WebView), une fois par la page elle-meme
 * (fetch, depuis l'origine du site, donc avec les vraies regles CORS).
 * La comparaison des deux repond a la seule question qui compte :
 *
 *   Java OK  + page OK   -> rien a signaler
 *   Java OK  + page KO   -> C'EST LA WEBVIEW qui bloque (Safe Browsing,
 *                           CORS, agent utilisateur, filtrage interne)
 *   Java KO  + page KO   -> c'est le reseau, l'operateur ou le serveur
 *   Java KO  + page OK   -> cas rare, a montrer tel quel
 *
 * Meme methode pour le theme : ce qu'Android dit du telephone, face a ce
 * que la WebView repond a prefers-color-scheme. Si les deux divergent,
 * c'est ecrit noir sur blanc au lieu d'etre deduit.
 */
public class DiagnosticActivity extends Activity {

    private static final String VERSION = "2.1";
    private static final String SITE = "https://sonora-sandy.vercel.app";

    /** Page du site la plus legere : meme origine, aucun lecteur lance. */
    private static final String PAGE_ESSAI = SITE + "/legal.html";

    private static final String[] NOMS = {
        "Le site Sonora",
        "Proxy Last.fm (direct)",
        "Relais Last.fm (Supabase)",
        "iTunes (recherche)"
    };
    private static final String[] URLS = {
        SITE + "/r2.json",
        "https://sonora-lastfm-proxy.vercel.app/api/lastfm?method=artist.getsimilar&artist=Daft%20Punk&limit=1",
        "https://ngrjawapavxmpsxndjmp.supabase.co/functions/v1/lastfm?method=artist.getsimilar&artist=Daft%20Punk&limit=1",
        "https://itunes.apple.com/search?term=daft+punk&limit=1"
    };

    /* Batterie cote page. Volontairement ecrite en JavaScript d'avant 2015 :
       cet ecran doit encore fonctionner sur une WebView vieille de dix ans,
       puisque c'est precisement ce genre de telephone qu'on cherche a
       comprendre. Pas de fleche, pas de gabarit, pas d'AbortController. */
    private static final String JS =
        "(function(){var L=[];function esc(x){return String(x==null?'':x).replace(/[|\\n\\r]/g,' ')}"
      + "try{L.push('T|'+(matchMedia('(prefers-color-scheme: dark)').matches?1:0)+'|'"
      + "+(matchMedia('(prefers-color-scheme: light)').matches?1:0)+'|'"
      + "+((window.SonoraNative&&SonoraNative.modeSysteme)?SonoraNative.modeSysteme():'absent')+'|'"
      + "+(document.documentElement.getAttribute('data-theme')||'-'))}catch(e){L.push('T|?|?|?|?')}"
      + "try{L.push('U|'+esc(navigator.userAgent))}catch(e){}"
      + "try{L.push('O|'+esc(location.origin))}catch(e){}"
      + "var U=%URLS%;var reste=U.length;var R=[];"
      + "function fini(){if(--reste>0)return;for(var k=0;k<R.length;k++)L.push(R[k]);"
      + "try{SonoraDiag.resultat(L.join('\\n'))}catch(e){}}"
      + "for(var i=0;i<U.length;i++){(function(j){var t0=new Date().getTime();"
      + "fetch(U[j]).then(function(r){var ct='';try{ct=r.headers.get('content-type')||''}catch(e){}"
      + "return r.text().then(function(t){R[j]='R|'+j+'|'+(r.ok?1:0)+'|'+r.status+'|'"
      + "+(new Date().getTime()-t0)+'|'+esc(ct)+'|'+esc((t||'').substring(0,70));fini()})})"
      + ".catch(function(e){R[j]='R|'+j+'|0|0|'+(new Date().getTime()-t0)+'|-|'"
      + "+esc((e&&e.message)||e);fini()})})(i)}"
      + "if(!U.length)fini();})();";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView rapport;
    private WebView sonde;
    private boolean sombre;

    private String[][] cotJava;      // par adresse : {ok, code, ms, type, debut}
    private String cotePage;         // lignes renvoyees par la page
    private boolean pageRendue, javaRendu, deja;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sombre = (getResources().getConfiguration().uiMode
                  & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        int fond  = sombre ? Color.parseColor("#0B0B0F") : Color.WHITE;
        int encre = sombre ? Color.parseColor("#E8E8EC") : Color.parseColor("#131313");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(fond);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(p, p, p, p);

        TextView titre = new TextView(this);
        titre.setText("Diagnostic Sonora " + VERSION);
        titre.setTextColor(encre);
        titre.setTypeface(Typeface.DEFAULT_BOLD);
        titre.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        col.addView(titre);

        TextView aide = new TextView(this);
        aide.setText("Chaque adresse est demandee deux fois : une fois par "
                   + "l'application, une fois par la page. C'est la comparaison "
                   + "des deux qui dit ou ca coince.");
        aide.setTextColor(sombre ? Color.parseColor("#9A9AA6") : Color.parseColor("#5D5D67"));
        aide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        aide.setPadding(0, p / 2, 0, p);
        col.addView(aide);

        rapport = new TextView(this);
        rapport.setText("Mesures en cours...");
        rapport.setTextColor(encre);
        rapport.setTypeface(Typeface.MONOSPACE);
        rapport.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        rapport.setTextIsSelectable(true);

        ScrollView sc = new ScrollView(this);
        sc.addView(rapport, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout rangee = new LinearLayout(this);
        rangee.setOrientation(LinearLayout.HORIZONTAL);
        rangee.setPadding(0, p, 0, 0);

        Button copier = new Button(this);
        copier.setText("Copier");
        copier.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Diagnostic Sonora",
                            rapport.getText().toString()));
                    Toast.makeText(DiagnosticActivity.this,
                            "Rapport copie", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) { }
            }
        });

        Button relancer = new Button(this);
        relancer.setText("Relancer");
        relancer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { lancer(); }
        });

        LinearLayout.LayoutParams demi =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rangee.addView(copier, demi);
        rangee.addView(relancer, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(rangee);

        setContentView(col);
        lancer();
    }

    private void lancer() {
        cotJava = new String[URLS.length][];
        cotePage = null;
        pageRendue = false; javaRendu = false; deja = false;
        rapport.setText("Mesures en cours...");

        // --- Cote application : hors de la WebView, sans aucune regle de page.
        new Thread(new Runnable() {
            @Override public void run() {
                final String[][] res = new String[URLS.length][];
                for (int i = 0; i < URLS.length; i++) {
                    res[i] = essaiJava(URLS[i]);
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        cotJava = res; javaRendu = true; peutEtreAfficher();
                    }
                });
            }
        }).start();

        // --- Cote page : la meme chose, mais depuis l'origine du site.
        if (sonde != null) { try { sonde.destroy(); } catch (Throwable t) { } }
        sonde = new WebView(this);
        WebSettings st = sonde.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        sonde.addJavascriptInterface(new Pont(), "SonoraDiag");
        sonde.addJavascriptInterface(new JsBridge(this), "SonoraNative");
        sonde.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                StringBuilder liste = new StringBuilder("[");
                for (int i = 0; i < URLS.length; i++) {
                    if (i > 0) { liste.append(","); }
                    liste.append("'").append(URLS[i]).append("'");
                }
                liste.append("]");
                try { v.evaluateJavascript(JS.replace("%URLS%", liste.toString()), null); }
                catch (Throwable t) { }
            }
        });
        sonde.loadUrl(PAGE_ESSAI);

        // Filet : si la page ne repond jamais, on montre au moins le cote Java.
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!pageRendue) {
                    cotePage = null; pageRendue = true; peutEtreAfficher();
                }
            }
        }, 30000);
    }

    private class Pont {
        @JavascriptInterface
        public void resultat(final String lignes) {
            ui.post(new Runnable() {
                @Override public void run() {
                    cotePage = lignes; pageRendue = true; peutEtreAfficher();
                }
            });
        }
    }

    private void peutEtreAfficher() {
        if (!pageRendue || !javaRendu || deja) { return; }
        deja = true;
        rapport.setText(construire());
    }

    /** Une requete faite par l'application elle-meme. */
    private static String[] essaiJava(String adresse) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(adresse).openConnection();
            c.setConnectTimeout(9000);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json,text/plain,*/*");
            int code = c.getResponseCode();
            String type = c.getContentType();
            InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            byte[] tampon = new byte[220];
            int lu = 0;
            if (in != null) {
                lu = Math.max(0, in.read(tampon));
                try { in.close(); } catch (Throwable t) { }
            }
            String debut = new String(tampon, 0, lu, "UTF-8").replaceAll("\\s+", " ").trim();
            return new String[] {
                (code >= 200 && code < 400) ? "1" : "0",
                String.valueOf(code),
                String.valueOf(System.currentTimeMillis() - t0),
                (type == null ? "?" : type),
                debut
            };
        } catch (Throwable t) {
            return new String[] {
                "0", "0", String.valueOf(System.currentTimeMillis() - t0), "-",
                t.getClass().getSimpleName() + " : " + String.valueOf(t.getMessage())
            };
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable t) { } }
        }
    }

    private String champ(String prefixe, int position) {
        if (cotePage == null) { return null; }
        String[] lignes = cotePage.split("\n");
        for (int i = 0; i < lignes.length; i++) {
            String[] m = lignes[i].split("\\|", -1);
            if (m.length > position && m[0].equals(prefixe)) { return m[position]; }
        }
        return null;
    }

    private String[] ligneReseau(int index) {
        if (cotePage == null) { return null; }
        String[] lignes = cotePage.split("\n");
        for (int i = 0; i < lignes.length; i++) {
            String[] m = lignes[i].split("\\|", -1);
            if (m.length >= 7 && m[0].equals("R") && m[1].equals(String.valueOf(index))) { return m; }
        }
        return null;
    }

    private String construire() {
        StringBuilder r = new StringBuilder();

        // ---------- 1. La machine ----------
        r.append("1. LE TELEPHONE\n");
        r.append("   ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        r.append("   Android ").append(Build.VERSION.RELEASE)
         .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        r.append("   Sonora APK ").append(VERSION).append("\n");
        String moteur = "inconnu";
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) { moteur = pi.packageName + " " + pi.versionName; }
            } catch (Throwable t) { }
        }
        r.append("   Moteur WebView : ").append(moteur).append("\n");
        String ua = champ("U", 1);
        if (ua != null) { r.append("   Identite : ").append(ua).append("\n"); }
        r.append("\n");

        // ---------- 2. Clair / sombre ----------
        r.append("2. MODE CLAIR / SOMBRE\n");
        boolean nuit = (getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        r.append("   Android dit          : ").append(nuit ? "SOMBRE" : "CLAIR").append("\n");
        String wd = champ("T", 1), wl = champ("T", 2);
        String pont = champ("T", 3), pose = champ("T", 4);
        if (wd == null) {
            r.append("   La page n'a pas repondu (voir plus bas)\n");
        } else {
            String vueWeb = "1".equals(wd) ? "SOMBRE" : ("1".equals(wl) ? "CLAIR" : "NE SAIT PAS");
            r.append("   La WebView repond    : ").append(vueWeb).append("\n");
            r.append("   Le pont Java repond  : ").append(pont).append("\n");
            r.append("   Theme finalement pose: ").append(pose).append("\n");
            boolean webNuit = "1".equals(wd);
            boolean webSait = "1".equals(wd) || "1".equals(wl);
            if (!webSait) {
                r.append("   >>> La WebView ne sait pas repondre. Sans le pont,\n");
                r.append("       le site n'aurait aucun moyen de savoir.\n");
            } else if (webNuit != nuit) {
                r.append("   >>> LA WEBVIEW SE TROMPE. C'est exactement le defaut\n");
                r.append("       corrige : le site suit le pont, pas la WebView.\n");
            } else {
                r.append("   Les deux concordent.\n");
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                r.append("   (force dark WebView : ")
                 .append(String.valueOf(new WebView(this).getSettings().getForceDark()))
                 .append(" — 0 = coupe, ce qu'on veut)\n");
            } catch (Throwable t) { }
        }
        r.append("\n");

        // ---------- 3. Le reseau, des deux cotes ----------
        r.append("3. RESEAU — application contre page\n");
        String origine = champ("O", 1);
        r.append("   Page d'essai : ").append(origine == null ? "non chargee" : origine).append("\n\n");

        for (int i = 0; i < URLS.length; i++) {
            r.append("   ").append(NOMS[i]).append("\n");

            String[] j = (cotJava != null && cotJava[i] != null) ? cotJava[i] : null;
            boolean okJava = j != null && "1".equals(j[0]);
            if (j == null) {
                r.append("     application : pas de mesure\n");
            } else {
                r.append("     application : ").append(okJava ? "OK" : "ECHEC")
                 .append("  code ").append(j[1]).append("  ").append(j[2]).append(" ms\n");
                r.append("                  ").append(j[3]).append("\n");
                r.append("                  ").append(coupe(j[4], 120)).append("\n");
            }

            String[] w = ligneReseau(i);
            boolean okWeb = w != null && "1".equals(w[2]);
            if (w == null) {
                r.append("     page        : pas de mesure\n");
            } else {
                r.append("     page        : ").append(okWeb ? "OK" : "ECHEC")
                 .append("  code ").append(w[3]).append("  ").append(w[4]).append(" ms\n");
                r.append("                  ").append(w[5]).append("\n");
                r.append("                  ").append(coupe(w[6], 120)).append("\n");
            }

            if (j != null && w != null) {
                r.append("     -> ").append(verdict(okJava, okWeb)).append("\n");
            }
            r.append("\n");
        }

        r.append("Fait le ").append(new java.util.Date().toString()).append("\n");
        return r.toString();
    }

    private static String coupe(String t, int max) {
        if (t == null) { return ""; }
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    /**
     * La seule ligne qui compte vraiment : elle separe « le reseau ne passe
     * pas » de « c'est l'enveloppe WebView qui bloque ».
     */
    private static String verdict(boolean java, boolean page) {
        if (java && page)   { return "rien a signaler"; }
        if (java && !page)  { return "C'EST LA WEBVIEW QUI BLOQUE (le reseau, lui, repond)"; }
        if (!java && !page) { return "INJOIGNABLE des deux cotes : reseau, operateur ou serveur"; }
        return "repond a la page mais pas a l'application (cas rare, montre-le tel quel)";
    }

    @Override
    protected void onDestroy() {
        if (sonde != null) {
            try { sonde.destroy(); } catch (Throwable t) { }
            sonde = null;
        }
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
