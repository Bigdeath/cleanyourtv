package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps a privileged local ADB shell alive.
 *
 * The shell supervises the button and Cast watchers.
 * Unlike a short-lived ADB shell command, this connection stays
 * open until the service stops or the ADB transport disconnects.
 */
public final class ButtonKeeperService extends Service {
    private static final String TAG = "ButtonKeeper";
    private static final String CHANNEL = "button_keeper";

    private static final String SUPERVISOR =
            "while true; do " +
            "sh /data/local/tmp/tv-buttons.sh start; " +
            "sh /data/local/tmp/cast-local-watch.sh start; " +
            "echo .; " +
            "sleep 15; " +
            "done";

    private volatile boolean running;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm =
                getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Fernbedienungs-Tasten",
                NotificationManager.IMPORTANCE_LOW));

        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("CleanYourTV Fernbedienung")
                .setContentText("Tasten- und Cast-Wächter aktiv")
                .setOngoing(true)
                .build();

        startForeground(1211, notification);

        running = true;

        worker = new Thread(() -> {
            while (running) {
                try {
                    Log.i(TAG, "Starting persistent local ADB supervisor");

                    new LocalAdb(getApplicationContext())
                            .runShellPersistent(SUPERVISOR);

                    Log.w(TAG, "Persistent ADB supervisor exited");
                } catch (Exception e) {
                    Log.w(TAG, "ADB supervisor disconnected", e);
                }

                if (!running) break;

                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "CleanYourTV-AdbSupervisor");

        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
