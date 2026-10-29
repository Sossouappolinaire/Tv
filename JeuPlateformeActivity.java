package bj.radio;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Random;

/**
 * Deux jeux de plateformes avec le même moteur :
 *  - « panda »  : Panda Dash (forêt de bambous, panda à l'écharpe rouge, scarabées rouges) ;
 *  - « mario »  : Super Kouamé (ciel, briques, tuyaux, champignons méchants).
 * Les niveaux sont fabriqués automatiquement et deviennent plus durs. Boutons gauche / droite / JUMP.
 */
public class JeuPlateformeActivity extends Activity {

    private VuePlateforme vue;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean panda = !"mario".equals(getIntent().getStringExtra("mode"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersif();
        vue = new VuePlateforme(this, panda);
        setContentView(vue);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersif();
    }

    private void immersif() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (vue != null) vue.pauser();
    }

    // =====================================================================================================

    private static final class VuePlateforme extends View {

        private static final int LIG = 10;            // hauteur du monde, en tuiles
        private static final float G = 38f;           // gravité (tuiles / s²)
        private static final float SAUT = 16f;        // vitesse du saut
        private static final float VMAX = 6.4f;       // vitesse de course
        private static final float LARG = 0.7f, HAUT = 0.85f;   // boîte du joueur
        private static final int ATTENTE = 0, JEU = 1, PAUSE = 2, FIN = 3, NIVEAU = 4;

        private final Context ctx;
        private final boolean panda;
        private final String cle;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path chemin = new Path();

        // monde
        private byte[][] grille;
        private int cols;
        private float flagX;
        private float vitEnn = 1.2f;
        private final ArrayList<float[]> pieces = new ArrayList<>();    // x, y, prise
        private final ArrayList<float[]> ennemis = new ArrayList<>();   // x, y(pieds), sens, vivant, temps écrasé

        // écran
        private int lw, lh;
        private float T, vuesT, camX;
        private Shader ciel;
        private float gcx, dcx, gy, gr, scx, sy, sr;

        // joueur
        private float px, py, vx, vy, invul, tempsSol, tamponSaut, repriseX, repriseY;
        private int face = 1;
        private boolean surSol, sautEnCours;
        private boolean gauche, droite, saut;

        // partie
        private int vies = 3, niveau = 1, score, nbPieces, record, etat;
        private float timer, temps;
        private long dernier, finTemps;

        VuePlateforme(Context c, boolean panda) {
            super(c);
            ctx = c;
            this.panda = panda;
            cle = panda ? "panda" : "mario";
            d = c.getResources().getDisplayMetrics().density;
            record = JeuxActivity.record(c, cle);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            generer();
            placer();
            etat = ATTENTE;
        }

        void pauser() {
            if (etat == JEU) etat = PAUSE;
            gauche = false; droite = false; saut = false;
            invalidate();
        }

        // ---------------------------------------------------------------- fabrication du niveau

        private void remplir(int a, int b, int h) {
            for (int col = a; col < b; col++) {
                grille[h][col] = 1;
                for (int row = h + 1; row < LIG; row++) grille[row][col] = 2;
            }
        }

        private void ajouterPiece(float x, float y) {
            pieces.add(new float[]{x, y, 0f});
        }

        private void generer() {
            Random rd = new Random(niveau * 7919L + (panda ? 11 : 29));
            int longueur = Math.min(240, 80 + niveau * 18);
            cols = longueur + 14;
            grille = new byte[LIG][cols];
            pieces.clear();
            ennemis.clear();
            vitEnn = Math.min(3.2f, 1.2f + 0.2f * niveau);
            int h = 8, c = 0;
            while (c < longueur) {
                int seg = (c == 0) ? 9 : 3 + rd.nextInt(6);
                if (c + seg > longueur) seg = longueur - c;
                remplir(c, c + seg, h);
                if (c > 0 && seg >= 4) {
                    int t = rd.nextInt(10);
                    if (t <= 2) {                                   // plateforme avec des pièces dessus
                        int pl = 2 + rd.nextInt(Math.min(3, seg - 2));
                        int pc = c + 1 + rd.nextInt(seg - pl);
                        for (int k = 0; k < pl; k++) {
                            grille[h - 3][pc + k] = 3;
                            ajouterPiece(pc + k + 0.5f, h - 3.5f);
                        }
                    } else if (t <= 4 && seg >= 5) {                // tuyau / rocher à sauter
                        int ho = 1 + rd.nextInt(2);
                        for (int k = 1; k <= ho; k++) grille[h - k][c + 2] = 4;
                        ajouterPiece(c + 2.5f, h - ho - 1.6f);
                    } else if (t <= 6 + Math.min(2, niveau / 2)) {  // ennemi
                        ennemis.add(new float[]{c + seg / 2f + 0.5f, h, rd.nextBoolean() ? 1f : -1f, 1f, 0f});
                    } else {                                        // ligne de pièces
                        for (int k = 1; k < seg - 1; k++) ajouterPiece(c + k + 0.5f, h - 0.7f);
                    }
                }
                c += seg;
                if (c < longueur) {
                    if (rd.nextFloat() < 0.45f + Math.min(0.3f, niveau * 0.04f)) {   // trou de 2 ou 3 cases
                        int gap = 2 + rd.nextInt(2);
                        for (int k = 0; k < gap; k++) ajouterPiece(c + k + 0.5f, h - 2.2f);
                        c += gap;
                    }
                    h = Math.max(6, Math.min(8, h + rd.nextInt(3) - 1));
                }
            }
            remplir(c, cols, h);
            flagX = c + 5f;
        }

        private void placer() {
            px = 2.5f;
            py = 8f;
            vx = 0;
            vy = 0;
            invul = 0;
            camX = 0;
            repriseX = px;
            repriseY = py;
        }

        private void nouvellePartie() {
            vies = 3;
            niveau = 1;
            score = 0;
            nbPieces = 0;
            record = JeuxActivity.record(ctx, cle);
            generer();
            placer();
            etat = JEU;
        }

        private void prochainNiveau() {
            niveau++;
            generer();
            placer();
            etat = JEU;
        }

        private void fin() {
            etat = FIN;
            finTemps = SystemClock.uptimeMillis();
            JeuxActivity.enregistrer(ctx, cle, score);
            record = Math.max(record, score);
        }

        // ---------------------------------------------------------------- physique

        private boolean solide(int c, int rr) {
            if (c < 0 || c >= cols) return true;
            if (rr < 0 || rr >= LIG) return false;
            return grille[rr][c] != 0;
        }

        private void resoudreX() {
            int r0 = (int) Math.floor(py - HAUT + 0.02f);
            int r1 = (int) Math.floor(py - 0.02f);
            if (vx > 0) {
                int c = (int) Math.floor(px + LARG / 2);
                for (int rr = r0; rr <= r1; rr++) {
                    if (solide(c, rr)) { px = c - LARG / 2 - 0.001f; vx = 0; break; }
                }
            } else if (vx < 0) {
                int c = (int) Math.floor(px - LARG / 2);
                for (int rr = r0; rr <= r1; rr++) {
                    if (solide(c, rr)) { px = c + 1 + LARG / 2 + 0.001f; vx = 0; break; }
                }
            }
        }

        private void resoudreY() {
            int c0 = (int) Math.floor(px - LARG / 2 + 0.01f);
            int c1 = (int) Math.floor(px + LARG / 2 - 0.01f);
            if (vy >= 0) {
                int rr = (int) Math.floor(py);
                for (int c = c0; c <= c1; c++) {
                    if (solide(c, rr)) { py = rr; vy = 0; surSol = true; break; }
                }
            } else {
                int rr = (int) Math.floor(py - HAUT);
                for (int c = c0; c <= c1; c++) {
                    if (solide(c, rr)) { py = rr + 1 + HAUT + 0.001f; vy = 0; break; }
                }
            }
        }

        private void perdreUneVie(boolean chute) {
            vies--;
            if (vies <= 0) { fin(); return; }
            if (chute) {
                px = repriseX;
                py = repriseY;
                vx = 0;
                vy = 0;
                invul = 2f;
            }
        }

        private void maj(float dt) {
            float dir = (droite ? 1f : 0f) - (gauche ? 1f : 0f);
            float cible = dir * VMAX;
            float acc = dir == 0 ? (surSol ? 40f : 8f) * dt : (surSol ? 45f : 30f) * dt;
            if (vx < cible) vx = Math.min(cible, vx + acc);
            else if (vx > cible) vx = Math.max(cible, vx - acc);
            if (dir != 0) face = dir > 0 ? 1 : -1;

            if (surSol) tempsSol = 0.1f; else tempsSol -= dt;
            if (tamponSaut > 0) tamponSaut -= dt;
            if (tamponSaut > 0 && tempsSol > 0) {
                vy = -SAUT;
                tempsSol = 0;
                tamponSaut = 0;
                surSol = false;
                sautEnCours = true;
            }
            if (sautEnCours && !saut && vy < -6f) { vy = -6f; sautEnCours = false; }
            if (vy >= 0) sautEnCours = false;

            vy = Math.min(22f, vy + G * dt);
            if (invul > 0) invul -= dt;

            px += vx * dt;
            resoudreX();
            py += vy * dt;
            surSol = false;
            resoudreY();

            // dernier endroit sûr (sol des deux côtés) pour revenir après une chute
            if (surSol && solide((int) Math.floor(px - 1f), (int) Math.floor(py))
                    && solide((int) Math.floor(px + 1f), (int) Math.floor(py))) {
                repriseX = px;
                repriseY = py;
            }

            if (py > LIG + 1.5f) { perdreUneVie(true); if (etat != JEU) return; }

            // pièces
            for (float[] pc : pieces) {
                if (pc[2] > 0.5f) continue;
                if (Math.abs(pc[0] - px) < 0.55f && Math.abs(pc[1] - (py - HAUT / 2)) < 0.65f) {
                    pc[2] = 1f;
                    score += 10;
                    nbPieces++;
                    if (nbPieces % 20 == 0 && vies < 5) vies++;
                }
            }

            // ennemis
            for (float[] e : ennemis) {
                if (e[3] < 0.5f) { e[4] -= dt; continue; }
                float nx = e[0] + e[2] * vitEnn * dt;
                int sens = e[2] > 0 ? 1 : -1;
                boolean mur = solide((int) Math.floor(nx + sens * 0.4f), (int) Math.floor(e[1] - 0.3f));
                boolean vide = !solide((int) Math.floor(nx + sens * 0.45f), (int) Math.floor(e[1] + 0.1f));
                if (mur || vide) e[2] = -e[2]; else e[0] = nx;

                if (Math.abs(px - e[0]) < (LARG + 0.75f) / 2 && py > e[1] - 0.7f && py - HAUT < e[1]) {
                    if (vy > 0.5f && py < e[1] - 0.25f) {
                        e[3] = 0f;
                        e[4] = 0.5f;
                        vy = -11f;
                        sautEnCours = false;
                        score += 100;
                    } else if (invul <= 0) {
                        invul = 1.8f;
                        vy = -9f;
                        vx = (px < e[0] ? -1f : 1f) * 5f;
                        perdreUneVie(false);
                        if (etat != JEU) return;
                    }
                }
            }

            // drapeau
            if (px > flagX) {
                score += 500;
                JeuxActivity.enregistrer(ctx, cle, score);
                record = Math.max(record, score);
                etat = NIVEAU;
                timer = 2.2f;
            }

            // caméra
            float cam = px - vuesT * 0.38f;
            camX += (cam - camX) * Math.min(1f, dt * 8f);
            camX = Math.max(0f, Math.min(camX, Math.max(0f, cols - vuesT)));
        }

        // ---------------------------------------------------------------- tactile

        private boolean dans(float x, float y, float cx, float cy, float rad) {
            float a = x - cx, b = y - cy;
            return a * a + b * b <= rad * rad;
        }

        private void toucher(float x, float y) {
            if (etat == JEU) {
                if (dans(x, y, lw - 0.8f * T, 0.8f * T, 0.9f * T)) etat = PAUSE;
            } else if (etat == ATTENTE) {
                etat = JEU;
            } else if (etat == PAUSE) {
                etat = JEU;
            } else if (etat == FIN && SystemClock.uptimeMillis() - finTemps > 600) {
                nouvellePartie();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                int i = e.getActionIndex();
                toucher(e.getX(i), e.getY(i));
            }
            boolean g = false, dr = false, s = false;
            if (a != MotionEvent.ACTION_UP && a != MotionEvent.ACTION_CANCEL) {
                int leve = a == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (i == leve) continue;
                    float x = e.getX(i), y = e.getY(i);
                    if (dans(x, y, gcx, gy, gr * 1.25f)) g = true;
                    else if (dans(x, y, dcx, gy, gr * 1.25f)) dr = true;
                    else if (dans(x, y, scx, sy, sr * 1.4f)) s = true;
                }
            }
            gauche = g;
            droite = dr;
            if (s && !saut) tamponSaut = 0.14f;
            saut = s;
            invalidate();
            return true;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            lw = w;
            lh = h;
            T = h / (float) LIG;
            vuesT = w / T;
            ciel = panda
                    ? new LinearGradient(0, 0, 0, h, 0xFF8EC9F0, 0xFFDDF0E0, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, h, 0xFF5C94FC, 0xFFBFE4FF, Shader.TileMode.CLAMP);
            gr = 1.0f * T;
            gcx = 1.5f * T;
            dcx = 4.0f * T;
            gy = h - 1.5f * T;
            sr = 1.3f * T;
            scx = w - 2.1f * T;
            sy = h - 1.8f * T;
        }

        // ---------------------------------------------------------------- dessin

        private static float boucle(float v, float per) {
            v %= per;
            if (v < 0) v += per;
            return v;
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.033f, (now - dernier) / 1000f);
            dernier = now;
            temps += dt;
            if (etat == JEU) maj(dt);
            else if (etat == NIVEAU) {
                timer -= dt;
                if (timer <= 0) prochainNiveau();
            }

            fond(c);
            tuiles(c);
            objets(c);
            joueur(c);
            hud(c);
            commandes(c);
            messages(c);

            if (etat == ATTENTE || etat == JEU || etat == NIVEAU) postInvalidateOnAnimation();
        }

        private void fond(Canvas c) {
            p.setShader(ciel);
            r.set(0, 0, lw, lh);
            c.drawRect(r, p);
            p.setShader(null);
            if (panda) {
                // montagnes dans la brume
                p.setColor(0x55A9CFC0);
                float sp = 6f * T;
                int n = (int) (lw / sp) + 3;
                for (int i = 0; i < n; i++) {
                    float x = boucle(i * sp - camX * T * 0.08f, n * sp) - sp;
                    r.set(x, lh * 0.35f + (i % 3) * 0.4f * T, x + sp * 1.5f, lh * 1.2f);
                    c.drawOval(r, p);
                }
                // bambous : deux plans
                bambous(c, 0.25f, 1.7f * T, 0.30f * T, 0xFF6FAA5A, 0xFF4F8A3C);
                bambous(c, 0.5f, 2.7f * T, 0.46f * T, 0xFF3F7F2F, 0xFF2D6322);
            } else {
                // nuages
                p.setColor(0xDDFFFFFF);
                float sp = 6f * T;
                int n = (int) (lw / sp) + 3;
                for (int i = 0; i < n; i++) {
                    float x = boucle(i * sp - camX * T * 0.15f, n * sp) - sp;
                    float y = (1.0f + (i * 7 % 3) * 0.9f) * T;
                    r.set(x, y, x + 2.2f * T, y + 0.8f * T);
                    c.drawOval(r, p);
                    r.set(x + 0.5f * T, y - 0.35f * T, x + 1.6f * T, y + 0.6f * T);
                    c.drawOval(r, p);
                }
                // collines
                float sp2 = 8f * T;
                int n2 = (int) (lw / sp2) + 3;
                for (int i = 0; i < n2; i++) {
                    float x = boucle(i * sp2 - camX * T * 0.3f, n2 * sp2) - sp2;
                    p.setColor(0xFF4CAF50);
                    r.set(x, 6.4f * T, x + 5f * T, 10.5f * T);
                    c.drawOval(r, p);
                    p.setColor(0xFF66BB6A);
                    r.set(x + 2.6f * T, 7.1f * T, x + 6.4f * T, 10.5f * T);
                    c.drawOval(r, p);
                }
            }
        }

        private void bambous(Canvas c, float facteur, float sp, float larg, int clair, int fonce) {
            int n = (int) (lw / sp) + 3;
            for (int i = 0; i < n; i++) {
                float x = boucle(i * sp - camX * T * facteur, n * sp) - sp;
                p.setColor(clair);
                r.set(x - larg / 2, 0, x + larg / 2, lh);
                c.drawRect(r, p);
                p.setColor(fonce);
                for (float y = (i % 4) * 0.3f * T; y < lh; y += 1.4f * T) {
                    r.set(x - larg * 0.6f, y, x + larg * 0.6f, y + 0.09f * T);
                    c.drawRect(r, p);
                }
                // feuilles
                for (int k = 0; k < 3; k++) {
                    float ly = (1.2f + k * 1.5f + (i * 37 % 5) * 0.2f) * T;
                    c.save();
                    c.translate(x, ly);
                    c.rotate(k % 2 == 0 ? -25 : 25);
                    r.set(0, -0.12f * T, 1.1f * T, 0.12f * T);
                    c.drawOval(r, p);
                    c.restore();
                }
            }
        }

        private void tuiles(Canvas c) {
            int c0 = Math.max(0, (int) Math.floor(camX) - 1);
            int c1 = Math.min(cols - 1, (int) Math.ceil(camX + vuesT) + 1);
            for (int col = c0; col <= c1; col++) {
                for (int row = 0; row < LIG; row++) {
                    int t = grille[row][col];
                    if (t == 0) continue;
                    float x = (col - camX) * T, y = row * T;
                    r.set(x, y, x + T + 1f, y + T + 1f);
                    switch (t) {
                        case 1:
                            p.setColor(panda ? 0xFF8B5A2B : 0xFFC8742C);
                            c.drawRect(r, p);
                            p.setColor(panda ? 0xFF4CAF2E : 0xFF58C23A);
                            r.set(x, y, x + T + 1f, y + 0.28f * T);
                            c.drawRect(r, p);
                            p.setColor(panda ? 0xFF7ED957 : 0xFF8BE05A);
                            r.set(x, y, x + T + 1f, y + 0.08f * T);
                            c.drawRect(r, p);
                            break;
                        case 2:
                            p.setColor(panda ? 0xFF8B5A2B : 0xFFC8742C);
                            c.drawRect(r, p);
                            p.setColor(panda ? 0xFF6F4620 : 0xFF8A4A14);
                            if (panda) {
                                c.drawCircle(x + 0.3f * T, y + 0.35f * T, 0.07f * T, p);
                                c.drawCircle(x + 0.7f * T, y + 0.7f * T, 0.09f * T, p);
                            } else {
                                r.set(x, y + 0.48f * T, x + T + 1f, y + 0.52f * T);
                                c.drawRect(r, p);
                                r.set(x + (((col + row) & 1) == 0 ? 0.5f : 0.2f) * T, y, x + (((col + row) & 1) == 0 ? 0.5f : 0.2f) * T + 0.04f * T, y + 0.5f * T);
                                c.drawRect(r, p);
                            }
                            break;
                        case 3:
                            if (panda) {
                                p.setColor(0xFFD9B95C);
                                c.drawRect(r, p);
                                p.setColor(0xFF9C7A2E);
                                r.set(x, y + 0.45f * T, x + T + 1f, y + 0.5f * T);
                                c.drawRect(r, p);
                                r.set(x + 0.02f * T, y, x + 0.1f * T, y + T);
                                c.drawRect(r, p);
                            } else {
                                p.setColor(0xFFE0782C);
                                c.drawRect(r, p);
                                p.setColor(0xFF6B2E0E);
                                r.set(x, y + 0.48f * T, x + T + 1f, y + 0.53f * T);
                                c.drawRect(r, p);
                                r.set(x, y, x + T + 1f, y + 0.05f * T);
                                c.drawRect(r, p);
                                r.set(x + 0.48f * T, y, x + 0.53f * T, y + 0.48f * T);
                                c.drawRect(r, p);
                                r.set(x + 0.2f * T, y + 0.53f * T, x + 0.25f * T, y + T);
                                c.drawRect(r, p);
                            }
                            break;
                        default:   // 4 : rocher (panda) ou tuyau (mario)
                            boolean sommet = row == 0 || grille[row - 1][col] != 4;
                            if (panda) {
                                p.setColor(0xFF8E9AA5);
                                r.set(x + 0.04f * T, y + (sommet ? 0.1f * T : 0f), x + 0.96f * T, y + T + 1f);
                                c.drawRoundRect(r, 0.25f * T, 0.25f * T, p);
                                p.setColor(0xFFB0BCC6);
                                r.set(x + 0.2f * T, y + 0.2f * T, x + 0.5f * T, y + 0.4f * T);
                                c.drawOval(r, p);
                            } else {
                                p.setColor(0xFF2FB34A);
                                r.set(x + 0.08f * T, y, x + 0.92f * T, y + T + 1f);
                                c.drawRect(r, p);
                                p.setColor(0xFF7BE08E);
                                r.set(x + 0.18f * T, y, x + 0.3f * T, y + T + 1f);
                                c.drawRect(r, p);
                                if (sommet) {
                                    p.setColor(0xFF248F3B);
                                    r.set(x - 0.04f * T, y, x + 1.04f * T, y + 0.38f * T);
                                    c.drawRect(r, p);
                                    p.setColor(0xFF7BE08E);
                                    r.set(x + 0.06f * T, y + 0.04f * T, x + 0.2f * T, y + 0.34f * T);
                                    c.drawRect(r, p);
                                }
                            }
                            break;
                    }
                }
            }
        }

        private void objets(Canvas c) {
            // pièces
            for (float[] pc : pieces) {
                if (pc[2] > 0.5f) continue;
                float x = (pc[0] - camX) * T, y = pc[1] * T;
                if (x < -T || x > lw + T) continue;
                float wf = Math.max(0.18f, Math.abs((float) Math.cos(temps * 5f + pc[0])));
                float ra = 0.26f * T;
                p.setColor(0xFFFFB300);
                r.set(x - ra * wf, y - ra, x + ra * wf, y + ra);
                c.drawOval(r, p);
                p.setColor(0xFFFFE082);
                r.set(x - ra * wf * 0.55f, y - ra * 0.6f, x + ra * wf * 0.55f, y + ra * 0.6f);
                c.drawOval(r, p);
            }

            // drapeau
            float fx = (flagX - camX) * T;
            if (fx > -2 * T && fx < lw + 2 * T) {
                float sol = 8f * T;
                for (int row = 0; row < LIG; row++) {
                    int col = (int) Math.floor(flagX);
                    if (col < cols && grille[row][col] != 0) { sol = row * T; break; }
                }
                p.setColor(0xFFECEFF1);
                r.set(fx - 0.05f * T, sol - 3.6f * T, fx + 0.05f * T, sol);
                c.drawRect(r, p);
                p.setColor(0xFFFFC107);
                c.drawCircle(fx, sol - 3.6f * T, 0.14f * T, p);
                p.setColor(panda ? 0xFFE53935 : 0xFF43A047);
                chemin.reset();
                float on = (float) Math.sin(temps * 6f) * 0.08f * T;
                chemin.moveTo(fx + 0.05f * T, sol - 3.5f * T);
                chemin.lineTo(fx + 1.3f * T, sol - 3.1f * T + on);
                chemin.lineTo(fx + 0.05f * T, sol - 2.6f * T);
                chemin.close();
                c.drawPath(chemin, p);
            }

            // ennemis
            for (float[] e : ennemis) {
                if (e[3] < 0.5f && e[4] <= 0) continue;
                dessinerEnnemi(c, e);
            }
        }

        private void dessinerEnnemi(Canvas c, float[] e) {
            float x = (e[0] - camX) * T, y = e[1] * T;
            if (x < -T || x > lw + T) return;
            boolean vivant = e[3] > 0.5f;
            float bal = (float) Math.sin(temps * 10f + e[0]);
            c.save();
            c.translate(x, y);
            c.scale(T * (e[2] > 0 ? 1f : -1f), vivant ? T : T * 0.35f);
            if (panda) {
                p.setColor(0xFF3E2723);
                for (int i = 0; i < 3; i++) {
                    r.set(-0.34f + i * 0.24f + bal * 0.04f, -0.14f, -0.27f + i * 0.24f + bal * 0.04f, 0f);
                    c.drawRect(r, p);
                }
                p.setColor(0xFFC62828);
                r.set(-0.44f, -0.64f, 0.34f, -0.08f);
                c.drawOval(r, p);
                p.setColor(0xFF7F1717);
                c.drawCircle(-0.14f, -0.42f, 0.07f, p);
                c.drawCircle(0.08f, -0.3f, 0.06f, p);
                c.drawCircle(-0.02f, -0.52f, 0.05f, p);
                p.setColor(0xFF3E2723);
                c.drawCircle(0.36f, -0.32f, 0.17f, p);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(0.42f, -0.38f, 0.055f, p);
                p.setColor(0xFF000000);
                c.drawCircle(0.44f, -0.38f, 0.025f, p);
            } else {
                p.setColor(0xFF111111);
                r.set(-0.36f + bal * 0.05f, -0.14f, -0.04f + bal * 0.05f, 0f);
                c.drawOval(r, p);
                r.set(0.04f - bal * 0.05f, -0.14f, 0.36f - bal * 0.05f, 0f);
                c.drawOval(r, p);
                p.setColor(0xFFF5D7A8);
                r.set(-0.2f, -0.34f, 0.2f, -0.1f);
                c.drawRoundRect(r, 0.08f, 0.08f, p);
                p.setColor(0xFF8D4B1F);
                r.set(-0.44f, -0.74f, 0.44f, -0.22f);
                c.drawOval(r, p);
                p.setColor(0xFFFFFFFF);
                r.set(-0.24f, -0.52f, -0.04f, -0.32f);
                c.drawOval(r, p);
                r.set(0.04f, -0.52f, 0.24f, -0.32f);
                c.drawOval(r, p);
                p.setColor(0xFF000000);
                c.drawCircle(-0.09f, -0.4f, 0.04f, p);
                c.drawCircle(0.09f, -0.4f, 0.04f, p);
                r.set(-0.28f, -0.58f, -0.02f, -0.54f);
                c.drawRect(r, p);
                r.set(0.02f, -0.58f, 0.28f, -0.54f);
                c.drawRect(r, p);
            }
            c.restore();
        }

        private void joueur(Canvas c) {
            if (invul > 0 && ((int) (temps * 14f)) % 2 == 0) return;   // clignote quand on est touché
            float x = (px - camX) * T, y = py * T;
            float marche = surSol ? (float) Math.sin(temps * 16f) * Math.min(1f, Math.abs(vx) / 3f) : 0.6f;
            c.save();
            c.translate(x, y);
            c.scale(face * T * 0.88f, T * 0.88f);
            if (panda) dessinerPanda(c, marche); else dessinerHeros(c, marche);
            c.restore();
        }

        private void dessinerPanda(Canvas c, float bal) {
            float fl = (float) Math.sin(temps * 12f) * 0.06f;
            // écharpe qui flotte derrière
            p.setColor(0xFFD32F2F);
            chemin.reset();
            chemin.moveTo(-0.15f, -0.52f);
            chemin.lineTo(-0.72f, -0.58f + fl);
            chemin.lineTo(-0.62f, -0.4f - fl);
            chemin.lineTo(-0.15f, -0.4f);
            chemin.close();
            c.drawPath(chemin, p);
            // jambes
            p.setColor(0xFF222222);
            r.set(-0.30f + bal * 0.12f, -0.22f, -0.08f + bal * 0.12f, 0f);
            c.drawRoundRect(r, 0.08f, 0.08f, p);
            r.set(0.08f - bal * 0.12f, -0.22f, 0.30f - bal * 0.12f, 0f);
            c.drawRoundRect(r, 0.08f, 0.08f, p);
            // corps
            p.setColor(0xFFFFFFFF);
            r.set(-0.36f, -0.64f, 0.36f, -0.12f);
            c.drawOval(r, p);
            // bras
            p.setColor(0xFF222222);
            r.set(0.14f, -0.56f - bal * 0.04f, 0.42f, -0.28f - bal * 0.04f);
            c.drawOval(r, p);
            r.set(-0.40f, -0.56f + bal * 0.04f, -0.12f, -0.28f + bal * 0.04f);
            c.drawOval(r, p);
            // écharpe autour du cou
            p.setColor(0xFFD32F2F);
            r.set(-0.30f, -0.52f, 0.30f, -0.40f);
            c.drawRoundRect(r, 0.06f, 0.06f, p);
            // tête
            p.setColor(0xFFFFFFFF);
            r.set(-0.34f, -0.94f, 0.34f, -0.42f);
            c.drawOval(r, p);
            p.setColor(0xFF222222);
            c.drawCircle(-0.25f, -0.88f, 0.12f, p);
            c.drawCircle(0.25f, -0.88f, 0.12f, p);
            r.set(-0.24f, -0.78f, -0.05f, -0.56f);
            c.drawOval(r, p);
            r.set(0.05f, -0.78f, 0.24f, -0.56f);
            c.drawOval(r, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(-0.14f, -0.68f, 0.045f, p);
            c.drawCircle(0.14f, -0.68f, 0.045f, p);
            p.setColor(0xFF222222);
            r.set(-0.07f, -0.6f, 0.07f, -0.52f);
            c.drawOval(r, p);
        }

        private void dessinerHeros(Canvas c, float bal) {
            // chaussures
            p.setColor(0xFF5D3A1A);
            r.set(-0.34f + bal * 0.1f, -0.16f, -0.02f + bal * 0.1f, 0f);
            c.drawOval(r, p);
            r.set(0.02f - bal * 0.1f, -0.16f, 0.34f - bal * 0.1f, 0f);
            c.drawOval(r, p);
            // salopette
            p.setColor(0xFF1E5FD8);
            r.set(-0.24f, -0.50f, 0.24f, -0.12f);
            c.drawRoundRect(r, 0.06f, 0.06f, p);
            // chemise rouge
            p.setColor(0xFFD32F2F);
            r.set(-0.26f, -0.62f, 0.26f, -0.44f);
            c.drawRoundRect(r, 0.06f, 0.06f, p);
            // bras et gants
            r.set(0.16f, -0.60f - bal * 0.04f, 0.38f, -0.36f - bal * 0.04f);
            c.drawOval(r, p);
            r.set(-0.38f, -0.60f + bal * 0.04f, -0.16f, -0.36f + bal * 0.04f);
            c.drawOval(r, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(0.32f, -0.36f - bal * 0.04f, 0.08f, p);
            c.drawCircle(-0.32f, -0.36f + bal * 0.04f, 0.08f, p);
            // tête
            p.setColor(0xFFF5C9A0);
            c.drawCircle(0f, -0.74f, 0.25f, p);
            // casquette
            p.setColor(0xFFD32F2F);
            r.set(-0.27f, -1.0f, 0.27f, -0.48f);
            c.drawArc(r, 180f, 180f, true, p);
            r.set(0.02f, -0.78f, 0.42f, -0.70f);
            c.drawRoundRect(r, 0.04f, 0.04f, p);
            // visage
            p.setColor(0xFF000000);
            c.drawCircle(0.11f, -0.70f, 0.035f, p);
            r.set(0.0f, -0.62f, 0.22f, -0.56f);
            c.drawOval(r, p);
            p.setColor(0xFFE0A070);
            c.drawCircle(0.22f, -0.66f, 0.05f, p);
        }

        private void coeur(Canvas c, float cx, float cy, float s, int couleur) {
            chemin.reset();
            chemin.moveTo(cx, cy + s * 0.9f);
            chemin.cubicTo(cx - s * 1.6f, cy - s * 0.1f, cx - s * 0.7f, cy - s * 1.2f, cx, cy - s * 0.4f);
            chemin.cubicTo(cx + s * 0.7f, cy - s * 1.2f, cx + s * 1.6f, cy - s * 0.1f, cx, cy + s * 0.9f);
            chemin.close();
            p.setColor(couleur);
            c.drawPath(chemin, p);
        }

        private void texte(Canvas c, String s, float x, float y, float taille, int couleur, Paint.Align al) {
            tp.setTextAlign(al);
            tp.setTextSize(taille);
            tp.setShadowLayer(3f * d, 0, 1.5f * d, 0xAA000000);
            tp.setColor(couleur);
            c.drawText(s, x, y, tp);
            tp.clearShadowLayer();
        }

        private void hud(Canvas c) {
            p.setColor(0x55000000);
            r.set(0, 0, lw, 1.5f * T);
            c.drawRect(r, p);
            int total = Math.max(3, vies);
            for (int i = 0; i < total; i++) {
                coeur(c, 0.6f * T + i * 0.78f * T, 0.75f * T, 0.22f * T, i < vies ? 0xFFFF4D6D : 0x55FFFFFF);
            }
            float x0 = 0.6f * T + total * 0.78f * T + 0.4f * T;
            texte(c, "Lv " + niveau, x0, 0.98f * T, 0.55f * T, 0xFFFFFFFF, Paint.Align.LEFT);
            texte(c, String.valueOf(score), lw * 0.5f, 0.98f * T, 0.62f * T, 0xFFFFF59D, Paint.Align.CENTER);
            // pièces
            float cx = lw * 0.68f;
            p.setColor(0xFFFFB300);
            c.drawCircle(cx, 0.75f * T, 0.26f * T, p);
            p.setColor(0xFFFFE082);
            c.drawCircle(cx, 0.75f * T, 0.15f * T, p);
            texte(c, "×" + nbPieces, cx + 0.45f * T, 0.98f * T, 0.55f * T, 0xFFFFFFFF, Paint.Align.LEFT);
            // pause
            p.setColor(0x66000000);
            c.drawCircle(lw - 0.8f * T, 0.8f * T, 0.5f * T, p);
            p.setColor(0xFFFFFFFF);
            r.set(lw - 0.8f * T - 0.2f * T, 0.8f * T - 0.2f * T, lw - 0.8f * T - 0.07f * T, 0.8f * T + 0.2f * T);
            c.drawRect(r, p);
            r.set(lw - 0.8f * T + 0.07f * T, 0.8f * T - 0.2f * T, lw - 0.8f * T + 0.2f * T, 0.8f * T + 0.2f * T);
            c.drawRect(r, p);
        }

        private void bouton(Canvas c, float cx, float cy, float ra, boolean actif) {
            p.setColor(actif ? 0x88FFFFFF : 0x44FFFFFF);
            c.drawCircle(cx, cy, ra, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.07f * T);
            p.setColor(0xAAFFFFFF);
            c.drawCircle(cx, cy, ra, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void fleche(Canvas c, float cx, float cy, int sens) {
            p.setColor(0xFFFFFFFF);
            chemin.reset();
            chemin.moveTo(cx + sens * 0.38f * T, cy);
            chemin.lineTo(cx - sens * 0.2f * T, cy - 0.38f * T);
            chemin.lineTo(cx - sens * 0.2f * T, cy + 0.38f * T);
            chemin.close();
            c.drawPath(chemin, p);
        }

        private void commandes(Canvas c) {
            if (etat != JEU && etat != ATTENTE && etat != NIVEAU) return;
            bouton(c, gcx, gy, gr, gauche);
            fleche(c, gcx, gy, -1);
            bouton(c, dcx, gy, gr, droite);
            fleche(c, dcx, gy, 1);
            bouton(c, scx, sy, sr, saut);
            texte(c, "JUMP", scx, sy + 0.2f * T, 0.55f * T, 0xFFFFFFFF, Paint.Align.CENTER);
        }

        private void messages(Canvas c) {
            String t1 = null, t2 = "", t3 = "";
            if (etat == ATTENTE) {
                t1 = panda ? "🐼 Panda Dash" : "🍄 Super Kouamé";
                t2 = "Atteins le drapeau, ramasse les pièces, écrase les ennemis";
                t3 = "Touchez pour jouer";
            } else if (etat == PAUSE) {
                t1 = "Pause";
                t3 = "Touchez pour reprendre";
            } else if (etat == FIN) {
                t1 = "Perdu !";
                t2 = "Score " + score + "  •  Record " + record;
                t3 = "Touchez pour rejouer";
            } else if (etat == NIVEAU) {
                t1 = "Niveau " + niveau + " réussi !";
                t2 = "+500 points";
            }
            if (t1 == null) return;
            p.setColor(0x88000000);
            r.set(0, 0, lw, lh);
            c.drawRect(r, p);
            texte(c, t1, lw / 2f, lh * 0.40f, 0.95f * T, 0xFFFFFFFF, Paint.Align.CENTER);
            texte(c, t2, lw / 2f, lh * 0.40f + 0.9f * T, 0.5f * T, 0xFFE3E5EA, Paint.Align.CENTER);
            texte(c, t3, lw / 2f, lh * 0.40f + 1.7f * T, 0.55f * T, 0xFFFFC857, Paint.Align.CENTER);
            String monde = ScoresJeux.meilleur(cle);
            if (etat != NIVEAU && !monde.isEmpty()) {
                texte(c, "🥇 Meilleur joueur : " + monde, lw / 2f, lh * 0.40f + 2.5f * T, 0.45f * T, 0xFFB3E5FC, Paint.Align.CENTER);
            }
        }
    }
}
