package bj.radio;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Random;

/** Jeu du Serpent : on glisse le doigt pour tourner, on mange les pommes, on évite les murs et sa queue. */
public class JeuSerpentActivity extends Activity {

    private VueSerpent vue;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.fenetre(this);
        vue = new VueSerpent(this);
        setContentView(vue);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (vue != null) vue.pauser();
    }

    private static final class VueSerpent extends View {

        private static final int COLS = 16;
        private static final int ATTENTE = 0, JEU = 1, PAUSE = 2, FIN = 3;

        private final Context ctx;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Random rnd = new Random();
        private final ArrayList<int[]> corps = new ArrayList<>();
        private final int[] pomme = new int[2];

        private int rows = 20;
        private float cell, ox, oy, haut;
        private int dx = 1, dy = 0, pdx = 1, pdy = 0;
        private int score, record, etat;
        private long dernier, finTemps;
        private float tx, ty;
        private boolean glisse;
        private int largeur, hauteur;

        VueSerpent(Context c) {
            super(c);
            ctx = c;
            d = c.getResources().getDisplayMetrics().density;
            record = JeuxActivity.record(c, "serpent");
        }

        void pauser() {
            if (etat == JEU) etat = PAUSE;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            largeur = w;
            hauteur = h;
            cell = w / (float) COLS;
            haut = 56 * d;
            float bas = 24 * d;
            rows = Math.max(8, (int) ((h - haut - bas) / cell));
            ox = (w - cell * COLS) / 2f;
            oy = haut + (h - haut - bas - rows * cell) / 2f;
            reinitialiser();
        }

        private void reinitialiser() {
            corps.clear();
            int cx = COLS / 2, cy = rows / 2;
            corps.add(new int[]{cx, cy});
            corps.add(new int[]{cx - 1, cy});
            corps.add(new int[]{cx - 2, cy});
            dx = 1; dy = 0; pdx = 1; pdy = 0;
            score = 0;
            record = JeuxActivity.record(ctx, "serpent");
            poserPomme();
            etat = ATTENTE;
        }

        private void poserPomme() {
            boolean[][] occupe = new boolean[COLS][rows];
            for (int[] s : corps) occupe[s[0]][s[1]] = true;
            ArrayList<int[]> libres = new ArrayList<>();
            for (int i = 0; i < COLS; i++)
                for (int j = 0; j < rows; j++)
                    if (!occupe[i][j]) libres.add(new int[]{i, j});
            if (libres.isEmpty()) { pomme[0] = -1; pomme[1] = -1; return; }
            int[] c = libres.get(rnd.nextInt(libres.size()));
            pomme[0] = c[0];
            pomme[1] = c[1];
        }

        private long intervalle() {
            return Math.max(65, 150 - (corps.size() - 3) * 3);
        }

        private void demarrer() {
            etat = JEU;
            dernier = SystemClock.uptimeMillis();
        }

        private void fin() {
            etat = FIN;
            finTemps = SystemClock.uptimeMillis();
            JeuxActivity.enregistrer(ctx, "serpent", score);
            record = Math.max(record, score);
        }

        private void avancer() {
            dx = pdx;
            dy = pdy;
            int[] tete = corps.get(0);
            int nx = tete[0] + dx, ny = tete[1] + dy;
            if (nx < 0 || ny < 0 || nx >= COLS || ny >= rows) { fin(); return; }
            boolean mange = nx == pomme[0] && ny == pomme[1];
            int limite = mange ? corps.size() : corps.size() - 1;   // la queue se déplace si on ne grandit pas
            for (int i = 0; i < limite; i++) {
                int[] s = corps.get(i);
                if (s[0] == nx && s[1] == ny) { fin(); return; }
            }
            corps.add(0, new int[]{nx, ny});
            if (mange) {
                score += 10;
                poserPomme();
                if (pomme[0] < 0) { fin(); return; }   // grille pleine : bravo
            } else {
                corps.remove(corps.size() - 1);
            }
        }

        private void tourner(int ndx, int ndy) {
            if (etat == ATTENTE) demarrer();
            if (etat != JEU) return;
            if (ndx == -dx && ndy == -dy) return;      // pas de demi-tour
            pdx = ndx;
            pdy = ndy;
        }

        private void toucher(float x, float y) {
            long now = SystemClock.uptimeMillis();
            if (etat == JEU) {
                if (x > largeur - haut * 1.3f && y < haut) etat = PAUSE;
            } else if (etat == ATTENTE) {
                demarrer();
            } else if (etat == PAUSE) {
                etat = JEU;
                dernier = now;
            } else if (etat == FIN && now - finTemps > 500) {
                reinitialiser();
                demarrer();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tx = e.getX();
                    ty = e.getY();
                    glisse = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float ddx = e.getX() - tx, ddy = e.getY() - ty;
                    float seuil = 22 * d;
                    if (Math.abs(ddx) > seuil || Math.abs(ddy) > seuil) {
                        if (Math.abs(ddx) > Math.abs(ddy)) tourner(ddx > 0 ? 1 : -1, 0);
                        else tourner(0, ddy > 0 ? 1 : -1);
                        tx = e.getX();
                        ty = e.getY();
                        glisse = true;
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!glisse) toucher(e.getX(), e.getY());
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            if (etat == JEU && now - dernier >= intervalle()) {
                dernier = now;
                avancer();
            }

            c.drawColor(0xFF163A27);

            // damier
            for (int i = 0; i < COLS; i++) {
                for (int j = 0; j < rows; j++) {
                    p.setColor(((i + j) & 1) == 0 ? 0xFF2E6B45 : 0xFF2A6040);
                    r.set(ox + i * cell, oy + j * cell, ox + (i + 1) * cell, oy + (j + 1) * cell);
                    c.drawRect(r, p);
                }
            }

            // pomme
            if (pomme[0] >= 0) {
                float cx = ox + (pomme[0] + 0.5f) * cell, cy = oy + (pomme[1] + 0.5f) * cell;
                p.setColor(0xFFE53935);
                c.drawCircle(cx, cy + cell * 0.04f, cell * 0.38f, p);
                p.setColor(0x55FFFFFF);
                c.drawCircle(cx - cell * 0.12f, cy - cell * 0.1f, cell * 0.1f, p);
                p.setColor(0xFF6D4C41);
                r.set(cx - cell * 0.03f, cy - cell * 0.5f, cx + cell * 0.03f, cy - cell * 0.28f);
                c.drawRect(r, p);
                p.setColor(0xFF66BB6A);
                r.set(cx, cy - cell * 0.5f, cx + cell * 0.28f, cy - cell * 0.34f);
                c.drawOval(r, p);
            }

            // serpent (de la queue vers la tête, pour que la tête soit dessinée en dernier)
            int n = corps.size();
            float marge = cell * 0.08f;
            for (int i = n - 1; i >= 0; i--) {
                int[] s = corps.get(i);
                p.setColor(i == 0 ? 0xFF9BE564 : Ui.assombrir(0xFF7ED957, 0.45f * i / Math.max(1, n)));
                r.set(ox + s[0] * cell + marge, oy + s[1] * cell + marge,
                        ox + (s[0] + 1) * cell - marge, oy + (s[1] + 1) * cell - marge);
                c.drawRoundRect(r, cell * 0.3f, cell * 0.3f, p);
            }
            if (n > 0) {
                int[] t = corps.get(0);
                float cx = ox + (t[0] + 0.5f) * cell, cy = oy + (t[1] + 0.5f) * cell;
                float px = -dy, py = dx;   // perpendiculaire à la direction
                for (int k = -1; k <= 1; k += 2) {
                    float ex = cx + dx * cell * 0.15f + px * k * cell * 0.2f;
                    float ey = cy + dy * cell * 0.15f + py * k * cell * 0.2f;
                    p.setColor(0xFFFFFFFF);
                    c.drawCircle(ex, ey, cell * 0.13f, p);
                    p.setColor(0xFF111111);
                    c.drawCircle(ex + dx * cell * 0.04f, ey + dy * cell * 0.04f, cell * 0.07f, p);
                }
            }

            // barre du haut
            p.setColor(0xFF0E241A);
            r.set(0, 0, largeur, haut);
            c.drawRect(r, p);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(20 * d);
            p.setColor(0xFFFFFFFF);
            c.drawText("Score " + score, 14 * d, haut * 0.64f, p);
            p.setTextSize(15 * d);
            p.setColor(0xFFFFC857);
            c.drawText("Record " + Math.max(record, score), largeur * 0.46f, haut * 0.62f, p);
            // bouton pause
            p.setColor(0xFFFFFFFF);
            float bx = largeur - 30 * d, by = haut / 2f;
            r.set(bx - 8 * d, by - 10 * d, bx - 3 * d, by + 10 * d);
            c.drawRect(r, p);
            r.set(bx + 3 * d, by - 10 * d, bx + 8 * d, by + 10 * d);
            c.drawRect(r, p);

            // messages
            if (etat == ATTENTE) {
                message(c, "🐍 Serpent", "Glissez le doigt pour tourner", "Touchez pour commencer");
            } else if (etat == PAUSE) {
                message(c, "Pause", "", "Touchez pour reprendre");
            } else if (etat == FIN) {
                message(c, "Perdu !", "Score " + score + "  •  Record " + record, "Touchez pour rejouer");
            }

            if (etat == JEU) postInvalidateOnAnimation();
        }

        private void message(Canvas c, String titre, String ligne2, String ligne3) {
            p.setColor(0xAA000000);
            r.set(0, 0, largeur, hauteur);
            c.drawRect(r, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(34 * d);
            c.drawText(titre, largeur / 2f, hauteur * 0.40f, p);
            p.setTextSize(17 * d);
            p.setColor(0xFFE3E5EA);
            c.drawText(ligne2, largeur / 2f, hauteur * 0.40f + 36 * d, p);
            p.setColor(0xFFFFC857);
            c.drawText(ligne3, largeur / 2f, hauteur * 0.40f + 70 * d, p);
            String monde = ScoresJeux.meilleur("serpent");
            if (!monde.isEmpty()) {
                p.setTextSize(15 * d);
                p.setColor(0xFFB3E5FC);
                c.drawText("🥇 Meilleur joueur : " + monde, largeur / 2f, hauteur * 0.40f + 104 * d, p);
            }
            p.setTextAlign(Paint.Align.LEFT);
        }
    }
}
