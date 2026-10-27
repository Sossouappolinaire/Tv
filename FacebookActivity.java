package bj.radio;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Télécharge une vidéo Facebook publique vers « Mes vidéos & audios ».
 *
 * Méthode en deux temps :
 *  1. lecture rapide de la page (comme un navigateur d'ordinateur, redirections et cookies suivis) ;
 *  2. si rien n'est trouvé, la page est ouverte dans un navigateur intégré et on récupère
 *     l'adresse du fichier vidéo au moment où Facebook la charge.
 */
public class FacebookActivity extends Activity {

    static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    static final String UA_BOT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";
    static final String UA_TEL = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    static final String[] CLES_SD = {"browser_native_sd_url", "sd_src_no_ratelimit", "sd_src", "playable_url"};
    static final String[] CLES_HD = {"browser_native_hd_url", "hd_src_no_ratelimit", "hd_src", "playable_url_quality_hd"};

    static {
        // Facebook pose des cookies pendant les redirections : sans eux la page boucle ou se bloque
        if (java.net.CookieHandler.getDefault() == null)
            java.net.CookieHandler.setDefault(new java.net.CookieManager());
    }

    final Handler h = new Handler(Looper.getMainLooper());
    final List<String[]> captures = new ArrayList<>();   // {adresse propre, étiquette, "r" si segment}
    int generation = 0;

    EditText etLien;
    TextView tvEtat;
    LinearLayout resultats, panneauWeb;
    WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);

        Ui.ajouter(root, Ui.texte(this, "📘 Vidéos Facebook", 24, Ui.TXT, true), 0);
        Ui.ajouter(root, Ui.texte(this,
                "Collez le lien d'une vidéo publique, ou partagez-la depuis Facebook vers RiRe a GOGO. "
                + "Téléchargez seulement vos propres vidéos ou celles dont l'auteur est d'accord, "
                + "et respectez les conditions de Facebook.", 13, Ui.TXT2, false), 6);

        etLien = Ui.champ(this, "https://www.facebook.com/…/videos/…");
        Ui.ajouter(root, etLien, 12);

        LinearLayout ligne = new LinearLayout(this);
        Button bColler = Ui.bouton(this, "📋 Coller", Ui.VIOLET);
        Button bAnalyser = Ui.bouton(this, "🔍 Analyser", Ui.ACCENT);
        bColler.setOnClickListener(v -> coller());
        bAnalyser.setOnClickListener(v -> analyser());
        ligne.addView(bColler, Ui.lp(this, 0, -2, 1f, 0, 0));
        ligne.addView(bAnalyser, Ui.lp(this, 0, -2, 1f, 0, 8));
        Ui.ajouter(root, ligne, 8);

        tvEtat = Ui.texte(this, "", 14, Ui.TXT, false);
        Ui.ajouter(root, tvEtat, 14);

        resultats = new LinearLayout(this);
        resultats.setOrientation(LinearLayout.VERTICAL);
        Ui.ajouter(root, resultats, 6);

        // Navigateur intégré (visible seulement pendant la recherche de la vidéo)
        panneauWeb = new LinearLayout(this);
        panneauWeb.setOrientation(LinearLayout.VERTICAL);
        panneauWeb.setBackground(Ui.rond(Ui.CARD, 14, this));
        int q = Ui.dp(this, 8);
        panneauWeb.setPadding(q, q, q, q);
        panneauWeb.addView(Ui.texte(this,
                "Ouverture de la page Facebook… Si la vidéo ne démarre pas toute seule, appuyez sur ▶ dans la page.",
                12, Ui.TXT2, false));
        Button bFermer = Ui.bouton(this, "✖ Annuler", Ui.CARD);
        bFermer.setOnClickListener(v -> annulerWeb());
        panneauWeb.addView(bFermer, Ui.lp(this, -1, -2, 0f, 6, 0));
        panneauWeb.setVisibility(View.GONE);
        Ui.ajouter(root, panneauWeb, 10);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(root);
        setContentView(sv);

        // Lien reçu par « Partager » depuis l'application Facebook
        Intent in = getIntent();
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            String t = in.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) {
                Matcher m = Pattern.compile("https?://\\S+").matcher(t);
                if (m.find()) {
                    etLien.setText(m.group());
                    analyser();
                }
            }
        }
    }

    void coller() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
            if (t != null) {
                // « Titre de la vidéo https://… » : on garde seulement le lien
                Matcher m = Pattern.compile("https?://\\S+").matcher(t);
                etLien.setText(m.find() ? m.group() : t.toString().trim());
                analyser();   // le lien est analysé tout de suite : les formats s'affichent sans taper sur Analyser
                return;
            }
        }
        Toast.makeText(this, "Rien à coller : copiez d'abord le lien de la vidéo", Toast.LENGTH_SHORT).show();
    }

    // ---------- Analyse ----------

    void analyser() {
        String lien = etLien.getText().toString().trim();
        if (lien.isEmpty()) {
            Toast.makeText(this, "Collez d'abord un lien", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!lien.startsWith("http")) lien = "https://" + lien;
        if (!(lien.contains("facebook.com") || lien.contains("fb.watch") || lien.contains("fb.com"))) {
            Toast.makeText(this, "Ce n'est pas un lien Facebook", Toast.LENGTH_SHORT).show();
            return;
        }
        final String adresse = lien;
        final int gen = ++generation;
        nettoyerWeb();
        resultats.removeAllViews();
        tvEtat.setText("Analyse du lien…");
        new Thread(() -> {
            String sd = null, hd = null;
            String[][] essais = {
                    {adresse, UA_PC},
                    {adresse, UA_BOT},
                    {versMobile(adresse), UA_TEL}
            };
            for (String[] essai : essais) {
                try {
                    String[] r = extraire(lirePage(essai[0], essai[1]));
                    if (r[0] != null || r[1] != null) { sd = r[0]; hd = r[1]; break; }
                } catch (Exception ignored) {}
            }
            final String fSd = sd, fHd = hd;
            runOnUiThread(() -> {
                if (gen != generation) return;
                if (fSd != null || fHd != null) afficher(fSd, fHd, null, null, null);
                else lancerWeb(adresse, gen);
            });
        }).start();
    }

    static String versMobile(String adresse) {
        return adresse.replaceFirst("^https?://(www\\.)?facebook\\.com", "https://m.facebook.com");
    }

    /** Lit une page en suivant les redirections à la main (liens « share », fb.watch…). */
    static String lirePage(String adresse, String ua) throws Exception {
        String u = adresse;
        for (int k = 0; k < 8; k++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
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
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new Exception("http " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 6000000) sb.append(buf, 0, n);
            r.close();
            c.disconnect();
            return sb.toString();
        }
        throw new Exception("trop de redirections");
    }

    /** Cherche dans le texte d'une page les liens {SD, HD} d'une vidéo avec son. */
    static String[] extraire(String html) {
        String sd = chercher(html, CLES_SD), hd = chercher(html, CLES_HD);
        if (sd == null || hd == null) {
            Matcher m = Pattern.compile("\"progressive_url\"\\s*:\\s*\"(https?:[^\"]+)\"(.{0,300}?)\"quality\"\\s*:\\s*\"(\\w+)\"",
                    Pattern.DOTALL).matcher(html);
            while (m.find()) {
                String u = decoder(m.group(1));
                if ("HD".equalsIgnoreCase(m.group(3))) { if (hd == null) hd = u; }
                else if (sd == null) sd = u;
            }
        }
        if (sd == null && hd == null) {
            Matcher mt = Pattern.compile("<meta[^>]+>").matcher(html);
            Pattern pc = Pattern.compile("content=\"([^\"]+)\"");
            while (mt.find()) {
                String tag = mt.group();
                if (!tag.contains("og:video")) continue;
                Matcher c = pc.matcher(tag);
                if (c.find()) {
                    String u = decoder(c.group(1));
                    if (u.startsWith("http") && (u.contains(".mp4") || u.contains("fbcdn"))) { sd = u; break; }
                }
            }
        }
        return new String[]{sd, hd};
    }

    static String chercher(String html, String[] cles) {
        for (String cle : cles) {
            Matcher m = Pattern.compile("\"" + cle + "\"\\s*:\\s*\"(https?:[^\"]+)\"").matcher(html);
            if (m.find()) return decoder(m.group(1));
        }
        return null;
    }

    static String decoder(String s) {
        Matcher mu = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (mu.find())
            mu.appendReplacement(sb, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(mu.group(1), 16))));
        mu.appendTail(sb);
        return sb.toString().replace("\\/", "/").replace("&amp;", "&");
    }

    // ---------- Navigateur intégré ----------

    void lancerWeb(final String adresse, final int gen) {
        nettoyerWeb();
        synchronized (captures) { captures.clear(); }
        tvEtat.setText("Lecture de la page Facebook…");
        try {
            web = new WebView(this);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setUserAgentString(UA_PC);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(web, true);
            web.setBackgroundColor(Ui.BG);
            web.setOnTouchListener((v, ev) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
            web.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    noterRequete(r.getUrl().toString());
                    return null;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    String sch = r.getUrl().getScheme();
                    return sch == null || !(sch.equals("http") || sch.equals("https"));   // bloque fb://, intent://
                }
            });
            panneauWeb.addView(web, 1, new LinearLayout.LayoutParams(-1, Ui.dp(this, 320)));
            panneauWeb.setVisibility(View.VISIBLE);
            web.loadUrl(adresse);
            h.postDelayed(() -> sonder(gen, 0), 3000);
        } catch (Exception e) {
            afficher(null, null, null, null, null);
        }
    }

    /** Appelé pour chaque requête de la page : on retient les fichiers vidéo de Facebook. */
    void noterRequete(String url) {
        String bas = url.toLowerCase(Locale.ROOT);
        if (!bas.contains("fbcdn") || !bas.contains(".mp4")) return;
        String propre = nettoyer(url);
        synchronized (captures) {
            for (String[] c : captures) if (c[0].equals(propre)) return;
            captures.add(new String[]{propre, etiquette(url), bas.contains("bytestart=") ? "r" : ""});
        }
    }

    /** Enlève les paramètres bytestart/byteend : on obtient alors le fichier entier. */
    static String nettoyer(String url) {
        int q = url.indexOf('?');
        if (q < 0) return url;
        StringBuilder sb = new StringBuilder(url.substring(0, q));
        String sep = "?";
        for (String p : url.substring(q + 1).split("&")) {
            if (p.startsWith("bytestart=") || p.startsWith("byteend=")) continue;
            sb.append(sep).append(p);
            sep = "&";
        }
        return sb.toString();
    }

    /** Lit l'étiquette « vencode_tag » cachée dans le paramètre efg (audio, dash…, progressive…). */
    static String etiquette(String url) {
        try {
            Matcher m = Pattern.compile("[?&]efg=([^&]+)").matcher(url);
            if (!m.find()) return "";
            String b = URLDecoder.decode(m.group(1), "UTF-8").replace('-', '+').replace('_', '/');
            while (b.length() % 4 != 0) b += "=";
            String j = new String(Base64.decode(b, Base64.DEFAULT), "UTF-8");
            Matcher t = Pattern.compile("\"vencode_tag\"\\s*:\\s*\"([^\"]*)\"").matcher(j);
            return t.find() ? t.group(1).toLowerCase(Locale.ROOT) : "";
        } catch (Exception e) {
            return "";
        }
    }

    void sonder(final int gen, final int n) {
        if (gen != generation || web == null) return;
        String js = "(function(){try{var v=document.querySelector('video');"
                + "if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}}catch(e){}"
                + "return document.documentElement.outerHTML;})()";
        web.evaluateJavascript(js, valeur -> {
            if (gen != generation || web == null) return;
            String html = null;
            try {
                Object o = new JSONTokener(valeur).nextValue();
                if (o instanceof String) html = (String) o;
            } catch (Exception ignored) {}
            String[] r = html == null ? new String[]{null, null} : extraire(html);
            if (r[0] != null || r[1] != null) {
                afficher(r[0], r[1], null, null, null);
                return;
            }
            String[] cap = meilleuresCaptures();      // {complet, video seule, audio, inconnu}
            boolean dernier = n >= 14;
            if (cap[0] != null) {
                afficher(cap[0], null, null, null, null);
            } else if (cap[1] != null && cap[2] != null) {
                afficher(null, null, cap[1], cap[2], null);
            } else if (dernier) {
                afficher(null, null, cap[1], null, cap[3]);
            } else {
                tvEtat.setText("Lecture de la page Facebook… (" + (n + 1) + ")");
                h.postDelayed(() -> sonder(gen, n + 1), 2000);
            }
        });
    }

    String[] meilleuresCaptures() {
        String complet = null, video = null, audio = null, inconnu = null;
        int meilleureHauteur = -1;
        Pattern ph = Pattern.compile("(\\d{3,4})p");
        synchronized (captures) {
            for (String[] c : captures) {
                String tag = c[1];
                if (tag.contains("audio")) {
                    if (audio == null) audio = c[0];
                } else if (tag.contains("dash")) {
                    Matcher m = ph.matcher(tag);
                    int hauteur = m.find() ? Integer.parseInt(m.group(1)) : 0;
                    if (video == null || hauteur > meilleureHauteur) { video = c[0]; meilleureHauteur = hauteur; }
                } else if (tag.isEmpty() && c[2].equals("r")) {
                    if (inconnu == null) inconnu = c[0];          // segment dont on ne sait pas si c'est l'image ou le son
                } else {
                    if (complet == null) complet = c[0];
                }
            }
        }
        return new String[]{complet, video, audio, inconnu};
    }

    void annulerWeb() {
        generation++;
        nettoyerWeb();
        tvEtat.setText("Recherche annulée.");
    }

    void nettoyerWeb() {
        h.removeCallbacksAndMessages(null);
        if (web != null) {
            try {
                web.stopLoading();
                web.loadUrl("about:blank");
                panneauWeb.removeView(web);
                web.destroy();
            } catch (Exception ignored) {}
            web = null;
        }
        panneauWeb.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (panneauWeb.getVisibility() == View.VISIBLE) annulerWeb();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        generation++;
        nettoyerWeb();
        super.onDestroy();
    }

    // ---------- Résultats ----------

    void afficher(String sd, String hd, String video, String audio, String essai) {
        nettoyerWeb();
        resultats.removeAllViews();
        if (sd != null || hd != null) {
            tvEtat.setText("✅ Vidéo trouvée. Choisissez la qualité :");
            if (sd != null) {
                Button b1 = Ui.bouton(this, "⬇ Qualité standard (SD, environ 360p)", Ui.ACCENT);
                final String u = sd;
                b1.setOnClickListener(v -> telecharger(u, "sd"));
                Ui.ajouter(resultats, b1, 8);
            }
            if (hd != null && !hd.equals(sd)) {
                Button b2 = Ui.bouton(this, "⬇ Haute qualité (HD)", Ui.VIOLET);
                final String u = hd;
                b2.setOnClickListener(v -> telecharger(u, "hd"));
                Ui.ajouter(resultats, b2, 8);
            }
        } else if (video != null && audio != null) {
            tvEtat.setText("✅ Vidéo trouvée (image et son séparés : ils seront rassemblés dans un seul fichier).");
            Button b = Ui.bouton(this, "⬇ Télécharger la vidéo avec le son", Ui.ACCENT);
            b.setOnClickListener(v -> fusionner(video, audio));
            Ui.ajouter(resultats, b, 8);
        } else if (video != null) {
            tvEtat.setText("⚠ Vidéo trouvée, mais Facebook n'a pas donné le son : elle sera sans son.");
            Button b = Ui.bouton(this, "⬇ Télécharger (sans son)", Ui.VIOLET);
            b.setOnClickListener(v -> telecharger(video, "sanson"));
            Ui.ajouter(resultats, b, 8);
        } else if (essai != null) {
            tvEtat.setText("⚠ Un fichier a été trouvé, mais je ne peux pas garantir qu'il contient l'image et le son.");
            Button b = Ui.bouton(this, "⬇ Essayer ce fichier", Ui.VIOLET);
            b.setOnClickListener(v -> telecharger(essai, "essai"));
            Ui.ajouter(resultats, b, 8);
        } else {
            tvEtat.setText("⚠ Aucun lien de téléchargement trouvé, même après avoir ouvert la page. "
                    + "La vidéo est peut-être privée, Facebook demande une connexion, ou la page a changé.");
        }
    }

    void telecharger(String url, String qualite) {
        try {
            String nom = "facebook_" + qualite + "_" + System.currentTimeMillis() + ".mp4";
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.addRequestHeader("User-Agent", UA_PC);
            r.addRequestHeader("Referer", "https://www.facebook.com/");
            r.setTitle(nom);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MOVIES, nom);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Téléchargement lancé. Retrouvez-le dans Sans internet → Mes vidéos & audios.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Téléchargement impossible", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Image + son séparés : téléchargement puis fusion ----------

    void fusionner(final String uVideo, final String uAudio) {
        tvEtat.setText("Téléchargement de l'image…");
        new Thread(() -> {
            File fv = new File(getCacheDir(), "fb_image.mp4");
            File fa = new File(getCacheDir(), "fb_son.mp4");
            File sortie = null;
            try {
                copierUrl(uVideo, fv, "Téléchargement de l'image");
                copierUrl(uAudio, fa, "Téléchargement du son");
                runOnUiThread(() -> tvEtat.setText("Assemblage de l'image et du son…"));
                File dossier = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (dossier != null) dossier.mkdirs();
                sortie = new File(dossier, "facebook_" + System.currentTimeMillis() + ".mp4");
                muxer(fv, fa, sortie);
                runOnUiThread(() -> tvEtat.setText(
                        "✅ Vidéo enregistrée. Retrouvez-la dans Sans internet → Mes vidéos & audios."));
            } catch (Exception e) {
                if (sortie != null) sortie.delete();
                final String msg = e.getMessage() == null ? "erreur" : e.getMessage();
                runOnUiThread(() -> tvEtat.setText("⚠ Téléchargement impossible (" + msg + ")."));
            } finally {
                fv.delete();
                fa.delete();
            }
        }).start();
    }

    void copierUrl(String adresse, File fichier, String libelle) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
        c.setRequestProperty("User-Agent", UA_PC);
        c.setRequestProperty("Referer", "https://www.facebook.com/");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code != 200 && code != 206) throw new Exception("http " + code);
        long total = 0;
        long dernier = 0;
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(fichier)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total - dernier > 1000000) {
                    dernier = total;
                    final String t = libelle + "… " + (total / 1000000) + " Mo";
                    runOnUiThread(() -> tvEtat.setText(t));
                }
            }
        } finally {
            c.disconnect();
        }
        if (total == 0) throw new Exception("fichier vide");
    }

    static int piste(MediaExtractor ex, String prefixe) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefixe)) return i;
        }
        return -1;
    }

    /** Range l'image et le son dans un seul fichier mp4, sans les recompresser. */
    static void muxer(File video, File audio, File sortie) throws Exception {
        MediaExtractor ev = new MediaExtractor();
        MediaExtractor ea = new MediaExtractor();
        MediaMuxer mux = null;
        boolean demarre = false;
        try {
            ev.setDataSource(video.getAbsolutePath());
            ea.setDataSource(audio.getAbsolutePath());
            int tv = piste(ev, "video/");
            int ta = piste(ea, "audio/");
            if (tv < 0 || ta < 0) throw new Exception("image ou son introuvable");
            mux = new MediaMuxer(sortie.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int mv = mux.addTrack(ev.getTrackFormat(tv));
            int ma = mux.addTrack(ea.getTrackFormat(ta));
            ev.selectTrack(tv);
            ea.selectTrack(ta);
            mux.start();
            demarre = true;
            ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long tpsV = ev.getSampleTime();
            long tpsA = ea.getSampleTime();
            while (tpsV >= 0 || tpsA >= 0) {
                boolean prendreVideo = tpsV >= 0 && (tpsA < 0 || tpsV <= tpsA);
                MediaExtractor ex = prendreVideo ? ev : ea;
                buf.clear();
                int n = ex.readSampleData(buf, 0);
                if (n < 0) {
                    if (prendreVideo) tpsV = -1; else tpsA = -1;
                    continue;
                }
                info.offset = 0;
                info.size = n;
                info.presentationTimeUs = ex.getSampleTime();
                info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mux.writeSampleData(prendreVideo ? mv : ma, buf, info);
                ex.advance();
                if (prendreVideo) tpsV = ev.getSampleTime(); else tpsA = ea.getSampleTime();
            }
            mux.stop();
            demarre = false;
        } finally {
            ev.release();
            ea.release();
            if (mux != null) {
                try { if (demarre) mux.stop(); } catch (Exception ignored) {}
                try { mux.release(); } catch (Exception ignored) {}
            }
        }
    }
}
