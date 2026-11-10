package com.liangledecha.chachacalendar;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import java.time.LocalDate;

/** 本地处理待办勾选；只有点击事项正文时才打开应用。 */
public final class WidgetActionReceiver extends BroadcastReceiver {
    static final String ACTION = "com.liangledecha.chachacalendar.WIDGET_ITEM";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        if (CalendarWidgetProvider.mode(context, AppWidgetManager.getInstance(context), widgetId) != WidgetData.TODOS
                || !WidgetActionToken.accepts(CalendarWidgetProvider.settings(context).getString("widget_action_token", ""),
                intent.getStringExtra("widget_action_token"))) return;
        long id = intent.getLongExtra(MainActivity.EXTRA_EVENT_ID, -1);
        if (id <= 0) return;
        if ("open".equals(intent.getStringExtra("operation"))) {
            Intent open = new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_AGENDA, true)
                    .putExtra(MainActivity.EXTRA_EVENT_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent activity = CalendarWidgetProvider.activityPendingIntent(context, widgetId, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Bundle options = null;
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions allowed = ActivityOptions.makeBasic();
                allowed.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                options = allowed.toBundle();
            }
            try { activity.send(context, 0, null, null, null, null, options); }
            catch (PendingIntent.CanceledException ignored) { }
            return;
        }
        if (!"complete".equals(intent.getStringExtra("operation")) || !intent.hasExtra("completed")) return;
        final LocalDate expected;
        try { expected = LocalDate.parse(intent.getStringExtra("occurrence")); }
        catch (java.time.DateTimeException | NullPointerException ignored) { return; }
        boolean completed = intent.getBooleanExtra("completed", false);
        PendingResult pending = goAsync();
        new Thread(() -> {
            boolean changed = false;
            try (EventStore store = new EventStore(context)) {
                Event current = store.all().stream().filter(event -> event.id == id).findFirst().orElse(null);
                if (current != null) {
                    ContentValues before = EventStore.values(current);
                    changed = current.setOccurrenceCompleted(expected, completed, LocalDate.now())
                            && store.applyCalendarChange(current, before, false);
                }
                if (changed) {
                    CalendarWidgetProvider.refreshTodo(context, widgetId);
                    CalendarWidgetProvider.refreshAll(context);
                    LocalReminder.rebuildAsync(context);
                    if (SystemCalendarSync.hasPermission(context)) SystemCalendarSync.syncStored(context, store, id);
                }
            } catch (Exception ignored) {
                String message = changed ? "待办已更新，组件或系统提醒暂未刷新" : "待办更新失败，请重试";
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show());
            } finally {
                try { CalendarWidgetProvider.refreshAll(context); }
                finally { pending.finish(); }
            }
        }, "小组件待办勾选").start();
    }
}
