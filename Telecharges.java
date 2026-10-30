package bj.radio;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Mémoire LOCALE des vidéos téléchargées : lien Facebook → fichier enregistré sur le téléphone.
 * Rien n'est envoyé à la base : les fichiers restent sur le téléphone de l'utilisateur.
 * Si le fichier est supprimé, la vidéo redevient « à télécharger ».
 */
final class Telecharges {

    private Telecharges() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("telecharges", Context.MODE_PRIVATE);
    }

    private static String cle(String lien) {
        return lien == null ? "" : Supabase.nettoyerLien(lien.trim());
    }

    /** À appeler quand un téléchargement s'est terminé correctement. */
    static void enregistrer(Context c, String lien, File fichier) {
        String k = cle(lien);
        if (k.isEmpty() || fichier == null) return;
        p(c).edit().putString(k, fichier.getAbsolutePath()).apply();
    }

    /** Le fichier téléchargé pour ce lien, ou null s'il n'y en a pas (ou s'il a été supprimé). */
    static File fichier(Context c, String lien) {
        String k = cle(lien);
        if (k.isEmpty()) return null;
        String chemin = p(c).getString(k, null);
        if (chemin == null) return null;
        File f = new File(chemin);
        if (f.isFile() && f.length() > 0) return f;
        p(c).edit().remove(k).apply();   // fichier supprimé : on oublie
        return null;
    }
}
