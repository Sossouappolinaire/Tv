package bj.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Reçoit le résultat de l'installateur d'Android après « Mettre à jour » :
 * - il faut une confirmation de l'utilisateur : on ouvre la fenêtre d'Android « Mettre à jour cette application ? » ;
 * - en cas d'échec, on explique la cause (Play Protect, signature différente, espace insuffisant…).
 * En cas de réussite, Android ferme l'ancienne version et la remplace : il suffit de rouvrir l'appli.
 */
public class MajRecepteur extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        int statut = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (statut) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirmation);
                    } catch (Exception e) {
                        Toast.makeText(c, "Impossible d'ouvrir l'installation. Réessayez.", Toast.LENGTH_LONG).show();
                    }
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Toast.makeText(c, "✅ Mise à jour installée. Rouvrez l'application.", Toast.LENGTH_LONG).show();
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                Toast.makeText(c, "Mise à jour annulée.", Toast.LENGTH_LONG).show();
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                Toast.makeText(c, "Installation bloquée par Google Play Protect ou par le téléphone. "
                        + "Si Play Protect s'affiche, touchez « Plus de détails » puis « Installer quand même ».", Toast.LENGTH_LONG).show();
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                Toast.makeText(c, "Cette version n'est pas signée avec la même clé que celle installée. "
                        + "Désinstallez l'application une seule fois, puis installez la dernière version.", Toast.LENGTH_LONG).show();
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                Toast.makeText(c, "Espace insuffisant sur le téléphone : libérez de la place puis réessayez.", Toast.LENGTH_LONG).show();
                break;
            default: {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "Installation impossible" + (msg == null || msg.isEmpty() ? "." : " : " + msg), Toast.LENGTH_LONG).show();
            }
        }
    }
}
