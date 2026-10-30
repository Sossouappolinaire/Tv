package bj.radio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Random;

/**
 * Effets sonores des jeux. Aucun fichier audio à ajouter au projet : les sons sont fabriqués au premier
 * lancement (petits fichiers WAV écrits dans le cache), puis joués avec un SoundPool (faible latence,
 * plusieurs sons en même temps, suit le volume « média » du téléphone).
 *
 * Utilisation : Sons.jouer(contexte, Sons.SAUT);
 */
public final class Sons {

    public static final int PAS = 0, SAUT = 1, PIECE = 2, TIR = 3, TOUCHE = 4, EXPLOSION = 5,
            VERSER = 6, MANGER = 7, PERDRE_VIE = 8, PERDU = 9, GAGNE = 10;
    private static final int NB = 11;
    private static final float[] VOLUME = {0.30f, 0.70f, 0.80f, 0.45f, 0.60f, 0.90f, 0.70f, 0.80f, 0.90f, 1f, 1f};

    private static final int RATE = 22050;
    private static final Random RND = new Random(7);

    private static SoundPool pool;
    private static final int[] ids = new int[NB];
    private static final HashSet<Integer> prets = new HashSet<>();
    private static boolean tente;

    private Sons() { }

    /** Joue un effet. Ne plante jamais : si le son n'est pas prêt ou indisponible, on continue sans bruit. */
    public static void jouer(Context ctx, int son) {
        try {
            init(ctx);
            SoundPool p = pool;
            if (p == null || son < 0 || son >= NB) return;
            int id = ids[son];
            boolean pret;
            synchronized (prets) { pret = prets.contains(id); }
            if (id != 0 && pret) {
                float v = VOLUME[son];
                p.play(id, v, v, 1, 0, 1f);
            }
        } catch (Throwable ignore) {
            // pas de son plutôt qu'un plantage
        }
    }

    private static synchronized void init(Context ctx) {
        if (tente) return;
        tente = true;
        try {
            Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            SoundPool p = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(aa).build();
            p.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                    if (status == 0) synchronized (prets) { prets.add(sampleId); }
                }
            });
            File dir = new File(app.getCacheDir(), "sons");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            for (int i = 0; i < NB; i++) {
                File f = new File(dir, "s" + i + ".wav");
                ecrire(f, fabriquer(i));
                ids[i] = p.load(f.getAbsolutePath(), 1);
            }
            pool = p;
        } catch (Throwable t) {
            pool = null;
        }
    }

    // ------------------------------------------------------------------ fabrication des sons

    private static short[] fabriquer(int son) {
        switch (son) {
            case PAS:        return tonalite(180, 120, 35, 3, 0.55);                       // petit « tic » sourd
            case SAUT:       return tonalite(280, 700, 150, 1, 0.55);                      // glissando vers l'aigu
            case PIECE:      return coller(tonalite(988, 988, 60, 1, 0.5), tonalite(1319, 1319, 160, 1, 0.5));
            case TIR:        return coller(tonalite(1500, 300, 90, 1, 0.5), tonalite(900, 200, 40, 3, 0.35));
            case TOUCHE:     return coller(tonalite(220, 140, 70, 1, 0.6), tonalite(0, 0, 50, 3, 0.5));
            case EXPLOSION:  return coller(tonalite(0, 0, 220, 3, 0.9), tonalite(110, 50, 260, 0, 0.8));
            case VERSER:     return coller(tonalite(400, 700, 90, 0, 0.5), tonalite(500, 900, 90, 0, 0.45),
                                           tonalite(450, 800, 120, 0, 0.4));              // « glou glou »
            case MANGER:     return coller(tonalite(500, 900, 55, 1, 0.45), tonalite(900, 1300, 70, 1, 0.45));
            case PERDRE_VIE: return coller(tonalite(500, 180, 260, 1, 0.6), tonalite(160, 90, 220, 1, 0.55));
            case PERDU:      return coller(tonalite(392, 392, 170, 1, 0.55), tonalite(330, 330, 170, 1, 0.55),
                                           tonalite(262, 262, 170, 1, 0.55), tonalite(196, 130, 520, 1, 0.55));
            default:         return coller(tonalite(523, 523, 110, 1, 0.5), tonalite(659, 659, 110, 1, 0.5),
                                           tonalite(784, 784, 110, 1, 0.5), tonalite(1047, 1047, 380, 1, 0.5)); // GAGNE
        }
    }

    /** forme : 0 = sinus, 1 = carré doux, 3 = bruit. La fréquence glisse de f0 à f1. */
    private static short[] tonalite(double f0, double f1, int ms, int forme, double vol) {
        int n = RATE * ms / 1000;
        short[] o = new short[n];
        double ph = 0;
        int attaque = Math.max(1, RATE / 250);   // 4 ms pour éviter les « clics »
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double f = f0 + (f1 - f0) * t;
            ph += 2 * Math.PI * f / RATE;
            double s;
            if (forme == 3) s = RND.nextDouble() * 2 - 1;
            else if (forme == 1) s = Math.sin(ph) >= 0 ? 0.6 : -0.6;
            else s = Math.sin(ph);
            double env = Math.min(1.0, i / (double) attaque) * Math.pow(1 - t, 1.4);
            o[i] = (short) (s * env * vol * 32767);
        }
        return o;
    }

    private static short[] coller(short[]... morceaux) {
        int total = 0;
        for (short[] m : morceaux) total += m.length;
        short[] r = new short[total];
        int pos = 0;
        for (short[] m : morceaux) {
            System.arraycopy(m, 0, r, pos, m.length);
            pos += m.length;
        }
        return r;
    }

    private static void ecrire(File f, short[] pcm) throws Exception {
        int octets = pcm.length * 2;
        byte[] b = new byte[44 + octets];
        ecrireTexte(b, 0, "RIFF");
        ecrireInt(b, 4, 36 + octets);
        ecrireTexte(b, 8, "WAVE");
        ecrireTexte(b, 12, "fmt ");
        ecrireInt(b, 16, 16);
        b[20] = 1; b[21] = 0;                 // PCM
        b[22] = 1; b[23] = 0;                 // mono
        ecrireInt(b, 24, RATE);
        ecrireInt(b, 28, RATE * 2);
        b[32] = 2; b[33] = 0;                 // octets par échantillon
        b[34] = 16; b[35] = 0;                // 16 bits
        ecrireTexte(b, 36, "data");
        ecrireInt(b, 40, octets);
        for (int i = 0; i < pcm.length; i++) {
            b[44 + 2 * i] = (byte) (pcm[i] & 0xFF);
            b[45 + 2 * i] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        FileOutputStream os = new FileOutputStream(f);
        try { os.write(b); } finally { os.close(); }
    }

    private static void ecrireTexte(byte[] b, int pos, String s) {
        for (int i = 0; i < s.length(); i++) b[pos + i] = (byte) s.charAt(i);
    }

    private static void ecrireInt(byte[] b, int pos, int v) {
        b[pos] = (byte) (v & 0xFF);
        b[pos + 1] = (byte) ((v >> 8) & 0xFF);
        b[pos + 2] = (byte) ((v >> 16) & 0xFF);
        b[pos + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
