package bj.radio;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/** Petits outils de style partagés : thème gris cendre « Kouamé Play Box » (dégradés, boutons, animations). */
public class Ui {
    // Couleurs du thème : valeurs d'origine ci-dessous, modifiables à distance par la clé "theme" du fichier de configuration
    // (voir Ui.theme). Elles ne sont plus « final » pour pouvoir changer sans recompiler.
    static final int D_BG = 0xFF5C6068, D_BG_HAUT = 0xFF6A6E77, D_CARD = 0xFF474B53, D_ACCENT = 0xFFFF6B35, D_VIOLET = 0xFF7B4DFF,
            D_JAUNE = 0xFFFFC857, D_TXT = 0xFFFFFFFF, D_TXT2 = 0xFFE3E5EA, D_ROSE = 0xFFFF4D8D, D_VERT = 0xFF2E9E73, D_BLEU = 0xFF1877F2;
    static int BG = D_BG;               // fond gris cendre (bas du dégradé)
    static int BG_HAUT = D_BG_HAUT;     // haut du dégradé de l'accueil
    static int CARD = D_CARD;
    static int ACCENT = D_ACCENT;
    static int VIOLET = D_VIOLET;
    static int JAUNE = D_JAUNE;
    static int TXT = D_TXT;
    static int TXT2 = D_TXT2;
    static int ROSE = D_ROSE;
    static int VERT = D_VERT;
    static int BLEU = D_BLEU;
    /** Multiplicateur des coins arrondis (1 = d'origine, 0 = angles droits, 2 = très arrondi). */
    static float RAYON = 1f;

    private static int coul(JSONObject t, String cle, int defaut) {
        try {
            String v = t == null ? "" : t.optString(cle, "").trim();
            return v.isEmpty() ? defaut : Color.parseColor(v);
        } catch (Exception e) {
            return defaut;
        }
    }

    /** Applique (ou remet d'origine si null) le thème du fichier de configuration : { "theme": { "fond_haut": "#RRGGBB", … } }. */
    static void theme(JSONObject cfg) {
        JSONObject t = cfg == null ? null : cfg.optJSONObject("theme");
        BG_HAUT = coul(t, "fond_haut", D_BG_HAUT);
        BG = coul(t, "fond_bas", D_BG);
        CARD = coul(t, "carte", D_CARD);
        ACCENT = coul(t, "accent", D_ACCENT);
        VIOLET = coul(t, "violet", D_VIOLET);
        JAUNE = coul(t, "jaune", D_JAUNE);
        TXT = coul(t, "texte", D_TXT);
        TXT2 = coul(t, "texte2", D_TXT2);
        ROSE = coul(t, "rose", D_ROSE);
        VERT = coul(t, "vert", D_VERT);
        BLEU = coul(t, "bleu", D_BLEU);
        double r = t == null ? 1.0 : t.optDouble("rayon", 1.0);
        RAYON = (float) Math.max(0.0, Math.min(2.5, Double.isNaN(r) ? 1.0 : r));
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable rond(int couleur, int rayonDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(couleur);
        g.setCornerRadius(dp(c, rayonDp) * RAYON);
        return g;
    }

    static void fenetre(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float poids, int hautDp, int gaucheDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h, poids);
        l.topMargin = dp(c, hautDp);
        l.leftMargin = dp(c, gaucheDp);
        return l;
    }

    static void ajouter(LinearLayout parent, View v, int hautDp) {
        parent.addView(v, lp(parent.getContext(), -1, -2, 0f, hautDp, 0));
    }

    static TextView texte(Context c, String t, int taille, int couleur, boolean gras) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(taille);
        v.setTextColor(couleur);
        if (gras) v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    // ------------------------------------------------------------------ couleurs et dégradés

    static int eclaircir(int c, float f) {
        return Color.argb(Color.alpha(c),
                Math.min(255, (int) (Color.red(c) + (255 - Color.red(c)) * f)),
                Math.min(255, (int) (Color.green(c) + (255 - Color.green(c)) * f)),
                Math.min(255, (int) (Color.blue(c) + (255 - Color.blue(c)) * f)));
    }

    static int assombrir(int c, float f) {
        return Color.argb(Color.alpha(c), (int) (Color.red(c) * (1 - f)),
                (int) (Color.green(c) * (1 - f)), (int) (Color.blue(c) * (1 - f)));
    }

    /** Fond de page : dégradé du gris clair (haut) vers le gris cendre (bas). */
    static GradientDrawable fond() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_HAUT, BG});
    }

    /** Dégradé diagonal à coins arrondis. */
    static GradientDrawable degrade(int c1, int c2, int rayonDp, Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(c, rayonDp) * RAYON);
        return g;
    }

    /** Dégradé + effet d'onde au toucher. */
    static RippleDrawable onde(int c1, int c2, int rayonDp, Context c) {
        return new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), degrade(c1, c2, rayonDp, c),
                rond(0xFFFFFFFF, rayonDp, c));
    }

    // ------------------------------------------------------------------ textes, boutons, champs

    /** Bouton plein, dégradé de la couleur donnée (plus claire en haut à gauche), onde et rebond au toucher. */
    static Button bouton(Context c, String t, int couleur) {
        Button b = base(c, t);
        b.setBackground(onde(eclaircir(couleur, 0.22f), assombrir(couleur, 0.18f), 14, c));
        b.setElevation(dp(c, 3));
        return b;
    }

    /** Bouton principal : pilule à dégradé de deux couleurs (ex. orange → rose). */
    static Button boutonPilule(Context c, String t, int c1, int c2) {
        Button b = base(c, t);
        b.setTextSize(16);
        b.setBackground(onde(c1, c2, 28, c));
        b.setElevation(dp(c, 5));
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        return b;
    }

    /** Bouton secondaire : contour coloré, fond transparent. */
    static Button boutonContour(Context c, String t, int couleur) {
        Button b = base(c, t);
        b.setTextColor(couleur);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(c, 14));
        g.setStroke(dp(c, 2), couleur);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, rond(0xFFFFFFFF, 14, c)));
        return b;
    }

    /** Lien discret (texte seul, sans fond). */
    static Button lien(Context c, String t, int couleur) {
        Button b = base(c, t);
        b.setTextColor(couleur);
        b.setTextSize(13);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, rond(0xFFFFFFFF, 10, c)));
        return b;
    }

    private static Button base(Context c, String t) {
        Button b = new Button(c);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setTextSize(14);
        b.setTypeface(null, Typeface.BOLD);
        b.setStateListAnimator(null);
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        pression(b);
        return b;
    }

    /** Effet « rebond » : la vue rétrécit quand on appuie, puis revient avec un petit dépassement. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    static void pression(View v) {
        v.setOnTouchListener((x, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    x.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).setInterpolator(new AccelerateDecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    x.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Champ de saisie : le contour s'illumine quand il est sélectionné. */
    static EditText champ(Context c, String indice) {
        EditText e = new EditText(c);
        e.setHint(indice);
        e.setHintTextColor(TXT2);
        e.setTextColor(TXT);
        e.setSingleLine(true);
        e.setBackground(contourChamp(c, false));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        e.setOnFocusChangeListener((v, a) -> v.setBackground(contourChamp(v.getContext(), a)));
        return e;
    }

    private static GradientDrawable contourChamp(Context c, boolean actif) {
        GradientDrawable g = rond(CARD, 12, c);
        g.setStroke(dp(c, 2), actif ? JAUNE : 0x00000000);
        return g;
    }

    // ------------------------------------------------------------------ animations

    /** Entrée en cascade : la vue monte en fondu avec un rebond, avec un délai selon son rang. */
    static void entree(View v, int rang) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 36));
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(90L + rang * 80L).setDuration(520)
                .setInterpolator(new OvershootInterpolator(1.6f)).start();
    }

    /** Lévitation douce et sans fin (monte/descend) ; renvoie l'animation pour pouvoir l'arrêter. */
    static Animator flotter(View v, int rang) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -dp(v.getContext(), 5));
        a.setDuration(1500 + (rang % 3) * 350L);
        a.setStartDelay(700L + rang * 130L);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.start();
        return a;
    }

    /** Texte dont les couleurs défilent (jaune → orange → rose → violet…). */
    static Animator titreColore(TextView t) {
        float w = Math.max(1f, t.getPaint().measureText(t.getText().toString()));
        final LinearGradient g = new LinearGradient(0, 0, w, 0,
                new int[]{JAUNE, ACCENT, ROSE, VIOLET, ROSE, ACCENT, JAUNE}, null, Shader.TileMode.MIRROR);
        t.getPaint().setShader(g);
        final Matrix m = new Matrix();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(4200);
        va.setRepeatCount(ValueAnimator.INFINITE);
        va.setInterpolator(new LinearInterpolator());
        va.addUpdateListener(an -> {
            m.setTranslate(w * 2f * (float) an.getAnimatedValue(), 0);
            g.setLocalMatrix(m);
            t.invalidate();
        });
        va.start();
        return va;
    }

    /** Fond qui change doucement de teinte (gris-bleu → gris-violet → gris-vert…). */
    static Animator fondAnime(View v) {
        final GradientDrawable g = fond();
        v.setBackground(g);
        final int[] teintesHaut = {0xFF6F6A82, 0xFF607A80, 0xFF7A6A72};
        final int[] teintesBas = {0xFF54506A, 0xFF4C5E64, 0xFF5E5058};
        final ArgbEvaluator ev0 = new ArgbEvaluator();
        // Teintes douces autour des couleurs du thème (même rendu qu'avant avec le thème d'origine).
        final int[] haut = new int[5];
        final int[] bas = new int[5];
        haut[0] = haut[4] = BG_HAUT;
        bas[0] = bas[4] = BG;
        final float force = BG_HAUT == D_BG_HAUT && BG == D_BG ? 1f : 0.45f;   // thème d'origine : teintes exactes
        for (int i = 0; i < 3; i++) {
            haut[i + 1] = (int) ev0.evaluate(force, BG_HAUT, teintesHaut[i]);
            bas[i + 1] = (int) ev0.evaluate(force, BG, teintesBas[i]);
        }
        final ArgbEvaluator ev = new ArgbEvaluator();
        ValueAnimator va = ValueAnimator.ofFloat(0f, haut.length - 1);
        va.setDuration(24000);
        va.setRepeatCount(ValueAnimator.INFINITE);
        va.setInterpolator(new LinearInterpolator());
        va.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            int i = Math.min((int) f, haut.length - 2);
            float k = f - i;
            g.setColors(new int[]{(int) ev.evaluate(k, haut[i], haut[i + 1]), (int) ev.evaluate(k, bas[i], bas[i + 1])});
        });
        va.start();
        return va;
    }

    static void styleSpinner(Spinner s) {
        Context c = s.getContext();
        s.setBackground(rond(CARD, 12, c));
        s.setPopupBackgroundDrawable(rond(CARD, 8, c));
        s.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6));
    }

    static ArrayAdapter<String> adapterSpinner(Context c, List<String> items) {
        return new ArrayAdapter<String>(c, android.R.layout.simple_spinner_dropdown_item, items) {
            @Override
            public View getView(int p, View v, ViewGroup g) {
                TextView t = (TextView) super.getView(p, v, g);
                t.setTextColor(TXT);
                t.setTextSize(15);
                return t;
            }
            @Override
            public View getDropDownView(int p, View v, ViewGroup g) {
                TextView t = (TextView) super.getDropDownView(p, v, g);
                t.setTextColor(TXT);
                t.setBackgroundColor(CARD);
                return t;
            }
        };
    }

    static ArrayAdapter<String> adapterListe(Context c) {
        return new ArrayAdapter<String>(c, android.R.layout.simple_list_item_single_choice) {
            @Override
            public View getView(int p, View v, ViewGroup g) {
                View r = super.getView(p, v, g);
                if (r instanceof TextView) {
                    TextView t = (TextView) r;
                    t.setTextColor(TXT);
                    t.setTextSize(15);
                    if (t instanceof CheckedTextView)
                        ((CheckedTextView) t).setCheckMarkTintList(ColorStateList.valueOf(ACCENT));
                }
                return r;
            }
        };
    }
}
