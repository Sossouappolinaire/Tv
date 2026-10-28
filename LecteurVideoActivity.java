package bj.radio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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
import org.json.JSONObject;

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

    // ----- traduction de la voix par le micro (vidéos sans sous-titres) -----
    private static final int REQ_MICRO = 52;
    private final Handler hUi = new Handler(Looper.getMainLooper());
    private SpeechRecognizer reco;
    private boolean microActif = false;
    private Button bMicro;
    private long dernierPartielMs = 0;
    private String dernierPartiel = "";
    private int erreursMicro = 0;
    private static volatile String derniereErreur = "";          // dernière raison d'échec des services de traduction

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

        // choix fait dans « Vidéos de ma galerie » : tout / audio / soustitres / aucun
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

        bMicro = Ui.bouton(this, "🎤 Traduire la voix (micro)", 0xFFD6336C);
        bMicro.setOnClickListener(v -> basculerMicro());
        ajouter(bMicro, 8);

        ajouter(Ui.texte(this,
                "La traduction suit les sous-titres de la vidéo (pistes intégrées ou fichier .srt/.vtt chargé). "
                + "Sans sous-titres (vidéos Facebook, par exemple), utilisez « Traduire la voix » : le micro écoute "
                + "le son de la vidéo, donc mettez-la sur le haut-parleur, pas au casque. "
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
            if (!averti && !microActif) {
                averti = true;
                new AlertDialog.Builder(this)
                        .setTitle("Pas de sous-titres")
                        .setMessage("Cette vidéo n'a pas de sous-titres à traduire (c'est le cas de la plupart "
                                + "des vidéos téléchargées de Facebook).\n\nVous pouvez :\n"
                                + "• traduire la voix avec le micro (son sur haut-parleur, pas au casque) ;\n"
                                + "• ou charger un fichier .srt / .vtt.")
                        .setPositiveButton("🎤 Traduire la voix", (d, w) -> basculerMicro())
                        .setNeutralButton("Charger .srt / .vtt", (d, w) -> choisirSousTitres())
                        .setNegativeButton("Plus tard", null)
                        .show();
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
        if (afficher || microActif) { overlay.setText("…"); overlay.setVisibility(View.VISIBLE); }
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
        if ((afficher || microActif) && original.equals(derniere)) { overlay.setText(original); overlay.setVisibility(View.VISIBLE); }
        long now = System.currentTimeMillis();
        if (now - dernierMessageErreur > 15000) {
            dernierMessageErreur = now;
            Toast.makeText(this, "Traduction impossible : vérifiez la connexion internet"
                    + (derniereErreur.isEmpty() ? "" : "\n(" + derniereErreur + ")"), Toast.LENGTH_LONG).show();
        }
    }

    private void montrer(String trad) {
        if (afficher || microActif) { overlay.setText(trad); overlay.setVisibility(View.VISIBLE); }
        else overlay.setVisibility(View.GONE);
        if (voix && !microActif) parler(trad);
    }

    /**
     * Traduit avec 3 services gratuits, l'un après l'autre : Google (gtx), Google (extension Chrome),
     * puis MyMemory. Le premier qui répond gagne ; sinon null (et derniereErreur dit pourquoi).
     */
    static String traduireTexte(String texte, String src, String dst) {
        derniereErreur = "";
        if (src == null || src.isEmpty()) src = "auto";
        if (src.equals(dst)) return texte;
        String r = appelTraduction(texte, src, dst);
        if (r == null) r = appelGoogleBis(texte, src, dst);
        if (r == null) r = appelMyMemory(texte, src, dst);
        return r;
    }

    private static String lire(HttpURLConnection c) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.toString();
    }

    private static String nettoyerHtml(String s) {
        try {
            return android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
        } catch (Exception e) {
            return s.trim();
        }
    }

    /** MyMemory : gratuit, sans clé. « Autodetect » quand la langue d'origine n'est pas choisie. */
    static String appelMyMemory(String texte, String src, String dst) {
        HttpURLConnection c = null;
        try {
            String sl = "auto".equals(src) ? "Autodetect" : src;
            URL u = new URL("https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(texte, "UTF-8")
                    + "&langpair=" + URLEncoder.encode(sl + "|" + dst, "UTF-8"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = c.getResponseCode();
            if (code != 200) { derniereErreur += " MyMemory http " + code; return null; }
            JSONObject o = new JSONObject(lire(c));
            String res = nettoyerHtml(o.getJSONObject("responseData").getString("translatedText"));
            if (res.isEmpty() || res.contains("MYMEMORY WARNING") || res.contains("INVALID")) {
                derniereErreur += " MyMemory refuse"; return null;
            }
            return res;
        } catch (Exception e) {
            derniereErreur += " MyMemory " + e.getClass().getSimpleName();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Google Traduction (service web gratuit non officiel : « sl » = langue d'origine, « tl » = langue voulue). */
    static String appelTraduction(String texte, String src, String dst) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + src
                    + "&tl=" + dst + "&dt=t&ie=UTF-8&oe=UTF-8&q=" + URLEncoder.encode(texte, "UTF-8"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36");
            int code = c.getResponseCode();
            if (code != 200) { derniereErreur += " Google http " + code; return null; }
            JSONArray segments = new JSONArray(lire(c)).getJSONArray(0);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < segments.length(); i++) {
                JSONArray s = segments.optJSONArray(i);
                if (s != null && !s.isNull(0)) out.append(s.getString(0));
            }
            String res = out.toString().trim();
            if (res.isEmpty()) { derniereErreur += " Google vide"; return null; }
            return res;
        } catch (Exception e) {
            derniereErreur += " Google " + e.getClass().getSimpleName();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Deuxième adresse Google (celle de l'extension Chrome) : marche souvent quand la première est refusée. */
    static String appelGoogleBis(String texte, String src, String dst) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=" + src
                    + "&tl=" + dst + "&q=" + URLEncoder.encode(texte, "UTF-8"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36");
            int code = c.getResponseCode();
            if (code != 200) { derniereErreur += " Google2 http " + code; return null; }
            String corps = lire(c).trim();
            StringBuilder out = new StringBuilder();
            if (corps.startsWith("{")) {
                JSONArray ph = new JSONObject(corps).getJSONArray("sentences");
                for (int i = 0; i < ph.length(); i++) out.append(ph.getJSONObject(i).optString("trans", ""));
            } else {
                JSONArray t = new JSONArray(corps);
                Object e0 = t.get(0);
                out.append(e0 instanceof JSONArray ? ((JSONArray) e0).getString(0) : String.valueOf(e0));
            }
            String res = out.toString().trim();
            if (res.isEmpty()) { derniereErreur += " Google2 vide"; return null; }
            return res;
        } catch (Exception e) {
            derniereErreur += " Google2 " + e.getClass().getSimpleName();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- Traduction de la voix (micro) : pour les vidéos SANS sous-titres ----------

    /** Codes de langue de la liste → codes attendus par la reconnaissance vocale d'Android. */
    private static String codeReco(String c) {
        switch (c) {
            case "en": return "en-US";
            case "fr": return "fr-FR";
            case "es": return "es-ES";
            case "pt": return "pt-BR";
            case "de": return "de-DE";
            case "it": return "it-IT";
            case "nl": return "nl-NL";
            case "ru": return "ru-RU";
            case "ar": return "ar-SA";
            case "ja": return "ja-JP";
            case "ko": return "ko-KR";
            case "hi": return "hi-IN";
            case "tr": return "tr-TR";
            case "sw": return "sw-KE";
            case "no": return "nb-NO";
            default: return c;
        }
    }

    private void basculerMicro() {
        if (microActif) { arreterMicro(); return; }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Reconnaissance vocale absente")
                    .setMessage("Ce téléphone n'a pas de service de reconnaissance vocale. Installez ou activez "
                            + "l'application « Google » (ou « Services vocaux Google ») dans le Play Store.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MICRO);
            return;
        }
        demarrerMicro();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_MICRO) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) demarrerMicro();
        else Toast.makeText(this, "Sans l'autorisation du micro, la voix ne peut pas être traduite", Toast.LENGTH_LONG).show();
    }

    private void demarrerMicro() {
        if (!active) {
            active = true;
            prefs.edit().putBoolean("active", true).apply();
        }
        microActif = true;
        erreursMicro = 0;
        dernierPartiel = "";
        derniere = "";
        bMicro.setText("⏹ Arrêter la traduction de la voix");
        majAffichageNatif();
        majStatut();
        if (player != null && !player.getPlayWhenReady()) player.play();
        if ("auto".equals(langSource)) {
            Toast.makeText(this, "Astuce : choisissez la langue de la vidéo dans « Traduction » pour mieux comprendre la voix",
                    Toast.LENGTH_LONG).show();
        }
        stopperVoix();
        lancerEcoute();
    }

    private void arreterMicro() {
        microActif = false;
        hUi.removeCallbacksAndMessages(null);
        if (reco != null) {
            try { reco.cancel(); } catch (Exception ignored) {}
            try { reco.destroy(); } catch (Exception ignored) {}
            reco = null;
        }
        if (bMicro != null) bMicro.setText("🎤 Traduire la voix (micro)");
        if (overlay != null) overlay.setVisibility(View.GONE);
        majAffichageNatif();
        majStatut();
    }

    private void lancerEcoute() {
        if (!microActif) return;
        try {
            if (reco == null) {
                reco = SpeechRecognizer.createSpeechRecognizer(this);
                reco.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle p) {}
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float v) {}
                    @Override public void onBufferReceived(byte[] b) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onEvent(int t, Bundle p) {}
                    @Override public void onPartialResults(Bundle b) { texteMicro(b, false); }
                    @Override public void onResults(Bundle b) {
                        texteMicro(b, true);
                        relancerEcoute(150);
                    }
                    @Override public void onError(int err) { erreurMicro(err); }
                });
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            if (!"auto".equals(langSource)) {
                String tag = codeReco(langSource);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
            }
            reco.startListening(i);
        } catch (Exception e) {
            Toast.makeText(this, "Micro indisponible : " + e.getMessage(), Toast.LENGTH_LONG).show();
            arreterMicro();
        }
    }

    private void relancerEcoute(long delaiMs) {
        hUi.removeCallbacksAndMessages(null);
        hUi.postDelayed(this::lancerEcoute, delaiMs);
    }

    private void texteMicro(Bundle b, boolean fini) {
        if (!microActif || b == null) return;
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l == null || l.isEmpty()) return;
        String t = l.get(0) == null ? "" : l.get(0).trim();
        if (t.isEmpty()) return;
        erreursMicro = 0;
        long now = System.currentTimeMillis();
        // Résultat partiel : on traduit au plus une fois par seconde environ pour ne pas saturer le service.
        if (!fini && (t.equals(dernierPartiel) || now - dernierPartielMs < 1000)) return;
        dernierPartiel = t;
        dernierPartielMs = now;
        derniere = t;
        traduire(t);
    }

    private void erreurMicro(int err) {
        if (!microActif) return;
        if (err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            Toast.makeText(this, "Autorisation du micro manquante", Toast.LENGTH_LONG).show();
            arreterMicro();
            return;
        }
        // Silence ou rien compris : normal, on réécoute tout de suite.
        if (err == SpeechRecognizer.ERROR_NO_MATCH || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            relancerEcoute(100);
            return;
        }
        erreursMicro++;
        if (erreursMicro > 6) {
            String raison = err == SpeechRecognizer.ERROR_NETWORK || err == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    ? "La reconnaissance vocale a besoin d'internet (ou du pack de langue hors-ligne)."
                    : "La reconnaissance vocale ne répond pas (code " + err + ").";
            Toast.makeText(this, raison + " Traduction de la voix arrêtée.", Toast.LENGTH_LONG).show();
            arreterMicro();
            return;
        }
        if (err == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && reco != null) {
            try { reco.cancel(); } catch (Exception ignored) {}
        }
        relancerEcoute(700);
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
                    if (!active) {
                        overlay.setVisibility(View.GONE);
                        if (microActif) arreterMicro();
                    } else {
                        // Traduction activée après le début de la vidéo : on choisit la piste de sous-titres maintenant.
                        averti = false;
                        surPistes(player.getCurrentTracks());
                        if (!derniere.isEmpty()) traduire(derniere);
                    }
                    if (microActif) { arreterMicro(); demarrerMicro(); }   // nouvelle langue → on relance l'écoute
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
        sb.append(microActif ? "  •  🎤 écoute du micro" : "");
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
        if (microActif) arreterMicro();
        if (player != null) player.pause();
        stopperVoix();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        arreterMicro();
        reseau.shutdownNow();
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
