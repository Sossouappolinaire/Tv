package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * Mise à jour de l'application elle-même : l'APK publié par l'administrateur est stocké dans la base.
 * Chaque compte connecté voit « nouvelle mise à jour » (avec la date de sortie), touche « Mettre à jour »,
 * l'APK est téléchargé puis installé PAR-DESSUS l'ancienne version (même nom de paquet + même clé de signature :
 * rien à désinstaller, les données du compte sont conservées).
 */
final class MiseAJour {

    private MiseAJour() {}

    static final String ACTION_STATUT = "bj.radio.MAJ_STATUT";

    /** Dernière réponse de la base (pour le bandeau de l'accueil). */
    static volatile JSONObject derniere;

    /** Numéro de version déjà proposé pendant cette ouverture de l'appli (on ne répète pas la fenêtre à chaque écran). */
    private static long dejaProposee = 0;

    // ------------------------------------------------------------------ version installée / publiée

    static long versionInstallee(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String nomInstalle(Context c) {
        try {
            String n = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return n == null ? "?" : n;
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean disponible(Context c, JSONObject r) {
        return r != null && r.optBoolean("ok") && !r.optBoolean("aucune") && r.optLong("version_code", 0L) > versionInstallee(c);
    }

    /** Phrase courte pour le bandeau : « Nouvelle mise à jour 1.17 · sortie le 29/10/2026 à 19:05 ». */
    static String resume(JSONObject r) {
        return "Nouvelle mise à jour " + r.optString("version_name", "") + " · sortie le " + Config.date(r.optLong("cree_ms", 0L));
    }

    // ------------------------------------------------------------------ recherche

    /**
     * Interroge la base (hors thread principal) et, s'il y a une version plus récente, affiche la fenêtre « Mettre à jour ».
     * manuel = vrai quand l'utilisateur a lui-même demandé la recherche : on lui répond aussi « déjà à jour ».
     */
    static void verifier(final Activity a, final boolean manuel) {
        new Thread(() -> {
            final JSONObject r = Supabase.majDerniere();
            derniere = r;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (r == null || !r.optBoolean("ok")) {
                    if (manuel) Toast.makeText(a, "Impossible de consulter la base : vérifiez internet.", Toast.LENGTH_LONG).show();
                } else if (disponible(a, r)) {
                    if (manuel) proposer(a, r);
                    else verifierAvecReponse(a, r);
                } else if (manuel) {
                    Toast.makeText(a, "✅ Vous avez déjà la dernière version (" + nomInstalle(a) + ").", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /**
     * À appeler sur le thread principal avec une réponse déjà reçue : ouvre la fenêtre « Mettre à jour » une seule fois
     * par version pendant l'ouverture de l'appli (à chaque retour si la mise à jour est obligatoire).
     */
    static void verifierAvecReponse(Activity a, JSONObject r) {
        if (a.isFinishing() || !disponible(a, r)) return;
        long v = r.optLong("version_code", 0L);
        if (r.optBoolean("obligatoire") || v != dejaProposee) {
            dejaProposee = v;
            proposer(a, r);
        }
    }

    // ------------------------------------------------------------------ fenêtre « Mettre à jour »

    static void proposer(final Activity a, final JSONObject r) {
        final boolean obligatoire = r.optBoolean("obligatoire");
        StringBuilder sb = new StringBuilder();
        sb.append("Version ").append(r.optString("version_name", "")).append("  (vous avez la ").append(nomInstalle(a)).append(")\n");
        sb.append("Date de sortie : ").append(Config.date(r.optLong("cree_ms", 0L))).append('\n');
        long t = r.optLong("taille", 0L);
        if (t > 0) sb.append("Taille : ").append(Supabase.taille(t)).append('\n');
        String note = r.optString("note", "").trim();
        if (!note.isEmpty() && !note.equals("null")) sb.append("\nNouveautés :\n").append(note).append('\n');
        sb.append("\nL'ancienne version est remplacée par la nouvelle : pas besoin de désinstaller, votre compte est conservé.");
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("🆕 Nouvelle mise à jour disponible")
                .setMessage(sb.toString())
                .setCancelable(!obligatoire)
                .setPositiveButton("Mettre à jour", (d, w) -> telecharger(a, r));
        if (!obligatoire) b.setNegativeButton("Plus tard", null);
        b.show();
    }

    // ------------------------------------------------------------------ téléchargement + installation

    static void telecharger(final Activity a, final JSONObject r) {
        final String apk = r.optString("apk", "");
        if (apk.isEmpty()) {
            Toast.makeText(a, "Mise à jour introuvable.", Toast.LENGTH_LONG).show();
            return;
        }
        // Android demande d'autoriser l'appli à installer des mises à jour (une seule fois).
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("Autorisation nécessaire")
                    .setMessage("Android doit d'abord vous laisser installer les mises à jour depuis cette application.\n\n"
                            + "1. Touchez « Ouvrir les réglages »\n2. Activez « Autoriser depuis cette source »\n"
                            + "3. Revenez ici et touchez de nouveau « Mettre à jour ».")
                    .setPositiveButton("Ouvrir les réglages", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(a, "Ouvrez Réglages > Applications > Kouamé Play Box > Installer des applis inconnues", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }

        final TextView texte = Ui.texte(a, "Téléchargement…", 15, 0xFF222222, false);
        final ProgressBar barre = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        barre.setMax(100);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        int q = Ui.dp(a, 20);
        l.setPadding(q, q, q, 0);
        l.addView(texte);
        l.addView(barre, Ui.lp(a, -1, -2, 0f, 12, 0));
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Mise à jour").setView(l).setCancelable(false).create();
        dlg.show();

        final Context app = a.getApplicationContext();
        new Thread(() -> {
            String erreur = "";
            File f = null;
            HttpURLConnection con = null;
            try {
                File dossier = new File(app.getCacheDir(), "maj");
                supprimerContenu(dossier);
                dossier.mkdirs();
                f = new File(dossier, "maj.apk");
                con = Supabase.ouvrirApk(apk);
                if (con.getResponseCode() != 200) {
                    erreur = "Téléchargement refusé (http " + con.getResponseCode() + ").";
                } else {
                    long total = con.getContentLengthLong();
                    if (total <= 0) total = r.optLong("taille", 0L);
                    long lu = 0, dernier = 0;
                    try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            lu += n;
                            long now = System.currentTimeMillis();
                            if (total > 0 && now - dernier > 250) {
                                dernier = now;
                                final int pct = (int) (lu * 100 / total);
                                final long luF = lu, totF = total;
                                a.runOnUiThread(() -> {
                                    barre.setProgress(pct);
                                    texte.setText("Téléchargement… " + Supabase.taille(luF) + " / " + Supabase.taille(totF));
                                });
                            }
                        }
                    }
                    long attendu = r.optLong("taille", 0L);
                    if (attendu > 0 && f.length() != attendu) erreur = "Téléchargement incomplet. Réessayez.";
                }
            } catch (Exception e) {
                erreur = "Connexion interrompue : vérifiez internet et réessayez.";
            } finally {
                if (con != null) con.disconnect();
            }

            if (erreur.isEmpty()) erreur = verifierApk(app, f);
            if (erreur.isEmpty()) {
                a.runOnUiThread(() -> texte.setText("Installation…"));
                erreur = installer(app, f);
            }
            final String e2 = erreur;
            a.runOnUiThread(() -> {
                try {
                    dlg.dismiss();
                } catch (Exception ignore) {
                    // fenêtre déjà fermée
                }
                if (!e2.isEmpty()) {
                    new AlertDialog.Builder(a).setTitle("Mise à jour impossible").setMessage(e2).setPositiveButton("OK", null).show();
                }
                // sinon : Android affiche sa propre fenêtre « Mettre à jour cette application ? » (voir MajRecepteur)
            });
        }).start();
    }

    // ------------------------------------------------------------------ contrôles de l'APK téléchargé

    /** Vérifie que l'APK est bien CETTE application, plus récente, et signée avec la même clé. Renvoie "" si tout est bon. */
    static String verifierApk(Context c, File f) {
        try {
            PackageManager pm = c.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : 0;
            PackageInfo neuf = pm.getPackageArchiveInfo(f.getAbsolutePath(), flags);
            if (neuf == null) return "Le fichier téléchargé n'est pas un APK valide.";
            if (!c.getPackageName().equals(neuf.packageName)) return "Ce fichier n'est pas l'application Kouamé Play Box.";
            long vNeuf = Build.VERSION.SDK_INT >= 28 ? neuf.getLongVersionCode() : neuf.versionCode;
            if (vNeuf <= versionInstallee(c)) return "Cette version n'est pas plus récente que celle installée.";
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo actuel = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] a = signataires(actuel);
                Signature[] n = signataires(neuf);
                if (a != null && n != null && !memeSignature(a, n)) {
                    return "La nouvelle version n'est pas signée avec la même clé que celle installée sur ce téléphone, "
                            + "Android refuse donc de la mettre par-dessus.\n\n"
                            + "Une seule fois : désinstallez l'application, puis installez la dernière version. "
                            + "Les mises à jour suivantes se feront sans désinstaller.";
                }
            }
            return "";
        } catch (Exception e) {
            return "Impossible de vérifier le fichier téléchargé.";
        }
    }

    private static Signature[] signataires(PackageInfo pi) {
        if (Build.VERSION.SDK_INT < 28 || pi == null || pi.signingInfo == null) return null;
        return pi.signingInfo.getApkContentsSigners();
    }

    private static boolean memeSignature(Signature[] a, Signature[] b) {
        for (Signature x : a) {
            boolean trouve = false;
            for (Signature y : b) if (x.equals(y)) { trouve = true; break; }
            if (!trouve) return false;
        }
        return true;
    }

    /** Vrai si l'APK choisi par l'administrateur a la même signature que l'application installée (ou si on ne peut pas le savoir). */
    static boolean memeSignatureQueInstallee(Context c, File f) {
        try {
            if (Build.VERSION.SDK_INT < 28) return true;
            PackageManager pm = c.getPackageManager();
            PackageInfo neuf = pm.getPackageArchiveInfo(f.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo actuel = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] a = signataires(actuel);
            Signature[] n = signataires(neuf);
            return a == null || n == null || memeSignature(a, n);
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ installation par-dessus l'ancienne version

    /** Envoie l'APK à l'installateur d'Android (session de remplacement). Renvoie "" si la demande est partie, sinon un message. */
    private static String installer(Context c, File f) {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("maj.apk", 0, f.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent i = new Intent(c, MajRecepteur.class).setAction(ACTION_STATUT);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getBroadcast(c, id, i, flags);
                s.commit(pend.getIntentSender());
            }
            return "";
        } catch (Exception e) {
            if (id >= 0) {
                try {
                    pi.abandonSession(id);
                } catch (Exception ignore) {
                    // session déjà fermée
                }
            }
            return "Installation impossible : " + (e.getMessage() == null ? "erreur inconnue" : e.getMessage());
        }
    }

    private static void supprimerContenu(File d) {
        File[] fils = d.listFiles();
        if (fils == null) return;
        for (File x : fils) x.delete();
    }
}
