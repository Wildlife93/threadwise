package com.wildlife93.threadwise;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * SmsNotificationHelper
 *
 * Posts a heads-up notification when an SMS arrives while Threadwise is
 * backgrounded (so the user is not staring at the WebView when SMS_DELIVER
 * fires).
 *
 * As the default SMS app we are responsible for ALL user-visible SMS alerts.
 */
public class SmsNotificationHelper {

    private static final String CHANNEL_ID   = "tw_sms";
    private static final String CHANNEL_NAME = "SMS Messages";
    private static final int    NOTIF_BASE   = 9000; // base notification ID

    public static void show(Context context, String sender, String body) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // ── Create channel (API 26+) ──────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Incoming SMS notifications");
            nm.createNotificationChannel(ch);
        }

        // ── Tap intent: open MainActivity ─────────────────────────────────────
        Intent openApp = new Intent(context, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = (sender != null && !sender.isEmpty()) ? sender : "New message";
        String text  = (body   != null && !body.isEmpty())   ? body   : "";

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)          // use your launcher icon
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        // Use sender-based ID so separate senders get separate notifications
        int notifId = NOTIF_BASE + (sender != null ? sender.hashCode() & 0xFFFF : 0);
        nm.notify(notifId, notification);
    }
}
