package gt.david.asistentecasa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Después de actualizarse, T-Bot vuelve a abrirse solo. */
public class Reinicio extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        try {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }
}
