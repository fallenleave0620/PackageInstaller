package io.github.vvb2060.packageinstaller.installmodel;

import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.apk.ApkLite;

/**
 * 用户即将卸载某个已安装应用（或它的某个 split）。
 */
public final class UninstallUserAction extends InstallStage {

    public final ApkLite apkLite;
    public final long versionCode;
    /**
     * 非空时表示只移除这个 split，而不是卸载整个应用。
     */
    @Nullable
    public final String splitName;
    /**
     * 卸载后应用就不在了，记下 APK 路径好把标签/图标先固化下来。
     */
    @Nullable
    public final String sourceDir;

    public UninstallUserAction(ApkLite apkLite, long versionCode,
                               @Nullable String splitName, @Nullable String sourceDir) {
        super(UNINSTALL_USER_ACTION);
        this.apkLite = apkLite;
        this.versionCode = versionCode;
        this.splitName = splitName;
        this.sourceDir = sourceDir;
    }

    public boolean isSplit() {
        return splitName != null && !splitName.isEmpty();
    }
}
