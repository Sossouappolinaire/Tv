package bj.radio;

import android.net.Uri;

import java.util.Locale;
import java.util.regex.Pattern;

/** Reconnaît la plateforme d'un lien (Facebook, YouTube, TikTok) et raccourcit les titres. */
final class LienPlateforme {
    static final String FACEBOOK = "Facebook";
    static final String YOUTUBE = "YouTube";
    static final String TIKTOK = "TikTok";

    private static final String[] DOM_FB = {"facebook.com", "fb.watch", "fb.com", "fb.me"};
    private static final String[] DOM_YT = {"youtube.com", "youtu.be", "youtube-nocookie.com"};
    private static final String[] DOM_TT = {"tiktok.com"};

    private static final Pattern STATS = Pattern.compile(
            "(?i).*\\b(vues?|views?|réactions?|reactions?|likes?|j'aime|partages?|shares?|commentaires?)\\b.*");

    private LienPlateforme() { }

    /** Retourne FACEBOOK, YOUTUBE, TIKTOK, ou null si le lien n'est d'aucune de ces plateformes. */
    static String detecter(String lien) {
        if (lien == null) return null;
        String host;
        try {
            host = Uri.parse(lien.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (dans(host, DOM_FB)) return FACEBOOK;
        if (dans(host, DOM_YT)) return YOUTUBE;
        if (dans(host, DOM_TT)) return TIKTOK;
        return null;
    }

    private static boolean dans(String host, String[] domaines) {
        for (String d : domaines) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    /**
     * Titre de 5 mots au maximum : on enlève les compteurs (« 116 K vues · 4,3 K réactions »),
     * on garde la première phrase utile (avant le nom de la page), puis les premiers mots.
     */
    static String titreCourt(String titre, int maxMots) {
        if (titre == null) return null;
        String t = titre.trim();
        if (t.isEmpty()) return t;
        String[] morceaux = t.split("\\|");
        String choisi = null;
        for (String m : morceaux) {
            String s = m.trim();
            if (s.isEmpty() || STATS.matcher(s).matches()) continue;
            choisi = s;
            break;
        }
        if (choisi == null) choisi = morceaux[0].trim();
        choisi = choisi.replaceAll("[—–…]+", " ")
                .replaceAll("([,;:!?.])(\\S)", "$1 $2")
                .replaceAll("\\s+", " ")
                .trim();
        String[] mots = choisi.split(" ");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String mot : mots) {
            if (mot.isEmpty()) continue;
            if (n > 0) sb.append(' ');
            sb.append(mot);
            if (++n >= maxMots) break;
        }
        String r = sb.toString().replaceAll("[\\s,;:\\-–—.…·]+$", "");
        if (r.isEmpty()) return t.length() > 40 ? t.substring(0, 40) : t;
        return Character.toUpperCase(r.charAt(0)) + r.substring(1);
    }
}
