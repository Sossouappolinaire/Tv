package bj.radio;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Point d'entrée de l'application : initialise le compte et la sécurité, et verrouille
 * l'application (PIN / empreinte) au lancement et quand on revient après plus de 30 secondes.
 */
public class App extends Application {

    private static Context ctx;
    private static final long DELAI_VERROU_MS = 30_000;

    private final android.os.Handler pouls = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable battement = new Runnable() {
        @Override public void run() {
            if (Compte.connecte()) new Thread(Supabase::battement).start();
            pouls.postDelayed(this, 60_000);
        }
    };

    private int demarrees = 0;
    private long heureFond = 0;

    static Context contexte() { return ctx; }

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();
        Compte.init(this);
        Securite.init(this);
        Securite.verrouille = Compte.connecte() && Securite.pinActif();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity a) {
                if (demarrees == 0 && heureFond > 0 && Compte.connecte() && Securite.pinActif()
                        && System.currentTimeMillis() - heureFond > DELAI_VERROU_MS) {
                    Securite.verrouille = true;
                }
                if (demarrees == 0) {
                    pouls.removeCallbacks(battement);
                    pouls.post(battement);   // « en ligne » : un signal par minute tant que l'app est visible
                }
                demarrees++;
                if (Securite.verrouille && Compte.connecte() && Securite.pinActif()
                        && !(a instanceof VerrouActivity) && !(a instanceof AuthActivity)) {
                    a.startActivity(new Intent(a, VerrouActivity.class));
                }
            }

            @Override
            public void onActivityStopped(Activity a) {
                demarrees = Math.max(0, demarrees - 1);
                if (demarrees == 0) {
                    heureFond = System.currentTimeMillis();
                    pouls.removeCallbacks(battement);
                }
            }

            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}
