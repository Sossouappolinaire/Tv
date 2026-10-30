package bj.radio;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
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
 * Commando Kouamé : jeu de tir et de course à défilement (dans l'esprit des anciens jeux d'action des consoles
 * de salon). Jeu original : on avance vers la droite, on saute au-dessus des trous, on tire sur les soldats et
 * les tourelles, on attrape les capsules d'armes et on détruit le gros robot qui garde la fin de chaque niveau.
 * Boutons : gauche / droite, SAUT et TIR (maintenu = tir continu, visée assistée vers l'ennemi le plus proche).
 */
public class JeuCommandoActivity extends Activity {

    private VueCommando vue;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersif();
        vue = new VueCommando(this);
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

    private static final class VueCommando extends View {

        // Le jeu est dessiné dans un repère de 360 unités de haut, quelle que soit la taille de l'écran.
        private static final float ZOOM = 1.4f;   // le soldat et les ennemis paraissent 1,4 fois plus grands
        private static final float HAUT = 360f, SOL = 300f, GRAV = 1500f, VIT = 175f, SAUT = 560f;
        private static final int ATTENTE = 0, JEU = 1, PAUSE = 2, NIVEAU = 3, FIN = 4;
        private static final int RUN = 0, TIREUR = 1, TOURELLE = 2, CAPSULE = 3, BOSS = 4;

        private static final class Seg {
            final float x, w;
            Seg(float x, float w) { this.x = x; this.w = w; }
        }

        private static final class Palier {
            final float x, y, w;
            Palier(float x, float y, float w) { this.x = x; this.y = y; this.w = w; }
        }

        private static final class Ennemi {
            int type, pv, pvMax;
            float x, y, w, h, vy, tir, anim, base;
        }

        private static final class Balle {
            float x, y, vx, vy;
            boolean joueur;
        }

        private static final class Eclat {
            float x, y, vx, vy, vie;
            int couleur;
        }

        private final Context ctx;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private final ArrayList<Seg> segs = new ArrayList<>();
        private final ArrayList<Palier> paliers = new ArrayList<>();
        private final ArrayList<Ennemi> ennemis = new ArrayList<>();
        private final ArrayList<Balle> balles = new ArrayList<>();
        private final ArrayList<Eclat> eclats = new ArrayList<>();

        private int etat = ATTENTE;
        private int score, record, niveau = 1, vies = 3, arme = 0, face = 1;
        private float px, py, pvy, camX, longueur, largeur = 640f, k = 1f;
        private float inv, cdTir, tNiveau, msgT, temps, tPas;
        private boolean psol, gauche, droite, saut, tir;
        private String msg = "";
        private long dernier, finTemps;
        private Shader ciel;

        VueCommando(Context c) {
            super(c);
            ctx = c;
            record = JeuxActivity.record(c, "commando");
        }

        void pauser() {
            if (etat == JEU) {
                etat = PAUSE;
                sauver();
            }
            invalidate();
        }

        private void sauver() {
            if (score > 0) JeuxActivity.enregistrer(ctx, "commando", score);
            if (score > record) record = score;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            k = h / HAUT;
            largeur = w / k;
            ciel = new LinearGradient(0, 0, 0, HAUT, 0xFF0B1E3F, 0xFF2F7D6D, Shader.TileMode.CLAMP);
        }

        // ------------------------------------------------------------------ niveaux

        private void demarrerPartie() {
            score = 0;
            niveau = 1;
            vies = 3;
            arme = 0;
            chargerNiveau();
            etat = JEU;
            dernier = SystemClock.uptimeMillis();
            invalidate();
        }

        private void chargerNiveau() {
            segs.clear();
            paliers.clear();
            ennemis.clear();
            balles.clear();
            eclats.clear();
            Random r = new Random(1000L + niveau * 37L);
            int d = Math.min(niveau, 6);
            float fin = 3000f + 350f * d;
            segs.add(new Seg(0f, 520f));
            float x = 520f;
            int n = 0;
            while (x < fin) {
                x += 70f + r.nextInt(50);
                float l = 260f + r.nextInt(260);
                segs.add(new Seg(x, l));
                n++;
                if (r.nextInt(100) < 55) ajouter(RUN, x + l * 0.7f, SOL, d);
                if (niveau >= 2 && r.nextInt(100) < 40) ajouter(RUN, x + l * 0.85f, SOL, d);
                if (r.nextInt(100) < 35 + 5 * d) ajouter(TIREUR, x + l * 0.5f, SOL, d);
                if (l > 300f && r.nextInt(100) < 55) {
                    float lx = x + 40f + r.nextInt((int) (l - 250f));
                    float lw = 120f + r.nextInt(60);
                    float ly = SOL - 90f;
                    paliers.add(new Palier(lx, ly, lw));
                    if (r.nextInt(2) == 0) ajouter(TOURELLE, lx + lw / 2f, ly, d);
                    else if (r.nextInt(100) < 50) ajouter(TIREUR, lx + lw / 2f, ly, d);
                } else if (r.nextInt(100) < 25) {
                    ajouter(TOURELLE, x + l * 0.3f, SOL, d);
                }
                if (n % 3 == 0) ajouter(CAPSULE, x + l * 0.3f, 100f + r.nextInt(50), d);
                x += l;
            }
            segs.add(new Seg(x, 760f));                 // arène du boss, sans trou
            longueur = x + 760f;
            ajouter(BOSS, longueur - 230f, SOL, d);
            px = 60f;
            py = 120f;
            pvy = 0f;
            psol = false;
            camX = 0f;
            inv = 1.5f;
        }

        private void ajouter(int type, float x, float y, int d) {
            Ennemi e = new Ennemi();
            e.type = type;
            e.x = x;
            e.y = y;
            e.base = type == BOSS ? x : y;
            e.tir = 1f + rnd.nextFloat();
            switch (type) {
                case RUN: e.pv = 1; e.w = 18f; e.h = 38f; break;
                case TIREUR: e.pv = 2; e.w = 18f; e.h = 40f; break;
                case TOURELLE: e.pv = 3; e.w = 30f; e.h = 24f; break;
                case CAPSULE: e.pv = 1; e.w = 28f; e.h = 16f; break;
                default: e.pv = 30 + 10 * d; e.w = 110f; e.h = 84f; break;
            }
            e.pvMax = e.pv;
            ennemis.add(e);
        }

        // ------------------------------------------------------------------ physique

        /** Hauteur du sol ou du palier atteint en tombant de yAvant à yApres, ou NaN s'il n'y a rien. */
        private float surface(float xc, float yAvant, float yApres) {
            for (int i = 0; i < segs.size(); i++) {
                Seg s = segs.get(i);
                if (xc >= s.x && xc <= s.x + s.w && yAvant <= SOL + 1f && yApres >= SOL) return SOL;
            }
            for (int i = 0; i < paliers.size(); i++) {
                Palier l = paliers.get(i);
                if (xc >= l.x && xc <= l.x + l.w && yAvant <= l.y + 1f && yApres >= l.y) return l.y;
            }
            return Float.NaN;
        }

        private boolean surSol(float x) {
            for (int i = 0; i < segs.size(); i++) {
                Seg s = segs.get(i);
                if (x >= s.x + 4f && x <= s.x + s.w - 4f) return true;
            }
            return false;
        }

        private void maj(float dt) {
            temps += dt;
            if (inv > 0) inv -= dt;
            if (msgT > 0) msgT -= dt;
            cdTir -= dt;

            // --- joueur
            float dir = (droite ? 1f : 0f) - (gauche ? 1f : 0f);
            if (dir != 0f) face = dir > 0 ? 1 : -1;
            px += dir * VIT * dt;
            px = Math.max(px, camX + 12f);
            px = Math.min(px, longueur - 12f);
            if (saut && psol) {
                pvy = -SAUT;
                psol = false;
                Sons.jouer(ctx, Sons.SAUT);
            }
            if (dir != 0f && psol) {                 // petit bruit de pas quand il avance
                tPas -= dt;
                if (tPas <= 0f) { Sons.jouer(ctx, Sons.PAS); tPas = 0.28f; }
            } else {
                tPas = 0f;
            }
            pvy += GRAV * dt;
            float yNouveau = py + pvy * dt;
            float sol = pvy >= 0f ? surface(px, py, yNouveau) : Float.NaN;
            if (!Float.isNaN(sol)) {
                py = sol;
                pvy = 0f;
                psol = true;
            } else {
                py = yNouveau;
                psol = false;
            }
            if (py > 430f) {          // tombé dans l'eau : on perd une vie même pendant la protection
                inv = 0f;
                mort();
            }

            if (tir && cdTir <= 0f && etat == JEU) {
                tirerJoueur();
                cdTir = arme == 2 ? 0.10f : arme == 1 ? 0.32f : 0.24f;
            }

            float vis = largeur / ZOOM;                      // largeur réellement visible
            float cible = px - vis * 0.38f;
            if (cible > camX) camX = cible;
            camX = Math.max(0f, Math.min(camX, longueur - vis));

            majEnnemis(dt);
            majBalles(dt);
            majEclats(dt);
        }

        private void majEnnemis(float dt) {
            int d = Math.min(niveau, 6);
            for (int i = ennemis.size() - 1; i >= 0; i--) {
                Ennemi e = ennemis.get(i);
                float ecran = e.x - camX;
                if (ecran < -200f) {
                    ennemis.remove(i);
                    continue;
                }
                if (ecran > largeur / ZOOM + 120f) continue;
                e.anim += dt;
                boolean visible = ecran > 15f && ecran < largeur - 15f;
                switch (e.type) {
                    case RUN: {
                        float v = (px < e.x ? -1f : 1f) * (75f + 10f * d);
                        e.x += v * dt;
                        e.vy += GRAV * dt;
                        float yN = e.y + e.vy * dt;
                        float s = e.vy >= 0f ? surface(e.x, e.y, yN) : Float.NaN;
                        if (!Float.isNaN(s)) {
                            e.y = s;
                            e.vy = 0f;
                        } else {
                            e.y = yN;
                        }
                        if (e.y > 430f) {
                            ennemis.remove(i);
                            continue;
                        }
                        break;
                    }
                    case TIREUR:
                    case TOURELLE: {
                        e.tir -= dt;
                        if (visible && e.tir <= 0f) {
                            tirer(e.x, e.y - e.h * 0.6f, px, py - 22f, 150f + 8f * d, false);
                            e.tir = Math.max(0.9f, (e.type == TIREUR ? 2.0f : 2.4f) - 0.15f * d) + rnd.nextFloat() * 0.8f;
                        }
                        break;
                    }
                    case CAPSULE: {
                        e.x -= 40f * dt;
                        e.y = e.base + 25f * (float) Math.sin(e.anim * 2.2f);
                        break;
                    }
                    default: {   // boss
                        e.x = e.base + 35f * (float) Math.sin(e.anim * 0.8f);
                        e.tir -= dt;
                        if (visible && e.tir <= 0f) {
                            float cx = e.x - 30f, cy = e.y - e.h * 0.6f;
                            float base = (float) Math.atan2((py - 22f) - cy, px - cx);
                            for (int a = -1; a <= 1; a++) {
                                float ang = base + a * 0.32f;
                                Balle b = new Balle();
                                b.x = cx;
                                b.y = cy;
                                b.vx = (float) Math.cos(ang) * (170f + 8f * d);
                                b.vy = (float) Math.sin(ang) * (170f + 8f * d);
                                balles.add(b);
                            }
                            e.tir = Math.max(0.9f, 1.8f - 0.1f * d);
                        }
                        break;
                    }
                }
                // contact : seuls les soldats qui courent et le boss blessent au toucher
                if ((e.type == RUN || e.type == BOSS) && inv <= 0f
                        && Math.abs(e.x - px) < e.w / 2f + 8f && py > e.y - e.h && py - 40f < e.y) {
                    mort();
                    if (etat != JEU) return;
                }
            }
        }

        private void majBalles(float dt) {
            for (int i = balles.size() - 1; i >= 0; i--) {
                Balle b = balles.get(i);
                b.x += b.vx * dt;
                b.y += b.vy * dt;
                if (b.x < camX - 60f || b.x > camX + largeur + 60f || b.y < -60f || b.y > HAUT + 60f) {
                    balles.remove(i);
                    continue;
                }
                if (b.joueur) {
                    boolean touche = false;
                    for (int j = ennemis.size() - 1; j >= 0 && !touche; j--) {
                        Ennemi e = ennemis.get(j);
                        if (Math.abs(b.x - e.x) < e.w / 2f + 4f && b.y > e.y - e.h - 4f && b.y < e.y + 4f) {
                            touche = true;
                            e.pv--;
                            eclater(b.x, b.y, 0xFFFFE082, 3);
                            if (e.pv <= 0) tuer(e, j); else Sons.jouer(ctx, Sons.TOUCHE);
                        }
                    }
                    if (touche) {
                        balles.remove(i);
                        if (etat != JEU) return;
                    }
                } else if (inv <= 0f && Math.abs(b.x - px) < 11f && b.y > py - 42f && b.y < py + 2f) {
                    balles.remove(i);
                    mort();
                    if (etat != JEU) return;
                }
            }
        }

        private void majEclats(float dt) {
            for (int i = eclats.size() - 1; i >= 0; i--) {
                Eclat e = eclats.get(i);
                e.vie -= dt;
                e.x += e.vx * dt;
                e.y += e.vy * dt;
                e.vy += 400f * dt;
                if (e.vie <= 0f) eclats.remove(i);
            }
        }

        private void majNiveau(float dt) {
            temps += dt;
            majEclats(dt);
            tNiveau -= dt;
            if (tNiveau <= 0f) {
                niveau++;
                chargerNiveau();
                etat = JEU;
            }
        }

        private void tuer(Ennemi e, int index) {
            ennemis.remove(index);
            if (e.type != CAPSULE) Sons.jouer(ctx, Sons.EXPLOSION);
            eclater(e.x, e.y - e.h / 2f, 0xFFFF7043, e.type == BOSS ? 40 : 12);
            switch (e.type) {
                case RUN: score += 100; break;
                case TIREUR: score += 200; break;
                case TOURELLE: score += 300; break;
                case CAPSULE:
                    Sons.jouer(ctx, Sons.PIECE);
                    score += 500;
                    arme = rnd.nextInt(2) + 1;
                    msg = arme == 1 ? "ARME : TIR LARGE" : "ARME : TIR RAPIDE";
                    msgT = 1.8f;
                    break;
                default:
                    Sons.jouer(ctx, Sons.GAGNE);          // robot détruit : niveau gagné
                    score += 2000;
                    balles.clear();
                    msg = "";
                    etat = NIVEAU;
                    tNiveau = 3.5f;
                    sauver();
                    break;
            }
        }

        private void mort() {
            if (inv > 0f || etat != JEU) return;
            vies--;
            arme = 0;
            eclater(px, py - 20f, 0xFFEF5350, 18);
            Sons.jouer(ctx, vies <= 0 ? Sons.PERDU : Sons.PERDRE_VIE);
            if (vies <= 0) {
                etat = FIN;
                finTemps = SystemClock.uptimeMillis();
                sauver();
                return;
            }
            // retour de l'autre côté de l'écran, au-dessus d'un vrai sol
            float x = camX + 70f;
            for (int i = 0; i < 300 && !surSol(x); i++) x += 10f;
            px = x;
            py = 100f;
            pvy = 0f;
            psol = false;
            inv = 2.2f;
            for (int i = balles.size() - 1; i >= 0; i--) if (!balles.get(i).joueur) balles.remove(i);
        }

        // ------------------------------------------------------------------ tirs

        private void tirer(float x, float y, float cx, float cy, float vit, boolean joueur) {
            if (balles.size() > 90) return;
            float dx = cx - x, dy = cy - y;
            float n = (float) Math.sqrt(dx * dx + dy * dy);
            if (n < 1f) {
                dx = 1f;
                dy = 0f;
                n = 1f;
            }
            Balle b = new Balle();
            b.x = x;
            b.y = y;
            b.vx = dx / n * vit;
            b.vy = dy / n * vit;
            b.joueur = joueur;
            balles.add(b);
        }

        /** Tir du joueur : tout droit, ou vers l'ennemi le plus proche s'il est à peu près devant (visée assistée). */
        private void tirerJoueur() {
            Sons.jouer(ctx, Sons.TIR);
            float bx = px + face * 16f, by = py - 24f;
            float dx = face, dy = 0f;
            float meilleur = 520f;
            for (int i = 0; i < ennemis.size(); i++) {
                Ennemi e = ennemis.get(i);
                float ex = (e.x - px) * face;
                float ey = (e.y - e.h / 2f) - by;
                if (ex > 20f && ex < meilleur && Math.abs(ey) <= ex * 1.1f + 20f && e.x - camX < largeur / ZOOM) {
                    meilleur = ex;
                    dx = e.x - bx;
                    dy = (e.y - e.h / 2f) - by;
                }
            }
            float n = (float) Math.sqrt(dx * dx + dy * dy);
            if (n < 1f) {
                dx = face;
                dy = 0f;
                n = 1f;
            }
            dx /= n;
            dy /= n;
            int nb = arme == 1 ? 3 : 1;
            for (int a = 0; a < nb; a++) {
                float ang = (arme == 1 ? (a - 1) * 0.22f : 0f);
                float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
                if (balles.size() > 90) break;
                Balle b = new Balle();
                b.x = bx;
                b.y = by;
                b.vx = (dx * c - dy * s) * 440f;
                b.vy = (dx * s + dy * c) * 440f;
                b.joueur = true;
                balles.add(b);
            }
        }

        private void eclater(float x, float y, int couleur, int n) {
            for (int i = 0; i < n; i++) {
                Eclat e = new Eclat();
                e.x = x;
                e.y = y;
                e.vx = (rnd.nextFloat() - 0.5f) * 260f;
                e.vy = (rnd.nextFloat() - 0.8f) * 200f;
                e.vie = 0.4f + rnd.nextFloat() * 0.4f;
                e.couleur = couleur;
                eclats.add(e);
            }
        }

        // ------------------------------------------------------------------ touches

        private boolean dans(float x, float y, float cx, float cy, float r) {
            float dx = x - cx, dy = y - cy;
            return dx * dx + dy * dy <= r * r * 1.5f;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                int i = e.getActionIndex();
                float lx = e.getX(i) / k, ly = e.getY(i) / k;
                if (etat == ATTENTE || (etat == FIN && SystemClock.uptimeMillis() - finTemps > 800)) {
                    demarrerPartie();
                } else if (etat == PAUSE) {
                    etat = JEU;
                    dernier = SystemClock.uptimeMillis();
                    invalidate();
                } else if (etat == JEU && lx > largeur - 52f && ly < 48f) {
                    pauser();
                }
            }
            boolean g = false, dr = false, s = false, t = false;
            int leve = a == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
            if (a != MotionEvent.ACTION_UP && a != MotionEvent.ACTION_CANCEL) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (i == leve) continue;
                    float lx = e.getX(i) / k, ly = e.getY(i) / k;
                    if (dans(lx, ly, 62f, 300f, 42f)) g = true;
                    else if (dans(lx, ly, 160f, 300f, 42f)) dr = true;
                    else if (dans(lx, ly, largeur - 175f, 305f, 40f)) s = true;
                    else if (dans(lx, ly, largeur - 72f, 292f, 48f)) t = true;
                }
            }
            gauche = g;
            droite = dr;
            saut = s;
            tir = t;
            return true;
        }

        // ------------------------------------------------------------------ dessin

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.033f, (now - dernier) / 1000f);
            dernier = now;
            if (etat == JEU) maj(dt);
            else if (etat == NIVEAU) majNiveau(dt);
            dessiner(c);
            if (etat == JEU || etat == NIVEAU) postInvalidateOnAnimation();
        }

        private void rect(Canvas c, float l, float t, float r, float b, int couleur) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(couleur);
            c.drawRect(l, t, r, b, p);
        }

        private void rond(Canvas c, float x, float y, float r, int couleur) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(couleur);
            c.drawCircle(x, y, r, p);
        }

        private void texte(Canvas c, String t, float x, float y, float taille, int couleur, Paint.Align al) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(couleur);
            p.setTextSize(taille);
            p.setTextAlign(al);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(t, x, y, p);
        }

        private void dessiner(Canvas c) {
            c.save();
            c.scale(k, k);

            p.setShader(ciel);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(0f, 0f, largeur, HAUT, p);
            p.setShader(null);

            c.save();
            c.translate(0f, SOL * (1f - ZOOM));     // zoom sur le monde, le sol reste à la même hauteur
            c.scale(ZOOM, ZOOM);

            // collines et arbres lointains (défilement plus lent)
            for (int i = -1; i < 6; i++) {
                float hx = i * 220f - (camX * 0.2f) % 220f;
                rond(c, hx + 110f, SOL + 60f, 150f, 0xFF1B4D4A);
            }
            for (int i = -1; i < 12; i++) {
                float tx = i * 110f - (camX * 0.5f) % 110f;
                rect(c, tx + 50f, SOL - 70f, tx + 58f, SOL, 0xFF3E2A1A);
                rond(c, tx + 54f, SOL - 80f, 26f, 0xFF1E6B3A);
            }

            c.save();
            c.translate(-camX, 0f);

            rect(c, camX, 338f, camX + largeur, HAUT, 0xFF1C5FA8);        // eau dans les trous
            for (int i = 0; i < segs.size(); i++) {
                Seg s = segs.get(i);
                if (s.x + s.w < camX || s.x > camX + largeur) continue;
                rect(c, s.x, SOL, s.x + s.w, HAUT, 0xFF6D4C2A);
                rect(c, s.x, SOL, s.x + s.w, SOL + 9f, 0xFF3E8E41);
            }
            for (int i = 0; i < paliers.size(); i++) {
                Palier l = paliers.get(i);
                if (l.x + l.w < camX || l.x > camX + largeur) continue;
                rect(c, l.x, l.y, l.x + l.w, l.y + 11f, 0xFF8D6E3F);
                rect(c, l.x, l.y, l.x + l.w, l.y + 4f, 0xFF3E8E41);
            }

            for (int i = 0; i < ennemis.size(); i++) {
                Ennemi e = ennemis.get(i);
                if (e.x + 80f < camX || e.x - 80f > camX + largeur) continue;
                dessinerEnnemi(c, e);
            }

            if (etat != FIN && (inv <= 0f || ((int) (temps * 12f)) % 2 == 0)) dessinerJoueur(c);

            for (int i = 0; i < balles.size(); i++) {
                Balle b = balles.get(i);
                if (b.joueur) {
                    rond(c, b.x, b.y, 3.6f, 0xFFFFEB3B);
                } else {
                    rond(c, b.x, b.y, 5f, 0xFFB71C1C);
                    rond(c, b.x, b.y, 3f, 0xFFFF8A80);
                }
            }
            for (int i = 0; i < eclats.size(); i++) {
                Eclat e = eclats.get(i);
                rond(c, e.x, e.y, 2.5f, e.couleur);
            }
            c.restore();
            c.restore();

            dessinerInterface(c);
            c.restore();
        }

        private void dessinerJoueur(Canvas c) {
            boolean marche = (gauche || droite) && psol;
            float pas = marche ? (float) Math.sin(temps * 16f) * 5f : 0f;
            rect(c, px - 6f + pas, py - 14f, px - 1f + pas, py, 0xFF1B3A1E);            // jambes
            rect(c, px + 1f - pas, py - 14f, px + 6f - pas, py, 0xFF1B3A1E);
            rect(c, px - 8f, py - 32f, px + 8f, py - 13f, 0xFF2E7D32);                  // corps
            rond(c, px, py - 37f, 6.5f, 0xFFFFCC99);                                    // tête
            rect(c, px - 7f, py - 41f, px + 7f, py - 38f, 0xFFD32F2F);                  // bandeau rouge
            float x2 = px + face * 19f;
            rect(c, Math.min(px, x2), py - 26f, Math.max(px, x2), py - 22f, 0xFF212121);  // arme
        }

        private void dessinerEnnemi(Canvas c, Ennemi e) {
            switch (e.type) {
                case RUN: {
                    float pas = (float) Math.sin(e.anim * 14f) * 5f;
                    rect(c, e.x - 6f + pas, e.y - 14f, e.x - 1f + pas, e.y, 0xFF263238);
                    rect(c, e.x + 1f - pas, e.y - 14f, e.x + 6f - pas, e.y, 0xFF263238);
                    rect(c, e.x - 8f, e.y - 30f, e.x + 8f, e.y - 13f, 0xFF607D8B);
                    rond(c, e.x, e.y - 35f, 6f, 0xFFFFCC99);
                    rect(c, e.x - 7f, e.y - 42f, e.x + 7f, e.y - 36f, 0xFF455A64);
                    break;
                }
                case TIREUR: {
                    int sens = px < e.x ? -1 : 1;
                    rect(c, e.x - 6f, e.y - 14f, e.x + 6f, e.y, 0xFF3E1A1A);
                    rect(c, e.x - 8f, e.y - 32f, e.x + 8f, e.y - 13f, 0xFF9E2A2A);
                    rond(c, e.x, e.y - 37f, 6f, 0xFFFFCC99);
                    rect(c, e.x - 7f, e.y - 43f, e.x + 7f, e.y - 38f, 0xFF4E1010);
                    float x2 = e.x + sens * 18f;
                    rect(c, Math.min(e.x, x2), e.y - 27f, Math.max(e.x, x2), e.y - 23f, 0xFF212121);
                    break;
                }
                case TOURELLE: {
                    rect(c, e.x - 15f, e.y - 10f, e.x + 15f, e.y, 0xFF546E7A);
                    rond(c, e.x, e.y - 12f, 11f, 0xFF78909C);
                    float ang = (float) Math.atan2((py - 22f) - (e.y - 12f), px - e.x);
                    p.setColor(0xFF263238);
                    p.setStrokeWidth(5f);
                    c.drawLine(e.x, e.y - 12f, e.x + (float) Math.cos(ang) * 20f, e.y - 12f + (float) Math.sin(ang) * 20f, p);
                    break;
                }
                case CAPSULE: {
                    rect(c, e.x - 14f, e.y - 14f, e.x + 14f, e.y - 2f, 0xFFFFC107);
                    rond(c, e.x - 14f, e.y - 8f, 6f, 0xFFFFC107);
                    rond(c, e.x + 14f, e.y - 8f, 6f, 0xFFFFC107);
                    rect(c, e.x - 8f, e.y - 22f, e.x - 2f, e.y - 14f, 0xFFFFFFFF);       // ailes
                    rect(c, e.x + 2f, e.y - 22f, e.x + 8f, e.y - 14f, 0xFFFFFFFF);
                    texte(c, "★", e.x, e.y - 3.5f, 12f, 0xFFB71C1C, Paint.Align.CENTER);
                    break;
                }
                default: {   // boss : gros robot à canons
                    rect(c, e.x - 55f, e.y - 70f, e.x + 55f, e.y - 12f, 0xFF37474F);
                    rect(c, e.x - 62f, e.y - 14f, e.x + 62f, e.y, 0xFF263238);
                    rect(c, e.x - 40f, e.y - 84f, e.x + 40f, e.y - 70f, 0xFF455A64);
                    int pulse = ((int) (temps * 6f)) % 2 == 0 ? 0xFFFF1744 : 0xFFFF8A80;
                    rond(c, e.x, e.y - 46f, 13f, pulse);
                    rect(c, e.x - 78f, e.y - 56f, e.x - 52f, e.y - 46f, 0xFF212121);     // canons
                    rect(c, e.x - 78f, e.y - 38f, e.x - 52f, e.y - 28f, 0xFF212121);
                    break;
                }
            }
        }

        private void dessinerBouton(Canvas c, float cx, float cy, float r, boolean actif, String t) {
            rond(c, cx, cy, r, actif ? 0x99FFFFFF : 0x55FFFFFF);
            texte(c, t, cx, cy + 7f, r > 44f ? 20f : 17f, 0xFF102030, Paint.Align.CENTER);
        }

        private void dessinerInterface(Canvas c) {
            texte(c, "SCORE " + score, 14f, 24f, 16f, 0xFFFFFFFF, Paint.Align.LEFT);
            texte(c, "RECORD " + Math.max(record, score), 14f, 44f, 12f, 0xFFFFF59D, Paint.Align.LEFT);
            texte(c, "NIVEAU " + niveau, largeur / 2f, 24f, 16f, 0xFFFFFFFF, Paint.Align.CENTER);
            StringBuilder v = new StringBuilder();
            for (int i = 0; i < vies; i++) v.append("♥ ");
            texte(c, v.toString(), largeur / 2f, 44f, 16f, 0xFFFF5252, Paint.Align.CENTER);
            texte(c, arme == 1 ? "TIR LARGE" : arme == 2 ? "TIR RAPIDE" : "TIR NORMAL", largeur - 62f, 24f, 12f, 0xFFB3E5FC, Paint.Align.RIGHT);
            rect(c, largeur - 44f, 10f, largeur - 38f, 34f, 0xCCFFFFFF);                  // bouton pause
            rect(c, largeur - 32f, 10f, largeur - 26f, 34f, 0xCCFFFFFF);

            for (int i = 0; i < ennemis.size(); i++) {                                     // vie du boss
                Ennemi e = ennemis.get(i);
                if (e.type != BOSS || e.x - camX > largeur / ZOOM - 10f) continue;
                float w = 220f, x0 = (largeur - w) / 2f;
                rect(c, x0, 56f, x0 + w, 64f, 0x99000000);
                rect(c, x0, 56f, x0 + w * Math.max(0, e.pv) / (float) e.pvMax, 64f, 0xFFFF1744);
            }

            if (msgT > 0f && etat == JEU) texte(c, msg, largeur / 2f, 100f, 18f, 0xFFFFEB3B, Paint.Align.CENTER);

            dessinerBouton(c, 62f, 300f, 42f, gauche, "◀");
            dessinerBouton(c, 160f, 300f, 42f, droite, "▶");
            dessinerBouton(c, largeur - 175f, 305f, 40f, saut, "SAUT");
            dessinerBouton(c, largeur - 72f, 292f, 48f, tir, "TIR");

            if (etat == ATTENTE) {
                voile(c);
                texte(c, "COMMANDO KOUAMÉ", largeur / 2f, 140f, 36f, 0xFFFFEB3B, Paint.Align.CENTER);
                texte(c, "Cours, saute et tire. Détruis le robot de chaque niveau !", largeur / 2f, 178f, 15f, 0xFFFFFFFF, Paint.Align.CENTER);
                texte(c, "★ Capsules : tir large ou tir rapide", largeur / 2f, 202f, 14f, 0xFFFFE082, Paint.Align.CENTER);
                texte(c, "Touchez l'écran pour commencer", largeur / 2f, 246f, 18f, 0xFFFFFFFF, Paint.Align.CENTER);
            } else if (etat == PAUSE) {
                voile(c);
                texte(c, "PAUSE", largeur / 2f, 160f, 34f, 0xFFFFEB3B, Paint.Align.CENTER);
                texte(c, "Touchez l'écran pour reprendre", largeur / 2f, 200f, 17f, 0xFFFFFFFF, Paint.Align.CENTER);
            } else if (etat == NIVEAU) {
                texte(c, "NIVEAU " + niveau + " TERMINÉ !", largeur / 2f, 150f, 32f, 0xFFFFEB3B, Paint.Align.CENTER);
                texte(c, "Bonus 2000 points", largeur / 2f, 184f, 17f, 0xFFFFFFFF, Paint.Align.CENTER);
            } else if (etat == FIN) {
                voile(c);
                texte(c, "PARTIE TERMINÉE", largeur / 2f, 140f, 34f, 0xFFFF5252, Paint.Align.CENTER);
                texte(c, "Score : " + score + "   Record : " + Math.max(record, score), largeur / 2f, 180f, 18f, 0xFFFFFFFF, Paint.Align.CENTER);
                texte(c, "Touchez l'écran pour rejouer", largeur / 2f, 224f, 17f, 0xFFFFE082, Paint.Align.CENTER);
            }
        }

        private void voile(Canvas c) {
            rect(c, 0f, 0f, largeur, HAUT, 0xAA000000);
        }
    }
}
