package com.bg7yoz.ft8cn.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import com.bg7yoz.ft8cn.R;

public class RecordingForegroundService extends Service {
    private static final String CHANNEL_ID = "FT8CN_Recording_Channel";
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public RecordingForegroundService getService() {
            return RecordingForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start foreground with persistent notification
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FT8CN Recording")
                .setContentText("Audio recording active for FT8 operation")
                .setSmallIcon(R.drawable.ic_baseline_mic_48) // Use your app icon
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "FT8CN Recording Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps audio recording active in background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // Static helpers to start/stop from anywhere
    public static void start(Context context) {
        Intent intent = new Intent(context, RecordingForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, RecordingForegroundService.class);
        context.stopService(intent);
    }
}