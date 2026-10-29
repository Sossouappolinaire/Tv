package bj.radio;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TV Kouamé : une chaîne « en direct » faite des vidéos Facebook de la base (titre, canal, durée).
 * Chaque jour à 00h00 (heure du Bénin), le SERVEUR fabrique tout seul le programme du jour (section 8 de
 * supabase_setup.sql). Le programme est CONTINU, 24 h sur 24 : un film qui finit à 00h34 est suivi du film suivant
 * à 00h34 (le film commencé la veille a un début négatif). Aucun film répété tant qu'il en reste d'inédits.
 * En arrivant à 01h07, on tombe au milieu du programme en cours, à la bonne minute, et le suivant démarre tout seul.
 * TV Kouamé s'ouvre directement en plein écran (un appui sur l'image ou « Retour » revient à la page).
 * L'écran montre le logo « TV K » et, en bas, le bandeau d'annonces défilant.
 * L'heure vient du serveur (pas du téléphone).
 */
@OptIn(markerClass = UnstableApi.class)
public class TvKouameActivity extends Activity {

    private static final long JOUR_MS = 86_400_000L;

    private static final class Prog {
        String titre, lien, canal, serie;
        long dureeMs, debutMs;
        int episode;
    }

    private final List<Prog> progs = new ArrayList<>();
    private long totalMs = 0;
    private long refServeurMs = 0, refAppareil = 0;     // heure du serveur (ms depuis minuit) à l'instant refAppareil
    private long derniereSync = 0, dernierMaintenant = -1;
    private String jourServeur = "", dernierBandeau = "";

    private ExoPlayer player;
    private PlayerView vue;
    private TextView voile, etat, titreV, heure, suivant, programmeTxt, titreProgramme, logo, bandeau;
    private ProgressBar barre;
    private Button revoir, plein;
    private FrameLayout zone;
    private ScrollView infos;
    private LinearLayout page, entete;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tickRun = this::tick;
    private boolean actif = false, differe = false, pleinEcran = false, syncEnCours = false;
    private long debutCourant = -1;
    private int courant = -1, generation = 0, essaisProg = 0;
    private String prefetch = "";

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.fond());

        int p = Ui.dp(this, 14);
        entete = new LinearLayout(this);
        entete.setPadding(p, p, p, Ui.dp(this, 6));
        entete.addView(Ui.texte(this, "📺 TV Kouamé", 22, Ui.TXT, true));
        page.addView(entete);

        vue = new PlayerView(this);
        vue.setUseController(false);
        vue.setBackgroundColor(Color.BLACK);
        zone = new FrameLayout(this);
        zone.setBackgroundColor(Color.BLACK);
        zone.addView(vue, new FrameLayout.LayoutParams(-1, -1));
        voile = Ui.texte(this, "Chargement du programme…", 15, Ui.TXT, true);
        voile.setGravity(Gravity.CENTER);
        voile.setBackgroundColor(0x99000000);
        voile.setPadding(p, p, p, p);
        zone.addView(voile, new FrameLayout.LayoutParams(-1, -1));

        // Logo de la chaîne (coin haut-droit, toujours visible)
        logo = Ui.texte(this, "TV K", 15, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.rond(0xE6E91E63, 8, this));
        logo.setPadding(Ui.dp(this, 10), Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3));
        FrameLayout.LayoutParams lpLogo = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lpLogo.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 8), 0);
        zone.addView(logo, lpLogo);

        // Bandeau d'annonces défilant (bas de l'écran)
        bandeau = Ui.texte(this, "", 13, Color.WHITE, true);
        bandeau.setSingleLine(true);
        bandeau.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        bandeau.setMarqueeRepeatLimit(-1);
        bandeau.setHorizontallyScrolling(true);
        bandeau.setSelected(true);
        bandeau.setBackgroundColor(0xCC8E0F3A);
        bandeau.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        zone.addView(bandeau, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        page.addView(zone, new LinearLayout.LayoutParams(-1, Ui.dp(this, 230)));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(p, p, p, p);

        etat = Ui.texte(this, "", 14, Ui.JAUNE, true);
        titreV = Ui.texte(this, "", 18, Ui.TXT, true);
        heure = Ui.texte(this, "", 13, Ui.TXT2, false);
        barre = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barre.setMax(1000);
        suivant = Ui.texte(this, "", 14, Ui.TXT, false);
        Ui.ajouter(c, etat, 0);
        Ui.ajouter(c, titreV, 4);
        Ui.ajouter(c, heure, 4);
        Ui.ajouter(c, barre, 8);
        Ui.ajouter(c, suivant, 8);

        revoir = Ui.bouton(this, "⏮ Revoir depuis le début", Ui.VIOLET);
        revoir.setOnClickListener(v -> basculerDifferePlusDirect());
        Ui.ajouter(c, revoir, 12);
        plein = Ui.boutonContour(this, "⛶ Plein écran", Ui.TXT);
        plein.setOnClickListener(v -> basculerPlein());
        Ui.ajouter(c, plein, 8);

        titreProgramme = Ui.texte(this, "Programme du jour", 18, Ui.TXT, true);
        Ui.ajouter(c, titreProgramme, 20);
        programmeTxt = Ui.texte(this, "", 14, Ui.TXT2, false);
        Ui.ajouter(c, programmeTxt, 8);

        infos = new ScrollView(this);
        infos.addView(c);
        page.addView(infos, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);

        zone.setOnClickListener(v -> basculerPlein());   // un appui sur l'image : plein écran / retour à la page
        basculerPlein();                                  // TV Kouamé s'ouvre directement en plein écran
    }

    @Override
    protected void onResume() {
        super.onResume();
        actif = true;
        if (player == null) creerLecteur();
        differe = false;
        debutCourant = -1;                 // on revient au direct, à la bonne minute
        synchroniser(true);
    }

    @Override
    protected void onPause() {
        actif = false;
        h.removeCallbacks(tickRun);
        generation++;
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (pleinEcran) basculerPlein();
        else super.onBackPressed();
    }

    private void creerLecteur() {
        player = new ExoPlayer.Builder(this).build();
        vue.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                if (courant < 0 || courant >= progs.size()) return;
                if (essaisProg == 0) {          // le lien Facebook a peut-être expiré : on le cherche à nouveau
                    FbLien.oublier(progs.get(courant).lien);
                    relancer(true);
                } else {
                    voile("⚠ Ce programme ne peut pas être lu (" + e.getErrorCodeName() + "). Le suivant démarre à l'heure prévue.");
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                voile(null);                    // l'image est là : on retire le message
            }

            @Override
            public void onPlaybackStateChanged(int etat) {
                if (etat == Player.STATE_BUFFERING) voile("⏳ Chargement de la vidéo…");
                else if (etat == Player.STATE_READY) voile(null);
            }
        });
    }

    // ------------------------------------------------------------------ planning et heure

    /** Récupère le programme du jour (fabriqué par le serveur à 00h00) et l'heure du serveur. */
    private void synchroniser(final boolean premiere) {
        if (syncEnCours) return;
        syncEnCours = true;
        if (premiere) voile("Chargement du programme…");
        new Thread(() -> {
            final JSONObject j = Supabase.tvPlanning();
            final long t0 = SystemClock.elapsedRealtime();
            runOnUiThread(() -> {
                syncEnCours = false;
                if (!actif) return;
                if (j == null) {
                    if (progs.isEmpty()) voile("⚠ Programme indisponible. Vérifiez votre connexion internet. "
                            + "(L'administrateur doit avoir exécuté la section 8 de supabase_setup.sql.)");
                    else h.postDelayed(tickRun, 1000);
                    return;
                }
                List<Prog> nouv = new ArrayList<>();
                long total = 0;
                JSONArray a = j.optJSONArray("programmes");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null || o.optLong("duree_ms", 0) <= 0) continue;
                    Prog g = new Prog();
                    g.titre = texte(o, "titre");
                    g.lien = texte(o, "lien");
                    g.canal = texte(o, "canal");
                    g.serie = texte(o, "serie");
                    g.dureeMs = o.optLong("duree_ms", 0);
                    g.debutMs = o.has("debut_ms") ? o.optLong("debut_ms", total) : total;
                    g.episode = o.optInt("episode", 0);
                    if (g.lien.isEmpty()) continue;
                    nouv.add(g);
                    total = g.debutMs + g.dureeMs;
                }
                progs.clear();
                progs.addAll(nouv);
                totalMs = total;
                jourServeur = j.optString("jour", "");
                refServeurMs = j.optLong("maintenant_ms", 0);
                refAppareil = t0;
                derniereSync = t0;
                dernierMaintenant = -1;
                tick();
            });
        }).start();
    }

    private static String texte(JSONObject o, String cle) {
        String s = o.optString(cle, "");
        return "null".equals(s) ? "" : s;
    }

    /** Heure du serveur, en ms depuis minuit (heure du Bénin). */
    private long maintenant() {
        return ((refServeurMs + (SystemClock.elapsedRealtime() - refAppareil)) % JOUR_MS + JOUR_MS) % JOUR_MS;
    }

    /** Programme en cours : {index, avancée dans ce programme (ms), heure de son début (ms depuis minuit)} ; null si rien ne passe. */
    private long[] position(long now) {
        for (int i = 0; i < progs.size(); i++) {
            Prog g = progs.get(i);
            if (now >= g.debutMs && now < g.debutMs + g.dureeMs) return new long[]{i, now - g.debutMs, g.debutMs};
        }
        return null;
    }

    // ------------------------------------------------------------------ boucle d'une seconde

    private void tick() {
        h.removeCallbacks(tickRun);
        if (!actif) return;
        long now = maintenant();

        // minuit est passé : le serveur a fabriqué le programme du nouveau jour, on le récupère
        if (dernierMaintenant >= 0 && now < dernierMaintenant - 60_000L) {
            dernierMaintenant = -1;
            synchroniser(false);
            return;
        }
        dernierMaintenant = now;

        majTitreProgramme();
        if (progs.isEmpty() || totalMs <= 0) {
            ecranAttente("📭 Pas encore de programme. Il se fabrique tout seul chaque jour à 00h00 "
                    + "avec les films de « Films partagés ».");
            majBandeau("📺 TV K · Nouveau programme chaque jour à 00h00 · Ajoutez vos films dans « Films partagés »");
            h.postDelayed(() -> synchroniser(false), 15000);
            return;
        }

        long[] p = position(now);
        if (p == null) {                                     // ne devrait pas arriver : le programme couvre toute la journée
            ecranAttente("📺 TV K\n\nLe programme se met à jour…");
            etat.setText("⏳ Mise à jour du programme");
            titreV.setText("TV K");
            heure.setText("");
            barre.setProgress(0);
            suivant.setText("");
            majBandeau("📺 TV K · Programme continu, 24 h sur 24 · Nouveau programme chaque jour à 00h00 (heure du Bénin)");
            listeProgramme(-1);
            h.postDelayed(() -> synchroniser(false), 5000);
            return;
        }

        int idx = (int) p[0];
        long off = p[1], debut = p[2];
        courant = idx;

        boolean enLecture = player != null && (player.getPlaybackState() == Player.STATE_READY
                || player.getPlaybackState() == Player.STATE_BUFFERING);
        boolean memeProgramme = debutCourant != -1 && jourMod(debut) == jourMod(debutCourant);
        if (memeProgramme) debutCourant = debut;            // à minuit, même film : seul son début est recalculé
        else if (!(differe && enLecture)) lancer(idx, debut, false);

        majAffichage(idx, off, debut, now);

        if (SystemClock.elapsedRealtime() - derniereSync > 10 * 60_000L) synchroniser(false);
        h.postDelayed(tickRun, 1000);
    }

    /** Écran « TV K » sans lecture (pas de programme, ou fin de journée). */
    private void ecranAttente(String t) {
        if (player != null && player.getPlaybackState() != Player.STATE_IDLE) {
            player.stop();
            player.clearMediaItems();
        }
        debutCourant = -1;
        voile(t);
    }

    private void lancer(int idx, long debut, boolean reessai) {
        courant = idx;
        debutCourant = debut;
        differe = false;
        essaisProg = reessai ? 1 : 0;
        final int gen = ++generation;
        final Prog g = progs.get(idx);
        listeProgramme(idx);
        voile("📺 TV K · Chargement : " + titreDe(g) + "…");
        FbLien.Direct d = FbLien.deja(g.lien);
        if (d != null) {
            demarrer(d, gen);
            return;
        }
        new Thread(() -> {
            final FbLien.Direct r = FbLien.resoudre(g.lien);
            runOnUiThread(() -> {
                if (gen != generation || !actif) return;
                if (r == null) voile("⚠ Programme indisponible pour l'instant. Le suivant démarre à l'heure prévue.");
                else demarrer(r, gen);
            });
        }).start();
    }

    private void relancer(boolean reessai) {
        if (courant < 0 || courant >= progs.size()) return;
        lancer(courant, debutCourant, reessai);
    }

    /** Lance la lecture à la minute exacte du direct (le temps de chargement est rattrapé). */
    private void demarrer(FbLien.Direct d, int gen) {
        if (gen != generation || !actif || player == null || courant < 0 || courant >= progs.size()) return;
        Prog g = progs.get(courant);
        long off = Math.max(0, maintenant() - debutCourant);
        if (off >= g.dureeMs - 2000) {
            voile("⏳ Fin du programme…");
            return;
        }
        Map<String, String> entetes = new HashMap<>();
        entetes.put("Referer", "https://www.facebook.com/");
        DefaultHttpDataSource.Factory f = new DefaultHttpDataSource.Factory()
                .setUserAgent(d.ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(entetes);
        MediaSource src = new ProgressiveMediaSource.Factory(f).createMediaSource(MediaItem.fromUri(d.url));
        player.setMediaSource(src, off < 3000 ? 0 : off);
        player.prepare();
        player.play();
        voile("⏳ Chargement de la vidéo…");
        final int g2 = gen;
        vue.postDelayed(() -> {             // si rien ne démarre en 30 s : nouvelle tentative, puis message clair
            if (g2 != generation || !actif || player == null || courant < 0 || courant >= progs.size()) return;
            if (player.getPlaybackState() == Player.STATE_READY) return;
            if (essaisProg == 0) {
                FbLien.oublier(progs.get(courant).lien);
                relancer(true);
            } else {
                voile("⚠ La vidéo ne démarre pas. Vérifiez votre connexion internet.");
            }
        }, 30000);
    }

    // ------------------------------------------------------------------ affichage

    private void voile(String t) {
        if (t == null) {
            voile.setVisibility(View.GONE);
        } else {
            voile.setText(t);
            voile.setVisibility(View.VISIBLE);
        }
    }

    private String titreDe(Prog g) {
        String t = g.titre.isEmpty() ? "Vidéo" : LienPlateforme.titreCourt(g.titre, 5);
        if (!g.serie.isEmpty() || g.episode > 0) {
            t = (g.serie.isEmpty() ? "" : g.serie + " · ") + (g.episode > 0 ? "Épisode " + g.episode + " · " : "") + t;
        }
        return t;
    }

    /** Heure du jour (0 à 24 h) d'un instant exprimé en ms depuis minuit, même négatif (film commencé la veille). */
    private static long jourMod(long ms) {
        return ((ms % JOUR_MS) + JOUR_MS) % JOUR_MS;
    }

    private static String hm(long ms) {
        long m = (((ms % JOUR_MS) + JOUR_MS) % JOUR_MS) / 60000;
        return String.format(Locale.FRANCE, "%02dh%02d", m / 60, m % 60);
    }

    /** « Programme du jour · mercredi 28 octobre » */
    private void majTitreProgramme() {
        String t = "Programme du jour";
        try {
            java.util.Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).parse(jourServeur);
            if (d != null) t += " · " + new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(d);
        } catch (Exception ignored) {}
        if (!t.contentEquals(titreProgramme.getText())) titreProgramme.setText(t);
    }

    /** Le texte du bandeau n'est changé que s'il est différent (sinon le défilement repartirait à zéro). */
    private void majBandeau(String t) {
        if (t.equals(dernierBandeau)) return;
        dernierBandeau = t;
        bandeau.setText(t);
        bandeau.setSelected(true);
    }

    private void majAffichage(int idx, long off, long debut, long now) {
        Prog g = progs.get(idx);
        long fin = debut + g.dureeMs;
        Prog n = idx + 1 < progs.size() ? progs.get(idx + 1) : null;

        etat.setText(differe ? "⏪ Vous regardez en différé" : "🔴 EN DIRECT · " + (g.canal.isEmpty() ? "Kouamé TV" : g.canal));
        titreV.setText(titreDe(g));
        heure.setText(hm(debut) + " → " + hm(fin) + " · " + Supabase.duree(g.dureeMs)
                + " · reste " + Supabase.duree(Math.max(0, fin - now)));
        long avance = off;
        if (differe && player != null) avance = Math.max(0, player.getCurrentPosition());
        barre.setProgress((int) Math.min(1000, avance * 1000 / g.dureeMs));
        suivant.setText(n != null ? "À suivre à " + hm(fin) + " : " + titreDe(n)
                : "Ce programme se termine à " + hm(fin) + " : le nouveau programme du jour enchaîne ensuite.");
        revoir.setText(differe ? "🔴 Revenir en direct" : "⏮ Revoir depuis le début");

        // bandeau d'annonces
        StringBuilder b = new StringBuilder("📺 TV K · EN CE MOMENT : ").append(titreDe(g));
        if (n != null) b.append("   •   À SUIVRE ").append(hm(fin)).append(" : ").append(titreDe(n));
        if (idx + 2 < progs.size()) {
            Prog n2 = progs.get(idx + 2);
            b.append("   •   ENSUITE ").append(hm(n2.debutMs)).append(" : ").append(titreDe(n2));
        }
        b.append("   •   Nouveau programme chaque jour à 00h00 (heure du Bénin)   ");
        majBandeau(b.toString());

        if (!differe && player != null && player.getPlaybackState() == Player.STATE_ENDED) {
            voile("⏳ " + (n != null ? "Prochain programme à " + hm(fin) + " : " + titreDe(n)
                    : "Prochain programme à " + hm(fin)));
        }
        // on prépare le lien du programme suivant avant la fin
        if (n != null && fin - now < 90_000 && FbLien.deja(n.lien) == null && !n.lien.equals(prefetch)) {
            prefetch = n.lien;
            final String lien = n.lien;
            new Thread(() -> FbLien.resoudre(lien)).start();
        }
    }

    /** Programme complet de la journée : ✓ déjà passé, ▶ en cours, • à venir. */
    private void listeProgramme(int idxCourant) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < progs.size(); i++) {
            Prog g = progs.get(i);
            String marque = idxCourant < 0 ? "•" : (i < idxCourant ? "✓" : (i == idxCourant ? "▶" : "•"));
            sb.append(marque).append(' ').append(g.debutMs < 0 ? "00h00" : hm(g.debutMs)).append("  ")
                    .append(titreDe(g)).append(g.debutMs < 0 ? " (suite)" : "")
                    .append(" (").append(Supabase.duree(g.dureeMs)).append(")\n");
        }
        programmeTxt.setText(sb.length() == 0 ? "—" : sb.toString());
    }

    // ------------------------------------------------------------------ boutons

    private void basculerDifferePlusDirect() {
        if (player == null || courant < 0) return;
        if (differe) {
            differe = false;
            debutCourant = -1;              // le prochain tick relance le direct
            tick();
        } else {
            differe = true;
            if (player.getPlaybackState() == Player.STATE_READY) voile(null);
            player.seekTo(0);
            player.play();
        }
    }

    private void basculerPlein() {
        pleinEcran = !pleinEcran;
        entete.setVisibility(pleinEcran ? View.GONE : View.VISIBLE);
        infos.setVisibility(pleinEcran ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) zone.getLayoutParams();
        lp.height = pleinEcran ? -1 : Ui.dp(this, 230);
        zone.setLayoutParams(lp);
        setRequestedOrientation(pleinEcran ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        // barres du système masquées (vrai plein écran) ; un glissement depuis le bord les fait réapparaître un instant
        getWindow().getDecorView().setSystemUiVisibility(pleinEcran
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : 0);
        plein.setText(pleinEcran ? "⛶ Quitter le plein écran" : "⛶ Plein écran");
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        // les barres reviennent parfois (notification, volume) : on les recache
        if (focus && pleinEcran) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
