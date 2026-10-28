package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Panneau administrateur : chiffres clés, liste des comptes, ajout de liens Facebook (avec série et épisode).
 * Les droits sont vérifiés par la base (fonction est_admin) : cet écran seul ne donne aucun pouvoir.
 */
public class AdminActivity extends Activity {

    private TextView chiffres, statut, comptesTxt;
    private EditText lien, titre, serie, episode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        if (!Compte.admin()) {
            finish();
            return;
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.fond());
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        c.setPadding(p, p, p, p);

        Ui.ajouter(c, Ui.texte(this, "👑 Administration", 24, Ui.TXT, true), 0);

        chiffres = Ui.texte(this, "Chargement…", 16, Ui.JAUNE, true);
        Ui.ajouter(c, chiffres, 12);
        Button actu = Ui.bouton(this, "🔄 Actualiser", Ui.VIOLET);
        actu.setOnClickListener(v -> charger());
        Ui.ajouter(c, actu, 8);

        Button sortir = Ui.boutonContour(this, "Se déconnecter", Ui.TXT);
        sortir.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Se déconnecter ?")
                .setMessage("Vous devrez saisir votre email et votre mot de passe pour revenir. Le code PIN sera supprimé.")
                .setPositiveButton("Se déconnecter", (d, w) -> {
                    Compte.deconnecter();
                    Intent i = new Intent(this, AuthActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Annuler", null)
                .show());
        Ui.ajouter(c, sortir, 8);

        Ui.ajouter(c, Ui.texte(this, "Ajouter un film Facebook", 18, Ui.TXT, true), 22);
        lien = Ui.champ(this, "Lien Facebook (obligatoire)");
        lien.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        titre = Ui.champ(this, "Titre du film");
        serie = Ui.champ(this, "Série (facultatif)");
        episode = Ui.champ(this, "Numéro d'épisode (facultatif)");
        episode.setInputType(InputType.TYPE_CLASS_NUMBER);
        Ui.ajouter(c, lien, 8);
        Ui.ajouter(c, titre, 8);
        Ui.ajouter(c, serie, 8);
        Ui.ajouter(c, episode, 8);
        Button ajout = Ui.bouton(this, "➕ Ajouter à la liste", Ui.ACCENT);
        ajout.setOnClickListener(v -> ajouter(ajout));
        Ui.ajouter(c, ajout, 10);
        statut = Ui.texte(this, "", 13, Ui.JAUNE, false);
        Ui.ajouter(c, statut, 8);

        Ui.ajouter(c, Ui.texte(this, "Comptes créés", 18, Ui.TXT, true), 22);
        comptesTxt = Ui.texte(this, "", 13, Ui.TXT2, false);
        Ui.ajouter(c, comptesTxt, 8);

        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);
        charger();
    }

    private void charger() {
        new Thread(() -> {
            final JSONObject s = Supabase.statsAdmin();
            final JSONArray l = Supabase.comptes();
            runOnUiThread(() -> {
                if (s == null) {
                    chiffres.setText("⚠ Statistiques indisponibles : exécutez la section 3 de supabase_setup.sql "
                            + "et vérifiez que votre e-mail est bien celui de l'administrateur.");
                } else {
                    chiffres.setText("👥 Comptes : " + s.optInt("comptes")
                            + "\n🟢 En ligne : " + s.optInt("en_ligne")
                            + "\n⬇ Téléchargements : " + s.optInt("telechargements")
                            + "\n🎞 Films : " + s.optInt("films"));
                }
                StringBuilder sb = new StringBuilder();
                if (l != null) {
                    for (int i = 0; i < l.length(); i++) {
                        JSONObject o = l.optJSONObject(i);
                        if (o == null) continue;
                        String nom = o.optString("nom", "");
                        if ("null".equals(nom) || nom.isEmpty()) nom = "(sans nom)";
                        sb.append(i + 1).append(". ").append(nom).append(" — ").append(o.optString("email", ""))
                                .append("\n    inscrit le ").append(jour(o.optString("cree_le", "")))
                                .append(" · vu le ").append(jour(o.optString("derniere_connexion", ""))).append("\n");
                    }
                }
                comptesTxt.setText(sb.length() == 0 ? "Aucun compte à afficher." : sb.toString());
            });
        }).start();
    }

    private static String jour(String iso) {
        return iso == null || iso.length() < 10 || "null".equals(iso) ? "—" : iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }

    private void ajouter(final Button bouton) {
        final String l = lien.getText().toString().trim();
        if (!l.matches("(?i)^https?://([a-z0-9-]+\\.)*(facebook\\.com|fb\\.watch|fb\\.com)/.*")) {
            statut.setText("⚠ Collez un vrai lien Facebook (facebook.com, fb.watch…).");
            return;
        }
        final String t = titre.getText().toString().trim();
        final String s = serie.getText().toString().trim();
        int e = 0;
        try {
            e = Integer.parseInt(episode.getText().toString().trim());
        } catch (Exception ignore) {
            // pas d'épisode
        }
        final int ep = e;
        bouton.setEnabled(false);
        statut.setText("Envoi…");
        new Thread(() -> {
            final boolean ok = Supabase.ajouterFilm(l, t, s, ep);
            final String err = Supabase.derniereErreur;
            runOnUiThread(() -> {
                bouton.setEnabled(true);
                if (ok) {
                    statut.setText("✅ Ajouté à « Films Facebook ».");
                    lien.setText("");
                    titre.setText("");
                    episode.setText("");
                    charger();
                } else {
                    statut.setText("⚠ " + (err.isEmpty() ? "Ajout impossible." : err));
                }
            });
        }).start();
    }
}
