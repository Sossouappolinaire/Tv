package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
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

import java.util.Locale;

/**
 * Administrateur : programme du jour de TV Kouamé, liste des vidéos par catégorie,
 * changement de catégorie (série + numéro d'épisode) et du titre, retrait d'une vidéo.
 * Les droits sont vérifiés par la base (est_admin) : cet écran seul ne donne aucun pouvoir.
 */
public class AdminTvActivity extends Activity {

    private LinearLayout liste;
    private TextView statut;
    private String mode = "programme";   // programme, "" (tout), serie, film, chanson, exclus

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

        Ui.ajouter(c, Ui.texte(this, "📺 Programme TV Kouamé", 22, Ui.TXT, true), 0);
        Ui.ajouter(c, Ui.texte(this, "Touchez une vidéo pour changer sa catégorie ou la retirer de la TV (elle ne sera plus jamais programmée). "
                + "Après une modification, appuyez sur « Refaire le programme » pour l'appliquer à aujourd'hui.", 13, Ui.TXT2, false), 6);

        Button refaire = Ui.bouton(this, "🔄 Refaire le programme d'aujourd'hui", Ui.VERT);
        refaire.setOnClickListener(v -> refaire());
        Ui.ajouter(c, refaire, 10);

        String[][] filtres = {{"programme", "📺 Programme du jour"}, {"", "Toutes"}, {"serie", "📺 Séries"},
                {"film", "🎬 Films"}, {"chanson", "🎵 Chansons"}, {"exclus", "⛔ Retirées de la TV"}};
        for (final String[] f : filtres) {
            Button bt = Ui.boutonContour(this, f[1], Ui.TXT);
            bt.setOnClickListener(v -> {
                mode = f[0];
                charger();
            });
            Ui.ajouter(c, bt, 6);
        }

        statut = Ui.texte(this, "", 13, Ui.JAUNE, false);
        Ui.ajouter(c, statut, 10);
        liste = new LinearLayout(this);
        liste.setOrientation(LinearLayout.VERTICAL);
        Ui.ajouter(c, liste, 4);

        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);
        charger();
    }

    private void charger() {
        statut.setText("Chargement…");
        liste.removeAllViews();
        final String m = mode;
        new Thread(() -> {
            JSONArray arr = null;
            if ("programme".equals(m)) {
                JSONObject pr = Supabase.adminTvProgramme();
                if (pr != null) arr = pr.optJSONArray("programmes");
            } else if ("exclus".equals(m)) {
                arr = Supabase.adminTvVideos(null, true);
            } else {
                arr = Supabase.adminTvVideos(m, false);
            }
            final JSONArray res = arr;
            runOnUiThread(() -> {
                if (!m.equals(mode)) return;
                liste.removeAllViews();
                if (res == null) {
                    statut.setText("⚠ Impossible de charger : vérifiez que votre compte est administrateur et que les fonctions sont installées dans Supabase.");
                    return;
                }
                for (int i = 0; i < res.length(); i++) {
                    JSONObject o = res.optJSONObject(i);
                    if (o != null) liste.addView(ligne(o, "programme".equals(m)), Ui.lp(this, -1, -2, 0f, 8, 0));
                }
                statut.setText(res.length() == 0 ? "Aucune vidéo ici." : res.length() + " vidéo(s)"
                        + ("programme".equals(m) ? " dans le programme du jour." : "."));
            });
        }).start();
    }

    private static String hm(long ms) {
        long min = (((ms % 86_400_000L) + 86_400_000L) % 86_400_000L) / 60_000L;
        return String.format(Locale.FRANCE, "%02dh%02d", min / 60, min % 60);
    }

    private static String texte(JSONObject o, String cle) {
        String s = o.optString(cle, "");
        return "null".equals(s) ? "" : s;
    }

    private String titreDe(JSONObject o) {
        String t = texte(o, "titre");
        return t.isEmpty() ? "(sans titre)" : t;
    }

    private String etiquette(JSONObject o) {
        String cat = texte(o, "categorie");
        String s = "serie".equals(cat) ? "📺 Série" : "film".equals(cat) ? "🎬 Film" : "chanson".equals(cat) ? "🎵 Chanson" : "❔ Sans catégorie";
        String serie = texte(o, "serie");
        int ep = o.optInt("episode", 0);
        if ("serie".equals(cat) && (!serie.isEmpty() || ep > 0)) {
            s += " · " + (serie.isEmpty() ? "" : serie) + (ep > 0 ? " ép. " + ep : "");
        }
        long d = o.optLong("duree_ms", 0);
        s += d > 0 ? " · " + Math.round(d / 60000.0) + " min" : " · ⚠ durée inconnue (jamais programmée)";
        if (o.optBoolean("exclu")) s += " · ⛔ retirée de la TV";
        return s;
    }

    private View ligne(final JSONObject o, boolean avecHeure) {
        LinearLayout carte = new LinearLayout(this);
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setBackground(Ui.rond(Ui.CARD, 12, this));
        int q = Ui.dp(this, 12);
        carte.setPadding(q, q, q, q);
        String t = (avecHeure ? hm(o.optLong("debut_ms", 0)) + " · " : "") + titreDe(o);
        TextView tt = Ui.texte(this, t, 14, Ui.TXT, true);
        tt.setMaxLines(3);
        carte.addView(tt);
        carte.addView(Ui.texte(this, etiquette(o), 12, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 4, 0));
        carte.setOnClickListener(v -> actions(o));
        return carte;
    }

    private void actions(final JSONObject o) {
        final String id = o.optString("id", "");
        final boolean exclu = o.optBoolean("exclu");
        String[] choix = {"✏ Changer la catégorie / l'épisode",
                exclu ? "✅ Remettre dans la TV" : "⛔ Retirer de la TV"};
        new AlertDialog.Builder(this)
                .setTitle(titreDe(o))
                .setItems(choix, (d, i) -> {
                    if (i == 0) choisirCategorie(o);
                    else exclure(id, !exclu);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void choisirCategorie(final JSONObject o) {
        final String id = o.optString("id", "");
        final String[] noms = {"📺 Série", "🎬 Film", "🎵 Chanson"};
        new AlertDialog.Builder(this)
                .setTitle("Cette vidéo est…")
                .setItems(noms, (d, i) -> {
                    if (i == 0) saisirSerie(o);
                    else saisirTitre(o, i == 1 ? "film" : "chanson", "", 0);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void saisirSerie(final JSONObject o) {
        final String id = o.optString("id", "");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.rond(Ui.CARD, 0, this));
        int p = Ui.dp(this, 16);
        l.setPadding(p, p, p, p);
        final EditText nom = Ui.champ(this, "Nom de la série");
        final EditText ep = Ui.champ(this, "Numéro de l'épisode");
        ep.setInputType(InputType.TYPE_CLASS_NUMBER);
        nom.setText(texte(o, "serie"));
        int e = o.optInt("episode", 0);
        if (e > 0) ep.setText(String.valueOf(e));
        Ui.ajouter(l, nom, 0);
        Ui.ajouter(l, ep, 8);
        new AlertDialog.Builder(this)
                .setTitle("Série et épisode")
                .setView(l)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String n = nom.getText().toString().trim();
                    int numero = 0;
                    try {
                        numero = Integer.parseInt(ep.getText().toString().trim());
                    } catch (Exception ignore) {
                        // numéro vide ou invalide
                    }
                    if (n.isEmpty() || numero < 1) {
                        statut.setText("⚠ Indiquez le nom de la série et le numéro de l'épisode (1 ou plus).");
                        return;
                    }
                    saisirTitre(o, "serie", n, numero);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Après le choix de la catégorie : on peut donner (ou corriger) le titre. « Passer » garde le titre actuel. */
    private void saisirTitre(final JSONObject o, final String cat, final String serie, final int episode) {
        final String id = o.optString("id", "");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.rond(Ui.CARD, 0, this));
        int p = Ui.dp(this, 16);
        l.setPadding(p, p, p, p);
        final EditText titre = Ui.champ(this, "Titre de la vidéo");
        titre.setText(texte(o, "titre"));
        titre.setSelection(titre.getText().length());
        Ui.ajouter(l, titre, 0);
        new AlertDialog.Builder(this)
                .setTitle("Titre (facultatif)")
                .setView(l)
                .setPositiveButton("Enregistrer", (d, w) -> modifier(id, cat, serie, episode, titre.getText().toString().trim()))
                .setNeutralButton("Passer", (d, w) -> modifier(id, cat, serie, episode, ""))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void modifier(final String id, final String cat, final String serie, final int episode, final String titre) {
        statut.setText("Enregistrement…");
        new Thread(() -> {
            String e = Supabase.adminVideoModifier(id, cat, serie, episode);
            if (e.isEmpty() && titre != null && !titre.isEmpty()) {
                String et = Supabase.adminVideoTitre(id, titre);
                if (!et.isEmpty()) e = "titre : " + et;
            }
            final String err = e;
            runOnUiThread(() -> {
                if (err.isEmpty()) {
                    statut.setText("✅ Enregistré. Appuyez sur « Refaire le programme » pour l'appliquer à aujourd'hui.");
                    charger();
                } else if ("serie_requise".equals(err)) {
                    statut.setText("⚠ Le nom de la série est obligatoire.");
                } else if ("episode_requis".equals(err)) {
                    statut.setText("⚠ Le numéro de l'épisode est obligatoire (1 ou plus).");
                } else {
                    statut.setText("⚠ Impossible d'enregistrer (" + err + ").");
                }
            });
        }).start();
    }

    private void exclure(final String id, final boolean exclu) {
        statut.setText("Enregistrement…");
        new Thread(() -> {
            final boolean ok = Supabase.adminTvExclure(id, exclu);
            runOnUiThread(() -> {
                if (ok) {
                    statut.setText(exclu ? "✅ Vidéo retirée : elle ne sera plus programmée. Appuyez sur « Refaire le programme » pour l'enlever d'aujourd'hui."
                            : "✅ Vidéo remise dans la TV.");
                    charger();
                } else {
                    statut.setText("⚠ Impossible d'enregistrer.");
                }
            });
        }).start();
    }

    private void refaire() {
        statut.setText("Génération…");
        new Thread(() -> {
            final JSONObject r = Supabase.adminTvGenerer("Kouamé TV");
            runOnUiThread(() -> {
                if (r == null || !r.optBoolean("ok")) {
                    statut.setText("⚠ Impossible de refaire le programme.");
                } else if (r.optInt("nombre") == 0) {
                    statut.setText("⚠ Aucune vidéo programmable (il faut une durée connue).");
                } else {
                    statut.setText("✅ Programme refait : " + r.optInt("nombre") + " vidéo(s).");
                    if ("programme".equals(mode)) charger();
                }
            });
        }).start();
    }
}
