package bj.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Classement partagé des jeux (table « scores_jeux », voir section9_scores_jeux.sql).
 * La base ne garde que le meilleur score de chaque joueur pour chaque jeu ; tous les joueurs connectés le voient.
 */
final class ScoresJeux {

    private static final ConcurrentHashMap<String, String> MEILLEURS = new ConcurrentHashMap<>();

    private ScoresJeux() {}

    /** « Nom — score » du meilleur joueur d'un jeu, tel que lu la dernière fois (vide si inconnu). */
    static String meilleur(String jeu) {
        String s = MEILLEURS.get(jeu);
        return s == null ? "" : s;
    }

    /** Envoie un score à la base (en arrière-plan). Sans compte ou sans internet : rien ne se passe, il sera renvoyé plus tard. */
    static void envoyer(String jeu, int score) {
        if (score <= 0 || !Compte.connecte()) return;
        new Thread(() -> {
            if (envoyerSync(jeu, score)) actualiser(jeu);
        }).start();
    }

    /** Même chose, mais à appeler depuis un thread d'arrière-plan. */
    static boolean envoyerSync(String jeu, int score) {
        if (score <= 0 || !Compte.connecte()) return false;
        try {
            String r = Supabase.rest("POST", "rpc/enregistrer_score",
                    new JSONObject().put("p_jeu", jeu).put("p_score", score).toString(), null);
            return r != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Les n meilleurs scores du jeu (nom, score). À appeler hors du thread principal ; null si pas de réponse. */
    static JSONArray classement(String jeu, int n) {
        try {
            String r = Supabase.rest("GET", "scores_jeux?select=nom,score&jeu=eq." + jeu
                    + "&order=score.desc,maj.asc&limit=" + n, null, null);
            return r == null ? null : new JSONArray(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Relit le meilleur joueur d'un jeu et le garde en mémoire. Hors du thread principal. */
    static String actualiser(String jeu) {
        JSONArray a = classement(jeu, 1);
        if (a == null) return meilleur(jeu);
        String t = "";
        if (a.length() > 0) {
            JSONObject o = a.optJSONObject(0);
            if (o != null) t = o.optString("nom", "Joueur") + " — " + o.optInt("score");
        }
        MEILLEURS.put(jeu, t);
        return t;
    }
}
