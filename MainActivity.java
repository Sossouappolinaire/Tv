package bj.radio;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        int p = Ui.dp(this, 24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(p, p, p, p);

        TextView titre = Ui.texte(this, "RiRe a GOGO", 40, Ui.JAUNE, true);
        titre.setGravity(Gravity.CENTER);
        root.addView(titre);

        TextView sous = Ui.texte(this, "Radio & TV du monde", 16, Ui.TXT2, false);
        sous.setGravity(Gravity.CENTER);
        root.addView(sous, Ui.lp(this, -1, -2, 0f, 4, 0));

        int h = Ui.dp(this, 100);
        root.addView(carte("📻", "Radio", "Des milliers de radios, par pays et par ville",
                Ui.ACCENT, RadioActivity.class), Ui.lp(this, -1, h, 0f, 28, 0));
        root.addView(carte("📺", "Télévision", "Les chaînes qui marchent, en plein écran",
                Ui.VIOLET, TvActivity.class), Ui.lp(this, -1, h, 0f, 14, 0));
        root.addView(carte("📂", "Sans internet", "Vidéos, box, satellite, TNT, radio FM",
                0xFF2E9E73, HorsLigneActivity.class), Ui.lp(this, -1, h, 0f, 14, 0));
        root.addView(carte("📘", "Vidéos Facebook", "Télécharger une vidéo en qualité standard ou HD",
                0xFF1877F2, FacebookActivity.class), Ui.lp(this, -1, h, 0f, 14, 0));
        root.addView(carte("🛠", "Compiler un projet", "Zip Android → APK à télécharger",
                0xFF16A085, CompilateurActivity.class), Ui.lp(this, -1, h, 0f, 14, 0));
        root.addView(carte("🛡", "Analyse du téléphone", "Applications suspectes et fichiers cachés à supprimer",
                0xFFC0392B, AnalyseActivity.class), Ui.lp(this, -1, h, 0f, 14, 0));
        root.addView(carte("💬", "Messages SMS", "Lire vos SMS reçus et envoyés",
                0xFF2980B9, SmsActivity.class), Ui.lp(this, -1, h, 0f, 14, 0));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.BG_HAUT, Ui.BG}));
        sv.addView(root);
        setContentView(sv);
    }

    private LinearLayout carte(String emoji, String titre, String desc, int couleur, Class<?> cible) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(Ui.rond(couleur, 26, this));
        int q = Ui.dp(this, 18);
        c.setPadding(q, q, q, q);
        c.setClickable(true);
        c.setOnClickListener(v -> startActivity(new Intent(this, cible)));

        c.addView(Ui.texte(this, emoji, 38, Ui.TXT, false));

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.texte(this, titre, 22, Ui.TXT, true));
        t.addView(Ui.texte(this, desc, 13, 0xDDFFFFFF, false));
        c.addView(t, Ui.lp(this, 0, -2, 1f, 0, 14));
        return c;
    }
}
