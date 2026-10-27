package bj.radio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Analyse du téléphone :
 *  1) fichiers malveillants : programmes déguisés, scripts, fichier de test antivirus, autorun…
 *     (détection par nom, extension et contenu réel du fichier ; les .apk ne sont PAS recherchés)
 *  2) fichiers et dossiers cachés (nom commençant par un point), rangés par dossier, supprimables
 */
public class AnalyseActivity extends Activity {

    static class Element {
        File f;
        boolean dossier;
        long taille;
        int nb;
        int score;
        List<String> raisons = new ArrayList<>();
        CheckBox box;
        View ligne;
        LinearLayout groupe;
    }

    // Extensions de programmes / scripts Windows : sans intérêt sur Android, souvent propagés par clé USB
    private static final List<String> EXT_FORTES = Arrays.asList(
            "exe", "scr", "bat", "cmd", "com", "pif", "vbs", "vbe", "jse", "wsf", "hta", "cpl", "msi", "dll", "ps1", "lnk");
    // Extensions parfois légitimes : ne comptent que combinées à d'autres indices
    private static final List<String> EXT_FAIBLES = Arrays.asList("jar", "sh", "js");
    // Extensions « leurre » : un fichier qui prétend être une image, une vidéo, un document…
    private static final List<String> EXT_LEURRES = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "mp3", "mp4", "3gp", "mkv", "avi", "mov",
            "wav", "ogg", "pdf", "txt", "doc", "xls", "ppt");
    private static final Pattern DOUBLE_EXT = Pattern.compile(
            ".*\\.(jpg|jpeg|png|gif|mp3|mp4|3gp|pdf|doc|docx|xls|xlsx|ppt|pptx|txt|zip|rar)"
                    + "\\.(exe|scr|bat|cmd|vbs|vbe|js|jse|jar|lnk|com|pif|apk|hta|wsf|ps1)$");
    private static final String EICAR = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE";
    private static final int MAX_FICHIERS = 400000;

    private TextView statut;
    private Button btnAnalyse, btnSupprimer;
    private LinearLayout zoneResultats, corpsMenaces, corpsFichiers;
    private final List<Element> elements = new ArrayList<>();
    private volatile boolean enCours = false;
    private boolean attendAcces = false;
    private int nbFichiersVus = 0;
    private long dernierMaj = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.BG);

        LinearLayout contenu = new LinearLayout(this);
        contenu.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        contenu.setPadding(p, p, p, p);

        Ui.ajouter(contenu, Ui.texte(this, "🛡 Analyse du téléphone", 24, Ui.TXT, true), 0);
        Ui.ajouter(contenu, Ui.texte(this,
                "Cherche les fichiers malveillants (programmes déguisés en photo ou en vidéo, scripts, "
                + "fichiers de propagation par clé USB), puis les fichiers cachés rangés par dossier. "
                + "Vous choisissez ce qu'il faut supprimer : rien n'est effacé sans votre accord.\n\n"
                + "Ce n'est pas un antivirus à base de signatures : l'analyse repose sur le nom, l'extension "
                + "et le contenu réel des fichiers. Un fichier signalé n'est pas forcément un virus. "
                + "Les applications installées ne sont pas analysées.",
                13, Ui.TXT2, false), 6);

        btnAnalyse = Ui.bouton(this, "🔍 Lancer l'analyse", 0xFFC0392B);
        btnAnalyse.setTextSize(16);
        btnAnalyse.setOnClickListener(v -> lancer());
        Ui.ajouter(contenu, btnAnalyse, 14);

        statut = Ui.texte(this, "", 13, Ui.JAUNE, false);
        Ui.ajouter(contenu, statut, 10);

        zoneResultats = new LinearLayout(this);
        zoneResultats.setOrientation(LinearLayout.VERTICAL);
        Ui.ajouter(contenu, zoneResultats, 6);

        ScrollView sv = new ScrollView(this);
        sv.addView(contenu);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        btnSupprimer = Ui.bouton(this, "🗑 Supprimer la sélection", 0xFFC0392B);
        btnSupprimer.setTextSize(16);
        btnSupprimer.setVisibility(View.GONE);
        btnSupprimer.setOnClickListener(v -> confirmerSuppression());
        LinearLayout.LayoutParams lb = Ui.lp(this, -1, -2, 0f, 6, 0);
        lb.setMargins(p, Ui.dp(this, 6), p, p);
        page.addView(btnSupprimer, lb);

        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reprendre();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        reprendre();
    }

    private void reprendre() {
        if (attendAcces && !enCours && accesStockage()) {
            attendAcces = false;
            lancerFichiers();
        }
    }

    // ================= Lancement =================

    private void lancer() {
        if (enCours) return;
        enCours = true;
        elements.clear();
        zoneResultats.removeAllViews();
        btnSupprimer.setVisibility(View.GONE);
        btnAnalyse.setEnabled(false);
        btnAnalyse.setText("Analyse en cours…");
        statut.setText("Analyse des fichiers…");

        corpsMenaces = section("🦠 Fichiers malveillants");
        corpsFichiers = section("👻 Fichiers cachés");

        new Thread(() -> {
            if (accesStockage()) {
                scannerFichiers();
            } else {
                runOnUiThread(this::demanderAcces);
            }
            runOnUiThread(this::fin);
        }).start();
    }

    private void lancerFichiers() {
        enCours = true;
        btnAnalyse.setEnabled(false);
        btnAnalyse.setText("Analyse en cours…");
        corpsFichiers.removeAllViews();
        corpsMenaces.removeAllViews();
        elements.clear();
        new Thread(() -> {
            scannerFichiers();
            runOnUiThread(this::fin);
        }).start();
    }

    private void fin() {
        enCours = false;
        btnAnalyse.setEnabled(true);
        btnAnalyse.setText("🔍 Relancer l'analyse");
        statut.setText("Analyse terminée.");
        majBouton();
    }

    private LinearLayout section(String titre) {
        zoneResultats.addView(Ui.texte(this, titre, 19, Ui.TXT, true),
                Ui.lp(this, -1, -2, 0f, 18, 0));
        LinearLayout corps = new LinearLayout(this);
        corps.setOrientation(LinearLayout.VERTICAL);
        zoneResultats.addView(corps, Ui.lp(this, -1, -2, 0f, 6, 0));
        return corps;
    }

    // ================= Fichiers malveillants =================

    private byte[] entete(File f, int n) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[n];
            int r = in.read(b);
            if (r <= 0) return null;
            return r == n ? b : Arrays.copyOf(b, r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Renvoie un élément si le fichier est suspect (score >= 3), sinon null. */
    private Element evaluer(File f, String nom) {
        String bas = nom.toLowerCase(Locale.ROOT);
        int pt = bas.lastIndexOf('.');
        String ext = pt >= 0 ? bas.substring(pt + 1) : "";
        int score = 0;
        List<String> rs = new ArrayList<>();
        long taille = f.length();

        // Fichier de test standard des antivirus (inoffensif, mais prouve que la détection marche)
        if (taille >= 68 && taille <= 200) {
            byte[] b = entete(f, 200);
            if (b != null && new String(b, StandardCharsets.ISO_8859_1).contains(EICAR)) {
                score += 10;
                rs.add("Fichier de test antivirus EICAR (inoffensif, utilisé pour tester les antivirus)");
            }
        }
        if (bas.equals("autorun.inf")) {
            score += 5;
            rs.add("autorun.inf : technique de virus qui se propagent par clé USB / carte mémoire");
        }
        if (DOUBLE_EXT.matcher(bas).matches()) {
            score += 6;
            rs.add("Double extension : un programme se fait passer pour une image, une vidéo ou un document");
        } else if (EXT_FORTES.contains(ext)) {
            score += 3;
            rs.add("Programme ou script Windows (." + ext + ") : inutile sur Android, souvent un virus copié depuis un PC");
        } else if (EXT_FAIBLES.contains(ext)) {
            score += 1;
        }
        if (nom.startsWith(".") && (EXT_FORTES.contains(ext) || EXT_FAIBLES.contains(ext))) {
            score += 2;
            rs.add("Programme ou script caché (nom commençant par un point)");
        }
        // Contenu réel : une « photo » qui est en fait un programme
        if (EXT_LEURRES.contains(ext) && taille >= 4) {
            byte[] h = entete(f, 8);
            if (h != null && h.length >= 4) {
                if (h[0] == 'M' && h[1] == 'Z') {
                    score += 6;
                    rs.add("Le contenu est un programme Windows déguisé en ." + ext);
                } else if (h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') {
                    score += 6;
                    rs.add("Le contenu est un exécutable Linux/Android déguisé en ." + ext);
                } else if (h[0] == 'd' && h[1] == 'e' && h[2] == 'x' && h[3] == 0x0A) {
                    score += 6;
                    rs.add("Le contenu est du code d'application Android (dex) déguisé en ." + ext);
                } else if (h[0] == '#' && h[1] == '!') {
                    score += 4;
                    rs.add("Le contenu est un script déguisé en ." + ext);
                } else if (h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) {
                    score += 4;
                    rs.add("Archive ZIP déguisée en ." + ext + " (peut cacher une application)");
                }
            }
        }
        if (score < 3) return null;
        Element e = new Element();
        e.f = f;
        e.taille = taille;
        e.nb = 1;
        e.score = score;
        e.raisons = rs;
        return e;
    }

    private void afficherMenaces(List<Element> menaces, File racine) {
        corpsMenaces.removeAllViews();
        if (menaces.isEmpty()) {
            corpsMenaces.addView(Ui.texte(this, "✅ Aucun fichier malveillant détecté parmi les "
                    + nbFichiersVus + " fichiers examinés.", 14, Ui.TXT2, false));
            corpsMenaces.addView(Ui.texte(this,
                    "Cette analyse repère les programmes déguisés et les scripts dangereux ; elle ne remplace "
                    + "pas un antivirus à base de signatures.", 11, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 6, 0));
            return;
        }
        menaces.sort((a, b) -> b.score - a.score);
        elements.addAll(menaces);
        corpsMenaces.addView(Ui.texte(this, menaces.size() + " fichier(s) suspect(s) sur "
                + nbFichiersVus + " examinés :", 13, Ui.TXT2, false));
        String base = racine.getAbsolutePath();
        for (Element e : menaces) {
            LinearLayout carte = new LinearLayout(this);
            carte.setOrientation(LinearLayout.VERTICAL);
            carte.setBackground(Ui.rond(Ui.CARD, 16, this));
            int q = Ui.dp(this, 14);
            carte.setPadding(q, q, q, q);

            LinearLayout haut = new LinearLayout(this);
            haut.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this);
            cb.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
            cb.setOnCheckedChangeListener((v, c) -> majBouton());
            haut.addView(cb);
            LinearLayout noms = new LinearLayout(this);
            noms.setOrientation(LinearLayout.VERTICAL);
            noms.addView(Ui.texte(this, e.f.getName(), 15, Ui.TXT, true));
            String chemin = e.f.getParent() == null ? "" : e.f.getParent();
            if (chemin.startsWith(base)) chemin = chemin.substring(base.length());
            if (chemin.isEmpty()) chemin = "/";
            noms.addView(Ui.texte(this, chemin + " • " + Formatter.formatFileSize(this, e.taille),
                    11, Ui.TXT2, false));
            haut.addView(noms, Ui.lp(this, 0, -2, 1f, 0, 6));
            boolean eleve = e.score >= 6;
            TextView niveau = Ui.texte(this, eleve ? "Dangereux" : "Suspect", 12, Ui.TXT, true);
            niveau.setBackground(Ui.rond(eleve ? 0xFFC0392B : 0xFFD68910, 10, this));
            int m = Ui.dp(this, 8);
            niveau.setPadding(m, m / 2, m, m / 2);
            haut.addView(niveau);
            carte.addView(haut);

            StringBuilder sb = new StringBuilder();
            for (String r : e.raisons) sb.append("• ").append(r).append('\n');
            carte.addView(Ui.texte(this, sb.toString().trim(), 12, Ui.TXT2, false),
                    Ui.lp(this, -1, -2, 0f, 8, 0));

            e.box = cb;
            e.ligne = carte;
            e.groupe = corpsMenaces;
            corpsMenaces.addView(carte, Ui.lp(this, -1, -2, 0f, 10, 0));
        }
        corpsMenaces.addView(Ui.texte(this,
                "Cochez les fichiers à effacer puis appuyez sur « Supprimer la sélection ». "
                + "Ne supprimez pas un fichier que vous avez créé vous-même sans vérifier.",
                11, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 8, 0));
    }

    // ================= Fichiers cachés =================

    private boolean accesStockage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void demanderAcces() {
        corpsFichiers.removeAllViews();
        corpsMenaces.removeAllViews();
        corpsMenaces.addView(Ui.texte(this,
                "En attente de l'accès aux fichiers (voir plus bas).", 13, Ui.TXT2, false));
        corpsFichiers.addView(Ui.texte(this,
                "Pour chercher (et pouvoir supprimer) les fichiers malveillants et cachés, Android demande d'autoriser "
                + "l'accès à tous les fichiers. L'analyse des fichiers reprendra automatiquement au retour.",
                13, Ui.TXT2, false));
        Button b = Ui.bouton(this, "Autoriser l'accès aux fichiers", 0xFF2E9E73);
        b.setOnClickListener(v -> {
            attendAcces = true;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
            }
        });
        corpsFichiers.addView(b, Ui.lp(this, -1, -2, 0f, 10, 0));
    }

    private void scannerFichiers() {
        File racine = Environment.getExternalStorageDirectory();
        List<Element> caches = new ArrayList<>();
        List<Element> menaces = new ArrayList<>();
        nbFichiersVus = 0;
        parcourir(racine, 0, false, caches, menaces);
        runOnUiThread(() -> {
            afficherMenaces(menaces, racine);
            afficherFichiers(caches, racine);
        });
    }

    /**
     * Un seul passage sur le stockage : repère les fichiers cachés ET évalue chaque fichier.
     * Les dossiers cachés sont aussi explorés (les virus s'y cachent), mais leur contenu n'est pas
     * listé dans « Fichiers cachés » (le dossier est déjà listé en entier).
     */
    private void parcourir(File dossier, int profondeur, boolean dansCache,
                           List<Element> caches, List<Element> menaces) {
        if (profondeur > 14 || nbFichiersVus > MAX_FICHIERS) return;
        long t = System.currentTimeMillis();
        if (t - dernierMaj > 250) {
            dernierMaj = t;
            String nom = dossier.getName();
            runOnUiThread(() -> statut.setText("Analyse des fichiers : " + nom));
        }
        File[] liste = dossier.listFiles();
        if (liste == null) return;
        for (File f : liste) {
            String nom = f.getName();
            if (profondeur == 0 && nom.equals("Android")) continue; // dossiers privés des applis
            boolean cache = nom.startsWith(".") && !nom.equals(".nomedia");
            boolean estDossier = f.isDirectory();
            if (cache && !dansCache) {
                Element e = new Element();
                e.f = f;
                e.dossier = estDossier;
                if (estDossier) {
                    long[] acc = new long[2];
                    taille(f, acc);
                    e.taille = acc[0];
                    e.nb = (int) acc[1];
                } else {
                    e.taille = f.length();
                    e.nb = 1;
                }
                caches.add(e);
            }
            if (estDossier) {
                parcourir(f, profondeur + 1, dansCache || cache, caches, menaces);
            } else {
                nbFichiersVus++;
                Element m = evaluer(f, nom);
                if (m != null) menaces.add(m);
            }
        }
    }

    private void taille(File f, long[] acc) {
        if (acc[1] > 50000) return;
        if (f.isDirectory()) {
            File[] l = f.listFiles();
            if (l != null) for (File x : l) taille(x, acc);
        } else {
            acc[0] += f.length();
            acc[1]++;
        }
    }

    private void afficherFichiers(List<Element> trouves, File racine) {
        corpsFichiers.removeAllViews();
        if (trouves.isEmpty()) {
            corpsFichiers.addView(Ui.texte(this, "✅ Aucun fichier caché trouvé.", 14, Ui.TXT2, false));
            return;
        }
        Map<String, List<Element>> groupes = new TreeMap<>();
        String base = racine.getAbsolutePath();
        long total = 0;
        for (Element e : trouves) {
            total += e.taille;
            String parent = e.f.getParentFile() == null ? base : e.f.getParentFile().getAbsolutePath();
            String rel = parent.startsWith(base) ? parent.substring(base.length()) : parent;
            if (rel.isEmpty()) rel = "/";
            List<Element> l = groupes.get(rel);
            if (l == null) { l = new ArrayList<>(); groupes.put(rel, l); }
            l.add(e);
        }
        elements.addAll(trouves);
        corpsFichiers.addView(Ui.texte(this, trouves.size() + " élément(s) caché(s) dans "
                + groupes.size() + " dossier(s) • " + Formatter.formatFileSize(this, total),
                13, Ui.TXT2, false));
        corpsFichiers.addView(Ui.texte(this,
                "Cochez ce que vous voulez effacer. Attention : certains fichiers cachés (cache, miniatures) "
                + "servent à d'autres applications ; ils se recréent en général tout seuls.",
                11, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 4, 0));

        for (Map.Entry<String, List<Element>> g : groupes.entrySet()) {
            LinearLayout groupe = new LinearLayout(this);
            groupe.setOrientation(LinearLayout.VERTICAL);
            groupe.setBackground(Ui.rond(Ui.CARD, 16, this));
            int q = Ui.dp(this, 12);
            groupe.setPadding(q, q, q, q);

            LinearLayout entete = new LinearLayout(this);
            entete.setGravity(Gravity.CENTER_VERTICAL);
            String titre = "📁 " + g.getKey() + "  (" + g.getValue().size() + ")";
            entete.addView(Ui.texte(this, titre, 14, Ui.JAUNE, true), new LinearLayout.LayoutParams(0, -2, 1f));
            Button tout = Ui.bouton(this, "Tout cocher", 0xFF3A7BD5);
            tout.setTextSize(11);
            final List<Element> liste = g.getValue();
            tout.setOnClickListener(v -> {
                boolean toutes = true;
                for (Element e : liste) if (!e.box.isChecked()) toutes = false;
                for (Element e : liste) e.box.setChecked(!toutes);
            });
            entete.addView(tout);
            groupe.addView(entete);

            for (Element e : liste) {
                CheckBox cb = new CheckBox(this);
                cb.setTextColor(Ui.TXT);
                cb.setTextSize(13);
                cb.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
                String detail = e.dossier
                        ? "📁 " + e.f.getName() + "\n" + e.nb + " fichier(s) • " + Formatter.formatFileSize(this, e.taille)
                        : "📄 " + e.f.getName() + "\n" + Formatter.formatFileSize(this, e.taille);
                cb.setText(detail);
                cb.setOnCheckedChangeListener((v, c) -> majBouton());
                e.box = cb;
                e.ligne = cb;
                e.groupe = groupe;
                groupe.addView(cb, Ui.lp(this, -1, -2, 0f, 4, 0));
            }
            corpsFichiers.addView(groupe, Ui.lp(this, -1, -2, 0f, 10, 0));
        }
    }

    private void majBouton() {
        int n = 0;
        long t = 0;
        for (Element e : elements) {
            if (e.box != null && e.box.isChecked()) { n++; t += e.taille; }
        }
        if (n == 0) {
            btnSupprimer.setVisibility(View.GONE);
        } else {
            btnSupprimer.setVisibility(View.VISIBLE);
            btnSupprimer.setText("🗑 Supprimer la sélection (" + n + " • " + Formatter.formatFileSize(this, t) + ")");
        }
    }

    private void confirmerSuppression() {
        List<Element> sel = new ArrayList<>();
        long t = 0;
        for (Element e : elements) {
            if (e.box != null && e.box.isChecked()) { sel.add(e); t += e.taille; }
        }
        if (sel.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Supprimer définitivement ?")
                .setMessage(sel.size() + " élément(s), " + Formatter.formatFileSize(this, t)
                        + " seront effacés. Cette action est irréversible (pas de corbeille).")
                .setPositiveButton("Supprimer", (d, w) -> supprimer(sel))
                .setNegativeButton("Annuler", null).show();
    }

    private void supprimer(List<Element> sel) {
        btnSupprimer.setEnabled(false);
        statut.setText("Suppression en cours…");
        new Thread(() -> {
            List<Element> ok = new ArrayList<>();
            int echecs = 0;
            long libere = 0;
            for (Element e : sel) {
                if (supprimerRec(e.f)) { ok.add(e); libere += e.taille; } else echecs++;
            }
            final int ech = echecs;
            final long lib = libere;
            runOnUiThread(() -> {
                for (Element e : ok) {
                    elements.remove(e);
                    LinearLayout g = e.groupe;
                    if (g != null) {
                        g.removeView(e.ligne);
                        if (g.getChildCount() <= 1 && g.getParent() instanceof LinearLayout)
                            ((LinearLayout) g.getParent()).removeView(g);
                    }
                }
                btnSupprimer.setEnabled(true);
                statut.setText("Terminé.");
                majBouton();
                String msg = ok.size() + " élément(s) supprimé(s), " + Formatter.formatFileSize(this, lib) + " libérés.";
                if (ech > 0) msg += " " + ech + " impossible(s) à supprimer (protégés).";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private boolean supprimerRec(File f) {
        try {
            if (f.isDirectory()) {
                File[] l = f.listFiles();
                if (l != null) for (File x : l) supprimerRec(x);
            }
            return f.delete();
        } catch (Exception e) {
            return false;
        }
    }
}
