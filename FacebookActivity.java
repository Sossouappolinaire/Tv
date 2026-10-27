package bj.radio;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Télécharge une vidéo Facebook publique (qualité standard ou HD) vers « Mes vidéos & audios ». */
public class FacebookActivity extends Activity {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    static final String[] CLES_SD = {"browser_native_sd_url", "sd_src_no_ratelimit", "sd_src", "playable_url"};
    static final String[] CLES_HD = {"browser_native_hd_url", "hd_src_no_ratelimit", "hd_src", "playable_url_quality_hd"};

    EditText etLien;
    TextView tvEtat;
    LinearLayout resultats;

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
            if (t != null) etLien.setText(t.toString().trim());
        }
    }

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
        resultats.removeAllViews();
        tvEtat.setText("Analyse du lien…");
        new Thread(() -> {
            String sd = null, hd = null;
            try {
                String html = lirePage(adresse);
                sd = chercher(html, CLES_SD);
                hd = chercher(html, CLES_HD);
            } catch (Exception ignored) {}
            final String fSd = sd, fHd = hd;
            runOnUiThread(() -> afficher(fSd, fHd));
        }).start();
    }

    static String lirePage(String adresse) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8");
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        if (c.getResponseCode() >= 400) throw new Exception("http " + c.getResponseCode());
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0 && sb.length() < 4000000) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
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

    void afficher(String sd, String hd) {
        resultats.removeAllViews();
        if (sd == null && hd == null) {
            tvEtat.setText("⚠ Aucun lien de téléchargement trouvé. La vidéo est peut-être privée, "
                    + "Facebook demande une connexion, ou la page a changé.");
            return;
        }
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
    }

    void telecharger(String url, String qualite) {
        try {
            String nom = "facebook_" + qualite + "_" + System.currentTimeMillis() + ".mp4";
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.addRequestHeader("User-Agent", UA);
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
}
