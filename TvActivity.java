package bj.radio;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TvActivity extends Activity {

    static final String BASE = "https://iptv-org.github.io/iptv/";
    static final long TROIS_JOURS = 3L * 24 * 3600 * 1000;

    static class Chaine {
        String nom, url, texte, cc = "";
    }

    final List<String[]> paysTous = new ArrayList<>();     // {nom, code}
    final List<String[]> paysMarche = new ArrayList<>();   // {libellé, code}
    List<String[]> paysCourants = paysTous;
    final List<Chaine> toutes = new ArrayList<>();         // liste d'un pays (non vérifiée)
    final List<Chaine> affichees = new ArrayList<>();
    final List<Chaine> marche = new ArrayList<>();         // chaînes vérifiées
    final List<Chaine> manuelles = new java.util.concurrent.CopyOnWriteArrayList<>();      // chaînes validées en les jouant (gardées même après une nouvelle analyse)
    Chaine enLecture = null;
    final Set<String> urlsMarche = new HashSet<>();
    final List<View> cachables = new ArrayList<>();
    List<Chaine> cacheMonde = null;

    SharedPreferences prefs;
    volatile boolean arret = false, scanEnCours = false;
    boolean modeMarche = true, modeMonde = false, plein = false;
    int courant = -1, generation = 0, hauteurVideo;
    String nomActuel = "";

    ExoPlayer player;
    PlayerView vue;
    LinearLayout root, panneauScan;
    ListView liste;
    ArrayAdapter<String> adapter;
    Spinner spPays;
    EditText etRecherche;
    TextView tvInfo, tvEtat, tvScan;
    ProgressBar progres;
    Button bModeMarche, bModeTous, bScan, bMonde;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.fenetre(this);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        hauteurVideo = Ui.dp(this, 220);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.fond());
        int p = Ui.dp(this, 14);
        root.setPadding(p, p, p, p);

        ajouter(Ui.texte(this, "📺 Télévision", 24, Ui.TXT, true), 0);

        LinearLayout modes = new LinearLayout(this);
        bModeMarche = Ui.bouton(this, "✅ Chaînes qui marchent", Ui.ACCENT);
        bModeTous = Ui.bouton(this, "🌍 Toutes les chaînes", Ui.CARD);
        bModeMarche.setOnClickListener(v -> setMode(true));
        bModeTous.setOnClickListener(v -> setMode(false));
        modes.addView(bModeMarche, Ui.lp(this, 0, -2, 1f, 0, 0));
        modes.addView(bModeTous, Ui.lp(this, 0, -2, 1f, 0, 8));
        ajouter(modes, 8);

        spPays = new Spinner(this);
        Ui.styleSpinner(spPays);
        spPays.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (pos < 0 || pos >= paysCourants.size()) return;
                if (modeMarche) filtrer();
                else charger(paysCourants.get(pos)[1]);
            }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        ajouter(spPays, 8);

        LinearLayout ligne = new LinearLayout(this);
        ligne.setGravity(Gravity.CENTER_VERTICAL);
        etRecherche = Ui.champ(this, "Rechercher une chaîne…");
        etRecherche.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable e) { filtrer(); }
        });
        Button bPos = Ui.bouton(this, "📍", Ui.ACCENT);
        bPos.setOnClickListener(v -> localiser());
        bMonde = Ui.bouton(this, "🌍", Ui.VIOLET);
        bMonde.setOnClickListener(v -> chercherMonde());
        ligne.addView(etRecherche, Ui.lp(this, 0, -2, 1f, 0, 0));
        ligne.addView(bPos, Ui.lp(this, -2, -2, 0f, 0, 8));
        ligne.addView(bMonde, Ui.lp(this, -2, -2, 0f, 0, 8));
        ajouter(ligne, 8);

        panneauScan = new LinearLayout(this);
        panneauScan.setOrientation(LinearLayout.VERTICAL);
        panneauScan.setBackground(Ui.rond(Ui.CARD, 14, this));
        int q = Ui.dp(this, 10);
        panneauScan.setPadding(q, q, q, q);
        tvScan = Ui.texte(this, "", 13, Ui.TXT2, false);
        progres = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progres.setProgressTintList(ColorStateList.valueOf(Ui.JAUNE));
        bScan = Ui.bouton(this, "🔍 Analyser", Ui.VIOLET);
        bScan.setOnClickListener(v -> {
            if (scanEnCours) { arret = true; bScan.setText("Arrêt…"); }
            else lancerScan();
        });
        panneauScan.addView(tvScan);
        panneauScan.addView(progres, Ui.lp(this, -1, -2, 0f, 6, 0));
        panneauScan.addView(bScan, Ui.lp(this, -1, -2, 0f, 6, 0));
        root.addView(panneauScan, Ui.lp(this, -1, -2, 0f, 8, 0));

        tvInfo = Ui.texte(this, "Chargement…", 13, Ui.TXT2, false);
        ajouter(tvInfo, 8);

        vue = new PlayerView(this);
        vue.setUseController(true);
        vue.setBackgroundColor(0xFF000000);
        root.addView(vue, Ui.lp(this, -1, hauteurVideo, 0f, 8, 0));

        tvEtat = Ui.texte(this, "Choisissez une chaîne", 16, Ui.TXT, true);
        tvEtat.setGravity(Gravity.CENTER);
        ajouter(tvEtat, 8);

        LinearLayout commandes = new LinearLayout(this);
        Button prec = Ui.bouton(this, "◀ Chaîne −", Ui.VIOLET);
        Button pleinEcran = Ui.bouton(this, "⛶ Plein écran", Ui.ACCENT);
        Button suiv = Ui.bouton(this, "Chaîne + ▶", Ui.VIOLET);
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
        root.addView(liste, Ui.lp(this, -1, 0, 1f, 8, 0));
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
                if (s == Player.STATE_READY) {
                    tvEtat.setText(nomActuel + "\n▶ En lecture");
                    validerChaine();
                }
                else if (s == Player.STATE_BUFFERING) tvEtat.setText(nomActuel + "\nChargement…");
            }
        });

        for (String c : Locale.getISOCountries()) {
            String n = new Locale("", c).getDisplayCountry(Locale.FRENCH);
            if (n != null && !n.isEmpty()) paysTous.add(new String[]{n, c});
        }
        Collator col = Collator.getInstance(Locale.FRENCH);
        Collections.sort(paysTous, (x, y) -> col.compare(x[0], y[0]));

        chargerMarche();
        setMode(true);

        // Analyse automatique : première fois, reprise, ou liste de plus de 3 jours
        boolean fini = prefs.getBoolean("fini", false);
        long age = System.currentTimeMillis() - prefs.getLong("date", 0);
        if (!fini || age > TROIS_JOURS) {
            lancerScan();
        } else {
            tvScan.setText("✅ Dernière analyse terminée : " + nbMarche() + " chaînes qui marchent");
            progres.setMax(1);
            progres.setProgress(1);
            bScan.setText("🔍 Analyser à nouveau");
        }
    }

    void ajouter(View v, int hautDp) {
        root.addView(v, Ui.lp(this, -1, -2, 0f, hautDp, 0));
        cachables.add(v);
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

    // ---------- Modes ----------

    void setMode(boolean m) {
        modeMarche = m;
        modeMonde = false;
        bModeMarche.setBackground(Ui.rond(m ? Ui.ACCENT : Ui.CARD, 14, this));
        bModeTous.setBackground(Ui.rond(m ? Ui.CARD : Ui.VIOLET, 14, this));
        appliquerVisibilites();
        if (m) {
            majPaysMarche();
            filtrer();
        } else {
            paysCourants = paysTous;
            List<String> noms = new ArrayList<>();
            for (String[] x : paysTous) noms.add(x[0]);
            spPays.setAdapter(Ui.adapterSpinner(this, noms));
            for (int i = 0; i < paysTous.size(); i++)
                if (paysTous.get(i)[1].equals("BJ")) spPays.setSelection(i);
        }
    }

    void appliquerVisibilites() {
        panneauScan.setVisibility(modeMarche ? View.VISIBLE : View.GONE);
        bMonde.setVisibility(modeMarche ? View.GONE : View.VISIBLE);
    }

    void majPaysMarche() {
        String codeChoisi = "";
        int pos = spPays.getSelectedItemPosition();
        if (paysCourants == paysMarche && pos >= 0 && pos < paysMarche.size())
            codeChoisi = paysMarche.get(pos)[1];

        java.util.Map<String, Integer> nb = new java.util.TreeMap<>();
        int total;
        synchronized (marche) {
            total = marche.size();
            for (Chaine c : marche) {
                if (c.cc.isEmpty()) continue;
                Integer n = nb.get(c.cc);
                nb.put(c.cc, n == null ? 1 : n + 1);
            }
        }
        List<String[]> tmp = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : nb.entrySet())
            tmp.add(new String[]{nomPays(e.getKey()) + " (" + e.getValue() + ")", e.getKey()});
        Collator col = Collator.getInstance(Locale.FRENCH);
        Collections.sort(tmp, (x, y) -> col.compare(x[0], y[0]));

        paysMarche.clear();
        paysMarche.add(new String[]{"Tous les pays (" + total + ")", ""});
        paysMarche.addAll(tmp);
        paysCourants = paysMarche;

        List<String> noms = new ArrayList<>();
        for (String[] x : paysMarche) noms.add(x[0]);
        spPays.setAdapter(Ui.adapterSpinner(this, noms));
        for (int i = 0; i < paysMarche.size(); i++)
            if (paysMarche.get(i)[1].equals(codeChoisi)) spPays.setSelection(i);
    }

    static String nomPays(String cc) {
        String n = new Locale("", cc).getDisplayCountry(Locale.FRENCH);
        return (n == null || n.isEmpty()) ? cc : n;
    }

    // ---------- Réseau ----------

    String get(String adresse) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
        c.setRequestProperty("User-Agent", "Sossokouame-appel/1.0");
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
        Pattern pi = Pattern.compile("tvg-id=\"([^\"]*)\"");
        String nom = null, groupe = "", cc = "";
        for (String l : m3u.split("\\r?\\n")) {
            l = l.trim();
            if (l.startsWith("#EXTINF")) {
                int v = l.lastIndexOf(',');
                nom = v >= 0 ? l.substring(v + 1).trim() : "Chaîne";
                Matcher m = pg.matcher(l);
                groupe = m.find() ? m.group(1).trim() : "";
                cc = "";
                Matcher mi = pi.matcher(l);
                if (mi.find()) {
                    String id = mi.group(1);
                    int at = id.indexOf('@');
                    String base = at >= 0 ? id.substring(0, at) : id;
                    int dot = base.lastIndexOf('.');
                    if (dot >= 0 && base.length() - dot - 1 == 2)
                        cc = base.substring(dot + 1).toUpperCase(Locale.ROOT);
                }
            } else if (!l.isEmpty() && !l.startsWith("#") && nom != null) {
                Chaine c = new Chaine();
                c.nom = nom.isEmpty() ? "Chaîne" : nom;
                c.url = l;
                c.cc = cc;
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

    void charger(String code) {
        final int gen = ++generation;
        tvInfo.setText("Chargement des chaînes…");
        new Thread(() -> {
            try {
                String t = get(BASE + "countries/" + code.toLowerCase(Locale.ROOT) + ".m3u");
                List<Chaine> tmp = t == null ? new ArrayList<>() : parser(t);
                for (Chaine c : tmp) if (c.cc.isEmpty()) c.cc = code.toUpperCase(Locale.ROOT);
                runOnUiThread(() -> {
                    if (gen != generation || modeMarche) return;
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
                    if (gen != generation || modeMarche) return;
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

    // ---------- Analyse automatique des chaînes qui marchent ----------

    boolean testerFlux(String adresse) {
        HttpURLConnection c = null;
        try {
            String u = adresse;
            for (int k = 0; k < 4; k++) {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) return false;
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                if (code != 200) { c.disconnect(); return false; }
                String type = c.getContentType();
                InputStream in = c.getInputStream();
                byte[] buf = new byte[2048];
                int n = in.read(buf);
                in.close();
                c.disconnect();
                if (n <= 0) return false;
                String tete = new String(buf, 0, n, "UTF-8").trim();
                if (tete.startsWith("#EXTM3U")) return true;           // liste HLS
                if (type == null) return false;
                type = type.toLowerCase(Locale.ROOT);
                return type.startsWith("video/") || type.startsWith("audio/")
                        || type.contains("mpegurl") || type.contains("octet-stream")
                        || type.contains("mp2t");
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Exception ignored) {} }
        }
    }

    void ajouterMarche(Chaine c) {
        synchronized (marche) {
            if (urlsMarche.add(c.url)) marche.add(c);
        }
    }

    /** Une chaîne de « Toutes les chaînes » qui démarre vraiment est ajoutée à « Chaînes qui marchent ». */
    void validerChaine() {
        Chaine c = enLecture;
        if (c == null || modeMarche) return;
        synchronized (marche) {
            if (urlsMarche.contains(c.url)) return;
        }
        Chaine n = new Chaine();
        n.nom = c.nom;
        n.url = c.url;
        n.texte = c.texte;
        n.cc = c.cc;
        if (n.cc.isEmpty() && !modeMonde && paysCourants == paysTous) {
            int pos = spPays.getSelectedItemPosition();
            if (pos >= 0 && pos < paysTous.size()) n.cc = paysTous.get(pos)[1];
        }
        ajouterMarche(n);
        manuelles.add(n);
        sauver();
        sauverManuelles();
        Toast.makeText(this, "✅ Ajoutée à « Chaînes qui marchent »", Toast.LENGTH_SHORT).show();
    }

    int nbMarche() {
        synchronized (marche) { return marche.size(); }
    }

    List<Chaine> dedoublonner(List<Chaine> l) {
        Set<String> vus = new HashSet<>();
        List<Chaine> r = new ArrayList<>();
        for (Chaine c : l) if (vus.add(c.url)) r.add(c);
        return r;
    }

    void lancerScan() {
        if (scanEnCours) return;
        scanEnCours = true;
        arret = false;
        bScan.setText("⏹ Arrêter");
        tvScan.setText("Préparation de l'analyse…");
        new Thread(() -> {
            boolean termine = false;
            try {
                File cache = new File(getFilesDir(), "index.m3u");
                List<Chaine> tous = null;
                int debut = prefs.getInt("index", 0);
                if (debut > 0 && !prefs.getBoolean("fini", false) && cache.exists()) {
                    tous = dedoublonner(parser(lire(cache)));
                    if (debut >= tous.size()) tous = null;
                }
                if (tous == null) {
                    debut = 0;
                    String t = get(BASE + "index.m3u");     // liste fraîche : nouvelles chaînes incluses
                    if (t == null) throw new Exception("index introuvable");
                    ecrire(cache, t);
                    tous = dedoublonner(parser(t));
                    synchronized (marche) { marche.clear(); urlsMarche.clear(); }
                    for (Chaine m : new ArrayList<>(manuelles)) ajouterMarche(m);
                    prefs.edit().putInt("index", 0).putBoolean("fini", false).apply();
                }
                final List<Chaine> aTester = tous;
                final int total = aTester.size();
                final AtomicInteger faits = new AtomicInteger(debut);
                runOnUiThread(() -> majScan(faits.get(), total));

                ExecutorService pool = Executors.newFixedThreadPool(24);
                int i = debut;
                while (i < total && !arret) {
                    int fin = Math.min(total, i + 240);
                    List<Callable<Void>> taches = new ArrayList<>();
                    for (int j = i; j < fin; j++) {
                        final Chaine c = aTester.get(j);
                        taches.add(() -> {
                            if (!arret && testerFlux(c.url)) ajouterMarche(c);
                            int f = faits.incrementAndGet();
                            if (f % 10 == 0) runOnUiThread(() -> majScan(f, total));
                            return null;
                        });
                    }
                    pool.invokeAll(taches);
                    if (!arret) {
                        prefs.edit().putInt("index", fin).apply();
                        sauver();
                        runOnUiThread(this::apresLot);
                        i = fin;
                    }
                }
                pool.shutdownNow();
                termine = !arret && i >= total;
                if (termine)
                    prefs.edit().putBoolean("fini", true)
                            .putLong("date", System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
            }
            sauver();
            final boolean ok = termine;
            runOnUiThread(() -> finScan(ok));
        }).start();
    }

    void majScan(int faits, int total) {
        tvScan.setText("Analyse : " + faits + " / " + total + "  ·  "
                + nbMarche() + " chaînes qui marchent");
        progres.setMax(total);
        progres.setProgress(faits);
    }

    void apresLot() {
        if (modeMarche && courant < 0) {
            majPaysMarche();
            filtrer();
        }
    }

    void finScan(boolean termine) {
        scanEnCours = false;
        if (termine) {
            tvScan.setText("✅ Analyse terminée : " + nbMarche() + " chaînes qui marchent");
            bScan.setText("🔍 Analyser à nouveau");
        } else if (arret) {
            tvScan.setText("⏸ Analyse en pause : " + nbMarche() + " chaînes qui marchent");
            bScan.setText("▶ Reprendre l'analyse");
        } else {
            tvScan.setText("⚠ Analyse interrompue (réseau ?) : " + nbMarche() + " chaînes trouvées");
            bScan.setText("▶ Reprendre l'analyse");
        }
        apresLot();
    }

    // ---------- Sauvegarde locale de la liste ----------

    static String lire(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        r.close();
        return sb.toString();
    }

    static void ecrire(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }

    static String versTsv(List<Chaine> l) {
        StringBuilder sb = new StringBuilder();
        for (Chaine c : l)
            sb.append(c.nom.replace('\t', ' ')).append('\t').append(c.cc)
                    .append('\t').append(c.url).append('\n');
        return sb.toString();
    }

    void sauver() {
        try {
            String t;
            synchronized (marche) { t = versTsv(marche); }
            ecrire(new File(getFilesDir(), "marche.tsv"), t);
        } catch (Exception ignored) {}
    }

    void sauverManuelles() {
        try {
            ecrire(new File(getFilesDir(), "ajoutees.tsv"), versTsv(manuelles));
        } catch (Exception ignored) {}
    }

    void lireTsv(String nomFichier, boolean manuelle) {
        try {
            File f = new File(getFilesDir(), nomFichier);
            if (!f.exists()) return;
            for (String l : lire(f).split("\n")) {
                String[] t = l.split("\t");
                if (t.length < 3) continue;
                Chaine c = new Chaine();
                c.nom = t[0];
                c.cc = t[1];
                c.url = t[2];
                c.texte = norm(c.nom);
                ajouterMarche(c);
                if (manuelle) manuelles.add(c);
            }
        } catch (Exception ignored) {}
    }

    void chargerMarche() {
        lireTsv("marche.tsv", false);
        lireTsv("ajoutees.tsv", true);   // chaînes validées à la main
    }

    // ---------- Liste affichée ----------

    void filtrer() {
        affichees.clear();
        String q = norm(etRecherche.getText().toString().trim());
        String code = "";
        List<Chaine> source;
        if (modeMarche) {
            int pos = spPays.getSelectedItemPosition();
            if (pos >= 0 && pos < paysCourants.size()) code = paysCourants.get(pos)[1];
            synchronized (marche) { source = new ArrayList<>(marche); }
        } else {
            source = toutes;
        }
        for (Chaine c : source) {
            if (!code.isEmpty() && !code.equalsIgnoreCase(c.cc)) continue;
            if (!q.isEmpty() && !c.texte.contains(q)) continue;
            affichees.add(c);
        }
        if (modeMarche) Collections.sort(affichees, (x, y) -> x.nom.compareToIgnoreCase(y.nom));

        if (modeMarche && marche.isEmpty())
            tvInfo.setText("Aucune chaîne vérifiée pour l'instant : l'analyse est en cours…");
        else if (!modeMarche && toutes.isEmpty())
            tvInfo.setText(modeMonde ? "Aucun résultat"
                    : "Aucune chaîne libre pour ce pays : essayez 🌍 pour chercher dans le monde");
        else
            tvInfo.setText(affichees.size() + " chaîne(s)"
                    + (modeMarche ? " vérifiée(s)" : modeMonde ? " · recherche mondiale" : " (non vérifiées)"));

        adapter.clear();
        for (Chaine c : affichees)
            adapter.add(c.nom + (modeMarche && !c.cc.isEmpty() ? "  · " + nomPays(c.cc) : ""));
        courant = -1;
    }

    // ---------- Lecture ----------

    void jouer(int i) {
        if (i < 0 || i >= affichees.size()) return;
        courant = i;
        liste.setItemChecked(i, true);
        liste.smoothScrollToPosition(i);
        Chaine c = affichees.get(i);
        enLecture = c;
        nomActuel = c.nom;
        tvEtat.setText(nomActuel + "\nConnexion…");
        if (plein) Toast.makeText(this, c.nom, Toast.LENGTH_SHORT).show();
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
        if (plein) panneauScan.setVisibility(View.GONE);
        else appliquerVisibilites();
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

    // ---------- Position ----------

    void choisirPays(String code) {
        int idx = -1;
        for (int i = 0; i < paysCourants.size(); i++)
            if (paysCourants.get(i)[1].equalsIgnoreCase(code)) idx = i;
        if (idx < 0) {
            Toast.makeText(this, "Aucune chaîne trouvée pour ce pays (pour l'instant)", Toast.LENGTH_LONG).show();
        } else if (idx == spPays.getSelectedItemPosition()) {
            if (modeMarche) filtrer(); else charger(code);
        } else {
            spPays.setSelection(idx);
        }
    }

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
                if (c == null) {
                    Toast.makeText(this, "Impossible de déterminer votre pays", Toast.LENGTH_LONG).show();
                } else {
                    choisirPays(c);
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
        arret = true;
        sauver();
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
