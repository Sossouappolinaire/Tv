package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;

import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ecran « Vidéos Facebook » : analyse d'un lien, choix du format (SD / HD),
 * confirmation, puis téléchargement avec barre de progression en bas de l'écran.
 *
 * Fichier autonome (aucune dépendance à Ui.java). Les vidéos sont enregistrées dans
 * getExternalFilesDir(Movies), le même dossier que « Sans internet > Mes vidéos & audios ».
 */
public class FacebookActivity extends Activity {

    // ---------- Couleurs (fond plus clair que l'ancien gris très sombre) ----------
    private static final int C_HAUT = 0xFF6D82CB;
    private static final int C_BG = 0xFF4A5EA6;
    private static final int C_CARD = 0xFF3B4C8C;
    private static final int C_ACCENT = 0xFFFF6B35;
    private static final int C_VIOLET = 0xFF7B4DFF;
    private static final int C_TXT2 = 0xFFE3E5EA;

    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String UA_BOT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";
    private static final String UA_TEL = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final String[] CLES_SD = {"browser_native_sd_url", "sd_src_no_ratelimit", "sd_src", "playable_url"};
    private static final String[] CLES_HD = {"browser_native_hd_url", "hd_src_no_ratelimit", "hd_src", "playable_url_quality_hd"};

    private static final Pattern PROG = Pattern.compile(
            "\"progressive_url\"\\s*:\\s*\"(https?:[^\"]+)\"(.{0,300}?)\"quality\"\\s*:\\s*\"(\\w+)\"", Pattern.DOTALL);
    private static final Pattern META = Pattern.compile("<meta[^>]+>");
    private static final Pattern CONTENT = Pattern.compile("content=\"([^\"]+)\"");
    private static final Pattern SAUT_JS = Pattern.compile("location\\.href\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern EFG = Pattern.compile("[?&]efg=([^&]+)");
    private static final Pattern TAG = Pattern.compile("\"vencode_tag\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern HAUTEUR = Pattern.compile("(\\d{3,4})p");
    private static final Pattern LIEN = Pattern.compile("https?://\\S+");

    // Durée de la vidéo : Facebook la donne sous plusieurs noms selon la page.
    private static final Pattern DUREE_MS = Pattern.compile("\"playable_duration_in_ms\"\\s*:\\s*(\\d+)");
    private static final Pattern DUREE_S = Pattern.compile("\"(?:length_in_second|video_duration|duration_in_seconds)\"\\s*:\\s*\"?(\\d+(?:\\.\\d+)?)");
    private static final Pattern DUREE_MPD = Pattern.compile("mediaPresentationDuration=\\\\?\"PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?");
    private static final Pattern DUREE_WEB = Pattern.compile("^<!--DUR:([0-9.]+)-->");
    private static final Pattern OG_DUREE = Pattern.compile("og:video:duration\"[^>]*content=\"(\\d+)\"");

    static {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(new java.net.CookieManager());
        }
    }

    /** Ce qu'on a trouvé pour une vidéo. */
    private static class Res {
        String sd, hd, uaSd, uaHd;
        String dashV, dashA, uaDash;
        long dureeMs = 0;   // durée de la vidéo (0 = inconnue)
    }

    /** Ce qu'on va télécharger. audio != null : image et son séparés, à rassembler. */
    private static class Plan {
        final String nom, url, audio, ua;
        long dureeMs = 0;                 // durée annoncée par Facebook (0 = inconnue)
        volatile long tailleV = -1;       // taille du fichier image (ou du fichier complet), -1 = inconnue
        volatile long tailleA = -1;       // taille du fichier son (flux séparé)

        Plan(String nom, String url, String audio, String ua) {
            this.nom = nom;
            this.url = url;
            this.audio = audio;
            this.ua = ua == null ? UA_PC : ua;
        }

        long tailleTotale() {
            if (tailleV <= 0) return -1;
            return tailleV + (audio != null && tailleA > 0 ? tailleA : 0);
        }
    }

    // ---------- Durée et taille : calcul et affichage ----------

    private static String formatDuree(long ms) {
        if (ms <= 0) return "durée inconnue";
        long s = (ms + 500) / 1000;
        long hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        if (hh > 0) return String.format(Locale.FRANCE, "%d h %02d min %02d s", hh, mm, ss);
        if (mm > 0) return String.format(Locale.FRANCE, "%d min %02d s", mm, ss);
        return ss + " s";
    }

    private static String formatTaille(long octets) {
        if (octets <= 0) return "taille inconnue";
        if (octets >= 1073741824L) return String.format(Locale.FRANCE, "%.2f Go", octets / 1073741824.0);
        return String.format(Locale.FRANCE, "%.1f Mo", octets / 1048576.0);
    }

    /** Estimation grossière quand Facebook ne donne pas la taille (débit moyen supposé en bits/s). */
    private static long estimer(long dureeMs, long bitsParSeconde) {
        return dureeMs <= 0 ? -1 : dureeMs / 1000 * bitsParSeconde / 8;
    }

    /** Durée annoncée par la page (ms), 0 si introuvable. */
    private long extraireDuree(String html) {
        try {
            Matcher m = DUREE_MS.matcher(html);
            if (m.find()) return Long.parseLong(m.group(1));
            m = DUREE_MPD.matcher(html);
            if (m.find() && (m.group(1) != null || m.group(2) != null || m.group(3) != null)) {
                double s = (m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 3600.0)
                        + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)) * 60.0)
                        + (m.group(3) == null ? 0 : Double.parseDouble(m.group(3)));
                if (s > 0) return (long) (s * 1000);
            }
            m = DUREE_S.matcher(html);
            if (m.find()) return (long) (Double.parseDouble(m.group(1)) * 1000);
            m = OG_DUREE.matcher(html);
            if (m.find()) return Long.parseLong(m.group(1)) * 1000;
        } catch (Exception ignore) {
            // durée inconnue
        }
        return 0;
    }

    /**
     * Taille réelle du fichier sans le télécharger : on demande 1 seul octet (Range: bytes=0-0)
     * et on lit le total dans « Content-Range: bytes 0-0/TOTAL ». Renvoie -1 si impossible.
     */
    private long sonderTaille(String adresse, String ua) {
        for (int essai = 0; essai < 2; essai++) {
            boolean referer = essai == 0;
            String courant = adresse;
            HttpURLConnection c = null;
            try {
                for (int i = 0; i < 6; i++) {
                    c = (HttpURLConnection) new URL(courant).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("User-Agent", essai == 0 ? (ua == null ? UA_PC : ua) : UA_PC);
                    if (referer) c.setRequestProperty("Referer", "https://www.facebook.com/");
                    c.setRequestProperty("Accept", "*/*");
                    c.setRequestProperty("Accept-Encoding", "identity");
                    c.setRequestProperty("Range", "bytes=0-0");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        c = null;
                        if (loc == null) break;
                        courant = new URL(new URL(courant), loc).toString();
                        continue;
                    }
                    if (code == 206) {
                        String cr = c.getHeaderField("Content-Range");   // bytes 0-0/123456
                        if (cr != null && cr.contains("/")) {
                            String tot = cr.substring(cr.lastIndexOf('/') + 1).trim();
                            if (!tot.equals("*")) return Long.parseLong(tot);
                        }
                    } else if (code == 200) {
                        long len = c.getContentLengthLong();
                        if (len > 0) return len;
                    }
                    break;
                }
            } catch (Exception ignore) {
                // on tente l'autre méthode
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return -1;
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<String[]> captures = new CopyOnWriteArrayList<>();

    private EditText etLien;
    private TextView tvEtat;
    private LinearLayout resultats;
    private LinearLayout panneauWeb;
    private LinearLayout barre;
    private TextView tvDlTitre;
    private TextView tvDlInfo;
    private ProgressBar pb;
    private Button bAnnuler;
    private Button bOk;
    private WebView web;
    private Thread threadDl;

    private volatile int generation = 0;
    private volatile boolean annule = false;
    private volatile boolean enCours = false;
    private volatile String etape = "";
    private VideoView apercu;
    private String dernierLien = "";
    private volatile boolean webEssaye = false;   // la lecture via la page Facebook a déjà été tentée
    private long premiereTrouvaille = 0;   // moment où la WebView a vu un 1er flux (thread principal)

    // =====================================================================
    //  Cycle de vie
    // =====================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(C_HAUT);
        construire();
        traiterIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        traiterIntent(i);
    }

    @Override
    protected void onDestroy() {
        generation++;
        annule = true;
        arreterApercu();
        nettoyerWeb();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (panneauWeb.getVisibility() == View.VISIBLE) {
            annulerWeb();
            return;
        }
        if (enCours) {
            new AlertDialog.Builder(this)
                    .setTitle("Téléchargement en cours")
                    .setMessage("Annuler le téléchargement et quitter ?")
                    .setPositiveButton("Annuler et quitter", (d, w) -> {
                        annule = true;
                        finish();
                    })
                    .setNegativeButton("Continuer", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    private void traiterIntent(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String u = premierLien(i.getStringExtra(Intent.EXTRA_TEXT));
        if (u != null) {
            etLien.setText(u);
            analyser();
        }
    }

    // =====================================================================
    //  Interface : un seul bouton par action, rien en double
    // =====================================================================

    private void construire() {
        LinearLayout racine = new LinearLayout(this);
        racine.setOrientation(LinearLayout.VERTICAL);
        racine.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_HAUT, C_BG}));

        // --- En-tête : Retour + titre ---
        LinearLayout entete = new LinearLayout(this);
        entete.setOrientation(LinearLayout.HORIZONTAL);
        entete.setGravity(Gravity.CENTER_VERTICAL);
        entete.setPadding(dp(12), dp(10), dp(16), dp(10));
        Button retour = bouton("← Retour", C_CARD);
        retour.setOnClickListener(v -> onBackPressed());
        TextView titre = texte("Vidéos Facebook", 20, true);
        titre.setPadding(dp(14), 0, 0, 0);
        entete.addView(retour, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        entete.addView(titre, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        racine.addView(entete, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- Contenu défilant ---
        ScrollView sv = new ScrollView(this);
        LinearLayout contenu = new LinearLayout(this);
        contenu.setOrientation(LinearLayout.VERTICAL);
        contenu.setPadding(dp(16), dp(4), dp(16), dp(16));
        sv.addView(contenu, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = texte("Collez le lien d'une vidéo publique, ou partagez-la depuis Facebook "
                + "vers RiRe a GOGO. Téléchargez seulement vos propres vidéos ou celles dont "
                + "l'auteur est d'accord, et respectez les conditions de Facebook.", 13, false);
        intro.setTextColor(C_TXT2);
        contenu.addView(intro, lp(-1, -2, 0, 0, 0, 12));

        etLien = new EditText(this);
        etLien.setHint("https://www.facebook.com/…/videos/…");
        etLien.setHintTextColor(0xAAE3E5EA);
        etLien.setTextColor(Color.WHITE);
        etLien.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        etLien.setSingleLine(true);
        etLien.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etLien.setPadding(dp(14), dp(12), dp(14), dp(12));
        etLien.setBackground(rond(C_CARD, 14, 0, 0));
        contenu.addView(etLien, lp(-1, -2, 0, 0, 0, 10));

        LinearLayout rangee = new LinearLayout(this);
        rangee.setOrientation(LinearLayout.HORIZONTAL);
        Button bColler = bouton("📋 Coller", C_VIOLET);
        bColler.setOnClickListener(v -> coller());
        Button bAnalyser = bouton("🔍 Analyser", C_ACCENT);
        bAnalyser.setOnClickListener(v -> analyser());
        LinearLayout.LayoutParams lpa = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpa.rightMargin = dp(6);
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpb.leftMargin = dp(6);
        rangee.addView(bColler, lpa);
        rangee.addView(bAnalyser, lpb);
        contenu.addView(rangee, lp(-1, -2, 0, 0, 0, 12));

        tvEtat = texte("", 14, false);
        tvEtat.setTextColor(C_TXT2);
        contenu.addView(tvEtat, lp(-1, -2, 0, 0, 0, 10));

        resultats = new LinearLayout(this);
        resultats.setOrientation(LinearLayout.VERTICAL);
        contenu.addView(resultats, lp(-1, -2, 0, 0, 0, 0));

        // Panneau de secours (page Facebook ouverte quand l'analyse directe ne trouve rien)
        panneauWeb = new LinearLayout(this);
        panneauWeb.setOrientation(LinearLayout.VERTICAL);
        panneauWeb.setVisibility(View.GONE);
        TextView aide = texte("Lecture de la page Facebook… Si la vidéo ne démarre pas toute seule, "
                + "appuyez sur ▶ dans la page.", 12, false);
        aide.setTextColor(C_TXT2);
        panneauWeb.addView(aide, lp(-1, -2, 0, 0, 0, 6));
        Button bFermerWeb = bouton("✕ Arrêter la recherche", C_CARD);
        bFermerWeb.setOnClickListener(v -> annulerWeb());
        panneauWeb.addView(bFermerWeb, lp(-1, -2, 0, 0, 0, 6));
        contenu.addView(panneauWeb, lp(-1, -2, 0, 8, 0, 0));

        racine.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- Barre de progression, fixée en bas de l'écran ---
        barre = new LinearLayout(this);
        barre.setOrientation(LinearLayout.VERTICAL);
        barre.setPadding(dp(16), dp(12), dp(16), dp(12));
        barre.setBackground(rond(C_CARD, 0, 0, 0));
        barre.setVisibility(View.GONE);

        tvDlTitre = texte("", 16, true);
        barre.addView(tvDlTitre, lp(-1, -2, 0, 0, 0, 8));

        pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        pb.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        pb.setIndeterminateTintList(ColorStateList.valueOf(C_ACCENT));
        barre.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        tvDlInfo = texte("", 13, false);
        tvDlInfo.setTextColor(C_TXT2);
        barre.addView(tvDlInfo, lp(-1, -2, 0, 8, 0, 8));

        bAnnuler = bouton("Annuler", C_VIOLET);
        bAnnuler.setOnClickListener(v -> {
            annule = true;
            bAnnuler.setText("Annulation…");
        });
        bOk = bouton("OK", C_ACCENT);
        bOk.setOnClickListener(v -> barreCacher());
        barre.addView(bAnnuler, lp(-1, -2, 0, 0, 0, 0));
        barre.addView(bOk, lp(-1, -2, 0, 0, 0, 0));

        racine.addView(barre, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(racine);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams lp(int w, int hh, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hh);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private GradientDrawable rond(int couleur, int rayon, int traitEpaisseur, int traitCouleur) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(couleur);
        g.setCornerRadius(dp(rayon));
        if (traitEpaisseur > 0) g.setStroke(dp(traitEpaisseur), traitCouleur);
        return g;
    }

    private TextView texte(String s, int sp, boolean gras) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (gras) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button bouton(String s, int couleur) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rond(couleur, 14, 0, 0));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    //  Lien : coller, analyser
    // =====================================================================

    private String premierLien(String t) {
        if (t == null) return null;
        Matcher m = LIEN.matcher(t);
        if (!m.find()) return null;
        String u = m.group();
        while (u.length() > 0 && ".,;)»\"'".indexOf(u.charAt(u.length() - 1)) >= 0) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private void coller() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            toast("Rien à coller : copiez d'abord le lien de la vidéo");
            return;
        }
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) {
            toast("Rien à coller : copiez d'abord le lien de la vidéo");
            return;
        }
        CharSequence cs = cd.getItemAt(0).coerceToText(this);
        String u = premierLien(cs == null ? null : cs.toString());
        if (u == null) {
            toast("Rien à coller : copiez d'abord le lien de la vidéo");
            return;
        }
        etLien.setText(u);
        analyser();
    }

    private void analyser() {
        if (enCours) {
            toast("Un téléchargement est en cours");
            return;
        }
        String saisie = etLien.getText().toString().trim();
        if (saisie.isEmpty()) {
            toast("Collez d'abord un lien");
            return;
        }
        String l = premierLien(saisie);
        if (l == null) l = saisie.startsWith("http") ? saisie : "https://" + saisie;
        String bas = l.toLowerCase(Locale.ROOT);
        if (!(bas.contains("facebook.com") || bas.contains("fb.watch") || bas.contains("fb.com"))) {
            toast("Ce n'est pas un lien Facebook");
            return;
        }
        final String lien = l;
        dernierLien = lien;
        webEssaye = false;
        final int g = ++generation;
        nettoyerWeb();
        viderResultats();
        barreCacher();
        tvEtat.setText("Analyse du lien…");
        new Thread(() -> {
            final Res r = analyserPage(lien);
            h.post(() -> {
                if (g != generation) return;
                if (r.sd != null || r.hd != null) afficher(r);
                else lancerWeb(lien, g);
            });
        }).start();
    }

    // =====================================================================
    //  Analyse de la page : on essaie plusieurs « identités » (UA)
    // =====================================================================

    private Res analyserPage(String lien) {
        Res r = new Res();
        String mobile = versMobile(lien);
        String[][] essais = {
                {lien, UA_PC},
                {lien, UA_BOT},
                {mobile, UA_TEL},
                {mobile, UA_PC}
        };
        for (String[] e : essais) {
            try {
                String html = lirePage(e[0], e[1]);
                extraire(html, e[1], r);
                if (r.sd != null && r.hd != null) break;
            } catch (Exception ignore) {
                // on passe à l'essai suivant
            }
        }
        return r;
    }

    private String versMobile(String lien) {
        return lien.replaceFirst("^https?://(www\\.)?facebook\\.com", "https://m.facebook.com");
    }

    /**
     * Lit la page en suivant les redirections HTTP (3xx) ET les redirections JavaScript :
     * Facebook répond parfois par une mini-page (~300 octets) qui contient seulement
     * location.href = "...". Sans la suivre, on ne trouve aucun lien de vidéo.
     */
    private String lirePage(String adresse, String ua) throws Exception {
        String courant = adresse;
        for (int i = 0; i < 8; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(courant).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirection vide");
                courant = new URL(new URL(courant), loc).toString();
                continue;
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                c.disconnect();
                throw new Exception("http " + code);
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0 && sb.length() < 6000000) sb.append(buf, 0, n);
            br.close();
            c.disconnect();
            String page = sb.toString();
            if (page.length() < 4000) {
                Matcher m = SAUT_JS.matcher(page);
                if (m.find()) {
                    courant = new URL(new URL(courant), decoder(m.group(1))).toString();
                    continue;
                }
            }
            return page;
        }
        throw new Exception("trop de redirections");
    }

    private void extraire(String html, String ua, Res r) {
        if (html == null) return;
        if (r.dureeMs <= 0) r.dureeMs = extraireDuree(html);
        String sd = premier(html, CLES_SD);
        String hd = premier(html, CLES_HD);
        if (sd == null || hd == null) {
            Matcher m = PROG.matcher(html);
            while (m.find()) {
                String u = decoder(m.group(1));
                if ("HD".equalsIgnoreCase(m.group(3))) {
                    if (hd == null) hd = u;
                } else {
                    if (sd == null) sd = u;
                }
            }
        }
        if (sd == null && hd == null) {
            Matcher mt = META.matcher(html);
            while (mt.find()) {
                String tag = mt.group();
                if (!tag.contains("og:video")) continue;
                Matcher mc = CONTENT.matcher(tag);
                if (!mc.find()) continue;
                String u = decoder(mc.group(1));
                if (u.startsWith("http") && (u.contains(".mp4") || u.contains("fbcdn"))) {
                    sd = u;
                    break;
                }
            }
        }
        if (r.sd == null && sd != null) {
            r.sd = sd;
            r.uaSd = ua;
        }
        if (r.hd == null && hd != null) {
            r.hd = hd;
            r.uaHd = ua;
        }
    }

    private String premier(String html, String[] cles) {
        for (String cle : cles) {
            Pattern p = Pattern.compile("\"" + Pattern.quote(cle) + "\"\\s*:\\s*\"(https?:[^\"]+)\"");
            Matcher m = p.matcher(html);
            if (m.find()) return decoder(m.group(1));
        }
        return null;
    }

    private String decoder(String s) {
        Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String c = String.valueOf((char) Integer.parseInt(m.group(1), 16));
            m.appendReplacement(sb, Matcher.quoteReplacement(c));
        }
        m.appendTail(sb);
        return sb.toString().replace("\\/", "/").replace("&amp;", "&");
    }

    // =====================================================================
    //  Secours : page ouverte dans une WebView, on repère les flux vidéo
    // =====================================================================

    private void lancerWeb(final String lien, final int g) {
        nettoyerWeb();
        captures.clear();
        premiereTrouvaille = 0;
        tvEtat.setText("Lecture de la page Facebook…");
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(UA_PC);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(C_BG);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest rq) {
                noterRequete(rq.getUrl().toString());
                return null;
            }
        });
        panneauWeb.addView(web, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        panneauWeb.setVisibility(View.VISIBLE);
        web.loadUrl(lien);
        h.postDelayed(() -> sonder(g, 0), 2500);
    }

    private void sonder(final int g, final int essai) {
        if (g != generation || web == null) return;
        // On ajoute en tête la durée lue par le lecteur de la page (<!--DUR:secondes-->).
        String js = "(function(){var d=0;try{var v=document.querySelector('video');if(v){v.muted=true;"
                + "var p=v.play();if(p&&p.catch)p.catch(function(){});"
                + "if(isFinite(v.duration))d=v.duration;}}catch(e){}"
                + "return '<!--DUR:'+d+'-->'+document.documentElement.outerHTML;})()";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String valeur) {
                if (g != generation || web == null) return;
                Res r = new Res();
                String html = depuisJson(valeur);
                if (html != null) {
                    Matcher md = DUREE_WEB.matcher(html);
                    if (md.find()) {
                        try {
                            r.dureeMs = (long) (Double.parseDouble(md.group(1)) * 1000);
                        } catch (Exception ignore) {
                            // durée inconnue
                        }
                    }
                    extraire(html, UA_PC, r);
                }
                capturesVersRes(r);
                boolean trouve = r.sd != null || r.hd != null || r.dashV != null;
                if (trouve) {
                    long now = System.currentTimeMillis();
                    if (premiereTrouvaille == 0) premiereTrouvaille = now;
                    // On attend un peu : le son (flux séparé) et la HD arrivent souvent après le 1er flux.
                    boolean complet = (r.sd != null && r.hd != null) || (r.dashV != null && r.dashA != null);
                    if (complet || now - premiereTrouvaille >= 6000) {
                        afficher(r);
                        return;
                    }
                    tvEtat.setText("Vidéo détectée, recherche du son et de la meilleure qualité…");
                }
                if (essai >= 20) {
                    if (trouve) {
                        afficher(r);
                        return;
                    }
                    nettoyerWeb();
                    tvEtat.setText("⚠ Aucun lien de téléchargement trouvé, même après avoir ouvert la page. "
                            + "La vidéo est peut-être privée, Facebook demande une connexion, ou la page a changé.");
                    return;
                }
                h.postDelayed(() -> sonder(g, essai + 1), 1500);
            }
        });
    }

    private String depuisJson(String valeur) {
        if (valeur == null || valeur.equals("null")) return null;
        try {
            Object o = new JSONTokener(valeur).nextValue();
            return o instanceof String ? (String) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Appelé sur un thread de la WebView : on garde les fichiers vidéo/audio de fbcdn. */
    private void noterRequete(String url) {
        String bas = url.toLowerCase(Locale.ROOT);
        if (!(bas.contains("fbcdn") || bas.contains("fbcdn.net")) || !(bas.contains(".mp4") || bas.contains("bytestart="))) return;
        boolean morceau = bas.contains("bytestart=");
        String propre = nettoyer(url);
        for (String[] c : captures) {
            if (c[0].equals(propre)) return;
        }
        captures.add(new String[]{propre, etiquette(url), morceau ? "1" : "0"});
    }

    private String nettoyer(String url) {
        int q = url.indexOf('?');
        if (q < 0) return url;
        StringBuilder sb = new StringBuilder(url.substring(0, q));
        String[] ps = url.substring(q + 1).split("&");
        boolean premier = true;
        for (String p : ps) {
            if (p.startsWith("bytestart=") || p.startsWith("byteend=")) continue;
            sb.append(premier ? '?' : '&').append(p);
            premier = false;
        }
        return sb.toString();
    }

    private String etiquette(String url) {
        try {
            Matcher m = EFG.matcher(url);
            if (!m.find()) return "";
            String b = URLDecoder.decode(m.group(1), "UTF-8");
            byte[] raw = Base64.decode(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            Matcher t = TAG.matcher(new String(raw, StandardCharsets.UTF_8));
            return t.find() ? t.group(1).toLowerCase(Locale.ROOT) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void capturesVersRes(Res r) {
        int meilleureV = -1;
        for (String[] c : captures) {
            String tag = c[1];
            boolean audio = tag.contains("audio");
            boolean separe = tag.contains("dash") || "1".equals(c[2]);
            Matcher m = HAUTEUR.matcher(tag);
            int haut = m.find() ? Integer.parseInt(m.group(1)) : 0;
            if (audio) {
                if (r.dashA == null) r.dashA = c[0];
            } else if (separe) {
                if (haut > meilleureV) {
                    meilleureV = haut;
                    r.dashV = c[0];
                }
            } else if (haut >= 720) {
                if (r.hd == null) {
                    r.hd = c[0];
                    r.uaHd = UA_PC;
                }
            } else {
                if (r.sd == null) {
                    r.sd = c[0];
                    r.uaSd = UA_PC;
                }
            }
        }
        if (r.dashV != null) r.uaDash = UA_PC;
    }

    private void nettoyerWeb() {
        if (web != null) {
            panneauWeb.removeView(web);
            web.stopLoading();
            web.destroy();
            web = null;
        }
        panneauWeb.setVisibility(View.GONE);
    }

    private void annulerWeb() {
        generation++;
        nettoyerWeb();
        tvEtat.setText("Recherche annulée.");
    }

    // =====================================================================
    //  Choix du format : 2 cartes, puis confirmation, puis téléchargement
    // =====================================================================

    private void afficher(final Res r) {
        nettoyerWeb();
        viderResultats();
        final int g = generation;
        if (r.sd != null || r.hd != null) {
            tvEtat.setText("✅ Vidéo trouvée. Regardez l'aperçu, puis choisissez le format :");
            ajouterApercu(r.sd != null ? r.sd : r.hd, r.sd != null ? r.uaSd : r.uaHd, r.dureeMs);
            final Plan psd = r.sd != null ? new Plan("sd", r.sd, null, r.uaSd) : null;
            final Plan phd = r.hd != null ? new Plan("hd", r.hd, null, r.uaHd) : null;
            if (psd != null) psd.dureeMs = r.dureeMs;
            if (phd != null) phd.dureeMs = r.dureeMs;
            final TextView tSd = carte("Standard (SD)",
                    psd != null ? infoCarte("Environ 360p · fichier plus léger", psd, 900000) : "Non disponible pour cette vidéo",
                    psd != null, C_ACCENT,
                    v -> confirmer("SD (qualité standard, environ 360p)", psd));
            final TextView tHd = carte("Haute qualité (HD)",
                    phd != null ? infoCarte("Meilleure image · fichier plus lourd", phd, 2500000) : "Non disponible pour cette vidéo",
                    phd != null, C_VIOLET,
                    v -> confirmer("HD (haute qualité)", phd));
            // Taille réelle : on la demande au serveur en arrière-plan, puis on met les cartes à jour.
            new Thread(() -> {
                if (psd != null) psd.tailleV = sonderTaille(psd.url, psd.ua);
                if (phd != null) phd.tailleV = sonderTaille(phd.url, phd.ua);
                h.post(() -> {
                    if (g != generation) return;
                    if (psd != null) tSd.setText(infoCarte("Environ 360p · fichier plus léger", psd, 900000));
                    if (phd != null) tHd.setText(infoCarte("Meilleure image · fichier plus lourd", phd, 2500000));
                });
            }).start();
        } else if (r.dashV != null) {
            final Plan pd = new Plan("hd", r.dashV, r.dashA, r.uaDash);
            pd.dureeMs = r.dureeMs;
            ajouterApercu(r.dashV, r.uaDash, r.dureeMs);
            final String base;
            final String titreCarte;
            final String formatTxt;
            if (r.dashA != null) {
                tvEtat.setText("✅ Vidéo trouvée (image et son séparés : ils seront rassemblés dans un seul fichier).");
                titreCarte = "Meilleure qualité avec le son";
                base = "Image et son rassemblés dans un seul fichier";
                formatTxt = "meilleure qualité (avec le son)";
            } else {
                tvEtat.setText("⚠ Vidéo trouvée, mais Facebook n'a pas donné le son : elle sera sans son.");
                titreCarte = "Meilleure qualité (sans son)";
                base = "Le son n'a pas été trouvé";
                formatTxt = "meilleure qualité (sans son)";
            }
            final TextView tD = carte(titreCarte, infoCarte(base, pd, 2500000), true, C_ACCENT,
                    v -> confirmer(formatTxt, pd));
            new Thread(() -> {
                pd.tailleV = sonderTaille(pd.url, pd.ua);
                if (pd.audio != null) pd.tailleA = sonderTaille(pd.audio, pd.ua);
                h.post(() -> {
                    if (g != generation) return;
                    tD.setText(infoCarte(base, pd, 2500000));
                });
            }).start();
        } else {
            tvEtat.setText("⚠ Aucun lien de téléchargement trouvé. La vidéo est peut-être privée, "
                    + "Facebook demande une connexion, ou la page a changé.");
        }
    }

    /** Texte sous le titre d'une carte : description · durée · taille (réelle si connue, sinon estimée). */
    private String infoCarte(String base, Plan p, long debitEstime) {
        StringBuilder sb = new StringBuilder(base);
        sb.append("\n⏱ Durée : ").append(formatDuree(p.dureeMs));
        long t = p.tailleTotale();
        if (t > 0) {
            sb.append("\n💾 Taille : ").append(formatTaille(t));
        } else {
            long est = estimer(p.dureeMs, debitEstime);
            sb.append("\n💾 Taille : calcul en cours…");
            if (est > 0) sb.append(" (estimation ≈ ").append(formatTaille(est)).append(")");
        }
        return sb.toString();
    }

    // ---------- Aperçu de la vidéo avant téléchargement ----------

    private void viderResultats() {
        arreterApercu();
        resultats.removeAllViews();
    }

    private void arreterApercu() {
        if (apercu != null) {
            try {
                apercu.stopPlayback();
            } catch (Exception ignore) {
                // rien
            }
            apercu = null;
        }
    }

    /**
     * Lit la vidéo trouvée AVANT le téléchargement. Si le lecteur n'arrive pas à la lire, le lien direct
     * ne marchera pas non plus : on bascule alors sur la lecture via la page Facebook.
     */
    private void ajouterApercu(String url, String ua, final long dureeAnnonceeMs) {
        if (url == null) return;
        final TextView etat = texte("Chargement de l'aperçu…", 12, false);
        etat.setTextColor(C_TXT2);
        resultats.addView(etat, lp(-1, -2, 0, 0, 0, 6));
        final VideoView vv = new VideoView(this);
        apercu = vv;
        vv.setBackgroundColor(Color.BLACK);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);
        vv.setOnPreparedListener(mp -> {
            int lue = 0;
            try {
                lue = vv.getDuration();
            } catch (Exception ignore) {
                // durée inconnue
            }
            String msg = "▶ Aperçu : vérifiez que c'est la bonne vidéo.";
            if (lue > 0 && dureeAnnonceeMs > 0 && lue < dureeAnnonceeMs * 0.9) {
                msg += "\n⚠ Ce lien ne donne que " + formatDuree(lue) + " sur " + formatDuree(dureeAnnonceeMs)
                        + " : le téléchargement essaiera de récupérer la suite.";
            } else if (lue > 0) {
                msg += " Durée du fichier : " + formatDuree(lue) + ".";
            }
            etat.setText(msg);
            vv.start();
        });
        vv.setOnErrorListener((mp, what, extra) -> {
            etat.setText("⚠ Aperçu impossible : le lien direct n'est pas lisible.");
            if (!enCours) basculerWeb("Le lien direct n'est pas lisible, lecture via la page Facebook…");
            return true;
        });
        resultats.addView(vv, lp(-1, dp(220), 0, 0, 0, 12));
        Map<String, String> entetes = new HashMap<>();
        entetes.put("User-Agent", ua == null ? UA_PC : ua);
        entetes.put("Referer", "https://www.facebook.com/");
        try {
            vv.setVideoURI(Uri.parse(url), entetes);
        } catch (Exception e) {
            etat.setText("⚠ Aperçu impossible.");
        }
    }

    /** Dernier recours : ouvrir la page dans une WebView et récupérer les vrais liens que Facebook sert. */
    private void basculerWeb(String message) {
        if (webEssaye || dernierLien.isEmpty()) return;
        webEssaye = true;
        viderResultats();
        tvEtat.setText(message);
        lancerWeb(dernierLien, ++generation);
    }

    private TextView carte(String titre, String sous, boolean dispo, int couleurBord, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(rond(C_CARD, 16, 2, couleurBord));
        TextView t = texte((dispo ? "⬇ " : "") + titre, 17, true);
        TextView s = texte(sous, 13, false);
        s.setTextColor(C_TXT2);
        c.addView(t, lp(-1, -2, 0, 0, 0, 2));
        c.addView(s, lp(-1, -2, 0, 0, 0, 0));
        if (dispo) {
            c.setOnClickListener(l);
        } else {
            c.setAlpha(0.45f);
        }
        resultats.addView(c, lp(-1, -2, 0, 0, 0, 10));
        return s;
    }

    private void confirmer(String format, final Plan p) {
        if (p == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Télécharger la vidéo")
                .setMessage("Voulez-vous télécharger cette vidéo au format " + format + " ?\n\n"
                        + "⏱ Durée : " + formatDuree(p.dureeMs) + "\n"
                        + "💾 Taille : " + (p.tailleTotale() > 0 ? formatTaille(p.tailleTotale()) : "non communiquée par Facebook")
                        + "\n\nElle sera enregistrée dans « Mes vidéos & audios ».\n"
                        + "Pour une longue vidéo, gardez l'écran allumé et l'application ouverte jusqu'à la fin.")
                .setPositiveButton("Télécharger", (d, w) -> telecharger(p))
                .setNegativeButton("Annuler", null)
                .show();
    }

    // =====================================================================
    //  Téléchargement avec progression (barre en bas de l'écran)
    // =====================================================================

    private void telecharger(final Plan p) {
        if (enCours) {
            toast("Un téléchargement est déjà en cours");
            return;
        }
        arreterApercu();
        enCours = true;
        annule = false;
        etape = "";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // longue vidéo : écran allumé
        barreDebut("Téléchargement " + p.nom.toUpperCase(Locale.ROOT), "Connexion…");
        threadDl = new Thread(() -> executer(p));
        threadDl.start();
    }

    private void executer(Plan p) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = getFilesDir();
        dir.mkdirs();
        final String nom = "facebook_" + p.nom + "_" + System.currentTimeMillis() + ".mp4";
        final File finale = new File(dir, nom);
        final File part = new File(dir, nom + ".part");
        final File tv = new File(getCacheDir(), "fb_image.mp4");
        final File ta = new File(getCacheDir(), "fb_son.mp4");
        try {
            // Place disponible : une vidéo de 2 h peut peser plusieurs Go (le double si image et son sont séparés).
            long besoin = p.tailleTotale();
            if (besoin <= 0) besoin = estimer(p.dureeMs, 2500000);
            if (besoin > 0) {
                if (p.audio != null) besoin = besoin * 2;
                long libre = dir.getUsableSpace();
                if (libre < besoin + 50L * 1048576L) {
                    throw new Exception("Espace insuffisant sur le téléphone : il faut environ "
                            + formatTaille(besoin) + ", il reste " + formatTaille(libre) + ".");
                }
            }
            if (p.audio == null) {
                copier(p.url, p.ua, part, 0f, 1f, p.tailleV);
                if (!estVideo(part)) {
                    throw new Exception("Le fichier reçu n'est pas une vidéo : Facebook a refusé le lien. Réessayez.");
                }
                if (!part.renameTo(finale)) throw new Exception("Enregistrement impossible");
            } else {
                etape = "Image";
                copier(p.url, p.ua, tv, 0f, 0.45f, p.tailleV);
                etape = "Son";
                copier(p.audio, p.ua, ta, 0.45f, 0.9f, p.tailleA);
                etape = "";
                h.post(() -> {
                    pb.setIndeterminate(true);
                    tvDlInfo.setText("Assemblage de l'image et du son…");
                });
                muxer(tv, ta, finale);
            }
            final long taille = finale.length();
            // Vérification : le fichier est-il complet ? (durée et taille comparées à ce que Facebook annonce)
            final long reelMs = dureeFichier(finale);
            String alerte = "";
            if (p.dureeMs > 0 && reelMs > 0 && reelMs < p.dureeMs * 0.92) {
                alerte = "\n\n⚠ Vidéo incomplète : " + formatDuree(reelMs) + " récupérées sur "
                        + formatDuree(p.dureeMs) + ". Facebook n'a pas servi la suite de ce lien.";
            } else if (p.audio == null && p.tailleV > 0 && taille < p.tailleV * 0.97) {
                alerte = "\n\n⚠ Fichier plus petit que prévu (" + formatTaille(taille) + " sur "
                        + formatTaille(p.tailleV) + ") : la vidéo peut être coupée.";
            }
            final String finInfo = nom + " · " + formatTaille(taille)
                    + (reelMs > 0 ? " · " + formatDuree(reelMs) : "")
                    + "\nRetrouvez-le dans Sans internet → Mes vidéos & audios." + alerte;
            final boolean complet = alerte.isEmpty();
            h.post(() -> barreFin(true, complet ? "✅ Téléchargement terminé" : "⚠ Téléchargement terminé (incomplet)", finInfo));
        } catch (Exception e) {
            part.delete();
            if (p.audio != null) finale.delete();
            final boolean a = annule;
            final String msg = e.getMessage() == null ? "erreur inconnue" : e.getMessage();
            final boolean basculer = !webEssaye && msg.contains("page web");
            h.post(() -> {
                if (a) {
                    enCours = false;
                    barreCacher();
                } else if (basculer) {
                    enCours = false;
                    barreCacher();
                    basculerWeb("Facebook refuse le lien direct. Lecture de la page pour obtenir un lien valide…");
                } else {
                    barreFin(false, "⚠ Téléchargement impossible", msg);
                }
            });
        } finally {
            tv.delete();
            ta.delete();
        }
    }

    /**
     * Essaie plusieurs combinaisons (identité UA, avec/sans Referer) : un lien signé pour l'une
     * est parfois refusé pour l'autre. Si tout échoue, le message d'erreur dit pourquoi.
     */
    private void copier(String adresse, String ua, File dest, float debut, float fin, long attendu) throws Exception {
        List<String> uas = new ArrayList<>();
        uas.add(ua);
        for (String u : new String[]{UA_PC, UA_TEL, UA_BOT}) {
            if (!uas.contains(u)) uas.add(u);
        }
        StringBuilder bilan = new StringBuilder();
        int n = 0;
        for (int k = 0; k < uas.size() + 1; k++) {
            if (annule) throw new Exception("annulé");
            // dernier essai : identité PC mais SANS Referer
            String u = k < uas.size() ? uas.get(k) : UA_PC;
            boolean referer = k < uas.size();
            try {
                copierUnique(adresse, u, referer, dest, debut, fin, attendu);
                return;
            } catch (Exception e) {
                if (annule) throw e;
                n++;
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (bilan.indexOf(m) < 0) {
                    if (bilan.length() > 0) bilan.append(" / ");
                    bilan.append(m);
                }
            }
        }
        String res = bilan.length() == 0 ? "échec du téléchargement" : bilan.toString();
        if (res.contains("http 403") || res.contains("http 410") || res.contains("http 404")) {
            res += "\nLe lien a probablement expiré : appuyez sur « Analyser » pour en obtenir un nouveau.";
        }
        throw new Exception(res);
    }

    /**
     * Télécharge TOUT le fichier, même quand le serveur n'en donne qu'une partie par réponse
     * (cas des longues vidéos : une seule réponse s'arrête après quelques minutes / ~40 Mo).
     * Après chaque réponse on redemande la suite avec « Range: bytes=<déjà reçu>- » et on
     * continue tant que le serveur répond 206 ; 416 (ou une réponse sans Range) = c'est la fin.
     */
    private void copierUnique(String adresse, String ua, boolean referer, File dest,
                              float debut, float fin, long attendu) throws Exception {
        FileOutputStream out = new FileOutputStream(dest);
        try {
            long lu = 0;             // octets déjà écrits dans le fichier
            long totalConnu = -1;    // taille totale, quand le serveur la donne (Content-Range)
            long dernier = 0;
            for (int tour = 0; tour < 5000; tour++) {
                if (annule) throw new Exception("annulé");
                if (totalConnu > 0 && lu >= totalConnu) break;

                String courant = adresse;
                HttpURLConnection c = null;
                int code = -1;
                try {
                    // Redirections suivies à la main (y compris https -> http, que Java ne suit pas tout seul)
                    for (int i = 0; i < 6; i++) {
                        c = (HttpURLConnection) new URL(courant).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setRequestProperty("User-Agent", ua);
                        if (referer) {
                            c.setRequestProperty("Referer", "https://www.facebook.com/");
                            c.setRequestProperty("Origin", "https://www.facebook.com");
                        }
                        c.setRequestProperty("Accept", "*/*");
                        c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8");
                        c.setRequestProperty("Accept-Encoding", "identity");
                        if (lu > 0) c.setRequestProperty("Range", "bytes=" + lu + "-");
                        try {
                            String ck = CookieManager.getInstance().getCookie(courant);
                            if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                        } catch (Exception ignore) {
                            // pas de cookies : on continue
                        }
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        code = c.getResponseCode();
                        if (code >= 300 && code < 400) {
                            String loc = c.getHeaderField("Location");
                            c.disconnect();
                            c = null;
                            if (loc == null) throw new Exception("redirection vide");
                            courant = new URL(new URL(courant), loc).toString();
                            continue;
                        }
                        break;
                    }
                    if (c == null) throw new Exception("trop de redirections");

                    if (lu > 0) {
                        // Demande de la suite : 206 = voilà la suite ; tout le reste = le fichier est fini.
                        if (code != 206) break;
                    } else if (code != 200 && code != 206) {
                        throw new Exception("http " + code);
                    }

                    String type = c.getContentType();
                    if (type != null) {
                        String t = type.toLowerCase(Locale.ROOT);
                        if (t.startsWith("text/") || t.contains("html") || t.contains("json")) {
                            if (lu > 0) break;
                            throw new Exception("Facebook a répondu par une page web au lieu de la vidéo ["
                                    + new URL(courant).getHost() + " · " + type + "]");
                        }
                    }

                    long len = c.getContentLengthLong();
                    if (code == 206) {
                        String cr = c.getHeaderField("Content-Range");   // bytes 100-199/5000
                        if (cr != null && cr.contains("/")) {
                            String tot = cr.substring(cr.lastIndexOf('/') + 1).trim();
                            try {
                                if (!tot.equals("*")) totalConnu = Long.parseLong(tot);
                            } catch (NumberFormatException ignore) {
                                // total inconnu
                            }
                        }
                    }

                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[65536];
                    long recu = 0;
                    boolean premierBloc = true;
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) {
                            if (annule) throw new Exception("annulé");
                            if (premierBloc) {
                                premierBloc = false;
                                if (lu == 0 && ressembleHtml(buf, n)) {
                                    String deb = new String(buf, 0, Math.min(n, 60), StandardCharsets.ISO_8859_1)
                                            .replaceAll("\\s+", " ");
                                    throw new Exception("Facebook a répondu par une page web au lieu de la vidéo ["
                                            + new URL(courant).getHost() + " · " + deb + "]");
                                }
                            }
                            out.write(buf, 0, n);
                            lu += n;
                            recu += n;
                            long now = System.currentTimeMillis();
                            if (now - dernier > 200) {
                                dernier = now;
                                long ref = totalConnu > 0 ? totalConnu : attendu;
                                progresser(lu, ref > lu ? ref : -1, debut, fin);
                            }
                        }
                    } finally {
                        in.close();
                    }
                    if (len > 0 && recu < len) {
                        throw new Exception("téléchargement coupé (" + mo(lu) + " Mo reçus)");
                    }
                    if (recu == 0) break;   // le serveur n'a plus rien à donner
                } finally {
                    if (c != null) c.disconnect();
                }
            }
            if (lu == 0) throw new Exception("fichier vide");
            long ref = totalConnu > 0 ? totalConnu : attendu;
            progresser(lu, ref > lu ? ref : lu, debut, fin);
            if (totalConnu > 0 && lu < totalConnu) {
                throw new Exception("téléchargement coupé (" + mo(lu) + "/" + mo(totalConnu) + " Mo)");
            }
        } finally {
            out.close();
        }
    }

    /** Durée réelle du fichier enregistré (ms), 0 si illisible. */
    private long dureeFichier(File f) {
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(f.getAbsolutePath());
            String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                mr.release();
            } catch (Exception ignore) {
                // rien
            }
        }
    }

    private boolean ressembleHtml(byte[] buf, int n) {
        String debut = new String(buf, 0, Math.min(n, 256), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return debut.contains("<html") || debut.contains("<!doctype") || debut.contains("location.href");
    }

    private boolean estVideo(File f) {
        if (f.length() < 20000) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[256];
            int n = in.read(b);
            return n > 0 && !ressembleHtml(b, n);
        } catch (Exception e) {
            return false;
        }
    }

    private void progresser(final long lu, final long total, final float debut, final float fin) {
        final String et = etape;
        h.post(() -> {
            if (!enCours) return;
            if (total <= 0) {
                pb.setIndeterminate(true);
                tvDlInfo.setText((et.isEmpty() ? "" : et + " · ") + formatTaille(lu) + " téléchargés");
            } else {
                float frac = Math.min(1f, (float) lu / total);
                pb.setIndeterminate(false);
                pb.setProgress((int) ((debut + (fin - debut) * frac) * 1000));
                tvDlInfo.setText((et.isEmpty() ? "" : et + " · ") + (int) (frac * 100) + " % · "
                        + formatTaille(lu) + " / " + formatTaille(total));
            }
        });
    }

    private String mo(long octets) {
        return String.format(Locale.FRANCE, "%.1f", octets / 1048576.0);
    }

    // ---------- Assemblage image + son ----------

    private void muxer(File video, File audio, File sortie) throws Exception {
        MediaExtractor ev = new MediaExtractor();
        MediaExtractor ea = new MediaExtractor();
        MediaMuxer mm = null;
        boolean demarre = false;
        try {
            ev.setDataSource(video.getAbsolutePath());
            ea.setDataSource(audio.getAbsolutePath());
            int iv = piste(ev, "video/");
            int ia = piste(ea, "audio/");
            if (iv < 0 || ia < 0) throw new Exception("image ou son introuvable");
            ev.selectTrack(iv);
            ea.selectTrack(ia);
            MediaFormat fv = ev.getTrackFormat(iv);
            mm = new MediaMuxer(sortie.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (fv.containsKey(MediaFormat.KEY_ROTATION)) {
                try {
                    mm.setOrientationHint(fv.getInteger(MediaFormat.KEY_ROTATION));
                } catch (Exception ignore) {
                    // pas grave
                }
            }
            int tv = mm.addTrack(fv);
            int ta = mm.addTrack(ea.getTrackFormat(ia));
            mm.start();
            demarre = true;

            // Écriture entrelacée : à chaque tour on écrit l'échantillon le plus ancien (image ou son)
            ByteBuffer buf = ByteBuffer.allocate(4 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                long tImg = ev.getSampleTime();
                long tSon = ea.getSampleTime();
                if (tImg < 0 && tSon < 0) break;
                boolean image = tSon < 0 || (tImg >= 0 && tImg <= tSon);
                MediaExtractor ex = image ? ev : ea;
                buf.clear();
                int n = ex.readSampleData(buf, 0);
                if (n < 0) {
                    ex.advance();
                    continue;
                }
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, n, ex.getSampleTime(), flags);
                mm.writeSampleData(image ? tv : ta, buf, info);
                ex.advance();
            }
            mm.stop();
            demarre = false;
        } finally {
            ev.release();
            ea.release();
            if (mm != null) {
                try {
                    if (demarre) mm.stop();
                } catch (Exception ignore) {
                    // rien
                }
                try {
                    mm.release();
                } catch (Exception ignore) {
                    // rien
                }
            }
        }
    }

    private int piste(MediaExtractor ex, String prefixe) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefixe)) return i;
        }
        return -1;
    }

    // ---------- Barre du bas ----------

    private void barreDebut(String titre, String info) {
        barre.setVisibility(View.VISIBLE);
        tvDlTitre.setText(titre);
        tvDlInfo.setText(info);
        pb.setIndeterminate(true);
        bAnnuler.setText("Annuler");
        bAnnuler.setVisibility(View.VISIBLE);
        bOk.setVisibility(View.GONE);
    }

    private void barreFin(boolean ok, String titre, String info) {
        enCours = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        barre.setVisibility(View.VISIBLE);
        tvDlTitre.setText(titre);
        tvDlInfo.setText(info);
        pb.setIndeterminate(false);
        pb.setProgress(ok ? 1000 : 0);
        bAnnuler.setVisibility(View.GONE);
        bOk.setText(ok ? "OK" : "Fermer");
        bOk.setVisibility(View.VISIBLE);
    }

    private void barreCacher() {
        if (enCours) return;
        barre.setVisibility(View.GONE);
    }
}
