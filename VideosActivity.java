package bj.radio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * « Mes vidéos » : toutes les vidéos du téléphone (MediaStore), rangées par dossier
 * ou triées (récentes, nom, taille, durée). Un tap ouvre le lecteur avec traduction.
 */
public class VideosActivity extends Activity {

    static class Video {
        long id, duree, taille, date;
        String nom, dossier;
        Uri uri;
    }

    private static final String[] TRIS = {
        "📁 Par dossier", "🕒 Plus récentes", "🔤 Nom A → Z", "💾 Plus grosses", "⏱ Plus longues"
    };

    private final List<Video> tous = new ArrayList<>();
    private final List<Object> affichage = new ArrayList<>();   // String = titre de dossier, Video = ligne
    private final ExecutorService vignettes = Executors.newFixedThreadPool(2);
    private final LruCache<Long, Bitmap> cacheVignettes = new LruCache<>(120);

    private BaseAdapter adapter;
    private ListView liste;
    private TextView etat;
    private Button btnReglages;
    private EditText recherche;
    private Spinner tri;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.fond());
        int p = Ui.dp(this, 14);
        root.setPadding(p, p, p, p);

        Ui.ajouter(root, Ui.texte(this, "🎬 Mes vidéos", 24, Ui.TXT, true), 0);
        etat = Ui.texte(this, "Chargement…", 13, Ui.TXT2, false);
        Ui.ajouter(root, etat, 4);

        recherche = Ui.champ(this, "🔎 Rechercher une vidéo");
        recherche.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) { appliquer(); }
        });
        Ui.ajouter(root, recherche, 8);

        tri = new Spinner(this);
        Ui.styleSpinner(tri);
        tri.setAdapter(Ui.adapterSpinner(this, Arrays.asList(TRIS)));
        tri.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { appliquer(); }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        Ui.ajouter(root, tri, 8);

        btnReglages = Ui.bouton(this, "⚙ Autoriser l'accès aux vidéos", Ui.ACCENT);
        btnReglages.setVisibility(View.GONE);
        btnReglages.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        Ui.ajouter(root, btnReglages, 8);

        liste = new ListView(this);
        liste.setDivider(new ColorDrawable(0x00000000));
        liste.setDividerHeight(Ui.dp(this, 6));
        liste.setSelector(android.R.color.transparent);
        adapter = new LigneAdapter();
        liste.setAdapter(adapter);
        liste.setOnItemClickListener((a, v, pos, id) -> {
            Object o = affichage.get(pos);
            if (o instanceof Video) ouvrir((Video) o);
        });
        root.addView(liste, Ui.lp(this, -1, 0, 1f, 8, 0));

        setContentView(root);

        if (autorise()) charger();
        else requestPermissions(new String[]{permission()}, 7);
    }

    // ---------- Autorisation ----------

    private String permission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO
                                           : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean autorise() {
        return checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 7) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            btnReglages.setVisibility(View.GONE);
            charger();
        } else {
            etat.setText("L'accès aux vidéos est refusé. Autorisez-le dans les paramètres de l'application.");
            btnReglages.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // retour des paramètres Android : si l'accès vient d'être donné, on charge
        if (tous.isEmpty() && autorise() && btnReglages.getVisibility() == View.VISIBLE) {
            btnReglages.setVisibility(View.GONE);
            charger();
        }
    }

    // ---------- Chargement des vidéos ----------

    private void charger() {
        etat.setText("Recherche des vidéos…");
        new Thread(() -> {
            List<Video> res = new ArrayList<>();
            Uri collection = Build.VERSION.SDK_INT >= 29
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] colonnes = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            };
            try (Cursor c = getContentResolver().query(collection, colonnes, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int iNom = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                    int iDuree = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                    int iTaille = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                    int iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                    int iDossier = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                    while (c.moveToNext()) {
                        Video v = new Video();
                        v.id = c.getLong(iId);
                        v.nom = c.getString(iNom);
                        v.duree = c.getLong(iDuree);
                        v.taille = c.getLong(iTaille);
                        v.date = c.getLong(iDate);
                        v.dossier = c.getString(iDossier);
                        if (v.nom == null) v.nom = "Vidéo " + v.id;
                        if (v.dossier == null || v.dossier.isEmpty()) v.dossier = "Autres";
                        v.uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id);
                        res.add(v);
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> etat.setText("Impossible de lire les vidéos du téléphone."));
                return;
            }
            runOnUiThread(() -> {
                tous.clear();
                tous.addAll(res);
                appliquer();
            });
        }).start();
    }

    // ---------- Rangement ----------

    private void appliquer() {
        String filtre = recherche.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Video> v = new ArrayList<>();
        for (Video x : tous)
            if (filtre.isEmpty() || x.nom.toLowerCase(Locale.ROOT).contains(filtre)
                    || x.dossier.toLowerCase(Locale.ROOT).contains(filtre)) v.add(x);

        affichage.clear();
        int mode = tri.getSelectedItemPosition();
        if (mode <= 0) {                                  // par dossier
            TreeMap<String, List<Video>> dossiers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Video x : v) {
                List<Video> l = dossiers.get(x.dossier);
                if (l == null) { l = new ArrayList<>(); dossiers.put(x.dossier, l); }
                l.add(x);
            }
            for (java.util.Map.Entry<String, List<Video>> e : dossiers.entrySet()) {
                affichage.add("📁 " + e.getKey() + "  (" + e.getValue().size() + ")");
                affichage.addAll(e.getValue());           // déjà du plus récent au plus ancien
            }
        } else {
            Comparator<Video> cmp;
            if (mode == 1) cmp = (a, c) -> Long.compare(c.date, a.date);
            else if (mode == 2) cmp = (a, c) -> a.nom.compareToIgnoreCase(c.nom);
            else if (mode == 3) cmp = (a, c) -> Long.compare(c.taille, a.taille);
            else cmp = (a, c) -> Long.compare(c.duree, a.duree);
            Collections.sort(v, cmp);
            affichage.addAll(v);
        }
        adapter.notifyDataSetChanged();
        etat.setText(tous.isEmpty() ? "Aucune vidéo trouvée sur ce téléphone."
                : v.size() + " vidéo(s)" + (filtre.isEmpty() ? "" : " trouvée(s)"));
    }

    /** Étape 1 : que faut-il traduire ? */
    private void ouvrir(Video v) {
        final String[] choix = {
            "🔊🔤  Audio + sous-titres", "🔊  Audio uniquement", "🔤  Sous-titres uniquement", "▶  Lire sans traduction"
        };
        final String[] modes = {"tout", "audio", "soustitres", "aucun"};
        new AlertDialog.Builder(this)
                .setTitle("Traduire « " + v.nom + " » ?")
                .setItems(choix, (d, w) -> {
                    if (w == 3) lancer(v, "aucun", "auto", "fr");
                    else choisirLangues(v, modes[w]);
                })
                .show();
    }

    /** Étape 2 : de quelle langue vers quelle langue ? */
    private void choisirLangues(Video v, String mode) {
        SharedPreferences prefs = getSharedPreferences("traduction", MODE_PRIVATE);
        final List<String> nomsSource = new ArrayList<>(), codesSource = new ArrayList<>();
        final List<String> nomsCible = new ArrayList<>(), codesCible = new ArrayList<>();
        nomsSource.add("Détecter automatiquement");
        codesSource.add("auto");
        List<String> vus = new ArrayList<>();
        for (String l : LecteurVideoActivity.LANGUES) {
            String[] t = l.split("\\|");
            if (vus.contains(t[1])) continue;
            vus.add(t[1]);
            nomsSource.add(t[0]); codesSource.add(t[1]);
            nomsCible.add(t[0]); codesCible.add(t[1]);
        }

        int m = Ui.dp(this, 18);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(m, m / 2, m, 0);

        l.addView(etiquette("Langue de la vidéo"));
        Spinner spSource = new Spinner(this);
        Ui.styleSpinner(spSource);
        spSource.setAdapter(Ui.adapterSpinner(this, nomsSource));
        int is = codesSource.indexOf(prefs.getString("source", "auto"));
        spSource.setSelection(is < 0 ? 0 : is);
        l.addView(spSource);

        l.addView(etiquette("Traduire vers"));
        Spinner spCible = new Spinner(this);
        Ui.styleSpinner(spCible);
        spCible.setAdapter(Ui.adapterSpinner(this, nomsCible));
        int ic = codesCible.indexOf(prefs.getString("cible", "fr"));
        spCible.setSelection(ic < 0 ? 0 : ic);
        l.addView(spCible);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(l);

        new AlertDialog.Builder(this)
                .setTitle("🌐 Langues")
                .setView(sv)
                .setPositiveButton("▶ Lancer", (d, w) -> lancer(v, mode,
                        codesSource.get(spSource.getSelectedItemPosition()),
                        codesCible.get(spCible.getSelectedItemPosition())))
                .setNegativeButton("Retour", (d, w) -> ouvrir(v))
                .show();
    }

    private TextView etiquette(String t) {
        TextView v = Ui.texte(this, t, 13, 0xFF444444, true);
        v.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        return v;
    }

    private void lancer(Video v, String mode, String source, String cible) {
        startActivity(new Intent(this, LecteurVideoActivity.class)
                .putExtra("uri", v.uri.toString())
                .putExtra("titre", v.nom)
                .putExtra("mode", mode)
                .putExtra("source", source)
                .putExtra("cible", cible));
    }

    // ---------- Affichage des lignes ----------

    private static String duree(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                     : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private void vignette(ImageView iv, Video v) {
        iv.setTag(v.id);
        Bitmap b = cacheVignettes.get(v.id);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageDrawable(null);
        if (Build.VERSION.SDK_INT < 29) return;           // sous Android 10 : simple case grise
        vignettes.execute(() -> {
            try {
                Bitmap bm = getContentResolver().loadThumbnail(v.uri, new Size(240, 180), null);
                cacheVignettes.put(v.id, bm);
                runOnUiThread(() -> { if (Long.valueOf(v.id).equals(iv.getTag())) iv.setImageBitmap(bm); });
            } catch (Exception ignored) {}
        });
    }

    private class LigneAdapter extends BaseAdapter {
        @Override public int getCount() { return affichage.size(); }
        @Override public Object getItem(int i) { return affichage.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return affichage.get(i) instanceof String ? 0 : 1; }
        @Override public boolean isEnabled(int i) { return affichage.get(i) instanceof Video; }

        @Override
        public View getView(int i, View reuse, ViewGroup parent) {
            Object o = affichage.get(i);
            Context c = VideosActivity.this;
            if (o instanceof String) {
                TextView t = (TextView) reuse;
                if (t == null) {
                    t = Ui.texte(c, "", 15, Ui.JAUNE, true);
                    t.setPadding(Ui.dp(c, 4), Ui.dp(c, 14), 0, Ui.dp(c, 4));
                }
                t.setText((String) o);
                return t;
            }
            Video v = (Video) o;
            LinearLayout ligne;
            ImageView img;
            TextView titre, info;
            if (reuse == null) {
                ligne = new LinearLayout(c);
                ligne.setGravity(Gravity.CENTER_VERTICAL);
                ligne.setBackground(Ui.rond(Ui.CARD, 12, c));
                int q = Ui.dp(c, 8);
                ligne.setPadding(q, q, q, q);
                img = new ImageView(c);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Ui.rond(0xFF30333A, 8, c));
                img.setClipToOutline(true);
                ligne.addView(img, new LinearLayout.LayoutParams(Ui.dp(c, 84), Ui.dp(c, 63)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                titre = Ui.texte(c, "", 14, Ui.TXT, true);
                titre.setMaxLines(2);
                titre.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info = Ui.texte(c, "", 12, Ui.TXT2, false);
                col.addView(titre);
                col.addView(info);
                ligne.addView(col, Ui.lp(c, 0, -2, 1f, 0, 10));
                ligne.setTag(new View[]{img, titre, info});
            } else {
                ligne = (LinearLayout) reuse;
            }
            View[] vues = (View[]) ligne.getTag();
            img = (ImageView) vues[0];
            titre = (TextView) vues[1];
            info = (TextView) vues[2];
            titre.setText(v.nom);
            String detail = duree(v.duree) + "  •  " + Formatter.formatShortFileSize(c, v.taille);
            if (tri.getSelectedItemPosition() > 0) detail += "  •  📁 " + v.dossier;
            info.setText(detail);
            vignette(img, v);
            return ligne;
        }
    }

    @Override
    protected void onDestroy() {
        vignettes.shutdownNow();
        super.onDestroy();
    }
}
