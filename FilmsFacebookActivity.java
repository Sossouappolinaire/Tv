package bj.radio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * « Films partagés » : les vidéos dont un utilisateur a collé le lien (téléchargées ou non) sont partagées
 * dans la base Supabase. Ici on les voit avec leur image, leur durée, leurs formats et leurs tailles,
 * et un appui sur « Télécharger » rouvre l'analyse du lien dans « Télécharger Facebook ».
 */
public class FilmsFacebookActivity extends Activity {

    private static final int PAR_PAGE = 20;

    private LinearLayout liste;
    private TextView statut;
    private Button bPlus;
    private EditText champ;
    private int decalage = 0;
    private String recherche = "";
    private String filtreCat = "";      // "", serie, film ou chanson
    private volatile boolean charge = false;
    private int generation = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.fond());

        LinearLayout contenu = new LinearLayout(this);
        contenu.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        contenu.setPadding(p, p, p, p);

        Ui.ajouter(contenu, Ui.texte(this, "🎞 Films partagés", 24, Ui.TXT, true), 0);
        Ui.ajouter(contenu, Ui.texte(this,
                "Les vidéos Facebook dont quelqu'un a collé le lien dans « Télécharger Facebook » apparaissent ici, "
                + "même si elles n'ont pas été téléchargées. Appuyez sur « Télécharger » pour récupérer l'une d'elles.",
                13, Ui.TXT2, false), 6);

        LinearLayout rech = new LinearLayout(this);
        rech.setGravity(Gravity.CENTER_VERTICAL);
        champ = Ui.champ(this, "Rechercher dans les titres…");
        champ.setSingleLine(true);
        champ.setInputType(InputType.TYPE_CLASS_TEXT);
        champ.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        champ.setOnEditorActionListener((v, a, e) -> { chercher(); return true; });
        Button ok = Ui.bouton(this, "🔍", Ui.VIOLET);
        ok.setOnClickListener(v -> chercher());
        rech.addView(champ, Ui.lp(this, 0, -2, 1f, 0, 0));
        rech.addView(ok, Ui.lp(this, -2, -2, 0f, 0, 8));
        Ui.ajouter(contenu, rech, 12);

        // Catégories : tout / séries / films / chansons (chaque vidéo a une catégorie choisie au moment du collage du lien)
        LinearLayout cats = new LinearLayout(this);
        String[][] choix = {{"", "Tout"}, {"serie", "📺 Séries"}, {"film", "🎬 Films"}, {"chanson", "🎵 Chansons"}};
        for (final String[] ch : choix) {
            Button bCat = Ui.bouton(this, ch[1], Ui.VIOLET);
            bCat.setOnClickListener(v -> {
                filtreCat = ch[0];
                chercher();
            });
            cats.addView(bCat, Ui.lp(this, 0, -2, 1f, 0, 4));
        }
        Ui.ajouter(contenu, cats, 10);

        statut = Ui.texte(this, "", 13, Ui.JAUNE, false);
        Ui.ajouter(contenu, statut, 10);

        liste = new LinearLayout(this);
        liste.setOrientation(LinearLayout.VERTICAL);
        Ui.ajouter(contenu, liste, 6);

        bPlus = Ui.bouton(this, "Charger plus", Ui.VIOLET);
        bPlus.setVisibility(View.GONE);
        bPlus.setOnClickListener(v -> charger());
        Ui.ajouter(contenu, bPlus, 10);

        ScrollView sv = new ScrollView(this);
        sv.addView(contenu);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);

        charger();
        surveillerReseau();
    }

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback rappelReseau;

    /** Dès que le téléphone se (re)connecte à internet, la liste se recharge toute seule si elle est vide ou en erreur. */
    private void surveillerReseau() {
        try {
            cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            rappelReseau = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    runOnUiThread(() -> {
                        if (decalage == 0 && !charge) charger();
                    });
                }
            };
            cm.registerDefaultNetworkCallback(rappelReseau);
        } catch (Exception ignore) {
            // pas de surveillance : le bouton de recherche et « Charger plus » restent utilisables
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (cm != null && rappelReseau != null) cm.unregisterNetworkCallback(rappelReseau);
        } catch (Exception ignore) {
            // déjà retiré
        }
        super.onDestroy();
    }

    private void chercher() {
        recherche = champ.getText().toString().trim();
        decalage = 0;
        generation++;
        liste.removeAllViews();
        bPlus.setVisibility(View.GONE);
        charge = false;
        charger();
    }

    private void charger() {
        if (charge) return;
        charge = true;
        final int g = generation;
        final int debut = decalage;
        final String rq = recherche;
        final String cat = filtreCat;
        statut.setText("Chargement…");
        bPlus.setEnabled(false);
        new Thread(() -> {
            final JSONArray films = Supabase.lister(debut, PAR_PAGE, rq, cat);
            runOnUiThread(() -> {
                if (g != generation) return;
                charge = false;
                bPlus.setEnabled(true);
                if (films == null) {
                    erreur();
                    return;
                }
                for (int i = 0; i < films.length(); i++) liste.addView(carte(films.optJSONObject(i)),
                        Ui.lp(this, -1, -2, 0f, 10, 0));
                decalage += films.length();
                majPlus(films.length() >= PAR_PAGE);
                statut.setText(decalage == 0
                        ? (rq.isEmpty() ? "Aucun film pour l'instant. Collez un lien dans « Télécharger Facebook » : il apparaîtra ici."
                                : "Aucun résultat pour « " + rq + " ».")
                        : decalage + " film(s) affiché(s).");
            });
        }).start();
    }

    private void majPlus(boolean encore) {
        bPlus.setVisibility(encore ? View.VISIBLE : View.GONE);
    }

    private void erreur() {
        String e = Supabase.derniereErreur;
        if ("CLE_MANQUANTE".equals(e)) {
            statut.setText("⚠ La base n'est pas encore reliée : ouvrez Supabase.java et collez la clé « anon public » "
                    + "de votre projet dans CLE (Supabase › Project Settings › API).");
        } else if ("TABLE_MANQUANTE".equals(e)) {
            statut.setText("⚠ La table « films_facebook » n'existe pas encore : exécutez le fichier "
                    + "supabase_setup.sql dans Supabase › SQL Editor.");
        } else {
            statut.setText("⚠ " + (e.isEmpty() ? "Chargement impossible." : e));
        }
        bPlus.setVisibility(decalage > 0 ? View.VISIBLE : View.GONE);
    }

    /** Pas d'image enregistrée : une icône de film plutôt qu'un carré noir vide. */
    private void imageAbsente(ImageView img) {
        img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        img.setImageResource(android.R.drawable.ic_media_play);
    }

    private View carte(JSONObject f) {
        LinearLayout carte = new LinearLayout(this);
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setBackground(Ui.rond(Ui.CARD, 16, this));
        int q = Ui.dp(this, 12);
        carte.setPadding(q, q, q, q);

        LinearLayout haut = new LinearLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF000000);
        String mini = f.optString("miniature", "");
        if (!mini.isEmpty() && !"null".equals(mini)) {
            try {
                byte[] d = Base64.decode(mini, Base64.DEFAULT);
                Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length);
                if (bm != null) img.setImageBitmap(bm);
                else imageAbsente(img);
            } catch (Exception ignore) {
                imageAbsente(img);
            }
        } else {
            imageAbsente(img);
        }
        haut.addView(img, Ui.lp(this, Ui.dp(this, 130), Ui.dp(this, 90), 0f, 0, 0));

        LinearLayout infos = new LinearLayout(this);
        infos.setOrientation(LinearLayout.VERTICAL);
        String titre = f.optString("titre", "");
        if (titre.isEmpty() || "null".equals(titre)) titre = "Vidéo Facebook";
        String serie = f.optString("serie", ""), ep = f.optString("episode", "");
        if ("null".equals(serie)) serie = "";
        if ("null".equals(ep)) ep = "";
        if (!serie.isEmpty() || !ep.isEmpty()) {
            infos.addView(Ui.texte(this, (serie.isEmpty() ? "" : serie + " · ") + (ep.isEmpty() ? "" : "Épisode " + ep),
                    12, Ui.JAUNE, true), Ui.lp(this, -1, -2, 0f, 0, 0));
        }
        String cat = f.optString("categorie", "");
        String pfm = f.optString("plateforme", "");
        if ("null".equals(pfm)) pfm = "";
        String etiquette = "serie".equals(cat) ? "📺 Série" : "film".equals(cat) ? "🎬 Film" : "chanson".equals(cat) ? "🎵 Chanson" : "";
        if (!etiquette.isEmpty()) {
            infos.addView(Ui.texte(this, etiquette + (pfm.isEmpty() ? "" : " · " + pfm), 12, Ui.ACCENT, true),
                    Ui.lp(this, -1, -2, 0f, 0, 0));
        }
        TextView tt = Ui.texte(this, titre, 14, Ui.TXT, true);
        tt.setMaxLines(3);
        infos.addView(tt);
        infos.addView(Ui.texte(this, "⏱ " + Supabase.duree(f.optLong("duree_ms", 0)), 12, Ui.TXT2, false),
                Ui.lp(this, -1, -2, 0f, 4, 0));
        long sd = f.optLong("taille_sd", 0), hd = f.optLong("taille_hd", 0);
        infos.addView(Ui.texte(this, "SD : " + Supabase.taille(sd) + "\nHD : " + Supabase.taille(hd), 12, Ui.TXT2, false),
                Ui.lp(this, -1, -2, 0f, 2, 0));
        haut.addView(infos, Ui.lp(this, 0, -2, 1f, 0, 10));
        carte.addView(haut);

        final String lien = f.optString("lien", "");
        Button dl = Ui.bouton(this, "⬇ Télécharger", Ui.ACCENT);
        dl.setOnClickListener(v -> {
            Intent i = new Intent(this, FacebookActivity.class)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, lien);
            startActivity(i);
        });
        carte.addView(dl, Ui.lp(this, -1, -2, 0f, 10, 0));
        return carte;
    }
}
