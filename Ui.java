package bj.radio;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

/** Petits outils de style partagés : thème sombre RiRe a GOGO. */
public class Ui {
    static final int BG = 0xFF12122B;
    static final int CARD = 0xFF23244A;
    static final int ACCENT = 0xFFFF6B35;
    static final int VIOLET = 0xFF7B4DFF;
    static final int JAUNE = 0xFFFFC857;
    static final int TXT = 0xFFFFFFFF;
    static final int TXT2 = 0xFFB8B8D8;

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable rond(int couleur, int rayonDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(couleur);
        g.setCornerRadius(dp(c, rayonDp));
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

    static Button bouton(Context c, String t, int couleur) {
        Button b = new Button(c);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setTextSize(14);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(rond(couleur, 14, c));
        b.setStateListAnimator(null);
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        return b;
    }

    static EditText champ(Context c, String indice) {
        EditText e = new EditText(c);
        e.setHint(indice);
        e.setHintTextColor(TXT2);
        e.setTextColor(TXT);
        e.setSingleLine(true);
        e.setBackground(rond(CARD, 12, c));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return e;
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
