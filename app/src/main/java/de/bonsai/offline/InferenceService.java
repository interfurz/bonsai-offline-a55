package de.bonsai.offline;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Protects the application-owned worker while its user-initiated task is running. */
public final class InferenceService extends Service {
    static final String CHANNEL = "local-inference";
    static final String CANCEL = "de.bonsai.offline.CANCEL_INFERENCE";
    static final int NOTIFICATION_ID = 1;
    private BonsaiApp app;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        app = (BonsaiApp) getApplication();
        app.inferenceService = this;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Lokale Verarbeitung", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Bonsai rechnet auf deinem Gerät weiter, auch bei ausgeschaltetem Bildschirm.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bonsai:Inference");
        wakeLock.setReferenceCounted(false);
    }

    @SuppressLint("WakelockTimeout") // Released on idle, startup failure and service destruction.
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            if (intent != null && CANCEL.equals(intent.getAction())) app.cancel();
            if (app.busy) wakeLock.acquire();
            finishIfIdle();
        } catch (RuntimeException e) {
            app.cancel();
            app.backgroundError = "Hintergrundverarbeitung konnte nicht gestartet werden: " + e.getMessage();
            app.error = app.backgroundError;
            app.notifyUi();
            stopSelf();
        }
        // A killed process cannot resume the native context or an incomplete answer.
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1,
            new Intent(this, InferenceService.class).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(de.bonsai.offline.R.drawable.ic_bonsai)
            .setContentTitle("Bonsai verarbeitet lokal")
            .setContentText("Die Verarbeitung läuft im Hintergrund weiter.")
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Abbrechen", cancel).build())
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build();
    }

    void finishIfIdle() {
        if (app.busy) return;
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public void onDestroy() {
        releaseWakeLock();
        if (app.inferenceService == this) app.inferenceService = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
