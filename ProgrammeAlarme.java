package bj.radio;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Chaque jour à 00h00 (heure du Bénin), le téléphone récupère le programme de TV Kouamé fabriqué par le serveur
 * et affiche une notification « Programme du jour » à l'utilisateur connecté.
 * L'alarme est reprogrammée à chaque ouverture de l'application, après chaque notification et après un redémarrage.
 * (Alarme non exacte : Android peut la décaler de quelques minutes pour économiser la batterie.)
 */
public class ProgrammeAlarme extends BroadcastReceiver {

    static final String ACTION = "bj.radio.PROGRAMME_MINUIT";
    private static final String CANAL = "tv_kouame";

    /** Programme la prochaine notification à 00h00 (heure du Bénin). */
    static void planifier(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Africa/Porto-Novo"));
            cal.add(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 30);
            cal.set(Calendar.MILLISECOND, 0);
            Intent i = new Intent(c, ProgrammeAlarme.class).setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(c, 7, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        final Context app = c.getApplicationContext();
        planifier(app);                                        // toujours reprogrammer pour demain
        if (i == null || !ACTION.equals(i.getAction())) return; // démarrage du téléphone / mise à jour : on s'arrête là
        if (!Compte.connecte()) return;
        final PendingResult fin = goAsync();
        new Thread(() -> {
            try {
                JSONObject j = Supabase.tvPlanning();
                if (j != null) notifier(app, j);
            } catch (Exception ignored) {
            } finally {
                fin.finish();
            }
        }).start();
    }

    private static String hm(long ms) {
        long m = ms / 60000;
        return String.format(Locale.FRANCE, "%02dh%02d", (m / 60) % 24, m % 60);
    }

    private static void notifier(Context c, JSONObject j) {
        JSONArray a = j.optJSONArray("programmes");
        StringBuilder sb = new StringBuilder();
        int n = 0, vus = 0;
        for (int k = 0; a != null && k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || o.optLong("debut_ms", 0) < 0) continue;   // le film commencé la veille continue : on ne le liste pas
            n++;
            if (vus >= 8) continue;
            vus++;
            String t = o.optString("titre", "");
            if (t.isEmpty() || "null".equals(t)) t = "Vidéo";
            sb.append(hm(o.optLong("debut_ms", 0))).append("  ").append(t).append('\n');
        }
        if (n > 8) sb.append("… et ").append(n - 8).append(" autre(s)");
        if (sb.length() == 0) sb.append("Le programme du jour est en préparation.");

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CANAL, "TV Kouamé", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent ouvrir = new Intent(c, TvKouameActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 8, ouvrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CANAL) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("📺 TV Kouamé · programme du jour")
                .setContentText(n + " programme(s) à partir de 00h00")
                .setStyle(new Notification.BigTextStyle().bigText(sb.toString().trim()))
                .setContentIntent(pi)
                .setAutoCancel(true);
        nm.notify(7, b.build());
    }
}
