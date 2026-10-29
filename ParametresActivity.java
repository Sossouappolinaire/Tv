package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Paramètres : compte, code PIN et empreinte digitale. */
public class ParametresActivity extends Activity {

    private LinearLayout securite;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        if (!Compte.connecte()) { finish(); return; }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        page.setPadding(p, p, p, p);

        Ui.ajouter(page, Ui.texte(this, "⚙ Paramètres", 24, Ui.TXT, true), 0);

        // ---- Compte
        LinearLayout compte = carte(0xFF6C7A89, 0xFF34495E);
        compte.addView(Ui.texte(this, "👤 Mon compte", 17, Ui.TXT, true));
        String nom = Compte.nom();
        compte.addView(Ui.texte(this, nom.isEmpty() ? "Nom non renseigné" : nom, 15, Ui.TXT, false), Ui.lp(this, -1, -2, 0f, 8, 0));
        compte.addView(Ui.texte(this, Compte.email(), 13, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 2, 0));
        Button sortir = Ui.boutonContour(this, "Se déconnecter", Ui.TXT);
        sortir.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Se déconnecter ?")
                .setMessage("Vous devrez saisir votre email et votre mot de passe pour revenir. Le code PIN sera supprimé.")
                .setPositiveButton("Se déconnecter", (d, w) -> {
                    Compte.deconnecter();
                    Intent i = new Intent(this, AuthActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Annuler", null)
                .show());
        compte.addView(sortir, Ui.lp(this, -1, -2, 0f, 12, 0));
        Ui.ajouter(page, compte, 14);
        Ui.entree(compte, 0);

        // ---- Version et mise à jour
        LinearLayout maj = carte(0xFF2E9E73, 0xFF1B6B4E);
        maj.addView(Ui.texte(this, "⬆ Mise à jour de l'application", 17, Ui.TXT, true));
        maj.addView(Ui.texte(this, "Version installée : " + MiseAJour.nomInstalle(this), 14, Ui.TXT, false), Ui.lp(this, -1, -2, 0f, 8, 0));
        maj.addView(Ui.texte(this, "Quand une nouvelle version sort, touchez « Mettre à jour » : elle remplace l'ancienne, sans désinstaller.",
                13, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 4, 0));
        Button chercherMaj = Ui.bouton(this, "🔍 Chercher une mise à jour", Ui.VERT);
        chercherMaj.setOnClickListener(v -> MiseAJour.verifier(this, true));
        maj.addView(chercherMaj, Ui.lp(this, -1, -2, 0f, 12, 0));
        Ui.ajouter(page, maj, 14);
        Ui.entree(maj, 1);

        // ---- Sécurité
        securite = carte(Ui.VIOLET, 0xFF3A7BFF);
        Ui.ajouter(page, securite, 14);
        Ui.entree(securite, 2);
        afficherSecurite();

        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.fond());
        sv.addView(page);
        setContentView(sv);
    }

    private LinearLayout carte(int c1, int c2) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.degrade(c1, c2, 20, this));
        l.setElevation(Ui.dp(this, 4));
        int q = Ui.dp(this, 16);
        l.setPadding(q, q, q, q);
        return l;
    }

    private void afficherSecurite() {
        securite.removeAllViews();
        securite.addView(Ui.texte(this, "🔒 Verrouillage de l'application", 17, Ui.TXT, true));

        if (!Securite.pinActif()) {
            securite.addView(Ui.texte(this, "Protégez l'accès avec un code PIN (4 à 6 chiffres). "
                    + "Il sera demandé à chaque ouverture, et après 30 secondes passées hors de l'application.",
                    13, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 8, 0));
            Button activer = Ui.bouton(this, "Activer le code PIN", Ui.VERT);
            activer.setOnClickListener(v -> nouveauPin(() -> {
                Toast.makeText(this, "Code PIN activé", Toast.LENGTH_SHORT).show();
                afficherSecurite();
            }));
            securite.addView(activer, Ui.lp(this, -1, -2, 0f, 12, 0));
            return;
        }

        securite.addView(Ui.texte(this, "✔ Code PIN activé", 14, 0xFF9BE8B8, true), Ui.lp(this, -1, -2, 0f, 8, 0));
        Button modifier = Ui.bouton(this, "Modifier le code PIN", Ui.ACCENT);
        modifier.setOnClickListener(v -> demanderPinActuel("Code PIN actuel", () -> nouveauPin(() -> {
            Toast.makeText(this, "Code PIN modifié", Toast.LENGTH_SHORT).show();
            afficherSecurite();
        })));
        securite.addView(modifier, Ui.lp(this, -1, -2, 0f, 10, 0));

        Button retirer = Ui.boutonContour(this, "Désactiver le code PIN", Ui.TXT);
        retirer.setOnClickListener(v -> demanderPinActuel("Code PIN actuel", () -> {
            Securite.retirerPin();
            Toast.makeText(this, "Code PIN désactivé", Toast.LENGTH_SHORT).show();
            afficherSecurite();
        }));
        securite.addView(retirer, Ui.lp(this, -1, -2, 0f, 8, 0));

        // Empreinte digitale
        securite.addView(Ui.texte(this, "👆 Empreinte digitale", 16, Ui.TXT, true), Ui.lp(this, -1, -2, 0f, 18, 0));
        if (!Securite.biometrieDisponible(this)) {
            securite.addView(Ui.texte(this, "Indisponible : ce téléphone n'a pas de capteur d'empreinte, "
                    + "ou aucune empreinte n'est enregistrée dans les réglages du téléphone.",
                    13, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 6, 0));
            return;
        }
        boolean on = Securite.biometrieActive();
        securite.addView(Ui.texte(this, on ? "Activée : l'empreinte ouvre l'application, le code PIN reste le secours."
                : "Désactivée : seul le code PIN est demandé.", 13, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 6, 0));
        Button bio = on ? Ui.boutonContour(this, "Désactiver l'empreinte", Ui.TXT)
                : Ui.bouton(this, "Activer l'empreinte digitale", Ui.ROSE);
        bio.setOnClickListener(v -> {
            if (on) {
                Securite.activerBiometrie(false);
                afficherSecurite();
            } else {
                Securite.demanderBiometrie(this, "Activer l'empreinte", "Posez votre doigt pour vérifier", new Securite.Retour() {
                    @Override public void reussi() {
                        Securite.activerBiometrie(true);
                        Toast.makeText(ParametresActivity.this, "Empreinte activée", Toast.LENGTH_SHORT).show();
                        afficherSecurite();
                    }
                    @Override public void echec() { /* annulé */ }
                });
            }
        });
        securite.addView(bio, Ui.lp(this, -1, -2, 0f, 10, 0));
    }

    // ------------------------------------------------------------------ boîtes de saisie du PIN

    private EditText champPin(String indice) {
        EditText e = Ui.champ(this, indice);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setGravity(Gravity.CENTER);
        e.setTextSize(22);
        e.setMaxLines(1);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        return e;
    }

    /** Demande le PIN actuel ; si juste, exécute la suite. */
    private void demanderPinActuel(String titre, Runnable suite) {
        if (Securite.attente() > 0) {
            Toast.makeText(this, "Trop d'essais. Réessayez dans " + Securite.attente() + " s", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText e = champPin("• • • •");
        LinearLayout l = new LinearLayout(this);
        int q = Ui.dp(this, 20);
        l.setPadding(q, q, q, 0);
        l.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(titre).setView(l)
                .setPositiveButton("Valider", (d, w) -> {
                    if (Securite.verifierPin(e.getText().toString())) suite.run();
                    else Toast.makeText(this, "Code incorrect", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Saisie d'un nouveau PIN (4 à 6 chiffres) avec confirmation. */
    private void nouveauPin(Runnable fini) {
        final EditText a = champPin("Nouveau code (4 à 6 chiffres)");
        final EditText c = champPin("Confirmer le code");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int q = Ui.dp(this, 20);
        l.setPadding(q, q, q, 0);
        l.addView(a, new LinearLayout.LayoutParams(-1, -2));
        l.addView(c, Ui.lp(this, -1, -2, 0f, 10, 0));
        new AlertDialog.Builder(this).setTitle("Choisir un code PIN").setView(l)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String x = a.getText().toString();
                    if (x.length() < 4) {
                        Toast.makeText(this, "Le code doit avoir 4 à 6 chiffres", Toast.LENGTH_LONG).show();
                    } else if (!x.equals(c.getText().toString())) {
                        Toast.makeText(this, "Les deux codes sont différents", Toast.LENGTH_LONG).show();
                    } else {
                        Securite.definirPin(x);
                        fini.run();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }
}
