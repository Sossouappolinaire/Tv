package bj.radio;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
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
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.HashMap;
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
 *  3) doublons : fichiers identiques (même contenu) rangés par groupe, on garde un exemplaire
 *  4) applications suspectes (hors boutique officielle + permissions dangereuses, appli sans icône)
 *  5) chaleur et batterie : température, état thermique ; refroidissement et optimisation
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

    /** Application installée qui mérite une vérification. */
    static class AppSuspecte {
        String pkg, nom;
        int score;
        List<String> raisons = new ArrayList<>();
    }

    private static final long MIN_DOUBLON = 20 * 1024;   // en dessous de 20 Ko : sans intérêt
    private final Map<Long, List<File>> parTaille = new HashMap<>();
    private LinearLayout corpsChaleur, corpsApps, corpsDoublons;
    private final Handler hUi = new Handler(Looper.getMainLooper());
    private float tempAvant = -1;

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
        page.setBackground(Ui.fond());

        LinearLayout contenu = new LinearLayout(this);
        contenu.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        contenu.setPadding(p, p, p, p);

        Ui.ajouter(contenu, Ui.texte(this, "🛡 Analyse du téléphone", 24, Ui.TXT, true), 0);
        Ui.ajouter(contenu, Ui.texte(this,
                "Vérifie si le téléphone chauffe (et le refroidit), cherche les fichiers malveillants "
                + "(programmes déguisés en photo ou en vidéo, scripts, propagation par clé USB), les applications "
                + "suspectes, les doublons et les fichiers cachés. "
                + "Vous choisissez ce qu'il faut supprimer : aucun fichier n'est effacé sans votre accord.\n\n"
                + "Ce n'est pas un antivirus à base de signatures : l'analyse repose sur le nom, l'extension, "
                + "le contenu réel des fichiers et les permissions des applications. Un élément signalé "
                + "n'est pas forcément un virus.",
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
        if (corpsChaleur != null && !enCours) verifierChaleur(false);   // au retour des réglages Android
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

        corpsChaleur = section("🌡 Chaleur et batterie");
        corpsMenaces = section("🦠 Fichiers malveillants");
        corpsApps = section("📱 Applications à vérifier");
        corpsDoublons = section("📑 Doublons");
        corpsFichiers = section("👻 Fichiers cachés");

        verifierChaleur(true);   // chaleur d'abord : si le téléphone est chaud, on le refroidit tout de suite

        new Thread(() -> {
            scannerApps();
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
        corpsDoublons.removeAllViews();
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

    // ================= Doublons =================

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /** Empreinte du contenu. Gros fichier (> 32 Mo) : début + milieu + fin seulement (rapide). */
    private String empreinte(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            long len = raf.length();
            byte[] buf = new byte[65536];
            if (len <= (32L << 20)) {
                int n;
                while ((n = raf.read(buf)) > 0) md.update(buf, 0, n);
            } else {
                long[] pos = {0, Math.max(0, len / 2 - 524288), len - 1048576};
                for (long p : pos) {
                    raf.seek(p);
                    long reste = 1048576;
                    while (reste > 0) {
                        int n = raf.read(buf, 0, (int) Math.min(buf.length, reste));
                        if (n <= 0) break;
                        md.update(buf, 0, n);
                        reste -= n;
                    }
                }
            }
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** Groupes de fichiers de même taille ET de même contenu. */
    private List<List<File>> trouverDoublons() {
        List<List<File>> res = new ArrayList<>();
        for (Map.Entry<Long, List<File>> en : parTaille.entrySet()) {
            List<File> memeTaille = en.getValue();
            if (memeTaille.size() < 2) continue;
            Map<String, List<File>> parEmpreinte = new HashMap<>();
            for (File f : memeTaille) {
                String h = empreinte(f);
                if (h == null) continue;
                List<File> l = parEmpreinte.get(h);
                if (l == null) { l = new ArrayList<>(); parEmpreinte.put(h, l); }
                l.add(f);
            }
            for (List<File> g : parEmpreinte.values()) if (g.size() >= 2) res.add(g);
        }
        // les plus gros gaspillages d'abord
        res.sort((a, b) -> Long.compare(b.get(0).length() * (b.size() - 1), a.get(0).length() * (a.size() - 1)));
        return res;
    }

    private void afficherDoublons(List<List<File>> groupes, File racine) {
        corpsDoublons.removeAllViews();
        if (groupes.isEmpty()) {
            corpsDoublons.addView(Ui.texte(this, "✅ Aucun doublon trouvé.", 14, Ui.TXT2, false));
            return;
        }
        long gaspille = 0;
        int copies = 0;
        for (List<File> g : groupes) { gaspille += g.get(0).length() * (g.size() - 1); copies += g.size() - 1; }
        final List<Element> toutes = new ArrayList<>();
        corpsDoublons.addView(Ui.texte(this, groupes.size() + " fichier(s) en plusieurs exemplaires • "
                + copies + " copie(s) inutile(s) • " + Formatter.formatFileSize(this, gaspille) + " récupérables",
                13, Ui.TXT2, false));
        corpsDoublons.addView(Ui.texte(this,
                "Dans chaque groupe, l'exemplaire le plus ancien est considéré comme l'original. "
                + "« Cocher les copies » coche tous les autres : rien n'est effacé avant votre confirmation.",
                11, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 4, 0));
        Button toutCocher = Ui.bouton(this, "Cocher toutes les copies", 0xFF3A7BD5);
        toutCocher.setTextSize(12);
        corpsDoublons.addView(toutCocher, Ui.lp(this, -1, -2, 0f, 8, 0));

        String base = racine.getAbsolutePath();
        int numero = 0;
        for (List<File> g : groupes) {
            if (++numero > 150) {   // on n'affiche que les 150 plus gros groupes
                corpsDoublons.addView(Ui.texte(this, "… et " + (groupes.size() - 150) + " autres groupes plus petits.",
                        12, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 8, 0));
                break;
            }
            g.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            LinearLayout carte = new LinearLayout(this);
            carte.setOrientation(LinearLayout.VERTICAL);
            carte.setBackground(Ui.rond(Ui.CARD, 16, this));
            int q = Ui.dp(this, 12);
            carte.setPadding(q, q, q, q);

            LinearLayout entete = new LinearLayout(this);
            entete.setGravity(Gravity.CENTER_VERTICAL);
            entete.addView(Ui.texte(this, "📑 " + g.get(0).getName() + "\n" + g.size() + " exemplaires • "
                    + Formatter.formatFileSize(this, g.get(0).length()) + " chacun", 13, Ui.JAUNE, true),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            Button copiesBtn = Ui.bouton(this, "Cocher les copies", 0xFF3A7BD5);
            copiesBtn.setTextSize(11);
            entete.addView(copiesBtn);
            carte.addView(entete);

            final List<Element> groupeEl = new ArrayList<>();
            for (int i = 0; i < g.size(); i++) {
                File f = g.get(i);
                Element e = new Element();
                e.f = f;
                e.taille = f.length();
                e.nb = 1;
                CheckBox cb = new CheckBox(this);
                cb.setTextColor(Ui.TXT);
                cb.setTextSize(12);
                cb.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
                String chemin = f.getParent() == null ? "/" : f.getParent();
                if (chemin.startsWith(base)) chemin = chemin.substring(base.length());
                if (chemin.isEmpty()) chemin = "/";
                cb.setText((i == 0 ? "⭐ Original : " : "📄 Copie : ") + f.getName() + "\n" + chemin);
                cb.setOnCheckedChangeListener((v, c) -> majBouton());
                e.box = cb;
                e.ligne = cb;
                e.groupe = carte;
                carte.addView(cb, Ui.lp(this, -1, -2, 0f, 4, 0));
                groupeEl.add(e);
                if (i > 0) toutes.add(e);
            }
            elements.addAll(groupeEl);
            copiesBtn.setOnClickListener(v -> {
                boolean dejaToutes = true;
                for (int i = 1; i < groupeEl.size(); i++) if (!groupeEl.get(i).box.isChecked()) dejaToutes = false;
                for (int i = 1; i < groupeEl.size(); i++) groupeEl.get(i).box.setChecked(!dejaToutes);
            });
            corpsDoublons.addView(carte, Ui.lp(this, -1, -2, 0f, 10, 0));
        }
        toutCocher.setOnClickListener(v -> {
            boolean dejaToutes = true;
            for (Element e : toutes) if (!e.box.isChecked()) dejaToutes = false;
            for (Element e : toutes) e.box.setChecked(!dejaToutes);
        });
    }

    // ================= Applications suspectes =================

    private static final List<String> BOUTIQUES = Arrays.asList(
            "com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket",
            "com.xiaomi.mipicks", "com.xiaomi.market", "com.amazon.venezia", "com.heytap.market",
            "com.oppo.market", "com.vivo.appstore", "com.transsion.phonemaster", "com.google.android.feedback");

    private void scannerApps() {
        final List<AppSuspecte> trouvees = new ArrayList<>();
        int total = 0;
        PackageManager pm = getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(getPackageName())) continue;
            total++;
            try {
                AppSuspecte a = evaluerApp(pm, ai);
                if (a != null) trouvees.add(a);
            } catch (Exception ignore) {
                // appli illisible : on passe
            }
        }
        trouvees.sort((x, y) -> y.score - x.score);
        final int n = total;
        runOnUiThread(() -> afficherApps(trouvees, n));
    }

    private AppSuspecte evaluerApp(PackageManager pm, ApplicationInfo ai) throws Exception {
        PackageInfo pi = pm.getPackageInfo(ai.packageName,
                PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS);
        int score = 0;
        List<String> rs = new ArrayList<>();

        String installateur = null;
        try {
            installateur = Build.VERSION.SDK_INT >= 30
                    ? pm.getInstallSourceInfo(ai.packageName).getInstallingPackageName()
                    : pm.getInstallerPackageName(ai.packageName);
        } catch (Exception ignore) {
            // inconnu
        }
        boolean horsBoutique = installateur == null || !BOUTIQUES.contains(installateur);
        if (horsBoutique) {
            score += 1;
            rs.add("Installée hors d'une boutique officielle (fichier .apk, clé USB, lien…)");
        }

        List<String> perms = pi.requestedPermissions == null
                ? new ArrayList<String>() : Arrays.asList(pi.requestedPermissions);
        boolean sms = perms.contains("android.permission.READ_SMS") || perms.contains("android.permission.RECEIVE_SMS")
                || perms.contains("android.permission.SEND_SMS");
        if (sms) { score += 2; rs.add("Peut lire ou envoyer vos SMS (codes bancaires, abonnements surtaxés)"); }
        if (perms.contains("android.permission.SYSTEM_ALERT_WINDOW")) {
            score += 1; rs.add("Peut s'afficher par-dessus les autres applications (fausses fenêtres de saisie)");
        }
        if (perms.contains("android.permission.REQUEST_INSTALL_PACKAGES")) {
            score += 2; rs.add("Peut installer d'autres applications");
        }
        if (perms.contains("android.permission.READ_CALL_LOG")) { score += 1; rs.add("Peut lire votre journal d'appels"); }

        boolean accessibilite = false, admin = false, notifs = false;
        if (pi.services != null) {
            for (ServiceInfo si : pi.services) {
                if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(si.permission)) accessibilite = true;
                if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(si.permission)) notifs = true;
            }
        }
        if (pi.receivers != null) {
            for (ActivityInfo ri : pi.receivers) {
                if ("android.permission.BIND_DEVICE_ADMIN".equals(ri.permission)) admin = true;
            }
        }
        if (accessibilite) { score += 3; rs.add("Service d'accessibilité : peut lire l'écran et agir à votre place"); }
        if (admin) { score += 3; rs.add("Administrateur de l'appareil : difficile à désinstaller"); }
        if (notifs) { score += 1; rs.add("Peut lire vos notifications"); }

        boolean sansIcone = pm.getLaunchIntentForPackage(ai.packageName) == null;
        if (sansIcone && horsBoutique) {
            score += 2;
            rs.add("Aucune icône dans le menu : l'application est cachée");
        }
        // Les applis réputées (boutique officielle) ont souvent ces permissions légitimement : seuil plus haut
        int seuil = horsBoutique ? 4 : 6;
        if (score < seuil) return null;
        AppSuspecte a = new AppSuspecte();
        a.pkg = ai.packageName;
        CharSequence nom = pm.getApplicationLabel(ai);
        a.nom = nom == null ? ai.packageName : nom.toString();
        a.score = score;
        a.raisons = rs;
        return a;
    }

    private void afficherApps(List<AppSuspecte> apps, int total) {
        corpsApps.removeAllViews();
        if (apps.isEmpty()) {
            corpsApps.addView(Ui.texte(this, "✅ Aucune application suspecte parmi les " + total
                    + " applications installées.", 14, Ui.TXT2, false));
            return;
        }
        corpsApps.addView(Ui.texte(this, apps.size() + " application(s) à vérifier sur " + total + " installées :",
                13, Ui.TXT2, false));
        for (AppSuspecte a : apps) {
            LinearLayout carte = new LinearLayout(this);
            carte.setOrientation(LinearLayout.VERTICAL);
            carte.setBackground(Ui.rond(Ui.CARD, 16, this));
            int q = Ui.dp(this, 14);
            carte.setPadding(q, q, q, q);

            LinearLayout haut = new LinearLayout(this);
            haut.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout noms = new LinearLayout(this);
            noms.setOrientation(LinearLayout.VERTICAL);
            noms.addView(Ui.texte(this, a.nom, 15, Ui.TXT, true));
            noms.addView(Ui.texte(this, a.pkg, 11, Ui.TXT2, false));
            haut.addView(noms, new LinearLayout.LayoutParams(0, -2, 1f));
            boolean eleve = a.score >= 7;
            TextView niveau = Ui.texte(this, eleve ? "Dangereuse ?" : "À vérifier", 12, Ui.TXT, true);
            niveau.setBackground(Ui.rond(eleve ? 0xFFC0392B : 0xFFD68910, 10, this));
            int m = Ui.dp(this, 8);
            niveau.setPadding(m, m / 2, m, m / 2);
            haut.addView(niveau);
            carte.addView(haut);

            StringBuilder sb = new StringBuilder();
            for (String r : a.raisons) sb.append("• ").append(r).append('\n');
            carte.addView(Ui.texte(this, sb.toString().trim(), 12, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 8, 0));

            LinearLayout boutons = new LinearLayout(this);
            Button infos = Ui.bouton(this, "ℹ Infos", 0xFF3A7BD5);
            Button desinst = Ui.bouton(this, "🗑 Désinstaller", 0xFFC0392B);
            infos.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.pkg))));
            desinst.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + a.pkg))));
            boutons.addView(infos, Ui.lp(this, 0, -2, 1f, 0, 0));
            boutons.addView(desinst, Ui.lp(this, 0, -2, 1f, 0, 8));
            carte.addView(boutons, Ui.lp(this, -1, -2, 0f, 8, 0));
            corpsApps.addView(carte, Ui.lp(this, -1, -2, 0f, 10, 0));
        }
        corpsApps.addView(Ui.texte(this,
                "Android affiche sa propre confirmation avant toute désinstallation. Si le bouton ne fait rien, "
                + "l'application est administrateur de l'appareil : retirez d'abord ce droit dans "
                + "Paramètres › Sécurité › Applications d'administration.", 11, Ui.TXT2, false),
                Ui.lp(this, -1, -2, 0f, 8, 0));
    }

    // ================= Chaleur et batterie =================

    private float lireTemperature() {
        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi == null) return -1;
        int t = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        return t < 0 ? -1 : t / 10f;
    }

    private static String degres(float t) {
        return String.format(Locale.FRANCE, "%.1f °C", t);
    }

    private void verifierChaleur(boolean auto) {
        if (corpsChaleur == null) return;
        corpsChaleur.removeAllViews();
        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi == null) {
            corpsChaleur.addView(Ui.texte(this, "Impossible de lire l'état de la batterie.", 14, Ui.TXT2, false));
            return;
        }
        float temp = lireTemperature();
        int niv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int ech = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = niv >= 0 && ech > 0 ? niv * 100 / ech : -1;
        int branche = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int sante = bi.getIntExtra(BatteryManager.EXTRA_HEALTH, 0);
        int therm = -1;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                therm = ((PowerManager) getSystemService(Context.POWER_SERVICE)).getCurrentThermalStatus();
            } catch (Exception ignore) {
                // non disponible
            }
        }
        boolean surchauffe = sante == BatteryManager.BATTERY_HEALTH_OVERHEAT;
        boolean chaud = temp >= 40f || surchauffe || therm >= 2;

        String etat;
        if (temp < 0) etat = "inconnue";
        else if (temp >= 45f || therm >= 3) etat = "très chaud 🔥";
        else if (chaud) etat = "chaud";
        else if (temp >= 35f) etat = "tiède";
        else etat = "normale";
        corpsChaleur.addView(Ui.texte(this, "🌡 Température : " + (temp < 0 ? "?" : degres(temp)) + " — " + etat,
                16, chaud ? 0xFFFF8A65 : Ui.TXT, true));
        corpsChaleur.addView(Ui.texte(this, "🔋 Batterie : " + (pct < 0 ? "?" : pct + " %")
                + (branche != 0 ? " • en charge" : " • sur batterie")
                + (surchauffe ? " • ⚠ Android signale une surchauffe" : ""), 13, Ui.TXT2, false),
                Ui.lp(this, -1, -2, 0f, 4, 0));

        if (chaud) {
            StringBuilder c = new StringBuilder("⚠ Le téléphone est chaud.");
            if (branche != 0) c.append(" Débranchez le chargeur : la charge chauffe beaucoup.");
            corpsChaleur.addView(Ui.texte(this, c.toString(), 13, 0xFFFFC857, false), Ui.lp(this, -1, -2, 0f, 6, 0));
        } else {
            corpsChaleur.addView(Ui.texte(this, "✅ Le téléphone n'est pas chaud.", 13, Ui.TXT2, false),
                    Ui.lp(this, -1, -2, 0f, 6, 0));
        }

        Button opt = Ui.bouton(this, chaud ? "❄ Refroidir et optimiser" : "⚙ Optimiser la batterie", 0xFF2E9E73);
        opt.setOnClickListener(v -> optimiser());
        corpsChaleur.addView(opt, Ui.lp(this, -1, -2, 0f, 10, 0));

        LinearLayout l2 = new LinearLayout(this);
        Button lum = Ui.bouton(this, "🔅 Baisser la luminosité", 0xFF3A7BD5);
        Button eco = Ui.bouton(this, "🔋 Économie d'énergie", 0xFF3A7BD5);
        lum.setTextSize(12);
        eco.setTextSize(12);
        lum.setOnClickListener(v -> baisserLuminosite());
        eco.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        l2.addView(lum, Ui.lp(this, 0, -2, 1f, 0, 0));
        l2.addView(eco, Ui.lp(this, 0, -2, 1f, 0, 8));
        corpsChaleur.addView(l2, Ui.lp(this, -1, -2, 0f, 8, 0));

        corpsChaleur.addView(Ui.texte(this,
                "Une application ne peut pas refroidir le processeur elle-même : elle arrête ce qui tourne en "
                + "arrière-plan, vide son cache et vous aide à réduire la charge. La température baisse ensuite "
                + "lentement (comptez quelques minutes). Pour aller plus vite : sortez le téléphone de sa coque, "
                + "mettez-le à l'ombre, fermez les jeux et la caméra.", 11, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 8, 0));

        afficherConsommation();

        if (auto && chaud) optimiser();
    }

    /** Arrête les applications en arrière-plan, vide le cache de Kouamé Play Box, puis remesure la température. */
    private void optimiser() {
        tempAvant = lireTemperature();
        int n = 0;
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(getPackageName())) continue;
            try {
                am.killBackgroundProcesses(ai.packageName);
                n++;
            } catch (Exception ignore) {
                // refusé pour cette appli
            }
        }
        long libere = viderCache(getCacheDir()) + viderCache(getExternalCacheDir());
        final TextView res = Ui.texte(this, "⏳ Optimisation faite : activités en arrière-plan arrêtées pour " + n
                + " application(s), cache vidé (" + Formatter.formatFileSize(this, libere) + "). "
                + "Nouvelle mesure dans 30 secondes…", 12, Ui.JAUNE, false);
        corpsChaleur.addView(res, Ui.lp(this, -1, -2, 0f, 8, 0));
        Toast.makeText(this, "Optimisation en cours…", Toast.LENGTH_SHORT).show();
        hUi.postDelayed(() -> {
            if (isFinishing()) return;
            float apres = lireTemperature();
            String t = tempAvant >= 0 && apres >= 0
                    ? "Avant : " + degres(tempAvant) + " → maintenant : " + degres(apres)
                    : "Température maintenant : " + (apres < 0 ? "?" : degres(apres));
            res.setText("✅ Optimisation terminée. " + t + ". La baisse continue pendant quelques minutes "
                    + "si le téléphone reste au repos.");
        }, 30000);
    }

    private long viderCache(File d) {
        if (d == null || !d.exists()) return 0;
        long t = 0;
        File[] l = d.listFiles();
        if (l != null) {
            for (File f : l) {
                t += f.isDirectory() ? viderCache(f) : f.length();
                f.delete();
            }
        }
        return t;
    }

    private void baisserLuminosite() {
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "Autorisez la modification des réglages, puis appuyez de nouveau", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
            }
            return;
        }
        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 70);   // sur 255
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 70 / 255f;
            getWindow().setAttributes(lp);
            Toast.makeText(this, "Luminosité baissée (réglable dans les paramètres d'affichage)", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de changer la luminosité", Toast.LENGTH_LONG).show();
        }
    }

    private boolean accesUtilisation() {
        try {
            AppOpsManager ao = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Les applications les plus utilisées ces dernières 24 h : ce sont elles qui chauffent et vident la batterie. */
    private void afficherConsommation() {
        corpsChaleur.addView(Ui.texte(this, "📊 Applications qui consomment le plus (24 h)", 14, Ui.TXT, true),
                Ui.lp(this, -1, -2, 0f, 14, 0));
        if (!accesUtilisation()) {
            corpsChaleur.addView(Ui.texte(this, "Pour savoir lesquelles consomment, Android demande l'autorisation "
                    + "« Accès aux données d'utilisation ».", 12, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 4, 0));
            Button b = Ui.bouton(this, "Autoriser l'accès aux statistiques", 0xFF3A7BD5);
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            corpsChaleur.addView(b, Ui.lp(this, -1, -2, 0f, 6, 0));
            return;
        }
        try {
            UsageStatsManager us = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> l = us.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24L * 3600 * 1000, now);
            Map<String, Long> temps = new HashMap<>();
            if (l != null) {
                for (UsageStats u : l) {
                    long t = u.getTotalTimeInForeground();
                    if (t <= 0) continue;
                    Long d = temps.get(u.getPackageName());
                    temps.put(u.getPackageName(), (d == null ? 0 : d) + t);
                }
            }
            PackageManager pm = getPackageManager();
            List<Map.Entry<String, Long>> tri = new ArrayList<>(temps.entrySet());
            tri.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            int affiches = 0;
            for (Map.Entry<String, Long> en : tri) {
                if (affiches >= 5) break;
                final String pkg = en.getKey();
                if (pkg.equals(getPackageName()) || en.getValue() < 2 * 60 * 1000) continue;
                String nom = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                    nom = String.valueOf(pm.getApplicationLabel(ai));
                } catch (Exception ignore) {
                    continue;
                }
                long min = en.getValue() / 60000;
                String duree = min >= 60 ? (min / 60) + " h " + String.format(Locale.FRANCE, "%02d", min % 60) + " min" : min + " min";
                LinearLayout ligne = new LinearLayout(this);
                ligne.setGravity(Gravity.CENTER_VERTICAL);
                ligne.addView(Ui.texte(this, nom + " — " + duree, 13, Ui.TXT2, false), new LinearLayout.LayoutParams(0, -2, 1f));
                Button b = Ui.bouton(this, "Arrêter / infos", 0xFF3A7BD5);
                b.setTextSize(11);
                b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg))));
                ligne.addView(b);
                corpsChaleur.addView(ligne, Ui.lp(this, -1, -2, 0f, 4, 0));
                affiches++;
            }
            if (affiches == 0) {
                corpsChaleur.addView(Ui.texte(this, "Aucune application n'a été beaucoup utilisée.", 12, Ui.TXT2, false),
                        Ui.lp(this, -1, -2, 0f, 4, 0));
            } else {
                corpsChaleur.addView(Ui.texte(this, "« Arrêter / infos » ouvre la fiche de l'application : "
                        + "appuyez sur « Forcer l'arrêt », ou limitez sa batterie.", 11, Ui.TXT2, false),
                        Ui.lp(this, -1, -2, 0f, 4, 0));
            }
        } catch (Exception e) {
            corpsChaleur.addView(Ui.texte(this, "Statistiques indisponibles sur ce téléphone.", 12, Ui.TXT2, false));
        }
    }

    @Override
    protected void onDestroy() {
        hUi.removeCallbacksAndMessages(null);
        super.onDestroy();
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
        parTaille.clear();
        parcourir(racine, 0, false, caches, menaces);
        runOnUiThread(() -> statut.setText("Recherche des doublons…"));
        final List<List<File>> doublons = trouverDoublons();
        parTaille.clear();
        runOnUiThread(() -> {
            afficherMenaces(menaces, racine);
            afficherDoublons(doublons, racine);
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
                if (!dansCache && !cache) {
                    long len = f.length();
                    if (len >= MIN_DOUBLON) {
                        List<File> l = parTaille.get(len);
                        if (l == null) { l = new ArrayList<>(); parTaille.put(len, l); }
                        l.add(f);
                    }
                }
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
