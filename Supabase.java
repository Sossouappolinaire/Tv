package bj.radio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Accès à la base Supabase (tables « films_facebook » et « profils », voir supabase_setup.sql).
 * Utilise l'API REST de Supabase directement : aucune bibliothèque à ajouter.
 *
 * IMPORTANT : CLE doit être la clé « anon public » (ou « publishable ») du projet, JAMAIS la clé « service_role ».
 * Supabase > Project Settings > API.
 */
final class Supabase {

    static final String URL_PROJET = "https://ooccarepvhiqireyxjdl.supabase.co";
    static final String CLE = "sb_publishable_jCxBMntWstbkdcRdsi4EVA_Tl-X4Jt6";

    private Supabase() {}

    /** Dernier message d'erreur lisible (pour l'écran « Films Facebook »). */
    static volatile String derniereErreur = "";

    static boolean configure() {
        return CLE != null && CLE.length() > 20 && !CLE.startsWith("COLLEZ");
    }

    // ---------- Outils d'affichage (partagés) ----------

    static String duree(long ms) {
        if (ms <= 0) return "durée inconnue";
        long s = (ms + 500) / 1000;
        long hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        if (hh > 0) return String.format(Locale.FRANCE, "%d h %02d min %02d s", hh, mm, ss);
        if (mm > 0) return String.format(Locale.FRANCE, "%d min %02d s", mm, ss);
        return ss + " s";
    }

    static String taille(long o) {
        if (o <= 0) return "?";
        if (o >= 1073741824L) return String.format(Locale.FRANCE, "%.2f Go", o / 1073741824.0);
        return String.format(Locale.FRANCE, "%.1f Mo", o / 1048576.0);
    }

    // ---------- Lien propre : on retire les paramètres de suivi (qui identifient la personne qui partage) ----------

    static String nettoyerLien(String lien) {
        try {
            int q = lien.indexOf('?');
            if (q < 0) return lien.endsWith("/") && lien.length() > 12 ? lien.substring(0, lien.length() - 1) : lien;
            String base = lien.substring(0, q);
            StringBuilder sb = new StringBuilder(base);
            boolean premier = true;
            for (String p : lien.substring(q + 1).split("&")) {
                String k = p.contains("=") ? p.substring(0, p.indexOf('=')) : p;
                if (k.equals("fbclid") || k.equals("mibextid") || k.equals("rdid") || k.equals("share_url")
                        || k.equals("ref") || k.equals("sfnsn") || k.startsWith("__") || k.equals("s")
                        || k.equals("extid") || k.equals("app")) continue;
                sb.append(premier ? '?' : '&').append(p);
                premier = false;
            }
            return sb.toString();
        } catch (Exception e) {
            return lien;
        }
    }

    // ---------- Miniature : image réduite (≈ 320 px) en base64 ----------

    static String miniature(String urlImage, String ua) {
        if (urlImage == null || !urlImage.startsWith("http")) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlImage).openConnection();
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Referer", "https://www.facebook.com/");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 4_000_000) bo.write(buf, 0, n);
            in.close();
            byte[] data = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            o.inSampleSize = Math.max(1, o.outWidth / 640);
            o.inJustDecodeBounds = false;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            int w = 320, h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
            Bitmap p = Bitmap.createScaledBitmap(b, w, h, true);
            ByteArrayOutputStream jo = new ByteArrayOutputStream();
            int qualite = 70;
            do {
                jo.reset();
                p.compress(Bitmap.CompressFormat.JPEG, qualite, jo);
                qualite -= 15;
            } while (jo.size() > 45000 && qualite > 20);
            return Base64.encodeToString(jo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- Envoi : appelé à chaque lien analysé (téléchargé ou non) ----------

    private static HttpURLConnection ouvrir(String chemin, String methode) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_PROJET + "/rest/v1/" + chemin).openConnection();
        c.setRequestMethod(methode);
        c.setRequestProperty("apikey", CLE);
        // Utilisateur connecté : son jeton prouve à la base qui il est (règles RLS « authenticated »).
        String jeton = Compte.jeton();
        if (jeton != null) c.setRequestProperty("Authorization", "Bearer " + jeton);
        else if (CLE.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer " + CLE);   // ancienne clé « anon » (JWT)
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        return c;
    }

    /** Ajoute la vidéo à la base partagée. Si le lien y est déjà, rien ne change. À appeler hors du thread principal. */
    static void partager(String lien, String titre, String urlImage, long dureeMs, long tailleSd, long tailleHd, String ua) {
        if (!configure() || lien == null || lien.isEmpty()) return;
        HttpURLConnection c = null;
        try {
            JSONObject o = new JSONObject();
            o.put("lien", nettoyerLien(lien));
            if (titre != null && !titre.isEmpty()) o.put("titre", titre.length() > 300 ? titre.substring(0, 300) : titre);
            String mini = miniature(urlImage, ua);
            if (mini != null) o.put("miniature", mini);
            if (dureeMs > 0) o.put("duree_ms", dureeMs);
            if (tailleSd > 0) o.put("taille_sd", tailleSd);
            if (tailleHd > 0) o.put("taille_hd", tailleHd);
            byte[] corps = new JSONArray().put(o).toString().getBytes(StandardCharsets.UTF_8);

            c = ouvrir("films_facebook?on_conflict=lien", "POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Prefer", "resolution=ignore-duplicates,return=minimal");
            c.setDoOutput(true);
            c.getOutputStream().write(corps);
            int code = c.getResponseCode();
            if (code >= 300) derniereErreur = "Envoi refusé (http " + code + ")";
        } catch (Exception e) {
            derniereErreur = "Envoi impossible : " + e.getClass().getSimpleName();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- Lecture : liste des films partagés ----------

    /** Renvoie les films (plus récents d'abord), ou null en cas d'erreur (voir derniereErreur). */
    static JSONArray lister(int decalage, int nombre, String recherche) {
        if (!configure()) {
            derniereErreur = "CLE_MANQUANTE";
            return null;
        }
        HttpURLConnection c = null;
        try {
            StringBuilder q = new StringBuilder("films_facebook?select=*&order=serie.asc.nullslast,episode.asc.nullslast,cree_le.desc&limit=" + nombre
                    + "&offset=" + decalage);
            if (recherche != null && !recherche.trim().isEmpty()) {
                q.append("&titre=ilike.*").append(java.net.URLEncoder.encode(recherche.trim().replace("*", ""), "UTF-8")).append('*');
            }
            c = ouvrir(q.toString(), "GET");
            int code = c.getResponseCode();
            if (code != 200) {
                String err = "";
                try {
                    InputStream e = c.getErrorStream();
                    if (e != null) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] b = new byte[2048];
                        int n = e.read(b);
                        if (n > 0) bo.write(b, 0, n);
                        err = bo.toString("UTF-8");
                    }
                } catch (Exception ignore) {
                    // pas de détail
                }
                derniereErreur = code == 404 || err.contains("films_facebook")
                        ? "TABLE_MANQUANTE" : "Erreur du serveur (http " + code + ") " + err;
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            derniereErreur = "";
            return new JSONArray(bo.toString("UTF-8"));
        } catch (Exception e) {
            derniereErreur = "Connexion impossible : vérifiez internet";
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- Administration (droits vérifiés côté base par est_admin(), voir supabase_setup.sql) ----------

    /** Appel REST générique avec le jeton de l'utilisateur. Renvoie le texte de la réponse, ou null (voir derniereErreur). */
    static String rest(String methode, String chemin, String corps, String prefer) {
        if (!configure()) { derniereErreur = "CLE_MANQUANTE"; return null; }
        HttpURLConnection c = null;
        try {
            c = ouvrir(chemin, methode);
            if (prefer != null) c.setRequestProperty("Prefer", prefer);
            if (corps != null) {
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.getOutputStream().write(corps.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String t = "";
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                t = bo.toString("UTF-8");
            }
            if (code >= 300) {
                derniereErreur = code == 409 ? "Ce lien existe déjà." : "Refusé par le serveur (http " + code + ")";
                return null;
            }
            derniereErreur = "";
            return t;
        } catch (Exception e) {
            derniereErreur = "Connexion impossible : vérifiez internet";
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Vrai si le compte connecté est administrateur. */
    static boolean estAdmin() {
        String r = rest("POST", "rpc/est_admin", "{}", null);
        return r != null && r.trim().equals("true");
    }

    /** Signale que l'utilisateur est actif (pour le compteur « en ligne »). */
    static void battement() {
        String id = Compte.id();
        if (id.isEmpty()) return;
        try {
            rest("PATCH", "profils?id=eq." + id,
                    new JSONObject().put("derniere_activite", Compte.iso(System.currentTimeMillis())).toString(),
                    "return=minimal");
        } catch (Exception ignore) {
            // réessayé au prochain battement
        }
    }

    /** Une vidéo vient d'être téléchargée avec succès. */
    static void compterTelechargement(String lien) {
        try {
            JSONObject o = new JSONObject();
            if (lien != null && !lien.isEmpty()) o.put("lien", nettoyerLien(lien));
            rest("POST", "telechargements", new JSONArray().put(o).toString(), "return=minimal");
        } catch (Exception ignore) {
            // compteur non essentiel
        }
    }

    static JSONObject statsAdmin() {
        try {
            String r = rest("POST", "rpc/stats_admin", "{}", null);
            return r == null ? null : new JSONObject(r);
        } catch (Exception e) {
            return null;
        }
    }

    static JSONArray comptes() {
        try {
            String r = rest("GET", "profils?select=nom,email,cree_le,derniere_connexion,derniere_activite&order=cree_le.desc&limit=500", null, null);
            return r == null ? null : new JSONArray(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ajoute un lien Facebook à la liste « Films Facebook » (titre, série et épisode facultatifs). */
    static boolean ajouterFilm(String lien, String titre, String serie, int episode) {
        try {
            JSONObject o = new JSONObject().put("lien", nettoyerLien(lien.trim()));
            if (!titre.isEmpty()) o.put("titre", titre.length() > 300 ? titre.substring(0, 300) : titre);
            if (!serie.isEmpty()) o.put("serie", serie.length() > 200 ? serie.substring(0, 200) : serie);
            if (episode > 0) o.put("episode", episode);
            return rest("POST", "films_facebook", new JSONArray().put(o).toString(), "return=minimal") != null;
        } catch (Exception e) {
            derniereErreur = "Envoi impossible";
            return false;
        }
    }
}
