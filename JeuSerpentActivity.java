package bj.radio;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Random;

/**
 * Serpent « téléphone classique » (comme sur les anciens Nokia) : écran LCD vert à gros pixels,
 * serpent fait de carrés, pomme en forme de losange, bonus chronométré toutes les 5 pommes,
 * les murs et la queue font perdre. On tourne avec les 4 flèches (ou en glissant le doigt).
 */
public class JeuSerpentActivity extends Activity {

    private VueSerpent vue;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.fenetre(this);
        getWindow().setStatusBarColor(0xFF2A3326);
        getWindow().setNavigationBarColor(0xFF2A3326);
        vue = new VueSerpent(this);
        setContentView(vue);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (vue != null) vue.pauser();
    }

    private static final class VueSerpent extends View {

        private static final int COLS = 15;
        private static final int ATTENTE = 0, JEU = 1, PAUSE = 2, FIN = 3;

        // couleurs de l'écran LCD
        private static final int CORPS = 0xFF2A3326, LCD = 0xFFA9C28B, ENCRE = 0xFF1B2612, PALE = 0x331B2612;

        private final Context ctx;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final RectF ecran = new RectF();
        private final Path chemin = new Path();
        private final Random rnd = new Random();
        private final ArrayList<int[]> corps = new ArrayList<>();
        private final ArrayList<int[]> fileDir = new ArrayList<>();   // virages en attente (2 au maximum)
        private final int[] pomme = new int[2];
        private final int[] bonus = new int[]{-1, -1};

        // flèches
        private final RectF fHaut = new RectF(), fBas = new RectF(), fGauche = new RectF(), fDroite = new RectF();

        private int rows = 20;
        private float cell, ox, oy, haut;
        private int dx = 1, dy = 0;
        private int score, record, etat, mangees;
        private float bonusT;                 // secondes restantes du bonus
        private long dernier, finTemps, derniereImage;
        private float tx, ty;
        private boolean glisse;
        private int largeur, hauteur;
        private boolean nouveauRecord;

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
            haut = 50 * d;
            float marge = 12 * d;
            float pad = Math.min(h * 0.30f, 190 * d);             // zone des flèches
            float zoneH = h - haut - pad - marge * 2;
            cell = (w - marge * 2 - 8 * d) / COLS;
            rows = Math.max(8, (int) (zoneH / cell));
            ox = (w - cell * COLS) / 2f;
            oy = marge + haut + (zoneH - rows * cell) / 2f;
            ecran.set(marge, marge, w - marge, marge + haut + zoneH);

            // croix directionnelle
            float b = Math.min(pad * 0.42f, 74 * d);
            float cx = w / 2f, cy = h - pad / 2f - 2 * d;
            fHaut.set(cx - b / 2, cy - b * 1.5f, cx + b / 2, cy - b / 2);
            fBas.set(cx - b / 2, cy + b / 2, cx + b / 2, cy + b * 1.5f);
            fGauche.set(cx - b * 1.5f, cy - b / 2, cx - b / 2, cy + b / 2);
            fDroite.set(cx + b / 2, cy - b / 2, cx + b * 1.5f, cy + b / 2);
            reinitialiser();
        }

        private void reinitialiser() {
            corps.clear();
            fileDir.clear();
            int cx = COLS / 2, cy = rows / 2;
            for (int i = 0; i < 4; i++) corps.add(new int[]{cx - i, cy});
            dx = 1; dy = 0;
            score = 0;
            mangees = 0;
            bonus[0] = bonus[1] = -1;
            bonusT = 0;
            nouveauRecord = false;
            record = JeuxActivity.record(ctx, "serpent");
            poser(pomme);
            etat = ATTENTE;
        }

        private boolean libre(int x, int y) {
            for (int[] s : corps) if (s[0] == x && s[1] == y) return false;
            return !(x == pomme[0] && y == pomme[1]) && !(x == bonus[0] && y == bonus[1]);
        }

        private void poser(int[] cible) {
            ArrayList<int[]> libres = new ArrayList<>();
            for (int i = 0; i < COLS; i++)
                for (int j = 0; j < rows; j++)
                    if (libre(i, j)) libres.add(new int[]{i, j});
            if (libres.isEmpty()) { cible[0] = -1; cible[1] = -1; return; }
            int[] c = libres.get(rnd.nextInt(libres.size()));
            cible[0] = c[0];
            cible[1] = c[1];
        }

        /** Plus on mange, plus ça va vite (comme sur le téléphone). */
        private long intervalle() {
            return Math.max(70, 165 - mangees * 4L);
        }

        private void demarrer() {
            etat = JEU;
            dernier = SystemClock.uptimeMillis();
        }

        private void fin(boolean victoire) {
            Sons.jouer(ctx, victoire ? Sons.GAGNE : Sons.PERDU);
            etat = FIN;
            finTemps = SystemClock.uptimeMillis();
            nouveauRecord = score > record;
            JeuxActivity.enregistrer(ctx, "serpent", score);
            record = Math.max(record, score);
        }

        private void avancer() {
            if (!fileDir.isEmpty()) {
                int[] n = fileDir.remove(0);
                dx = n[0];
                dy = n[1];
            }
            int[] tete = corps.get(0);
            int nx = tete[0] + dx, ny = tete[1] + dy;
            if (nx < 0 || ny < 0 || nx >= COLS || ny >= rows) { fin(false); return; }   // les murs font perdre
            boolean mange = nx == pomme[0] && ny == pomme[1];
            boolean mangeBonus = nx == bonus[0] && ny == bonus[1];
            int limite = (mange || mangeBonus) ? corps.size() : corps.size() - 1;
            for (int i = 0; i < limite; i++) {
                int[] s = corps.get(i);
                if (s[0] == nx && s[1] == ny) { fin(false); return; }
            }
            corps.add(0, new int[]{nx, ny});
            if (mange) {
                score += 7;
                mangees++;
                Sons.jouer(ctx, Sons.MANGER);
                poser(pomme);
                if (pomme[0] < 0) { fin(true); return; }
                if (mangees % 5 == 0 && bonus[0] < 0) {      // un bonus toutes les 5 pommes
                    poser(bonus);
                    bonusT = 9f;
                }
            } else if (mangeBonus) {
                score += 5 + (int) Math.ceil(bonusT) * 5;     // plus on est rapide, plus ça rapporte
                bonus[0] = bonus[1] = -1;
                bonusT = 0;
                Sons.jouer(ctx, Sons.PIECE);
            } else {
                corps.remove(corps.size() - 1);
            }
        }

        /** Demande de virage : on garde les 2 derniers pour ne perdre aucun appui rapide. */
        private void tourner(int ndx, int ndy) {
            if (etat == ATTENTE) demarrer();
            if (etat != JEU) return;
            int refx = fileDir.isEmpty() ? dx : fileDir.get(fileDir.size() - 1)[0];
            int refy = fileDir.isEmpty() ? dy : fileDir.get(fileDir.size() - 1)[1];
            if (ndx == -refx && ndy == -refy) return;     // pas de demi-tour
            if (ndx == refx && ndy == refy) return;
            if (fileDir.size() < 2) fileDir.add(new int[]{ndx, ndy});
        }

        private boolean clic(float x, float y) {
            long now = SystemClock.uptimeMillis();
            if (etat == JEU) {
                if (x > largeur - 70 * d && y < 70 * d) etat = PAUSE;
            } else if (etat == ATTENTE) {
                demarrer();
            } else if (etat == PAUSE) {
                etat = JEU;
                dernier = now;
            } else if (etat == FIN && now - finTemps > 600) {
                reinitialiser();
                demarrer();
            }
            return true;
        }

        private boolean fleche(float x, float y) {
            float m = 10 * d;
            if (x >= fHaut.left - m && x <= fHaut.right + m && y >= fHaut.top - m && y <= fHaut.bottom) { tourner(0, -1); return true; }
            if (x >= fBas.left - m && x <= fBas.right + m && y >= fBas.top && y <= fBas.bottom + m) { tourner(0, 1); return true; }
            if (x >= fGauche.left - m && x <= fGauche.right && y >= fGauche.top - m && y <= fGauche.bottom + m) { tourner(-1, 0); return true; }
            if (x >= fDroite.left && x <= fDroite.right + m && y >= fDroite.top - m && y <= fDroite.bottom + m) { tourner(1, 0); return true; }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                int i = e.getActionIndex();
                float x = e.getX(i), y = e.getY(i);
                if (etat == JEU && fleche(x, y)) {                // appui immédiat sur une flèche
                    invalidate();
                    glisse = true;
                    return true;
                }
                if (a == MotionEvent.ACTION_DOWN) {
                    tx = x;
                    ty = y;
                    glisse = false;
                }
                if (etat == ATTENTE && fleche(x, y)) { glisse = true; invalidate(); }
                return true;
            }
            if (a == MotionEvent.ACTION_MOVE) {
                float ddx = e.getX() - tx, ddy = e.getY() - ty;
                float seuil = 22 * d;
                if (Math.abs(ddx) > seuil || Math.abs(ddy) > seuil) {      // on peut aussi glisser le doigt
                    if (Math.abs(ddx) > Math.abs(ddy)) tourner(ddx > 0 ? 1 : -1, 0);
                    else tourner(0, ddy > 0 ? 1 : -1);
                    tx = e.getX();
                    ty = e.getY();
                    glisse = true;
                    invalidate();
                }
                return true;
            }
            if (a == MotionEvent.ACTION_UP) {
                if (!glisse) clic(e.getX(), e.getY());
                invalidate();
            }
            return true;
        }

        // ------------------------------------------------------------------ dessin

        private void pixel(Canvas c, float x, float y, float t) {
            r.set(x, y, x + t, y + t);
            c.drawRect(r, p);
        }

        private void texte(Canvas c, String t, float x, float y, float taille, int couleur, Paint.Align al) {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(taille);
            p.setTextAlign(al);
            p.setColor(couleur);
            c.drawText(t, x, y, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            if (derniereImage == 0) derniereImage = now;
            if (etat == JEU) {
                float dtImg = Math.min(0.05f, (now - derniereImage) / 1000f);
                if (bonus[0] >= 0) {
                    bonusT -= dtImg;
                    if (bonusT <= 0) { bonus[0] = bonus[1] = -1; bonusT = 0; }
                }
                if (now - dernier >= intervalle()) {
                    dernier = now;
                    avancer();
                }
            }
            derniereImage = now;

            c.drawColor(CORPS);

            // écran LCD
            p.setStyle(Paint.Style.FILL);
            p.setColor(LCD);
            c.drawRoundRect(ecran, 10 * d, 10 * d, p);

            // barre du haut : score, record, bonus
            texte(c, String.format(java.util.Locale.US, "%04d", score), ecran.left + 12 * d, ecran.top + haut * 0.7f, 24 * d, ENCRE, Paint.Align.LEFT);
            texte(c, "HI " + String.format(java.util.Locale.US, "%04d", Math.max(record, score)), ecran.right - 62 * d, ecran.top + haut * 0.66f, 14 * d, ENCRE, Paint.Align.RIGHT);
            // bouton pause (deux barres)
            p.setColor(ENCRE);
            float bx = ecran.right - 30 * d, by = ecran.top + haut / 2f;
            r.set(bx - 7 * d, by - 9 * d, bx - 3 * d, by + 9 * d);
            c.drawRect(r, p);
            r.set(bx + 3 * d, by - 9 * d, bx + 7 * d, by + 9 * d);
            c.drawRect(r, p);
            // barre du temps de bonus
            if (bonus[0] >= 0) {
                float lg = (ecran.width() - 24 * d) * Math.max(0f, bonusT / 9f);
                r.set(ecran.left + 12 * d, ecran.top + haut - 5 * d, ecran.left + 12 * d + lg, ecran.top + haut - 1 * d);
                c.drawRect(r, p);
            }

            // cadre du terrain : les murs
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * d);
            p.setColor(ENCRE);
            r.set(ox - 3 * d, oy - 3 * d, ox + cell * COLS + 3 * d, oy + cell * rows + 3 * d);
            c.drawRect(r, p);
            p.setStyle(Paint.Style.FILL);

            // grille très discrète (pixels de l'écran)
            p.setColor(PALE);
            for (int i = 1; i < COLS; i++) c.drawRect(ox + i * cell - 0.5f, oy, ox + i * cell + 0.5f, oy + rows * cell, p);
            for (int j = 1; j < rows; j++) c.drawRect(ox, oy + j * cell - 0.5f, ox + COLS * cell, oy + j * cell + 0.5f, p);

            // pomme : losange de petits pixels
            p.setColor(ENCRE);
            if (pomme[0] >= 0) losange(c, pomme[0], pomme[1], false);
            // bonus : grosse croix qui clignote
            if (bonus[0] >= 0 && (bonusT > 3f || ((int) (bonusT * 6f)) % 2 == 0)) losange(c, bonus[0], bonus[1], true);

            // serpent : carrés pleins
            p.setColor(ENCRE);
            float m = cell * 0.06f;
            for (int i = corps.size() - 1; i >= 0; i--) {
                int[] s = corps.get(i);
                r.set(ox + s[0] * cell + m, oy + s[1] * cell + m, ox + (s[0] + 1) * cell - m, oy + (s[1] + 1) * cell - m);
                c.drawRect(r, p);
            }
            if (!corps.isEmpty()) {                       // tête : un « œil » clair et la bouche qui s'ouvre vers la pomme
                int[] t = corps.get(0);
                float cx = ox + (t[0] + 0.5f) * cell, cy = oy + (t[1] + 0.5f) * cell;
                p.setColor(LCD);
                float px = -dy, py = dx;
                pixel(c, cx + dx * cell * 0.12f + px * cell * 0.18f - cell * 0.07f, cy + dy * cell * 0.12f + py * cell * 0.18f - cell * 0.07f, cell * 0.14f);
                if (pomme[0] >= 0 && Math.abs(t[0] - pomme[0]) + Math.abs(t[1] - pomme[1]) <= 2) {
                    pixel(c, cx + dx * cell * 0.38f - cell * 0.08f, cy + dy * cell * 0.38f - cell * 0.08f, cell * 0.16f);
                }
            }

            // flèches
            fleche(c, fHaut, 0);
            fleche(c, fDroite, 1);
            fleche(c, fBas, 2);
            fleche(c, fGauche, 3);

            // messages
            if (etat == ATTENTE) {
                message(c, "SERPENT", "Touchez une flèche", "pour commencer");
            } else if (etat == PAUSE) {
                message(c, "PAUSE", "", "Touchez pour reprendre");
            } else if (etat == FIN) {
                message(c, "GAME OVER", "Score " + score + (nouveauRecord ? "  NOUVEAU RECORD !" : "   Record " + record), "Touchez pour rejouer");
            }

            if (etat == JEU) postInvalidateOnAnimation();
        }

        /** Losange de 4 pixels (la « pomme » du vieux Serpent). Grand = bonus. */
        private void losange(Canvas c, int gx, int gy, boolean grand) {
            float x = ox + gx * cell, y = oy + gy * cell, t = cell / 3f;
            if (grand) {
                float m = cell * 0.04f;
                r.set(x + m, y + m, x + cell - m, y + cell - m);
                c.drawRect(r, p);
                p.setColor(LCD);
                pixel(c, x + t, y + t, t);
                p.setColor(ENCRE);
            } else {
                pixel(c, x + t, y, t);
                pixel(c, x, y + t, t);
                pixel(c, x + 2 * t, y + t, t);
                pixel(c, x + t, y + 2 * t, t);
            }
        }

        private void fleche(Canvas c, RectF b, int sens) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF3E4C37);
            c.drawRoundRect(b, 12 * d, 12 * d, p);
            p.setColor(0xFF1E2619);
            r.set(b.left + 2 * d, b.bottom - 6 * d, b.right - 2 * d, b.bottom);
            c.drawRoundRect(r, 8 * d, 8 * d, p);
            p.setColor(0xFFC8E0A8);
            float cx = b.centerX(), cy = b.centerY() - 2 * d, t = b.width() * 0.22f;
            chemin.reset();
            switch (sens) {
                case 0: chemin.moveTo(cx, cy - t); chemin.lineTo(cx - t, cy + t * 0.7f); chemin.lineTo(cx + t, cy + t * 0.7f); break;
                case 1: chemin.moveTo(cx + t, cy); chemin.lineTo(cx - t * 0.7f, cy - t); chemin.lineTo(cx - t * 0.7f, cy + t); break;
                case 2: chemin.moveTo(cx, cy + t); chemin.lineTo(cx - t, cy - t * 0.7f); chemin.lineTo(cx + t, cy - t * 0.7f); break;
                default: chemin.moveTo(cx - t, cy); chemin.lineTo(cx + t * 0.7f, cy - t); chemin.lineTo(cx + t * 0.7f, cy + t); break;
            }
            chemin.close();
            c.drawPath(chemin, p);
        }

        private void message(Canvas c, String titre, String ligne2, String ligne3) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCCA9C28B);
            c.drawRoundRect(ecran, 10 * d, 10 * d, p);
            float cy = ecran.centerY();
            texte(c, titre, largeur / 2f, cy - 20 * d, 34 * d, ENCRE, Paint.Align.CENTER);
            texte(c, ligne2, largeur / 2f, cy + 16 * d, 15 * d, ENCRE, Paint.Align.CENTER);
            texte(c, ligne3, largeur / 2f, cy + 44 * d, 15 * d, ENCRE, Paint.Align.CENTER);
            String monde = ScoresJeux.meilleur("serpent");
            if (!monde.isEmpty()) texte(c, "Meilleur : " + monde, largeur / 2f, cy + 76 * d, 13 * d, ENCRE, Paint.Align.CENTER);
        }
    }
}
