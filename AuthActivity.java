package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.Toast;

/**
 * Entrée de l'application : inscription (nom, email, mot de passe), confirmation, connexion et mot de passe oublié.
 * Aucun e-mail n'est envoyé : chaque inscription reçoit un NOUVEAU numéro de série (à conserver) et un code,
 * affichés dans la zone de message. Mot de passe oublié = email + numéro de série + code.
 */
public class AuthActivity extends Activity {

    private static final int CONNEXION = 0, INSCRIPTION = 1, OUBLI = 2, CONFIRMATION = 3;
    private static final int AUTH_BG_HAUT = 0xFF0B1020;
    private static final int AUTH_BG_BAS = 0xFF111B31;
    private static final int AUTH_CARTE = 0xFF172239;
    private static final int AUTH_CHAMP = 0xFF10192B;
    private static final int AUTH_TEXTE = 0xFFF5F7FF;
    private static final int AUTH_DISCRET = 0xFFADB9D0;
    private static final int AUTH_ORANGE = 0xFFFF9052;
    private static final int AUTH_AMBRE = 0xFFFFC857;

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
    private AlertDialog dialogCode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        getWindow().setStatusBarColor(AUTH_BG_HAUT);
        getWindow().setNavigationBarColor(AUTH_BG_HAUT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        int p = Ui.dp(this, 22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(p, Ui.dp(this, 24), p, p);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{AUTH_BG_HAUT, AUTH_BG_BAS}));

        TextView logo = Ui.texte(this, "▶", 25, AUTH_TEXTE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.degrade(AUTH_ORANGE, AUTH_AMBRE, 24, this));
        logo.setElevation(Ui.dp(this, 8));
        logo.setContentDescription("Logo de l’application");
        root.addView(logo, Ui.lp(this, Ui.dp(this, 62), Ui.dp(this, 62), 0f, 0, 0));

        TextView surTitre = Ui.texte(this, "VOTRE ESPACE PERSONNEL", 11, AUTH_AMBRE, true);
        surTitre.setGravity(Gravity.CENTER);
        surTitre.setLetterSpacing(0.14f);
        root.addView(surTitre, Ui.lp(this, -1, -2, 0f, 17, 0));

        TextView titre = Ui.texte(this, Config.titreApp(this), 29, AUTH_TEXTE, true);
        titre.setGravity(Gravity.CENTER);
        root.addView(titre, Ui.lp(this, -1, -2, 0f, 3, 0));

        intro = Ui.texte(this, "", 14, AUTH_DISCRET, false);
        intro.setGravity(Gravity.CENTER);
        root.addView(intro, Ui.lp(this, -1, -2, 0f, 7, 0));

        carte = new LinearLayout(this);
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setBackground(fondCarte());
        carte.setElevation(Ui.dp(this, 10));
        int q = Ui.dp(this, 20);
        carte.setPadding(q, q, q, q);
        int largeurDisponible = getResources().getDisplayMetrics().widthPixels - p * 2;
        int largeurCarteMax = Ui.dp(this, 520);
        LinearLayout.LayoutParams carteParams = new LinearLayout.LayoutParams(
                Math.min(largeurDisponible, largeurCarteMax), LinearLayout.LayoutParams.WRAP_CONTENT);
        carteParams.topMargin = Ui.dp(this, 24);
        root.addView(carte, carteParams);

        // Onglets Connexion / Inscription
        onglets = new LinearLayout(this);
        onglets.setOrientation(LinearLayout.HORIZONTAL);
        onglets.setBackground(Ui.rond(AUTH_CHAMP, 18, this));
        int o = Ui.dp(this, 4);
        onglets.setPadding(o, o, o, o);
        ongletConnexion = Ui.lien(this, "Connexion", AUTH_TEXTE);
        ongletConnexion.setTextSize(15);
        ongletInscription = Ui.lien(this, "Inscription", AUTH_TEXTE);
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
        styliserChamp(champNom);
        champNom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        ligneNom = ligne("◉", champNom);

        champEmail = Ui.champ(this, "Adresse email");
        styliserChamp(champEmail);
        champEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        ligneEmail = ligne("@", champEmail);

        champPasse = Ui.champ(this, "Mot de passe");
        styliserChamp(champPasse);
        champPasse.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        champPasse.setImeOptions(EditorInfo.IME_ACTION_DONE);
        champPasse.setOnEditorActionListener((v, a, e) -> { valider(); return true; });
        lignePasse = ligne("◇", champPasse);
        oeil = Ui.lien(this, "Afficher", AUTH_AMBRE);
        oeil.setOnClickListener(v -> basculerPasse());
        lignePasse.addView(oeil, Ui.lp(this, -2, -2, 0f, 0, 4));

        champCode = Ui.champ(this, "Code (6 chiffres)");
        styliserChamp(champCode);
        champCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        ligneCode = ligne("#", champCode);

        champSerie = Ui.champ(this, "Numéro de série (XXXX-XXXX-XXXX-XXXX)");
        styliserChamp(champSerie);
        champSerie.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ligneSerie = ligne("№", champSerie);
        copier = Ui.lien(this, "Copier", AUTH_AMBRE);
        copier.setOnClickListener(v -> copierSerie());
        ligneSerie.addView(copier, Ui.lp(this, -2, -2, 0f, 0, 4));

        message = Ui.texte(this, "", 13, AUTH_DISCRET, false);
        message.setGravity(Gravity.START);
        message.setTextIsSelectable(true);

        action = Ui.boutonPilule(this, "", Ui.ACCENT, Ui.ROSE);
        action.setOnClickListener(v -> valider());

        lienOubli = Ui.lien(this, "Mot de passe oublié ?", AUTH_AMBRE);
        lienOubli.setOnClickListener(v -> { etapeCode = false; changerMode(OUBLI); });

        lienConfirmer = Ui.lien(this, "Confirmer mon compte", AUTH_AMBRE);
        lienConfirmer.setOnClickListener(v -> changerMode(CONFIRMATION));

        lienRenvoyer = Ui.lien(this, "Obtenir un nouveau code", AUTH_AMBRE);
        lienRenvoyer.setOnClickListener(v -> demanderCode(mode == OUBLI ? "reinitialisation" : "confirmation"));

        lienRetour = Ui.lien(this, "← Retour à la connexion", AUTH_DISCRET);
        lienRetour.setOnClickListener(v -> changerMode(CONNEXION));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);

        Ui.entree(logo, 0);
        Ui.entree(titre, 1);
        Ui.entree(intro, 2);
        Ui.entree(carte, 3);

        construire();
    }

    @Override
    protected void onDestroy() {
        if (dialogCode != null && dialogCode.isShowing()) dialogCode.dismiss();
        dialogCode = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ affichage

    /** Ligne « icône + champ ». */
    private LinearLayout ligne(String icone, EditText champ) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        TextView i = Ui.texte(this, icone, 18, AUTH_AMBRE, true);
        i.setGravity(Gravity.CENTER);
        l.addView(i, Ui.lp(this, Ui.dp(this, 28), -2, 0f, 0, 0));
        l.addView(champ, Ui.lp(this, 0, -2, 1f, 0, 5));
        return l;
    }

    private GradientDrawable fondCarte() {
        GradientDrawable g = Ui.rond(AUTH_CARTE, 24, this);
        g.setStroke(Ui.dp(this, 1), 0xFF26334D);
        return g;
    }

    private GradientDrawable fondChamp(boolean actif) {
        GradientDrawable g = Ui.rond(AUTH_CHAMP, 14, this);
        g.setStroke(Ui.dp(this, actif ? 2 : 1), actif ? AUTH_AMBRE : 0xFF2B3852);
        return g;
    }

    private void styliserChamp(EditText champ) {
        champ.setTextColor(AUTH_TEXTE);
        champ.setHintTextColor(0xFF8694AE);
        champ.setPadding(Ui.dp(this, 13), Ui.dp(this, 12), Ui.dp(this, 13), Ui.dp(this, 12));
        champ.setBackground(fondChamp(false));
        champ.setOnFocusChangeListener((v, actif) -> v.setBackground(fondChamp(actif)));
    }

    private void ajouterChamp(String libelle, View champ, int margeHaut) {
        TextView label = Ui.texte(this, libelle, 11, AUTH_DISCRET, true);
        label.setLetterSpacing(0.08f);
        formulaire.addView(label, Ui.lp(this, -1, -2, 0f, margeHaut, 0));
        formulaire.addView(champ, Ui.lp(this, -1, -2, 0f, 6, 0));
    }

    private TextView conseil(String texte) {
        TextView t = Ui.texte(this, texte, 12, AUTH_DISCRET, false);
        int p = Ui.dp(this, 12);
        t.setPadding(p, p, p, p);
        t.setBackground(Ui.rond(0xFF202B42, 14, this));
        return t;
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
        message.setText("");
        boolean avecOnglets = mode == CONNEXION || mode == INSCRIPTION;
        onglets.setVisibility(avecOnglets ? View.VISIBLE : View.GONE);
        marquerOnglet(ongletConnexion, mode == CONNEXION);
        marquerOnglet(ongletInscription, mode == INSCRIPTION);

        int c1, c2;
        String libelle;
        champPasse.setHint("Mot de passe");
        if (mode == CONNEXION) {
            intro.setText("Connectez-vous pour retrouver votre espace.");
            c1 = AUTH_ORANGE; c2 = AUTH_AMBRE; libelle = "Se connecter";
            ajouterChamp("ADRESSE E-MAIL", ligneEmail, 18);
            ajouterChamp("MOT DE PASSE", lignePasse, 14);
        } else if (mode == INSCRIPTION) {
            intro.setText("Créez votre compte en quelques étapes.");
            c1 = AUTH_ORANGE; c2 = AUTH_AMBRE; libelle = "Créer mon compte";
            ajouterChamp("NOM", ligneNom, 18);
            ajouterChamp("ADRESSE E-MAIL", ligneEmail, 12);
            ajouterChamp("MOT DE PASSE", lignePasse, 12);
            formulaire.addView(conseil("Votre numéro de série sera nécessaire pour récupérer votre compte."),
                    Ui.lp(this, -1, -2, 0f, 12, 0));
        } else if (mode == CONFIRMATION) {
            intro.setText("Encore une étape pour sécuriser votre compte.");
            c1 = AUTH_ORANGE; c2 = AUTH_AMBRE; libelle = "Confirmer mon compte";
            formulaire.addView(conseil("Conservez votre numéro de série dans un endroit sûr : il vous servira pour récupérer votre compte."),
                    Ui.lp(this, -1, -2, 0f, 4, 0));
            ajouterChamp("ADRESSE E-MAIL", ligneEmail, 14);
            ajouterChamp("NUMÉRO DE SÉRIE", ligneSerie, 12);
            ajouterChamp("CODE DE CONFIRMATION", ligneCode, 12);
        } else if (!etapeCode) {
            intro.setText("Récupérez simplement l’accès à votre compte.");
            c1 = AUTH_ORANGE; c2 = AUTH_AMBRE; libelle = "Obtenir le code";
            formulaire.addView(conseil("Saisissez l’adresse e-mail et le numéro de série utilisés à l’inscription."),
                    Ui.lp(this, -1, -2, 0f, 4, 0));
            ajouterChamp("ADRESSE E-MAIL", ligneEmail, 14);
            ajouterChamp("NUMÉRO DE SÉRIE", ligneSerie, 12);
        } else {
            intro.setText("Choisissez un nouveau mot de passe.");
            c1 = AUTH_ORANGE; c2 = AUTH_AMBRE; libelle = "Changer le mot de passe";
            ajouterChamp("ADRESSE E-MAIL", ligneEmail, 4);
            ajouterChamp("NUMÉRO DE SÉRIE", ligneSerie, 12);
            ajouterChamp("CODE DE RÉINITIALISATION", ligneCode, 12);
            ajouterChamp("NOUVEAU MOT DE PASSE", lignePasse, 12);
            champPasse.setHint("Nouveau mot de passe");
        }
        boolean passeFinal = mode == OUBLI && etapeCode || mode == CONNEXION || mode == INSCRIPTION;
        champPasse.setImeOptions(passeFinal ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);

        // zone de message (en bas) : affiche le numéro de série, le code et les erreurs
        if (message.getParent() != null) ((LinearLayout) message.getParent()).removeView(message);
        formulaire.addView(message, Ui.lp(this, -1, -2, 0f, 12, 0));

        action.setText(libelle);
        action.setBackground(Ui.onde(c1, c2, 20, this));
        formulaire.addView(action, Ui.lp(this, -1, -2, 0f, 12, 0));

        if (mode == CONNEXION) {
            formulaire.addView(lienOubli, Ui.lp(this, -2, -2, 0f, 8, 0));
            formulaire.addView(lienConfirmer, Ui.lp(this, -2, -2, 0f, 3, 0));
        }
        if (mode == CONFIRMATION || (mode == OUBLI && etapeCode)) formulaire.addView(lienRenvoyer, Ui.lp(this, -2, -2, 0f, 8, 0));
        if (mode == OUBLI || mode == CONFIRMATION) formulaire.addView(lienRetour, Ui.lp(this, -2, -2, 0f, 7, 0));
        ((LinearLayout.LayoutParams) action.getLayoutParams()).gravity = Gravity.CENTER;
    }

    private void copierSerie() {
        String t = serie();
        if (t.isEmpty()) { info("Aucun numéro de série à copier.", true); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Numéro de série", t));
        info("Numéro de série copié ✔ Collez-le dans vos notes.", false);
    }

    /**
     * Présente les identifiants dans une fenêtre dédiée : ils ne sont plus mélangés aux erreurs
     * ou aux messages de formulaire. Le partage ouvre uniquement la feuille Android, jamais un envoi.
     */
    private void afficherCodeDialog(String titre, String sousTitre, String numeroSerie, String code) {
        if (dialogCode != null && dialogCode.isShowing()) dialogCode.dismiss();
        final String serieSure = numeroSerie == null ? "" : numeroSerie.trim();
        final String codeSur = code == null ? "" : code.trim();
        if (codeSur.isEmpty()) {
            info("Le code n’a pas été reçu. Demandez un nouveau code pour continuer.", true);
            return;
        }
        final String libelleCode = mode == OUBLI ? "Code de réinitialisation" : "Code de confirmation";
        final String etiquetteCode = mode == OUBLI ? "CODE DE RÉINITIALISATION" : "CODE DE CONFIRMATION";

        LinearLayout contenu = new LinearLayout(this);
        contenu.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        contenu.setPadding(p, p, p, p);
        contenu.setBackground(fondCarte());

        TextView badge = Ui.texte(this, "ACCÈS À CONSERVER", 10, AUTH_AMBRE, true);
        badge.setLetterSpacing(0.12f);
        contenu.addView(badge);

        TextView entete = Ui.texte(this, titre, 22, AUTH_TEXTE, true);
        contenu.addView(entete, Ui.lp(this, -1, -2, 0f, 8, 0));

        TextView description = Ui.texte(this, sousTitre, 13, AUTH_DISCRET, false);
        contenu.addView(description, Ui.lp(this, -1, -2, 0f, 5, 0));

        contenu.addView(blocValeur("NUMÉRO DE SÉRIE", serieSure, false, () -> copierValeur("Numéro de série", serieSure)),
                Ui.lp(this, -1, -2, 0f, 18, 0));
        contenu.addView(blocValeur(etiquetteCode, codeSur, true, () -> copierValeur(libelleCode, codeSur)),
                Ui.lp(this, -1, -2, 0f, 10, 0));

        Button copierTout = boutonDialog("Copier le code et le numéro", 0xFF27344D, 0xFF34435F);
        copierTout.setTextColor(AUTH_TEXTE);
        copierTout.setOnClickListener(v -> copierValeur("Code et numéro de série",
                "Numéro de série : " + serieSure + "\nCode : " + codeSur));
        contenu.addView(copierTout, Ui.lp(this, -1, -2, 0f, 10, 0));

        TextView avertissement = conseil("Pour garder ces accès, partagez-les uniquement dans une conversation WhatsApp avec vous-même. Le code sert à valider ou récupérer votre compte.");
        contenu.addView(avertissement, Ui.lp(this, -1, -2, 0f, 12, 0));

        Button partager = boutonDialog("Partager sur WhatsApp", 0xFF1FAF73, 0xFF11885A);
        partager.setOnClickListener(v -> partagerCodes(serieSure, codeSur, libelleCode));
        contenu.addView(partager, Ui.lp(this, -1, -2, 0f, 14, 0));

        Button continuer = boutonDialog("Continuer dans l’application", AUTH_ORANGE, AUTH_AMBRE);
        continuer.setOnClickListener(v -> {
            champCode.setText(codeSur);
            if (dialogCode != null) dialogCode.dismiss();
            champCode.requestFocus();
        });
        contenu.addView(continuer, Ui.lp(this, -1, -2, 0f, 8, 0));

        ScrollView defilement = new ScrollView(this);
        defilement.setFillViewport(false);
        defilement.setClipToPadding(false);
        defilement.addView(contenu);

        AlertDialog dialogue = new AlertDialog.Builder(this).setView(defilement).create();
        dialogue.setCancelable(true);
        dialogue.setCanceledOnTouchOutside(false);
        dialogue.setOnDismissListener(d -> {
            if (dialogCode == dialogue) dialogCode = null;
        });
        dialogCode = dialogue;
        dialogue.show();
        if (dialogue.getWindow() != null) {
            dialogue.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int largeurEcran = getResources().getDisplayMetrics().widthPixels;
            int largeurMax = Ui.dp(this, 460);
            int marge = Ui.dp(this, 32);
            dialogue.getWindow().setLayout(Math.min(largeurEcran - marge, largeurMax),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private LinearLayout blocValeur(String libelle, String valeur, boolean important, Runnable actionCopie) {
        LinearLayout bloc = new LinearLayout(this);
        bloc.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 13);
        bloc.setPadding(p, p, p, p);
        bloc.setBackground(Ui.rond(AUTH_CHAMP, 16, this));

        TextView etiquette = Ui.texte(this, libelle, 10, AUTH_DISCRET, true);
        etiquette.setLetterSpacing(0.08f);
        bloc.addView(etiquette);

        LinearLayout ligneValeur = new LinearLayout(this);
        ligneValeur.setOrientation(LinearLayout.HORIZONTAL);
        ligneValeur.setGravity(Gravity.CENTER_VERTICAL);
        TextView contenu = Ui.texte(this, valeur.isEmpty() ? "—" : valeur,
                important ? 23 : 15, important ? AUTH_TEXTE : AUTH_AMBRE, true);
        contenu.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        contenu.setSingleLine(false);
        ligneValeur.addView(contenu, Ui.lp(this, 0, -2, 1f, 8, 0));

        Button copierValeur = boutonDialog("Copier", 0xFF27344D, 0xFF34435F);
        copierValeur.setTextColor(AUTH_AMBRE);
        copierValeur.setTextSize(12);
        copierValeur.setEnabled(!valeur.isEmpty());
        copierValeur.setAlpha(valeur.isEmpty() ? 0.5f : 1f);
        copierValeur.setOnClickListener(v -> actionCopie.run());
        ligneValeur.addView(copierValeur, Ui.lp(this, -2, -2, 0f, 4, 8));
        bloc.addView(ligneValeur);
        return bloc;
    }

    private Button boutonDialog(String texte, int couleur1, int couleur2) {
        Button b = new Button(this);
        b.setText(texte);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(AUTH_TEXTE);
        b.setTypeface(null, Typeface.BOLD);
        b.setStateListAnimator(null);
        b.setMinHeight(Ui.dp(this, 46));
        b.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        b.setBackground(Ui.onde(couleur1, couleur2, 14, this));
        Ui.pression(b);
        return b;
    }

    private void copierValeur(String etiquette, String valeur) {
        if (valeur == null || valeur.trim().isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(etiquette, valeur));
            Toast.makeText(this, etiquette + " copié", Toast.LENGTH_SHORT).show();
        }
    }

    private void partagerCodes(String numeroSerie, String code, String libelleCode) {
        String texte = Config.titreApp(this) + "\n"
                + "Numéro de série : " + numeroSerie + "\n"
                + libelleCode + " : " + code + "\n\n"
                + "Message personnel de sauvegarde — ne pas transférer à une autre personne.";
        Intent partage = new Intent(Intent.ACTION_SEND);
        partage.setType("text/plain");
        partage.putExtra(Intent.EXTRA_TEXT, texte);
        try {
            startActivity(Intent.createChooser(partage, "Choisir WhatsApp pour garder vos accès"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Aucune application de partage n’est disponible.", Toast.LENGTH_LONG).show();
        }
    }

    private void marquerOnglet(Button b, boolean actif) {
        if (actif) {
            b.setBackground(Ui.onde(AUTH_ORANGE, AUTH_AMBRE, 14, this));
            b.setTextColor(AUTH_TEXTE);
        } else {
            b.setBackground(null);
            b.setTextColor(AUTH_DISCRET);
        }
    }

    private void basculerPasse() {
        passeVisible = !passeVisible;
        champPasse.setTransformationMethod(passeVisible ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        champPasse.setSelection(champPasse.getText().length());
        oeil.setText(passeVisible ? "Masquer" : "Afficher");
    }

    private void info(String t, boolean erreur) {
        message.setTextColor(erreur ? 0xFFFFAAA6 : AUTH_AMBRE);
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

    /** Demande un code à la base (il faut l'email ET le numéro de série) puis ouvre la fenêtre de conservation. */
    private void demanderCode(final String objet) {
        if (occupe) return;
        if (!emailValide(email())) { info("Entrez une adresse email valide.", true); return; }
        if (serie().length() < 12) { info("Entrez votre numéro de série.", true); return; }
        final String e = email(), s = serie();
        message.setText("");
        occupe(true);
        new Thread(() -> {
            final Compte.Resultat r = Compte.demanderCode(e, s, objet);
            runOnUiThread(() -> {
                occupe(false);
                if (r.ok) {
                    boolean premiere = mode == OUBLI && !etapeCode;
                    if (mode == OUBLI) etapeCode = true;
                    construire();
                    afficherCodeDialog("Votre code est prêt",
                            mode == OUBLI ? "Saisissez ce code pour choisir un nouveau mot de passe."
                                    : "Saisissez ce code pour terminer la confirmation de votre compte.",
                            s, Compte.dernierCode);
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
                    afficherCodeDialog("Votre compte est prêt",
                            "Gardez ces deux informations : le numéro de série vous servira à récupérer votre compte.",
                            Compte.derniereSerie, Compte.dernierCode);
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
