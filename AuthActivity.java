package bj.radio;

import android.animation.Animator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Patterns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Entrée de l'application : inscription (nom, email, mot de passe), connexion (email, mot de passe)
 * et mot de passe oublié (code reçu par email). Le compte et le profil sont enregistrés dans Supabase.
 */
public class AuthActivity extends Activity {

    private static final int CONNEXION = 0, INSCRIPTION = 1, OUBLI = 2;

    private int mode = CONNEXION;
    private boolean etapeCode = false;        // « mot de passe oublié » : 2e étape (saisie du code)
    private boolean occupe = false;

    private LinearLayout carte, formulaire, onglets;
    private Button ongletConnexion, ongletInscription, action, lienOubli, lienRetour, lienRenvoyer;
    private EditText champNom, champEmail, champPasse, champCode;
    private LinearLayout ligneNom, ligneEmail, lignePasse, ligneCode;
    private Button oeil;
    private TextView intro, message;
    private boolean passeVisible = false;
    private final List<Animator> animations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        int p = Ui.dp(this, 18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(p, p * 2, p, p);

        TextView logo = Ui.texte(this, "📞", 54, Ui.TXT, false);
        logo.setGravity(Gravity.CENTER);
        root.addView(logo);
        animations.add(Ui.flotter(logo, 0));

        TextView titre = Ui.texte(this, "Sossokouamé appel", 32, Ui.JAUNE, true);
        titre.setGravity(Gravity.CENTER);
        root.addView(titre, Ui.lp(this, -1, -2, 0f, 4, 0));
        animations.add(Ui.titreColore(titre));

        intro = Ui.texte(this, "", 14, Ui.TXT2, false);
        intro.setGravity(Gravity.CENTER);
        root.addView(intro, Ui.lp(this, -1, -2, 0f, 4, 0));

        carte = new LinearLayout(this);
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setBackground(Ui.rond(0x55202328, 26, this));
        int q = Ui.dp(this, 16);
        carte.setPadding(q, q, q, q);
        root.addView(carte, Ui.lp(this, -1, -2, 0f, 22, 0));

        // Onglets Connexion / Inscription
        onglets = new LinearLayout(this);
        onglets.setOrientation(LinearLayout.HORIZONTAL);
        onglets.setBackground(Ui.rond(Ui.CARD, 18, this));
        int o = Ui.dp(this, 4);
        onglets.setPadding(o, o, o, o);
        ongletConnexion = Ui.lien(this, "Connexion", Ui.TXT);
        ongletConnexion.setTextSize(15);
        ongletInscription = Ui.lien(this, "Inscription", Ui.TXT);
        ongletInscription.setTextSize(15);
        ongletConnexion.setOnClickListener(v -> changerMode(CONNEXION));
        ongletInscription.setOnClickListener(v -> changerMode(INSCRIPTION));
        onglets.addView(ongletConnexion, Ui.lp(this, 0, -2, 1f, 0, 0));
        onglets.addView(ongletInscription, Ui.lp(this, 0, -2, 1f, 0, 4));
        carte.addView(onglets);

        formulaire = new LinearLayout(this);
        formulaire.setOrientation(LinearLayout.VERTICAL);
        carte.addView(formulaire, Ui.lp(this, -1, -2, 0f, 16, 0));

        champNom = Ui.champ(this, "Votre nom");
        champNom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        ligneNom = ligne("👤", champNom);

        champEmail = Ui.champ(this, "Adresse email");
        champEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        ligneEmail = ligne("✉", champEmail);

        champPasse = Ui.champ(this, "Mot de passe");
        champPasse.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        champPasse.setImeOptions(EditorInfo.IME_ACTION_DONE);
        champPasse.setOnEditorActionListener((v, a, e) -> { valider(); return true; });
        lignePasse = ligne("🔒", champPasse);
        oeil = Ui.lien(this, "👁", Ui.TXT);
        oeil.setOnClickListener(v -> basculerPasse());
        lignePasse.addView(oeil, Ui.lp(this, -2, -2, 0f, 0, 4));

        champCode = Ui.champ(this, "Code reçu par email");
        champCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        ligneCode = ligne("🔑", champCode);

        message = Ui.texte(this, "", 13, Ui.JAUNE, false);
        message.setGravity(Gravity.CENTER);

        action = Ui.boutonPilule(this, "", Ui.ACCENT, Ui.ROSE);
        action.setOnClickListener(v -> valider());

        lienOubli = Ui.lien(this, "Mot de passe oublié ?", Ui.JAUNE);
        lienOubli.setOnClickListener(v -> { etapeCode = false; changerMode(OUBLI); });

        lienRenvoyer = Ui.lien(this, "Renvoyer le code", Ui.JAUNE);
        lienRenvoyer.setOnClickListener(v -> envoyerCode());

        lienRetour = Ui.lien(this, "← Retour à la connexion", Ui.TXT2);
        lienRetour.setOnClickListener(v -> changerMode(CONNEXION));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        animations.add(Ui.fondAnime(sv));
        setContentView(sv);

        Ui.entree(logo, 0);
        Ui.entree(titre, 1);
        Ui.entree(intro, 2);
        Ui.entree(carte, 3);

        construire();
    }

    @Override
    protected void onDestroy() {
        for (Animator a : animations) a.cancel();
        animations.clear();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ affichage

    /** Ligne « icône + champ ». */
    private LinearLayout ligne(String icone, EditText champ) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        TextView i = Ui.texte(this, icone, 20, Ui.TXT, false);
        i.setGravity(Gravity.CENTER);
        l.addView(i, Ui.lp(this, Ui.dp(this, 30), -2, 0f, 0, 0));
        l.addView(champ, Ui.lp(this, 0, -2, 1f, 0, 6));
        return l;
    }

    private void changerMode(int nouveau) {
        if (occupe) return;
        mode = nouveau;
        if (nouveau != OUBLI) etapeCode = false;
        message.setText("");
        formulaire.animate().alpha(0f).translationX(Ui.dp(this, -18)).setDuration(110).withEndAction(() -> {
            construire();
            formulaire.setTranslationX(Ui.dp(this, 18));
            formulaire.animate().alpha(1f).translationX(0f).setDuration(220).start();
        }).start();
    }

    /** Remet dans le formulaire les champs et boutons correspondant au mode courant. */
    private void construire() {
        formulaire.removeAllViews();
        boolean oubli = mode == OUBLI;
        onglets.setVisibility(oubli ? View.GONE : View.VISIBLE);
        marquerOnglet(ongletConnexion, mode == CONNEXION, Ui.ACCENT, Ui.ROSE);
        marquerOnglet(ongletInscription, mode == INSCRIPTION, Ui.VERT, 0xFF12B5CB);

        int c1, c2;
        String libelle;
        if (mode == CONNEXION) {
            intro.setText("Connectez-vous pour entrer");
            c1 = Ui.ACCENT; c2 = Ui.ROSE; libelle = "Se connecter";
            formulaire.addView(ligneEmail);
            formulaire.addView(lignePasse, Ui.lp(this, -1, -2, 0f, 10, 0));
        } else if (mode == INSCRIPTION) {
            intro.setText("Créez votre compte en quelques secondes");
            c1 = Ui.VERT; c2 = 0xFF12B5CB; libelle = "Créer mon compte";
            formulaire.addView(ligneNom);
            formulaire.addView(ligneEmail, Ui.lp(this, -1, -2, 0f, 10, 0));
            formulaire.addView(lignePasse, Ui.lp(this, -1, -2, 0f, 10, 0));
        } else if (!etapeCode) {
            intro.setText("Mot de passe oublié");
            c1 = Ui.VIOLET; c2 = Ui.BLEU; libelle = "Envoyer le code";
            formulaire.addView(Ui.texte(this, "Entrez votre email : nous vous envoyons un code pour choisir un nouveau mot de passe.",
                    13, Ui.TXT2, false));
            formulaire.addView(ligneEmail, Ui.lp(this, -1, -2, 0f, 10, 0));
        } else {
            intro.setText("Nouveau mot de passe");
            c1 = Ui.VIOLET; c2 = Ui.BLEU; libelle = "Changer le mot de passe";
            formulaire.addView(ligneCode);
            formulaire.addView(lignePasse, Ui.lp(this, -1, -2, 0f, 10, 0));
            champPasse.setHint("Nouveau mot de passe");
        }
        if (mode != OUBLI || !etapeCode) champPasse.setHint("Mot de passe");
        champPasse.setImeOptions(mode == OUBLI && !etapeCode ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);

        if (message.getParent() != null) ((LinearLayout) message.getParent()).removeView(message);
        formulaire.addView(message, Ui.lp(this, -1, -2, 0f, 12, 0));

        action.setText(libelle);
        action.setBackground(Ui.onde(c1, c2, 28, this));
        formulaire.addView(action, Ui.lp(this, -1, -2, 0f, 12, 0));

        if (mode == CONNEXION) formulaire.addView(lienOubli, Ui.lp(this, -2, -2, 0f, 6, 0));
        if (oubli && etapeCode) formulaire.addView(lienRenvoyer, Ui.lp(this, -2, -2, 0f, 6, 0));
        if (oubli) formulaire.addView(lienRetour, Ui.lp(this, -2, -2, 0f, 6, 0));
        ((LinearLayout.LayoutParams) action.getLayoutParams()).gravity = Gravity.CENTER;
    }

    private void marquerOnglet(Button b, boolean actif, int c1, int c2) {
        if (actif) {
            b.setBackground(Ui.onde(c1, c2, 14, this));
            b.setTextColor(Ui.TXT);
        } else {
            b.setBackground(null);
            b.setTextColor(Ui.TXT2);
        }
    }

    private void basculerPasse() {
        passeVisible = !passeVisible;
        champPasse.setTransformationMethod(passeVisible ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        champPasse.setSelection(champPasse.getText().length());
        oeil.setText(passeVisible ? "🙈" : "👁");
    }

    private void info(String t, boolean erreur) {
        message.setTextColor(erreur ? 0xFFFFB4A8 : 0xFF9BE8B8);
        message.setText(t);
    }

    private void occupe(boolean oui) {
        occupe = oui;
        action.setEnabled(!oui);
        action.setAlpha(oui ? 0.6f : 1f);
        if (oui) action.setText("Un instant…");
    }

    // ------------------------------------------------------------------ actions

    private String nom() { return champNom.getText().toString().trim(); }
    private String email() { return champEmail.getText().toString().trim().toLowerCase(); }
    private String passe() { return champPasse.getText().toString(); }

    private boolean emailValide(String e) { return Patterns.EMAIL_ADDRESS.matcher(e).matches(); }

    private void valider() {
        if (occupe) return;
        if (mode == CONNEXION) {
            if (!emailValide(email())) { info("Entrez une adresse email valide.", true); return; }
            if (passe().isEmpty()) { info("Entrez votre mot de passe.", true); return; }
            final String e = email(), pw = passe();
            lancer(() -> Compte.connecter(e, pw), null);
        } else if (mode == INSCRIPTION) {
            if (nom().length() < 2) { info("Entrez votre nom (2 lettres minimum).", true); return; }
            if (!emailValide(email())) { info("Entrez une adresse email valide.", true); return; }
            if (passe().length() < 6) { info("Le mot de passe doit avoir 6 caractères minimum.", true); return; }
            final String n = nom(), e = email(), pw = passe();
            lancer(() -> Compte.inscrire(n, e, pw), () -> {
                // compte créé mais email à confirmer : on passe à la connexion
                mode = CONNEXION;
                construire();
            });
        } else if (!etapeCode) {
            envoyerCode();
        } else {
            String code = champCode.getText().toString().trim();
            if (code.length() < 6) { info("Entrez le code reçu par email.", true); return; }
            if (passe().length() < 6) { info("Le mot de passe doit avoir 6 caractères minimum.", true); return; }
            final String e = email(), pw = passe();
            lancer(() -> Compte.changerMotDePasse(e, code, pw), null);
        }
    }

    private void envoyerCode() {
        if (occupe) return;
        if (!emailValide(email())) { info("Entrez une adresse email valide.", true); return; }
        final String e = email();
        occupe(true);
        new Thread(() -> {
            final Compte.Resultat r = Compte.envoyerCode(e);
            runOnUiThread(() -> {
                occupe(false);
                if (r.ok) {
                    boolean premiere = !etapeCode;
                    etapeCode = true;
                    construire();
                    info(r.message, false);
                    if (premiere) champCode.requestFocus();
                } else {
                    construire();
                    info(r.message, true);
                }
            });
        }).start();
    }

    private interface Appel { Compte.Resultat faire(); }

    /** Exécute l'appel réseau hors du thread principal ; en cas de succès on ouvre l'accueil. */
    private void lancer(Appel appel, Runnable siInfo) {
        occupe(true);
        new Thread(() -> {
            final Compte.Resultat r = appel.faire();
            runOnUiThread(() -> {
                occupe(false);
                if (r.ok) {
                    Securite.verrouille = false;
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    if (Compte.admin()) startActivity(new Intent(this, AdminActivity.class));
                    finish();
                } else if (siInfo != null && r.message.startsWith("Compte créé")) {
                    siInfo.run();
                    info(r.message, false);
                } else {
                    construire();
                    info(r.message, true);
                }
            });
        }).start();
    }
}
