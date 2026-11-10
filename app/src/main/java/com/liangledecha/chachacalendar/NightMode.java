package com.liangledecha.chachacalendar;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import java.time.LocalTime;
import java.time.ZonedDateTime;

final class NightMode {
    static final String MODE = "night_mode", START = "night_start", END = "night_end";
    static final int BACKGROUND = 0xff141a18, SURFACE = 0xff222b27, BORDER = 0xff414e46;

    static boolean shouldBeNight(Context context) {
        SharedPreferences p = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        return NightSchedule.active(p.getInt(MODE, NightSchedule.OFF), start(p), end(p), LocalTime.now());
    }

    static int start(SharedPreferences p) { int value = p.getInt(START, NightSchedule.DEFAULT_START); return value >= 0 && value < 1440 ? value : NightSchedule.DEFAULT_START; }
    static int end(SharedPreferences p) { int value = p.getInt(END, NightSchedule.DEFAULT_END); return value >= 0 && value < 1440 ? value : NightSchedule.DEFAULT_END; }

    static boolean isNight(Context context) {
        // 正在编辑的弹窗不因到点重建；其余页面仍使用本次创建时的同一套配色。
        while (context instanceof ContextWrapper) {
            if (context instanceof MainActivity) return ((MainActivity) context).nightApplied;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return shouldBeNight(context);
    }

    static int foreground(Context context, int lightColor) {
        if (!isNight(context)) return lightColor;
        int r = Color.red(lightColor), g = Color.green(lightColor), b = Color.blue(lightColor);
        if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 55)
            return r > 230 && g > 230 && b > 230 ? Color.WHITE
                    : r >= 115 ? 0xffaebcb3 : 0xffe5eee8;
        return blend(lightColor, Color.WHITE, .35f);
    }

    static int surface(Context context) { return isNight(context) ? SURFACE : Color.WHITE; }
    static int border(Context context, int lightColor) { return isNight(context) ? BORDER : lightColor; }
    static int accent(Context context, int color) {
        if (!isNight(context)) return color;
        int candidate = color;
        for (float mix = .32f; mix <= 1.01f; mix += .04f) {
            candidate = blend(color, Color.WHITE, Math.min(1, mix));
            if ((Color.luminance(candidate) + .05f) / (Color.luminance(SURFACE) + .05f) >= 4.5f) break;
        }
        return candidate;
    }
    static int blend(int color, int base, float amount) {
        return Color.rgb(Math.round(Color.red(color) * (1 - amount) + Color.red(base) * amount),
                Math.round(Color.green(color) * (1 - amount) + Color.green(base) * amount),
                Math.round(Color.blue(color) * (1 - amount) + Color.blue(base) * amount));
    }

    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        PendingIntent pending = PendingIntent.getBroadcast(app, 8610, new Intent(app, NightModeReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarm.cancel(pending);
        SharedPreferences p = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (p.getInt(MODE, NightSchedule.OFF) != NightSchedule.TIMED || !NightSchedule.valid(start(p), end(p))) return;
        AppWidgetManager widgets = AppWidgetManager.getInstance(app);
        boolean installed = false;
        for (Class<?> provider : new Class<?>[]{CalendarWidgetProvider.class, TodoWidgetProvider.class, CountdownWidgetProvider.class})
            installed |= widgets.getAppWidgetIds(new ComponentName(app, provider)).length > 0;
        if (!installed) return;
        long when = NightSchedule.nextChange(start(p), end(p), ZonedDateTime.now()).toInstant().toEpochMilli();
        // 非唤醒闹钟，不为换色唤醒休眠设备；前台有自己的精确边界回调。
        alarm.set(AlarmManager.RTC, when, pending);
    }
}
