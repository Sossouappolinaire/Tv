package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lecteur d'une vidéo du téléphone, avec traduction :
 *  - les sous-titres de la vidéo (pistes intégrées ou fichier .srt/.vtt chargé) sont traduits
 *    en direct vers la langue choisie et affichés par-dessus l'image ;
 *  - la traduction peut être lue à voix haute (voix Android, le son d'origine baisse pendant ce temps).
 * La traduction a besoin d'internet. Les voix Android doivent être installées pour la langue voulue.
 */
public class LecteurVideoActivity extends Activity {

    /** Nom|code (codes de Google Traduction). Les plus courantes d'abord. */
    static final String[] LANGUES = {
        "Français|fr", "Anglais|en", "Espagnol|es", "Portugais|pt", "Allemand|de", "Italien|it",
        "Néerlandais|nl", "Russe|ru", "Arabe|ar", "Chinois (simplifié)|zh-CN", "Chinois (traditionnel)|zh-TW",
        "Japonais|ja", "Coréen|ko", "Hindi|hi", "Turc|tr", "Swahili|sw", "Hausa|ha", "Yoruba|yo",
        "Igbo|ig", "Lingala|ln", "Twi (akan)|ak", "Éwé|ee", "Bambara|bm", "Kinyarwanda|rw", "Zoulou|zu",
        "Xhosa|xh", "Amharique|am", "Somali|so", "Malgache|mg", "Shona|sn", "Sesotho|st", "Chichewa|ny",
        "Afrikaans|af", "Albanais|sq", "Arménien|hy", "Azéri|az", "Basque|eu", "Biélorusse|be",
        "Bengali|bn", "Bosniaque|bs", "Bulgare|bg", "Birman|my", "Catalan|ca", "Cebuano|ceb",
        "Corse|co", "Croate|hr", "Tchèque|cs", "Danois|da", "Espéranto|eo", "Estonien|et",
        "Philippin (tagalog)|tl", "Finnois|fi", "Galicien|gl", "Géorgien|ka", "Grec|el", "Gujarati|gu",
        "Créole haïtien|ht", "Hawaïen|haw", "Hébreu|he", "Hongrois|hu", "Islandais|is", "Indonésien|id",
        "Irlandais|ga", "Javanais|jv", "Kannada|kn", "Kazakh|kk", "Khmer|km", "Kurde|ku", "Kirghize|ky",
        "Lao|lo", "Latin|la", "Letton|lv", "Lituanien|lt", "Luxembourgeois|lb", "Macédonien|mk",
        "Malais|ms", "Malayalam|ml", "Maltais|mt", "Maori|mi", "Marathi|mr", "Mongol|mn", "Népalais|ne",
        "Norvégien|no", "Pachto|ps", "Persan|fa", "Polonais|pl", "Pendjabi|pa", "Roumain|ro",
        "Serbe|sr", "Cingalais|si", "Slovaque|sk", "Slovène|sl", "Soundanais|su", "Suédois|sv",
        "Tadjik|tg", "Tamoul|ta", "Télougou|te", "Thaï|th", "Ukrainien|uk", "Ourdou|ur", "Ouzbek|uz",
        "Vietnamien|vi", "Gallois|cy", "Yiddish|yi"
    };

    // ----- vues et lecteur -----
    private ExoPlayer player;
    private PlayerView vue;
    private TextView overlay, tvStatut;
    private FrameLayout zoneVideo;
    private LinearLayout root;
    private final List<View> cachables = new ArrayList<>();
    private boolean plein = false;
    private int hauteurVideo;

    private Uri uriVideo;
    private String titre;

    // ----- traduction -----
    private SharedPreferences prefs;
    private boolean active, afficher, voix, baisser;
    private String langSource, langCible;
    private volatile String derniere = "";                       // texte du sous-titre en cours (original)
    private final Map<String, String> cacheTrad = new HashMap<>();
    private final ExecutorService reseau = Executors.newSingleThreadExecutor();
    private long dernierMessageErreur = 0;

    // ----- voix -----
    private TextToSpeech tts;
    private boolean ttsPret = false, voixDispo = true;
    private int compteurVoix = 0;
    private volatile int voixCourante = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        String u = getIntent().getStringExtra("uri");
        if (u == null) { finish(); return; }
        uriVideo = Uri.parse(u);
        titre = getIntent().getStringExtra("titre");
        if (titre == null) titre = "Vidéo";
        hauteurVideo = Ui.dp(this, 230);

        prefs = getSharedPreferences("traduction", MODE_PRIVATE);
        active = prefs.getBoolean("active", false);
        langSource = prefs.getString("source", "auto");
        langCible = prefs.getString("cible", "fr");
        afficher = prefs.getBoolean("afficher", true);
        voix = prefs.getBoolean("voix", false);
        baisser = prefs.getBoolean("baisser", true);

        // choix fait dans « Mes vidéos » : tout / audio / soustitres / aucun
        String mode = getIntent().getStringExtra("mode");
        if (mode != null) {
            if ("aucun".equals(mode)) {
                active = false;
            } else {
                active = true;
                afficher = !"audio".equals(mode);
                voix = !"soustitres".equals(mode);
                String src = getIntent().getStringExtra("source");
                String dst = getIntent().getStringExtra("cible");
                if (src != null) langSource = src;
                if (dst != null) langCible = dst;
                prefs.edit().putBoolean("active", true).putString("source", langSource)
                        .putString("cible", langCible).putBoolean("afficher", afficher)
                        .putBoolean("voix", voix).apply();
            }
        }

        // ----- écran -----
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 14);
        root.setPadding(p, p, p, p);

        TextView tvTitre = Ui.texte(this, "🎬 " + titre, 16, Ui.TXT, true);
        tvTitre.setMaxLines(2);
        ajouter(tvTitre, 0);

        zoneVideo = new FrameLayout(this);
        zoneVideo.setBackgroundColor(0xFF000000);
        vue = new PlayerView(this);
        vue.setUseController(true);
        zoneVideo.addView(vue, new FrameLayout.LayoutParams(-1, -1));

        overlay = new TextView(this);
        overlay.setTextColor(0xFFFFFFFF);
        overlay.setTextSize(16);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackground(Ui.rond(0xB3000000, 8, this));
        overlay.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        overlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams lo = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lo.bottomMargin = Ui.dp(this, 56);                 // au-dessus de la barre de lecture
        lo.leftMargin = lo.rightMargin = Ui.dp(this, 12);
        zoneVideo.addView(overlay, lo);
        root.addView(zoneVideo, Ui.lp(this, -1, hauteurVideo, 0f, 8, 0));

        tvStatut = Ui.texte(this, "", 13, Ui.TXT2, false);
        ajouter(tvStatut, 8);

        LinearLayout l1 = new LinearLayout(this);
        Button bTrad = Ui.bouton(this, "🌐 Traduction", Ui.VIOLET);
        Button bPistes = Ui.bouton(this, "💬 Sous-titres", Ui.ACCENT);
        bTrad.setOnClickListener(v -> dialogTraduction());
        bPistes.setOnClickListener(v -> dialogPistes());
        l1.addView(bTrad, Ui.lp(this, 0, -2, 1f, 0, 0));
        l1.addView(bPistes, Ui.lp(this, 0, -2, 1f, 0, 8));
        ajouter(l1, 8);

        LinearLayout l2 = new LinearLayout(this);
        Button bFichier = Ui.bouton(this, "📄 Charger .srt / .vtt", 0xFF2E9E73);
        Button bPlein = Ui.bouton(this, "⛶ Plein écran", Ui.VIOLET);
        bFichier.setOnClickListener(v -> choisirSousTitres());
        bPlein.setOnClickListener(v -> basculerPleinEcran());
        l2.addView(bFichier, Ui.lp(this, 0, -2, 1.3f, 0, 0));
        l2.addView(bPlein, Ui.lp(this, 0, -2, 1f, 0, 8));
        ajouter(l2, 8);

        ajouter(Ui.texte(this,
                "La traduction suit les sous-titres de la vidéo (pistes intégrées ou fichier .srt/.vtt chargé). "
                + "Internet est nécessaire pour traduire.", 12, Ui.TXT2, false), 10);

        setContentView(root);

        // ----- lecteur -----
        player = new ExoPlayer.Builder(this).build();
        vue.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onCues(CueGroup g) { surSousTitre(g); }
            @Override public void onPlayerError(PlaybackException e) {
                Toast.makeText(LecteurVideoActivity.this, "Lecture impossible", Toast.LENGTH_LONG).show();
            }
            @Override public void onTracksChanged(Tracks tracks) { surPistes(tracks); }
            @Override public void onPlayWhenReadyChanged(boolean pw, int raison) { if (!pw) stopperVoix(); }
            @Override public void onPositionDiscontinuity(Player.PositionInfo a, Player.PositionInfo n, int raison) {
                if (raison == Player.DISCONTINUITY_REASON_SEEK) { derniere = ""; overlay.setVisibility(View.GONE); stopperVoix(); }
            }
        });

        player.setMediaItem(construireItem(uriVideo, trouverSousTitresVoisins(), null));
        player.prepare();
        player.play();

        majAffichageNatif();
        majStatut();
        if (active && voix) preparerVoix();
    }

    private boolean averti = false;

    /** Quand la traduction est active : choisit tout seul une piste de sous-titres, ou prévient s'il n'y en a pas. */
    private void surPistes(Tracks tracks) {
        if (!active) return;
        boolean existe = false, choisie = false;
        Tracks.Group premier = null;
        int idx = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                existe = true;
                if (g.isTrackSelected(i)) choisie = true;
                if (premier == null) { premier = g; idx = i; }
            }
        }
        if (!existe) {
            if (!averti) {
                averti = true;
                Toast.makeText(this, "Cette vidéo n'a pas de sous-titres à traduire. "
                        + "Chargez un fichier .srt / .vtt avec le bouton « Charger ».", Toast.LENGTH_LONG).show();
            }
            return;
        }
        boolean desactive = player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        if (!choisie && !desactive && premier != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(premier.getMediaTrackGroup(), idx))
                    .build());
        }
    }

    private void ajouter(View v, int hautDp) {
        root.addView(v, Ui.lp(this, -1, -2, 0f, hautDp, 0));
        cachables.add(v);
    }

    // ---------- Sous-titres : fichier voisin, fichier choisi, pistes intégrées ----------

    private MediaItem construireItem(Uri video, Uri sousTitres, String nomSousTitres) {
        MediaItem.Builder mb = new MediaItem.Builder().setUri(video);
        if (sousTitres != null) {
            String n = (nomSousTitres != null ? nomSousTitres : String.valueOf(sousTitres.getLastPathSegment()))
                    .toLowerCase(Locale.ROOT);
            String mime = MimeTypes.APPLICATION_SUBRIP;
            if (n.endsWith(".vtt")) mime = MimeTypes.TEXT_VTT;
            else if (n.endsWith(".ass") || n.endsWith(".ssa")) mime = MimeTypes.TEXT_SSA;
            else if (n.endsWith(".ttml") || n.endsWith(".dfxp") || n.endsWith(".xml")) mime = MimeTypes.APPLICATION_TTML;
            MediaItem.SubtitleConfiguration sc = new MediaItem.SubtitleConfiguration.Builder(sousTitres)
                    .setMimeType(mime)
                    .setLabel(nomSousTitres != null ? nomSousTitres : "Sous-titres")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build();
            mb.setSubtitleConfigurations(Collections.singletonList(sc));
        }
        return mb.build();
    }

    /** Cherche un fichier .srt/.vtt du même nom à côté de la vidéo (si Android nous laisse le lire). */
    private Uri trouverSousTitresVoisins() {
        try {
            String chemin = null;
            try (Cursor c = getContentResolver().query(uriVideo,
                    new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
                if (c != null && c.moveToFirst()) chemin = c.getString(0);
            }
            if (chemin == null) return null;
            int pt = chemin.lastIndexOf('.');
            String base = pt > 0 ? chemin.substring(0, pt) : chemin;
            String[] suffixes = {".srt", ".vtt", ".fr.srt", ".en.srt", ".fr.vtt", ".en.vtt"};
            for (String s : suffixes) {
                File f = new File(base + s);
                if (f.isFile() && f.canRead()) return Uri.fromFile(f);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void choisirSousTitres() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 51);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 51 || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String nom = u.getLastPathSegment();
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (ix >= 0 && c.getString(ix) != null) nom = c.getString(ix);
            }
        } catch (Exception ignored) {}
        long pos = player.getCurrentPosition();
        boolean lecture = player.getPlayWhenReady();
        player.setMediaItem(construireItem(uriVideo, u, nom), pos);
        player.prepare();
        player.setPlayWhenReady(lecture);
        Toast.makeText(this, "Sous-titres chargés : " + nom, Toast.LENGTH_SHORT).show();
    }

    private void dialogPistes() {
        final List<Tracks.Group> groupes = new ArrayList<>();
        final List<Integer> index = new ArrayList<>();
        List<String> noms = new ArrayList<>();
        noms.add("Aucun sous-titre");
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                String nom = f.label;
                if ((nom == null || nom.isEmpty()) && f.language != null && !f.language.equals("und"))
                    nom = new Locale(f.language).getDisplayLanguage();
                if (nom == null || nom.isEmpty()) nom = "Piste " + (groupes.size() + 1);
                noms.add(nom);
                groupes.add(g);
                index.add(i);
            }
        }
        if (groupes.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Sous-titres")
                    .setMessage("Cette vidéo n'a pas de sous-titres intégrés.\n\nUtilisez « Charger .srt / .vtt » "
                            + "pour ajouter un fichier de sous-titres, puis activez la traduction.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Piste de sous-titres")
                .setItems(noms.toArray(new String[0]), (d, w) -> {
                    TrackSelectionParameters.Builder pb = player.getTrackSelectionParameters().buildUpon();
                    if (w == 0) {
                        pb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                    } else {
                        pb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
                        pb.setOverrideForType(new TrackSelectionOverride(
                                groupes.get(w - 1).getMediaTrackGroup(), index.get(w - 1)));
                    }
                    player.setTrackSelectionParameters(pb.build());
                    derniere = "";
                    overlay.setVisibility(View.GONE);
                }).show();
    }

    // ---------- Réception d'un sous-titre → traduction ----------

    private void surSousTitre(CueGroup g) {
        StringBuilder sb = new StringBuilder();
        for (Cue c : g.cues) {
            if (c.text == null) continue;
            String t = c.text.toString().replaceAll("\\s+", " ").trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        String texte = sb.toString();
        if (texte.equals(derniere)) return;
        derniere = texte;
        if (!active) return;                                 // sous-titres d'origine : le lecteur s'en charge
        if (texte.isEmpty()) { overlay.setVisibility(View.GONE); return; }   // la voix en cours se termine seule
        traduire(texte);
    }

    private void traduire(final String texte) {
        final String src = langSource, dst = langCible;
        final String cle = src + ">" + dst + ">" + texte;
        String deja = cacheTrad.get(cle);
        if (deja != null) { montrer(deja); return; }
        if (afficher) { overlay.setText("…"); overlay.setVisibility(View.VISIBLE); }
        reseau.execute(() -> {
            if (!texte.equals(derniere)) return;             // le sous-titre a déjà changé : on laisse tomber
            final String res = traduireTexte(texte, src, dst);
            runOnUiThread(() -> {
                if (res == null) {
                    erreurReseau(texte);
                    return;
                }
                cacheTrad.put(cle, res);
                if (texte.equals(derniere) && active) montrer(res);
            });
        });
    }

    private void erreurReseau(String original) {
        if (afficher && original.equals(derniere)) { overlay.setText(original); overlay.setVisibility(View.VISIBLE); }
        long now = System.currentTimeMillis();
        if (now - dernierMessageErreur > 15000) {
            dernierMessageErreur = now;
            Toast.makeText(this, "Traduction impossible : vérifiez la connexion internet", Toast.LENGTH_LONG).show();
        }
    }

    private void montrer(String trad) {
        if (afficher) { overlay.setText(trad); overlay.setVisibility(View.VISIBLE); }
        else overlay.setVisibility(View.GONE);
        if (voix) parler(trad);
    }

    /** Traduit avec l'API gratuite de Google ; si elle échoue, essaie l'API gratuite MyMemory (langue d'origine connue). */
    static String traduireTexte(String texte, String src, String dst) {
        String r = appelTraduction(texte, src, dst);
        if (r == null && !"auto".equals(src)) r = appelMyMemory(texte, src, dst);
        return r;
    }

    static String appelMyMemory(String texte, String src, String dst) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(texte, "UTF-8")
                    + "&langpair=" + URLEncoder.encode(src + "|" + dst, "UTF-8"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            String res = new org.json.JSONObject(sb.toString()).getJSONObject("responseData").getString("translatedText").trim();
            if (res.isEmpty() || res.contains("MYMEMORY WARNING")) return null;
            return res;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Appel à Google Traduction (service web gratuit non officiel : « sl » = langue d'origine, « tl » = langue voulue). */
    static String appelTraduction(String texte, String src, String dst) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + src
                    + "&tl=" + dst + "&dt=t&q=" + URLEncoder.encode(texte, "UTF-8"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            JSONArray segments = new JSONArray(sb.toString()).getJSONArray(0);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < segments.length(); i++) {
                JSONArray s = segments.optJSONArray(i);
                if (s != null && !s.isNull(0)) out.append(s.getString(0));
            }
            String res = out.toString().trim();
            return res.isEmpty() ? null : res;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- Voix (traduction audio) ----------

    private void preparerVoix() {
        if (tts != null) { appliquerLangueVoix(); return; }
        tts = new TextToSpeech(this, status -> {
            ttsPret = status == TextToSpeech.SUCCESS;
            if (!ttsPret) {
                Toast.makeText(this, "Aucune voix de synthèse disponible sur ce téléphone", Toast.LENGTH_LONG).show();
                return;
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    if (baisser) runOnUiThread(() -> { if (player != null) player.setVolume(0.12f); });
                }
                @Override public void onDone(String id) { fin(id); }
                @Override public void onStop(String id, boolean interrompu) { fin(id); }
                @Override public void onError(String id) { fin(id); }
                private void fin(String id) {
                    if (id != null && id.equals("v" + voixCourante))
                        runOnUiThread(() -> { if (player != null) player.setVolume(1f); });
                }
            });
            appliquerLangueVoix();
        });
    }

    private void appliquerLangueVoix() {
        if (tts == null || !ttsPret) return;
        int r = tts.setLanguage(Locale.forLanguageTag(langCible));
        voixDispo = !(r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED);
        if (!voixDispo) {
            new AlertDialog.Builder(this)
                    .setTitle("Voix non installée")
                    .setMessage("Ce téléphone n'a pas de voix pour « " + nomLangue(langCible) + " ». "
                            + "Les sous-titres traduits restent affichés. Vous pouvez installer la voix "
                            + "dans les paramètres de synthèse vocale d'Android.")
                    .setPositiveButton("Installer la voix", (d, w) -> {
                        try { startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); }
                        catch (Exception e) {
                            Toast.makeText(this, "Ouvrez Paramètres › Synthèse vocale", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Plus tard", null).show();
        }
    }

    private void parler(String texte) {
        if (tts == null || !ttsPret || !voixDispo) return;
        voixCourante = ++compteurVoix;
        tts.speak(texte, TextToSpeech.QUEUE_FLUSH, null, "v" + voixCourante);
    }

    private void stopperVoix() {
        if (tts != null && ttsPret) tts.stop();
        if (player != null) player.setVolume(1f);
    }

    // ---------- Réglages de traduction ----------

    private static String nomLangue(String code) {
        for (String l : LANGUES) {
            String[] t = l.split("\\|");
            if (t[1].equals(code)) return t[0];
        }
        return code;
    }

    private static int indexLangue(List<String> codes, String code) {
        int i = codes.indexOf(code);
        return i < 0 ? 0 : i;
    }

    private void dialogTraduction() {
        // listes : source (avec détection auto) et cible
        final List<String> nomsSource = new ArrayList<>(), codesSource = new ArrayList<>();
        final List<String> nomsCible = new ArrayList<>(), codesCible = new ArrayList<>();
        nomsSource.add("Détecter automatiquement");
        codesSource.add("auto");
        List<String> vus = new ArrayList<>();
        for (String l : LANGUES) {
            String[] t = l.split("\\|");
            if (vus.contains(t[1])) continue;                 // évite les doublons de la liste
            vus.add(t[1]);
            nomsSource.add(t[0]); codesSource.add(t[1]);
            nomsCible.add(t[0]); codesCible.add(t[1]);
        }

        int m = Ui.dp(this, 18);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(m, m / 2, m, 0);

        CheckBox cbActive = new CheckBox(this);
        cbActive.setText("Activer la traduction");
        cbActive.setChecked(prefs.contains("active") ? active : true);
        l.addView(cbActive);

        l.addView(etiquette("Langue de la vidéo"));
        Spinner spSource = new Spinner(this);
        Ui.styleSpinner(spSource);
        spSource.setAdapter(Ui.adapterSpinner(this, nomsSource));
        spSource.setSelection(indexLangue(codesSource, langSource));
        l.addView(spSource);

        l.addView(etiquette("Traduire vers"));
        Spinner spCible = new Spinner(this);
        Ui.styleSpinner(spCible);
        spCible.setAdapter(Ui.adapterSpinner(this, nomsCible));
        spCible.setSelection(indexLangue(codesCible, langCible));
        l.addView(spCible);

        CheckBox cbAfficher = new CheckBox(this);
        cbAfficher.setText("Afficher les sous-titres traduits");
        cbAfficher.setChecked(afficher);
        l.addView(cbAfficher);

        CheckBox cbVoix = new CheckBox(this);
        cbVoix.setText("Lire la traduction à voix haute (audio)");
        cbVoix.setChecked(voix);
        l.addView(cbVoix);

        CheckBox cbBaisser = new CheckBox(this);
        cbBaisser.setText("Baisser le son d'origine pendant la voix");
        cbBaisser.setChecked(baisser);
        l.addView(cbBaisser);

        ScrollView sv = new ScrollView(this);
        sv.addView(l);

        new AlertDialog.Builder(this)
                .setTitle("🌐 Traduction")
                .setView(sv)
                .setPositiveButton("Appliquer", (d, w) -> {
                    active = cbActive.isChecked();
                    langSource = codesSource.get(spSource.getSelectedItemPosition());
                    langCible = codesCible.get(spCible.getSelectedItemPosition());
                    afficher = cbAfficher.isChecked();
                    voix = cbVoix.isChecked();
                    baisser = cbBaisser.isChecked();
                    prefs.edit().putBoolean("active", active).putString("source", langSource)
                            .putString("cible", langCible).putBoolean("afficher", afficher)
                            .putBoolean("voix", voix).putBoolean("baisser", baisser).apply();
                    majAffichageNatif();
                    majStatut();
                    stopperVoix();
                    if (active && voix) preparerVoix();
                    if (!active) overlay.setVisibility(View.GONE);
                    else if (!derniere.isEmpty()) traduire(derniere);
                })
                .setNegativeButton("Annuler", null).show();
    }

    private TextView etiquette(String t) {
        TextView v = Ui.texte(this, t, 13, 0xFF444444, true);
        v.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        return v;
    }

    /** Quand la traduction est active, on masque les sous-titres d'origine du lecteur (remplacés par les nôtres). */
    private void majAffichageNatif() {
        SubtitleView sv = vue.getSubtitleView();
        if (sv != null) sv.setVisibility(active && afficher ? View.GONE : View.VISIBLE);
    }

    private void majStatut() {
        if (!active) {
            tvStatut.setText("🌐 Traduction désactivée");
            return;
        }
        String src = "auto".equals(langSource) ? "détection automatique" : nomLangue(langSource);
        StringBuilder sb = new StringBuilder("🌐 " + src + " → " + nomLangue(langCible));
        sb.append(afficher ? "  •  sous-titres" : "");
        sb.append(voix ? "  •  voix" : "");
        tvStatut.setText(sb.toString());
    }

    // ---------- Plein écran ----------

    private void basculerPleinEcran() {
        plein = !plein;
        int vis = plein ? View.GONE : View.VISIBLE;
        for (View v : cachables) v.setVisibility(vis);
        zoneVideo.setLayoutParams(plein ? new LinearLayout.LayoutParams(-1, -1)
                                        : Ui.lp(this, -1, hauteurVideo, 0f, 8, 0));
        int p = plein ? 0 : Ui.dp(this, 14);
        root.setPadding(p, p, p, p);
        overlay.setTextSize(plein ? 20 : 16);
        setRequestedOrientation(plein ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                      : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().getDecorView().setSystemUiVisibility(plein
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : 0);
    }

    @Override
    public void onBackPressed() {
        if (plein) basculerPleinEcran();
        else super.onBackPressed();
    }

    @Override
    protected void onStop() {
        if (player != null) player.pause();
        stopperVoix();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        reseau.shutdownNow();
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
