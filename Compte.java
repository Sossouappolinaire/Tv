package bj.radio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Compte utilisateur (inscription, connexion, mot de passe oublié) via Supabase Auth,
 * et enregistrement du profil dans la table « profils » (voir supabase_setup.sql).
 *
 * Toutes les méthodes réseau sont à appeler HORS du thread principal.
 */
final class Compte {

    private Compte() {}

    /** Résultat d'un appel : ok = succès, message = texte lisible à afficher. */
    static final class Resultat {
        final boolean ok;
        final String message;
        Resultat(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    private static final String PREFS = "compte";
    private static SharedPreferences prefs;

    /** À appeler une fois (App.onCreate). */
    static void init(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ session locale

    static boolean connecte() {
        return prefs != null && !prefs.getString("refresh", "").isEmpty();
    }

    static String nom() { return prefs == null ? "" : prefs.getString("nom", ""); }

    static String id() { return prefs == null ? "" : prefs.getString("id", ""); }

    static boolean admin() { return prefs != null && prefs.getBoolean("admin", false); }

    /** Interroge la base (est_admin) et mémorise la réponse. Hors thread principal. */
    static boolean verifierAdmin() {
        boolean oui = Supabase.estAdmin();
        if (prefs != null) prefs.edit().putBoolean("admin", oui).apply();
        return oui;
    }

    static String email() { return prefs == null ? "" : prefs.getString("email", ""); }

    /**
     * Jeton d'accès valide (renouvelé si besoin), ou null si personne n'est connecté.
     * Utilisé par Supabase.java pour que la base reconnaisse l'utilisateur.
     */
    static synchronized String jeton() {
        if (!connecte()) return null;
        long expire = prefs.getLong("expire", 0);
        if (System.currentTimeMillis() < expire - 60_000) return prefs.getString("jeton", null);
        try {
            JSONObject corps = new JSONObject().put("refresh_token", prefs.getString("refresh", ""));
            Rep r = appeler("POST", "/auth/v1/token?grant_type=refresh_token", corps, null);
            if (r.code == 200 && r.json != null && r.json.has("access_token")) {
                enregistrerSession(r.json);
                return prefs.getString("jeton", null);
            }
            // Session refusée par le serveur (compte supprimé, jeton révoqué) : on se déconnecte.
            if (r.code == 400 || r.code == 401 || r.code == 403) deconnecter();
        } catch (Exception ignore) {
            // pas de réseau : on garde la session, on réessaiera
        }
        return prefs.getString("jeton", null);
    }

    static void deconnecter() {
        if (prefs == null) return;
        prefs.edit().clear().apply();
        Securite.effacer();
    }

    private static void enregistrerSession(JSONObject j) {
        long dur = j.optLong("expires_in", 3600);
        SharedPreferences.Editor e = prefs.edit();
        e.putString("jeton", j.optString("access_token"));
        e.putString("refresh", j.optString("refresh_token"));
        e.putLong("expire", System.currentTimeMillis() + dur * 1000);
        JSONObject u = j.optJSONObject("user");
        if (u != null) {
            e.putString("id", u.optString("id"));
            if (u.optString("email").length() > 0) e.putString("email", u.optString("email"));
            JSONObject m = u.optJSONObject("user_metadata");
            if (m != null && m.optString("nom").length() > 0) e.putString("nom", m.optString("nom"));
        }
        e.apply();
    }

    // ------------------------------------------------------------------ actions

    static Resultat inscrire(String nom, String email, String motDePasse) {
        try {
            JSONObject corps = new JSONObject()
                    .put("email", email)
                    .put("password", motDePasse)
                    .put("data", new JSONObject().put("nom", nom));
            Rep r = appeler("POST", "/auth/v1/signup", corps, null);
            if (r.code >= 300) return new Resultat(false, erreur(r));
            if (r.json != null && r.json.has("access_token")) {
                enregistrerSession(r.json);
                prefs.edit().putString("nom", nom).apply();
                enregistrerProfil();
                return new Resultat(true, "Compte créé. Bienvenue " + nom + " !");
            }
            // Confirmation par email exigée par le projet Supabase.
            return new Resultat(false, "Compte créé ✔ Ouvrez le message reçu à " + email
                    + " pour confirmer votre adresse, puis connectez-vous.");
        } catch (Exception e) {
            return new Resultat(false, echec(e));
        }
    }

    static Resultat connecter(String email, String motDePasse) {
        try {
            JSONObject corps = new JSONObject().put("email", email).put("password", motDePasse);
            Rep r = appeler("POST", "/auth/v1/token?grant_type=password", corps, null);
            if (r.code != 200 || r.json == null || !r.json.has("access_token"))
                return new Resultat(false, erreur(r));
            enregistrerSession(r.json);
            enregistrerProfil();
            verifierAdmin();
            return new Resultat(true, "Connecté.");
        } catch (Exception e) {
            return new Resultat(false, echec(e));
        }
    }

    /** Envoie par email un code à 6 chiffres pour choisir un nouveau mot de passe. */
    static Resultat envoyerCode(String email) {
        try {
            Rep r = appeler("POST", "/auth/v1/recover", new JSONObject().put("email", email), null);
            if (r.code >= 300) return new Resultat(false, erreur(r));
            return new Resultat(true, "Un code vient d'être envoyé à " + email + ". Regardez aussi les courriers indésirables.");
        } catch (Exception e) {
            return new Resultat(false, echec(e));
        }
    }

    /** Vérifie le code reçu par email puis change le mot de passe ; l'utilisateur est ensuite connecté. */
    static Resultat changerMotDePasse(String email, String code, String nouveau) {
        try {
            JSONObject v = new JSONObject().put("type", "recovery").put("email", email).put("token", code);
            Rep r = appeler("POST", "/auth/v1/verify", v, null);
            if (r.code != 200 || r.json == null || !r.json.has("access_token"))
                return new Resultat(false, "Code incorrect ou expiré. Demandez un nouveau code.");
            String jeton = r.json.optString("access_token");
            Rep m = appeler("PUT", "/auth/v1/user", new JSONObject().put("password", nouveau), jeton);
            if (m.code >= 300) return new Resultat(false, erreur(m));
            enregistrerSession(r.json);
            enregistrerProfil();
            return new Resultat(true, "Mot de passe changé. Vous êtes connecté.");
        } catch (Exception e) {
            return new Resultat(false, echec(e));
        }
    }

    /**
     * Enregistre (ou met à jour) la ligne de l'utilisateur dans la table « profils » :
     * nom, email, date d'inscription et dernière connexion.
     */
    static void enregistrerProfil() {
        try {
            String jeton = prefs.getString("jeton", null);
            String id = prefs.getString("id", "");
            if (jeton == null || id.isEmpty()) return;
            JSONObject o = new JSONObject()
                    .put("id", id)
                    .put("nom", nom())
                    .put("email", email())
                    .put("derniere_connexion", iso(System.currentTimeMillis()));
            String corps = "[" + o + "]";
            HttpURLConnection c = ouvrir("POST", "/rest/v1/profils?on_conflict=id", jeton);
            c.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal");
            c.setDoOutput(true);
            c.getOutputStream().write(corps.getBytes(StandardCharsets.UTF_8));
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignore) {
            // le profil sera réenregistré à la prochaine connexion
        }
    }

    private static String echec(Exception e) {
        if ("CLE_MANQUANTE".equals(e.getMessage()))
            return "Base non reliée : collez la clé « anon public » de Supabase dans Supabase.java.";
        return "Connexion impossible : vérifiez internet.";
    }

    static String iso(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    // ------------------------------------------------------------------ HTTP

    private static final class Rep {
        int code;
        JSONObject json;
    }

    private static HttpURLConnection ouvrir(String methode, String chemin, String jeton) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(Supabase.URL_PROJET + chemin).openConnection();
        c.setRequestMethod(methode);
        c.setRequestProperty("apikey", Supabase.CLE);
        if (jeton != null) c.setRequestProperty("Authorization", "Bearer " + jeton);
        else if (Supabase.CLE.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer " + Supabase.CLE);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        return c;
    }

    private static Rep appeler(String methode, String chemin, JSONObject corps, String jeton) throws Exception {
        if (!Supabase.configure()) throw new IllegalStateException("CLE_MANQUANTE");
        HttpURLConnection c = ouvrir(methode, chemin, jeton);
        try {
            if (corps != null) {
                c.setDoOutput(true);
                c.getOutputStream().write(corps.toString().getBytes(StandardCharsets.UTF_8));
            }
            Rep r = new Rep();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                String t = bo.toString("UTF-8").trim();
                if (t.startsWith("{")) {
                    try { r.json = new JSONObject(t); } catch (Exception ignore) { r.json = null; }
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** Traduit l'erreur du serveur en message lisible en français. */
    private static String erreur(Rep r) {
        String code = "", msg = "";
        if (r.json != null) {
            code = r.json.optString("error_code", r.json.optString("code", ""));
            msg = r.json.optString("msg", r.json.optString("message", r.json.optString("error_description", "")));
        }
        String m = msg.toLowerCase();
        if (code.equals("invalid_credentials") || m.contains("invalid login")) return "Email ou mot de passe incorrect.";
        if (code.equals("email_not_confirmed") || m.contains("not confirmed")) return "Adresse email non confirmée : ouvrez le message de confirmation reçu par email.";
        if (code.equals("user_already_exists") || m.contains("already registered")) return "Un compte existe déjà avec cet email : connectez-vous.";
        if (code.equals("weak_password") || m.contains("password should")) return "Mot de passe trop faible : 6 caractères minimum.";
        if (code.equals("over_email_send_rate_limit") || code.equals("over_request_rate_limit") || r.code == 429)
            return "Trop de demandes. Patientez une minute puis réessayez.";
        if (code.equals("same_password") || m.contains("different from the old")) return "Choisissez un mot de passe différent de l'ancien.";
        if (code.equals("signup_disabled")) return "Les inscriptions sont fermées sur ce projet.";
        if (code.equals("validation_failed") || m.contains("unable to validate email")) return "Adresse email invalide.";
        if (r.code == 0 || r.code >= 500) return "Le serveur ne répond pas. Réessayez dans un instant.";
        return msg.isEmpty() ? "Erreur (http " + r.code + ")." : msg;
    }
}
