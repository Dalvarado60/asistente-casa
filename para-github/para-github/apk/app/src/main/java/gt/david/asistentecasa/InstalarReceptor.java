package gt.david.asistentecasa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Recibe la respuesta de Android al instalar una actualización y muestra el aviso de "Instalar". */
public class InstalarReceptor extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmar = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmar != null) {
                confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirmar); } catch (Exception ignored) { }
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String txt = st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                    ? "T-Bot: la versión nueva tiene otra firma. Desinstala una sola vez e instálala desde GitHub."
                    : "T-Bot: no se instaló la actualización" + (m != null ? " (" + m + ")" : "");
            Toast.makeText(c, txt, Toast.LENGTH_LONG).show();
        }
    }
}
