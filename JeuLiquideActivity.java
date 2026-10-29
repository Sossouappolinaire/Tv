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
            while (nb[a] > 0 && tube[a][nb[a] - 1] == couleur && nb[b] < CAP) {
                tube[b][nb[b]++] = couleur;
                nb[a]--;
            }
            coups++;
            if (resolu()) {
                gagne = true;
                if (niveau > record) {
                    record = niveau;
                    sauver();
                }
            }
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
                choisi = nb[i] > 0 ? i : -1;
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

            for (int i = 0; i < nt && i < zones.size(); i++) dessinerTube(c, i);

            bouton(c, bAnnuler, "↶ Annuler", !historique.isEmpty());
            bouton(c, bRecommencer, "⟳ Recommencer", true);
            bouton(c, bTube, "＋ Tube", !tubeAjoute && nt < 14);

            if (gagne) {
                p.setColor(0xAA000000);
                c.drawRect(0, 0, larg, haut, p);
                texte(c, "BRAVO !", larg / 2f, haut / 2f - 20f * d, 40f, 0xFFFFEB3B, Paint.Align.CENTER);
                texte(c, "Niveau terminé en " + coups + " coups", larg / 2f, haut / 2f + 16f * d, 17f, 0xFFFFFFFF, Paint.Align.CENTER);
                texte(c, "Touchez l'écran pour le niveau suivant", larg / 2f, haut / 2f + 50f * d, 15f, 0xFFFFE082, Paint.Align.CENTER);
            }
        }

        private void dessinerTube(Canvas c, int i) {
            RectF z = zones.get(i);
            float haussement = i == choisi ? 18f * d : 0f;
            float top = z.top - haussement, bas = z.bottom - haussement;
            float rayon = tubeW / 2f;
            // liquide, du bas vers le haut
            for (int j = 0; j < nb[i]; j++) {
                float yb = bas - 7f * d - j * couche;
                float yh = yb - couche;
                p.setStyle(Paint.Style.FILL);
                p.setColor(COULEURS[tube[i][j]]);
                if (j == 0) {
                    r.set(z.left + 4f * d, yh, z.right - 4f * d, bas - 3f * d);
                    c.drawRoundRect(r, rayon * 0.8f, rayon * 0.8f, p);
                    c.drawRect(z.left + 4f * d, yh, z.right - 4f * d, yh + couche / 2f, p);
                } else {
                    c.drawRect(z.left + 4f * d, yh, z.right - 4f * d, yb + 1f, p);
                }
                p.setColor(0x33FFFFFF);                                  // reflet
                c.drawRect(z.left + 8f * d, yh + 2f, z.left + 12f * d, yb - 2f, p);
            }
            // verre
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f * d);
            p.setColor(i == choisi ? 0xFFFFEB3B : 0xCCFFFFFF);
            r.set(z.left, top, z.right, bas);
            c.drawRoundRect(r, rayon, rayon, p);
            p.setStyle(Paint.Style.FILL);
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
