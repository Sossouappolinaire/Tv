package bj.radio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Zips de contenu (pages web, images, scripts) stockés dans la base. Une page du fichier de configuration qui contient
 * « zip » est téléchargée puis décompressée dans le dossier privé de l'appli ; elle est refaite dès que le zip est remplacé.
 */
final class Paquets {

    private Paquets() {}

    private static final long MAX_TOTAL = 150L * 1024 * 1024;   // taille maximale une fois décompressé
    private static final int MAX_FICHIERS = 5000;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("paquets", Context.MODE_PRIVATE);
    }

    static File dossier(Context c, String id) {
        return new File(new File(c.getFilesDir(), "paquets"), id.replaceAll("[^A-Za-z0-9_-]", "_"));
    }

    /** Dossier qui contient réellement la page d'accueil (le zip peut avoir un dossier unique à l'intérieur). */
    static File racine(File dossier, String index) {
        if (new File(dossier, index).isFile()) return dossier;
        File[] sous = dossier.listFiles();
        if (sous != null && sous.length == 1 && sous[0].isDirectory() && new File(sous[0], index).isFile()) return sous[0];
        return dossier;
    }

    static boolean present(Context c, String id) {
        return p(c).getLong(id + ".modifie", 0L) > 0 && dossier(c, id).isDirectory();
    }

    /**
     * Télécharge et décompresse le zip d'une page si le fichier de la base est nouveau ou remplacé.
     * Renvoie vrai si le contenu a été mis à jour. À appeler hors du thread principal.
     */
    static boolean synchroniserUn(Context c, String id, String zip) {
        JSONObject info = Supabase.paquetInfo(zip);
        if (info == null || !info.optBoolean("ok") || info.optBoolean("aucune")) return false;
        long modifie = info.optLong("modifie_ms", 0L);
        SharedPreferences sp = p(c);
        if (present(c, id) && sp.getLong(id + ".modifie", 0L) == modifie && zip.equals(sp.getString(id + ".nom", ""))) return false;

        File racineTous = new File(c.getFilesDir(), "paquets");
        File tmp = new File(racineTous, "_tmp_" + System.currentTimeMillis());
        HttpURLConnection con = null;
        try {
            con = Supabase.ouvrirPaquet(zip);
            if (con.getResponseCode() != 200) return false;
            if (!tmp.mkdirs()) return false;
            decompresser(con.getInputStream(), tmp);
            File cible = dossier(c, id);
            supprimer(cible);
            if (!tmp.renameTo(cible)) return false;
            sp.edit().putLong(id + ".modifie", modifie)
                    .putLong(id + ".cree", info.optLong("cree_ms", 0L))
                    .putLong(id + ".applique", System.currentTimeMillis())
                    .putString(id + ".nom", zip).apply();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
            supprimer(tmp);   // reste seulement si le téléchargement a échoué
        }
    }

    /** Parcourt les pages du fichier de configuration et met à jour celles qui ont un zip. Vrai si quelque chose a changé. */
    static boolean synchroniser(Context c) {
        JSONObject cfg = Config.contenu(c);
        JSONObject pages = cfg == null ? null : cfg.optJSONObject("pages");
        if (pages == null) return false;
        boolean change = false;
        Iterator<String> it = pages.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject pg = pages.optJSONObject(id);
            if (pg == null) continue;
            String zip = pg.optString("zip", "").trim();
            if (zip.isEmpty()) continue;
            if (synchroniserUn(c, id, zip)) change = true;
        }
        return change;
    }

    /** Lignes « contenu » pour l'écran Mise à jour : une par page avec zip. Null s'il n'y en a pas. */
    static String info(Context c) {
        JSONObject cfg = Config.contenu(c);
        JSONObject pages = cfg == null ? null : cfg.optJSONObject("pages");
        if (pages == null) return null;
        StringBuilder sb = new StringBuilder();
        SharedPreferences sp = p(c);
        Iterator<String> it = pages.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject pg = pages.optJSONObject(id);
            if (pg == null || pg.optString("zip", "").trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            if (present(c, id)) {
                sb.append("📦 ").append(id).append(" : ").append(sp.getString(id + ".nom", ""))
                        .append("\n   zip créé le ").append(Config.date(sp.getLong(id + ".cree", 0L)))
                        .append("\n   appliqué le ").append(Config.date(sp.getLong(id + ".applique", 0L)));
            } else {
                sb.append("📦 ").append(id).append(" : pas encore téléchargé");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void decompresser(InputStream flux, File dest) throws Exception {
        String base = dest.getCanonicalPath() + File.separator;
        long total = 0;
        int n = 0;
        try (ZipInputStream zin = new ZipInputStream(flux)) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                if (++n > MAX_FICHIERS) throw new Exception("zip trop grand");
                File f = new File(dest, e.getName());
                String chemin = f.getCanonicalPath();
                if (!chemin.startsWith(base)) continue;   // protection : jamais en dehors du dossier
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    int r;
                    while ((r = zin.read(buf)) > 0) {
                        total += r;
                        if (total > MAX_TOTAL) throw new Exception("zip trop grand");
                        out.write(buf, 0, r);
                    }
                }
            }
        }
    }

    private static void supprimer(File f) {
        if (f == null || !f.exists()) return;
        File[] fils = f.listFiles();
        if (fils != null) for (File x : fils) supprimer(x);
        f.delete();
    }
}
