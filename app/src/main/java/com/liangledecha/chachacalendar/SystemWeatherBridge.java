package com.liangledecha.chachacalendar;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * 系统天气应用适配器。
 *
 * <p>安卓没有统一的系统天气数据接口，本类不读取厂商私有数据库，
 * 只在不申请敏感权限的前提下寻找系统天气应用的公开启动入口。</p>
 */
public final class SystemWeatherBridge {
    /** 常见手机厂商天气应用的包名，按优先顺序尝试。 */
    private static final String[] PACKAGES = {
            "com.huawei.android.totemweather", "com.sec.android.daemonapp", "com.miui.weather2",
            "com.coloros.weather2", "com.oplus.weather", "com.vivo.weather",
            "com.meizu.flyme.weather", "com.google.android.googlequicksearchbox"
    };
    /** 工具类不需要创建对象，因此隐藏默认构造函数。 */
    private SystemWeatherBridge() { }

    /**
     * 查找当前手机中可启动的天气应用。
     *
     * @param context 用于访问系统软件包管理器的上下文
     * @return 可启动天气应用的意图；没有发现时返回空值
     */
    public static Intent findWeatherApp(Context context) {
        // 软件包管理器负责查询应用是否安装及其启动页面。
        PackageManager pm = context.getPackageManager();
        // 依次尝试各厂商包名，找到第一个有效入口后立即返回。
        for (String packageName : PACKAGES) {
            Intent launch = pm.getLaunchIntentForPackage(packageName);
            if (launch != null) return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }
}
