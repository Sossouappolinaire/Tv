package bj.radio;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RadioActivity extends Activity {

    // Villes du Bénin (ajoutées à la liste quand le pays est le Bénin) : nom, lat, lon
    static final String[][] VILLES_BJ = {
        {"Cotonou","6.3654","2.4183"},{"Porto-Novo","6.4969","2.6289"},
        {"Abomey-Calavi","6.4485","2.3557"},{"Parakou","9.3372","2.6303"},
        {"Djougou","9.7085","1.6660"},{"Bohicon","7.1783","2.0667"},
        {"Abomey","7.1829","1.9912"},{"Natitingou","10.3042","1.3796"},
        {"Ouidah","6.3630","2.0850"},{"Lokossa","6.6386","1.7167"},
        {"Kandi","11.1342","2.9386"},{"Malanville","11.8683","3.3860"},
        {"Savalou","7.9283","1.9759"},{"Dassa-Zoumè","7.7500","2.1833"},
        {"Pobè","6.9833","2.6667"},{"Sakété","6.7333","2.6583"},
        {"Allada","6.6650","2.1514"},{"Comè","6.4067","1.8833"},
        {"Savè","8.0333","2.4833"},{"Tchaourou","8.8833","2.6000"}
    };
    static final String[] HOTES = {"de1", "nl1", "at1"};
    static final String TOUTES = "Toutes les villes / régions";

    final List<String[]> pays = new ArrayList<>();      // {libellé, code}
    final List<String> villes = new ArrayList<>();
    final List<Radio> toutes = new ArrayList<>();
    final List<Radio> affichees = new ArrayList<>();
    ArrayAdapter<String> adapter;
    ListView liste;
    Spinner spPays, spVille;
    EditText etRecherche;
    TextView tvEtat, tvInfo;
    MediaPlayer mp;
    int courant = -1;
    int generation = 0;
    boolean modeMonde = false;
    String villeEnAttente = null;

    static class Radio {
        String nom, url, texte, pays;
        double freq = -1;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView titre = new TextView(this);
        titre.setText("📻 Radios du monde");
        titre.setTextSize(24);
        titre.setTypeface(null, Typeface.BOLD);
        root.addView(titre);

        spPays = new Spinner(this);
        root.addView(spPays);

        spVille = new Spinner(this);
        spVille.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { filtrer(); }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        root.addView(spVille);

        Button bPos = new Button(this);
        bPos.setText("📍 Utiliser ma position");
        bPos.setOnClickListener(v -> localiser());
        root.addView(bPos);

        LinearLayout ligne = new LinearLayout(this);
        etRecherche = new EditText(this);
        etRecherche.setHint("Nom de radio…");
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
        root.addView(ligne);

        tvInfo = new TextView(this);
        tvInfo.setText("Chargement…");
        root.addView(tvInfo);

        tvEtat = new TextView(this);
        tvEtat.setTextSize(20);
        tvEtat.setGravity(Gravity.CENTER);
        tvEtat.setPadding(0, p / 2, 0, p / 2);
        tvEtat.setText("Choisissez une radio");
        root.addView(tvEtat);

        LinearLayout barre = new LinearLayout(this);
        barre.setGravity(Gravity.CENTER);
        Button prec = new Button(this); prec.setText("◀ Fréq −");
        Button stop = new Button(this); stop.setText("■ Stop");
        Button suiv = new Button(this); suiv.setText("Fréq + ▶");
        prec.setOnClickListener(v -> deplacer(-1));
        suiv.setOnClickListener(v -> deplacer(1));
        stop.setOnClickListener(v -> arreter());
        barre.addView(prec); barre.addView(stop); barre.addView(suiv);
        root.addView(barre);

        liste = new ListView(this);
        liste.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice);
        liste.setAdapter(adapter);
        liste.setOnItemClickListener((a, v, pos, id) -> jouer(pos));
        root.addView(liste, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        chargerPays();
    }

    // Les boutons de volume font défiler les fréquences
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

    // ---------- Réseau ----------

    String get(String chemin) throws Exception {
        Exception derniere = null;
        for (String h : HOTES) {
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL("https://" + h + ".api.radio-browser.info" + chemin).openConnection();
                c.setRequestProperty("User-Agent", "RadiosMonde/1.0");
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
                r.close();
                return sb.toString();
            } catch (Exception e) {
                derniere = e;
            }
        }
        throw derniere;
    }

    void chargerPays() {
        new Thread(() -> {
            try {
                JSONArray a = new JSONArray(get("/json/countries"));
                List<String[]> tmp = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String code = o.optString("iso_3166_1", "");
                    String nom = o.optString("name", "").trim();
                    int n = o.optInt("stationcount", 0);
                    if (code.length() != 2 || nom.isEmpty() || n <= 0) continue;
                    tmp.add(new String[]{nom + " (" + n + ")", code});
                }
                Collections.sort(tmp, (x, y) -> x[0].compareToIgnoreCase(y[0]));
                runOnUiThread(() -> {
                    pays.clear();
                    pays.addAll(tmp);
                    List<String> noms = new ArrayList<>();
                    for (String[] x : pays) noms.add(x[0]);
                    spPays.setAdapter(new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item, noms));
                    int idx = indexPays("BJ");
                    if (idx >= 0) spPays.setSelection(idx);
                    spPays.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        public void onItemSelected(AdapterView<?> a2, View v, int pos, long id) {
                            if (pos >= 0 && pos < pays.size()) chargerStations(pays.get(pos)[1]);
                        }
                        public void onNothingSelected(AdapterView<?> a2) {}
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvInfo.setText("Erreur réseau : vérifiez votre connexion."));
            }
        }).start();
    }

    void chargerStations(String code) {
        final int gen = ++generation;
        tvInfo.setText("Chargement des radios…");
        new Thread(() -> {
            try {
                List<Radio> tmp = parser(get("/json/stations/bycountrycodeexact/" + code
                        + "?hidebroken=true&limit=3000&order=votes&reverse=true"));
                runOnUiThread(() -> {
                    if (gen != generation) return;
                    modeMonde = false;
                    toutes.clear();
                    toutes.addAll(tmp);
                    construireVilles(code);
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
            Toast.makeText(this, "Tapez un nom de radio", Toast.LENGTH_SHORT).show();
            return;
        }
        final int gen = ++generation;
        tvInfo.setText("Recherche dans le monde…");
        new Thread(() -> {
            try {
                String enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20");
                List<Radio> tmp = parser(get("/json/stations/byname/" + enc
                        + "?hidebroken=true&limit=300&order=votes&reverse=true"));
                runOnUiThread(() -> {
                    if (gen != generation) return;
                    modeMonde = true;
                    toutes.clear();
                    toutes.addAll(tmp);
                    villes.clear();
                    villes.add(TOUTES);
                    spVille.setAdapter(new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item, villes));
                    filtrer();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (gen == generation) tvInfo.setText("Erreur réseau : vérifiez votre connexion.");
                });
            }
        }).start();
    }

    List<Radio> parser(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Radio> tmp = new ArrayList<>();
        Pattern pf = Pattern.compile("(\\d{2,3})[.,](\\d{1,2})");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Radio x = new Radio();
            x.nom = o.optString("name", "").trim();
            x.url = o.optString("url_resolved", "");
            if (x.url.isEmpty()) x.url = o.optString("url", "");
            if (x.nom.isEmpty() || x.url.isEmpty()) continue;
            x.pays = o.optString("countrycode", "");
            String etat = o.optString("state", "").trim();
            x.texte = norm(x.nom + " " + etat + " " + o.optString("tags", ""));
            x.nom = x.nom + (etat.isEmpty() ? "" : "  (" + etat + ")");
            Matcher m = pf.matcher(o.optString("name", ""));
            if (m.find()) {
                double f = Double.parseDouble(m.group(1) + "." + m.group(2));
                if (f >= 87.5 && f <= 108.0) x.freq = f;
            }
            tmp.add(x);
        }
        Collections.sort(tmp, (a, b2) -> {
            if (a.freq >= 0 && b2.freq >= 0) return Double.compare(a.freq, b2.freq);
            if (a.freq >= 0) return -1;
            if (b2.freq >= 0) return 1;
            return a.nom.compareToIgnoreCase(b2.nom);
        });
        return tmp;
    }

    // ---------- Filtrage ----------

    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace("-", " ");
    }

    int indexPays(String code) {
        for (int i = 0; i < pays.size(); i++) if (pays.get(i)[1].equalsIgnoreCase(code)) return i;
        return -1;
    }

    void construireVilles(String code) {
        Map<String, String> m = new TreeMap<>();
        for (Radio r : toutes) {
            int d = r.nom.lastIndexOf("  (");
            if (d >= 0 && r.nom.endsWith(")")) {
                String etat = r.nom.substring(d + 3, r.nom.length() - 1).trim();
                if (!etat.isEmpty()) m.put(norm(etat), etat);
            }
        }
        if (code.equalsIgnoreCase("BJ"))
            for (String[] v : VILLES_BJ) m.put(norm(v[0]), v[0]);
        villes.clear();
        villes.add(TOUTES);
        villes.addAll(m.values());
        spVille.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, villes));
        appliquerVilleEnAttente();
        filtrer();
    }

    void appliquerVilleEnAttente() {
        if (villeEnAttente == null) return;
        String cle = norm(villeEnAttente);
        int trouve = -1;
        for (int i = 1; i < villes.size(); i++) {
            String v = norm(villes.get(i));
            if (v.contains(cle) || cle.contains(v)) { trouve = i; break; }
        }
        if (trouve > 0) spVille.setSelection(trouve);
        else Toast.makeText(this, "Aucune radio indexée pour " + villeEnAttente
                + " : affichage du pays entier", Toast.LENGTH_LONG).show();
        villeEnAttente = null;
    }

    void filtrer() {
        affichees.clear();
        int pos = spVille.getSelectedItemPosition();
        String ville = (pos > 0 && pos < villes.size()) ? villes.get(pos) : null;
        String cleVille = ville == null ? null : norm(ville);
        String q = norm(etRecherche.getText().toString().trim());
        for (Radio r : toutes) {
            if (cleVille != null && !r.texte.contains(cleVille)) continue;
            if (!q.isEmpty() && !r.texte.contains(q)) continue;
            affichees.add(r);
        }
        tvInfo.setText(affichees.size() + " radio(s)" + (ville != null ? " · " + ville : "")
                + (modeMonde ? " · recherche mondiale" : ""));
        adapter.clear();
        for (Radio r : affichees)
            adapter.add((modeMonde && !r.pays.isEmpty() ? "[" + r.pays + "] " : "")
                    + (r.freq >= 0 ? "FM " + r.freq + "  ·  " : "") + r.nom);
        courant = -1;
    }

    // ---------- Lecture ----------

    void jouer(int i) {
        if (i < 0 || i >= affichees.size()) return;
        courant = i;
        liste.setItemChecked(i, true);
        liste.smoothScrollToPosition(i);
        Radio r = affichees.get(i);
        String tete = (r.freq >= 0 ? "FM " + r.freq + "\n" : "") + r.nom;
        tvEtat.setText(tete + "\nConnexion…");
        liberer();
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            mp.setDataSource(r.url);
            mp.setOnPreparedListener(m -> {
                m.start();
                tvEtat.setText(tete + "\n▶ En lecture");
            });
            mp.setOnErrorListener((m, w, e) -> {
                tvEtat.setText(tete + "\n⚠ Flux indisponible");
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            tvEtat.setText(tete + "\n⚠ Flux indisponible");
        }
    }

    void deplacer(int sens) {
        if (affichees.isEmpty()) return;
        int n = affichees.size();
        int i = courant < 0 ? (sens > 0 ? 0 : n - 1) : (courant + sens + n) % n;
        jouer(i);
    }

    void arreter() {
        liberer();
        tvEtat.setText("Arrêté");
    }

    void liberer() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) {} mp = null; }
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
        Toast.makeText(this, "Recherche de votre ville…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String code = null, ville = null;
            try {
                if (Geocoder.isPresent()) {
                    List<Address> l = new Geocoder(this, Locale.FRANCE)
                            .getFromLocation(pos.getLatitude(), pos.getLongitude(), 1);
                    if (l != null && !l.isEmpty()) {
                        Address a = l.get(0);
                        code = a.getCountryCode();
                        ville = a.getLocality();
                        if (ville == null) ville = a.getSubAdminArea();
                        if (ville == null) ville = a.getAdminArea();
                    }
                }
            } catch (Exception ignored) {}
            if (code == null) {
                // Plan B hors-ligne : ville béninoise la plus proche (si moins de 400 km)
                int meilleur = -1; float dmin = Float.MAX_VALUE;
                float[] d = new float[1];
                for (int i = 0; i < VILLES_BJ.length; i++) {
                    Location.distanceBetween(pos.getLatitude(), pos.getLongitude(),
                            Double.parseDouble(VILLES_BJ[i][1]), Double.parseDouble(VILLES_BJ[i][2]), d);
                    if (d[0] < dmin) { dmin = d[0]; meilleur = i; }
                }
                if (meilleur >= 0 && dmin < 400000f) { code = "BJ"; ville = VILLES_BJ[meilleur][0]; }
            }
            final String c = code, v = ville;
            runOnUiThread(() -> {
                if (c == null) {
                    Toast.makeText(this, "Impossible de déterminer votre ville", Toast.LENGTH_LONG).show();
                } else {
                    selectionnerPays(c, v);
                }
            });
        }).start();
    }

    void selectionnerPays(String code, String ville) {
        int idx = indexPays(code);
        if (idx < 0) {
            Toast.makeText(this, "Aucune radio indexée pour ce pays", Toast.LENGTH_LONG).show();
            return;
        }
        villeEnAttente = ville;
        if (!modeMonde && idx == spPays.getSelectedItemPosition()) {
            appliquerVilleEnAttente();
            filtrer();
        } else if (idx == spPays.getSelectedItemPosition()) {
            chargerStations(code);   // sortir du mode recherche mondiale
        } else {
            spPays.setSelection(idx);
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        if (rc == 1 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) localiser();
    }

    @Override
    protected void onDestroy() {
        liberer();
        super.onDestroy();
    }
}
