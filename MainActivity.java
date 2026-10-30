package bj.radio;

import android.animation.Animator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private LinearLayout mosaique;
    private LinearLayout ligne;
    private TextView bandeauMaj;
    private int rang = 0;
    /** Une tuile de l'accueil (réglages d'origine, modifiables par le fichier de configuration de la base). */
    private static final class T {
        String id, emoji, titre, desc, action = "";
        int c1, c2;
        Class<?> cible;
        boolean visible = true;
    }

    private final Map<String, Class<?>> ecrans = new HashMap<>();
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

        // TV Kouamé : notification « Programme du jour » chaque jour à 00h00 (heure du Bénin)
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 11);
        }
        ProgrammeAlarme.planifier(this);

        int p = Ui.dp(this, 14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p * 2, p, p);

        final JSONObject cfg = Config.contenu(this);
        String titreApp = Config.titreApp(this);
        TextView titre = Ui.texte(this, titreApp, 34, Ui.JAUNE, true);
        titre.setGravity(Gravity.CENTER);
        root.addView(titre);
        animations.add(Ui.titreColore(titre));
        Ui.entree(titre, 0);

        String nom = Compte.nom();
        String sousDefaut = cfg != null && !cfg.optString("sous_titre", "").trim().isEmpty() ? cfg.optString("sous_titre").trim() : "Radio, TV & vidéos";
        TextView sous = Ui.texte(this, nom.isEmpty() ? sousDefaut : "Bonjour " + nom + " 👋", 16, Ui.TXT2, false);
        sous.setGravity(Gravity.CENTER);
        root.addView(sous, Ui.lp(this, -1, -2, 0f, 4, 0));
        Ui.entree(sous, 1);

        String bandeau = cfg == null ? "" : cfg.optString("bandeau", "").trim();
        if (!bandeau.isEmpty()) {
            TextView bd = Ui.texte(this, bandeau, 14, Ui.JAUNE, true);
            bd.setGravity(Gravity.CENTER);
            root.addView(bd, Ui.lp(this, -1, -2, 0f, 10, 0));
        }

        // Mise à jour de l'appli : bandeau visible tant que la nouvelle version n'est pas installée.
        bandeauMaj = Ui.texte(this, "", 15, 0xFF222222, true);
        bandeauMaj.setGravity(Gravity.CENTER);
        bandeauMaj.setBackground(Ui.rond(Ui.JAUNE, 16, this));
        int pm = Ui.dp(this, 12);
        bandeauMaj.setPadding(pm, pm, pm, pm);
        bandeauMaj.setVisibility(View.GONE);
        bandeauMaj.setOnClickListener(v -> {
            JSONObject r = MiseAJour.derniere;
            if (r != null && MiseAJour.disponible(this, r)) MiseAJour.proposer(this, r);
        });
        root.addView(bandeauMaj, Ui.lp(this, -1, -2, 0f, 14, 0));
        afficherBandeauMaj();

        mosaique = new LinearLayout(this);
        mosaique.setOrientation(LinearLayout.VERTICAL);
        root.addView(mosaique, Ui.lp(this, -1, -2, 0f, 20, 0));

        List<T> liste = new ArrayList<>();
        def(liste, "radio", "📻", "Radio", "Radios par pays et par ville", Ui.ACCENT, 0xFFFF3D6E, RadioActivity.class);
        def(liste, "tv", "📺", "Télévision", "Chaînes du monde, en plein écran", Ui.VIOLET, 0xFF3A7BFF, TvActivity.class);
        def(liste, "tvkouame", "📡", "TV Kouamé", "Programme du jour, nouveau chaque jour à 00h00", 0xFFE91E63, 0xFFFF9800, TvKouameActivity.class);
        def(liste, "jeux", "🎮", "JEUX", "Panda Dash, Super Kouamé, Serpent, Commando, Liquide et Combat Mortel", 0xFF8E24AA, 0xFFFF5252, JeuxActivity.class);
        def(liste, "facebook", "📘", "Télécharger Facebook", "Une vidéo Facebook, en qualité standard ou HD", 0xFF1877F2, 0xFF5B3DF5, FacebookActivity.class);
        def(liste, "films", "🎞", "Films partagés", "Les films ajoutés par les utilisateurs", 0xFF0E5FCB, 0xFF12B5CB, FilmsFacebookActivity.class);
        def(liste, "telechargements", "📥", "Téléchargements", "Facebook, galerie et liens, classés à part", 0xFF2E9E73, 0xFF9BCB3C, HorsLigneActivity.class);
        def(liste, "compilateur", "🛠", "Compiler un projet", "Zip Android → APK à télécharger", 0xFF16A085, 0xFF2D8CFF, CompilateurActivity.class);
        def(liste, "analyse", "🛡", "Analyse du téléphone", "Fichiers malveillants et cachés", 0xFFC0392B, 0xFFE67E22, AnalyseActivity.class);
        def(liste, "sms", "💬", "Messages SMS", "Lire vos SMS reçus et envoyés", 0xFF2980B9, 0xFF8E44AD, SmsActivity.class);
        if (Compte.admin()) def(liste, "admin", "👑", "Administration", "Comptes, en ligne, téléchargements, liens", 0xFF8E44AD, 0xFFE91E63, AdminActivity.class);
        def(liste, "parametres", "⚙", "Paramètres", "Compte, code PIN et empreinte digitale", 0xFF6C7A89, 0xFF34495E, ParametresActivity.class);

        liste = appliquerConfig(liste, cfg);
        for (T t : liste) if (t.visible) tuile(t);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        animations.add(Ui.fondAnime(sv));
        setContentView(sv);
        afficherAnnonce(cfg);
    }

    /** Affiche (ou cache) le bandeau « Nouvelle mise à jour » selon la dernière réponse de la base. */
    private void afficherBandeauMaj() {
        if (bandeauMaj == null) return;
        JSONObject r = MiseAJour.derniere;
        if (r != null && MiseAJour.disponible(this, r)) {
            bandeauMaj.setText("🆕 " + MiseAJour.resume(r) + "\nTouchez ici pour mettre à jour");
            bandeauMaj.setVisibility(View.VISIBLE);
        } else {
            bandeauMaj.setVisibility(View.GONE);
        }
    }

    private void def(List<T> liste, String id, String emoji, String titre, String desc, int c1, int c2, Class<?> cible) {
        T t = new T();
        t.id = id;
        t.emoji = emoji;
        t.titre = titre;
        t.desc = desc;
        t.c1 = c1;
        t.c2 = c2;
        t.cible = cible;
        liste.add(t);
        ecrans.put(id, cible);
    }

    private static int couleur(String s, int defaut) {
        try {
            return s == null || s.isEmpty() ? defaut : Color.parseColor(s.trim());
        } catch (Exception e) {
            return defaut;
        }
    }

    private static T trouver(List<T> liste, String id) {
        for (T t : liste) if (t.id.equals(id)) return t;
        return null;
    }

    /** Applique le fichier de configuration de la base : modifier, masquer, ajouter et ordonner les tuiles. */
    private List<T> appliquerConfig(List<T> liste, JSONObject cfg) {
        if (cfg == null) return liste;
        JSONArray arr = cfg.optJSONArray("tuiles");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "").trim();
            if (id.isEmpty()) continue;
            T t = trouver(liste, id);
            if (t == null) {
                t = new T();
                t.id = id;
                t.emoji = "⭐";
                t.titre = id;
                t.desc = "";
                t.c1 = Ui.VIOLET;
                t.c2 = 0xFF3A7BFF;
                liste.add(t);
            }
            boolean protege = id.equals("admin") || id.equals("parametres");   // jamais masqués : on ne peut pas se bloquer l'accès
            if (o.has("masquer") && !protege) t.visible = !o.optBoolean("masquer");
            if (o.optBoolean("admin_seulement") && !Compte.admin()) t.visible = false;
            if (o.has("emoji")) t.emoji = o.optString("emoji", t.emoji);
            if (o.has("titre")) t.titre = o.optString("titre", t.titre);
            if (o.has("desc")) t.desc = o.optString("desc", t.desc);
            if (o.has("c1")) t.c1 = couleur(o.optString("c1"), t.c1);
            if (o.has("c2")) t.c2 = couleur(o.optString("c2"), t.c2);
            if (o.has("action")) t.action = o.optString("action", "");
        }
        JSONArray ordre = cfg.optJSONArray("ordre");
        if (ordre == null) return liste;
        List<T> res = new ArrayList<>();
        for (int i = 0; i < ordre.length(); i++) {
            T t = trouver(liste, ordre.optString(i, ""));
            if (t != null && !res.contains(t)) res.add(t);
        }
        for (T t : liste) if (!res.contains(t)) res.add(t);
        return res;
    }

    /** Annonce : fenêtre affichée une seule fois par identifiant. */
    private void afficherAnnonce(JSONObject cfg) {
        JSONObject a = cfg == null ? null : cfg.optJSONObject("annonce");
        if (a == null) return;
        String id = a.optString("id", "").trim();
        String msg = a.optString("message", "").trim();
        if (id.isEmpty() || msg.isEmpty() || id.equals(Config.annonceVue(this))) return;
        Config.annonceVue(this, id);
        new AlertDialog.Builder(this)
                .setTitle(a.optString("titre", "Annonce"))
                .setMessage(msg)
                .setPositiveButton(a.optString("bouton", "OK"), null)
                .show();
    }

    private void ouvrir(T t) {
        String a = t.action == null ? "" : t.action.trim();
        try {
            if (a.startsWith("page:")) {
                startActivity(new Intent(this, PageWebActivity.class).putExtra("id", a.substring(5).trim()));
            } else if (a.startsWith("url:")) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(a.substring(4).trim())));
            } else if (a.startsWith("ecran:")) {
                Class<?> k = ecrans.get(a.substring(6).trim());
                if (k != null) startActivity(new Intent(this, k));
            } else if (t.cible != null) {
                startActivity(new Intent(this, t.cible));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Compte.connecte()) return;
        new Thread(() -> {
            Supabase.battement();
            Compte.verifierAdmin();
            // Mise à jour automatique : si le fichier de la base a changé, l'accueil se reconstruit tout seul.
            boolean nouveau = Config.rafraichir(this);
            Paquets.synchroniser(this);   // zips de contenu : téléchargés / remplacés s'il y a du nouveau
            if (nouveau) runOnUiThread(() -> {
                if (!isFinishing()) recreate();
            });
            // Nouvelle version de l'application (APK dans la base) : bandeau + fenêtre « Mettre à jour » avec la date de sortie.
            final JSONObject maj = Supabase.majDerniere();
            MiseAJour.derniere = maj;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                afficherBandeauMaj();
                if (!nouveau && MiseAJour.disponible(this, maj)) MiseAJour.verifierAvecReponse(this, maj);
            });
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
    private void tuile(final T t) {
        if (rang % 2 == 0) {
            ligne = new LinearLayout(this);
            ligne.setOrientation(LinearLayout.HORIZONTAL);
            mosaique.addView(ligne, Ui.lp(this, -1, -2, 0f, 0, 0));
        }
        int rangee = rang / 2;
        boolean gauche = rang % 2 == 0;
        float poids = (rangee % 2 == 0) == gauche ? 1f : 1.35f;
        int hauteur = rangee % 2 == 0 ? 150 : 162;

        LinearLayout carte = new LinearLayout(this);
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setGravity(Gravity.CENTER);
        carte.setBackground(Ui.onde(t.c1, t.c2, 22, this));
        carte.setElevation(Ui.dp(this, 4));
        int q = Ui.dp(this, 10);
        carte.setPadding(q, q, q, q);
        carte.setClickable(true);
        carte.setOnClickListener(v -> ouvrir(t));
        Ui.pression(carte);

        TextView e = Ui.texte(this, t.emoji, 40, Ui.TXT, false);
        e.setGravity(Gravity.CENTER);
        carte.addView(e);

        TextView ti = Ui.texte(this, t.titre, 16, Ui.TXT, true);
        ti.setGravity(Gravity.CENTER);
        carte.addView(ti, Ui.lp(this, -1, -2, 0f, 6, 0));

        TextView d = Ui.texte(this, t.desc, 11, 0xDDFFFFFF, false);
        d.setGravity(Gravity.CENTER);
        d.setMaxLines(3);
        carte.addView(d, Ui.lp(this, -1, -2, 0f, 3, 0));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, hauteur), poids);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        ligne.addView(carte, lp);

        Ui.entree(carte, rang + 2);
        animations.add(Ui.flotter(e, rang));
        rang++;
    }
}
