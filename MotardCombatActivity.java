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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Combat Mortel : combat à pied (en paysage), dans l'esprit des jeux de combat d'arcade.
 *  - mode « robot » : on affronte le robot (niveau 1 à 10), calculé sur le téléphone.
 *  - mode « ligne » : on affronte un autre joueur ; chaque téléphone envoie sa position à la base ~6 fois par
 *    seconde et reçoit celle de l'adversaire. Les points de vie sont gardés par la base (duel_sync).
 * Commandes : ◀ ▶, SAUT, POING (rapide), PIED (plus fort, plus lent), GARDE (maintenue : réduit les coups),
 * SPÉCIAL (boule d'énergie, recharge 4 s).
 */
public class MotardCombatActivity extends Activity {

    private VueCombat vue;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersif();
        vue = new VueCombat(this, getIntent());
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vue != null) vue.arreter();
    }

    // =====================================================================================================

    private static final class VueCombat extends View {

        // Repère de jeu : 360 de haut, arène de 640 de large (identique sur tous les téléphones).
        private static final float HAUT = 360f, ARENE = 640f, SOL = 300f, GRAV = 1500f;
        private static final float XMIN = 60f, XMAX = ARENE - 60f;
        private static final int ATTENTE = 0, COMPTE = 1, COMBAT = 2, FIN = 3, PAUSE = 4;
        // actions envoyées à l'adversaire
        private static final int A_RIEN = 0, A_POING = 1, A_PIED = 2, A_TOUCHE = 3, A_GARDE = 4, A_SPECIAL = 5;

        private static final class Pilote {
            JeuMotardActivity.Perso perso;
            float x, y = SOL, vx, vy, pv, pvMax = 100f, marche;
            int face = 1, act, typeAtt;
            float tAtt, cdAtt, tSpec, cdSpec, tTouche;
            boolean attDonne, specLancee;
            boolean gauche, droite, saut, poing, pied, special, garde;
            boolean auSol() { return y >= SOL - 0.5f; }
            boolean enGarde() { return act == A_GARDE; }
        }

        private static final class Boule {
            float x, y, vx, degats;
            boolean miens, degatsReels;
            int couleur;
        }

        private final Context ctx;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path chemin = new Path();
        private final RectF r = new RectF();
        private final Random rnd = new Random();
        private final ArrayList<Boule> boules = new ArrayList<>();

        private final boolean enLigne;
        private final String duel;
        private final boolean hote;
        private final String advNom;
        private int niveau, persoMoi, persoAdv;

        private final Pilote moi = new Pilote(), adv = new Pilote();
        private int phase = ATTENTE;
        private boolean gagne;
        private float k = 1f, ox, largeurEcran = 640f, temps, tFin, flashMoi, flashAdv;
        private long dernier, debutLocal;
        private String message = "";
        private Shader fond;

        // robot
        private float tDecision;
        private float iaReact, iaVit, iaAttaque, iaGarde, iaDegats;

        // en ligne
        private volatile boolean actif = true;
        private final AtomicInteger degatsEnAttente = new AtomicInteger(0);
        private volatile float mx, my;
        private volatile int mface = 1, mact;
        private volatile boolean recu;
        private volatile String etatServeur = "en_cours";
        private volatile int debutDansMs, pvServeur = 100, advPvServeur = 100;
        private volatile boolean serveurGagne, serveurFini;
        private volatile float ax, ay;
        private volatile int aface = -1, aact, aperso = -1;
        private volatile int echecs;
        private int dernierPvMoi = 100, dernierActAdv = 0;

        // boutons (pixels)
        private final RectF bG = new RectF(), bD = new RectF(), bGarde = new RectF(), bSaut = new RectF(),
                bPoing = new RectF(), bPied = new RectF(), bSpec = new RectF(), bQuit = new RectF(),
                bA = new RectF(), bB = new RectF();

        VueCombat(Context c, android.content.Intent it) {
            super(c);
            ctx = c;
            enLigne = "ligne".equals(it.getStringExtra("mode"));
            duel = it.getStringExtra("duel") == null ? "" : it.getStringExtra("duel");
            hote = it.getBooleanExtra("hote", true);
            advNom = it.getStringExtra("adv_nom") == null ? "Adversaire" : it.getStringExtra("adv_nom");
            niveau = Math.max(1, Math.min(JeuMotardActivity.NIVEAUX, it.getIntExtra("niveau", 1)));
            persoMoi = clampPerso(it.getIntExtra("perso", 0));
            persoAdv = clampPerso(it.getIntExtra("adv_perso", 0));
            preparer();
            if (enLigne) demarrerSynchro();
        }

        private static int clampPerso(int n) {
            return Math.max(0, Math.min(JeuMotardActivity.PERSOS.length - 1, n));
        }

        // ------------------------------------------------------------------ préparation

        private void preparer() {
            moi.perso = JeuMotardActivity.PERSOS[persoMoi];
            moi.pvMax = 100f;
            moi.pv = 100f;
            moi.x = (!enLigne || hote) ? 190f : ARENE - 190f;
            moi.face = moi.x < ARENE / 2 ? 1 : -1;
            adv.x = ARENE - moi.x;
            adv.face = -moi.face;
            ax = adv.x;
            ay = SOL;
            aface = adv.face;
            if (enLigne) {
                adv.perso = JeuMotardActivity.PERSOS[persoAdv];
                adv.pvMax = 100f;
                adv.pv = 100f;
            } else {
                adv.perso = JeuMotardActivity.PERSOS[(persoMoi + 1 + niveau) % JeuMotardActivity.PERSOS.length];
                adv.pvMax = 70f + 10f * niveau;
                adv.pv = adv.pvMax;
                iaReact = 0.56f - 0.046f * niveau;           // délai entre deux décisions (plus petit = plus vif)
                iaVit = 0.55f + 0.05f * niveau;
                iaAttaque = 0.22f + 0.07f * niveau;
                iaGarde = 0.04f + 0.08f * niveau;
                iaDegats = 0.60f + 0.07f * niveau;
            }
            for (Pilote q : new Pilote[]{moi, adv}) {
                q.y = SOL;
                q.vx = q.vy = 0;
                q.tAtt = q.cdAtt = q.tSpec = q.cdSpec = q.tTouche = 0;
                q.gauche = q.droite = q.saut = q.poing = q.pied = q.special = q.garde = false;
                q.act = 0;
            }
            boules.clear();
            dernierPvMoi = 100;
            dernierActAdv = 0;
            phase = enLigne ? ATTENTE : COMPTE;
            debutLocal = SystemClock.elapsedRealtime() + 3200;
            gagne = false;
            message = "";
            dernier = 0;
            tDecision = 0.5f;
        }

        void pauser() {
            if (!enLigne && phase == COMBAT) phase = PAUSE;
            invalidate();
        }

        void arreter() {
            actif = false;
            if (enLigne && !duel.isEmpty() && phase != FIN) {
                final String d = duel;
                new Thread(() -> MotardReseau.quitter(d)).start();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            k = h / HAUT;
            largeurEcran = w / k;
            ox = (largeurEcran - ARENE) / 2f;
            fond = new LinearGradient(0, 0, 0, HAUT, 0xFF1A0505, 0xFF6B1B12, Shader.TileMode.CLAMP);
            float m = h * 0.04f, t = h * 0.2f, g = t * 0.15f;
            bG.set(m, h - m - t, m + t, h - m);
            bD.set(m + t + g, h - m - t, m + 2 * t + g, h - m);
            bGarde.set(m + (t + g) / 2f, h - m - 2 * t - g, m + (t + g) / 2f + t, h - m - t - g);
            bPoing.set(w - m - t, h - m - t, w - m, h - m);
            bPied.set(w - m - 2 * t - g, h - m - t, w - m - t - g, h - m);
            bSaut.set(w - m - t, h - m - 2 * t - g, w - m, h - m - t - g);
            bSpec.set(w - m - 2 * t - g, h - m - 2 * t - g, w - m - t - g, h - m - t - g);
            bQuit.set(w / 2f - t * 0.35f, m, w / 2f + t * 0.35f, m + t * 0.7f);
        }

        // ------------------------------------------------------------------ boucle

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.elapsedRealtime();
            float dt = dernier == 0 ? 0.016f : Math.min(0.033f, (now - dernier) / 1000f);
            dernier = now;
            temps += dt;
            avancer(dt, now);

            c.save();
            c.scale(k, k);
            dessinerDecor(c);
            c.translate(ox, 0);
            dessinerCombattant(c, adv, flashAdv, phase == FIN && gagne);
            dessinerCombattant(c, moi, flashMoi, phase == FIN && !gagne);
            for (Boule b : boules) dessinerBoule(c, b);
            c.restore();

            dessinerInterface(c, now);
            postInvalidateOnAnimation();
        }

        private void avancer(float dt, long now) {
            flashMoi = Math.max(0, flashMoi - dt);
            flashAdv = Math.max(0, flashAdv - dt);

            if (enLigne) {
                mx = moi.x;
                my = moi.y;
                mface = moi.face;
                mact = moi.act;
            }

            switch (phase) {
                case ATTENTE:
                    if (enLigne && recu) {
                        debutLocal = SystemClock.elapsedRealtime() + debutDansMs;
                        phase = COMPTE;
                    }
                    if (enLigne && "termine".equals(etatServeur)) terminer(serveurGagne);
                    break;
                case COMPTE:
                    if (enLigne && recu) debutLocal = SystemClock.elapsedRealtime() + debutDansMs;
                    if (now >= debutLocal) {
                        phase = COMBAT;
                        message = "";
                    }
                    suivreAdversaireLigne(dt);
                    break;
                case COMBAT:
                    jouer(dt);
                    break;
                case FIN:
                    tFin += dt;
                    break;
                default:
                    break;
            }
        }

        // ------------------------------------------------------------------ règles du combat

        private void jouer(float dt) {
            if (!enLigne) decisionsRobot(dt);
            majCombattant(moi, adv, dt, true);
            if (enLigne) suivreAdversaireLigne(dt);
            else majCombattant(adv, moi, dt, false);
            majBoules(dt);

            if (enLigne) {
                // Mes points de vie viennent de la base.
                int pv = pvServeur;
                if (pv < dernierPvMoi) {
                    if (!moi.enGarde()) {
                        moi.tTouche = 0.25f;
                        moi.vx = -moi.face * 200f;
                    }
                    flashMoi = 0.25f;
                    Sons.jouer(ctx, Sons.PERDRE_VIE);
                }
                dernierPvMoi = pv;
                moi.pv = pv;
                adv.pv = advPvServeur;
                if (serveurFini || "termine".equals(etatServeur)) terminer(serveurGagne);
                else if ("annule".equals(etatServeur) || "introuvable".equals(etatServeur)) terminer(true);
                else if (echecs > 40) {
                    message = "Connexion perdue";
                    terminer(false);
                }
            } else {
                if (adv.pv <= 0) terminer(true);
                else if (moi.pv <= 0) terminer(false);
            }
        }

        /** Mouvement, saut, coups et attaque spéciale d'un combattant. */
        private void majCombattant(Pilote q, Pilote autre, float dt, boolean estMoi) {
            q.cdAtt = Math.max(0, q.cdAtt - dt);
            q.cdSpec = Math.max(0, q.cdSpec - dt);
            q.tAtt = Math.max(0, q.tAtt - dt);
            q.tSpec = Math.max(0, q.tSpec - dt);
            q.tTouche = Math.max(0, q.tTouche - dt);

            boolean etourdi = q.tTouche > 0;
            boolean occupe = q.tAtt > 0 || q.tSpec > 0;
            boolean garde = q.garde && q.auSol() && !etourdi && !occupe;
            float vit = 170f * q.perso.vitesse;

            if (Math.abs(autre.x - q.x) > 8f) q.face = autre.x > q.x ? 1 : -1;

            if (garde || etourdi || occupe) {
                q.vx *= (1f - Math.min(1f, dt * (etourdi ? 5f : 12f)));
            } else {
                float cible = ((q.droite ? 1 : 0) - (q.gauche ? 1 : 0)) * vit;
                q.vx += (cible - q.vx) * Math.min(1f, dt * 10f);
            }

            if (!etourdi && !occupe && !garde) {
                if (q.saut && q.auSol()) {
                    q.vy = -560f * q.perso.saut;
                    Sons.jouer(ctx, Sons.SAUT);
                }
                if (q.poing && q.cdAtt <= 0) {
                    q.typeAtt = A_POING; q.tAtt = 0.28f; q.cdAtt = 0.36f; q.attDonne = false;
                    Sons.jouer(ctx, Sons.TIR);
                } else if (q.pied && q.cdAtt <= 0) {
                    q.typeAtt = A_PIED; q.tAtt = 0.44f; q.cdAtt = 0.58f; q.attDonne = false;
                    Sons.jouer(ctx, Sons.TIR);
                } else if (q.special && q.cdSpec <= 0) {
                    q.tSpec = 0.5f; q.cdSpec = 4f; q.specLancee = false;
                }
            }

            q.x += q.vx * dt;
            if (q.x < XMIN) { q.x = XMIN; q.vx = 0; }
            if (q.x > XMAX) { q.x = XMAX; q.vx = 0; }
            q.vy += GRAV * dt;
            q.y += q.vy * dt;
            if (q.y >= SOL) { q.y = SOL; q.vy = 0; }
            q.marche += Math.abs(q.vx) * dt * 0.05f;
            q.act = q.tTouche > 0 ? A_TOUCHE : q.tSpec > 0 ? A_SPECIAL : q.tAtt > 0 ? q.typeAtt : garde ? A_GARDE : A_RIEN;

            // --- coups portés : moi et le robot sont calculés ici ; l'adversaire en ligne est calculé sur son téléphone
            if (!estMoi && enLigne) return;
            float dy = Math.abs(q.y - autre.y);
            float devant = (autre.x - q.x) * q.face;
            float dist = Math.abs(autre.x - q.x);
            float force = estMoi ? q.perso.force : q.perso.force * iaDegats;

            if (q.tAtt > 0 && !q.attDonne) {
                if (q.typeAtt == A_POING && q.tAtt < 0.20f && q.tAtt > 0.08f && dy < 90f && devant > -10f && dist < 96f) {
                    q.attDonne = true;
                    toucher(q, autre, 6f * force, 150f, estMoi);
                } else if (q.typeAtt == A_PIED && q.tAtt < 0.28f && q.tAtt > 0.14f && dy < 100f && devant > -10f && dist < 118f) {
                    q.attDonne = true;
                    toucher(q, autre, 10f * force, 260f, estMoi);
                }
            }
            if (q.tSpec > 0 && q.tSpec < 0.3f && !q.specLancee) {
                q.specLancee = true;
                Boule b = new Boule();
                b.x = q.x + q.face * 62f;
                b.y = q.y - 104f;
                b.vx = q.face * 330f;
                b.degats = 12f * force;
                b.miens = estMoi;
                b.degatsReels = true;
                b.couleur = q.perso.couleur;
                boules.add(b);
                Sons.jouer(ctx, Sons.EXPLOSION);
            }
        }

        private void majBoules(float dt) {
            for (int i = boules.size() - 1; i >= 0; i--) {
                Boule b = boules.get(i);
                b.x += b.vx * dt;
                boolean fini = b.x < -20f || b.x > ARENE + 20f;
                if (b.degatsReels) {
                    Pilote cible = b.miens ? adv : moi;
                    Pilote tireur = b.miens ? moi : adv;
                    if (Math.abs(b.x - cible.x) < 46f && Math.abs(b.y - (cible.y - 95f)) < 95f) {
                        if (!(enLigne && !b.miens)) toucher(tireur, cible, b.degats, 200f, b.miens);
                        fini = true;
                    }
                }
                if (fini) boules.remove(i);
            }
        }

        /** Un coup arrive. En garde, il fait beaucoup moins mal. */
        private void toucher(Pilote qui, Pilote cible, float degats, float recul, boolean parMoi) {
            boolean garde = cible.enGarde();
            if (garde) {
                degats *= 0.2f;
                recul *= 0.3f;
            }
            if (enLigne) {
                // Mon coup sur l'adversaire : envoyé à la base, qui retire les points de vie.
                degatsEnAttente.addAndGet(Math.max(1, Math.min(16, Math.round(degats))));
                if (!garde) {
                    cible.tTouche = 0.25f;
                    cible.vx = qui.face * recul * 0.5f;
                }
                flashAdv = 0.25f;
                Sons.jouer(ctx, Sons.TOUCHE);
                return;
            }
            cible.pv = Math.max(0, cible.pv - degats);
            if (!garde) {
                cible.tTouche = 0.3f;
                cible.vx = qui.face * recul;
                cible.tAtt = 0;
                cible.tSpec = 0;
            }
            if (parMoi) flashAdv = 0.25f; else flashMoi = 0.25f;
            Sons.jouer(ctx, parMoi ? Sons.TOUCHE : Sons.PERDRE_VIE);
        }

        private void terminer(boolean victoire) {
            if (phase == FIN) return;
            phase = FIN;
            gagne = victoire;
            tFin = 0;
            moi.gauche = moi.droite = moi.saut = moi.poing = moi.pied = moi.special = moi.garde = false;
            Sons.jouer(ctx, victoire ? Sons.GAGNE : Sons.PERDU);
            if (!enLigne && victoire) {
                JeuMotardActivity.debloquer(ctx, niveau + 1);
                JeuxActivity.enregistrer(ctx, "motard", niveau * 100 + Math.round(moi.pv));
            }
            if (enLigne) actif = false;
        }

        // ------------------------------------------------------------------ le robot

        private void decisionsRobot(float dt) {
            tDecision -= dt;
            adv.saut = adv.poing = adv.pied = adv.special = false;
            if (tDecision > 0 || adv.tTouche > 0) return;
            tDecision = iaReact * (0.7f + rnd.nextFloat() * 0.6f);

            float dx = moi.x - adv.x;
            float dist = Math.abs(dx);
            int sens = dx > 0 ? 1 : -1;
            adv.gauche = adv.droite = adv.garde = false;

            boolean menace = (moi.act == A_POING || moi.act == A_PIED || moi.act == A_SPECIAL) && dist < 150f;
            if (menace) {
                float t = rnd.nextFloat();
                if (t < iaGarde) { adv.garde = true; tDecision = Math.max(tDecision, 0.35f); return; }
                if (t < iaGarde + 0.06f * niveau && adv.auSol()) { adv.saut = true; return; }
            }
            if (dist > 90f) {
                if (rnd.nextFloat() < iaVit) { if (sens > 0) adv.droite = true; else adv.gauche = true; }
                if (niveau >= 3 && dist > 180f && adv.cdSpec <= 0 && rnd.nextFloat() < 0.12f + 0.03f * niveau) adv.special = true;
            } else {
                if (rnd.nextFloat() < iaAttaque) {
                    if (rnd.nextFloat() < 0.3f + 0.04f * niveau && dist > 60f) adv.pied = true; else adv.poing = true;
                } else if (niveau >= 4 && rnd.nextFloat() < 0.2f) {
                    adv.garde = true;
                } else if (niveau >= 2 && rnd.nextFloat() < 0.25f) {
                    if (sens > 0) adv.gauche = true; else adv.droite = true;   // recule un peu
                }
            }
        }

        // ------------------------------------------------------------------ l'adversaire en ligne

        private void suivreAdversaireLigne(float dt) {
            if (!enLigne) return;
            float f = Math.min(1f, dt * 14f);
            float avant = adv.x;
            adv.x += (ax - adv.x) * f;
            adv.y += (ay - adv.y) * f;
            adv.face = aface >= 0 ? 1 : -1;
            adv.marche += Math.abs(adv.x - avant) * 0.12f;
            int a = aact;
            adv.act = a;
            adv.typeAtt = a;
            // petite animation : la durée de chaque action est recréée ici (affichage seulement)
            adv.tAtt = (a == A_POING) ? 0.16f : (a == A_PIED) ? 0.22f : 0;
            adv.tSpec = a == A_SPECIAL ? 0.4f : 0;
            adv.tTouche = a == A_TOUCHE ? 0.2f : 0;
            if (a == A_SPECIAL && dernierActAdv != A_SPECIAL) {      // boule d'énergie de l'adversaire (décor seulement)
                Boule b = new Boule();
                b.x = adv.x + adv.face * 62f;
                b.y = adv.y - 104f;
                b.vx = adv.face * 330f;
                b.miens = false;
                b.degatsReels = false;
                b.couleur = adv.perso.couleur;
                boules.add(b);
            }
            dernierActAdv = a;
            int ap = aperso;
            if (ap >= 0 && ap < JeuMotardActivity.PERSOS.length) adv.perso = JeuMotardActivity.PERSOS[ap];
        }

        private void demarrerSynchro() {
            new Thread(() -> {
                while (actif) {
                    long t0 = SystemClock.elapsedRealtime();
                    int deg = degatsEnAttente.getAndSet(0);
                    JSONObject o = MotardReseau.sync(duel, mx, my, mface, mact, Math.min(16, deg));
                    if (o == null) {
                        echecs++;
                        if (deg > 0) degatsEnAttente.addAndGet(deg);
                    } else {
                        echecs = 0;
                        etatServeur = o.optString("etat", "en_cours");
                        debutDansMs = o.optInt("debut_dans_ms", 0);
                        pvServeur = o.optInt("mon_pv", 100);
                        serveurGagne = o.optBoolean("gagnant_moi", false);
                        serveurFini = "termine".equals(etatServeur);
                        JSONObject a = o.optJSONObject("adv");
                        if (a != null) {
                            ax = (float) a.optDouble("x", ax);
                            ay = (float) a.optDouble("y", ay);
                            aface = a.optInt("face", aface);
                            aact = a.optInt("act", 0);
                            advPvServeur = a.optInt("pv", 100);
                            if (!a.isNull("perso")) aperso = a.optInt("perso", -1);
                        }
                        recu = true;
                    }
                    long reste = 165 - (SystemClock.elapsedRealtime() - t0);
                    if (reste > 0) {
                        try { Thread.sleep(reste); } catch (InterruptedException e) { return; }
                    }
                    if (phase == FIN && (serveurFini || "annule".equals(etatServeur))) return;
                }
            }).start();
        }

        // ------------------------------------------------------------------ commandes tactiles

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                float x = e.getX(), y = e.getY();
                if (phase == FIN) {
                    if (bA.contains(x, y)) {
                        if (enLigne) ((Activity) ctx).finish();
                        else {
                            if (gagne && niveau < JeuMotardActivity.NIVEAUX) niveau++;
                            preparer();
                        }
                    } else if (bB.contains(x, y)) {
                        ((Activity) ctx).finish();
                    }
                    return true;
                }
                if (phase == PAUSE) {
                    phase = COMBAT;
                    return true;
                }
                if (bQuit.contains(x, y)) {
                    ((Activity) ctx).finish();
                    return true;
                }
            }
            boolean g = false, d = false, s = false, po = false, pi = false, sp = false, ga = false;
            boolean fini = a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
            int saute = a == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
            if (!fini) {
                float m = getHeight() * 0.02f;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (i == saute) continue;
                    float x = e.getX(i), y = e.getY(i);
                    if (dans(bG, x, y, m)) g = true;
                    if (dans(bD, x, y, m)) d = true;
                    if (dans(bGarde, x, y, m)) ga = true;
                    if (dans(bSaut, x, y, m)) s = true;
                    if (dans(bPoing, x, y, m)) po = true;
                    if (dans(bPied, x, y, m)) pi = true;
                    if (dans(bSpec, x, y, m)) sp = true;
                }
            }
            if (phase == COMBAT || phase == COMPTE) {
                moi.gauche = g;
                moi.droite = d;
                moi.saut = s;
                moi.poing = po;
                moi.pied = pi;
                moi.special = sp;
                moi.garde = ga;
            }
            return true;
        }

        private static boolean dans(RectF rc, float x, float y, float m) {
            return x >= rc.left - m && x <= rc.right + m && y >= rc.top - m && y <= rc.bottom + m;
        }

        // ------------------------------------------------------------------ dessin : décor (temple)

        private void dessinerDecor(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(fond);
            c.drawRect(0, 0, largeurEcran, HAUT, p);
            p.setShader(null);

            // lune rouge
            p.setColor(0xFFFFB74D);
            c.drawCircle(largeurEcran / 2f, 95f, 46f, p);
            p.setColor(0x33FFFFFF);
            c.drawCircle(largeurEcran / 2f - 12f, 85f, 14f, p);

            // montagnes
            p.setColor(0xFF2A0C0A);
            chemin.reset();
            chemin.moveTo(0, SOL - 40f);
            for (int i = 0; i <= 8; i++) chemin.lineTo(i * largeurEcran / 8f, SOL - 70f - ((i * 53) % 70));
            chemin.lineTo(largeurEcran, SOL - 40f);
            chemin.close();
            c.drawPath(chemin, p);

            // piliers du temple et torches
            for (int i = 0; i < 4; i++) {
                float px = largeurEcran * (0.1f + 0.267f * i);
                p.setColor(0xFF3B1612);
                c.drawRect(px - 14f, 60f, px + 14f, SOL, p);
                p.setColor(0xFF4E1F18);
                c.drawRect(px - 20f, 52f, px + 20f, 66f, p);
                float fl = (float) Math.sin(temps * 11f + i) * 3f;
                p.setColor(0xFFFF6F00);
                c.drawCircle(px, 130f, 9f + fl, p);
                p.setColor(0xFFFFEB3B);
                c.drawCircle(px, 132f, 5f + fl * 0.5f, p);
            }

            // sol en pierre
            p.setColor(0xFF2B1A17);
            c.drawRect(0, SOL, largeurEcran, HAUT, p);
            p.setColor(0xFF4A2E28);
            c.drawRect(0, SOL, largeurEcran, SOL + 4f, p);
            p.setColor(0xFF3A231F);
            for (float lx = 0; lx < largeurEcran; lx += 80f) c.drawRect(lx, SOL + 4f, lx + 2f, HAUT, p);
        }

        // ------------------------------------------------------------------ dessin : combattants

        private static int avecAlpha(int couleur, int a) {
            return (couleur & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
        }

        private void membre(Canvas c, float x1, float y1, float x2, float y2, float epaisseur, int couleur) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(epaisseur);
            p.setColor(couleur);
            c.drawLine(x1, y1, x2, y2, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void dessinerCombattant(Canvas c, Pilote q, float flash, boolean ko) {
            if (q.perso == null) return;
            int coul = q.perso.couleur;
            boolean gorille = q.perso == JeuMotardActivity.PERSOS[2];
            boolean fantome = q.perso == JeuMotardActivity.PERSOS[3];
            boolean ninja = q.perso == JeuMotardActivity.PERSOS[1];
            boolean roi = q.perso == JeuMotardActivity.PERSOS[0];
            int alpha = fantome ? 200 : 255;
            if (q.tTouche > 0 && ((int) (temps * 30f)) % 2 == 0) alpha = 120;
            int peau = avecAlpha(gorille ? 0xFF4E342E : ninja ? 0xFF212121 : fantome ? 0xFFE1F5FE : 0xFFFFCC80, alpha);
            int tenue = avecAlpha(gorille ? 0xFF2E7D32 : ninja ? 0xFF311B92 : fantome ? 0xFF81D4FA : 0xFFC62828, alpha);
            int pantalon = avecAlpha(ninja ? 0xFF1A1A1A : gorille ? 0xFF3E2723 : fantome ? 0xFFB3E5FC : 0xFF212121, alpha);
            int ceinture = avecAlpha(coul, alpha);
            float ep = gorille ? 13f : 10f;
            float echelle = gorille ? 1.5f : 1.35f;

            // ombre
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x66000000);
            float oh = Math.max(0.4f, 1f - (SOL - q.y) / 220f);
            r.set(q.x - 46f * oh, SOL + 1f, q.x + 46f * oh, SOL + 9f);
            c.drawOval(r, p);

            c.save();
            c.translate(q.x, q.y);
            c.scale(q.face * echelle, echelle);
            if (ko) {
                float ang = 88f * Math.min(1f, tFin * 2.5f);
                c.rotate(-ang);
            } else if (q.act == A_TOUCHE) {
                c.rotate(-10f);
            }

            float pas = (float) Math.sin(q.marche) * 9f;
            boolean air = !q.auSol();
            boolean pied = q.act == A_PIED;
            boolean poing = q.act == A_POING;
            boolean garde = q.act == A_GARDE;
            boolean spec = q.act == A_SPECIAL;
            float hancheY = garde ? -56f : -64f;

            // jambes
            if (air) {
                membre(c, 0, hancheY, 14f, -38f, ep, pantalon);
                membre(c, 14f, -38f, 4f, -22f, ep, pantalon);
                membre(c, 0, hancheY, -8f, -34f, ep, pantalon);
                membre(c, -8f, -34f, -16f, -20f, ep, pantalon);
            } else {
                float prog = pied ? 1f - Math.max(0f, Math.min(1f, q.tAtt / 0.44f)) : 0f;
                float kick = pied ? (float) Math.sin(prog * Math.PI) : 0f;          // 0 → 1 → 0
                membre(c, 0, hancheY, -14f + pas, 0f, ep, pantalon);                   // jambe arrière
                if (pied) {
                    membre(c, 0, hancheY, 20f + 56f * kick, hancheY + 6f - 20f * kick, ep, pantalon);
                    p.setColor(peau);
                    c.drawCircle(20f + 60f * kick, hancheY + 4f - 20f * kick, ep * 0.6f, p);
                } else {
                    membre(c, 0, hancheY, 18f - pas, 0f, ep, pantalon);
                }
            }

            // torse et ceinture
            membre(c, 0, hancheY, 3f, -112f, gorille ? 22f : 17f, tenue);
            membre(c, -6f, hancheY + 2f, 8f, hancheY + 2f, 5f, ceinture);

            // bras
            float sx = 4f, sy = -104f;
            if (garde) {
                membre(c, sx, sy, 26f, -96f, ep - 2f, peau);
                membre(c, 26f, -96f, 24f, -124f, ep - 2f, peau);
                membre(c, sx, sy + 4f, 30f, -90f, ep - 2f, peau);
                membre(c, 30f, -90f, 28f, -118f, ep - 2f, peau);
            } else if (poing) {
                float prog = 1f - Math.max(0f, Math.min(1f, q.tAtt / 0.28f));
                float ext = (float) Math.sin(prog * Math.PI);
                membre(c, sx, sy, 22f + 50f * ext, -102f, ep - 1f, peau);
                p.setColor(peau);
                c.drawCircle(26f + 50f * ext, -102f, ep * 0.7f, p);
                membre(c, sx, sy + 4f, 22f, -88f, ep - 2f, peau);
            } else if (spec) {
                membre(c, sx, sy, 50f, -100f, ep - 1f, peau);
                membre(c, sx, sy + 4f, 46f, -92f, ep - 1f, peau);
                p.setColor(avecAlpha(coul, 170));
                c.drawCircle(58f, -96f, 12f + (float) Math.sin(temps * 30f) * 3f, p);
            } else {
                membre(c, sx, sy, 22f, -92f, ep - 2f, peau);
                membre(c, 22f, -92f, 30f, -110f, ep - 2f, peau);
                membre(c, sx, sy + 4f, 18f, -86f, ep - 2f, peau);
                membre(c, 18f, -86f, 26f, -102f, ep - 2f, peau);
            }

            // tête
            p.setStyle(Paint.Style.FILL);
            p.setColor(peau);
            float rt = gorille ? 14f : 12f;
            c.drawCircle(6f, -126f, rt, p);
            if (ninja) {                                              // masque et foulard
                p.setColor(avecAlpha(0xFF311B92, alpha));
                r.set(-6f, -140f, 18f, -122f);
                c.drawArc(r, 180f, 180f, true, p);
                p.setColor(avecAlpha(0xFFE53935, alpha));
                r.set(-6f, -134f, 18f, -127f);
                c.drawRect(r, p);
                chemin.reset();
                chemin.moveTo(-6f, -131f);
                chemin.lineTo(-30f, -128f - (float) Math.sin(temps * 14f) * 5f);
                chemin.lineTo(-26f, -122f);
                chemin.lineTo(-6f, -128f);
                chemin.close();
                c.drawPath(chemin, p);
            } else if (roi) {                                         // couronne
                p.setColor(avecAlpha(0xFFFFD54F, alpha));
                chemin.reset();
                chemin.moveTo(-3f, -137f);
                chemin.lineTo(-3f, -149f);
                chemin.lineTo(2f, -142f);
                chemin.lineTo(6f, -151f);
                chemin.lineTo(10f, -142f);
                chemin.lineTo(15f, -149f);
                chemin.lineTo(15f, -137f);
                chemin.close();
                c.drawPath(chemin, p);
            } else if (gorille) {
                p.setColor(avecAlpha(0xFF1B1B1B, alpha));
                r.set(-6f, -142f, 18f, -128f);
                c.drawArc(r, 180f, 180f, true, p);
            } else if (fantome) {
                p.setColor(avecAlpha(0xFFFFFFFF, 140));
                c.drawCircle(6f, -128f, rt + 6f, p);
            }
            p.setColor(avecAlpha(0xFF000000, alpha));                 // œil
            c.drawCircle(12f, -127f, 2.2f, p);
            if (flash > 0) {
                p.setColor(0x66FF1744);
                r.set(-40f, -150f, 40f, 0f);
                c.drawOval(r, p);
            }
            c.restore();
        }

        private void dessinerBoule(Canvas c, Boule b) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(avecAlpha(b.couleur, 90));
            c.drawCircle(b.x - Math.signum(b.vx) * 18f, b.y, 15f, p);
            p.setColor(avecAlpha(b.couleur, 200));
            c.drawCircle(b.x, b.y, 14f + (float) Math.sin(temps * 30f) * 2f, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(b.x, b.y, 7f, p);
        }

        // ------------------------------------------------------------------ dessin : interface

        private void texte(Canvas c, String t, float x, float y, float taille, int couleur, Paint.Align al) {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(taille);
            p.setTextAlign(al);
            p.setColor(0xAA000000);
            c.drawText(t, x + taille * 0.05f, y + taille * 0.05f, p);
            p.setColor(couleur);
            c.drawText(t, x, y, p);
        }

        private void barreVie(Canvas c, float x, float y, float w, float h, float pv, float max, boolean droite) {
            float ratio = Math.max(0f, Math.min(1f, pv / max));
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xAA000000);
            r.set(x - 3f, y - 3f, x + w + 3f, y + h + 3f);
            c.drawRoundRect(r, 8f, 8f, p);
            p.setColor(ratio > 0.5f ? 0xFFFDD835 : ratio > 0.25f ? 0xFFFB8C00 : 0xFFE53935);
            if (droite) r.set(x + w * (1f - ratio), y, x + w, y + h);
            else r.set(x, y, x + w * ratio, y + h);
            c.drawRoundRect(r, 6f, 6f, p);
        }

        private void bouton(Canvas c, RectF rc, String t, int couleur, boolean appuye, float taille) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(avecAlpha(couleur, appuye ? 250 : 150));
            c.drawRoundRect(rc, rc.height() * 0.3f, rc.height() * 0.3f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(getHeight() * 0.006f);
            p.setColor(0x88FFFFFF);
            c.drawRoundRect(rc, rc.height() * 0.3f, rc.height() * 0.3f, p);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(taille);
            p.setColor(0xFFFFFFFF);
            c.drawText(t, rc.centerX(), rc.centerY() + taille * 0.35f, p);
        }

        private void dessinerInterface(Canvas c, long now) {
            float w = getWidth(), h = getHeight();
            float u = h / HAUT;

            float bw = w * 0.36f;
            barreVie(c, w * 0.03f, h * 0.07f, bw, h * 0.05f, moi.pv, moi.pvMax, false);
            barreVie(c, w * 0.97f - bw, h * 0.07f, bw, h * 0.05f, adv.pv, adv.pvMax, true);
            texte(c, moi.perso.emoji + " " + (enLigne ? "Moi" : "Joueur 1"), w * 0.03f, h * 0.06f, 13f * u, 0xFFFFFFFF, Paint.Align.LEFT);
            String nomAdv = enLigne ? advNom : "🤖 Robot niv. " + niveau;
            texte(c, nomAdv, w * 0.97f, h * 0.06f, 13f * u, 0xFFFFFFFF, Paint.Align.RIGHT);
            if (!enLigne) texte(c, JeuMotardActivity.nomNiveau(niveau), w / 2f, h * 0.27f, 12f * u, 0xFFFFF59D, Paint.Align.CENTER);

            if (phase == ATTENTE) {
                texte(c, enLigne ? "Connexion avec " + advNom + "…" : "", w / 2f, h * 0.45f, 22f * u, 0xFFFFFFFF, Paint.Align.CENTER);
            } else if (phase == COMPTE) {
                long reste = Math.max(0, debutLocal - now);
                String t = reste > 0 ? String.valueOf((reste + 999) / 1000) : "COMBAT !";
                texte(c, t, w / 2f, h * 0.48f, (reste > 0 ? 70f : 44f) * u, 0xFFFFEB3B, Paint.Align.CENTER);
            }

            if (phase == COMBAT || phase == COMPTE || phase == PAUSE) {
                bouton(c, bG, "◀", 0xFF455A64, moi.gauche, 26f * u);
                bouton(c, bD, "▶", 0xFF455A64, moi.droite, 26f * u);
                bouton(c, bGarde, "GARDE", 0xFF00897B, moi.garde, 12f * u);
                bouton(c, bSaut, "SAUT", 0xFF1E88E5, moi.saut, 12f * u);
                bouton(c, bPoing, "POING", 0xFFE53935, moi.poing, 14f * u);
                bouton(c, bPied, "PIED", 0xFFFB8C00, moi.pied, 14f * u);
                boolean pret = moi.cdSpec <= 0;
                bouton(c, bSpec, pret ? "SPÉCIAL" : String.format(java.util.Locale.FRANCE, "%.1f", moi.cdSpec),
                        0xFF8E24AA, moi.special && pret, 11f * u);
                bouton(c, bQuit, "✕", 0xFF37474F, false, 14f * u);
            }

            if (phase == PAUSE) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x99000000);
                c.drawRect(0, 0, w, h, p);
                texte(c, "⏸ Pause", w / 2f, h * 0.45f, 36f * u, 0xFFFFFFFF, Paint.Align.CENTER);
                texte(c, "Touche l'écran pour reprendre", w / 2f, h * 0.58f, 15f * u, 0xFFFFF59D, Paint.Align.CENTER);
            }

            if (phase == FIN) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xAA000000);
                c.drawRect(0, 0, w, h, p);
                texte(c, gagne ? "🏆 VICTOIRE !" : "💀 DÉFAITE", w / 2f, h * 0.30f, 40f * u, gagne ? 0xFFFFD54F : 0xFFEF5350, Paint.Align.CENTER);
                String sous;
                if (enLigne) sous = message.isEmpty() ? (gagne ? "Tu as battu " + advNom + " !" : advNom + " est plus fort cette fois.") : message;
                else if (gagne) sous = niveau >= JeuMotardActivity.NIVEAUX ? "Tu as battu le robot invincible !" : "Niveau " + (niveau + 1) + " débloqué !";
                else sous = "Le robot niveau " + niveau + " t'a battu. Essaie encore !";
                texte(c, sous, w / 2f, h * 0.45f, 16f * u, 0xFFFFFFFF, Paint.Align.CENTER);
                if (!enLigne && gagne) texte(c, "Score : " + (niveau * 100 + Math.round(moi.pv)), w / 2f, h * 0.55f, 14f * u, 0xFFFFF59D, Paint.Align.CENTER);
                float bw2 = h * 0.5f, bh = h * 0.15f;
                if (enLigne) {
                    bA.set(w / 2f - bw2 / 2f, h * 0.68f, w / 2f + bw2 / 2f, h * 0.68f + bh);
                    bB.setEmpty();
                    bouton(c, bA, "Terminer", 0xFF1E8E3E, true, 16f * u);
                } else {
                    bA.set(w / 2f - bw2 - h * 0.02f, h * 0.68f, w / 2f - h * 0.02f, h * 0.68f + bh);
                    bB.set(w / 2f + h * 0.02f, h * 0.68f, w / 2f + bw2 + h * 0.02f, h * 0.68f + bh);
                    bouton(c, bA, gagne && niveau < JeuMotardActivity.NIVEAUX ? "Niveau suivant ▶" : "Rejouer", 0xFF1E8E3E, true, 15f * u);
                    bouton(c, bB, "Quitter", 0xFF546E7A, true, 15f * u);
                }
            }
        }
    }
}
