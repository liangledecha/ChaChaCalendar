package com.liangledecha.chachacalendar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 仅在每日边界或时钟变化时更新本地组件，不保活、不联网。 */
public final class NightModeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        CalendarWidgetProvider.refreshAll(context);
    }
}
