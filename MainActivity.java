package bj.radio;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int COLONNES = 2;       // nombre de tuiles par ligne (mettez 3 pour des tuiles plus petites)

    private GridLayout grille;
    private int rang = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        int p = Ui.dp(this, 14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        TextView titre = Ui.texte(this, "RiRe a GOGO", 40, Ui.JAUNE, true);
        titre.setGravity(Gravity.CENTER);
        root.addView(titre);

        TextView sous = Ui.texte(this, "Radio & TV du monde", 16, Ui.TXT2, false);
        sous.setGravity(Gravity.CENTER);
        root.addView(sous, Ui.lp(this, -1, -2, 0f, 4, 0));

        grille = new GridLayout(this);
        grille.setColumnCount(COLONNES);
        root.addView(grille, Ui.lp(this, -1, -2, 0f, 20, 0));

        tuile("📻", "Radio", "Radios par pays et par ville", Ui.ACCENT, RadioActivity.class);
        tuile("📺", "Télévision", "Les chaînes qui marchent, en plein écran", Ui.VIOLET, TvActivity.class);
        tuile("📂", "Sans internet", "Vidéos, box, satellite, TNT, radio FM", 0xFF2E9E73, HorsLigneActivity.class);
        tuile("🎬", "Mes vidéos", "Vidéos du téléphone, rangées, avec traduction", 0xFFD6336C, VideosActivity.class);
        tuile("📘", "Vidéos Facebook", "Télécharger en qualité standard ou HD", 0xFF1877F2, FacebookActivity.class);
        tuile("🛠", "Compiler un projet", "Zip Android → APK à télécharger", 0xFF16A085, CompilateurActivity.class);
        tuile("🛡", "Analyse du téléphone", "Fichiers malveillants et cachés", 0xFFC0392B, AnalyseActivity.class);
        tuile("💬", "Messages SMS", "Lire vos SMS reçus et envoyés", 0xFF2980B9, SmsActivity.class);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.BG_HAUT, Ui.BG}));
        sv.addView(root);
        setContentView(sv);
    }

    /** Une tuile de la mosaïque : grande icône, titre, petite description. */
    private void tuile(String emoji, String titre, String desc, int couleur, Class<?> cible) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rond(couleur, 22, this));
        int q = Ui.dp(this, 10);
        t.setPadding(q, q, q, q);
        t.setClickable(true);
        t.setOnClickListener(v -> startActivity(new Intent(this, cible)));

        TextView e = Ui.texte(this, emoji, 40, Ui.TXT, false);
        e.setGravity(Gravity.CENTER);
        t.addView(e);

        TextView ti = Ui.texte(this, titre, 16, Ui.TXT, true);
        ti.setGravity(Gravity.CENTER);
        t.addView(ti, Ui.lp(this, -1, -2, 0f, 6, 0));

        TextView d = Ui.texte(this, desc, 11, 0xDDFFFFFF, false);
        d.setGravity(Gravity.CENTER);
        d.setMaxLines(3);
        t.addView(d, Ui.lp(this, -1, -2, 0f, 3, 0));

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(rang / COLONNES), GridLayout.spec(rang % COLONNES, 1f));
        lp.width = 0;
        lp.height = Ui.dp(this, 150);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        grille.addView(t, lp);
        rang++;
    }
}
