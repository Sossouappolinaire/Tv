package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Écran de verrouillage : code PIN au clavier numérique, ou empreinte digitale. */
public class VerrouActivity extends Activity {

    private final StringBuilder saisie = new StringBuilder();
    private TextView points, message;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean bioDemandee = false;

    private final Runnable decompte = new Runnable() {
        @Override
        public void run() {
            int a = Securite.attente();
            if (a > 0) {
                message.setText("Trop d'essais. Réessayez dans " + a + " s");
                h.postDelayed(this, 500);
            } else if (message.getText().toString().startsWith("Trop")) {
                message.setText("");
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 20);
        root.setPadding(p, p, p, p);
        root.setBackground(Ui.fond());

        TextView logo = Ui.texte(this, "🔒", 46, Ui.TXT, false);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);
        TextView t = Ui.texte(this, "Entrez votre code", 22, Ui.JAUNE, true);
        t.setGravity(Gravity.CENTER);
        root.addView(t, Ui.lp(this, -1, -2, 0f, 6, 0));
        String nom = Compte.nom();
        if (!nom.isEmpty()) {
            TextView n = Ui.texte(this, nom, 14, Ui.TXT2, false);
            n.setGravity(Gravity.CENTER);
            root.addView(n, Ui.lp(this, -1, -2, 0f, 2, 0));
        }

        points = Ui.texte(this, "", 34, Ui.TXT, true);
        points.setGravity(Gravity.CENTER);
        points.setLetterSpacing(0.25f);
        root.addView(points, Ui.lp(this, -1, -2, 0f, 14, 0));

        message = Ui.texte(this, "", 13, 0xFFFFB4A8, false);
        message.setGravity(Gravity.CENTER);
        root.addView(message, Ui.lp(this, -1, Ui.dp(this, 22), 0f, 2, 0));

        String[][] touches = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"👆", "0", "⌫"}};
        int[][] c1 = {{Ui.ACCENT, Ui.VIOLET, Ui.VERT}, {Ui.ROSE, Ui.BLEU, Ui.ACCENT}, {Ui.VIOLET, Ui.VERT, Ui.ROSE}};
        for (int r = 0; r < 4; r++) {
            LinearLayout ligne = new LinearLayout(this);
            ligne.setGravity(Gravity.CENTER);
            for (int c = 0; c < 3; c++) {
                final String k = touches[r][c];
                Button bt;
                if (r < 3) {
                    int col = c1[r][c];
                    bt = Ui.bouton(this, k, col);
                } else {
                    bt = Ui.bouton(this, k, k.equals("0") ? Ui.BLEU : 0xFF6C7A89);
                }
                bt.setTextSize(24);
                bt.setOnClickListener(v -> touche(k));
                if (k.equals("⌫")) bt.setOnLongClickListener(v -> { saisie.setLength(0); maj(); return true; });
                if (k.equals("👆") && !Securite.biometrieActive()) bt.setVisibility(View.INVISIBLE);
                int d = Ui.dp(this, 74);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d, d);
                lp.setMargins(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
                ligne.addView(bt, lp);
                Ui.entree(bt, r * 3 + c);
            }
            root.addView(ligne);
        }

        Button oubli = Ui.lien(this, "Code oublié ? Se déconnecter", Ui.TXT2);
        oubli.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Se déconnecter ?")
                .setMessage("Le code PIN sera supprimé. Vous devrez vous reconnecter avec votre email et votre mot de passe.")
                .setPositiveButton("Se déconnecter", (d, w) -> {
                    Compte.deconnecter();
                    Intent i = new Intent(this, AuthActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Annuler", null)
                .show());
        root.addView(oubli, Ui.lp(this, -2, -2, 0f, 10, 0));

        setContentView(root);
        maj();
        h.post(decompte);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Securite.biometrieActive() && !bioDemandee) {
            bioDemandee = true;
            h.postDelayed(this::empreinte, 300);
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Retour : on ne peut pas contourner le verrou, on renvoie simplement l'application à l'arrière-plan. */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void empreinte() {
        Securite.demanderBiometrie(this, "Déverrouiller", "Posez votre doigt sur le capteur", new Securite.Retour() {
            @Override public void reussi() { ouvrir(); }
            @Override public void echec() { /* on reste sur le clavier PIN */ }
        });
    }

    private void touche(String k) {
        if (k.equals("👆")) { empreinte(); return; }
        if (Securite.attente() > 0) return;
        if (k.equals("⌫")) {
            if (saisie.length() > 0) saisie.setLength(saisie.length() - 1);
        } else if (saisie.length() < 6) {
            saisie.append(k);
        }
        maj();
        if (saisie.length() == Securite.longueur()) {
            if (Securite.verifierPin(saisie.toString())) {
                ouvrir();
            } else {
                saisie.setLength(0);
                maj();
                message.setText("Code incorrect");
                points.animate().translationX(Ui.dp(this, 14)).setDuration(60).withEndAction(
                        () -> points.animate().translationX(Ui.dp(this, -14)).setDuration(90).withEndAction(
                                () -> points.animate().translationX(0).setDuration(60).start()).start()).start();
                h.removeCallbacks(decompte);
                h.postDelayed(decompte, 200);
            }
        }
    }

    private void maj() {
        StringBuilder sb = new StringBuilder();
        int n = Securite.longueur();
        for (int i = 0; i < n; i++) sb.append(i < saisie.length() ? "●" : "○");
        points.setText(sb.toString());
        if (saisie.length() > 0 && message.getText().toString().equals("Code incorrect")) message.setText("");
    }

    private void ouvrir() {
        Securite.verrouille = false;
        finish();
    }
}
