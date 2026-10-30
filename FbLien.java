package bj.radio;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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


    // =====================================================================
    //  Formats à télécharger (SD / HD) avec tailles vérifiées
    // =====================================================================

    /** Un format proposé au téléchargement : un seul fichier, ou image + son séparés. */
    static final class Format {
        final String nom;               // "sd" ou "hd"
        final int hauteur;              // 0 = inconnue
        final String url, audio, ua;    // audio != null : image et son séparés
        volatile long tailleV = -1;     // taille réelle du fichier image (ou complet), -1 = inconnue
        volatile long tailleA = -1;     // taille réelle du fichier son
        long estimation = -1;           // taille d'après le débit annoncé par Facebook (manifeste), -1 = inconnue
        boolean suspect;                // la taille réelle est trop forte pour cette résolution
        boolean viaManifeste;           // format pris dans le manifeste (vrai 360 p / 720 p) et non dans le lien « sd »

        Format(String nom, int hauteur, String url, String audio, String ua) {
            this.nom = nom;
            this.hauteur = hauteur;
            this.url = url;
            this.audio = audio;
            this.ua = ua;
        }

        /** Taille réelle si connue, sinon l'estimation du manifeste, sinon -1. */
        long taille() {
            if (tailleV > 0) return tailleV + (audio != null && tailleA > 0 ? tailleA : 0);
            return estimation;
        }

        boolean tailleReelle() { return tailleV > 0; }

        String resolution() { return hauteur > 0 ? hauteur + "p" : "résolution non indiquée"; }
    }

    private static final class Rep {
        int haut;
        long debit;
        String url;
    }

    private static final Pattern EFG = Pattern.compile("[?&]efg=([^&]+)");
    private static final Pattern TAG = Pattern.compile("\"vencode_tag\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern HAUTEUR = Pattern.compile("(\\d{3,4})p");

    /** Hauteur de la vidéo (ex. 360) lue dans le lien Facebook ; 0 si Facebook ne l'indique pas. */
    static int hauteurDe(String url) {
        try {
            Matcher m = EFG.matcher(url);
            if (!m.find()) return 0;
            String b = URLDecoder.decode(m.group(1), "UTF-8");
            byte[] raw = Base64.decode(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            Matcher t = TAG.matcher(new String(raw, StandardCharsets.UTF_8));
            if (!t.find()) return 0;
            Matcher h = HAUTEUR.matcher(t.group(1));
            return h.find() ? Integer.parseInt(h.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Vrai si le fichier pèse trop pour sa résolution : un vrai 360 p dépasse rarement 1,3 Mbit/s,
     * un 480 p 2 Mbit/s, un 720 p 4 Mbit/s. Sert à repérer un lien « SD » qui est en fait une meilleure qualité.
     */
    static boolean tropLourd(long octets, long dureeMs, int hauteur) {
        if (octets <= 0 || dureeMs < 60_000) return false;
        double mbps = octets * 8.0 / (dureeMs / 1000.0) / 1e6;
        int h = hauteur > 0 ? hauteur : 360;
        double plafond = h <= 360 ? 1.3 : h <= 480 ? 2.0 : h <= 720 ? 4.0 : 7.0;
        return mbps > plafond;
    }

    /**
     * Analyse la page et renvoie les formats à proposer (SD puis HD, absents si introuvables).
     * La taille est mesurée sur le serveur ; pour le SD, si le fichier « sd » pèse beaucoup trop pour du 360 p
     * et que le manifeste propose un vrai 360 p, c'est ce dernier qui est proposé.
     * À appeler HORS du thread principal.
     */
    /** Ce que la page Facebook contient : liens directs sd / hd et images + son du manifeste DASH. */
    private static final class Sources {
        String sd, hd, uaSd, uaHd, uaDash = UA_PC;
        final List<Rep> videos = new ArrayList<>();
        Rep son;
        final long cree = System.currentTimeMillis();
    }

    private static final Map<String, Sources> CACHE_SRC = new HashMap<>();

    private static Sources sources(String lien) {
        synchronized (FbLien.class) {
            Sources c = CACHE_SRC.get(lien);
            if (c != null && System.currentTimeMillis() - c.cree < 30 * 60_000L) return c;
        }
        String mobile = lien.replaceFirst("^https?://(www\\.)?facebook\\.com", "https://m.facebook.com");
        String[][] essais = {{lien, UA_PC}, {lien, UA_BOT}, {mobile, UA_TEL}, {mobile, UA_PC}};
        Sources r = new Sources();
        for (String[] e : essais) {
            try {
                String html = lirePage(e[0], e[1]);
                String[] p = progressifs(html);
                if (r.sd == null && p[0] != null) { r.sd = nettoyer(p[0]); r.uaSd = e[1]; }
                if (r.hd == null && p[1] != null) { r.hd = nettoyer(p[1]); r.uaHd = e[1]; }
                if (r.videos.isEmpty()) {
                    Rep s = dashToutes(html, r.videos);
                    if (!r.videos.isEmpty()) { r.son = s; r.uaDash = e[1]; }
                }
                if (r.sd != null && r.hd != null && !r.videos.isEmpty()) break;
            } catch (Exception ignore) {
                // essai suivant
            }
        }
        if (r.sd != null || r.hd != null || !r.videos.isEmpty()) {
            synchronized (FbLien.class) { CACHE_SRC.put(lien, r); }
        }
        return r;
    }

    /** {le plus grand ≤ 480 p (le « vrai » format bas), le plus grand entre 481 et 720 p} dans le manifeste ; éléments possiblement null. */
    private static Rep[] choisirReps(List<Rep> videos) {
        Rep leger = null, haut = null;
        for (Rep r : videos) {
            if (r.haut > 0 && r.haut <= 480) { if (leger == null || r.haut > leger.haut) leger = r; }
            else if (r.haut > 480 && r.haut <= 720) { if (haut == null || r.haut > haut.haut) haut = r; }
        }
        return new Rep[]{leger, haut};
    }

    /**
     * Formats pour LIRE, sans mesurer les tailles (rapide) : le vrai format bas d'abord (360 p du manifeste, avec son),
     * puis la haute qualité. Un seul élément si Facebook ne donne qu'une qualité.
     * À appeler HORS du thread principal.
     */
    static List<Format> formatsLecture(String lien) {
        Sources s = sources(lien);
        Rep[] reps = choisirReps(s.videos);
        Format bas = null;
        if (reps[0] != null && (s.son != null || s.sd == null)) bas = depuisManifeste("sd", reps[0], s.son, s.uaDash, 0);
        else if (s.sd != null) bas = new Format("sd", hauteurDe(s.sd), s.sd, null, s.uaSd);
        Format haut = null;
        if (s.hd != null) haut = new Format("hd", hauteurDe(s.hd), s.hd, null, s.uaHd);
        else if (reps[1] != null) haut = depuisManifeste("hd", reps[1], s.son, s.uaDash, 0);
        if (bas == null) { bas = haut; haut = null; }
        List<Format> res = new ArrayList<>();
        if (bas != null) res.add(bas);
        if (haut != null) res.add(haut);
        return res;
    }

    static List<Format> formats(String lien, long dureeMs) {
        Sources src = sources(lien);
        String sd = src.sd, hd = src.hd, uaSd = src.uaSd, uaHd = src.uaHd, uaDash = src.uaDash;
        List<Rep> videos = src.videos;
        Rep son = src.son;

        // Vrais formats du manifeste : le plus grand ≤ 480 p (« 360 p ») et le plus grand entre 481 et 720 p
        Rep[] reps = choisirReps(videos);
        Rep leger = reps[0], haut = reps[1];

        Format fSd = sd == null ? null : new Format("sd", hauteurDe(sd), sd, null, uaSd);
        Format fHd = hd == null ? null : new Format("hd", hauteurDe(hd), hd, null, uaHd);
        Format mSd = leger == null ? null : depuisManifeste("sd", leger, son, uaDash, dureeMs);
        Format mHd = haut == null ? null : depuisManifeste("hd", haut, son, uaDash, dureeMs);

        // 1) mesure des fichiers « sd » et « hd » du lien (en parallèle)
        sonderTout(fSd, fHd);

        // 2) le « sd » est-il vraiment un 360 p ? sinon on bascule sur le 360 p du manifeste
        Format choixSd = fSd;
        if (fSd != null && tropLourd(fSd.tailleV, dureeMs, fSd.hauteur)) {
            fSd.suspect = true;
            if (mSd != null) {
                sonderTout(mSd);
                choixSd = mSd;
            }
        } else if (fSd == null && mSd != null) {
            sonderTout(mSd);
            choixSd = mSd;
        }
        Format choixHd = fHd;
        if (choixHd == null && mHd != null) {
            sonderTout(mHd);
            choixHd = mHd;
        }
        if (choixHd != null && choixSd != null && choixHd.url.equals(choixSd.url)) choixHd = null;

        List<Format> res = new ArrayList<>();
        if (choixSd != null) res.add(choixSd);
        if (choixHd != null) res.add(choixHd);
        return res;
    }

    private static Format depuisManifeste(String nom, Rep v, Rep son, String ua, long dureeMs) {
        Format f = new Format(nom, v.haut, nettoyer(v.url), son == null ? null : nettoyer(son.url), ua);
        f.viaManifeste = true;
        long debit = v.debit + (son == null ? 0 : son.debit);
        if (debit > 0 && dureeMs > 0) f.estimation = dureeMs / 1000 * debit / 8;
        return f;
    }

    /** Mesure les tailles de plusieurs formats en même temps. */
    private static void sonderTout(Format... fs) {
        List<Thread> ts = new ArrayList<>();
        for (final Format f : fs) {
            if (f == null) continue;
            Thread t = new Thread(() -> {
                f.tailleV = taille(f.url, f.ua);
                if (f.audio != null) f.tailleA = taille(f.audio, f.ua);
            });
            t.start();
            ts.add(t);
        }
        for (Thread t : ts) {
            try { t.join(40000); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        }
    }

    /** Liens « sd » et « hd » directs trouvés dans la page : {sd, hd} (valeurs possiblement null). */
    private static String[] progressifs(String html) {
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
        return new String[]{sd, hd};
    }

    /** Toutes les images du manifeste DASH (hauteur, débit annoncé, lien) dans « videos » ; renvoie le meilleur son. */
    private static Rep dashToutes(String html, List<Rep> videos) {
        int i = html.indexOf("dash_manifest");
        if (i < 0) i = html.indexOf("<MPD");
        if (i < 0) return null;
        String bloc = html.substring(i, Math.min(html.length(), i + 120000));
        bloc = decoder(bloc).replace("\\\"", "\"").replace("\\u003C", "<").replace("\\u003E", ">");
        Rep son = null;
        Matcher ens = ENSEMBLE.matcher(bloc);
        while (ens.find()) {
            boolean audio = ens.group(1).toLowerCase(java.util.Locale.ROOT).contains("audio");
            Matcher rep = REPRES.matcher(ens.group(2));
            while (rep.find()) {
                Matcher b = BASEURL.matcher(rep.group(2));
                if (!b.find()) continue;
                String attrs = rep.group(1);
                Rep r = new Rep();
                r.url = decoder(b.group(1));
                Matcher d = DEBIT.matcher(attrs);
                r.debit = d.find() ? Long.parseLong(d.group(1)) : 0;
                if (audio || attrs.toLowerCase(java.util.Locale.ROOT).contains("audio")) {
                    if (son == null || r.debit > son.debit) son = r;
                } else {
                    Matcher h = HAUT.matcher(attrs);
                    r.haut = h.find() ? Integer.parseInt(h.group(1)) : 0;
                    videos.add(r);
                }
            }
        }
        return son;
    }

    /**
     * Taille réelle du fichier sans le télécharger : 1 seul octet demandé (Range: bytes=0-0),
     * le total est lu dans « Content-Range: bytes 0-0/TOTAL ». Renvoie -1 si impossible.
     */
    static long taille(String adresse, String ua) {
        for (int essai = 0; essai < 2; essai++) {
            String courant = adresse;
            HttpURLConnection c = null;
            try {
                for (int i = 0; i < 6; i++) {
                    c = (HttpURLConnection) new URL(courant).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("User-Agent", essai == 0 && ua != null ? ua : UA_PC);
                    if (essai == 0) c.setRequestProperty("Referer", "https://www.facebook.com/");
                    c.setRequestProperty("Accept", "*/*");
                    c.setRequestProperty("Accept-Encoding", "identity");
                    c.setRequestProperty("Range", "bytes=0-0");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        c = null;
                        if (loc == null) break;
                        courant = new URL(new URL(courant), loc).toString();
                        continue;
                    }
                    if (code == 206) {
                        String cr = c.getHeaderField("Content-Range");
                        if (cr != null && cr.contains("/")) {
                            String tot = cr.substring(cr.lastIndexOf('/') + 1).trim();
                            if (!tot.equals("*")) return Long.parseLong(tot);
                        }
                    } else if (code == 200) {
                        long len = c.getContentLengthLong();
                        if (len > 0) return len;
                    }
                    break;
                }
            } catch (Exception ignore) {
                // on tente l'autre méthode
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return -1;
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
