package bj.radio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * « Films partagés » : les vidéos dont un utilisateur a collé le lien sont partagées dans la base Supabase.
 * 1) on choisit d'abord Séries, Films ou Musique ;
 * 2) chaque vidéo propose « Lire » (la vidéo se joue dans la carte) ou « Télécharger » (le lien est analysé
 *    en arrière-plan, puis les formats SD / HD sont montrés avec leur taille vérifiée).
 */
@OptIn(markerClass = UnstableApi.class)
public class FilmsFacebookActivity extends Activity {

    private static final int PAR_PAGE = 20;

    private LinearLayout liste;
    private TextView statut;
    private Button bPlus;
    private EditText champ;
    private int decalage = 0;
    private String recherche = "";
    private String filtreCat = "";      // "", serie, film ou chanson
    private volatile boolean charge = false;
    private int generation = 0;

    private View vueChoix, vueListe;
    private TextView titreListe;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.fond());
        int p = Ui.dp(this, 16);

        // ----- Écran 1 : on choisit d'abord série, film ou musique -----
        LinearLayout choix = new LinearLayout(this);
        choix.setOrientation(LinearLayout.VERTICAL);
        choix.setPadding(p, p, p, p);
        Ui.ajouter(choix, Ui.texte(this, "🎞 Films partagés", 24, Ui.TXT, true), 0);
        Ui.ajouter(choix, Ui.texte(this, "Que voulez-vous voir ou télécharger ?", 14, Ui.TXT2, false), 6);
        String[][] cats = {{"serie", "📺  Séries"}, {"film", "🎬  Films"}, {"chanson", "🎵  Musique"}};
        for (final String[] c : cats) {
            Button bc = Ui.bouton(this, c[1], "serie".equals(c[0]) ? Ui.VIOLET : "film".equals(c[0]) ? Ui.ACCENT : Ui.ROSE);
            bc.setTextSize(18);
            bc.setOnClickListener(v -> ouvrirCategorie(c[0], c[1]));
            choix.addView(bc, Ui.lp(this, -1, Ui.dp(this, 72), 0f, 16, 0));
        }
        Button tout = Ui.lien(this, "Tout voir", Ui.TXT2);
        tout.setOnClickListener(v -> ouvrirCategorie("", "🎞 Tout"));
        choix.addView(tout, Ui.lp(this, -2, -2, 0f, 14, 0));
        ScrollView svChoix = new ScrollView(this);
        svChoix.addView(choix);
        vueChoix = svChoix;
        page.addView(svChoix, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ----- Écran 2 : la liste de la catégorie choisie -----
        LinearLayout contenu = new LinearLayout(this);
        contenu.setOrientation(LinearLayout.VERTICAL);
        contenu.setPadding(p, p, p, p);

        LinearLayout entete = new LinearLayout(this);
        entete.setGravity(Gravity.CENTER_VERTICAL);
        Button retour = Ui.bouton(this, "←", Ui.VIOLET);
        retour.setOnClickListener(v -> versChoix());
        entete.addView(retour, Ui.lp(this, -2, -2, 0f, 0, 0));
        titreListe = Ui.texte(this, "", 22, Ui.TXT, true);
        entete.addView(titreListe, Ui.lp(this, 0, -2, 1f, 0, 12));
        Ui.ajouter(contenu, entete, 0);

        LinearLayout rech = new LinearLayout(this);
        rech.setGravity(Gravity.CENTER_VERTICAL);
        champ = Ui.champ(this, "Rechercher dans les titres…");
        champ.setSingleLine(true);
        champ.setInputType(InputType.TYPE_CLASS_TEXT);
        champ.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        champ.setOnEditorActionListener((v, a, e) -> { chercher(); return true; });
        Button ok = Ui.bouton(this, "🔍", Ui.VIOLET);
        ok.setOnClickListener(v -> chercher());
        rech.addView(champ, Ui.lp(this, 0, -2, 1f, 0, 0));
        rech.addView(ok, Ui.lp(this, -2, -2, 0f, 0, 8));
        Ui.ajouter(contenu, rech, 12);

        statut = Ui.texte(this, "", 13, Ui.JAUNE, false);
        Ui.ajouter(contenu, statut, 10);

        liste = new LinearLayout(this);
        liste.setOrientation(LinearLayout.VERTICAL);
        Ui.ajouter(contenu, liste, 6);

        bPlus = Ui.bouton(this, "Charger plus", Ui.VIOLET);
        bPlus.setVisibility(View.GONE);
        bPlus.setOnClickListener(v -> charger());
        Ui.ajouter(contenu, bPlus, 10);

        ScrollView sv = new ScrollView(this);
        sv.addView(contenu);
        vueListe = sv;
        sv.setVisibility(View.GONE);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);

        surveillerReseau();
    }

    private void ouvrirCategorie(String cat, String titre) {
        filtreCat = cat;
        titreListe.setText(titre);
        champ.setText("");
        vueChoix.setVisibility(View.GONE);
        vueListe.setVisibility(View.VISIBLE);
        chercher();
    }

    private void versChoix() {
        arreterLecture();
        generation++;
        charge = false;
        liste.removeAllViews();
        vueListe.setVisibility(View.GONE);
        vueChoix.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (vueListe != null && vueListe.getVisibility() == View.VISIBLE) {
            versChoix();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (joueur != null) joueur.pause();
        super.onPause();
    }

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback rappelReseau;

    /** Dès que le téléphone se (re)connecte à internet, la liste se recharge toute seule si elle est vide ou en erreur. */
    private void surveillerReseau() {
        try {
            cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            rappelReseau = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    runOnUiThread(() -> {
                        if (vueListe != null && vueListe.getVisibility() == View.VISIBLE && decalage == 0 && !charge) charger();
                    });
                }
            };
            cm.registerDefaultNetworkCallback(rappelReseau);
        } catch (Exception ignore) {
            // pas de surveillance : le bouton de recherche et « Charger plus » restent utilisables
        }
    }

    @Override
    protected void onDestroy() {
        arreterLecture();
        try {
            if (cm != null && rappelReseau != null) cm.unregisterNetworkCallback(rappelReseau);
        } catch (Exception ignore) {
            // déjà retiré
        }
        super.onDestroy();
    }

    private void chercher() {
        arreterLecture();
        recherche = champ.getText().toString().trim();
        decalage = 0;
        generation++;
        liste.removeAllViews();
        bPlus.setVisibility(View.GONE);
        charge = false;
        charger();
    }

    private void charger() {
        if (charge) return;
        charge = true;
        final int g = generation;
        final int debut = decalage;
        final String rq = recherche;
        final String cat = filtreCat;
        statut.setText("Chargement…");
        bPlus.setEnabled(false);
        new Thread(() -> {
            final JSONArray films = Supabase.lister(debut, PAR_PAGE, rq, cat);
            runOnUiThread(() -> {
                if (g != generation) return;
                charge = false;
                bPlus.setEnabled(true);
                if (films == null) {
                    erreur();
                    return;
                }
                for (int i = 0; i < films.length(); i++) liste.addView(carte(films.optJSONObject(i)),
                        Ui.lp(this, -1, -2, 0f, 10, 0));
                decalage += films.length();
                majPlus(films.length() >= PAR_PAGE);
                statut.setText(decalage == 0
                        ? (rq.isEmpty() ? "Aucun film pour l'instant. Collez un lien dans « Télécharger Facebook » : il apparaîtra ici."
                                : "Aucun résultat pour « " + rq + " ».")
                        : decalage + " film(s) affiché(s).");
            });
        }).start();
    }

    private void majPlus(boolean encore) {
        bPlus.setVisibility(encore ? View.VISIBLE : View.GONE);
    }

    private void erreur() {
        String e = Supabase.derniereErreur;
        if ("CLE_MANQUANTE".equals(e)) {
            statut.setText("⚠ La base n'est pas encore reliée : ouvrez Supabase.java et collez la clé « anon public » "
                    + "de votre projet dans CLE (Supabase › Project Settings › API).");
        } else if ("TABLE_MANQUANTE".equals(e)) {
            statut.setText("⚠ La table « films_facebook » n'existe pas encore : exécutez le fichier "
                    + "supabase_setup.sql dans Supabase › SQL Editor.");
        } else {
            statut.setText("⚠ " + (e.isEmpty() ? "Chargement impossible." : e));
        }
        bPlus.setVisibility(decalage > 0 ? View.VISIBLE : View.GONE);
    }

    private View carte(JSONObject f) {
        LinearLayout carte = new LinearLayout(this);
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setBackground(Ui.rond(Ui.CARD, 16, this));
        int q = Ui.dp(this, 12);
        carte.setPadding(q, q, q, q);

        String titre = f.optString("titre", "");
        if (titre.isEmpty() || "null".equals(titre)) titre = "Vidéo Facebook";
        TextView tt = Ui.texte(this, titre, 15, Ui.TXT, true);
        tt.setMaxLines(3);
        carte.addView(tt);
        final long dureeMs = f.optLong("duree_ms", 0);
        carte.addView(Ui.texte(this, "⏱ " + Supabase.duree(dureeMs), 12, Ui.TXT2, false),
                Ui.lp(this, -1, -2, 0f, 4, 0));

        // Zone sous les boutons : le lecteur (Lire) ou les formats (Télécharger)
        final LinearLayout zone = new LinearLayout(this);
        zone.setOrientation(LinearLayout.VERTICAL);
        zone.setVisibility(View.GONE);

        final String lien = f.optString("lien", "");
        LinearLayout actions = new LinearLayout(this);
        Button bLire = Ui.bouton(this, "▶ Lire", Ui.VIOLET);
        // Vidéo déjà téléchargée sur ce téléphone : « Lire » depuis le fichier, plus de « Télécharger »
        bLire.setOnClickListener(v -> {
            File local = Telecharges.fichier(this, lien);
            if (local != null) lireLocal(local, zone); else lire(lien, zone);
        });
        Button bDl = Ui.bouton(this, "⬇ Télécharger", Ui.ACCENT);
        bDl.setOnClickListener(v -> analyserPourTelecharger(lien, dureeMs, zone));
        actions.addView(bLire, Ui.lp(this, 0, -2, 1f, 0, 0));
        actions.addView(bDl, Ui.lp(this, 0, -2, 1f, 0, 8));
        bDl.setVisibility(Telecharges.fichier(this, lien) != null ? View.GONE : View.VISIBLE);
        carte.setTag(new Object[]{lien, bDl});
        carte.addView(actions, Ui.lp(this, -1, -2, 0f, 10, 0));
        carte.addView(zone, Ui.lp(this, -1, -2, 0f, 10, 0));
        return carte;
    }

    /** Au retour sur l'écran (ex. fin d'un téléchargement) : met à jour le bouton « Télécharger » de chaque carte. */
    @Override
    protected void onResume() {
        super.onResume();
        if (liste == null) return;
        for (int i = 0; i < liste.getChildCount(); i++) {
            Object t = liste.getChildAt(i).getTag();
            if (t instanceof Object[]) {
                Object[] o = (Object[]) t;
                ((View) o[1]).setVisibility(Telecharges.fichier(this, (String) o[0]) != null ? View.GONE : View.VISIBLE);
            }
        }
    }

    /** Lecture depuis le fichier téléchargé : fonctionne sans internet. */
    private void lireLocal(File fichier, LinearLayout zone) {
        arreterLecture();
        zoneLecture = zone;
        zone.removeAllViews();
        zone.setVisibility(View.VISIBLE);
        final int g = genLecture;
        joueur = new ExoPlayer.Builder(this).build();
        vueJoueur = new PlayerView(this);
        vueJoueur.setUseController(true);
        zone.addView(vueJoueur, new LinearLayout.LayoutParams(-1, Ui.dp(this, 220)));
        vueJoueur.setPlayer(joueur);
        final TextView etat = Ui.texte(this, "✔ Lecture depuis votre téléphone (sans internet)", 12, Ui.JAUNE, false);
        zone.addView(etat, Ui.lp(this, -1, -2, 0f, 6, 0));
        joueur.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                if (g != genLecture) return;
                etat.setText("⚠ Le fichier téléchargé ne peut pas être lu. Supprimez-le puis retéléchargez la vidéo.");
            }
        });
        joueur.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(fichier)));
        joueur.prepare();
        joueur.play();
    }

    // =====================================================================
    //  LIRE : la vidéo se joue dans la carte
    // =====================================================================

    private ExoPlayer joueur;
    private PlayerView vueJoueur;
    private LinearLayout zoneLecture;
    private int genLecture = 0;

    private void arreterLecture() {
        genLecture++;
        if (joueur != null) {
            try { joueur.release(); } catch (Exception ignore) { /* déjà libéré */ }
            joueur = null;
        }
        vueJoueur = null;
        if (zoneLecture != null) {
            zoneLecture.removeAllViews();
            zoneLecture.setVisibility(View.GONE);
            zoneLecture = null;
        }
    }

    private void lire(final String lien, final LinearLayout zone) {
        arreterLecture();
        zoneLecture = zone;
        zone.removeAllViews();
        zone.setVisibility(View.VISIBLE);
        final TextView etat = Ui.texte(this, "⏳ Chargement de la vidéo…", 13, Ui.JAUNE, false);
        zone.addView(etat);
        final int g = genLecture;
        new Thread(() -> {
            final List<FbLien.Format> formats = FbLien.formatsLecture(lien);
            runOnUiThread(() -> {
                if (g != genLecture || isFinishing()) return;
                if (formats.isEmpty()) {
                    etat.setText("⚠ Lecture impossible : la vidéo est introuvable ou privée.");
                    return;
                }
                // sélecteur de format (seulement s'il y a le choix) : le format bas est lu par défaut
                final LinearLayout choix = new LinearLayout(this);
                if (formats.size() > 1) {
                    for (int i = 0; i < formats.size(); i++) {
                        final int idx = i;
                        FbLien.Format fm = formats.get(i);
                        Button bf = Ui.bouton(this, ("sd".equals(fm.nom) ? "SD " : "HD ") + fm.resolution(),
                                i == 0 ? Ui.VIOLET : Ui.CARD);
                        bf.setTag(idx);
                        bf.setOnClickListener(v -> {
                            majChoixLecture(choix, idx);
                            demarrerLecture(formats, idx, zone, etat, g);
                        });
                        choix.addView(bf, Ui.lp(this, 0, -2, 1f, 0, i == 0 ? 0 : 8));
                    }
                    zone.addView(choix, Ui.lp(this, -1, -2, 0f, 8, 0));
                }
                demarrerLecture(formats, 0, zone, etat, g);
            });
        }).start();
    }

    /** Met en valeur le format en cours de lecture dans le sélecteur. */
    private void majChoixLecture(LinearLayout choix, int actif) {
        for (int i = 0; i < choix.getChildCount(); i++) {
            View bf = choix.getChildAt(i);
            bf.setBackground(Ui.rond(i == actif ? Ui.VIOLET : Ui.CARD, 12, this));
        }
    }

    private void demarrerLecture(final List<FbLien.Format> formats, final int index,
                                 final LinearLayout zone, final TextView etat, final int g) {
        if (g != genLecture || isFinishing()) return;
        if (joueur != null) {
            try { joueur.release(); } catch (Exception ignore) { /* déjà libéré */ }
            joueur = null;
        }
        final FbLien.Format fm = formats.get(index);
        Map<String, String> entetes = new HashMap<>();
        entetes.put("Referer", "https://www.facebook.com/");
        DefaultHttpDataSource.Factory f = new DefaultHttpDataSource.Factory()
                .setUserAgent(fm.ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(entetes);
        ProgressiveMediaSource.Factory usine = new ProgressiveMediaSource.Factory(f);
        MediaSource src = usine.createMediaSource(MediaItem.fromUri(fm.url));
        if (fm.audio != null) src = new MergingMediaSource(src, usine.createMediaSource(MediaItem.fromUri(fm.audio)));   // image et son séparés

        joueur = new ExoPlayer.Builder(this).build();
        if (vueJoueur == null) {
            vueJoueur = new PlayerView(this);
            vueJoueur.setUseController(true);
            zone.addView(vueJoueur, 0, new LinearLayout.LayoutParams(-1, Ui.dp(this, 220)));
        }
        vueJoueur.setPlayer(joueur);
        etat.setText("⏳ Chargement de la vidéo…");
        etat.setVisibility(View.VISIBLE);
        joueur.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int etatLecture) {
                if (g == genLecture && etatLecture == Player.STATE_READY) etat.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                if (g != genLecture) return;
                etat.setVisibility(View.VISIBLE);
                if (index + 1 < formats.size()) {
                    etat.setText("⚠ Ce format ne démarre pas : essai de l'autre…");
                    zone.post(() -> demarrerLecture(formats, index + 1, zone, etat, g));
                } else {
                    etat.setText("⚠ La vidéo ne démarre pas. Vérifiez votre connexion internet puis réessayez.");
                }
            }
        });
        joueur.setMediaSource(src);
        joueur.prepare();
        joueur.play();
    }

    // =====================================================================
    //  TÉLÉCHARGER : analyse en arrière-plan, puis formats SD / HD avec leur taille
    // =====================================================================

    private static String tailleTexte(long o) {
        if (o <= 0) return "taille inconnue";
        if (o >= 1073741824L) return String.format(Locale.FRANCE, "%.2f Go", o / 1073741824.0);
        return String.format(Locale.FRANCE, "%.0f Mo", o / 1048576.0);
    }

    private void analyserPourTelecharger(final String lien, final long dureeMs, final LinearLayout zone) {
        arreterLecture();
        zone.removeAllViews();
        zone.setVisibility(View.VISIBLE);
        zone.addView(Ui.texte(this, "🔎 Analyse de la vidéo en cours… (quelques secondes)", 13, Ui.JAUNE, false));
        new Thread(() -> {
            final List<FbLien.Format> formats = FbLien.formats(lien, dureeMs);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                zone.removeAllViews();
                if (formats.isEmpty()) {
                    zone.addView(Ui.texte(this, "⚠ Aucun format trouvé : la vidéo est peut-être privée ou supprimée.",
                            13, Ui.JAUNE, false));
                    return;
                }
                zone.addView(Ui.texte(this, "Choisissez le format à télécharger :", 13, Ui.TXT2, true));
                for (FbLien.Format fm : formats) afficherFormat(zone, fm, lien, dureeMs);
            });
        }).start();
    }

    private void afficherFormat(LinearLayout zone, final FbLien.Format fm, final String lien, final long dureeMs) {
        boolean sd = "sd".equals(fm.nom);
        long t = fm.taille();
        String taille = t <= 0 ? "taille inconnue" : (fm.tailleReelle() ? tailleTexte(t) : "≈ " + tailleTexte(t));
        Button b = Ui.bouton(this, (sd ? "SD · " : "HD · ") + fm.resolution() + " · " + taille,
                sd ? Ui.ACCENT : Ui.VIOLET);
        b.setOnClickListener(v -> {
            Intent i = new Intent(this, FacebookActivity.class)
                    .putExtra("dl_url", fm.url)
                    .putExtra("dl_audio", fm.audio)
                    .putExtra("dl_ua", fm.ua)
                    .putExtra("dl_nom", fm.nom)
                    .putExtra("dl_res", fm.resolution())
                    .putExtra("dl_duree", dureeMs)
                    .putExtra("dl_taille_v", fm.tailleV)
                    .putExtra("dl_taille_a", fm.tailleA)
                    .putExtra("dl_lien", lien);
            startActivity(i);
        });
        zone.addView(b, Ui.lp(this, -1, -2, 0f, 8, 0));
        String note = null;
        if (fm.viaManifeste && sd) {
            note = "✔ Vrai " + fm.resolution() + " (image et son rassemblés au téléchargement). Le lien « SD » de Facebook "
                    + "pèse beaucoup plus pour cette vidéo : il n'a pas été retenu.";
        } else if (fm.suspect) {
            note = "⚠ Ce fichier pèse plus lourd qu'un vrai " + fm.resolution() + " : Facebook ne propose pas de version "
                    + "plus légère pour cette vidéo. La taille affichée est la taille réelle.";
        } else if (!fm.tailleReelle() && t > 0) {
            note = "Taille estimée d'après le débit annoncé par Facebook.";
        }
        if (note != null) zone.addView(Ui.texte(this, note, 11, Ui.TXT2, false), Ui.lp(this, -1, -2, 0f, 2, 0));
    }
}
