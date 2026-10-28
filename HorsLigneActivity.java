package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class HorsLigneActivity extends Activity {

    // Applications "Radio FM" connues sur certains téléphones (non garanti)
    static final String[] FM_PAQUETS = {
        "com.android.fmradio", "com.mediatek.fmradio", "com.sec.android.app.fm",
        "com.lge.fmradio", "com.sonyericsson.fmradio", "com.huawei.android.fmradio"
    };

    private TextView nbFacebook, nbAutres;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);

        Ui.ajouter(root, Ui.texte(this, "📥 Téléchargements", 24, Ui.TXT, true), 0);
        Ui.ajouter(root, Ui.texte(this,
                "Toutes vos vidéos à regarder sans internet, classées selon leur origine.", 13, Ui.TXT2, false), 6);

        nbFacebook = carte(root, "📘", "Vidéos Facebook téléchargées",
                "", 0xFF1877F2,
                () -> startActivity(new Intent(this, ListeActivity.class)
                        .putExtra("mode", "fichiers").putExtra("filtre", "facebook")));
        carte(root, "🖼", "Vidéos de ma galerie",
                "Les vidéos déjà présentes dans le téléphone, rangées par dossier", 0xFFD6336C,
                () -> startActivity(new Intent(this, VideosActivity.class)));
        nbAutres = carte(root, "🔗", "Autres téléchargements",
                "", 0xFF2E9E73,
                () -> startActivity(new Intent(this, ListeActivity.class)
                        .putExtra("mode", "fichiers").putExtra("filtre", "autres")));

        Ui.ajouter(root, Ui.texte(this, "Nouveau téléchargement", 16, Ui.TXT, true), 22);
        carte(root, "⬇", "Télécharger par lien direct",
                "Collez le lien d'un fichier vidéo ou audio (.mp4, .mp3…). Pour Facebook, utilisez « Télécharger Facebook » sur l'accueil.",
                0xFFC9A227, this::dialogTelecharger);

        Ui.ajouter(root, Ui.texte(this, "Écouter sans internet", 16, Ui.TXT, true), 22);
        carte(root, "📻", "Radio FM du téléphone",
                "Ouvre l'appli FM de votre téléphone, s'il en a une", Ui.ACCENT,
                this::ouvrirFm);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.fond());
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int fb = 0, autres = 0;
        File dossier = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        File[] fichiers = dossier == null ? null : dossier.listFiles();
        if (fichiers != null) {
            for (File f : fichiers) {
                if (!f.isFile()) continue;
                if (f.getName().startsWith("facebook_")) fb++; else autres++;
            }
        }
        nbFacebook.setText(compte(fb, "Téléchargées depuis « Télécharger Facebook »"));
        nbAutres.setText(compte(autres, "Liens directs et fichiers que vous avez ajoutés"));
    }

    private static String compte(int n, String texte) {
        return (n == 0 ? "Aucune vidéo" : n + (n == 1 ? " vidéo" : " vidéos")) + " · " + texte;
    }

    /** Une carte cliquable ; renvoie le texte de description pour pouvoir le mettre à jour. */
    private TextView carte(LinearLayout parent, String emoji, String titre, String desc,
                           int couleur, Runnable action) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(Ui.rond(couleur, 20, this));
        int q = Ui.dp(this, 16);
        c.setPadding(q, q, q, q);
        c.setClickable(true);
        c.setOnClickListener(v -> action.run());
        c.addView(Ui.texte(this, emoji, 30, Ui.TXT, false));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.texte(this, titre, 18, Ui.TXT, true));
        TextView d = Ui.texte(this, desc, 12, 0xDDFFFFFF, false);
        t.addView(d);
        c.addView(t, Ui.lp(this, 0, -2, 1f, 0, 14));
        Ui.ajouter(parent, c, 12);
        return d;
    }

    // ---------- Radio FM ----------

    void ouvrirFm() {
        PackageManager pm = getPackageManager();
        for (String pk : FM_PAQUETS) {
            Intent i = pm.getLaunchIntentForPackage(pk);
            if (i != null) { startActivity(i); return; }
        }
        new AlertDialog.Builder(this)
                .setTitle("Radio FM du téléphone")
                .setMessage("Cette application ne peut pas régler elle-même la puce FM : Android ne le "
                        + "permet pas aux applications ordinaires.\n\n"
                        + "Je n'ai trouvé aucune appli FM connue sur ce téléphone. Cherchez « Radio FM » "
                        + "dans vos applications (souvent dans un dossier « Outils »). Il faut un téléphone "
                        + "qui a une puce FM, et brancher des écouteurs filaires, qui servent d'antenne.")
                .setPositiveButton("OK", null).show();
    }

    // ---------- Téléchargement ----------

    void dialogTelecharger() {
        EditText lien = new EditText(this);
        lien.setHint("https://…/video.mp4");
        lien.setSingleLine(true);
        LinearLayout l = new LinearLayout(this);
        int m = Ui.dp(this, 20);
        l.setPadding(m, m / 2, m, 0);
        l.addView(lien, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Télécharger par lien direct")
                .setMessage("Collez un lien direct vers un fichier vidéo ou audio. Les chaînes en direct "
                        + "ne peuvent pas être téléchargées.")
                .setView(l)
                .setPositiveButton("Télécharger", (d, w) -> telecharger(lien.getText().toString().trim()))
                .setNegativeButton("Annuler", null).show();
    }

    void telecharger(String url) {
        if (url.isEmpty()) return;
        try {
            String nom = Uri.parse(url).getLastPathSegment();
            if (nom == null || !nom.contains(".")) nom = "video_" + System.currentTimeMillis() + ".mp4";
            nom = nom.replaceAll("[^A-Za-z0-9._-]", "_");
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(nom);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MOVIES, nom);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Téléchargement lancé. Retrouvez-le dans « Autres téléchargements ».",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Lien invalide", Toast.LENGTH_SHORT).show();
        }
    }
}
