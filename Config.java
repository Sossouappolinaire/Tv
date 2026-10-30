package bj.radio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fichier de configuration (JSON) stocké dans la base : chaque ouverture de l'appli le relit, et toute nouvelle
 * publication s'applique toute seule à tous les utilisateurs. Une copie est gardée sur le téléphone (appli utilisable hors ligne).
 */
final class Config {

    private Config() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    static String date(long ms) {
        if (ms <= 0) return "date inconnue";
        return new SimpleDateFormat("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE).format(new Date(ms));
    }

    /** Dernière configuration reçue (copie locale), ou null s'il n'y en a pas. */
    static JSONObject contenu(Context c) {
        String s = p(c).getString("contenu", null);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Interroge la base. Renvoie vrai si une NOUVELLE configuration vient d'être enregistrée sur le téléphone.
     * À appeler hors du thread principal.
     */
    static boolean rafraichir(Context c) {
        JSONObject r = Supabase.configDerniere();
        if (r == null || !r.optBoolean("ok")) return false;
        SharedPreferences sp = p(c);
        if (r.optBoolean("aucune")) {
            if (sp.getLong("id", 0L) == 0L) return false;
            sp.edit().clear().apply();   // plus aucune configuration : l'appli reprend ses réglages d'origine
            Ui.theme(null);
            return true;
        }
        long id = r.optLong("id", 0L);
        JSONObject contenu = r.optJSONObject("contenu");
        if (id <= 0 || contenu == null || id == sp.getLong("id", 0L)) return false;
        sp.edit().putString("contenu", contenu.toString())
                .putLong("id", id)
                .putLong("cree_ms", r.optLong("cree_ms", 0L))
                .putString("note", r.optString("note", "").equals("null") ? "" : r.optString("note", ""))
                .putLong("applique_ms", System.currentTimeMillis())
                .apply();
        Ui.theme(contenu);
        return true;
    }

    /** « Dernière mise à jour » : numéro, date de création du fichier dans la base, date d'application. Null s'il n'y en a pas. */
    static String info(Context c) {
        SharedPreferences sp = p(c);
        long id = sp.getLong("id", 0L);
        if (id <= 0) return null;
        String note = sp.getString("note", "");
        return "Mise à jour n° " + id + (note.isEmpty() ? "" : " (" + note + ")")
                + "\n   fichier créé le " + date(sp.getLong("cree_ms", 0L))
                + "\n   appliquée sur ce téléphone le " + date(sp.getLong("applique_ms", 0L));
    }

    /** Nom de l'appli affiché dans l'appli (accueil, connexion) : clé "titre" de la configuration, sinon « Kouamé Play Box ». */
    static String titreApp(Context c) {
        JSONObject cfg = contenu(c);
        String t = cfg == null ? "" : cfg.optString("titre", "").trim();
        return t.isEmpty() ? "Kouamé Play Box" : t;
    }

    /** Annonce déjà vue (une annonce ne s'affiche qu'une fois par identifiant). */
    static String annonceVue(Context c) {
        return p(c).getString("annonce_vue", "");
    }

    static void annonceVue(Context c, String id) {
        p(c).edit().putString("annonce_vue", id).apply();
    }
}
