package com.sonora.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * « PAS DE CONNEXION » (v2.5).
 *
 * Ouverte sans reseau, l'application montrait la page d'erreur brute de
 * Chromium (ou un aplat vide sous le voile), sans rien a toucher : il
 * fallait la tuer et la relancer. Cet ecran natif, aux couleurs de la page,
 * dit ce qui se passe, offre « Reessayer », et recharge tout seul des que le
 * reseau revient.
 */
final class EcranHorsLigne {

    private final Activity activite;
    private final FrameLayout racine;
    private final Runnable reessayer;
    private final ConnectivityManager connexions;

    private LinearLayout vue;
    private TextView titre;
    private TextView texte;
    private Button bouton;

    /** Dernier reseau vu « valide » (Internet joignable), fil ConnectivityThread. */
    private Network valide;

    /**
     * Ecoute pendant toute la vie de l'activite, mais n'agit que si l'ecran
     * est affiche : un reseau qui apparait, ou qui devient vraiment
     * utilisable (validation Android), relance le chargement. Les rappels de
     * l'inscription elle-meme tombent quand l'ecran est cache : ignores.
     */
    private final ConnectivityManager.NetworkCallback ecoute =
            new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) {
            retourDuReseau();
        }

        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            boolean ok = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (ok && !n.equals(valide)) {
                valide = n;
                retourDuReseau();
            } else if (!ok && n.equals(valide)) {
                valide = null;
            }
        }

        @Override public void onLost(Network n) {
            if (n.equals(valide)) { valide = null; }
        }
    };

    EcranHorsLigne(Activity a, FrameLayout racine, Runnable reessayer) {
        this.activite = a;
        this.racine = racine;
        this.reessayer = reessayer;
        this.connexions = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        try { connexions.registerDefaultNetworkCallback(ecoute); } catch (Throwable ignored) { }
    }

    boolean visible() {
        return vue != null && vue.getParent() != null;
    }

    /**
     * @param serveur true pour une erreur 5xx du site (le reseau, lui, va bien)
     */
    void montrer(boolean serveur, int fond, boolean sombre) {
        if (vue == null) { construire(); }
        int encre = sombre ? 0xFFE8E8EC : 0xFF131313;
        int douce = sombre ? 0xFF9A9AA6 : 0xFF5D5D67;
        vue.setBackgroundColor(fond);
        titre.setTextColor(encre);
        texte.setTextColor(douce);
        titre.setText(serveur ? R.string.serveur_titre : R.string.hors_ligne_titre);
        texte.setText(serveur ? R.string.serveur_texte : R.string.hors_ligne_texte);
        bouton.setText(R.string.reessayer);
        bouton.setEnabled(true);
        if (vue.getParent() == null) {
            // Par-dessus tout, voile compris.
            racine.addView(vue, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    void cacher() {
        if (visible()) { racine.removeView(vue); }
    }

    void liberer() {
        try { connexions.unregisterNetworkCallback(ecoute); } catch (Throwable ignored) { }
    }

    private void retourDuReseau() {
        activite.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (visible()) { relancer(); }
            }
        });
    }

    private void relancer() {
        bouton.setEnabled(false);
        bouton.setText(R.string.connexion_en_cours);
        reessayer.run();
    }

    private void construire() {
        float d = activite.getResources().getDisplayMetrics().density;
        int marge = (int) (32 * d);

        vue = new LinearLayout(activite);
        vue.setOrientation(LinearLayout.VERTICAL);
        vue.setGravity(Gravity.CENTER);
        vue.setPadding(marge, marge, marge, marge);
        vue.setClickable(true);          // rien ne passe a la page dessous

        titre = new TextView(activite);
        titre.setTypeface(Typeface.DEFAULT_BOLD);
        titre.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titre.setGravity(Gravity.CENTER);
        vue.addView(titre);

        texte = new TextView(activite);
        texte.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        texte.setGravity(Gravity.CENTER);
        texte.setPadding(0, (int) (12 * d), 0, (int) (28 * d));
        vue.addView(texte);

        bouton = new Button(activite);
        bouton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { relancer(); }
        });
        vue.addView(bouton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
