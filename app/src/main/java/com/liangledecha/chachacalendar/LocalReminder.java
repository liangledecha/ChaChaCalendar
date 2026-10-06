package com.liangledecha.chachacalendar;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/** 无常驻服务的本机提醒：系统定时唤醒，应用只在到点时发一条通知。 */
public final class LocalReminder extends BroadcastReceiver {
    static final int SYSTEM_CALENDAR = 0, DIRECT = 1;
    static final int NOTIFICATION_PERMISSION_REQUEST = 2402;
    private static final String ACTION_FIRE = "com.liangledecha.chachacalendar.REMINDER";
    private static final String CHANNEL = "calendar_reminders_v1";

    static int source(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("reminder_source", SYSTEM_CALENDAR);
    }

    static void rebuildAsync(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> rebuild(app), "更新本机日程提醒").start();
    }

    static synchronized void rebuild(Context context) {
        SharedPreferences state = context.getSharedPreferences("local_reminders", Context.MODE_PRIVATE);
        Set<String> old = state.getStringSet("ids", new HashSet<>());
        Set<String> current = new HashSet<>();
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        if (source(context) == DIRECT) {
            EventStore store = new EventStore(context);
            try {
                LocalDateTime now = LocalDateTime.now();
                for (Event event : store.all()) {
                    LocalDateTime next = event.nextLocalReminder(now);
                    if (next == null) continue;
                    long at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (at <= System.currentTimeMillis()) continue;
                    current.add(Long.toString(event.id));
                    PendingIntent intent = pending(context, event.id, at, PendingIntent.FLAG_UPDATE_CURRENT);
                    try {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms())
                            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent);
                        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent);
                    } catch (SecurityException denied) {
                        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent);
                    }
                }
            } finally { store.close(); }
        }
        for (String id : old) if (!current.contains(id)) {
            PendingIntent intent = pending(context, Long.parseLong(id), 0, PendingIntent.FLAG_NO_CREATE);
            if (intent != null) { manager.cancel(intent); intent.cancel(); }
        }
        state.edit().putStringSet("ids", current).apply();
    }

    private static PendingIntent pending(Context context, long id, long at, int flags) {
        Intent intent = new Intent(context, LocalReminder.class).setAction(ACTION_FIRE)
                .setData(Uri.parse("chacha-reminder://event/" + id)).putExtra("id", id).putExtra("at", at);
        return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static void openNotificationSettings(Context context) {
        createChannel(context);
        Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL);
        context.startActivity(intent);
    }

    private static void createChannel(Context context) {
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "茶茶日程提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("日程到点通知；声音、震动和横幅可在系统通知设置中调整");
        channel.enableVibration(true);
        notifications.createNotificationChannel(channel);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_FIRE.equals(intent.getAction())) {
            PendingResult pending = goAsync();
            new Thread(() -> { try { rebuild(context); } finally { pending.finish(); } }, "恢复本机日程提醒").start();
            return;
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                if (source(context) != DIRECT) return;
                long id = intent.getLongExtra("id", -1), at = intent.getLongExtra("at", -1);
                EventStore store = new EventStore(context);
                try {
                    Event found = null;
                    for (Event event : store.all()) if (event.id == id) { found = event; break; }
                    if (found == null || at <= 0) return;
                    LocalDateTime before = LocalDateTime.ofInstant(Instant.ofEpochMilli(at - 1), ZoneId.systemDefault());
                    LocalDateTime expected = found.nextLocalReminder(before);
                    if (expected == null || expected.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() != at) return;
                    show(context, found);
                } finally { store.close(); }
            } finally { rebuild(context); pending.finish(); }
        }, "显示本机日程提醒").start();
    }

    private static void show(Context context, Event event) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        createChannel(context);
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications == null || !notifications.areNotificationsEnabled()) return;
        Intent open = new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_AGENDA, true)
                .putExtra(MainActivity.EXTRA_EVENT_ID, event.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent click = PendingIntent.getActivity(context, Long.hashCode(event.id), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_app).setContentTitle(event.title)
                .setContentText(event.type + " · 点击查看日程")
                .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true).setContentIntent(click).build();
        notifications.notify(Long.hashCode(event.id), notice);
    }
}
