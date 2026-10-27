package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Compile un projet Android (zip) en APK, via GitHub Actions :
 * l'app envoie le projet sur une branche temporaire du dépôt de l'utilisateur,
 * attend le build, puis télécharge l'APK dans « Téléchargements ».
 */
public class CompilateurActivity extends Activity {

    static class Fichier {
        String chemin, texte;
        byte[] octets;
    }

    static final Set<String> DOSSIERS_IGNORES = new HashSet<>(
            Arrays.asList(".git", ".gradle", ".idea", "build", "__MACOSX"));

    final List<Fichier> fichiers = new ArrayList<>();
    SharedPreferences prefs;
    volatile boolean enCours = false;
    String token = "", nomZip = "projet", urlBuild = null;

    EditText etToken, etRepo;
    TextView tvZip, tvStatut, tvJournal;
    Button bCompiler, bVoir, bTelechargements;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        prefs = getSharedPreferences("compil", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);

        Ui.ajouter(root, Ui.texte(this, "🛠 Compiler un projet", 24, Ui.TXT, true), 0);
        Ui.ajouter(root, Ui.texte(this,
                "Importez le zip d'un projet Android : l'application l'envoie sur votre dépôt GitHub, "
                + "GitHub le compile, puis l'APK est téléchargé dans « Téléchargements ». "
                + "Le contenu actuel de votre dépôt n'est pas touché (le projet va sur une branche "
                + "temporaire).", 13, Ui.TXT2, false), 6);

        Button bAide = Ui.bouton(this, "🔑 Comment obtenir un token GitHub ?", Ui.CARD);
        bAide.setOnClickListener(v -> aideToken());
        Ui.ajouter(root, bAide, 10);

        etToken = Ui.champ(this, "Token GitHub");
        etToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etToken.setText(prefs.getString("token", ""));
        Ui.ajouter(root, etToken, 10);

        etRepo = Ui.champ(this, "Dépôt : utilisateur/nom-du-depot");
        etRepo.setText(prefs.getString("repo", ""));
        Ui.ajouter(root, etRepo, 8);

        Button bZip = Ui.bouton(this, "📦 Choisir le fichier zip", Ui.VIOLET);
        bZip.setOnClickListener(v -> choisirZip());
        Ui.ajouter(root, bZip, 12);

        tvZip = Ui.texte(this, "Aucun zip choisi", 13, Ui.TXT2, false);
        Ui.ajouter(root, tvZip, 6);

        bCompiler = Ui.bouton(this, "🚀 Compiler en APK", Ui.ACCENT);
        bCompiler.setOnClickListener(v -> lancer());
        Ui.ajouter(root, bCompiler, 12);

        tvStatut = Ui.texte(this, "", 15, Ui.JAUNE, true);
        Ui.ajouter(root, tvStatut, 12);

        tvJournal = Ui.texte(this, "", 12, Ui.TXT2, false);
        Ui.ajouter(root, tvJournal, 6);

        bVoir = Ui.bouton(this, "🔗 Voir le build sur GitHub", Ui.CARD);
        bVoir.setVisibility(View.GONE);
        bVoir.setOnClickListener(v -> {
            if (urlBuild != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(urlBuild)));
        });
        Ui.ajouter(root, bVoir, 10);

        bTelechargements = Ui.bouton(this, "📁 Ouvrir Téléchargements", Ui.VIOLET);
        bTelechargements.setVisibility(View.GONE);
        bTelechargements.setOnClickListener(v ->
                startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)));
        Ui.ajouter(root, bTelechargements, 8);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(root);
        setContentView(sv);
    }

    void aideToken() {
        new AlertDialog.Builder(this)
                .setTitle("Token GitHub")
                .setMessage("1. Sur github.com, créez un dépôt (public ou privé) avec un fichier README.\n\n"
                        + "2. Réglages → Developer settings → Personal access tokens → Fine-grained tokens "
                        + "→ Generate new token.\n\n"
                        + "3. Limitez-le à ce seul dépôt, avec les droits :\n"
                        + "   • Contents : Read and write\n"
                        + "   • Workflows : Read and write\n"
                        + "   • Actions : Read-only\n\n"
                        + "4. Copiez le token ici. Il est gardé dans les données de l'application sur ce "
                        + "téléphone : ne l'utilisez pas pour un dépôt important, et supprimez-le sur "
                        + "GitHub quand vous n'en avez plus besoin.\n\n"
                        + "(Avec un token classique : cochez « repo » et « workflow ».)")
                .setPositiveButton("OK", null).show();
    }

    // ---------- Lecture du zip ----------

    void choisirZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip",
                "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(i, 7);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 7 || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        tvZip.setText("Lecture du zip…");
        new Thread(() -> {
            try {
                List<Fichier> tmp = lireZip(uri);
                String nom = nomDe(uri);
                runOnUiThread(() -> {
                    fichiers.clear();
                    fichiers.addAll(tmp);
                    nomZip = nom.toLowerCase().endsWith(".zip") ? nom.substring(0, nom.length() - 4) : nom;
                    String type = typeProjet();
                    tvZip.setText("📦 " + nom + " · " + fichiers.size() + " fichiers · "
                            + (type.equals("plat") ? "projet à plat"
                               : type.equals("standard") ? "projet Gradle standard"
                               : "type non reconnu"));
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvZip.setText("⚠ Zip illisible : " + e.getMessage()));
            }
        }).start();
    }

    String nomDe(Uri u) {
        String nom = null;
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) nom = c.getString(ix);
            }
        } catch (Exception ignored) {}
        return nom == null || nom.isEmpty() ? "projet.zip" : nom;
    }

    List<Fichier> lireZip(Uri uri) throws Exception {
        List<Fichier> tmp = new ArrayList<>();
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(getContentResolver().openInputStream(uri))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String chemin = e.getName().replace('\\', '/');
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = z.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 25_000_000) throw new Exception("un fichier dépasse 25 Mo");
                }
                total += bo.size();
                if (total > 80_000_000) throw new Exception("le projet dépasse 80 Mo");
                if (ignorer(chemin)) continue;
                Fichier f = new Fichier();
                f.chemin = chemin;
                f.octets = bo.toByteArray();
                tmp.add(f);
            }
        }
        retirerDossierRacine(tmp);
        for (Fichier f : tmp) f.texte = versTexte(f.octets);
        if (tmp.isEmpty()) throw new Exception("le zip est vide");
        return tmp;
    }

    static boolean ignorer(String chemin) {
        if (chemin.startsWith("/")) return true;
        String[] seg = chemin.split("/");
        for (int i = 0; i < seg.length; i++) {
            if (seg[i].equals("..")) return true;
            if (i < seg.length - 1 && DOSSIERS_IGNORES.contains(seg[i])) return true;
        }
        String nom = seg[seg.length - 1];
        return nom.equals("local.properties") || nom.equals(".DS_Store")
                || nom.toLowerCase().endsWith(".apk");
    }

    // Un zip téléchargé depuis GitHub contient un dossier unique : on l'enlève
    static void retirerDossierRacine(List<Fichier> l) {
        if (l.isEmpty()) return;
        int s = l.get(0).chemin.indexOf('/');
        if (s <= 0) return;
        String prefixe = l.get(0).chemin.substring(0, s + 1);
        for (Fichier f : l) if (!f.chemin.startsWith(prefixe)) return;
        for (Fichier f : l) f.chemin = f.chemin.substring(prefixe.length());
    }

    static String versTexte(byte[] o) {
        if (o.length == 0) return null;
        for (byte x : o) if (x == 0) return null;
        try {
            return Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(o)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    String typeProjet() {
        boolean app = false, man = false, java = false, settings = false;
        for (Fichier f : fichiers) {
            String c = f.chemin;
            if (c.equals("app.gradle")) app = true;
            if (c.equals("AndroidManifest.xml")) man = true;
            if (!c.contains("/") && c.endsWith(".java")) java = true;
            if (c.equals("settings.gradle") || c.equals("settings.gradle.kts")) settings = true;
        }
        if (app && man && java) return "plat";
        if (settings) return "standard";
        return "inconnu";
    }

    boolean aUnWorkflow() {
        for (Fichier f : fichiers) if (f.chemin.startsWith(".github/workflows/")) return true;
        return false;
    }

    // ---------- Fichiers de workflow générés ----------

    static final String WORKFLOW_STANDARD =
            "name: Build APK\n"
            + "on: [push, workflow_dispatch]\n"
            + "jobs:\n"
            + "  build:\n"
            + "    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-java@v4\n"
            + "        with:\n"
            + "          distribution: temurin\n"
            + "          java-version: 17\n"
            + "      - uses: gradle/actions/setup-gradle@v4\n"
            + "        with:\n"
            + "          gradle-version: 8.7\n"
            + "      - run: |\n"
            + "          if [ -f gradlew ]; then chmod +x gradlew && ./gradlew assembleDebug --stacktrace;\n"
            + "          else gradle assembleDebug --stacktrace; fi\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n"
            + "          name: apk\n"
            + "          path: '**/build/outputs/apk/debug/*.apk'\n"
            + "          if-no-files-found: error\n";

    static final String WORKFLOW_PLAT =
            "name: Build APK\n"
            + "on: [push, workflow_dispatch]\n"
            + "jobs:\n"
            + "  build:\n"
            + "    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - name: Assembler le projet Android\n"
            + "        run: |\n"
            + "          mkdir -p app/src/main/java/bj/radio\n"
            + "          mv ./*.java app/src/main/java/bj/radio/\n"
            + "          mv AndroidManifest.xml app/src/main/AndroidManifest.xml\n"
            + "          mv app.gradle app/build.gradle\n"
            + "          if [ -f ic_launcher.png ]; then\n"
            + "            mkdir -p app/src/main/res/mipmap-xxxhdpi app/src/main/res/mipmap-anydpi-v26 app/src/main/res/drawable-xxxhdpi app/src/main/res/values\n"
            + "            mv ic_launcher.png app/src/main/res/mipmap-xxxhdpi/ic_launcher.png\n"
            + "            mv ic_launcher_foreground.png app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png\n"
            + "            mv ic_launcher_adaptive.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml\n"
            + "            mv ic_launcher_colors.xml app/src/main/res/values/ic_launcher_colors.xml\n"
            + "          fi\n"
            + "      - uses: actions/setup-java@v4\n"
            + "        with:\n"
            + "          distribution: temurin\n"
            + "          java-version: 17\n"
            + "      - uses: gradle/actions/setup-gradle@v4\n"
            + "        with:\n"
            + "          gradle-version: 8.7\n"
            + "      - run: gradle assembleDebug --stacktrace\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n"
            + "          name: apk\n"
            + "          path: 'app/build/outputs/apk/debug/*.apk'\n"
            + "          if-no-files-found: error\n";

    // ---------- Compilation ----------

    void lancer() {
        if (enCours) return;
        String t = etToken.getText().toString().trim();
        String repo = etRepo.getText().toString().trim();
        if (fichiers.isEmpty()) { Toast.makeText(this, "Choisissez d'abord un zip", Toast.LENGTH_SHORT).show(); return; }
        if (t.isEmpty()) { Toast.makeText(this, "Entrez votre token GitHub", Toast.LENGTH_SHORT).show(); return; }
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            Toast.makeText(this, "Dépôt : utilisateur/nom-du-depot", Toast.LENGTH_SHORT).show();
            return;
        }
        if (typeProjet().equals("inconnu") && !aUnWorkflow()) {
            Toast.makeText(this, "Projet non reconnu : il faut un settings.gradle à la racine", Toast.LENGTH_LONG).show();
            return;
        }
        token = t;
        prefs.edit().putString("token", t).putString("repo", repo).apply();
        enCours = true;
        urlBuild = null;
        bVoir.setVisibility(View.GONE);
        bTelechargements.setVisibility(View.GONE);
        bCompiler.setText("⏳ Compilation en cours…");
        tvJournal.setText("");
        final String type = typeProjet();
        new Thread(() -> executer(repo, type)).start();
    }

    void statut(String s) { runOnUiThread(() -> tvStatut.setText(s)); }

    void journal(String s) {
        runOnUiThread(() -> tvJournal.setText(tvJournal.getText().length() == 0
                ? s : tvJournal.getText() + "\n" + s));
    }

    void executer(String repo, String type) {
        String branche = null;
        try {
            statut("Envoi du projet vers GitHub…");
            JSONArray arbre = new JSONArray();
            int blobs = 0;
            for (Fichier f : fichiers) {
                JSONObject e = new JSONObject();
                e.put("path", f.chemin);
                e.put("mode", f.chemin.endsWith("gradlew") ? "100755" : "100644");
                e.put("type", "blob");
                if (f.texte != null) {
                    e.put("content", f.texte);
                } else {
                    JSONObject corps = new JSONObject();
                    corps.put("content", Base64.encodeToString(f.octets, Base64.NO_WRAP));
                    corps.put("encoding", "base64");
                    e.put("sha", api("POST", "/repos/" + repo + "/git/blobs", corps).getString("sha"));
                    blobs++;
                }
                arbre.put(e);
            }
            if (!aUnWorkflow()) {
                JSONObject w = new JSONObject();
                w.put("path", ".github/workflows/build.yml");
                w.put("mode", "100644");
                w.put("type", "blob");
                w.put("content", type.equals("plat") ? WORKFLOW_PLAT : WORKFLOW_STANDARD);
                arbre.put(w);
                journal("• Fichier de compilation ajouté automatiquement");
            }
            journal("• " + fichiers.size() + " fichiers envoyés (" + blobs + " binaires)");

            JSONObject ct = new JSONObject().put("tree", arbre);
            String arbreSha = api("POST", "/repos/" + repo + "/git/trees", ct).getString("sha");

            JSONObject cc = new JSONObject();
            cc.put("message", "Projet envoyé depuis RiRe a GOGO : " + nomZip);
            cc.put("tree", arbreSha);
            cc.put("parents", new JSONArray());
            String commit = api("POST", "/repos/" + repo + "/git/commits", cc).getString("sha");

            branche = "apk-build-" + System.currentTimeMillis();
            api("POST", "/repos/" + repo + "/git/refs",
                    new JSONObject().put("ref", "refs/heads/" + branche).put("sha", commit));
            journal("• Branche temporaire créée : " + branche);

            // Attente du build
            statut("Attente du démarrage du build…");
            long limite = System.currentTimeMillis() + 20 * 60 * 1000L;
            long debut = System.currentTimeMillis();
            JSONObject run = null;
            while (System.currentTimeMillis() < limite) {
                JSONArray runs = api("GET", "/repos/" + repo + "/actions/runs?head_sha=" + commit
                        + "&per_page=5", null).getJSONArray("workflow_runs");
                if (runs.length() > 0) {
                    run = runs.getJSONObject(0);
                    urlBuild = run.optString("html_url", null);
                    runOnUiThread(() -> bVoir.setVisibility(urlBuild == null ? View.GONE : View.VISIBLE));
                    String st = run.optString("status");
                    if (st.equals("completed")) break;
                    statut("Compilation en cours sur GitHub… (" + ((System.currentTimeMillis() - debut) / 1000) + " s)");
                } else if (System.currentTimeMillis() - debut > 90_000) {
                    throw new Exception("Aucun build n'a démarré : vérifiez que GitHub Actions est activé "
                            + "sur le dépôt et que le token a le droit « Workflows ».");
                }
                Thread.sleep(8000);
            }
            if (run == null || !run.optString("status").equals("completed"))
                throw new Exception("Le build prend trop de temps. Voyez son état sur GitHub.");
            String conclusion = run.optString("conclusion");
            if (!conclusion.equals("success")) {
                journal("❌ Le build a échoué (" + conclusion + "). Ouvrez le build sur GitHub pour lire l'erreur.");
                statut("Échec de la compilation");
                return;
            }

            // Téléchargement de l'APK
            statut("Téléchargement de l'APK…");
            JSONArray arts = api("GET", "/repos/" + repo + "/actions/runs/" + run.getLong("id")
                    + "/artifacts", null).getJSONArray("artifacts");
            long idArt = -1;
            for (int i = 0; i < arts.length(); i++)
                if (!arts.getJSONObject(i).optBoolean("expired")) { idArt = arts.getJSONObject(i).getLong("id"); break; }
            if (idArt < 0) throw new Exception("Le build a réussi mais n'a produit aucun fichier.");

            String chemin = telechargerApk(repo, idArt);
            journal("✅ APK enregistré : " + chemin);
            journal("Ouvrez-le depuis Téléchargements pour l'installer (Android demandera d'autoriser "
                    + "l'installation d'applications inconnues).");
            statut("✅ APK prêt");
            runOnUiThread(() -> bTelechargements.setVisibility(View.VISIBLE));
        } catch (Exception e) {
            journal("⚠ " + e.getMessage());
            statut("Échec");
        } finally {
            if (branche != null) {
                try { api("DELETE", "/repos/" + repo + "/git/refs/heads/" + branche, null); }
                catch (Exception ignored) {}
            }
            enCours = false;
            runOnUiThread(() -> bCompiler.setText("🚀 Compiler en APK"));
        }
    }

    String telechargerApk(String repo, long idArt) throws Exception {
        HttpURLConnection c = ouvrir("GET", "/repos/" + repo + "/actions/artifacts/" + idArt + "/zip");
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        InputStream flux;
        if (code == 301 || code == 302 || code == 307 || code == 308) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            HttpURLConnection c2 = (HttpURLConnection) new URL(loc).openConnection();   // sans le token
            c2.setConnectTimeout(20000);
            c2.setReadTimeout(120000);
            flux = c2.getInputStream();
        } else if (code == 200) {
            flux = c.getInputStream();
        } else {
            throw new Exception(erreur(code, ""));
        }
        byte[] apk = null;
        try (ZipInputStream z = new ZipInputStream(flux)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".apk")) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = z.read(buf)) > 0) bo.write(buf, 0, n);
                    apk = bo.toByteArray();
                    break;
                }
            }
        }
        if (apk == null) throw new Exception("Aucun APK dans le résultat du build");

        String nom = nomZip.replaceAll("[^A-Za-z0-9._-]", "_") + "-debug.apk";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nom);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new Exception("Enregistrement de l'APK impossible");
            try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(apk); }
            return "Téléchargements/" + nom;
        } else {
            File dossier = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File f = new File(dossier, nom);
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(apk); }
            return f.getAbsolutePath();
        }
    }

    // ---------- API GitHub ----------

    HttpURLConnection ouvrir(String methode, String chemin) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com" + chemin).openConnection();
        c.setRequestMethod(methode);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "RiReAGogo");
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        return c;
    }

    JSONObject api(String methode, String chemin, JSONObject corps) throws Exception {
        HttpURLConnection c = ouvrir(methode, chemin);
        if (corps != null) {
            byte[] d = corps.toString().getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(d.length);
            try (OutputStream o = c.getOutputStream()) { o.write(d); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String t = "";
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            t = bo.toString("UTF-8");
        }
        if (code >= 400) {
            String msg = "";
            try { msg = new JSONObject(t).optString("message"); } catch (Exception ignored) {}
            throw new Exception(erreur(code, msg));
        }
        return t.trim().isEmpty() ? new JSONObject() : new JSONObject(t);
    }

    static String erreur(int code, String msg) {
        switch (code) {
            case 401: return "Token invalide ou expiré (401).";
            case 403: return "Accès refusé (403) : " + msg + " Vérifiez les droits du token "
                    + "(Contents et Workflows en écriture, Actions en lecture).";
            case 404: return "Dépôt ou ressource introuvable (404). Vérifiez le nom du dépôt et que "
                    + "le token y a accès.";
            case 409: return "Le dépôt est vide (409) : créez-y d'abord un fichier README sur GitHub.";
            case 422: return "Requête refusée par GitHub (422) : " + msg;
            default: return "Erreur GitHub " + code + " : " + msg;
        }
    }
}
