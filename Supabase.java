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

    /** Dernier message d'erreur lisible (pour l'écran « Films partagés »). */
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

    /** Dernière raison pour laquelle l'image n'a pas pu être récupérée (affichée à l'écran). */
    static volatile String erreurImage = "";

    private static final String UA_TEL_IMG = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    /** Télécharge l'image brute ; 3 essais avec des en-têtes différents (Facebook refuse parfois selon le navigateur annoncé). */
    private static byte[] telechargerImage(String urlImage, String ua) {
        String[][] essais = {
            { ua, "https://www.facebook.com/" },
            { UA_TEL_IMG, "https://m.facebook.com/" },
            { UA_TEL_IMG, null }
        };
        for (String[] e : essais) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(urlImage).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", e[0] == null ? UA_TEL_IMG : e[0]);
                c.setRequestProperty("Accept", "image/avif,image/webp,image/jpeg,image/*,*/*;q=0.8");
                if (e[1] != null) c.setRequestProperty("Referer", e[1]);
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                int code = c.getResponseCode();
                if (code != 200) {
                    erreurImage = "Facebook a refusé l'image (http " + code + ")";
                    continue;
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 4_000_000) bo.write(buf, 0, n);
                in.close();
                if (bo.size() > 0) return bo.toByteArray();
                erreurImage = "L'image reçue est vide";
            } catch (Exception ex) {
                erreurImage = "Image inaccessible (" + ex.getClass().getSimpleName() + ")";
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    /** Renvoie l'image réduite en JPEG base64, ou null (la raison est alors dans erreurImage). */
    static String miniature(String urlImage, String ua) {
        if (urlImage == null || !urlImage.startsWith("http")) {
            erreurImage = "Facebook n'a donné aucune image pour cette page";
            return null;
        }
        try {
            byte[] data = telechargerImage(urlImage, ua);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) {
                erreurImage = "Le fichier reçu n'est pas une image";
                return null;
            }
            o.inSampleSize = Math.max(1, o.outWidth / 640);
            o.inJustDecodeBounds = false;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) {
                erreurImage = "Image illisible";
                return null;
            }
            int w = 320, h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
            Bitmap p = Bitmap.createScaledBitmap(b, w, h, true);
            ByteArrayOutputStream jo = new ByteArrayOutputStream();
            int qualite = 70;
            do {
                jo.reset();
                p.compress(Bitmap.CompressFormat.JPEG, qualite, jo);
                qualite -= 15;
            } while (jo.size() > 45000 && qualite > 20);
            erreurImage = "";
            return Base64.encodeToString(jo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            erreurImage = "Image impossible à préparer (" + e.getClass().getSimpleName() + ")";
            return null;
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
        partager(lien, titre, urlImage, null, dureeMs, tailleSd, tailleHd, ua);
    }

    /** Idem, avec une miniature déjà préparée (base64) : évite de retélécharger l'image. */
    static void partager(String lien, String titre, String urlImage, String miniPrete, long dureeMs, long tailleSd, long tailleHd, String ua) {
        partager(lien, titre, urlImage, miniPrete, dureeMs, tailleSd, tailleHd, ua, null, null);
    }

    /** Idem, avec la catégorie (serie, film ou chanson) et la plateforme (Facebook, YouTube, TikTok). Le titre est limité à 5 mots. */
    static void partager(String lien, String titre, String urlImage, String miniPrete, long dureeMs, long tailleSd, long tailleHd, String ua,
                         String categorie, String plateforme) {
        if (!configure() || lien == null || lien.isEmpty()) return;
        titre = LienPlateforme.titreCourt(titre, 5);
        HttpURLConnection c = null;
        try {
            JSONObject o = new JSONObject();
            o.put("lien", nettoyerLien(lien));
            if (categorie != null && !categorie.isEmpty()) o.put("categorie", categorie);
            if (plateforme != null && !plateforme.isEmpty()) o.put("plateforme", plateforme);
            if (titre != null && !titre.isEmpty()) o.put("titre", titre.length() > 300 ? titre.substring(0, 300) : titre);
            String mini = miniPrete != null ? miniPrete : miniature(urlImage, ua);
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
            // La vidéo existait peut-être déjà SANS image : on complète seulement si la miniature est vide
            // (nécessite la section 7 de supabase_setup.sql ; sans elle, l'envoi est simplement refusé et ignoré).
            completerChamps(nettoyerLien(lien), titre, mini);
        } catch (Exception e) {
            derniereErreur = "Envoi impossible : " + e.getClass().getSimpleName();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Complète le titre et/ou l'image d'une vidéo déjà enregistrée sans eux (jamais d'écrasement : filtre « est vide »). */
    private static void completerChamps(String lienPropre, String titre, String mini) {
        String avant = derniereErreur;
        try {
            String lienF = "lien=eq." + java.net.URLEncoder.encode(lienPropre, "UTF-8");
            if (mini != null) {
                rest("PATCH", "films_facebook?" + lienF + "&miniature=is.null",
                        new JSONObject().put("miniature", mini).toString(), "return=minimal");
            }
            if (titre != null && !titre.isEmpty()) {
                String t = titre.length() > 300 ? titre.substring(0, 300) : titre;
                rest("PATCH", "films_facebook?" + lienF + "&titre=is.null",
                        new JSONObject().put("titre", t).toString(), "return=minimal");
            }
        } catch (Exception ignore) {
            // pas grave : ce sera complété une prochaine fois
        }
        derniereErreur = avant;   // un refus ici (section 7 non appliquée) ne doit pas masquer l'état de l'envoi
    }

    // ---------- Lecture : liste des films partagés ----------

    /** Renvoie les films (plus récents d'abord), ou null en cas d'erreur (voir derniereErreur). */
    static JSONArray lister(int decalage, int nombre, String recherche) {
        return lister(decalage, nombre, recherche, null);
    }

    /** Idem, filtré par catégorie (serie, film ou chanson) ; null ou vide = toutes. */
    static JSONArray lister(int decalage, int nombre, String recherche, String categorie) {
        if (!configure()) {
            derniereErreur = "CLE_MANQUANTE";
            return null;
        }
        HttpURLConnection c = null;
        try {
            StringBuilder q = new StringBuilder("films_facebook?select=*&order=serie.asc.nullslast,episode.asc.nullslast,cree_le.desc&limit=" + nombre
                    + "&offset=" + decalage);
            if (categorie != null && categorie.matches("serie|film|chanson")) q.append("&categorie=eq.").append(categorie);
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

    /** Comptes avec leur numéro de série (fonction admin_comptes, réservée à l'administrateur). */
    static JSONArray comptesAdmin() {
        try {
            String r = rest("POST", "rpc/admin_comptes", "{}", null);
            return r == null ? null : new JSONArray(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Attribue un nouveau numéro de série au compte ; renvoie la série, ou null. */
    static String adminNouvelleSerie(String idCompte) {
        try {
            String r = rest("POST", "rpc/admin_nouvelle_serie", new JSONObject().put("p_uid", idCompte).toString(), null);
            return r == null ? null : new JSONObject(r).optString("serie", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Définit un nouveau mot de passe pour le compte (les sessions ouvertes sont fermées). */
    static boolean adminChangerMotDePasse(String idCompte, String nouveau) {
        try {
            String r = rest("POST", "rpc/admin_changer_mot_de_passe",
                    new JSONObject().put("p_uid", idCompte).put("p_nouveau", nouveau).toString(), null);
            return r != null && new JSONObject(r).optBoolean("ok");
        } catch (Exception e) {
            return false;
        }
    }

    /** Planning de TV Kouamé : heure du serveur (maintenant_ms) + programmes dans l'ordre. */
    static JSONObject tvPlanning() {
        try {
            String r = rest("POST", "rpc/tv_planning", "{}", null);
            return r == null ? null : new JSONObject(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Admin : remplit automatiquement la journée de TV Kouamé avec les films qui ont une durée. */
    static JSONObject adminTvGenerer(String canal) {
        try {
            String r = rest("POST", "rpc/admin_tv_generer", new JSONObject().put("p_canal", canal).toString(), null);
            return r == null ? null : new JSONObject(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Admin : programme du jour de TV Kouamé (objet avec « programmes »), ou null. */
    static JSONObject adminTvProgramme() {
        try {
            String r = rest("POST", "rpc/admin_tv_programme", "{}", null);
            return r == null ? null : new JSONObject(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Admin : vidéos filtrées par catégorie (null = toutes) ; exclus = seulement celles retirées de la TV. */
    static JSONArray adminTvVideos(String categorie, boolean exclus) {
        try {
            JSONObject o = new JSONObject().put("p_exclus", exclus);
            if (categorie != null && !categorie.isEmpty()) o.put("p_cat", categorie);
            String r = rest("POST", "rpc/admin_tv_videos", o.toString(), null);
            return r == null ? null : new JSONArray(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Admin : change la catégorie d'une vidéo (serie, film, chanson). Pour une série : nom et numéro d'épisode. Renvoie "" si c'est fait, sinon un code d'erreur. */
    static String adminVideoModifier(String id, String categorie, String serie, int episode) {
        try {
            JSONObject o = new JSONObject().put("p_id", id).put("p_categorie", categorie);
            if (serie != null && !serie.isEmpty()) o.put("p_serie", serie);
            if (episode > 0) o.put("p_episode", episode);
            String r = rest("POST", "rpc/admin_video_modifier", o.toString(), null);
            if (r == null) return "reseau";
            JSONObject j = new JSONObject(r);
            return j.optBoolean("ok") ? "" : j.optString("erreur", "refuse");
        } catch (Exception e) {
            return "reseau";
        }
    }

    /** Admin : retire une vidéo de la TV (illisible) ou la remet. */
    static boolean adminTvExclure(String id, boolean exclu) {
        try {
            String r = rest("POST", "rpc/admin_tv_exclure", new JSONObject().put("p_id", id).put("p_exclu", exclu).toString(), null);
            return r != null && new JSONObject(r).optBoolean("ok");
        } catch (Exception e) {
            return false;
        }
    }

    static boolean adminTvVider() {
        String r = rest("POST", "rpc/admin_tv_vider", "{}", null);
        return r != null;
    }

    /** Ajoute un lien Facebook à la liste « Films partagés » (titre, série et épisode facultatifs). */
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
