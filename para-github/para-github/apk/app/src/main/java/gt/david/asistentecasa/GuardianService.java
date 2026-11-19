package gt.david.asistentecasa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Servicio en primer plano: le dice a Android que la app está usando cámara y micrófono a propósito,
 * para que no la cierre ni le quite el acceso. Muestra una notificación fija mientras vigila.
 */
public class GuardianService extends Service {

    private static final String CANAL = "vigilancia";
    private static final int ID = 1;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel c = new NotificationChannel(CANAL, "Vigilancia de la casa", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Aviso fijo mientras la estación está activa");
            nm.createNotificationChannel(c);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent abrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        Notification n = b.setContentTitle("Estación Casa activa")
                .setContentText("Escuchando y lista para encender la cámara")
                .setSmallIcon(R.drawable.ic_notif)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(ID, n);
            }
        } catch (Exception e) {
            Log.w("AsistenteCasa", "startForeground falló", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AsistenteCasa::vigilancia");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
