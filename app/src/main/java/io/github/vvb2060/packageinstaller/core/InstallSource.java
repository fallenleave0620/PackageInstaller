package io.github.vvb2060.packageinstaller.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import java.util.List;

import io.github.vvb2060.packageinstaller.BuildConfig;

/**
 * 安装来源（{@code SessionParams#setInstallerPackageName}）设置。
 *
 * <p>国内 OEM 会给「未知来源」安装的应用加限制（后台弹窗、自启动、权限弹窗等），
 * 把 installer 记成商店包名可以绕开这类限制。这里只负责「用户选了什么」和
 * 「选的东西能不能用」，真正写入会话参数在 {@link InstallRepository#createSessionParams}。
 */
public final class InstallSource {

    private static final String KEY = "installer_source";
    private static final String TAG = "InstallSource";

    /** 不伪造来源，仍以本应用作为 installer。 */
    public static final String NONE = "";

    public static final String GOOGLE_PLAY = "com.android.vending";

    /** 常见商店，按「Google Play → 各 OEM 商店」的顺序探测默认值。 */
    public static final List<String> PRESETS = List.of(
        GOOGLE_PLAY,
        "com.heytap.market",     // OPPO / 一加
        "com.oppo.market",       // 旧版 OPPO 商店
        "com.xiaomi.market",     // 小米 / 红米
        "com.huawei.appmarket",  // 华为应用市场
        "com.hihonor.appmarket", // 荣耀应用市场
        "com.bbk.appstore",      // vivo 应用商店
        "com.sec.android.app.samsungapps" // 三星应用商店
    );

    private InstallSource() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE);
    }

    /**
     * 用户选择的来源。null = 没设置过（走「自动」，即已安装的默认商店），
     * 空字符串 = 明确选择「不伪造」。
     */
    @Nullable
    public static String get(Context context) {
        var value = prefs(context).getString(KEY, null);
        return value == null ? null : value;
    }

    public static void set(Context context, String packageName) {
        prefs(context).edit().putString(KEY, packageName).apply();
    }

    /** 恢复成「自动」。 */
    public static void reset(Context context) {
        prefs(context).edit().remove(KEY).apply();
    }

    /**
     * 实际会写进会话的 installer 包名：空串表示「不伪造，用本应用」。
     * 没设置过时是默认商店（Play / 已装 OEM 商店 / shell）。
     */
    public static String effectiveSource(Context context, PackageManager pm) {
        var configured = get(context);
        if (configured != null && configured.isEmpty()) return "";
        return resolve(pm, configured);
    }


    /**
     * 需要系统视角的 PackageManager：本应用声明了包可见性限制（queries 里只有 Shizuku），
     * 直接查商店包名会一律 NameNotFound，所以优先用 Shizuku/root 换来的系统 PM。
     */
    public static PackageManager packageManager(Context context) {
        var system = BinderWrapper.pm;
        return system != null ? system : context.getPackageManager();
    }

    public static boolean isInstalled(PackageManager pm, String packageName) {
        try {
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** 默认来源：已安装的商店里第一个（优先 Google Play），都没有就退回 shell。 */
    public static String defaultSource(PackageManager pm) {
        for (var candidate : PRESETS) {
            if (isInstalled(pm, candidate)) return candidate;
        }
        return "com.android.shell";
    }

    /**
     * 把用户配置解析成实际可用的 installer 包名。
     *
     * <p>配置的包名没装时退回默认商店——伪造一个不存在的 installer 大概率被系统
     * 记成 {@code INSTALL_FAILED_INVALID_INSTALLER}，不如退到一个真实存在的商店。
     */
    public static String resolve(PackageManager pm, @Nullable String requested) {
        if (requested == null || requested.isEmpty()) {
            return defaultSource(pm);
        }
        if (isInstalled(pm, requested)) return requested;
        var fallback = defaultSource(pm);
        android.util.Log.w(TAG, "installer " + requested + " 未安装，退回 " + fallback);
        return fallback;
    }

    /** 显示名：优先应用名，查不到时用包名。 */
    public static CharSequence label(PackageManager pm, @Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        try {
            return pm.getApplicationInfo(packageName, 0).loadLabel(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return packageName;
        }
    }
}
