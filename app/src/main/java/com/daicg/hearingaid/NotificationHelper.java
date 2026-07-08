package com.daicg.hearingaid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class NotificationHelper {
    static final String MONITOR_CHANNEL = "headset_monitor";
    static final String LISTENING_CHANNEL = "hearing_listening";
    static final int MONITOR_NOTIFICATION_ID = 10;
    static final int LISTENING_NOTIFICATION_ID = 11;

    private NotificationHelper() {
    }

    static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel monitor = new NotificationChannel(
                MONITOR_CHANNEL,
                "\u8033\u673a\u76d1\u6d4b",
                NotificationManager.IMPORTANCE_MIN);
        monitor.setDescription("\u4f4e\u529f\u8017\u76d1\u6d4b\u84dd\u7259\u6216\u6709\u7ebf\u8033\u673a\u8fde\u63a5");

        NotificationChannel listening = new NotificationChannel(
                LISTENING_CHANNEL,
                "\u52a9\u542c\u8fd0\u884c\u4e2d",
                NotificationManager.IMPORTANCE_LOW);
        listening.setDescription("\u9ea6\u514b\u98ce\u6b63\u5728\u7528\u4e8e\u52a9\u542c");

        manager.createNotificationChannel(monitor);
        manager.createNotificationChannel(listening);
    }

    static Notification monitorNotification(Context context) {
        return baseBuilder(context, MONITOR_CHANNEL)
                .setContentTitle("\u624b\u673a\u52a9\u542c\u5668\u5df2\u5f85\u547d")
                .setContentText("\u6b63\u5728\u4f4e\u529f\u8017\u76d1\u6d4b\u8033\u673a\u8fde\u63a5")
                .setOngoing(true)
                .build();
    }

    static Notification listeningNotification(Context context) {
        return baseBuilder(context, LISTENING_CHANNEL)
                .setContentTitle("\u624b\u673a\u52a9\u542c\u5668\u8fd0\u884c\u4e2d")
                .setContentText("\u6b63\u5728\u6536\u97f3\u5e76\u8f93\u51fa\u5230\u8033\u673a")
                .setOngoing(true)
                .build();
    }

    private static Notification.Builder baseBuilder(Context context, String channelId) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, channelId)
                : new Notification.Builder(context);
        return builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setShowWhen(false);
    }
}
