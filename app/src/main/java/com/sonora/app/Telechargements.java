package com.sonora.app;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TELECHARGEMENTS/SONORA (v2.5).
 *
 * Un « Telecharger » de la page fabrique un blob: et le remet a un lien
 * <a download> : une WebView l'ignore, rien n'arrivait dans le telephone
 * alors que la page annoncait le contraire. Un blob n'existe que dans la
 * page, Android ne peut pas aller le chercher : c'est donc la page qui
 * l'envoie, par morceaux en base64 (fichierDebut / fichierMorceau /
 * fichierFin), et l'application qui l'ecrit dans Telechargements/Sonora.
 *
 * Les morceaux vont d'abord dans le cache de l'application : rien
 * d'inacheve n'apparait dans Telechargements si la page abandonne en route,
 * et sur Android 7 a 9 la demande d'autorisation a le temps d'aboutir
 * pendant l'envoi.
 */
final class Telechargements {

    /** Sous-dossier de Telechargements, pour tout ce que Sonora y range. */
    static final String DOSSIER = "Sonora";

    /** Au-dela, ce n'est plus un morceau de musique : on arrete d'ecrire. */
    private static final long TAILLE_MAX = 1024L * 1024 * 1024;

    /** Une page qui ouvrirait des envois sans les finir ne remplit pas le cache. */
    private static final int ENVOIS_MAX = 4;

    private static final class Envoi {
        final String nom;
        final String mime;
        final File temp;
        final OutputStream sortie;
        long ecrit = 0;

        Envoi(String nom, String mime, File temp) throws IOException {
            this.nom = nom;
            this.mime = mime;
            this.temp = temp;
            this.sortie = new FileOutputStream(temp);
        }
    }

    private final MainActivity activite;
    private final Map<String, Envoi> envois = new ConcurrentHashMap<String, Envoi>();
    private final SecureRandom hasard = new SecureRandom();

    Telechargements(MainActivity a) {
        this.activite = a;
    }

    String debut(String nom, String mime) {
        if (envois.size() >= ENVOIS_MAX) { return "refus:trop d'envois"; }
        String type = mimePropre(mime);
        String fichier = avecExtension(nomPropre(nom, "Sonora"), type);
        String id = Long.toHexString(hasard.nextLong() & Long.MAX_VALUE);
        try {
            File dossier = new File(activite.getCacheDir(), "envois");
            if (!dossier.isDirectory() && !dossier.mkdirs()) { return "refus:stockage"; }
            envois.put(id, new Envoi(fichier, type, new File(dossier, id)));
        } catch (IOException e) {
            return "refus:stockage";
        }
        // Android 7 a 9 : la question s'affiche des maintenant, pendant que
        // les morceaux arrivent, plutot qu'a la fin.
        activite.demanderStockage();
        return id;
    }

    String morceau(String id, String base64) {
        Envoi e = id == null ? null : envois.get(id);
        if (e == null) { return "erreur:envoi inconnu"; }
        byte[] octets;
        try {
            octets = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
        } catch (IllegalArgumentException mauvais) {
            abandonner(id);
            return "erreur:base64";
        }
        if (e.ecrit + octets.length > TAILLE_MAX) {
            abandonner(id);
            return "erreur:trop gros";
        }
        try {
            e.sortie.write(octets);
            e.ecrit += octets.length;
            return "ok";
        } catch (IOException x) {
            abandonner(id);
            return "erreur:ecriture";
        }
    }

    String fin(String id) {
        Envoi e = id == null ? null : envois.remove(id);
        if (e == null) { return "erreur:envoi inconnu"; }
        try {
            e.sortie.close();
            if (!activite.attendreStockage()) { return "erreur:permission"; }
            return "ok:" + publier(activite, e.temp, e.nom, e.mime);
        } catch (Throwable t) {
            return "erreur:ecriture";
        } finally {
            //noinspection ResultOfMethodCallIgnored
            e.temp.delete();
        }
    }

    /** La page part (ou l'activite se ferme) : les envois en cours sont perdus. */
    void oublier() {
        for (String id : envois.keySet()) { abandonner(id); }
    }

    private void abandonner(String id) {
        Envoi e = envois.remove(id);
        if (e == null) { return; }
        try { e.sortie.close(); } catch (IOException ignored) { }
        //noinspection ResultOfMethodCallIgnored
        e.temp.delete();
    }

    /**
     * Range un fichier complet dans Telechargements/Sonora et rend le nom
     * retenu. Sert aux envois de la page et aux telechargements YouTube
     * faits dans l'application (v2.6). Le fichier source n'est pas touche.
     */
    static String publier(Context c, File source, String nom, String mime) throws IOException {
        return Build.VERSION.SDK_INT >= 29
                ? publierMediaStore(c, source, nom, mime)
                : publierDossier(c, source, nom, mime);
    }

    /**
     * Android 10 et plus : MediaStore, sans autorisation. IS_PENDING cache le
     * fichier tant qu'il n'est pas complet ; MediaStore renomme lui-meme un
     * homonyme (« titre (1).mp3 »), on relit donc le nom qu'il a retenu.
     */
    @TargetApi(29)
    private static String publierMediaStore(Context ctx, File source, String nom, String mime) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, nom);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOSSIER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) { throw new IOException("MediaStore refuse"); }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = cr.openOutputStream(u)) {
            if (out == null) { throw new IOException("MediaStore sans flux"); }
            copier(in, out);
        } catch (IOException x) {
            cr.delete(u, null, null);
            throw x;
        }
        ContentValues fini = new ContentValues();
        fini.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, fini, null, null);
        try (Cursor c = cr.query(u, new String[] { MediaStore.MediaColumns.DISPLAY_NAME },
                null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) { return c.getString(0); }
        }
        return nom;
    }

    /** Android 7 a 9 : le dossier public, avec l'autorisation deja obtenue. */
    private static String publierDossier(Context ctx, File source, String nom, String mime) throws IOException {
        File dossier = dossierPublic();
        File cible = nomLibre(dossier, nom);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(cible)) {
            copier(in, out);
        }
        // Sans ca, le fichier n'apparait ni dans la galerie ni dans les
        // lecteurs avant le prochain redemarrage.
        MediaScannerConnection.scanFile(ctx, new String[] { cible.getPath() },
                new String[] { mime }, null);
        return cible.getName();
    }

    private static void copier(InputStream in, OutputStream out) throws IOException {
        byte[] tampon = new byte[65536];
        int lu;
        while ((lu = in.read(tampon)) > 0) { out.write(tampon, 0, lu); }
    }

    // -----------------------------------------------------------------
    //  Outils partages (YouTube, liens de telechargement)
    // -----------------------------------------------------------------

    static File dossierPublic() throws IOException {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DOSSIER);
        if (!d.isDirectory() && !d.mkdirs()) { throw new IOException("dossier"); }
        return d;
    }

    /**
     * « titre (2).m4a » si « titre.m4a » existe deja. Sur Android 7 a 9,
     * DownloadManager echoue au lieu de renommer ; a partir de 10 il renomme
     * seul, et ce test ne voit de toute facon pas les fichiers des autres.
     */
    static File nomLibre(File dossier, String nom) {
        File f = new File(dossier, nom);
        int point = nom.lastIndexOf('.');
        String base = point > 0 ? nom.substring(0, point) : nom;
        String ext = point > 0 ? nom.substring(point) : "";
        for (int n = 1; f.exists() && n < 1000; n++) {
            f = new File(dossier, base + " (" + n + ")" + ext);
        }
        return f;
    }

    /**
     * Un nom de fichier que tous les systemes de fichiers acceptent : ni
     * separateur, ni caractere de controle, ni point en tete (fichier cache),
     * et pas plus long que ce que FAT et ext4 tolerent avec une extension.
     */
    static String nomPropre(String brut, String repli) {
        String n = brut == null ? "" : brut.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        while (n.startsWith(".")) { n = n.substring(1).trim(); }
        if (n.length() > 120) { n = n.substring(0, 120).trim(); }
        return n.length() > 0 ? n : repli;
    }

    private static String mimePropre(String mime) {
        if (mime != null && mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) {
            return mime.toLowerCase(Locale.ROOT);
        }
        return "application/octet-stream";
    }

    /** « titre » + audio/mpeg -> « titre.mp3 », sauf s'il a deja la sienne. */
    private static String avecExtension(String nom, String mime) {
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        int point = nom.lastIndexOf('.');
        if (point > 0 && point < nom.length() - 1) {
            String actuelle = nom.substring(point + 1).toLowerCase(Locale.ROOT);
            if (ext == null || MimeTypeMap.getSingleton().hasExtension(actuelle)) { return nom; }
        }
        return ext == null ? nom : nom + "." + ext;
    }

    /**
     * Confie une adresse a DownloadManager, vers Telechargements/Sonora. Le
     * telephone telecharge lui-meme, avec sa connexion, reprend apres une
     * coupure et affiche sa propre notification de progression.
     */
    static long confier(Context c, String url, String fichier, String titre,
                        String description, String mime, String agent) throws IOException {
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        String nom = fichier;
        if (Build.VERSION.SDK_INT < 29) { nom = nomLibre(dossierPublic(), fichier).getName(); }
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, DOSSIER + "/" + nom);
        r.setTitle(titre);
        r.setDescription(description);
        r.setMimeType(mime);
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        if (Build.VERSION.SDK_INT < 29) {
            // Sinon les lecteurs de musique ne le voient pas avant longtemps.
            r.allowScanningByMediaScanner();
        }
        if (agent != null) { r.addRequestHeader("User-Agent", agent); }
        // Certains liens de la page ne servent le fichier qu'a une session
        // ouverte : on prete a DownloadManager les cookies de la WebView.
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) { r.addRequestHeader("Cookie", cookies); }
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        return dm.enqueue(r);
    }
}
