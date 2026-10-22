package com.sonora.app;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException;
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * LE TELEPHONE PARLE A YOUTUBE LUI-MEME (v2.5).
 *
 * NewPipeExtractor sait lire une page YouTube et en tirer l'adresse directe
 * du son ou de la video. Il ne fait AUCUNE requete lui-meme : il les confie a
 * ce « Downloader ». Celui-ci passe par HttpURLConnection, deja present dans
 * Android : pas d'OkHttp, rien de plus a embarquer.
 *
 * Tout l'interet est que la demande parte du telephone, avec SA connexion :
 * YouTube refuse de plus en plus souvent les adresses de serveurs (« confirme
 * que tu n'es pas un robot »), rarement celle d'un abonne mobile ou d'une box.
 *
 * Aucune classe Android ici, volontairement : l'essai sur ordinateur
 * (essai-jvm, voir le compte rendu de la v2.5) compile CE fichier tel quel,
 * pour juger la bibliotheque et ce Downloader sans telephone.
 */
final class PasserelleNewPipe extends Downloader {

    /**
     * Identite par defaut, celle d'un navigateur de bureau courant (c'est
     * aussi celle de NewPipe). Les requetes pour lesquelles YouTube attend un
     * client precis (Android, iOS) portent deja leur propre identite.
     */
    static final String AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private static boolean pret = false;

    /** NewPipe garde son Downloader en statique : une seule mise en place. */
    static synchronized void initialiser() {
        if (pret) { return; }
        NewPipe.init(new PasserelleNewPipe(), new Localization("fr", "FR"), new ContentCountry("FR"));
        pret = true;
    }

    @Override
    public Response execute(Request requete) throws IOException, ReCaptchaException {
        HttpURLConnection co = (HttpURLConnection) new URL(requete.url()).openConnection();
        try {
            co.setConnectTimeout(15000);
            co.setReadTimeout(30000);
            co.setInstanceFollowRedirects(true);
            // L'application installe un cache HTTP pour les pochettes de la
            // notification. Une reponse YouTube rejouee depuis ce cache porte
            // des adresses deja perimees : on ne s'en sert jamais ici.
            co.setUseCaches(false);
            co.setRequestMethod(requete.httpMethod());

            boolean agent = false;
            for (Map.Entry<String, List<String>> e : requete.headers().entrySet()) {
                if ("User-Agent".equalsIgnoreCase(e.getKey())) { agent = true; }
                for (String v : e.getValue()) { co.addRequestProperty(e.getKey(), v); }
            }
            if (!agent) { co.setRequestProperty("User-Agent", AGENT); }

            byte[] corps = requete.dataToSend();
            if (corps != null) {
                co.setDoOutput(true);
                co.setFixedLengthStreamingMode(corps.length);
                try (OutputStream sortie = co.getOutputStream()) { sortie.write(corps); }
            }

            int code = co.getResponseCode();
            // Ce que YouTube renvoie quand il veut un captcha : NewPipe attend
            // cette exception precise pour le dire, pas un simple code 429.
            if (code == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", requete.url());
            }

            Map<String, List<String>> entetes = new HashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> e : co.getHeaderFields().entrySet()) {
                if (e.getKey() != null) { entetes.put(e.getKey(), e.getValue()); }
            }
            return new Response(code, co.getResponseMessage(), entetes,
                    lireCorps(co, code), co.getURL().toString());
        } finally {
            co.disconnect();
        }
    }

    /**
     * Android ne decompresse tout seul que s'il a lui-meme demande gzip ; si
     * la bibliotheque l'a demande, ou sur un ordinateur, c'est a nous de le
     * faire, sinon NewPipe lirait du binaire au lieu du JSON.
     */
    private static String lireCorps(HttpURLConnection co, int code) throws IOException {
        InputStream brut = code >= 400 ? co.getErrorStream() : co.getInputStream();
        if (brut == null) { return ""; }
        InputStream in = "gzip".equalsIgnoreCase(co.getContentEncoding())
                ? new GZIPInputStream(brut) : brut;
        try {
            ByteArrayOutputStream tout = new ByteArrayOutputStream();
            byte[] tampon = new byte[16384];
            int lu;
            while ((lu = in.read(tampon)) > 0) { tout.write(tampon, 0, lu); }
            return tout.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    // -----------------------------------------------------------------
    //  Choix du flux
    // -----------------------------------------------------------------

    /** Ce qu'il faut a DownloadManager pour aller chercher le fichier. */
    static final class Flux {
        final String url;
        final String extension;
        final String mime;
        /** L'identite que googlevideo attend pour CETTE adresse. */
        final String agent;

        Flux(String url, String extension, String mime, String agent) {
            this.url = url;
            this.extension = extension;
            this.mime = mime;
            this.agent = agent;
        }
    }

    /**
     * Extrait la video et choisit le flux a telecharger, ou null si aucun ne
     * convient. Bloquant (plusieurs requetes) : jamais sur le fil d'affichage.
     */
    static Flux trouver(String video, boolean audio) throws IOException, ExtractionException {
        initialiser();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube,
                "https://www.youtube.com/watch?v=" + video);
        Stream choisi = audio ? meilleurSon(info.getAudioStreams())
                              : meilleureVideo(info.getVideoStreams());
        if (choisi == null) { return null; }
        MediaFormat f = choisi.getFormat();
        String url = choisi.getContent();
        return new Flux(url, f.getSuffix(), f.getMimeType(), agentPour(url));
    }

    /** Un fichier qu'on peut demander d'un seul tenant, a une seule adresse. */
    private static boolean telechargeable(Stream s) {
        return s.isUrl() && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                && s.getFormat() != null;
    }

    /**
     * Le meilleur son en M4A : c'est le format que tous les lecteurs, et la
     * page elle-meme, savent relire. A defaut, le meilleur disponible. Les
     * pistes doublees ou audiodecrites passent apres la piste d'origine.
     */
    private static AudioStream meilleurSon(List<AudioStream> liste) {
        AudioStream m4a = null, autre = null;
        for (AudioStream s : liste) {
            if (!telechargeable(s)) { continue; }
            AudioTrackType t = s.getAudioTrackType();
            if (t != null && t != AudioTrackType.ORIGINAL) { continue; }
            if (s.getFormat() == MediaFormat.M4A) {
                if (m4a == null || s.getAverageBitrate() > m4a.getAverageBitrate()) { m4a = s; }
            } else if (autre == null || s.getAverageBitrate() > autre.getAverageBitrate()) {
                autre = s;
            }
        }
        if (m4a != null || autre != null) { return m4a != null ? m4a : autre; }
        // Aucune piste d'origine marquee comme telle : on prend ce qu'il y a.
        for (AudioStream s : liste) {
            if (telechargeable(s) && (autre == null || s.getAverageBitrate() > autre.getAverageBitrate())) {
                autre = s;
            }
        }
        return autre;
    }

    /**
     * La meilleure video AVEC son (« progressive »), en MP4. YouTube n'en
     * sert plus guere qu'une, en 360p : au-dela, image et son sont separes et
     * il faudrait les remonter sur le telephone.
     */
    private static VideoStream meilleureVideo(List<VideoStream> liste) {
        VideoStream mp4 = null, autre = null;
        for (VideoStream s : liste) {
            if (!telechargeable(s) || s.isVideoOnly()) { continue; }
            if (s.getFormat() == MediaFormat.MPEG_4) {
                if (mp4 == null || s.getHeight() > mp4.getHeight()) { mp4 = s; }
            } else if (autre == null || s.getHeight() > autre.getHeight()) {
                autre = s;
            }
        }
        return mp4 != null ? mp4 : autre;
    }

    /**
     * googlevideo verifie que celui qui telecharge ressemble au client qui a
     * obtenu l'adresse (le parametre c= de l'URL) : une adresse obtenue
     * « comme l'appli Android » demandee avec une identite de navigateur
     * peut etre refusee.
     */
    static String agentPour(String url) {
        if (YoutubeParsingHelper.isAndroidStreamingUrl(url)) {
            return YoutubeParsingHelper.getAndroidUserAgent(null);
        }
        if (YoutubeParsingHelper.isIosStreamingUrl(url)) {
            return YoutubeParsingHelper.getIosUserAgent(null);
        }
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            return YoutubeParsingHelper.getVisionOsUserAgent(null);
        }
        return AGENT;
    }

    /** La phrase montree a la personne, en francais et sans jargon. */
    static String messagePour(Throwable t) {
        if (t instanceof SignInConfirmNotBotException || t instanceof ReCaptchaException) {
            return "YouTube a refusé l'extraction : réessaie plus tard ou mets à jour l'application";
        }
        if (t instanceof AgeRestrictedContentException) {
            return "Vidéo réservée aux adultes : YouTube la refuse sans compte";
        }
        if (t instanceof GeographicRestrictionException
                || t instanceof UnsupportedContentInCountryException) {
            return "Vidéo bloquée dans ce pays";
        }
        if (t instanceof PaidContentException || t instanceof YoutubeMusicPremiumContentException) {
            return "Vidéo payante : impossible de la télécharger";
        }
        if (t instanceof PrivateContentException) {
            return "Vidéo privée";
        }
        if (t instanceof ContentNotAvailableException) {
            return "Vidéo indisponible";
        }
        if (t instanceof IOException) {
            return "YouTube ne répond pas : vérifie la connexion et réessaie";
        }
        return "YouTube a refusé l'extraction : réessaie plus tard ou mets à jour l'application";
    }
}
