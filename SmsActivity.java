package bj.radio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lecteur de SMS : messages reçus et envoyés de ce téléphone.
 * Mise à jour automatique pendant que l'écran est ouvert (nouveaux SMS reçus ou envoyés).
 */
public class SmsActivity extends Activity {

    private static final int MAX_CHARGES = 3000;   // SMS lus au maximum par boîte
    private static final int PAGE = 100;           // SMS affichés par page

    private boolean recus = true;
    private int limite = PAGE;
    private boolean chargement = false;
    private boolean observe = false;
    private final List<String[]> tous = new ArrayList<>(); // {adresse, date, texte}
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);

    private Button btnRecus, btnEnvoyes;
    private EditText recherche;
    private TextView statut, banniere;
    private LinearLayout liste, zoneAcces;

    private final ContentObserver observateur = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            charger();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);

        Ui.ajouter(root, Ui.texte(this, "💬 Messages SMS", 24, Ui.TXT, true), 0);

        banniere = Ui.texte(this, "", 15, Ui.TXT, true);
        int bp = Ui.dp(this, 12);
        banniere.setPadding(bp, bp, bp, bp);
        Ui.ajouter(root, banniere, 10);
        Ui.ajouter(root, Ui.texte(this,
                "Vos SMS reçus et envoyés. Pendant que cet écran est ouvert, les nouveaux messages "
                + "(reçus ou envoyés) apparaissent automatiquement.", 13, Ui.TXT2, false), 6);

        LinearLayout onglets = new LinearLayout(this);
        btnRecus = Ui.bouton(this, "📥 Reçus", 0xFF2E9E73);
        btnEnvoyes = Ui.bouton(this, "📤 Envoyés", 0xFF474B53);
        btnRecus.setOnClickListener(v -> choisir(true));
        btnEnvoyes.setOnClickListener(v -> choisir(false));
        onglets.addView(btnRecus, new LinearLayout.LayoutParams(0, -2, 1f));
        onglets.addView(btnEnvoyes, Ui.lp(this, 0, -2, 1f, 0, 8));
        Ui.ajouter(root, onglets, 12);

        recherche = Ui.champ(this, "🔎 Rechercher (numéro ou texte)");
        recherche.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(Editable e) { limite = PAGE; afficher(); }
        });
        Ui.ajouter(root, recherche, 10);

        statut = Ui.texte(this, "", 12, Ui.JAUNE, false);
        Ui.ajouter(root, statut, 8);

        zoneAcces = new LinearLayout(this);
        zoneAcces.setOrientation(LinearLayout.VERTICAL);
        Ui.ajouter(root, zoneAcces, 4);

        liste = new LinearLayout(this);
        liste.setOrientation(LinearLayout.VERTICAL);
        Ui.ajouter(root, liste, 4);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accesSms()) {
            majBanniere(true);
            zoneAcces.removeAllViews();
            if (!observe) {
                getContentResolver().registerContentObserver(Uri.parse("content://sms"), true, observateur);
                observe = true;
            }
            charger();
        } else {
            demanderAcces(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (observe) {
            getContentResolver().unregisterContentObserver(observateur);
            observe = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (accesSms()) {
            onResume();
        } else {
            demanderAcces(true);
        }
    }

    // ---------- Autorisation ----------

    private boolean accesSms() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void majBanniere(boolean ok) {
        banniere.setText(ok
                ? "✅ Autorisé"
                : "⛔ Non autorisé — veuillez autoriser les messages");
        banniere.setBackground(Ui.rond(ok ? 0xFF2E9E73 : 0xFFC0392B, 12, this));
    }

    private void demanderAcces(boolean refuse) {
        majBanniere(false);
        zoneAcces.removeAllViews();
        liste.removeAllViews();
        statut.setText("");
        zoneAcces.addView(Ui.texte(this,
                refuse
                ? "L'accès aux SMS n'a pas été accordé. Sur Android 13 et plus, pour une appli installée "
                  + "hors boutique, ouvrez « Infos de l'appli », touchez ⋮ (en haut à droite) puis "
                  + "« Autoriser les paramètres restreints ». Ensuite, dans « Autorisations », activez « SMS »."
                : "Pour lire vos SMS, l'application a besoin de l'autorisation « SMS ». Les messages restent "
                  + "sur votre téléphone : l'application ne les envoie nulle part.",
                13, Ui.TXT2, false));
        Button b = Ui.bouton(this, refuse ? "Ouvrir les infos de l'appli" : "Autoriser la lecture des SMS",
                0xFF2E9E73);
        b.setOnClickListener(v -> {
            if (refuse) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_SMS}, 9);
            }
        });
        zoneAcces.addView(b, Ui.lp(this, -1, -2, 0f, 10, 0));
    }

    // ---------- Chargement ----------

    private void choisir(boolean r) {
        recus = r;
        limite = PAGE;
        btnRecus.setBackground(Ui.rond(r ? 0xFF2E9E73 : Ui.CARD, 14, this));
        btnEnvoyes.setBackground(Ui.rond(r ? Ui.CARD : 0xFF2E9E73, 14, this));
        if (accesSms()) charger();
    }

    private void charger() {
        if (chargement || !accesSms()) return;
        chargement = true;
        final boolean boite = recus;
        statut.setText("Lecture des messages…");
        new Thread(() -> {
            List<String[]> res = new ArrayList<>();
            Uri uri = boite ? Telephony.Sms.Inbox.CONTENT_URI : Telephony.Sms.Sent.CONTENT_URI;
            try (Cursor c = getContentResolver().query(uri,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY},
                    null, null, Telephony.Sms.DEFAULT_SORT_ORDER)) {
                if (c != null) {
                    while (c.moveToNext() && res.size() < MAX_CHARGES) {
                        String adr = c.getString(0);
                        long date = c.getLong(1);
                        String txt = c.getString(2);
                        res.add(new String[]{adr == null ? "?" : adr, fmt.format(new Date(date)),
                                txt == null ? "" : txt});
                    }
                }
            } catch (Exception e) {
                // accès refusé ou boîte illisible : on affichera simplement 0 message
            }
            runOnUiThread(() -> {
                chargement = false;
                if (boite != recus) { charger(); return; } // l'onglet a changé pendant la lecture
                tous.clear();
                tous.addAll(res);
                afficher();
            });
        }).start();
    }

    // ---------- Affichage ----------

    private void afficher() {
        liste.removeAllViews();
        String q = recherche.getText().toString().trim().toLowerCase(Locale.getDefault());
        List<String[]> filtres = new ArrayList<>();
        for (String[] m : tous) {
            if (q.isEmpty() || m[0].toLowerCase(Locale.getDefault()).contains(q)
                    || m[2].toLowerCase(Locale.getDefault()).contains(q)) filtres.add(m);
        }
        String nom = recus ? "reçu(s)" : "envoyé(s)";
        statut.setText(filtres.size() + " SMS " + nom + (tous.size() >= MAX_CHARGES
                ? " (les " + MAX_CHARGES + " plus récents)" : ""));
        if (filtres.isEmpty()) {
            liste.addView(Ui.texte(this, "Aucun message.", 14, Ui.TXT2, false));
            return;
        }
        int n = Math.min(limite, filtres.size());
        for (int i = 0; i < n; i++) {
            String[] m = filtres.get(i);
            LinearLayout carte = new LinearLayout(this);
            carte.setOrientation(LinearLayout.VERTICAL);
            carte.setBackground(Ui.rond(Ui.CARD, 14, this));
            int q2 = Ui.dp(this, 12);
            carte.setPadding(q2, q2, q2, q2);
            LinearLayout haut = new LinearLayout(this);
            haut.addView(Ui.texte(this, (recus ? "De " : "À ") + m[0], 14, Ui.JAUNE, true),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            haut.addView(Ui.texte(this, m[1], 11, Ui.TXT2, false));
            carte.addView(haut);
            TextView corps = Ui.texte(this, m[2], 14, Ui.TXT, false);
            corps.setTextIsSelectable(true);
            carte.addView(corps, Ui.lp(this, -1, -2, 0f, 6, 0));
            liste.addView(carte, Ui.lp(this, -1, -2, 0f, 8, 0));
        }
        if (n < filtres.size()) {
            Button plus = Ui.bouton(this, "Afficher plus (" + (filtres.size() - n) + " restants)", 0xFF3A7BD5);
            plus.setOnClickListener(v -> { limite += PAGE; afficher(); });
            liste.addView(plus, Ui.lp(this, -1, -2, 0f, 10, 0));
        }
    }
}
