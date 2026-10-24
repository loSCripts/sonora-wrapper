package com.sonora.app;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * TELECHARGER UN TITRE YOUTUBE, DEPUIS LE TELEPHONE (v2.5, revu en v2.6).
 *
 * Demande explicite du proprietaire, qui en assume le risque juridique.
 * Jusqu'ici, le site passait par un serveur (sonora-ytdl) que YouTube
 * reconnait comme un robot et bloque. Ici, tout part du telephone : la
 * lecture de la page YouTube (PasserelleNewPipe) comme le telechargement,
 * avec l'adresse IP de la personne, comme si elle regardait la video dans
 * son navigateur.
 *
 * v2.6 : le fichier est telecharge PAR L'APPLICATION (TelechargeurDirect,
 * par tranches, meme processus que l'extraction), puis range dans
 * Telechargements/Sonora. Un refus de YouTube : nouvelle extraction (adresse
 * fraiche) et un second essai. DownloadManager ne sert plus qu'en dernier
 * recours.
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

    /** Les fichiers telecharges restent la pour la copie hors ligne de la page, un jour au plus. */
    private static final long GARDE_MS = 24L * 3600 * 1000;

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
        if (Thread.currentThread().isInterrupted()) { return; }
        if (!activite.attendreStockage()) {
            rappel("echec", 0, 0, MainActivity.MESSAGE_STOCKAGE, nom + "." + flux.extension, flux.mime, "");
            return;
        }

        // 1. Dans l'application, par tranches. Un refus : adresse fraiche, une fois.
        File dossier = dossierTemporaire();
        for (int essai = 0; essai < 2 && dossier != null; essai++) {
            final String fichier = nom + "." + flux.extension;
            final String mime = flux.mime;
            File temp = new File(dossier, video + (audio ? "-son." : "-video.") + flux.extension);
            try {
                try (OutputStream sortie = new FileOutputStream(temp)) {
                    TelechargeurDirect.telecharger(flux.url, flux.agent, sortie, suivi(fichier, mime));
                }
                String nomFinal = Telechargements.publier(activite, temp, fichier, mime);
                rappel("fini", temp.length(), temp.length(), "Enregistré dans Téléchargements/Sonora",
                        nomFinal, mime, activite.exposerFichierLocal(temp, mime));
                return;
            } catch (TelechargeurDirect.Annule arret) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return;
            } catch (TelechargeurDirect.Refus refus) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                if (essai > 0) { break; }
                rappel("progres", 0, 0, "YouTube a refusé, nouvel essai…", fichier, mime, "");
                try {
                    PasserelleNewPipe.Flux frais = PasserelleNewPipe.trouver(video, audio);
                    if (frais == null) { break; }
                    flux = frais;
                } catch (Throwable t) {
                    break;
                }
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                break;
            }
        }
        if (Thread.currentThread().isInterrupted()) { return; }

        // 2. Dernier recours : DownloadManager, comme en 2.5.
        String fichier = nom + "." + flux.extension;
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

    /** La barre de la page avance, sans l'inonder : quatre nouvelles par seconde au plus. */
    private TelechargeurDirect.Suivi suivi(final String fichier, final String mime) {
        return new TelechargeurDirect.Suivi() {
            private long dernier = 0;
            @Override public void progres(long recu, long total) {
                long maintenant = System.currentTimeMillis();
                if (maintenant - dernier < 250 && (total <= 0 || recu < total)) { return; }
                dernier = maintenant;
                rappel("progres", recu, Math.max(0, total), "Téléchargement…", fichier, mime, "");
            }
            @Override public boolean annule() {
                return Thread.currentThread().isInterrupted();
            }
        };
    }

    /**
     * cache/youtube : la ou le fichier se construit, et d'ou la page en tire
     * sa copie hors ligne. Ce qui a plus d'un jour est efface au passage
     * (Android vide aussi ce dossier de lui-meme quand la place manque).
     */
    private File dossierTemporaire() {
        File d = new File(activite.getCacheDir(), "youtube");
        if (!d.isDirectory() && !d.mkdirs()) { return null; }
        File[] vieux = d.listFiles();
        long limite = System.currentTimeMillis() - GARDE_MS;
        if (vieux != null) {
            for (File f : vieux) {
                //noinspection ResultOfMethodCallIgnored
                if (f.lastModified() < limite) { f.delete(); }
            }
        }
        return d;
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
