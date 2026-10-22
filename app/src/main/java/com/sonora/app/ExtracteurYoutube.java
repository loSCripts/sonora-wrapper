package com.sonora.app;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * TELECHARGER UN TITRE YOUTUBE, DEPUIS LE TELEPHONE (v2.5).
 *
 * Demande explicite du proprietaire, qui en assume le risque juridique.
 * Jusqu'ici, le site passait par un serveur (sonora-ytdl) que YouTube
 * reconnait comme un robot et bloque. Ici, tout part du telephone : la
 * lecture de la page YouTube (PasserelleNewPipe) comme le telechargement
 * (DownloadManager), avec l'adresse IP de la personne, comme si elle
 * regardait la video dans son navigateur.
 *
 * Un seul telechargement a la fois (la page recoit "occupe" sinon). La page
 * suit l'avancement par window.__sonoraTelechargement({etat, recu, total,
 * message, nom, mime, local}) ; en fin de course, « local » est une adresse
 * de la meme origine que le site (voir MainActivity.exposerFichier) pour
 * qu'elle puisse en garder une copie hors ligne.
 */
final class ExtracteurYoutube {

    private static final Pattern VIDEO = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** Rythme du suivi : assez vif pour une barre qui avance, sans plus. */
    private static final long SUIVI_MS = 800;

    private static final String REFUS_YOUTUBE =
            "YouTube a refusé le téléchargement : réessaie plus tard ou mets à jour l'application";

    private final MainActivity activite;
    private final AtomicBoolean occupe = new AtomicBoolean(false);
    private volatile Thread fil;

    ExtracteurYoutube(MainActivity a) {
        this.activite = a;
    }

    /** Appele par le pont (fil JavaBridge) : repond tout de suite, travaille a cote. */
    String lancer(String json) {
        final String video, mode, nom, titre, artiste;
        try {
            JSONObject o = new JSONObject(json == null ? "" : json);
            video = o.optString("video", "");
            mode = o.optString("mode", "");
            titre = o.optString("titre", "");
            artiste = o.optString("artiste", "");
            nom = Telechargements.nomPropre(o.optString("nom", ""),
                    Telechargements.nomPropre(titre, video));
        } catch (JSONException e) {
            return "refus:json";
        }
        if (!VIDEO.matcher(video).matches()) { return "refus:video"; }
        if (!"audio".equals(mode) && !"video".equals(mode)) { return "refus:mode"; }
        if (!occupe.compareAndSet(false, true)) { return "occupe"; }

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    travailler(video, "audio".equals(mode), nom, titre, artiste);
                } finally {
                    fil = null;
                    occupe.set(false);
                }
            }
        }, "sonora-youtube");
        fil = t;
        t.start();
        return "ok";
    }

    /**
     * L'activite se ferme : on cesse de suivre. Le telechargement lui-meme
     * appartient a DownloadManager et va au bout ; le fichier sera la.
     */
    void arreter() {
        Thread t = fil;
        if (t != null) { t.interrupt(); }
    }

    private void travailler(String video, boolean audio, String nom, String titre, String artiste) {
        rappel("progres", 0, 0, "Recherche de la vidéo…", "", "", "");

        PasserelleNewPipe.Flux flux;
        try {
            flux = PasserelleNewPipe.trouver(video, audio);
        } catch (Throwable t) {
            rappel("echec", 0, 0, PasserelleNewPipe.messagePour(t), "", "", "");
            return;
        }
        if (flux == null) {
            rappel("echec", 0, 0, "Aucun format téléchargeable pour cette vidéo", "", "", "");
            return;
        }
        String fichier = nom + "." + flux.extension;
        if (Thread.currentThread().isInterrupted()) { return; }
        if (!activite.attendreStockage()) {
            rappel("echec", 0, 0, MainActivity.MESSAGE_STOCKAGE, fichier, flux.mime, "");
            return;
        }

        long id;
        try {
            id = Telechargements.confier(activite, flux.url, fichier,
                    titre.length() > 0 ? titre : fichier,
                    artiste.length() > 0 ? "Sonora · " + artiste : "Sonora",
                    flux.mime, flux.agent);
        } catch (Throwable t) {
            rappel("echec", 0, 0, "Le téléchargement n'a pas pu démarrer", fichier, flux.mime, "");
            return;
        }
        suivre(id, fichier, flux.mime);
    }

    private void suivre(long id, String fichier, String mime) {
        DownloadManager dm = (DownloadManager) activite.getSystemService(Context.DOWNLOAD_SERVICE);
        String dejaDit = "";
        while (true) {
            try {
                Thread.sleep(SUIVI_MS);
            } catch (InterruptedException fin) {
                return;
            }
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c == null) { continue; }
            try {
                if (!c.moveToFirst()) {
                    // Retire depuis la notification de telechargement.
                    rappel("echec", 0, 0, "Téléchargement annulé", fichier, mime, "");
                    return;
                }
                int etat = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long recu = c.getLong(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = Math.max(0, c.getLong(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
                if (etat == DownloadManager.STATUS_SUCCESSFUL) {
                    String nomFinal = nomRetenu(c, fichier);
                    rappel("fini", recu, total, "Enregistré dans Téléchargements/Sonora",
                            nomFinal, mime, activite.exposerFichier(id, mime));
                    return;
                }
                if (etat == DownloadManager.STATUS_FAILED) {
                    int raison = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    dm.remove(id);   // pas de morceau de fichier laisse derriere
                    rappel("echec", recu, total, messageEchec(raison), fichier, mime, "");
                    return;
                }
                String point = etat + ":" + recu;
                if (!point.equals(dejaDit)) {
                    dejaDit = point;
                    rappel("progres", recu, total, etat == DownloadManager.STATUS_PAUSED
                            ? "En attente du réseau…" : "Téléchargement…", fichier, mime, "");
                }
            } finally {
                c.close();
            }
        }
    }

    /** Le nom que DownloadManager a reellement donne (il peut renommer). */
    private static String nomRetenu(Cursor c, String prevu) {
        try {
            String u = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            String n = u == null ? null : Uri.parse(u).getLastPathSegment();
            if (n != null && n.length() > 0) { return n; }
        } catch (Throwable ignored) { }
        return prevu;
    }

    private static String messageEchec(int raison) {
        switch (raison) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Plus assez de place sur le téléphone";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Stockage du téléphone introuvable";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "Un fichier du même nom existe déjà dans Téléchargements/Sonora";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return REFUS_YOUTUBE;
            default:
                // DownloadManager donne aussi le code HTTP brut (403...) :
                // c'est YouTube qui a ferme la porte.
                return raison >= 400 && raison < 600 ? REFUS_YOUTUBE : "Le téléchargement a échoué";
        }
    }

    private void rappel(String etat, long recu, long total, String message,
                        String nom, String mime, String local) {
        try {
            JSONObject o = new JSONObject();
            o.put("etat", etat);
            o.put("recu", recu);
            o.put("total", total);
            o.put("message", message);
            o.put("nom", nom);
            o.put("mime", mime);
            o.put("local", local);
            activite.rappelerPage(o);
        } catch (JSONException ignored) { }
    }
}
