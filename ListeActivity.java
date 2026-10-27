package bj.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Lecteur hors ligne : mode "fichiers" (téléphone) ou "reseau" (box / récepteur en Wi-Fi local). */
public class ListeActivity extends Activity {

    static class Entree {
        String titre, uri;
    }

    final List<Entree> entrees = new ArrayList<>();
    final List<View> cachables = new ArrayList<>();
    String mode;
    boolean reseau, plein = false;
    int courant = -1, hauteurVideo;
    String nomActuel = "";

    SharedPreferences prefs;
    ExoPlayer player;
    PlayerView vue;
    LinearLayout root;
    ListView liste;
    ArrayAdapter<String> adapter;
    TextView tvInfo, tvEtat;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        reseau = "reseau".equals(getIntent().getStringExtra("mode"));
        mode = reseau ? "reseau" : "fichiers";
        prefs = getSharedPreferences("locales", MODE_PRIVATE);
        hauteurVideo = Ui.dp(this, 220);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 14);
        root.setPadding(p, p, p, p);

        ajouter(Ui.texte(this, reseau ? "🏠 Ma box / réseau local" : "🎞 Mes vidéos & audios",
                22, Ui.TXT, true), 0);

        tvInfo = Ui.texte(this, reseau
                ? "Ajoutez le lien d'un flux de votre box (ex. http://192.168.1.20:8001/…) ou importez "
                  + "sa liste M3U. Fonctionne sans internet, avec le Wi-Fi local."
                : "Ajoutez des fichiers du téléphone. Les vidéos téléchargées depuis « Sans internet » "
                  + "apparaissent ici toutes seules.", 13, Ui.TXT2, false);
        ajouter(tvInfo, 6);

        LinearLayout boutons = new LinearLayout(this);
        if (reseau) {
            Button b1 = Ui.bouton(this, "➕ Une chaîne", Ui.ACCENT);
            Button b2 = Ui.bouton(this, "📥 Liste M3U", Ui.VIOLET);
            b1.setOnClickListener(v -> dialogChaine());
            b2.setOnClickListener(v -> dialogListe());
            boutons.addView(b1, Ui.lp(this, 0, -2, 1f, 0, 0));
            boutons.addView(b2, Ui.lp(this, 0, -2, 1f, 0, 8));
        } else {
            Button b1 = Ui.bouton(this, "➕ Ajouter des fichiers", Ui.ACCENT);
            b1.setOnClickListener(v -> choisirFichiers());
            boutons.addView(b1, Ui.lp(this, 0, -2, 1f, 0, 0));
        }
        ajouter(boutons, 8);

        vue = new PlayerView(this);
        vue.setUseController(true);
        vue.setBackgroundColor(0xFF000000);
        root.addView(vue, Ui.lp(this, -1, hauteurVideo, 0f, 8, 0));

        tvEtat = Ui.texte(this, "Choisissez un élément", 16, Ui.TXT, true);
        tvEtat.setGravity(Gravity.CENTER);
        ajouter(tvEtat, 8);

        LinearLayout commandes = new LinearLayout(this);
        Button prec = Ui.bouton(this, "◀ Précédent", Ui.VIOLET);
        Button pleinEcran = Ui.bouton(this, "⛶ Plein écran", Ui.ACCENT);
        Button suiv = Ui.bouton(this, "Suivant ▶", Ui.VIOLET);
        prec.setOnClickListener(v -> deplacer(-1));
        suiv.setOnClickListener(v -> deplacer(1));
        pleinEcran.setOnClickListener(v -> basculerPleinEcran());
        commandes.addView(prec, Ui.lp(this, 0, -2, 1f, 0, 0));
        commandes.addView(pleinEcran, Ui.lp(this, 0, -2, 1.3f, 0, 8));
        commandes.addView(suiv, Ui.lp(this, 0, -2, 1f, 0, 8));
        ajouter(commandes, 8);

        liste = new ListView(this);
        liste.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        liste.setDivider(new ColorDrawable(0x22FFFFFF));
        liste.setDividerHeight(1);
        adapter = Ui.adapterListe(this);
        liste.setAdapter(adapter);
        liste.setOnItemClickListener((a, v, pos, id) -> jouer(pos));
        liste.setOnItemLongClickListener((a, v, pos, id) -> { retirer(pos); return true; });
        root.addView(liste, Ui.lp(this, -1, 0, 1f, 8, 0));
        cachables.add(liste);

        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        vue.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                tvEtat.setText(nomActuel + "\n⚠ Lecture impossible");
            }
            @Override
            public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) tvEtat.setText(nomActuel + "\n▶ En lecture");
                else if (s == Player.STATE_BUFFERING) tvEtat.setText(nomActuel + "\nChargement…");
            }
        });

        charger();
        rafraichir();
    }

    void ajouter(View v, int hautDp) {
        root.addView(v, Ui.lp(this, -1, -2, 0f, hautDp, 0));
        cachables.add(v);
    }

    // ---------- Liste enregistrée ----------

    void charger() {
        entrees.clear();
        String s = prefs.getString(mode, "");
        for (String l : s.split("\n")) {
            String[] t = l.split("\t");
            if (t.length >= 2) ajouterEntree(t[0], t[1]);
        }
        if (!reseau) {   // vidéos téléchargées d'avance
            File dossier = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            File[] fichiers = dossier == null ? null : dossier.listFiles();
            if (fichiers != null)
                for (File f : fichiers)
                    if (f.isFile()) ajouterEntree(f.getName(), Uri.fromFile(f).toString());
        }
    }

    void sauver() {
        StringBuilder sb = new StringBuilder();
        for (Entree e : entrees)
            sb.append(e.titre.replace('\t', ' ').replace('\n', ' ')).append('\t').append(e.uri).append('\n');
        prefs.edit().putString(mode, sb.toString()).apply();
    }

    boolean ajouterEntree(String titre, String uri) {
        for (Entree e : entrees) if (e.uri.equals(uri)) return false;
        Entree e = new Entree();
        e.titre = titre;
        e.uri = uri;
        entrees.add(e);
        return true;
    }

    void rafraichir() {
        adapter.clear();
        for (Entree e : entrees) adapter.add(e.titre);
        courant = -1;
        if (entrees.isEmpty()) tvEtat.setText(reseau ? "Aucune chaîne ajoutée" : "Aucun fichier ajouté");
    }

    void retirer(int pos) {
        if (pos < 0 || pos >= entrees.size()) return;
        Entree e = entrees.get(pos);
        new AlertDialog.Builder(this)
                .setTitle("Retirer de la liste ?")
                .setMessage(e.titre + (e.uri.startsWith("file:")
                        ? "\n\nLe fichier téléchargé sera aussi supprimé du téléphone." : ""))
                .setPositiveButton("Retirer", (d, w) -> {
                    if (e.uri.startsWith("file:")) {
                        String chemin = Uri.parse(e.uri).getPath();
                        if (chemin != null) new File(chemin).delete();
                    }
                    entrees.remove(e);
                    sauver();
                    rafraichir();
                })
                .setNegativeButton("Annuler", null).show();
    }

    // ---------- Fichiers du téléphone ----------

    void choisirFichiers() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, 42);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 42 || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        int n = 0;
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            if (ajouterEntree(nomFichier(u), u.toString())) n++;
        }
        sauver();
        rafraichir();
        Toast.makeText(this, n + " fichier(s) ajouté(s)", Toast.LENGTH_SHORT).show();
    }

    String nomFichier(Uri u) {
        String nom = null;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) nom = c.getString(ix);
            }
        } catch (Exception ignored) {}
        if (nom == null || nom.isEmpty()) nom = u.getLastPathSegment();
        return nom == null ? "Fichier" : nom;
    }

    // ---------- Box / réseau local ----------

    void dialogChaine() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int m = Ui.dp(this, 20);
        l.setPadding(m, m / 2, m, 0);
        EditText nom = new EditText(this);
        nom.setHint("Nom (ex. Canal 1)");
        EditText lien = new EditText(this);
        lien.setHint("Lien (http://192.168.1.20:8001/…)");
        lien.setSingleLine(true);
        l.addView(nom);
        l.addView(lien);
        new AlertDialog.Builder(this)
                .setTitle("Ajouter une chaîne")
                .setView(l)
                .setPositiveButton("Ajouter", (d, w) -> {
                    String u = lien.getText().toString().trim();
                    if (u.isEmpty()) return;
                    String n = nom.getText().toString().trim();
                    ajouterEntree(n.isEmpty() ? u : n, u);
                    sauver();
                    rafraichir();
                })
                .setNegativeButton("Annuler", null).show();
    }

    void dialogListe() {
        EditText lien = new EditText(this);
        lien.setHint("Lien de la liste M3U de la box");
        lien.setSingleLine(true);
        LinearLayout l = new LinearLayout(this);
        int m = Ui.dp(this, 20);
        l.setPadding(m, m / 2, m, 0);
        l.addView(lien, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Importer une liste M3U")
                .setView(l)
                .setPositiveButton("Importer", (d, w) -> importerM3u(lien.getText().toString().trim()))
                .setNegativeButton("Annuler", null).show();
    }

    void importerM3u(String adresse) {
        if (adresse.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                r.close();
                String texte = sb.toString();
                List<String[]> trouves = new ArrayList<>();
                if (texte.contains("#EXT-X-")) {            // c'est un flux unique, pas une liste
                    trouves.add(new String[]{adresse, adresse});
                } else {
                    String nom = null;
                    for (String ligne : texte.split("\\r?\\n")) {
                        ligne = ligne.trim();
                        if (ligne.startsWith("#EXTINF")) {
                            int v = ligne.lastIndexOf(',');
                            nom = v >= 0 ? ligne.substring(v + 1).trim() : null;
                        } else if (!ligne.isEmpty() && !ligne.startsWith("#")) {
                            trouves.add(new String[]{nom == null || nom.isEmpty() ? ligne : nom, ligne});
                            nom = null;
                        }
                    }
                }
                runOnUiThread(() -> {
                    int n = 0;
                    for (String[] t : trouves) if (ajouterEntree(t[0], t[1])) n++;
                    sauver();
                    rafraichir();
                    Toast.makeText(this, n + " chaîne(s) ajoutée(s)", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Impossible de lire la liste (box allumée et sur le même Wi-Fi ?)",
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---------- Lecture ----------

    void jouer(int i) {
        if (i < 0 || i >= entrees.size()) return;
        courant = i;
        liste.setItemChecked(i, true);
        liste.smoothScrollToPosition(i);
        Entree e = entrees.get(i);
        nomActuel = e.titre;
        tvEtat.setText(nomActuel + "\nConnexion…");
        if (plein) Toast.makeText(this, e.titre, Toast.LENGTH_SHORT).show();
        player.setMediaItem(MediaItem.fromUri(Uri.parse(e.uri)));
        player.prepare();
        player.play();
    }

    void deplacer(int sens) {
        if (entrees.isEmpty()) return;
        int n = entrees.size();
        int i = courant < 0 ? (sens > 0 ? 0 : n - 1) : (courant + sens + n) % n;
        jouer(i);
    }

    void basculerPleinEcran() {
        plein = !plein;
        int vis = plein ? View.GONE : View.VISIBLE;
        for (View v : cachables) v.setVisibility(vis);
        vue.setLayoutParams(plein ? new LinearLayout.LayoutParams(-1, -1)
                                  : Ui.lp(this, -1, hauteurVideo, 0f, 8, 0));
        int p = plein ? 0 : Ui.dp(this, 14);
        root.setPadding(p, p, p, p);
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
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
