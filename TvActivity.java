package bj.radio;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Collator;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TvActivity extends Activity {

    static final String BASE = "https://iptv-org.github.io/iptv/";

    static class Chaine {
        String nom, url, texte;
    }

    final List<String[]> pays = new ArrayList<>();   // {nom, code}
    final List<Chaine> toutes = new ArrayList<>();
    final List<Chaine> affichees = new ArrayList<>();
    final List<View> cachables = new ArrayList<>();
    List<Chaine> cacheMonde = null;
    boolean modeMonde = false, plein = false;
    int courant = -1, generation = 0, hauteurVideo;
    String nomActuel = "";

    ExoPlayer player;
    PlayerView vue;
    LinearLayout root;
    ListView liste;
    ArrayAdapter<String> adapter;
    Spinner spPays;
    EditText etRecherche;
    TextView tvInfo, tvEtat;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        int p = (int) (16 * d);
        hauteurVideo = (int) (220 * d);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView titre = new TextView(this);
        titre.setText("📺 Télévision du monde");
        titre.setTextSize(24);
        titre.setTypeface(null, Typeface.BOLD);
        ajouter(titre, true);

        spPays = new Spinner(this);
        ajouter(spPays, true);

        Button bPos = new Button(this);
        bPos.setText("📍 Utiliser ma position");
        bPos.setOnClickListener(v -> localiser());
        ajouter(bPos, true);

        LinearLayout ligne = new LinearLayout(this);
        etRecherche = new EditText(this);
        etRecherche.setHint("Nom de chaîne…");
        etRecherche.setSingleLine(true);
        etRecherche.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable e) { filtrer(); }
        });
        Button bMonde = new Button(this);
        bMonde.setText("🌍 Monde");
        bMonde.setOnClickListener(v -> chercherMonde());
        ligne.addView(etRecherche, new LinearLayout.LayoutParams(0, -2, 1f));
        ligne.addView(bMonde);
        ajouter(ligne, true);

        tvInfo = new TextView(this);
        tvInfo.setText("Chargement…");
        ajouter(tvInfo, true);

        vue = new PlayerView(this);
        vue.setUseController(true);
        vue.setBackgroundColor(0xFF000000);
        root.addView(vue, new LinearLayout.LayoutParams(-1, hauteurVideo));

        tvEtat = new TextView(this);
        tvEtat.setGravity(Gravity.CENTER);
        tvEtat.setText("Choisissez une chaîne");
        ajouter(tvEtat, true);

        LinearLayout barre = new LinearLayout(this);
        barre.setGravity(Gravity.CENTER);
        Button prec = new Button(this); prec.setText("◀ Chaîne −");
        Button plein_ = new Button(this); plein_.setText("⛶");
        Button suiv = new Button(this); suiv.setText("Chaîne + ▶");
        prec.setOnClickListener(v -> deplacer(-1));
        suiv.setOnClickListener(v -> deplacer(1));
        plein_.setOnClickListener(v -> basculerPleinEcran());
        barre.addView(prec); barre.addView(plein_); barre.addView(suiv);
        ajouter(barre, true);

        liste = new ListView(this);
        liste.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice);
        liste.setAdapter(adapter);
        liste.setOnItemClickListener((a, v, pos, id) -> jouer(pos));
        root.addView(liste, new LinearLayout.LayoutParams(-1, 0, 1f));
        cachables.add(liste);

        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        vue.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                tvEtat.setText(nomActuel + "\n⚠ Flux indisponible");
            }
            @Override
            public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) tvEtat.setText(nomActuel + "\n▶ En lecture");
                else if (s == Player.STATE_BUFFERING) tvEtat.setText(nomActuel + "\nChargement…");
            }
        });

        for (String c : Locale.getISOCountries()) {
            String n = new Locale("", c).getDisplayCountry(Locale.FRENCH);
            if (n != null && !n.isEmpty()) pays.add(new String[]{n, c});
        }
        Collator col = Collator.getInstance(Locale.FRENCH);
        Collections.sort(pays, (x, y) -> col.compare(x[0], y[0]));
        List<String> noms = new ArrayList<>();
        for (String[] x : pays) noms.add(x[0]);
        spPays.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, noms));
        int idx = indexPays("BJ");
        if (idx >= 0) spPays.setSelection(idx);
        spPays.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (pos >= 0 && pos < pays.size()) charger(pays.get(pos)[1]);
            }
            public void onNothingSelected(AdapterView<?> a) {}
        });
    }

    void ajouter(View v, boolean cachable) {
        root.addView(v);
        if (cachable) cachables.add(v);
    }

    // Les boutons de volume changent de chaîne
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP) { deplacer(1); return true; }
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { deplacer(-1); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        return super.onKeyUp(code, e);
    }

    @Override
    public void onBackPressed() {
        if (plein) basculerPleinEcran();
        else super.onBackPressed();
    }

    // ---------- Données ----------

    String get(String adresse) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
        c.setRequestProperty("User-Agent", "RadioTV/1.0");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (c.getResponseCode() == 404) return null;
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        r.close();
        return sb.toString();
    }

    List<Chaine> parser(String m3u) {
        List<Chaine> res = new ArrayList<>();
        Pattern pg = Pattern.compile("group-title=\"([^\"]*)\"");
        String nom = null, groupe = "";
        for (String l : m3u.split("\\r?\\n")) {
            l = l.trim();
            if (l.startsWith("#EXTINF")) {
                int v = l.lastIndexOf(',');
                nom = v >= 0 ? l.substring(v + 1).trim() : "Chaîne";
                Matcher m = pg.matcher(l);
                groupe = m.find() ? m.group(1).trim() : "";
            } else if (!l.isEmpty() && !l.startsWith("#") && nom != null) {
                Chaine c = new Chaine();
                c.nom = nom.isEmpty() ? "Chaîne" : nom;
                c.url = l;
                c.texte = norm(c.nom);
                if (!groupe.isEmpty()) c.nom = c.nom + "  (" + groupe.split(";")[0] + ")";
                res.add(c);
                nom = null;
            }
        }
        return res;
    }

    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    int indexPays(String code) {
        for (int i = 0; i < pays.size(); i++) if (pays.get(i)[1].equalsIgnoreCase(code)) return i;
        return -1;
    }

    void charger(String code) {
        final int gen = ++generation;
        tvInfo.setText("Chargement des chaînes…");
        new Thread(() -> {
            try {
                String t = get(BASE + "countries/" + code.toLowerCase(Locale.ROOT) + ".m3u");
                List<Chaine> tmp = t == null ? new ArrayList<>() : parser(t);
                runOnUiThread(() -> {
                    if (gen != generation) return;
                    modeMonde = false;
                    toutes.clear();
                    toutes.addAll(tmp);
                    filtrer();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (gen == generation) tvInfo.setText("Erreur réseau : vérifiez votre connexion.");
                });
            }
        }).start();
    }

    void chercherMonde() {
        String q = etRecherche.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "Tapez un nom de chaîne", Toast.LENGTH_SHORT).show();
            return;
        }
        final int gen = ++generation;
        final String cle = norm(q);
        tvInfo.setText("Recherche dans le monde (premier téléchargement un peu long)…");
        new Thread(() -> {
            try {
                if (cacheMonde == null) {
                    String t = get(BASE + "index.m3u");
                    cacheMonde = t == null ? new ArrayList<>() : parser(t);
                }
                List<Chaine> tmp = new ArrayList<>();
                for (Chaine c : cacheMonde) {
                    if (c.texte.contains(cle)) tmp.add(c);
                    if (tmp.size() >= 300) break;
                }
                runOnUiThread(() -> {
                    if (gen != generation) return;
                    modeMonde = true;
                    toutes.clear();
                    toutes.addAll(tmp);
                    filtrer();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (gen == generation) tvInfo.setText("Erreur réseau : vérifiez votre connexion.");
                });
            }
        }).start();
    }

    void filtrer() {
        affichees.clear();
        String q = norm(etRecherche.getText().toString().trim());
        for (Chaine c : toutes)
            if (q.isEmpty() || c.texte.contains(q)) affichees.add(c);
        if (toutes.isEmpty())
            tvInfo.setText(modeMonde ? "Aucun résultat"
                    : "Aucune chaîne libre pour ce pays : essayez 🌍 Monde");
        else
            tvInfo.setText(affichees.size() + " chaîne(s)" + (modeMonde ? " · recherche mondiale" : ""));
        adapter.clear();
        for (Chaine c : affichees) adapter.add(c.nom);
        courant = -1;
    }

    // ---------- Lecture ----------

    void jouer(int i) {
        if (i < 0 || i >= affichees.size()) return;
        courant = i;
        liste.setItemChecked(i, true);
        liste.smoothScrollToPosition(i);
        Chaine c = affichees.get(i);
        nomActuel = c.nom;
        tvEtat.setText(nomActuel + "\nConnexion…");
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.prepare();
        player.play();
    }

    void deplacer(int sens) {
        if (affichees.isEmpty()) return;
        int n = affichees.size();
        int i = courant < 0 ? (sens > 0 ? 0 : n - 1) : (courant + sens + n) % n;
        jouer(i);
    }

    void basculerPleinEcran() {
        plein = !plein;
        int vis = plein ? View.GONE : View.VISIBLE;
        for (View v : cachables) v.setVisibility(vis);
        vue.setLayoutParams(plein ? new LinearLayout.LayoutParams(-1, -1)
                                  : new LinearLayout.LayoutParams(-1, hauteurVideo));
        int p = plein ? 0 : (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);
        setRequestedOrientation(plein ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                      : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().getDecorView().setSystemUiVisibility(plein
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : 0);
    }

    // ---------- Position ----------

    void localiser() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        Location best = null;
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            for (String pr : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(pr);
                if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Permission de localisation refusée", Toast.LENGTH_SHORT).show();
            return;
        }
        if (best == null) {
            Toast.makeText(this, "Position introuvable : activez la localisation", Toast.LENGTH_LONG).show();
            return;
        }
        final Location pos = best;
        new Thread(() -> {
            String code = null;
            try {
                if (Geocoder.isPresent()) {
                    List<Address> l = new Geocoder(this, Locale.FRANCE)
                            .getFromLocation(pos.getLatitude(), pos.getLongitude(), 1);
                    if (l != null && !l.isEmpty()) code = l.get(0).getCountryCode();
                }
            } catch (Exception ignored) {}
            final String c = code;
            runOnUiThread(() -> {
                int idx = c == null ? -1 : indexPays(c);
                if (idx < 0) {
                    Toast.makeText(this, "Impossible de déterminer votre pays", Toast.LENGTH_LONG).show();
                } else if (idx == spPays.getSelectedItemPosition()) {
                    charger(c);
                } else {
                    spPays.setSelection(idx);
                }
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        if (rc == 1 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) localiser();
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
