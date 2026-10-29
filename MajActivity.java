package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Administrateur : cherche la dernière mise à jour dans la base, affiche la dernière mise à jour appliquée
 * (avec la date de création du fichier dans la base) et permet de modifier / publier le fichier de configuration (JSON).
 * Une publication s'applique toute seule à tous les utilisateurs, à leur prochaine ouverture de l'appli.
 */
public class MajActivity extends Activity {

    private static final String EXEMPLE = "{\n"
            + "  \"titre\": \"Sossokouamé\",\n"
            + "  \"bandeau\": \"Bienvenue !\",\n"
            + "  \"theme\": {\"fond_haut\": \"#6A6E77\", \"fond_bas\": \"#5C6068\", \"jaune\": \"#FFC857\", \"rayon\": 1},\n"
            + "  \"annonce\": {\"id\": \"a1\", \"titre\": \"Nouveau\", \"message\": \"Une nouveauté est arrivée.\"},\n"
            + "  \"tuiles\": [\n"
            + "    {\"id\": \"radio\", \"titre\": \"Radio\", \"c1\": \"#FF6B35\", \"c2\": \"#FF3D6E\"},\n"
            + "    {\"id\": \"sms\", \"masquer\": true},\n"
            + "    {\"id\": \"promo\", \"emoji\": \"⭐\", \"titre\": \"Promo\", \"desc\": \"Notre page spéciale\",\n"
            + "     \"c1\": \"#8E24AA\", \"c2\": \"#FF5252\", \"action\": \"page:promo\"}\n"
            + "  ],\n"
            + "  \"ordre\": [\"tvkouame\", \"radio\"],\n"
            + "  \"pages\": {\n"
            + "    \"promo\": {\"titre\": \"Promo\", \"html\": \"<h1>Bonjour</h1><p>Contenu de la page.</p>\"},\n"
            + "    \"site\": {\"titre\": \"Mon site\", \"zip\": \"site.zip\", \"index\": \"index.html\"}\n"
            + "  }\n"
            + "}";

    private TextView derniereTxt, statut, apkInfo;
    private EditText editeur, note, noteApk;
    private CheckBox apkObligatoire;
    private Button chercher, publier;
    private static final int CODE_ZIP = 21;
    private static final int CODE_APK = 22;
    private static final long APK_MAX = 50L * 1024 * 1024;   // limite du stockage Supabase (plan gratuit)

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

        Ui.ajouter(c, Ui.texte(this, "⬆ Mise à jour", 24, Ui.TXT, true), 0);
        Ui.ajouter(c, Ui.texte(this, "Le fichier de configuration est dans la base de données. À chaque ouverture, l'appli de chaque "
                + "utilisateur le lit et se met à jour toute seule : titres, tuiles, couleurs, annonces et pages entières.", 13, Ui.TXT2, false), 6);

        derniereTxt = Ui.texte(this, "", 14, Ui.JAUNE, false);
        Ui.ajouter(c, derniereTxt, 12);

        chercher = Ui.bouton(this, "🔍 Chercher une mise à jour", Ui.VIOLET);
        chercher.setOnClickListener(v -> chercher());
        Ui.ajouter(c, chercher, 12);

        statut = Ui.texte(this, "", 14, Ui.JAUNE, false);
        Ui.ajouter(c, statut, 10);

        Ui.ajouter(c, Ui.texte(this, "📱 Nouvelle version de l'application (APK)", 18, Ui.TXT, true), 20);
        Ui.ajouter(c, Ui.texte(this, "Choisissez le nouvel APK : il est copié dans la base, puis TOUS les comptes voient « Nouvelle mise à jour » "
                + "avec la date de sortie et la remplacent en un geste. Le numéro de version est lu dans l'APK lui-même "
                + "(versionCode dans app.gradle : il doit augmenter à chaque version).", 13, Ui.TXT2, false), 4);
        apkInfo = Ui.texte(this, "", 14, Ui.JAUNE, false);
        Ui.ajouter(c, apkInfo, 8);
        noteApk = Ui.champ(this, "Nouveautés de cette version (facultatif)");
        Ui.ajouter(c, noteApk, 8);
        apkObligatoire = new CheckBox(this);
        apkObligatoire.setText("Mise à jour obligatoire (impossible de la repousser)");
        apkObligatoire.setTextColor(Ui.TXT);
        Ui.ajouter(c, apkObligatoire, 4);
        Button apkBtn = Ui.bouton(this, "📱 Choisir l'APK et publier", Ui.VERT);
        apkBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, CODE_APK);
        });
        Ui.ajouter(c, apkBtn, 8);
        Button apkRetirer = Ui.boutonContour(this, "↩ Retirer la dernière version publiée", Ui.TXT);
        apkRetirer.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Retirer la dernière version ?")
                .setMessage("Les comptes ne verront plus cette mise à jour. Ceux qui l'ont déjà installée la gardent.")
                .setPositiveButton("Retirer", (d, w) -> new Thread(() -> {
                    final boolean ok = Supabase.adminMajRetirer();
                    runOnUiThread(() -> {
                        statut.setText(ok ? "✅ Dernière version retirée." : "⚠ Retrait impossible.");
                        rafraichirApk();
                    });
                }).start())
                .setNegativeButton("Annuler", null)
                .show());
        Ui.ajouter(c, apkRetirer, 8);

        Ui.ajouter(c, Ui.texte(this, "📦 Contenu (zip)", 18, Ui.TXT, true), 20);
        Ui.ajouter(c, Ui.texte(this, "Envoyez un zip de pages web (index.html, images, scripts). Il est copié dans la base et chaque appli le "
                + "télécharge toute seule. Renvoyer un zip du même nom le remplace pour tout le monde.", 13, Ui.TXT2, false), 4);
        Button zipBtn = Ui.bouton(this, "📦 Choisir et envoyer un zip", Ui.ACCENT);
        zipBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, CODE_ZIP);
        });
        Ui.ajouter(c, zipBtn, 8);

        Ui.ajouter(c, Ui.texte(this, "✏ Modifier le fichier (JSON)", 18, Ui.TXT, true), 20);
        editeur = Ui.champ(this, "Fichier JSON");
        editeur.setSingleLine(false);
        editeur.setMinLines(12);
        editeur.setGravity(Gravity.TOP | Gravity.START);
        editeur.setTypeface(Typeface.MONOSPACE);
        editeur.setTextSize(12);
        editeur.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Ui.ajouter(c, editeur, 8);

        Button exemple = Ui.boutonContour(this, "📄 Insérer un exemple", Ui.TXT);
        exemple.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Remplacer le texte par l'exemple ?")
                .setPositiveButton("Remplacer", (d, w) -> editeur.setText(EXEMPLE))
                .setNegativeButton("Annuler", null)
                .show());
        Ui.ajouter(c, exemple, 8);

        note = Ui.champ(this, "Note pour cette mise à jour (facultatif)");
        Ui.ajouter(c, note, 8);

        publier = Ui.bouton(this, "📤 Publier pour tous les utilisateurs", Ui.VERT);
        publier.setOnClickListener(v -> confirmerPublication());
        Ui.ajouter(c, publier, 8);

        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);

        remplirEditeur();
        rafraichirEntete();
        rafraichirApk();
        chercher();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rafraichirEntete();
    }

    private void rafraichirEntete() {
        String d = Config.info(this);
        String z = Paquets.info(this);
        derniereTxt.setText((d == null ? "Dernière mise à jour : aucune pour l'instant (l'appli utilise ses réglages d'origine)."
                : "✅ Dernière mise à jour :\n   " + d) + (z == null ? "" : "\n\nContenu :\n" + z));
    }

    private void remplirEditeur() {
        JSONObject cfg = Config.contenu(this);
        try {
            editeur.setText(cfg == null ? "{\n  \n}" : cfg.toString(2));
        } catch (Exception e) {
            editeur.setText("{\n  \n}");
        }
    }

    private void chercher() {
        statut.setText("Recherche dans la base de données…");
        chercher.setEnabled(false);
        new Thread(() -> {
            final JSONObject r = Supabase.configDerniere();
            final boolean nouveau = r != null && r.optBoolean("ok") && Config.rafraichir(this);
            final boolean contenu = r != null && r.optBoolean("ok") && Paquets.synchroniser(this);
            runOnUiThread(() -> {
                chercher.setEnabled(true);
                if (r == null || !r.optBoolean("ok")) {
                    statut.setText("⚠ Impossible de consulter la base : vérifiez internet.");
                } else if (r.optBoolean("aucune") && !nouveau) {
                    statut.setText("Aucune mise à jour publiée dans la base pour le moment.");
                } else if (nouveau || contenu) {
                    statut.setText("🆕 Nouvelle mise à jour appliquée. Elle s'affiche à l'accueil.");
                    if (nouveau) remplirEditeur();
                } else {
                    statut.setText("✅ Aucune nouvelle mise à jour : vous avez déjà la dernière.");
                }
                rafraichirEntete();
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int code, int resultat, Intent data) {
        super.onActivityResult(code, resultat, data);
        if (code == CODE_APK) {
            apkChoisi(resultat, data);
            return;
        }
        if (code != CODE_ZIP || resultat != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        String nom = "contenu.zip";
        long taille = -1;
        try (Cursor cur = getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && cur.getString(i) != null) nom = cur.getString(i);
                int j = cur.getColumnIndex(OpenableColumns.SIZE);
                if (j >= 0 && !cur.isNull(j)) taille = cur.getLong(j);
            }
        } catch (Exception ignore) {
            // on garde le nom par défaut
        }
        final String propre = nom.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!propre.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            statut.setText("⚠ Choisissez un fichier .zip.");
            return;
        }
        final long t = taille;
        statut.setText("Envoi de " + propre + "…");
        new Thread(() -> {
            String err;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                err = in == null ? "lecture" : Supabase.envoyerPaquet(propre, in, t);
            } catch (Exception e) {
                err = "lecture";
            }
            final String e2 = err;
            if (e2.isEmpty()) Paquets.synchroniser(this);
            runOnUiThread(() -> {
                if (e2.isEmpty()) {
                    statut.setText("✅ Zip envoyé : " + propre + "\nPour l'afficher, ajoutez dans le JSON puis publiez :\n"
                            + "\"pages\": {\"ma_page\": {\"titre\": \"Titre\", \"zip\": \"" + propre + "\"}}\n"
                            + "et une tuile avec \"action\": \"page:ma_page\". Si ce zip est déjà dans le JSON, "
                            + "tout le monde reçoit déjà la nouvelle version.");
                } else {
                    statut.setText("⚠ Envoi impossible (" + e2 + "). Taille maximale : 50 Mo.");
                }
                rafraichirEntete();
            });
        }).start();
    }

    // ------------------------------------------------------------------ nouvelle version de l'appli (APK)

    private void rafraichirApk() {
        new Thread(() -> {
            final JSONObject r = Supabase.majDerniere();
            runOnUiThread(() -> {
                String ici = "Installée sur ce téléphone : " + MiseAJour.nomInstalle(this) + " (n° " + MiseAJour.versionInstallee(this) + ")";
                if (r == null || !r.optBoolean("ok")) {
                    apkInfo.setText(ici + "\nVersion publiée : impossible de consulter la base.");
                } else if (r.optBoolean("aucune")) {
                    apkInfo.setText(ici + "\nAucune version publiée dans la base pour le moment.");
                } else {
                    apkInfo.setText(ici + "\nPubliée pour tous : " + r.optString("version_name") + " (n° " + r.optLong("version_code")
                            + "), sortie le " + Config.date(r.optLong("cree_ms", 0L)));
                }
            });
        }).start();
    }

    private void apkChoisi(int resultat, Intent data) {
        if (resultat != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        statut.setText("Lecture de l'APK…");
        new Thread(() -> {
            final File f = new File(getCacheDir(), "maj_admin.apk");
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
                if (in == null) throw new Exception("lecture");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                runOnUiThread(() -> statut.setText("⚠ Lecture du fichier impossible."));
                return;
            }
            PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null) {
                runOnUiThread(() -> statut.setText("⚠ Ce fichier n'est pas un APK valide."));
                return;
            }
            if (!getPackageName().equals(pi.packageName)) {
                final String autre = pi.packageName;
                runOnUiThread(() -> statut.setText("⚠ Cet APK est une autre application (" + autre + ")."));
                return;
            }
            final long taille = f.length();
            if (taille > APK_MAX) {
                runOnUiThread(() -> statut.setText("⚠ APK trop gros (" + Supabase.taille(taille) + ") : maximum 50 Mo."));
                return;
            }
            final long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            final String vn = pi.versionName == null ? String.valueOf(vc) : pi.versionName;
            final boolean memeSignature = MiseAJour.memeSignatureQueInstallee(this, f);
            runOnUiThread(() -> confirmerApk(f, (int) vc, vn, taille, memeSignature));
        }).start();
    }

    private void confirmerApk(final File f, final int versionCode, final String versionName, final long taille, boolean memeSignature) {
        StringBuilder sb = new StringBuilder();
        sb.append("Version ").append(versionName).append(" (n° ").append(versionCode).append(")\nTaille : ").append(Supabase.taille(taille));
        sb.append("\n\nTous les comptes verront « Nouvelle mise à jour » à leur prochaine ouverture.");
        if (!memeSignature) {
            sb.append("\n\n⚠ Cet APK n'a PAS la même signature que l'appli installée sur ce téléphone : les téléphones qui ont déjà l'appli "
                    + "ne pourront pas la remplacer sans la désinstaller. Compilez avec le fichier sossokouame.keystore.");
        }
        new AlertDialog.Builder(this)
                .setTitle("Publier cette version pour tous ?")
                .setMessage(sb.toString())
                .setPositiveButton("Publier", (d, w) -> publierApk(f, versionCode, versionName, taille))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void publierApk(final File f, final int versionCode, final String versionName, final long taille) {
        statut.setText("Envoi de l'APK (" + Supabase.taille(taille) + ")…");
        final String nomApk = "sossokouame-" + versionCode + ".apk";
        final String n = noteApk.getText().toString().trim();
        final boolean obligatoire = apkObligatoire.isChecked();
        new Thread(() -> {
            String err;
            try (InputStream in = new FileInputStream(f)) {
                err = Supabase.envoyerApk(nomApk, in, taille);
            } catch (Exception e) {
                err = "lecture";
            }
            if (err.isEmpty()) err = Supabase.adminMajPublier(versionCode, versionName, n, nomApk, taille, obligatoire);
            final String e2 = err;
            runOnUiThread(() -> {
                if (e2.isEmpty()) {
                    statut.setText("✅ Version " + versionName + " publiée : tous les comptes la verront à leur prochaine ouverture.");
                    noteApk.setText("");
                    apkObligatoire.setChecked(false);
                } else if ("version_trop_ancienne".equals(e2)) {
                    statut.setText("⚠ Une version égale ou plus récente est déjà publiée. Augmentez versionCode dans app.gradle, recompilez, puis recommencez.");
                } else if ("apk_introuvable".equals(e2) || e2.startsWith("http")) {
                    statut.setText("⚠ Envoi refusé (" + e2 + "). Avez-vous exécuté section10_mise_a_jour_apk.sql dans Supabase ?");
                } else {
                    statut.setText("⚠ Publication impossible (" + e2 + ").");
                }
                rafraichirApk();
            });
        }).start();
    }

    private void confirmerPublication() {
        final JSONObject contenu;
        try {
            contenu = new JSONObject(editeur.getText().toString().trim());
        } catch (Exception e) {
            statut.setText("⚠ Le fichier n'est pas du JSON valide : " + e.getMessage());
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Publier pour tous ?")
                .setMessage("Tous les utilisateurs recevront cette mise à jour à leur prochaine ouverture de l'appli.")
                .setPositiveButton("Publier", (d, w) -> publier(contenu))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void publier(final JSONObject contenu) {
        statut.setText("Publication…");
        publier.setEnabled(false);
        final String n = note.getText().toString().trim();
        new Thread(() -> {
            final String err = Supabase.adminConfigPublier(contenu, n);
            if (err.isEmpty()) Config.rafraichir(this);
            runOnUiThread(() -> {
                publier.setEnabled(true);
                if (err.isEmpty()) {
                    statut.setText("✅ Mise à jour publiée pour tous les utilisateurs.");
                    note.setText("");
                } else if ("json_invalide".equals(err)) {
                    statut.setText("⚠ Le fichier doit être un objet JSON { … }.");
                } else if ("trop_gros".equals(err)) {
                    statut.setText("⚠ Le fichier est trop gros.");
                } else {
                    statut.setText("⚠ Publication impossible (" + err + ").");
                }
                rafraichirEntete();
            });
        }).start();
    }
}
