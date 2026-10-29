package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;

/** Menu « JEUX » : Panda Dash, Super Kouamé (style Mario) et Serpent. Les records sont gardés sur le téléphone. */
public class JeuxActivity extends Activity {

    private static final String PREFS = "jeux";

    private static final String[] JEUX = {"panda", "mario", "serpent"};

    /** Par jeu : [0] mon record, [1] meilleur joueur de tous les joueurs. */
    private final HashMap<String, TextView[]> champs = new HashMap<>();

    static int record(Context c, String cle) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(cle, 0);
    }

    static void enregistrer(Context c, String cle, int valeur) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (valeur > sp.getInt(cle, 0)) {
            sp.edit().putInt(cle, valeur).apply();
            ScoresJeux.envoyer(cle, valeur);     // classement partagé par tous les joueurs
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);

        Ui.ajouter(root, Ui.texte(this, "🎮 Jeux", 28, Ui.JAUNE, true), 0);
        Ui.ajouter(root, Ui.texte(this, "Choisissez un jeu et battez votre record !", 14, Ui.TXT2, false), 4);

        carte(root, "panda", "🐼", "Panda Dash",
                "Cours, saute, ramasse les pièces et écrase les scarabées rouges jusqu'au drapeau.",
                0xFF2E9E73, 0xFF9BCB3C,
                new Intent(this, JeuPlateformeActivity.class).putExtra("mode", "panda"));

        carte(root, "mario", "🍄", "Super Kouamé",
                "Un jeu de plateformes comme Mario : briques, tuyaux, champignons méchants et niveaux de plus en plus durs.",
                0xFFD32F2F, 0xFFFF9800,
                new Intent(this, JeuPlateformeActivity.class).putExtra("mode", "mario"));

        carte(root, "serpent", "🐍", "Serpent",
                "Glissez le doigt pour tourner, mangez les pommes, ne touchez ni les murs ni votre queue.",
                0xFF1E8E3E, 0xFF12B5CB,
                new Intent(this, JeuSerpentActivity.class));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackground(Ui.fond());
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (String jeu : JEUX) {
            TextView[] f = champs.get(jeu);
            f[0].setText("🏆 Mon record : " + record(this, jeu));
            f[1].setText(texteMonde(ScoresJeux.meilleur(jeu)));
        }
        // Renvoie mes records (au cas où l'envoi avait échoué hors connexion) et relit les meilleurs scores de tous.
        new Thread(() -> {
            for (String jeu : JEUX) {
                ScoresJeux.envoyerSync(jeu, record(this, jeu));
                String t = ScoresJeux.actualiser(jeu);
                runOnUiThread(() -> {
                    TextView[] f = champs.get(jeu);
                    if (f != null) f[1].setText(texteMonde(t));
                });
            }
        }).start();
    }

    private static String texteMonde(String t) {
        return t.isEmpty() ? "🥇 Meilleur joueur : personne encore, soyez le premier !" : "🥇 Meilleur joueur : " + t;
    }

    /** Fenêtre « Top 10 » d'un jeu, lue dans la base (visible par tous les joueurs). */
    private void afficherClassement(String jeu, String titre) {
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("🏆 " + titre)
                .setMessage("Chargement…")
                .setPositiveButton("Fermer", null)
                .create();
        dlg.show();
        new Thread(() -> {
            JSONArray a = ScoresJeux.classement(jeu, 10);
            String txt;
            if (a == null) {
                txt = "Impossible de lire le classement. Vérifiez votre connexion internet.";
            } else if (a.length() == 0) {
                txt = "Aucun score pour le moment. Soyez le premier !";
            } else {
                StringBuilder sb = new StringBuilder();
                String moi = Compte.nom();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String nom = o.optString("nom", "Joueur");
                    sb.append(i + 1).append(". ").append(nom).append(" — ").append(o.optInt("score"));
                    if (!moi.isEmpty() && moi.equals(nom)) sb.append("   ← vous");
                    sb.append('\n');
                }
                txt = sb.toString().trim();
            }
            final String fin = txt;
            runOnUiThread(() -> dlg.setMessage(fin));
        }).start();
    }

    private void carte(LinearLayout parent, String jeu, String emoji, String titre, String desc, int c1, int c2, Intent it) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackground(Ui.onde(c1, c2, 22, this));
        t.setElevation(Ui.dp(this, 4));
        int q = Ui.dp(this, 14);
        t.setPadding(q, q, q, q);
        t.setClickable(true);
        t.setOnClickListener(v -> startActivity(it));
        Ui.pression(t);

        TextView e = Ui.texte(this, emoji, 44, Ui.TXT, false);
        t.addView(e);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.texte(this, titre, 20, Ui.TXT, true));
        col.addView(Ui.texte(this, desc, 12, 0xDDFFFFFF, false));
        TextView rec = Ui.texte(this, "", 13, Ui.JAUNE, true);
        col.addView(rec, Ui.lp(this, -2, -2, 0f, 4, 0));
        TextView monde = Ui.texte(this, "", 13, 0xFFFFF59D, true);
        col.addView(monde, Ui.lp(this, -2, -2, 0f, 2, 0));
        TextView classement = Ui.texte(this, "📊 Voir le classement (Top 10)", 13, Ui.TXT, true);
        classement.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 4));
        classement.setOnClickListener(v -> afficherClassement(jeu, titre));
        col.addView(classement);
        champs.put(jeu, new TextView[]{rec, monde});

        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(0, -2, 1f);
        lc.leftMargin = Ui.dp(this, 14);
        t.addView(col, lc);

        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(-1, -2);
        lt.topMargin = Ui.dp(this, 14);
        parent.addView(t, lt);
    }
}
