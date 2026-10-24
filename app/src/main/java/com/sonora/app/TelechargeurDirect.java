package com.sonora.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TELECHARGER UN FLUX YOUTUBE DANS L'APPLICATION, PAR MORCEAUX (v2.6).
 *
 * En 2.5, l'adresse trouvee par NewPipeExtractor etait confiee a
 * DownloadManager. Deux raisons de ne plus le faire d'abord :
 *  . l'adresse de googlevideo est liee a l'adresse IP qui l'a obtenue
 *    (parametre ip=). DownloadManager est un autre processus, qui peut
 *    sortir par une autre adresse (IPv4 au lieu d'IPv6, par exemple) :
 *    googlevideo repond alors 403. Ici, le meme processus et la meme pile
 *    reseau que l'extraction ;
 *  . googlevideo limite les gros envois d'un seul tenant. Comme NewPipe et
 *    yt-dlp, on demande le fichier par tranches (en-tete Range) de 2 Mo.
 *
 * Aucune classe Android : l'essai sur ordinateur compile ce fichier tel quel
 * contre un faux serveur (voir le compte rendu de la v2.6).
 */
final class TelechargeurDirect {

    /** Taille d'une tranche : assez petite pour googlevideo, assez grande pour aller vite. */
    static final int TRANCHE = 2 * 1024 * 1024;

    /** Au-dela, ce n'est plus un morceau de musique ni un clip : on arrete. */
    static final long TAILLE_MAX = 1024L * 1024 * 1024;

    /** Une coupure reseau au milieu d'une tranche : on la redemande, pas tout le fichier. */
    private static final int ESSAIS_PAR_TRANCHE = 3;

    private static final Pattern PLAGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)");

    /** Ce que la page voit avancer. */
    interface Suivi {
        void progres(long recu, long total);
        boolean annule();
    }

    /** googlevideo a ferme la porte (403, 410...) : une adresse fraiche peut suffire. */
    static final class Refus extends IOException {
        final int code;
        Refus(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    /** L'utilisateur ou l'activite a arrete le telechargement. */
    static final class Annule extends IOException {
        Annule() { super("annule"); }
    }

    private TelechargeurDirect() { }

    /**
     * Ecrit tout le fichier dans « sortie » et rend sa taille. Lance Refus si
     * le serveur refuse, IOException si le reseau lache pour de bon.
     */
    static long telecharger(String url, String agent, OutputStream sortie, Suivi suivi) throws IOException {
        // pos[0] = octets deja ecrits. Tenu a jour octet par octet par
        // tranche() : une coupure au milieu d'une tranche reprend la, sans
        // jamais reecrire ce qui est deja dans « sortie ».
        long[] pos = { 0 };
        long total = -1;
        while (true) {
            if (suivi != null && suivi.annule()) { throw new Annule(); }
            long fin = pos[0] + TRANCHE - 1;
            if (total > 0 && fin > total - 1) { fin = total - 1; }
            long[] r = null;
            IOException derniere = null;
            for (int essai = 0; essai < ESSAIS_PAR_TRANCHE && r == null; essai++) {
                try {
                    r = tranche(url, agent, pos, fin, sortie, suivi, total);
                } catch (Refus | Annule definitif) {
                    throw definitif;
                } catch (IOException coupure) {
                    derniere = coupure;
                    attendre(600L * (essai + 1));
                }
            }
            if (r == null) { throw derniere != null ? derniere : new IOException("tranche"); }
            if (r[0] >= 0) { total = r[0]; }
            if (r[1] == 1) { break; }                       // tout le fichier d'un coup (200)
            if (total >= 0 && pos[0] >= total) { break; }   // dernier octet recu
            if (total < 0 && r[2] == 1) { break; }          // taille inconnue, tranche courte : fini
            if (pos[0] > TAILLE_MAX) { throw new IOException("trop gros"); }
        }
        return pos[0];
    }

    /**
     * La tranche [pos[0], fin]. Rend { total connu ou -1, 1 si le serveur a
     * envoye tout le fichier d'un coup, 1 si la tranche est arrivee courte }.
     */
    private static long[] tranche(String url, String agent, long[] pos, long fin, OutputStream sortie,
                                  Suivi suivi, long totalConnu) throws IOException {
        long debut = pos[0];
        if (debut > fin) { return new long[] { totalConnu, 0, 0 }; }   // deja complete avant la coupure
        HttpURLConnection co = (HttpURLConnection) new URL(url).openConnection();
        try {
            co.setConnectTimeout(15000);
            co.setReadTimeout(30000);
            co.setInstanceFollowRedirects(true);
            co.setUseCaches(false);
            if (agent != null) { co.setRequestProperty("User-Agent", agent); }
            co.setRequestProperty("Accept", "*/*");
            co.setRequestProperty("Accept-Encoding", "identity");
            co.setRequestProperty("Range", "bytes=" + debut + "-" + fin);
            int code = co.getResponseCode();
            if (code == 403 || code == 401 || code == 410 || code == 404 || code == 429) {
                throw new Refus(code);
            }
            if (code == 416 && totalConnu > 0 && debut >= totalConnu) {
                return new long[] { totalConnu, 0, 0 };
            }
            boolean entier = code == 200;
            if (code != 206 && !entier) { throw new IOException("HTTP " + code); }
            long total = totalConnu;
            if (entier) {
                // Plage ignoree : tout le fichier arrive depuis le debut. Ce
                // n'est utilisable que si rien n'est encore ecrit.
                if (debut > 0) { throw new IOException("plage ignoree en cours de route"); }
                total = co.getContentLengthLong();
            } else {
                String cr = co.getHeaderField("Content-Range");
                Matcher m = cr == null ? null : PLAGE.matcher(cr);
                if (m == null || !m.find()) { throw new IOException("Content-Range absent"); }
                if (Long.parseLong(m.group(1)) != debut) { throw new IOException("plage inattendue"); }
                if (!"*".equals(m.group(3))) { total = Long.parseLong(m.group(3)); }
            }
            if (total > TAILLE_MAX) { throw new IOException("trop gros"); }
            byte[] tampon = new byte[65536];
            try (InputStream in = co.getInputStream()) {
                int lu;
                while ((lu = in.read(tampon)) > 0) {
                    if (suivi != null && suivi.annule()) { throw new Annule(); }
                    sortie.write(tampon, 0, lu);
                    pos[0] += lu;
                    if (suivi != null) { suivi.progres(pos[0], total); }
                    if (pos[0] > TAILLE_MAX) { throw new IOException("trop gros"); }
                }
            }
            if (entier) {
                if (total > 0 && pos[0] != total) { throw new IOException("fichier incomplet"); }
                return new long[] { total, 1, 0 };
            }
            boolean courte = pos[0] - debut < fin - debut + 1;
            return new long[] { total, 0, courte ? 1 : 0 };
        } finally {
            co.disconnect();
        }
    }

    private static void attendre(long ms) throws Annule {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Annule();
        }
    }
}
