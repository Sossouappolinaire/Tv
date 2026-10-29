package bj.radio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

/**
 * Affiche une page définie dans le fichier de configuration (pages → id) :
 *  - « html » : la page est écrite directement dans le fichier ;
 *  - « zip » : un mini-site complet (index.html, images, scripts) téléchargé depuis la base.
 */
public class PageWebActivity extends Activity {

    private static final String HOTE = "appli.local";

    private WebView web;
    private File racine;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        final String id = getIntent().getStringExtra("id");
        JSONObject cfg = Config.contenu(this);
        JSONObject pages = cfg == null ? null : cfg.optJSONObject("pages");
        final JSONObject pg = pages == null || id == null ? null : pages.optJSONObject(id);
        if (pg == null) {
            Toast.makeText(this, "Page introuvable", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.fond());

        LinearLayout barre = new LinearLayout(this);
        barre.setOrientation(LinearLayout.HORIZONTAL);
        barre.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 10);
        barre.setPadding(p, p, p, p);
        Button retour = Ui.boutonContour(this, "← Retour", Ui.TXT);
        retour.setOnClickListener(v -> finish());
        barre.addView(retour, new LinearLayout.LayoutParams(-2, -2));
        TextView titre = Ui.texte(this, pg.optString("titre", ""), 18, Ui.TXT, true);
        titre.setPadding(Ui.dp(this, 12), 0, 0, 0);
        barre.addView(titre, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(barre, new LinearLayout.LayoutParams(-1, -2));

        web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOTE.equals(u.getHost())) return false;   // pages du mini-site : on reste dans l'appli
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignore) {
                        // aucune appli pour ouvrir le lien
                    }
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return servir(r.getUrl());
            }
        });
        page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);

        final String zip = pg.optString("zip", "").trim();
        if (zip.isEmpty()) {
            web.loadDataWithBaseURL("https://" + HOTE + "/", pg.optString("html", ""), "text/html", "utf-8", null);
            return;
        }
        final String index = pg.optString("index", "index.html").trim();
        if (Paquets.present(this, id)) {
            ouvrirSite(id, index);
            return;
        }
        Toast.makeText(this, "Téléchargement du contenu…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Paquets.synchroniserUn(this, id, zip);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (Paquets.present(this, id)) {
                    ouvrirSite(id, index);
                } else {
                    Toast.makeText(this, "Contenu indisponible : vérifiez internet.", Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }).start();
    }

    private void ouvrirSite(String id, String index) {
        racine = Paquets.racine(Paquets.dossier(this, id), index);
        String chemin = index.startsWith("/") ? index.substring(1) : index;
        web.loadUrl("https://" + HOTE + "/" + chemin);
    }

    /** Sert les fichiers du zip décompressé pour https://appli.local/… (jamais en dehors du dossier du zip). */
    private WebResourceResponse servir(Uri u) {
        if (racine == null || !HOTE.equals(u.getHost())) return null;
        try {
            String rel = u.getPath() == null ? "" : u.getPath();
            File f = new File(racine, rel);
            String base = racine.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(base) || !f.isFile()) return null;
            String nom = f.getName().toLowerCase(Locale.ROOT);
            String ext = nom.contains(".") ? nom.substring(nom.lastIndexOf('.') + 1) : "";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime == null) mime = ext.equals("js") || ext.equals("mjs") ? "text/javascript" : ext.equals("json") ? "application/json" : "application/octet-stream";
            return new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") ? "utf-8" : null,
                    new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
