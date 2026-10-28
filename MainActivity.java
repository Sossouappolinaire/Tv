package bj.radio;

import android.animation.Animator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private LinearLayout mosaique;
    private LinearLayout ligne;
    private int rang = 0;
    private final List<Animator> animations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);

        // Il faut un compte pour entrer dans l'application.
        if (!Compte.connecte()) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }

        int p = Ui.dp(this, 14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        TextView titre = Ui.texte(this, "Sossokouamé appel", 34, Ui.JAUNE, true);
        titre.setGravity(Gravity.CENTER);
        root.addView(titre);
        animations.add(Ui.titreColore(titre));
        Ui.entree(titre, 0);

        String nom = Compte.nom();
        TextView sous = Ui.texte(this, nom.isEmpty() ? "Radio & TV du monde" : "Bonjour " + nom + " 👋", 16, Ui.TXT2, false);
        sous.setGravity(Gravity.CENTER);
        root.addView(sous, Ui.lp(this, -1, -2, 0f, 4, 0));
        Ui.entree(sous, 1);

        mosaique = new LinearLayout(this);
        mosaique.setOrientation(LinearLayout.VERTICAL);
        root.addView(mosaique, Ui.lp(this, -1, -2, 0f, 20, 0));

        tuile("📻", "Radio", "Radios par pays et par ville", Ui.ACCENT, 0xFFFF3D6E, RadioActivity.class);
        tuile("📺", "Télévision", "Les chaînes qui marchent, en plein écran", Ui.VIOLET, 0xFF3A7BFF, TvActivity.class);
        tuile("📂", "Sans internet", "Vidéos, box, satellite, TNT, radio FM", 0xFF2E9E73, 0xFF9BCB3C, HorsLigneActivity.class);
        tuile("🎬", "Mes vidéos", "Vidéos du téléphone, rangées, avec traduction", 0xFFD6336C, 0xFFFF8A3D, VideosActivity.class);
        tuile("📘", "Vidéos Facebook", "Télécharger en qualité standard ou HD", 0xFF1877F2, 0xFF5B3DF5, FacebookActivity.class);
        tuile("🎞", "Films Facebook", "Vidéos partagées par les autres utilisateurs", 0xFF0E5FCB, 0xFF12B5CB, FilmsFacebookActivity.class);
        tuile("🛠", "Compiler un projet", "Zip Android → APK à télécharger", 0xFF16A085, 0xFF2D8CFF, CompilateurActivity.class);
        tuile("🛡", "Analyse du téléphone", "Fichiers malveillants et cachés", 0xFFC0392B, 0xFFE67E22, AnalyseActivity.class);
        tuile("💬", "Messages SMS", "Lire vos SMS reçus et envoyés", 0xFF2980B9, 0xFF8E44AD, SmsActivity.class);
        if (Compte.admin()) tuile("👑", "Administration", "Comptes, en ligne, téléchargements, liens", 0xFF8E44AD, 0xFFE91E63, AdminActivity.class);
        tuile("⚙", "Paramètres", "Compte, code PIN et empreinte digitale", 0xFF6C7A89, 0xFF34495E, ParametresActivity.class);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        animations.add(Ui.fondAnime(sv));
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Compte.connecte()) return;
        new Thread(() -> {
            Supabase.battement();
            Compte.verifierAdmin();
        }).start();
    }

    @Override
    protected void onDestroy() {
        for (Animator a : animations) a.cancel();
        animations.clear();
        super.onDestroy();
    }

    /**
     * Une tuile de la mosaïque : dégradé de deux couleurs, grande icône qui flotte, titre, description.
     * Les rangées alternent leurs largeurs (1 / 1,35 puis 1,35 / 1) pour casser la grille régulière.
     */
    private void tuile(String emoji, String titre, String desc, int c1, int c2, Class<?> cible) {
        if (rang % 2 == 0) {
            ligne = new LinearLayout(this);
            ligne.setOrientation(LinearLayout.HORIZONTAL);
            mosaique.addView(ligne, Ui.lp(this, -1, -2, 0f, 0, 0));
        }
        int rangee = rang / 2;
        boolean gauche = rang % 2 == 0;
        float poids = (rangee % 2 == 0) == gauche ? 1f : 1.35f;
        int hauteur = rangee % 2 == 0 ? 150 : 162;

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.onde(c1, c2, 22, this));
        t.setElevation(Ui.dp(this, 4));
        int q = Ui.dp(this, 10);
        t.setPadding(q, q, q, q);
        t.setClickable(true);
        t.setOnClickListener(v -> startActivity(new Intent(this, cible)));
        Ui.pression(t);

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

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, hauteur), poids);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        ligne.addView(t, lp);

        Ui.entree(t, rang + 2);
        animations.add(Ui.flotter(e, rang));
        rang++;
    }
}
