package bj.radio;

import android.animation.Animator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
 * Entrée de l'application : inscription (nom, email, mot de passe), confirmation, connexion et mot de passe oublié.
 * Aucun e-mail n'est envoyé : chaque inscription reçoit un NOUVEAU numéro de série (à conserver) et un code,
 * affichés dans la zone de message. Mot de passe oublié = email + numéro de série + code.
 */
public class AuthActivity extends Activity {

    private static final int CONNEXION = 0, INSCRIPTION = 1, OUBLI = 2, CONFIRMATION = 3;

    private int mode = CONNEXION;
    private boolean etapeCode = false;        // « mot de passe oublié » : 2e étape (code reçu, nouveau mot de passe)
    private boolean occupe = false;

    private LinearLayout carte, formulaire, onglets;
    private Button ongletConnexion, ongletInscription, action, lienOubli, lienRetour, lienRenvoyer, lienConfirmer, copier;
    private EditText champNom, champEmail, champPasse, champCode, champSerie;
    private LinearLayout ligneNom, ligneEmail, lignePasse, ligneCode, ligneSerie;
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

        TextView titre = Ui.texte(this, Config.titreApp(this), 32, Ui.JAUNE, true);
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

        champCode = Ui.champ(this, "Code (6 chiffres)");
        champCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        ligneCode = ligne("🔑", champCode);

        champSerie = Ui.champ(this, "Numéro de série (XXXX-XXXX-XXXX-XXXX)");
        champSerie.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ligneSerie = ligne("🔢", champSerie);
        copier = Ui.lien(this, "📋", Ui.TXT);
        copier.setOnClickListener(v -> copierSerie());
        ligneSerie.addView(copier, Ui.lp(this, -2, -2, 0f, 0, 4));

        message = Ui.texte(this, "", 14, Ui.JAUNE, false);
        message.setGravity(Gravity.CENTER);
        message.setTextIsSelectable(true);

        action = Ui.boutonPilule(this, "", Ui.ACCENT, Ui.ROSE);
        action.setOnClickListener(v -> valider());

        lienOubli = Ui.lien(this, "Mot de passe oublié ?", Ui.JAUNE);
        lienOubli.setOnClickListener(v -> { etapeCode = false; changerMode(OUBLI); });

        lienConfirmer = Ui.lien(this, "Confirmer mon compte", Ui.JAUNE);
        lienConfirmer.setOnClickListener(v -> changerMode(CONFIRMATION));

        lienRenvoyer = Ui.lien(this, "Obtenir un nouveau code", Ui.JAUNE);
        lienRenvoyer.setOnClickListener(v -> demanderCode(mode == OUBLI ? "reinitialisation" : "confirmation"));

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
        if (nouveau == CONFIRMATION && champSerie.getText().length() == 0 && !Compte.derniereSerie.isEmpty())
            champSerie.setText(Compte.derniereSerie);
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
        boolean avecOnglets = mode == CONNEXION || mode == INSCRIPTION;
        onglets.setVisibility(avecOnglets ? View.VISIBLE : View.GONE);
        marquerOnglet(ongletConnexion, mode == CONNEXION, Ui.ACCENT, Ui.ROSE);
        marquerOnglet(ongletInscription, mode == INSCRIPTION, Ui.VERT, 0xFF12B5CB);

        int c1, c2;
        String libelle;
        champPasse.setHint("Mot de passe");
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
            formulaire.addView(Ui.texte(this, "ℹ Un numéro de série vous sera donné : gardez-le précieusement, "
                    + "il sera indispensable si vous oubliez votre mot de passe.", 12, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 10, 0));
        } else if (mode == CONFIRMATION) {
            intro.setText("Confirmez votre compte");
            c1 = Ui.VERT; c2 = 0xFF12B5CB; libelle = "Confirmer mon compte";
            formulaire.addView(Ui.texte(this, "⚠ Notez ou copiez votre numéro de série (bouton 📋) : "
                    + "sans lui, impossible de récupérer votre compte.", 13, Ui.JAUNE, false));
            formulaire.addView(ligneEmail, Ui.lp(this, -1, -2, 0f, 10, 0));
            formulaire.addView(ligneSerie, Ui.lp(this, -1, -2, 0f, 10, 0));
            formulaire.addView(ligneCode, Ui.lp(this, -1, -2, 0f, 10, 0));
        } else if (!etapeCode) {
            intro.setText("Mot de passe oublié");
            c1 = Ui.VIOLET; c2 = Ui.BLEU; libelle = "Obtenir le code";
            formulaire.addView(Ui.texte(this, "Entrez votre email et le numéro de série reçu à l'inscription : "
                    + "vous obtiendrez un code pour choisir un nouveau mot de passe.", 13, Ui.TXT2, false));
            formulaire.addView(ligneEmail, Ui.lp(this, -1, -2, 0f, 10, 0));
            formulaire.addView(ligneSerie, Ui.lp(this, -1, -2, 0f, 10, 0));
        } else {
            intro.setText("Nouveau mot de passe");
            c1 = Ui.VIOLET; c2 = Ui.BLEU; libelle = "Changer le mot de passe";
            formulaire.addView(ligneEmail);
            formulaire.addView(ligneSerie, Ui.lp(this, -1, -2, 0f, 10, 0));
            formulaire.addView(ligneCode, Ui.lp(this, -1, -2, 0f, 10, 0));
            formulaire.addView(lignePasse, Ui.lp(this, -1, -2, 0f, 10, 0));
            champPasse.setHint("Nouveau mot de passe");
        }
        boolean passeFinal = mode == OUBLI && etapeCode || mode == CONNEXION || mode == INSCRIPTION;
        champPasse.setImeOptions(passeFinal ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);

        // zone de message (en bas) : affiche le numéro de série, le code et les erreurs
        if (message.getParent() != null) ((LinearLayout) message.getParent()).removeView(message);
        formulaire.addView(message, Ui.lp(this, -1, -2, 0f, 12, 0));

        action.setText(libelle);
        action.setBackground(Ui.onde(c1, c2, 28, this));
        formulaire.addView(action, Ui.lp(this, -1, -2, 0f, 12, 0));

        if (mode == CONNEXION) {
            formulaire.addView(lienOubli, Ui.lp(this, -2, -2, 0f, 6, 0));
            formulaire.addView(lienConfirmer, Ui.lp(this, -2, -2, 0f, 2, 0));
        }
        if (mode == CONFIRMATION || (mode == OUBLI && etapeCode)) formulaire.addView(lienRenvoyer, Ui.lp(this, -2, -2, 0f, 6, 0));
        if (mode == OUBLI || mode == CONFIRMATION) formulaire.addView(lienRetour, Ui.lp(this, -2, -2, 0f, 6, 0));
        ((LinearLayout.LayoutParams) action.getLayoutParams()).gravity = Gravity.CENTER;
    }

    private void copierSerie() {
        String t = serie();
        if (t.isEmpty()) { info("Aucun numéro de série à copier.", true); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Numéro de série", t));
        info("Numéro de série copié ✔ Collez-le dans vos notes.", false);
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
    private String serie() { return champSerie.getText().toString().trim().toUpperCase(); }

    private boolean emailValide(String e) { return Patterns.EMAIL_ADDRESS.matcher(e).matches(); }

    private void valider() {
        if (occupe) return;
        if (mode == CONNEXION) {
            if (!emailValide(email())) { info("Entrez une adresse email valide.", true); return; }
            if (passe().isEmpty()) { info("Entrez votre mot de passe.", true); return; }
            final String e = email(), pw = passe();
            lancer(() -> Compte.connecter(e, pw));
        } else if (mode == INSCRIPTION) {
            if (nom().length() < 2) { info("Entrez votre nom (2 lettres minimum).", true); return; }
            if (!emailValide(email())) { info("Entrez une adresse email valide.", true); return; }
            if (passe().length() < 6) { info("Le mot de passe doit avoir 6 caractères minimum.", true); return; }
            final String n = nom(), e = email(), pw = passe();
            lancer(() -> Compte.inscrire(n, e, pw));
        } else if (mode == CONFIRMATION) {
            if (!emailValide(email())) { info("Entrez une adresse email valide.", true); return; }
            if (serie().length() < 12) { info("Entrez votre numéro de série.", true); return; }
            final String code = champCode.getText().toString().trim();
            if (code.length() < 6) { info("Entrez le code à 6 chiffres.", true); return; }
            final String e = email(), s = serie(), pw = passe();
            lancer(() -> Compte.confirmer(e, s, code, pw));
        } else if (!etapeCode) {
            demanderCode("reinitialisation");
        } else {
            final String code = champCode.getText().toString().trim();
            if (code.length() < 6) { info("Entrez le code à 6 chiffres.", true); return; }
            if (passe().length() < 6) { info("Le mot de passe doit avoir 6 caractères minimum.", true); return; }
            final String e = email(), s = serie(), pw = passe();
            lancer(() -> Compte.changerMotDePasse(e, s, code, pw));
        }
    }

    /** Demande un code à la base (il faut l'email ET le numéro de série) ; le code s'affiche dans la zone de message. */
    private void demanderCode(final String objet) {
        if (occupe) return;
        if (!emailValide(email())) { info("Entrez une adresse email valide.", true); return; }
        if (serie().length() < 12) { info("Entrez votre numéro de série.", true); return; }
        final String e = email(), s = serie();
        occupe(true);
        new Thread(() -> {
            final Compte.Resultat r = Compte.demanderCode(e, s, objet);
            runOnUiThread(() -> {
                occupe(false);
                if (r.ok) {
                    boolean premiere = mode == OUBLI && !etapeCode;
                    if (mode == OUBLI) etapeCode = true;
                    construire();
                    info(r.message, false);
                    if (premiere || mode == CONFIRMATION) champCode.requestFocus();
                } else {
                    construire();
                    info(r.message, true);
                }
            });
        }).start();
    }

    private interface Appel { Compte.Resultat faire(); }

    /** Exécute l'appel réseau hors du thread principal ; en cas de succès on ouvre l'accueil. */
    private void lancer(Appel appel) {
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
                } else if ("confirmation".equals(r.suite)) {
                    // compte créé : on affiche le numéro de série et on demande le code
                    mode = CONFIRMATION;
                    etapeCode = false;
                    champSerie.setText(Compte.derniereSerie);
                    champCode.setText("");
                    construire();
                    info("Compte créé ✔\n\nVotre numéro de série :\n" + Compte.derniereSerie
                            + "\n\nVotre code de confirmation : " + Compte.dernierCode
                            + "\n\nGardez bien ce numéro de série : il sera demandé si vous oubliez votre mot de passe.", false);
                } else if ("connexion".equals(r.suite)) {
                    mode = CONNEXION;
                    etapeCode = false;
                    champCode.setText("");
                    construire();
                    info(r.message, false);
                } else {
                    construire();
                    info(r.message, true);
                }
            });
        }).start();
    }
}
