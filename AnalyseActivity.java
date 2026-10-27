package bj.radio;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
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
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Analyse du téléphone :
 *  1) applications suspectes (détection par comportement : permissions, origine, accès spéciaux)
 *  2) fichiers et dossiers cachés (nom commençant par un point), rangés par dossier, supprimables
 */
public class AnalyseActivity extends Activity {

    static class Suspect {
        String pkg, nom;
        int score;
        List<String> raisons = new ArrayList<>();
        View carte;
    }

    static class Element {
        File f;
        boolean dossier;
        long taille;
        int nb;
        CheckBox box;
        View ligne;
        LinearLayout groupe;
    }

    private TextView statut;
    private Button btnAnalyse, btnSupprimer;
    private LinearLayout zoneResultats, corpsApps, corpsFichiers;
    private final List<Suspect> suspects = new ArrayList<>();
    private final List<Element> elements = new ArrayList<>();
    private volatile boolean enCours = false;
    private boolean attendAcces = false;
    private int nbAppsAnalysees = 0;
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
                "Cherche les applications suspectes, puis les fichiers cachés rangés par dossier. "
                + "Vous choisissez ce qu'il faut supprimer : rien n'est effacé sans votre accord.\n\n"
                + "Ce n'est pas un antivirus à base de signatures : l'analyse repose sur le comportement "
                + "(accès demandés, origine de l'appli). Une appli signalée n'est pas forcément un virus.",
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
        // retire les applis désinstallées entre-temps
        PackageManager pm = getPackageManager();
        for (Suspect s : new ArrayList<>(suspects)) {
            try {
                pm.getPackageInfo(s.pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                if (s.carte != null && s.carte.getParent() instanceof LinearLayout)
                    ((LinearLayout) s.carte.getParent()).removeView(s.carte);
                suspects.remove(s);
            }
        }
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
        suspects.clear();
        elements.clear();
        zoneResultats.removeAllViews();
        btnSupprimer.setVisibility(View.GONE);
        btnAnalyse.setEnabled(false);
        btnAnalyse.setText("Analyse en cours…");
        statut.setText("Analyse des applications…");

        corpsApps = section("🛡 Applications suspectes");
        corpsFichiers = section("👻 Fichiers cachés");

        new Thread(() -> {
            List<Suspect> res = analyserApps();
            runOnUiThread(() -> afficherApps(res));
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

    // ================= Applications suspectes =================

    private List<Suspect> analyserApps() {
        PackageManager pm = getPackageManager();
        Set<String> access = paquetsAccessibilite();
        Set<String> admins = paquetsAdmin();
        Set<String> notifs = paquetsNotif();
        List<PackageInfo> tous;
        try {
            tous = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } catch (Exception e) {
            tous = new ArrayList<>();
        }
        List<Suspect> res = new ArrayList<>();
        int n = 0;
        for (PackageInfo pi : tous) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || pi.packageName.equals(getPackageName())) continue;
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            n++;
            String pkg = pi.packageName;
            Suspect s = new Suspect();
            s.pkg = pkg;
            try { s.nom = pm.getApplicationLabel(ai).toString(); } catch (Exception e) { s.nom = pkg; }

            if (access.contains(pkg)) {
                s.score += 4;
                s.raisons.add("Service d'accessibilité actif : peut lire l'écran et agir à votre place");
            }
            if (admins.contains(pkg)) {
                s.score += 4;
                s.raisons.add("Administrateur de l'appareil : peut gêner sa propre désinstallation");
            }
            if (notifs.contains(pkg)) {
                s.score += 2;
                s.raisons.add("Lit toutes vos notifications (codes SMS, messages…)");
            }
            if (perm(pi, "android.permission.READ_SMS", true) || perm(pi, "android.permission.RECEIVE_SMS", true)) {
                s.score += 2;
                s.raisons.add("Peut lire vos SMS");
            }
            if (perm(pi, "android.permission.SEND_SMS", true)) {
                s.score += 2;
                s.raisons.add("Peut envoyer des SMS (risque de frais)");
            }
            if (perm(pi, "android.permission.READ_CALL_LOG", true)) {
                s.score += 1;
                s.raisons.add("Peut lire votre journal d'appels");
            }
            if (perm(pi, "android.permission.SYSTEM_ALERT_WINDOW", false)) {
                s.score += 1;
                s.raisons.add("Peut s'afficher par-dessus les autres applis");
            }
            if (perm(pi, "android.permission.REQUEST_INSTALL_PACKAGES", false)) {
                s.score += 1;
                s.raisons.add("Peut installer d'autres applis");
            }
            if (pm.getLaunchIntentForPackage(pkg) == null
                    && pm.getLeanbackLaunchIntentForPackage(pkg) == null) {
                s.score += 2;
                s.raisons.add("Aucune icône de lancement : application cachée");
            }
            if (!depuisBoutique(pm, pkg)) {
                s.score += 1;
                s.raisons.add("Installée hors d'une boutique officielle");
            }
            if ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                s.score += 1;
                s.raisons.add("Version de test (débogage activé)");
            }
            if (s.score >= 3) res.add(s);
        }
        nbAppsAnalysees = n;
        res.sort((a, b) -> b.score - a.score);
        return res;
    }

    private boolean perm(PackageInfo pi, String nom, boolean accordee) {
        if (pi.requestedPermissions == null) return false;
        for (int i = 0; i < pi.requestedPermissions.length; i++) {
            if (nom.equals(pi.requestedPermissions[i])) {
                if (!accordee) return true;
                return pi.requestedPermissionsFlags != null
                        && (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
            }
        }
        return false;
    }

    private boolean depuisBoutique(PackageManager pm, String pkg) {
        String inst = null;
        try {
            if (Build.VERSION.SDK_INT >= 30) inst = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            else inst = pm.getInstallerPackageName(pkg);
        } catch (Exception ignore) { }
        if (inst == null) return false;
        return inst.equals("com.android.vending") || inst.equals("com.sec.android.app.samsungapps")
                || inst.equals("com.huawei.appmarket") || inst.equals("com.amazon.venezia")
                || inst.equals("com.xiaomi.mipicks") || inst.equals("com.heytap.market")
                || inst.equals("com.vivo.appstore");
    }

    private Set<String> paquetsAccessibilite() {
        Set<String> s = new HashSet<>();
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            for (AccessibilityServiceInfo i : am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                s.add(i.getResolveInfo().serviceInfo.packageName);
            }
        } catch (Exception ignore) { }
        return s;
    }

    private Set<String> paquetsAdmin() {
        Set<String> s = new HashSet<>();
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            List<ComponentName> l = dpm.getActiveAdmins();
            if (l != null) for (ComponentName c : l) s.add(c.getPackageName());
        } catch (Exception ignore) { }
        return s;
    }

    private Set<String> paquetsNotif() {
        Set<String> s = new HashSet<>();
        try {
            String v = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (v != null) {
                for (String part : v.split(":")) {
                    ComponentName c = ComponentName.unflattenFromString(part);
                    if (c != null) s.add(c.getPackageName());
                }
            }
        } catch (Exception ignore) { }
        return s;
    }

    private void afficherApps(List<Suspect> res) {
        suspects.addAll(res);
        corpsApps.removeAllViews();
        if (res.isEmpty()) {
            corpsApps.addView(Ui.texte(this, "✅ Aucune application suspecte parmi les "
                    + nbAppsAnalysees + " applications installées.", 14, Ui.TXT2, false));
            return;
        }
        corpsApps.addView(Ui.texte(this, res.size() + " application(s) à vérifier sur "
                + nbAppsAnalysees + " analysées :", 13, Ui.TXT2, false));
        PackageManager pm = getPackageManager();
        for (Suspect s : res) {
            LinearLayout carte = new LinearLayout(this);
            carte.setOrientation(LinearLayout.VERTICAL);
            carte.setBackground(Ui.rond(Ui.CARD, 16, this));
            int q = Ui.dp(this, 14);
            carte.setPadding(q, q, q, q);

            LinearLayout haut = new LinearLayout(this);
            haut.setGravity(Gravity.CENTER_VERTICAL);
            ImageView ic = new ImageView(this);
            try { ic.setImageDrawable(pm.getApplicationIcon(s.pkg)); } catch (Exception ignore) { }
            haut.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
            LinearLayout noms = new LinearLayout(this);
            noms.setOrientation(LinearLayout.VERTICAL);
            noms.addView(Ui.texte(this, s.nom, 16, Ui.TXT, true));
            noms.addView(Ui.texte(this, s.pkg, 11, Ui.TXT2, false));
            haut.addView(noms, Ui.lp(this, 0, -2, 1f, 0, 12));
            boolean eleve = s.score >= 5;
            TextView niveau = Ui.texte(this, eleve ? "Risque élevé" : "À vérifier", 12, Ui.TXT, true);
            niveau.setBackground(Ui.rond(eleve ? 0xFFC0392B : 0xFFD68910, 10, this));
            int m = Ui.dp(this, 8);
            niveau.setPadding(m, m / 2, m, m / 2);
            haut.addView(niveau);
            carte.addView(haut);

            StringBuilder sb = new StringBuilder();
            for (String r : s.raisons) sb.append("• ").append(r).append('\n');
            carte.addView(Ui.texte(this, sb.toString().trim(), 12, Ui.TXT2, false),
                    Ui.lp(this, -1, -2, 0f, 10, 0));

            LinearLayout bs = new LinearLayout(this);
            Button del = Ui.bouton(this, "Désinstaller", 0xFFC0392B);
            del.setOnClickListener(v -> startActivity(
                    new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + s.pkg))));
            Button info = Ui.bouton(this, "Infos / Paramètres", 0xFF3A7BD5);
            info.setOnClickListener(v -> startActivity(
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s.pkg))));
            bs.addView(del, new LinearLayout.LayoutParams(0, -2, 1f));
            bs.addView(info, Ui.lp(this, 0, -2, 1f, 0, 8));
            carte.addView(bs, Ui.lp(this, -1, -2, 0f, 10, 0));

            s.carte = carte;
            corpsApps.addView(carte, Ui.lp(this, -1, -2, 0f, 10, 0));
        }
        corpsApps.addView(Ui.texte(this,
                "Si « Désinstaller » est refusé : ouvrez « Infos / Paramètres », ou désactivez d'abord "
                + "l'accès d'accessibilité / d'administrateur de l'appareil dans les réglages Android.",
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
        corpsFichiers.addView(Ui.texte(this,
                "Pour chercher (et pouvoir supprimer) les fichiers cachés, Android demande d'autoriser "
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
        List<Element> trouves = new ArrayList<>();
        parcourir(racine, 0, trouves);
        runOnUiThread(() -> afficherFichiers(trouves, racine));
    }

    private void parcourir(File dossier, int profondeur, List<Element> trouves) {
        if (profondeur > 14) return;
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
            if (nom.startsWith(".")) {
                if (nom.equals(".nomedia")) continue; // inoffensif : simple consigne à la galerie
                Element e = new Element();
                e.f = f;
                e.dossier = f.isDirectory();
                if (e.dossier) {
                    long[] acc = new long[2];
                    taille(f, acc);
                    e.taille = acc[0];
                    e.nb = (int) acc[1];
                } else {
                    e.taille = f.length();
                    e.nb = 1;
                }
                trouves.add(e);
                continue;
            }
            if (f.isDirectory()) parcourir(f, profondeur + 1, trouves);
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
