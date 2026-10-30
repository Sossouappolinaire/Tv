package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Combat Mortel : menu du jeu.
 *  - « Joueur 1 » : on joue contre le robot (10 niveaux, le robot devient de plus en plus fort).
 *  - « En ligne » : on voit les joueurs connectés, on invite quelqu'un ; il reçoit une notification
 *    « Vous voulez jouer avec moi ? ». S'il dit Oui, chacun choisit son personnage, puis le combat commence.
 * Le combat lui-même est dans MotardCombatActivity.
 */
public class JeuMotardActivity extends Activity {

    /** Un personnage de combat. vitesse / force / saut : 1 = normal. */
    static final class Perso {
        final String nom, emoji, desc;
        final int couleur;
        final float vitesse, force, saut;
        Perso(String nom, String emoji, String desc, int couleur, float vitesse, float force, float saut) {
            this.nom = nom; this.emoji = emoji; this.desc = desc; this.couleur = couleur;
            this.vitesse = vitesse; this.force = force; this.saut = saut;
        }
    }

    static final Perso[] PERSOS = {
            new Perso("Kouamé le Roi", "👑", "Équilibré : un bon pilote pour commencer.", 0xFFFF9800, 1.00f, 1.00f, 1.00f),
            new Perso("Ninja Noir", "🥷", "Très rapide, saute haut, mais frappe moins fort.", 0xFF7B4DFF, 1.25f, 0.80f, 1.15f),
            new Perso("Gorille", "🦍", "Lent et lourd, mais ses coups font très mal.", 0xFF2E9E73, 0.85f, 1.25f, 0.90f),
            new Perso("Fantôme", "👻", "Saute très haut et esquive tout, coups moyens.", 0xFF4FC3F7, 1.10f, 0.95f, 1.30f),
    };

    static final int NIVEAUX = 10;
    private static final String PREFS = "jeux";
    private static final String CLE_NIVEAU = "motard_niveau";

    static int niveauDebloque(Context c) {
        return Math.max(1, Math.min(NIVEAUX, c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(CLE_NIVEAU, 1)));
    }

    static void debloquer(Context c, int niveau) {
        int n = Math.min(NIVEAUX, niveau);
        if (n > niveauDebloque(c)) c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(CLE_NIVEAU, n).apply();
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout cadre;
    private int session = 0;            // change à chaque écran : arrête les anciennes boucles d'attente
    private boolean dialogOuvert = false;

    // Duel en cours (mode en ligne)
    private String duel = "";
    private String advNom = "";
    private boolean hote = false;
    private int monPerso = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        cadre = new FrameLayout(this);
        cadre.setBackground(Ui.fond());
        setContentView(cadre);
        accueil();
        traiterIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        traiterIntent(it);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        MotardReseau.annulerNotification(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        session++;
        if (!duel.isEmpty() && !enCombat) {
            final String d = duel;
            new Thread(() -> MotardReseau.quitter(d)).start();
        }
    }

    private boolean enCombat = false;

    /** Ouvert depuis la notification (ou ses boutons Oui / Non). */
    private void traiterIntent(Intent it) {
        if (it == null) return;
        String id = it.getStringExtra("invitation_id");
        if (id == null || id.isEmpty()) return;
        String de = it.getStringExtra("invitation_de");
        String rep = it.getStringExtra("invitation_reponse");
        it.removeExtra("invitation_id");
        if ("oui".equals(rep)) repondre(id, de == null ? "Un joueur" : de, true);
        else if ("non".equals(rep)) { repondre(id, "", false); }
        else proposer(id, de == null ? "Un joueur" : de);
    }

    // ------------------------------------------------------------------ outils d'écran

    private LinearLayout nouvelEcran(String titre, String sous) {
        session++;
        cadre.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);
        Ui.ajouter(root, Ui.texte(this, titre, 26, Ui.JAUNE, true), 0);
        if (sous != null) Ui.ajouter(root, Ui.texte(this, sous, 14, Ui.TXT2, false), 4);
        sv.addView(root);
        cadre.addView(sv);
        return root;
    }

    private boolean visible = true;   // faux pendant un combat : on n'interroge plus la base pour rien

    private void planifier(int s, long ms, Runnable r) {
        ui.postDelayed(() -> {
            if (s != session || isFinishing()) return;
            if (!visible) planifier(s, ms, r);
            else r.run();
        }, ms);
    }

    private Button bouton(LinearLayout parent, String t, int c1, int c2, View.OnClickListener l) {
        Button b = Ui.boutonPilule(this, t, c1, c2);
        b.setOnClickListener(l);
        parent.addView(b, Ui.lp(this, -1, -2, 0f, 12, 0));
        return b;
    }

    // ------------------------------------------------------------------ accueil

    private void accueil() {
        duel = "";
        LinearLayout r = nouvelEcran("🥊 Combat Mortel",
                "Un vrai jeu de combat : frappe, esquive et vide la barre de vie de ton adversaire.");
        final int s = session;
        Ui.ajouter(r, Ui.texte(this, "🏆 Niveau le plus haut débloqué contre le robot : " + niveauDebloque(this) + " / " + NIVEAUX,
                13, 0xFFFFF59D, true), 12);

        bouton(r, "🤖  Joueur 1 — contre le robot", 0xFF37474F, 0xFFFF5722, v -> choixPerso(false, p -> choixNiveau(p)));
        Ui.ajouter(r, Ui.texte(this, "Le robot est très fort : plus le niveau monte, plus il est rapide, résistant et dangereux.",
                12, Ui.TXT2, false), 4);

        bouton(r, "🌐  Jouer en ligne avec un autre joueur", 0xFF1E8E3E, 0xFF12B5CB, v -> salon());
        Ui.ajouter(r, Ui.texte(this, "Invitez un joueur connecté : il reçoit une notification « Vous voulez jouer avec moi ? ».",
                12, Ui.TXT2, false), 4);

        Ui.ajouter(r, Ui.texte(this, "Commandes : ◀ ▶ · SAUT · POING (rapide) · PIED (plus fort) · GARDE (bloque les coups) · SPÉCIAL (boule d'énergie, toutes les 4 secondes).",
                12, Ui.TXT2, false), 18);

        Button retour = Ui.boutonContour(this, "← Retour aux jeux", Ui.TXT);
        retour.setOnClickListener(v -> finish());
        r.addView(retour, Ui.lp(this, -1, -2, 0f, 18, 0));

        surveillerInvitations(s);
    }

    /** Tant qu'on est dans le menu, on regarde si quelqu'un nous invite (toutes les 4 secondes). */
    private void surveillerInvitations(int s) {
        planifier(s, 4000, () -> new Thread(() -> {
            JSONArray a = MotardReseau.invitations();
            runOnUiThread(() -> {
                if (s != session || isFinishing()) return;
                if (a != null && a.length() > 0 && !dialogOuvert) {
                    JSONObject o = a.optJSONObject(0);
                    if (o != null) proposer(o.optString("id", ""), o.optString("de", "Un joueur"));
                }
                surveillerInvitations(s);
            });
        }).start());
    }

    // ------------------------------------------------------------------ choix du personnage

    private interface Choix { void fait(int perso); }

    private void choixPerso(boolean enLigne, Choix suite) {
        LinearLayout r = nouvelEcran("Choisis ton pilote",
                enLigne ? "Ton adversaire choisit aussi le sien. Le combat commence quand vous avez tous les deux choisi."
                        : "Chaque pilote a ses forces et ses faiblesses.");
        for (int i = 0; i < PERSOS.length; i++) {
            final int n = i;
            Perso p = PERSOS[i];
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.HORIZONTAL);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setBackground(Ui.onde(p.couleur, Ui.assombrir(p.couleur, 0.35f), 20, this));
            t.setElevation(Ui.dp(this, 4));
            int q = Ui.dp(this, 14);
            t.setPadding(q, q, q, q);
            t.setClickable(true);
            t.setOnClickListener(v -> suite.fait(n));
            Ui.pression(t);
            t.addView(Ui.texte(this, p.emoji, 40, Ui.TXT, false));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.texte(this, p.nom, 19, Ui.TXT, true));
            col.addView(Ui.texte(this, p.desc, 12, 0xDDFFFFFF, false));
            col.addView(Ui.texte(this, "Vitesse " + barre(p.vitesse) + "   Force " + barre(p.force) + "   Saut " + barre(p.saut),
                    12, Ui.JAUNE, true), Ui.lp(this, -2, -2, 0f, 4, 0));
            LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(0, -2, 1f);
            lc.leftMargin = Ui.dp(this, 14);
            t.addView(col, lc);
            r.addView(t, Ui.lp(this, -1, -2, 0f, 12, 0));
        }
        Button retour = Ui.boutonContour(this, "← Retour", Ui.TXT);
        retour.setOnClickListener(v -> { if (enLigne) salon(); else accueil(); });
        r.addView(retour, Ui.lp(this, -1, -2, 0f, 16, 0));
    }

    private static String barre(float v) {
        int n = Math.max(1, Math.min(5, Math.round(v * 3f)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < n ? '▰' : '▱');
        return sb.toString();
    }

    // ------------------------------------------------------------------ contre le robot

    private void choixNiveau(int perso) {
        LinearLayout r = nouvelEcran("Contre le robot", "Choisis le niveau. Gagne pour débloquer le suivant.");
        int max = niveauDebloque(this);
        for (int n = 1; n <= NIVEAUX; n++) {
            final int niv = n;
            boolean ouvert = n <= max;
            Button b = Ui.bouton(this, (ouvert ? "Niveau " : "🔒 Niveau ") + n + "  —  " + nomNiveau(n),
                    ouvert ? (n >= 8 ? 0xFFD32F2F : n >= 5 ? 0xFFFF9800 : 0xFF2E9E73) : 0xFF555A63);
            b.setEnabled(ouvert);
            b.setOnClickListener(v -> lancerRobot(perso, niv));
            r.addView(b, Ui.lp(this, -1, -2, 0f, 8, 0));
        }
        Button retour = Ui.boutonContour(this, "← Changer de pilote", Ui.TXT);
        retour.setOnClickListener(v -> choixPerso(false, p -> choixNiveau(p)));
        r.addView(retour, Ui.lp(this, -1, -2, 0f, 16, 0));
    }

    static String nomNiveau(int n) {
        String[] t = {"Débutant", "Apprenti", "Motard du quartier", "Bagarreur", "Dur à cuire",
                "Redoutable", "Terrible", "Impitoyable", "Légende", "Robot invincible"};
        return t[Math.max(0, Math.min(t.length - 1, n - 1))];
    }

    private void lancerRobot(int perso, int niveau) {
        startActivity(new Intent(this, MotardCombatActivity.class)
                .putExtra("mode", "robot").putExtra("perso", perso).putExtra("niveau", niveau));
    }

    // ------------------------------------------------------------------ salon en ligne

    private void salon() {
        duel = "";
        LinearLayout r = nouvelEcran("🌐 Joueurs en ligne", "Touche un joueur pour lui envoyer « Vous voulez jouer avec moi ? ».");
        final int s = session;
        if (!Compte.connecte()) {
            Ui.ajouter(r, Ui.texte(this, "Connectez-vous à votre compte pour jouer en ligne.", 15, Ui.TXT, true), 16);
            Button retour = Ui.boutonContour(this, "← Retour", Ui.TXT);
            retour.setOnClickListener(v -> accueil());
            r.addView(retour, Ui.lp(this, -1, -2, 0f, 16, 0));
            return;
        }
        final LinearLayout liste = new LinearLayout(this);
        liste.setOrientation(LinearLayout.VERTICAL);
        r.addView(liste, Ui.lp(this, -1, -2, 0f, 12, 0));
        final TextView etat = Ui.texte(this, "Recherche des joueurs…", 14, Ui.TXT2, false);
        liste.addView(etat);

        Button retour = Ui.boutonContour(this, "← Retour", Ui.TXT);
        retour.setOnClickListener(v -> accueil());
        r.addView(retour, Ui.lp(this, -1, -2, 0f, 16, 0));

        actualiserSalon(s, liste);
    }

    private void actualiserSalon(int s, LinearLayout liste) {
        new Thread(() -> {
            JSONArray a = MotardReseau.joueursEnLigne();
            JSONArray inv = MotardReseau.invitations();
            runOnUiThread(() -> {
                if (s != session || isFinishing()) return;
                liste.removeAllViews();
                if (a == null) {
                    liste.addView(Ui.texte(this, "Impossible de lire les joueurs. Vérifiez internet (et que section11_motard_combat.sql a été exécuté).",
                            14, Ui.ROSE, false));
                } else if (a.length() == 0) {
                    liste.addView(Ui.texte(this, "Personne d'autre n'est en ligne pour le moment.\nCette liste se met à jour toute seule.",
                            14, Ui.TXT2, false));
                } else {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        final String id = o.optString("id", "");
                        final String nom = o.optString("nom", "Joueur");
                        Button b = Ui.bouton(this, "🟢  " + nom + "   ·   Inviter", 0xFF1E8E3E);
                        b.setOnClickListener(v -> inviter(id, nom));
                        liste.addView(b, Ui.lp(this, -1, -2, 0f, 8, 0));
                    }
                }
                if (inv != null && inv.length() > 0 && !dialogOuvert) {
                    JSONObject o = inv.optJSONObject(0);
                    if (o != null) proposer(o.optString("id", ""), o.optString("de", "Un joueur"));
                }
                planifier(s, 4000, () -> actualiserSalon(s, liste));
            });
        }).start();
    }

    // ------------------------------------------------------------------ j'invite

    private void inviter(String idJoueur, String nom) {
        LinearLayout r = nouvelEcran("Invitation envoyée", "En attente de la réponse de " + nom + "…");
        final int s = session;
        advNom = nom;
        hote = true;
        Ui.ajouter(r, Ui.texte(this, "⏳", 48, Ui.TXT, false), 24);
        Button annuler = Ui.boutonContour(this, "Annuler", Ui.TXT);
        r.addView(annuler, Ui.lp(this, -1, -2, 0f, 24, 0));
        final String[] id = {""};
        annuler.setOnClickListener(v -> {
            final String d = id[0];
            if (!d.isEmpty()) new Thread(() -> MotardReseau.quitter(d)).start();
            salon();
        });
        new Thread(() -> {
            String d = MotardReseau.inviter(idJoueur);
            runOnUiThread(() -> {
                if (s != session || isFinishing()) {
                    if (d != null) new Thread(() -> MotardReseau.quitter(d)).start();
                    return;
                }
                if (d == null) {
                    Toast.makeText(this, "Invitation impossible : " + Supabase.derniereErreur, Toast.LENGTH_LONG).show();
                    salon();
                    return;
                }
                id[0] = d;
                duel = d;
                attendreReponse(s, System.currentTimeMillis());
            });
        }).start();
    }

    private void attendreReponse(int s, long depuis) {
        planifier(s, 1500, () -> new Thread(() -> {
            JSONObject o = MotardReseau.info(duel);
            runOnUiThread(() -> {
                if (s != session || isFinishing()) return;
                String e = o == null ? "" : o.optString("etat", "");
                if ("accepte".equals(e) || "en_cours".equals(e)) {
                    choixPerso(true, p -> choisirEnLigne(p));
                } else if ("refuse".equals(e)) {
                    Toast.makeText(this, advNom + " ne veut pas jouer pour le moment.", Toast.LENGTH_LONG).show();
                    salon();
                } else if ("annule".equals(e) || "introuvable".equals(e) || "termine".equals(e)
                        || System.currentTimeMillis() - depuis > 100_000) {
                    final String d = duel;
                    new Thread(() -> MotardReseau.quitter(d)).start();
                    Toast.makeText(this, advNom + " n'a pas répondu.", Toast.LENGTH_LONG).show();
                    salon();
                } else {
                    attendreReponse(s, depuis);
                }
            });
        }).start());
    }

    // ------------------------------------------------------------------ on m'invite

    private void proposer(String id, String de) {
        if (id == null || id.isEmpty() || dialogOuvert) return;
        dialogOuvert = true;
        new AlertDialog.Builder(this)
                .setTitle("🥊 Combat Mortel")
                .setMessage(de + " : « Vous voulez jouer avec moi ? »")
                .setCancelable(false)
                .setPositiveButton("Oui", (d, w) -> { dialogOuvert = false; repondre(id, de, true); })
                .setNegativeButton("Non", (d, w) -> { dialogOuvert = false; repondre(id, de, false); })
                .show();
    }

    private void repondre(String id, String de, boolean oui) {
        MotardReseau.annulerNotification(this);
        if (!oui) {
            new Thread(() -> MotardReseau.repondre(id, false)).start();
            return;
        }
        ++session;
        advNom = de;
        hote = false;
        new Thread(() -> {
            String e = MotardReseau.repondre(id, true);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if ("accepte".equals(e)) {
                    duel = id;
                    choixPerso(true, p -> choisirEnLigne(p));
                } else {
                    Toast.makeText(this, "Cette invitation n'est plus valable.", Toast.LENGTH_LONG).show();
                    accueil();
                }
            });
        }).start();
    }

    // ------------------------------------------------------------------ après le choix : attente de l'adversaire

    private void choisirEnLigne(int perso) {
        monPerso = perso;
        LinearLayout r = nouvelEcran("Prêt à combattre !",
                "Tu as choisi " + PERSOS[perso].nom + ". En attente du choix de " + advNom + "…");
        final int s = session;
        Ui.ajouter(r, Ui.texte(this, PERSOS[perso].emoji, 60, Ui.TXT, false), 24);
        Button annuler = Ui.boutonContour(this, "Annuler", Ui.TXT);
        annuler.setOnClickListener(v -> {
            final String d = duel;
            new Thread(() -> MotardReseau.quitter(d)).start();
            salon();
        });
        r.addView(annuler, Ui.lp(this, -1, -2, 0f, 24, 0));
        final String d = duel;
        new Thread(() -> {
            boolean ok = MotardReseau.choisir(d, perso);
            runOnUiThread(() -> {
                if (s != session || isFinishing()) return;
                if (!ok) {
                    Toast.makeText(this, "Impossible d'envoyer ton choix.", Toast.LENGTH_LONG).show();
                    salon();
                } else {
                    attendreAdversaire(s, System.currentTimeMillis());
                }
            });
        }).start();
    }

    private void attendreAdversaire(int s, long depuis) {
        planifier(s, 1200, () -> new Thread(() -> {
            JSONObject o = MotardReseau.info(duel);
            runOnUiThread(() -> {
                if (s != session || isFinishing()) return;
                String e = o == null ? "" : o.optString("etat", "");
                if ("en_cours".equals(e)) {
                    int advPerso = Math.max(0, Math.min(PERSOS.length - 1, o.optInt("adv_perso", 0)));
                    enCombat = true;
                    startActivity(new Intent(this, MotardCombatActivity.class)
                            .putExtra("mode", "ligne").putExtra("duel", duel).putExtra("perso", monPerso)
                            .putExtra("adv_perso", advPerso).putExtra("adv_nom", advNom).putExtra("hote", hote));
                    // au retour du combat, on revient au salon
                    session++;
                    duel = "";
                    enCombat = false;
                    salon();
                } else if ("annule".equals(e) || "introuvable".equals(e) || "termine".equals(e)
                        || System.currentTimeMillis() - depuis > 120_000) {
                    final String d = duel;
                    new Thread(() -> MotardReseau.quitter(d)).start();
                    Toast.makeText(this, advNom + " a quitté la partie.", Toast.LENGTH_LONG).show();
                    salon();
                } else {
                    attendreAdversaire(s, depuis);
                }
            });
        }).start());
    }
}
