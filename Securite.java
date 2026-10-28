package bj.radio;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Verrouillage de l'application : code PIN (4 à 6 chiffres) et/ou empreinte digitale.
 * Le PIN n'est jamais stocké en clair : seulement son empreinte SHA-256 salée, sur le téléphone.
 */
final class Securite {

    private Securite() {}

    private static final String PREFS = "securite";
    private static SharedPreferences prefs;

    /** Vrai tant que l'écran de verrouillage doit être franchi. */
    static volatile boolean verrouille = false;

    static void init(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ PIN

    static boolean pinActif() {
        return prefs != null && !prefs.getString("hash", "").isEmpty();
    }

    static void definirPin(String pin) {
        byte[] sel = new byte[16];
        new SecureRandom().nextBytes(sel);
        String s = hex(sel);
        prefs.edit().putString("sel", s).putString("hash", empreinte(s, pin)).putInt("long", pin.length())
                .putInt("echecs", 0).putLong("bloque_jusqua", 0).apply();
    }

    /** Nombre de chiffres du PIN enregistré. */
    static int longueur() {
        return prefs == null ? 4 : prefs.getInt("long", 4);
    }

    static boolean verifierPin(String pin) {
        if (!pinActif()) return true;
        boolean ok = empreinte(prefs.getString("sel", ""), pin).equals(prefs.getString("hash", ""));
        if (ok) {
            prefs.edit().putInt("echecs", 0).putLong("bloque_jusqua", 0).apply();
        } else {
            int n = prefs.getInt("echecs", 0) + 1;
            SharedPreferences.Editor e = prefs.edit().putInt("echecs", n);
            if (n >= 5) e.putLong("bloque_jusqua", System.currentTimeMillis() + 30_000L * (n - 4));
            e.apply();
        }
        return ok;
    }

    /** Secondes restantes avant de pouvoir réessayer (0 si l'on peut saisir). */
    static int attente() {
        if (prefs == null) return 0;
        long reste = prefs.getLong("bloque_jusqua", 0) - System.currentTimeMillis();
        return reste > 0 ? (int) ((reste + 999) / 1000) : 0;
    }

    static void retirerPin() {
        if (prefs != null) prefs.edit().clear().apply();
    }

    /** Efface tout (déconnexion). */
    static void effacer() {
        retirerPin();
        verrouille = false;
    }

    private static String empreinte(String sel, String pin) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return hex(d.digest((sel + ":" + pin).getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------------ empreinte digitale

    static boolean biometrieActive() {
        return pinActif() && prefs.getBoolean("bio", false) && biometrieDisponible(null);
    }

    static void activerBiometrie(boolean oui) {
        if (prefs != null) prefs.edit().putBoolean("bio", oui).apply();
    }

    /** Le téléphone sait-il lire une empreinte (et en a-t-il une d'enregistrée) ? */
    static boolean biometrieDisponible(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            if (c == null) c = App.contexte();
            if (c == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                BiometricManager m = c.getSystemService(BiometricManager.class);
                if (m == null) return false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    return m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                            == BiometricManager.BIOMETRIC_SUCCESS;
                return m.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
            }
            return c.getPackageManager().hasSystemFeature("android.hardware.fingerprint");
        } catch (Exception e) {
            return false;
        }
    }

    interface Retour {
        void reussi();
        void echec();   // annulé ou refusé : l'appelant retombe sur le PIN
    }

    /** Affiche la fenêtre système d'empreinte digitale. */
    static void demanderBiometrie(Activity a, String titre, String sousTitre, Retour r) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) { r.echec(); return; }
        try {
            BiometricPrompt p = new BiometricPrompt.Builder(a)
                    .setTitle(titre)
                    .setSubtitle(sousTitre)
                    .setNegativeButton("Utiliser le code", a.getMainExecutor(), (d, w) -> r.echec())
                    .build();
            p.authenticate(new CancellationSignal(), a.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult res) {
                    r.reussi();
                }
                @Override
                public void onAuthenticationError(int code, CharSequence msg) {
                    r.echec();
                }
            });
        } catch (Exception e) {
            r.echec();
        }
    }
}
