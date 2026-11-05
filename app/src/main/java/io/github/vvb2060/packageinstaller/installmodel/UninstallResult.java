package io.github.vvb2060.packageinstaller.installmodel;

import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

/**
 * 卸载已经执行（成功或失败）。
 */
public final class UninstallResult extends InstallStage {

    public final ApkLite apkLite;
    @Nullable
    public final String splitName;
    /**
     * 是否真的把应用卸载掉了；false 表示卸载没有成功。
     */
    public final boolean uninstalled;
    public final boolean success;
    /**
     * 失败时的人类可读说明，成功时为 null。
     */
    @Nullable
    public final String message;

    private UninstallResult(ApkLite apkLite, @Nullable String splitName,
                            boolean uninstalled, boolean success, @Nullable String message) {
        super(STAGE_UNINSTALLED);
        this.apkLite = apkLite;
        this.splitName = splitName;
        this.uninstalled = uninstalled;
        this.success = success;
        this.message = message;
    }

    public static UninstallResult uninstalled(ApkLite apkLite, @Nullable String splitName) {
        return new UninstallResult(apkLite, splitName, true, true, null);
    }

    public static UninstallResult failed(ApkLite apkLite, @Nullable String splitName,
                                         @Nullable String message) {
        return new UninstallResult(apkLite, splitName, false, false, message);
    }

    public boolean isSplit() {
        return splitName != null && !splitName.isEmpty();
    }
}
