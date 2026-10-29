package bj.radio;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trouve le lien direct (fichier vidéo) d'une page Facebook, pour la lecture dans TV Kouamé.
 * Même méthode que « Télécharger Facebook » : lecture de la page puis recherche des liens SD / HD.
 * À appeler HORS du thread principal. Les résultats sont gardés 30 minutes (les liens Facebook expirent).
 */
final class FbLien {

    private FbLien() {}

    /** Lien direct + User-Agent à utiliser pour le lire. */
    static final class Direct {
        final String url, ua;
        /** Façons de lire la vidéo, de la plus simple à la plus complexe : {image(+son), son séparé ou null}. */
        final List<String[]> variantes;
        final long cree = System.currentTimeMillis();
        Direct(List<String[]> variantes, String ua) {
            this.variantes = variantes;
            this.url = variantes.get(0)[0];
            this.ua = ua;
        }
    }

    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String UA_BOT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";
    private static final String UA_TEL = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final String[] CLES_SD = {"browser_native_sd_url", "sd_src_no_ratelimit", "sd_src", "playable_url"};
    private static final String[] CLES_HD = {"browser_native_hd_url", "hd_src_no_ratelimit", "hd_src", "playable_url_quality_hd"};
    private static final Pattern PROG = Pattern.compile(
            "\"progressive_url\"\\s*:\\s*\"(https?:[^\"]+)\"(.{0,300}?)\"quality\"\\s*:\\s*\"(\\w+)\"", Pattern.DOTALL);
    private static final Pattern META = Pattern.compile("<meta[^>]+>");
    private static final Pattern CONTENT = Pattern.compile("content=\"([^\"]+)\"");
    private static final Pattern SAUT_JS = Pattern.compile("location\\.href\\s*=\\s*\"([^\"]+)\"");

    private static final Map<String, Direct> CACHE = new HashMap<>();

    /** Lien direct de la vidéo (qualité standard d'abord : moins de données), ou null si introuvable. */
    static synchronized Direct deja(String lien) {
        Direct d = CACHE.get(lien);
        if (d != null && System.currentTimeMillis() - d.cree > 30 * 60_000L) {
            CACHE.remove(lien);
            return null;
        }
        return d;
    }

    static synchronized void oublier(String lien) { CACHE.remove(lien); }

    static Direct resoudre(String lien) {
        Direct d = deja(lien);
        if (d != null) return d;
        String mobile = lien.replaceFirst("^https?://(www\\.)?facebook\\.com", "https://m.facebook.com");
        String[][] essais = {{lien, UA_PC}, {lien, UA_BOT}, {mobile, UA_TEL}, {mobile, UA_PC}};
        for (String[] e : essais) {
            try {
                String html = lirePage(e[0], e[1]);
                List<String[]> v = variantes(html);
                if (!v.isEmpty()) {
                    d = new Direct(v, e[1]);
                    synchronized (FbLien.class) { CACHE.put(lien, d); }
                    return d;
                }
            } catch (Exception ignore) {
                // essai suivant
            }
        }
        return null;
    }

    /**
     * Toutes les façons de lire la vidéo trouvées dans la page : qualité standard, haute qualité, puis flux séparés
     * image + son (cas des longues vidéos / films, que Facebook ne donne pas en un seul fichier).
     */
    private static List<String[]> variantes(String html) {
        List<String[]> res = new ArrayList<>();
        if (html == null) return res;
        String sd = premier(html, CLES_SD), hd = premier(html, CLES_HD);
        if (sd == null || hd == null) {
            Matcher m = PROG.matcher(html);
            while (m.find()) {
                String u = decoder(m.group(1));
                if ("HD".equalsIgnoreCase(m.group(3))) { if (hd == null) hd = u; }
                else if (sd == null) sd = u;
            }
        }
        if (sd == null && hd == null) {
            Matcher mt = META.matcher(html);
            while (mt.find()) {
                String tag = mt.group();
                if (!tag.contains("og:video")) continue;
                Matcher mc = CONTENT.matcher(tag);
                if (!mc.find()) continue;
                String u = decoder(mc.group(1));
                if (u.startsWith("http") && (u.contains(".mp4") || u.contains("fbcdn"))) { sd = u; break; }
            }
        }
        ajouter(res, sd, null);
        ajouter(res, hd, null);
        String[] dash = dash(html);
        if (dash != null) ajouter(res, dash[0], dash[1]);
        return res;
    }

    private static void ajouter(List<String[]> res, String video, String audio) {
        if (video == null) return;
        String v = nettoyer(video);
        String a = audio == null ? null : nettoyer(audio);
        for (String[] x : res) if (x[0].equals(v) && ((x[1] == null) == (a == null))) return;
        res.add(new String[]{v, a});
    }

    /** Enlève bytestart / byteend : ces liens ne désignent qu'un morceau ; sans eux on lit le fichier entier. */
    static String nettoyer(String url) {
        int q = url.indexOf('?');
        if (q < 0) return url;
        StringBuilder sb = new StringBuilder(url.substring(0, q));
        boolean premier = true;
        for (String p : url.substring(q + 1).split("&")) {
            if (p.startsWith("bytestart=") || p.startsWith("byteend=")) continue;
            sb.append(premier ? '?' : '&').append(p);
            premier = false;
        }
        return sb.toString();
    }

    private static final Pattern ENSEMBLE = Pattern.compile("<AdaptationSet([^>]*)>(.*?)</AdaptationSet>", Pattern.DOTALL);
    private static final Pattern REPRES = Pattern.compile("<Representation([^>]*)>(.*?)</Representation>", Pattern.DOTALL);
    private static final Pattern BASEURL = Pattern.compile("<BaseURL>\\s*([^<\\s]+)\\s*</BaseURL>");
    private static final Pattern HAUT = Pattern.compile("height=\"(\\d+)\"");
    private static final Pattern DEBIT = Pattern.compile("bandwidth=\"(\\d+)\"");

    /** Cherche dans le manifeste DASH de la page : une image (720 p au plus) et un son. Renvoie {image, son} ou null. */
    private static String[] dash(String html) {
        int i = html.indexOf("dash_manifest");
        if (i < 0) i = html.indexOf("<MPD");
        if (i < 0) return null;
        String bloc = html.substring(i, Math.min(html.length(), i + 120000));
        bloc = decoder(bloc).replace("\\\"", "\"").replace("\\u003C", "<").replace("\\u003E", ">");
        String video = null, son = null;
        int hautVideo = -1;
        long debitSon = -1;
        Matcher ens = ENSEMBLE.matcher(bloc);
        while (ens.find()) {
            boolean audio = ens.group(1).toLowerCase(java.util.Locale.ROOT).contains("audio");
            Matcher rep = REPRES.matcher(ens.group(2));
            while (rep.find()) {
                Matcher b = BASEURL.matcher(rep.group(2));
                if (!b.find()) continue;
                String url = decoder(b.group(1));
                String attrs = rep.group(1);
                if (audio || attrs.toLowerCase(java.util.Locale.ROOT).contains("audio")) {
                    Matcher d = DEBIT.matcher(attrs);
                    long deb = d.find() ? Long.parseLong(d.group(1)) : 0;
                    if (deb > debitSon) { debitSon = deb; son = url; }
                } else {
                    Matcher h = HAUT.matcher(attrs);
                    int haut = h.find() ? Integer.parseInt(h.group(1)) : 0;
                    boolean mieux = video == null || (haut <= 720 && (hautVideo > 720 || haut > hautVideo));
                    if (mieux) { hautVideo = haut; video = url; }
                }
            }
        }
        return video == null ? null : new String[]{video, son};
    }

    private static String lirePage(String adresse, String ua) throws Exception {
        String courant = adresse;
        for (int i = 0; i < 8; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(courant).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirection vide");
                courant = new URL(new URL(courant), loc).toString();
                continue;
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) { c.disconnect(); throw new Exception("http " + code); }
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0 && sb.length() < 6000000) sb.append(buf, 0, n);
            br.close();
            c.disconnect();
            String page = sb.toString();
            if (page.length() < 4000) {
                Matcher m = SAUT_JS.matcher(page);
                if (m.find()) {
                    courant = new URL(new URL(courant), decoder(m.group(1))).toString();
                    continue;
                }
            }
            return page;
        }
        throw new Exception("trop de redirections");
    }

    private static String premier(String html, String[] cles) {
        for (String cle : cles) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(cle) + "\"\\s*:\\s*\"(https?:[^\"]+)\"").matcher(html);
            if (m.find()) return decoder(m.group(1));
        }
        return null;
    }

    private static String decoder(String s) {
        Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String c = String.valueOf((char) Integer.parseInt(m.group(1), 16));
            m.appendReplacement(sb, Matcher.quoteReplacement(c));
        }
        m.appendTail(sb);
        return sb.toString().replace("\\/", "/").replace("&amp;", "&");
    }
}
