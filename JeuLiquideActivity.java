package bj.radio;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

/**
 * Jeu du Liquide (tri de liquides) : on touche un tube puis un autre pour verser le liquide du dessus.
 * On ne peut verser que sur un tube vide ou sur la même couleur, s'il reste de la place.
 * Le niveau est gagné quand chaque tube ne contient plus qu'une seule couleur.
 * Le liquide se verse en animation (le tube se penche et le filet coule dans l'autre) ; un coup impossible fait
 * trembler les deux tubes en rouge, avec un son et un message.
 * Boutons : Annuler, Recommencer, + Tube (un tube vide en plus, une fois par niveau).
 */
public class JeuLiquideActivity extends Activity {

    private VueLiquide vue;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.fenetre(this);
        vue = new VueLiquide(this);
        setContentView(vue);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (vue != null) vue.sauver();
    }

    private static final class VueLiquide extends View {

        private static final int CAP = 4;                 // un tube contient 4 couches
        private static final int[] COULEURS = {
                0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFFFB8C00, 0xFF8E24AA,
                0xFFF06292, 0xFF00ACC1, 0xFF6D4C41, 0xFFC0CA33, 0xFF00897B, 0xFF90A4AE};

        private final Context ctx;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Random rnd = new Random();
        private final ArrayList<int[]> historique = new ArrayList<>();
        private int[] depart = new int[0];                 // état au début du niveau (pour « Recommencer »)
        private final ArrayList<RectF> zones = new ArrayList<>();

        private int[][] tube = new int[0][];
        private int[] nb = new int[0];
        private int nt, niveau, coups, record, choisi = -1;
        private boolean gagne, tubeAjoute;
        private float larg, haut, tubeW, tubeH, couche;
        private RectF bAnnuler = new RectF(), bRecommencer = new RectF(), bTube = new RectF();
        private Shader fond;

        // animation de versement : le tube source se penche au-dessus du tube cible et le liquide coule
        private static final long ANIM_MS = 800;
        private int animA = -1, animB = -1, animN, animCol;
        private long animDebut;
        private boolean finEnAttente;
        // effet « coup impossible » : les deux tubes tremblent et clignotent en rouge
        private static final long ERR_MS = 480;
        private int errA = -1, errB = -1;
        private long errDebut;
        private String msgErreur = "";
        private long msgFin;

        VueLiquide(Context c) {
            super(c);
            ctx = c;
            d = c.getResources().getDisplayMetrics().density;
            record = JeuxActivity.record(c, "liquide");
            niveau = Math.min(record + 1, 60);
            nouveauNiveau();
        }

        void sauver() {
            if (record > 0) JeuxActivity.enregistrer(ctx, "liquide", record);
        }

        // ------------------------------------------------------------------ niveaux

        private void nouveauNiveau() {
            int couleurs = Math.min(3 + (niveau - 1) / 2, COULEURS.length);
            nt = couleurs + 2;
            ArrayList<Integer> unites = new ArrayList<>();
            for (int c = 0; c < couleurs; c++) for (int i = 0; i < CAP; i++) unites.add(c);
            do {
                Collections.shuffle(unites, rnd);
                tube = new int[nt][CAP];
                nb = new int[nt];
                for (int i = 0; i < unites.size(); i++) {
                    int t = i / CAP;
                    tube[t][nb[t]++] = unites.get(i);
                }
            } while (resolu());
            coups = 0;
            choisi = -1;
            gagne = false;
            tubeAjoute = false;
            historique.clear();
            animA = -1;
            animB = -1;
            finEnAttente = false;
            errA = -1;
            errB = -1;
            msgFin = 0;
            depart = instantane();
            calculerDispo();
            invalidate();
        }

        private boolean resolu() {
            for (int i = 0; i < nt; i++) {
                if (nb[i] == 0) continue;
                if (nb[i] != CAP) return false;
                for (int j = 1; j < CAP; j++) if (tube[i][j] != tube[i][0]) return false;
            }
            return true;
        }

        // ------------------------------------------------------------------ règles

        private boolean peutVerser(int a, int b) {
            if (a == b || nb[a] == 0 || nb[b] >= CAP) return false;
            return nb[b] == 0 || tube[b][nb[b] - 1] == tube[a][nb[a] - 1];
        }

        private int[] instantane() {
            int[] s = new int[1 + nt * (CAP + 1)];
            s[0] = nt;
            int k = 1;
            for (int i = 0; i < nt; i++) {
                s[k++] = nb[i];
                for (int j = 0; j < CAP; j++) s[k++] = tube[i][j];
            }
            return s;
        }

        private void restaurer(int[] s) {
            if (s[0] < nt) tubeAjoute = false;
            nt = s[0];
            tube = new int[nt][CAP];
            nb = new int[nt];
            int k = 1;
            for (int i = 0; i < nt; i++) {
                nb[i] = s[k++];
                for (int j = 0; j < CAP; j++) tube[i][j] = s[k++];
            }
            calculerDispo();
        }

        private void verser(int a, int b) {
            historique.add(instantane());
            if (historique.size() > 80) historique.remove(0);
            int couleur = tube[a][nb[a] - 1];
            int avant = nb[a];
            while (nb[a] > 0 && tube[a][nb[a] - 1] == couleur && nb[b] < CAP) {
                tube[b][nb[b]++] = couleur;
                nb[a]--;
            }
            coups++;
            Sons.jouer(ctx, Sons.VERSER);
            // l'état est déjà à jour ; l'animation montre le liquide qui coule, la victoire est annoncée après
            animA = a;
            animB = b;
            animN = avant - nb[a];
            animCol = couleur;
            animDebut = SystemClock.uptimeMillis();
            finEnAttente = resolu();
            postInvalidateOnAnimation();
        }

        /** Fin de l'animation : on annonce la victoire si le niveau est résolu. */
        private void terminerAnim() {
            animA = -1;
            animB = -1;
            if (finEnAttente) {
                finEnAttente = false;
                Sons.jouer(ctx, Sons.GAGNE);
                gagne = true;
                if (niveau > record) {
                    record = niveau;
                    sauver();
                }
            }
        }

        /** Coup impossible : les deux tubes tremblent en rouge, avec un son, une vibration et une explication. */
        private void erreur(int a, int b) {
            errA = a;
            errB = b;
            errDebut = SystemClock.uptimeMillis();
            msgErreur = nb[b] >= CAP ? "Ce tube est plein" : "Couleurs différentes : impossible de verser";
            msgFin = errDebut + 1600;
            Sons.jouer(ctx, Sons.TOUCHE);
            try { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Exception ignore) { /* sans vibration */ }
            postInvalidateOnAnimation();
        }

        // ------------------------------------------------------------------ dispositions

        private void calculerDispo() {
            if (larg <= 0f || nt == 0) return;
            int cols = nt <= 5 ? nt : (nt <= 8 ? 4 : 5);
            int lignes = (nt + cols - 1) / cols;
            float zoneHaut = haut - 190f * d;
            float cell = larg / cols;
            tubeW = Math.min(cell * 0.52f, 64f * d);
            tubeH = Math.min(zoneHaut / lignes * 0.82f, tubeW * 4.4f);
            couche = (tubeH - 14f * d) / CAP;
            zones.clear();
            float totalH = lignes * tubeH + (lignes - 1) * 34f * d;
            float y0 = 104f * d + Math.max(0f, (zoneHaut - totalH) / 2f);
            for (int i = 0; i < nt; i++) {
                int ligne = i / cols, col = i % cols;
                int dansLigne = Math.min(cols, nt - ligne * cols);
                float x0 = (larg - dansLigne * cell) / 2f + col * cell + (cell - tubeW) / 2f;
                float y = y0 + ligne * (tubeH + 34f * d);
                zones.add(new RectF(x0, y, x0 + tubeW, y + tubeH));
            }
            float by = haut - 96f * d, bh = 54f * d, marge = 14f * d;
            float bw = (larg - 4f * marge) / 3f;
            bAnnuler.set(marge, by, marge + bw, by + bh);
            bRecommencer.set(2f * marge + bw, by, 2f * marge + 2f * bw, by + bh);
            bTube.set(3f * marge + 2f * bw, by, 3f * marge + 3f * bw, by + bh);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            larg = w;
            haut = h;
            fond = new LinearGradient(0, 0, 0, h, 0xFF16213E, 0xFF4A1F6E, Shader.TileMode.CLAMP);
            calculerDispo();
        }

        // ------------------------------------------------------------------ touches

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
            if (animA >= 0) return true;                 // pendant que le liquide coule, on attend
            float x = e.getX(), y = e.getY();
            if (gagne) {
                niveau = Math.min(niveau + 1, 60);
                nouveauNiveau();
                return true;
            }
            if (bAnnuler.contains(x, y)) {
                if (!historique.isEmpty()) {
                    int avant = nt;
                    restaurer(historique.remove(historique.size() - 1));
                    if (nt == avant && coups > 0) coups--;
                    choisi = -1;
                    invalidate();
                }
                return true;
            }
            if (bRecommencer.contains(x, y)) {
                niveauIdentique();
                return true;
            }
            if (bTube.contains(x, y)) {
                if (!tubeAjoute && nt < 14) {
                    historique.add(instantane());
                    tubeAjoute = true;
                    int[][] t2 = new int[nt + 1][CAP];
                    int[] n2 = new int[nt + 1];
                    for (int i = 0; i < nt; i++) {
                        t2[i] = tube[i];
                        n2[i] = nb[i];
                    }
                    tube = t2;
                    nb = n2;
                    nt++;
                    choisi = -1;
                    calculerDispo();
                    invalidate();
                }
                return true;
            }
            for (int i = 0; i < zones.size(); i++) {
                RectF z = zones.get(i);
                if (x >= z.left - 8f * d && x <= z.right + 8f * d && y >= z.top - 24f * d && y <= z.bottom + 8f * d) {
                    toucherTube(i);
                    break;
                }
            }
            return true;
        }

        private void toucherTube(int i) {
            if (choisi < 0) {
                if (nb[i] > 0) choisi = i;
            } else if (choisi == i) {
                choisi = -1;
            } else if (peutVerser(choisi, i)) {
                verser(choisi, i);
                choisi = -1;
            } else {
                erreur(choisi, i);
                choisi = -1;
            }
            invalidate();
        }

        /** Recommence le même niveau, avec les mêmes liquides qu'au départ. */
        private void niveauIdentique() {
            historique.clear();
            restaurer(depart);
            tubeAjoute = false;
            coups = 0;
            choisi = -1;
            gagne = false;
            invalidate();
        }

        // ------------------------------------------------------------------ dessin

        @Override
        protected void onDraw(Canvas c) {
            p.setShader(fond);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(0, 0, larg, haut, p);
            p.setShader(null);

            texte(c, "Niveau " + niveau, larg / 2f, 56f * d, 28f, 0xFFFFEB3B, Paint.Align.CENTER);
            texte(c, "Coups : " + coups + "     🏆 Record : niveau " + record, larg / 2f, 86f * d, 14f, 0xFFFFFFFF, Paint.Align.CENTER);

            long maintenant = SystemClock.uptimeMillis();
            float t = 0f;
            if (animA >= 0) {
                t = (maintenant - animDebut) / (float) ANIM_MS;
                if (t >= 1f) { terminerAnim(); t = 0f; }
            }
            for (int i = 0; i < nt && i < zones.size(); i++) if (i != animA) dessinerTube(c, i, t, maintenant);
            if (animA >= 0 && animA < zones.size() && animB < zones.size()) dessinerVersement(c, t, maintenant);

            bouton(c, bAnnuler, "↶ Annuler", !historique.isEmpty());
            bouton(c, bRecommencer, "⟳ Recommencer", true);
            bouton(c, bTube, "＋ Tube", !tubeAjoute && nt < 14);

            if (maintenant < msgFin && !gagne) {
                int alpha = (int) (255 * Math.min(1f, (msgFin - maintenant) / 400f));
                texte(c, msgErreur, larg / 2f, 106f * d, 14f, (alpha << 24) | 0xFF8A80, Paint.Align.CENTER);
            }

            if (animA >= 0 || maintenant - errDebut < ERR_MS || maintenant < msgFin) postInvalidateOnAnimation();

            if (gagne) {
                p.setColor(0xAA000000);
                c.drawRect(0, 0, larg, haut, p);
                texte(c, "BRAVO !", larg / 2f, haut / 2f - 20f * d, 40f, 0xFFFFEB3B, Paint.Align.CENTER);
                texte(c, "Niveau terminé en " + coups + " coups", larg / 2f, haut / 2f + 16f * d, 17f, 0xFFFFFFFF, Paint.Align.CENTER);
                texte(c, "Touchez l'écran pour le niveau suivant", larg / 2f, haut / 2f + 50f * d, 15f, 0xFFFFE082, Paint.Align.CENTER);
            }
        }

        private static float borne(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

        /** Avancement du versement proprement dit (0 = rien versé, 1 = tout versé). */
        private static float avancementVersement(float t) { return borne((t - 0.2f) / 0.65f); }

        private void dessinerTube(Canvas c, int i, float t, long maintenant) {
            RectF z = zones.get(i);
            float haussement = i == choisi ? 18f * d : 0f;
            float top = z.top - haussement, bas = z.bottom - haussement;
            float rayon = tubeW / 2f;

            // tremblement et contour rouge si le dernier coup était impossible
            float dx = 0f;
            boolean erreurEnCours = (i == errA || i == errB) && maintenant - errDebut < ERR_MS;
            if (erreurEnCours) {
                long ecoule = maintenant - errDebut;
                dx = (float) Math.sin(ecoule * 0.06) * 7f * d * (1f - ecoule / (float) ERR_MS);
            }
            c.save();
            c.translate(dx, 0f);

            float pv = avancementVersement(t);
            int total = nb[i] + (i == animA ? animN : 0);
            float acc = 0f;                                   // hauteur déjà remplie, du bas vers le haut
            // liquide, du bas vers le haut (les couches en cours de versement sont partielles)
            for (int j = 0; j < total; j++) {
                int col;
                float frac = 1f;
                if (j < nb[i]) {
                    col = tube[i][j];
                    if (i == animB && j >= nb[i] - animN) frac = pv;          // le tube cible se remplit
                } else {
                    col = animCol;
                    frac = 1f - pv;                                           // le tube source se vide
                }
                if (frac <= 0f) continue;
                float yb = bas - 7f * d - acc;
                float yh = yb - couche * frac;
                acc += couche * frac;
                p.setStyle(Paint.Style.FILL);
                p.setColor(COULEURS[col]);
                if (j == 0) {
                    r.set(z.left + 4f * d, yh, z.right - 4f * d, bas - 3f * d);
                    c.drawRoundRect(r, rayon * 0.8f, rayon * 0.8f, p);
                    c.drawRect(z.left + 4f * d, yh, z.right - 4f * d, yh + (yb - yh) / 2f, p);
                } else {
                    c.drawRect(z.left + 4f * d, yh, z.right - 4f * d, yb + 1f, p);
                }
                p.setColor(0x33FFFFFF);                                  // reflet
                c.drawRect(z.left + 8f * d, yh + 2f, z.left + 12f * d, Math.max(yh + 2f, yb - 2f), p);
            }
            // verre
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f * d);
            p.setColor(erreurEnCours ? 0xFFFF5252 : (i == choisi ? 0xFFFFEB3B : 0xCCFFFFFF));
            r.set(z.left, top, z.right, bas);
            c.drawRoundRect(r, rayon, rayon, p);
            p.setStyle(Paint.Style.FILL);
            c.restore();
        }

        /** Le tube source se lève, se penche au-dessus du tube cible, le liquide coule, puis il revient à sa place. */
        private void dessinerVersement(Canvas c, float t, long maintenant) {
            RectF za = zones.get(animA), zb = zones.get(animB);
            float sens = zb.centerX() >= za.centerX() ? 1f : -1f;
            float angle = 38f;
            double rad = Math.toRadians(angle);
            float px = zb.centerX() - sens * (tubeW / 2f) * (float) Math.cos(rad);       // pivot du tube penché
            float py = zb.top - 16f * d - (tubeW / 2f) * (float) Math.sin(rad);
            float m = Math.min(borne(t / 0.2f), borne((1f - t) / 0.15f));
            m = m * m * (3f - 2f * m);                                                  // mouvement doux

            // filet de liquide entre le goulot et la surface du tube cible
            float pv = avancementVersement(t);
            if (t > 0.2f && t < 0.85f) {
                float rempli = (nb[animB] - animN) + animN * pv;
                float surface = zb.bottom - 7f * d - rempli * couche;
                float largeur = 5f * d * Math.min(1f, Math.min((t - 0.2f) / 0.06f, (0.85f - t) / 0.06f));
                float x = zb.centerX();
                p.setStyle(Paint.Style.FILL);
                p.setColor(COULEURS[animCol]);
                c.drawRect(x - largeur / 2f, zb.top - 16f * d, x + largeur / 2f, surface, p);
            }

            c.save();
            c.translate(m * (px - za.centerX()), m * (py - za.top));
            c.rotate(sens * angle * m, za.centerX(), za.top);
            dessinerTube(c, animA, t, maintenant);
            c.restore();
        }

        private void bouton(Canvas c, RectF b, String t, boolean actif) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(actif ? 0x66FFFFFF : 0x22FFFFFF);
            c.drawRoundRect(b, 16f * d, 16f * d, p);
            texte(c, t, b.centerX(), b.centerY() + 6f * d, 15f, actif ? 0xFFFFFFFF : 0x77FFFFFF, Paint.Align.CENTER);
        }

        private void texte(Canvas c, String t, float x, float y, float tailleDp, int couleur, Paint.Align al) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(couleur);
            p.setTextSize(tailleDp * d);
            p.setTextAlign(al);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(t, x, y, p);
        }
    }
}
