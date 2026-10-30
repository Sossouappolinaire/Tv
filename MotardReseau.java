package bj.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;

/**
 * Combat Mortel en ligne : appels vers la base (fonctions de section11_motard_combat.sql) et notification
 * « Voulez-vous jouer avec moi ? ». Toutes les méthodes réseau sont à appeler HORS du thread principal.
 */
final class MotardReseau {

    private MotardReseau() {}

    static final String CANAL = "motard_invitation";

    /** Invitations déjà notifiées (pour ne pas notifier deux fois la même). */
    private static final HashSet<String> NOTIFIEES = new HashSet<>();

    private static JSONObject objet(String fonction, JSONObject corps) {
        try {
            String r = Supabase.rest("POST", "rpc/" + fonction, corps.toString(), null);
            return r == null || r.trim().isEmpty() ? null : new JSONObject(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Joueurs actifs depuis moins de 3 minutes (sans moi) ; null si pas de réponse. */
    static JSONArray joueursEnLigne() {
        try {
            String r = Supabase.rest("POST", "rpc/joueurs_en_ligne", "{}", null);
            return r == null ? null : new JSONArray(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Envoie l'invitation ; renvoie l'identifiant du duel ou null (Supabase.derniereErreur dit pourquoi). */
    static String inviter(String idJoueur) {
        try {
            JSONObject o = objet("duel_inviter", new JSONObject().put("p_invite", idJoueur));
            return o == null ? null : o.optString("id", null);
        } catch (Exception e) {
            return null;
        }
    }

    static JSONArray invitations() {
        try {
            String r = Supabase.rest("POST", "rpc/duel_invitations", "{}", null);
            return r == null ? null : new JSONArray(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** « accepte », « refuse » ou « expire ». */
    static String repondre(String duel, boolean oui) {
        try {
            JSONObject o = objet("duel_repondre", new JSONObject().put("p_duel", duel).put("p_oui", oui));
            return o == null ? "" : o.optString("etat", "");
        } catch (Exception e) {
            return "";
        }
    }

    static boolean choisir(String duel, int perso) {
        try {
            return objet("duel_choisir", new JSONObject().put("p_duel", duel).put("p_perso", perso)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    static JSONObject info(String duel) {
        try {
            return objet("duel_info", new JSONObject().put("p_duel", duel));
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject sync(String duel, float x, float y, int face, int act, int degats) {
        try {
            return objet("duel_sync", new JSONObject().put("p_duel", duel).put("p_x", x).put("p_y", y)
                    .put("p_face", face).put("p_act", act).put("p_degats", degats));
        } catch (Exception e) {
            return null;
        }
    }

    static void quitter(String duel) {
        try {
            objet("duel_quitter", new JSONObject().put("p_duel", duel));
        } catch (Exception ignore) {
        }
    }

    // ------------------------------------------------------------------ notification d'invitation

    /**
     * Cherche une invitation reçue et affiche la notification « X veut jouer avec vous » (boutons Oui / Non).
     * Appelée régulièrement par App tant que l'application est ouverte.
     */
    static void verifierInvitations(Context c) {
        if (!Compte.connecte()) return;
        JSONArray a = invitations();
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            synchronized (NOTIFIEES) {
                if (id.isEmpty() || !NOTIFIEES.add(id)) continue;
            }
            notifier(c, id, o.optString("de", "Un joueur"));
        }
    }

    static void annulerNotification(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(31);
    }

    private static PendingIntent action(Context c, String duel, String de, String reponse, int code) {
        Intent it = new Intent(c, JeuMotardActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("invitation_id", duel)
                .putExtra("invitation_de", de)
                .putExtra("invitation_reponse", reponse);
        return PendingIntent.getActivity(c, code, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifier(Context c, String duel, String de) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CANAL, "Combat Mortel · invitations",
                        NotificationManager.IMPORTANCE_HIGH));
            }
            int base = (duel.hashCode() & 0xFFFF) * 4;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CANAL) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("🥊 Combat Mortel")
                    .setContentText(de + " : « Vous voulez jouer avec moi ? »")
                    .setContentIntent(action(c, duel, de, "", base + 1))
                    .setAutoCancel(true)
                    .addAction(new Notification.Action.Builder(null, "Oui", action(c, duel, de, "oui", base + 2)).build())
                    .addAction(new Notification.Action.Builder(null, "Non", action(c, duel, de, "non", base + 3)).build());
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(110_000);
            else b.setPriority(Notification.PRIORITY_HIGH);
            nm.notify(31, b.build());
        } catch (Exception ignore) {
            // pas de permission de notification : l'invitation s'affiche quand même dans le menu du jeu
        }
    }
}
