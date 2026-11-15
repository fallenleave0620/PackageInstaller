package io.github.vvb2060.packageinstaller.core;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.Intent_rename;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageInstaller_rename;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager_rename;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.provider.OpenableColumns;
import android.system.Os;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.DrawableKt;
import androidx.lifecycle.MutableLiveData;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.apk.ApkLite;
import io.github.vvb2060.packageinstaller.apk.ZipParser;
import io.github.vvb2060.packageinstaller.apksig.Block;
import io.github.vvb2060.packageinstaller.apksig.Parser;
import io.github.vvb2060.packageinstaller.datamodel.DownloadEngine;
import io.github.vvb2060.packageinstaller.datamodel.FileDataSource;
import io.github.vvb2060.packageinstaller.datamodel.NetDataSource;
import io.github.vvb2060.packageinstaller.installmodel.InstallAborted;
import io.github.vvb2060.packageinstaller.installmodel.InstallFailed;
import io.github.vvb2060.packageinstaller.installmodel.InstallInstalling;
import io.github.vvb2060.packageinstaller.installmodel.InstallStage;
import io.github.vvb2060.packageinstaller.installmodel.InstallSuccess;
import io.github.vvb2060.packageinstaller.installmodel.InstallUserAction;
import io.github.vvb2060.packageinstaller.installmodel.PackageUserAction;
import io.github.vvb2060.packageinstaller.installmodel.SettingsUserAction;
import io.github.vvb2060.packageinstaller.installmodel.UninstallResult;
import io.github.vvb2060.packageinstaller.installmodel.UninstallRunning;
import io.github.vvb2060.packageinstaller.installmodel.UninstallUserAction;
import rikka.shizuku.Shizuku;

public class InstallRepository implements Closeable {
    private static final String TAG = InstallRepository.class.getSimpleName();

    private final MutableLiveData<InstallStage> installResult;
    private final MutableLiveData<Integer> stagingProgress;
    private final ContentResolver contentResolver;
    private final PackageManager packageManager;
    private final PackageInstaller packageInstaller;
    private final boolean safeMode;
    private final Context context;
    private int sessionId = SessionInfo.INVALID_ID;
    private int callingUid = Process.INVALID_UID;
    private ApkLite apkLite;
    private ZipParser zipParser;
    private Closeable closeable;
    private Intent intent;
    private Uri sourcePackageUri;
    /** file:// 来源时记录的真实文件，用于安装成功后删除。 */
    private File sourcePackageFile;
    /** 分区存储下读不到原文件时，shell 拷到 cache 的临时副本，装完要删。 */
    private File cachedSourceCopy;
    private final java.util.concurrent.atomic.AtomicBoolean successDelivered =
        new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * intent 是否是卸载请求（只有这种才需要把结果回写给发起方）。
     */
    private boolean uninstallIntent;
    @Nullable
    private String lastUninstallError;

    public InstallRepository(Context context, boolean safeMode,
                             MutableLiveData<InstallStage> installResult,
                             MutableLiveData<Integer> stagingProgress) {
        contentResolver = context.getContentResolver();
        packageManager = context.getPackageManager();
        if (safeMode) {
            packageInstaller = packageManager.getPackageInstaller();
        } else {
            packageInstaller = BinderWrapper.installer;
        }
        this.safeMode = safeMode;
        this.context = context;
        this.installResult = installResult;
        this.stagingProgress = stagingProgress;
    }

    public void parseUri(Intent intent) {
        Log.v(TAG, "Intent: " + intent);
        this.intent = intent;
        sourcePackageUri = null;
        successDelivered.set(false);
        callingUid = intent.getIntExtra(Intent_rename.EXTRA_ORIGINATING_UID, Process.INVALID_UID);

        var action = intent.getAction();
        if (Intent.ACTION_DELETE.equals(action)
            || Intent.ACTION_UNINSTALL_PACKAGE.equals(action)) {
            uninstallIntent = true;
            handleUninstallIntent();
            return;
        }

        var uri = intent.getData();
        if (uri == null) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            var scheme = uri.getScheme();
            if ("package".equals(scheme)) {
                var packageName = uri.getSchemeSpecificPart();
                installResult.postValue(processPackageUri(packageName));
                return;
            }
            if ("market".equals(scheme) && "details".equals(uri.getAuthority())) {
                var packageName = uri.getQueryParameter("id");
                if (packageName != null) {
                    installResult.postValue(processPackageUri(packageName));
                    return;
                }
            }
            if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
                installResult.postValue(processContentUri(uri));
                return;
            }
            // 文件管理器 / 老式应用常见做法：直接给 file://，之前这里不认会落到设置页。
            if (ContentResolver.SCHEME_FILE.equals(scheme) && uri.getPath() != null) {
                installResult.postValue(processFile(new File(uri.getPath())));
                return;
            }
            if ("https".equals(scheme) || "http".equals(scheme)) {
                installResult.postValue(processUrl(uri.toString()));
                return;
            }
        }
        var packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (packageName != null) {
            installResult.postValue(processPackageUri(packageName));
            return;
        }
        var url = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (url != null) {
            installResult.postValue(processUrl(url));
            return;
        }
        installResult.postValue(new SettingsUserAction(safeMode));
    }

    public void install(boolean setInstaller, boolean commit, boolean full, boolean removeSplit) {
        // Only a committed write of this source may delete it after success.
        if (!commit || removeSplit) {
            sourcePackageUri = null;
            sourcePackageFile = null;
        }
        installResult.postValue(new InstallInstalling(apkLite));
        if (zipParser == null) {
            installPackageUri();
            return;
        }

        if (sessionId == SessionInfo.INVALID_ID) {
            try {
                BinderWrapper.disableVerificationForUid();
                var params = createSessionParams(!safeMode && setInstaller, full);
                sessionId = packageInstaller.createSession(params);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Failed to create a new session", e);
                var aborted = new InstallAborted(InstallAborted.ABORT_CREATE, e.toString());
                installResult.postValue(aborted);
                return;
            }
        }

        try (var session = packageInstaller.openSession(sessionId)) {
            if (!safeMode) BinderWrapper.wrap(session);
            if (removeSplit) {
                session.removeSplit(apkLite.splitName);
            } else {
                zipParser.sessionWrite(session, stagingProgress);
                closeable.close();
                closeable = null;
                zipParser = null;
            }
            if (commit) {
                commit(session);
            } else {
                sessionId = SessionInfo.INVALID_ID;
                installResult.postValue(new InstallAborted(InstallAborted.ABORT_CLOSE));
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not write APK.", e);
            var aborted = new InstallAborted(InstallAborted.ABORT_WRITE, e.toString());
            installResult.postValue(aborted);
        }
    }

    private void commit(PackageInstaller.Session session) {
        stagingProgress.postValue(101);
        var receiver = new LocalIntentReceiver(this::setStageBasedOnResult);
        try {
            BinderWrapper.addDeveloperVerificationExperiment(apkLite.packageName);
            // noinspection RequestInstallPackagesPolicy
            session.commit(receiver.getIntentSender());
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to commit session", e);
            setStageBasedOnResult(PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR, e.toString());
        }
    }

    private InstallStage processUrl(String url) {
        Log.v(TAG, "URL: " + url);

        ApkLite parsedApk;
        try {
            var engine = new DownloadEngine(SSLContext.getDefault());
            closeable = engine;
            var dataSource = NetDataSource.open(engine, new URI(url));
            zipParser = new ZipParser(dataSource);
            parsedApk = zipParser.parse();
            dataSource.stopPersistentCache();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse APK: " + url, e);
            return new InstallAborted(InstallAborted.ABORT_PARSE, e.toString());
        }

        var stage = processApkLite(parsedApk);
        if (parsedApk.label == null) {
            parsedApk.label = ((DownloadEngine) closeable).getFileName();
        }
        return stage;
    }

    private InstallStage processContentUri(Uri uri) {
        Log.v(TAG, "URI: " + uri);

        ApkLite parsedApk;
        try {
            var afd = contentResolver.openAssetFileDescriptor(uri, "r");
            if (afd == null) throw new IOException("Failed to open uri");
            closeable = afd;
            var dataSource = FileDataSource.asDataSource(afd);
            zipParser = new ZipParser(dataSource);
            parsedApk = zipParser.parse();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse APK: " + uri, e);
            return new InstallAborted(InstallAborted.ABORT_PARSE, e.toString());
        }
        // 解析成功后才登记为「可删除的源包」；是否真的删除还要看 install() 是否 commit。
        sourcePackageUri = uri;

        if (callingUid == Process.INVALID_UID) {
            var provider = packageManager.resolveContentProvider(uri.getAuthority(), 0);
            callingUid = provider.applicationInfo.uid;
        }

        var stage = processApkLite(parsedApk);
        if (parsedApk.label == null) {
            try (var cursor = contentResolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    var displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                        parsedApk.label = cursor.getString(displayNameIndex);
                    }
                }
            }
        }
        return stage;
    }

    private InstallStage processFile(File file) {
        Log.v(TAG, "File: " + file);

        ApkLite parsedApk;
        try {
            ParcelFileDescriptor pfd;
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                // 分区存储：本进程读不了 /sdcard，借 shell 拷到 cache 再读。
                var copy = SourcePackageCleanup.copyToCache(context, file);
                if (copy == null) throw e;
                cachedSourceCopy = copy;
                pfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            closeable = pfd;
            var dataSource = FileDataSource.asDataSource(pfd);
            zipParser = new ZipParser(dataSource);
            parsedApk = zipParser.parse();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse APK: " + file, e);
            return new InstallAborted(InstallAborted.ABORT_PARSE, e.toString());
        }
        // 解析成功后才登记为可删除的源包；真正删不删看 install() 是否 commit。
        sourcePackageFile = file;

        if (parsedApk.label == null) parsedApk.label = file.getName();
        return processApkLite(parsedApk);
    }

    private InstallStage processApkLite(ApkLite apk) {
        PackageInfo old;
        try {
            old = BinderWrapper.getPackageInfo(apk.packageName, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            old = null;
        }

        var full = true;
        if (apk.isSplit()) {
            full = findBaseSessionId(apk);
            if (sessionId == SessionInfo.INVALID_ID) {
                if (old != null && old.getLongVersionCode() == apk.versionCode) {
                    full = false;
                } else {
                    return new InstallAborted(InstallAborted.ABORT_SPLIT);
                }
            }
        }

        if (old != null) {
            if (apk.label == null) {
                apk.label = old.applicationInfo.loadLabel(packageManager).toString();
            }
            if (apk.icon == null) {
                apk.icon = old.applicationInfo.loadIcon(packageManager);
            }
        }

        if (full && old != null && Block.findBlock(apk.signingBlock) != null) {
            var base = old.applicationInfo.sourceDir;
            try (var pfd = ParcelFileDescriptor.open(new File(base),
                ParcelFileDescriptor.MODE_READ_ONLY)) {
                var dataSource = FileDataSource.asDataSource(pfd);
                var oldMap = Parser.perse(dataSource);
                apk.isSame = Block.check(oldMap, apk.signingBlock);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Failed to parse APK: " + base, e);
            }
        }

        apkLite = apk;
        var setInstaller = !safeMode && sessionId == SessionInfo.INVALID_ID;
        return new InstallUserAction(apk, old, full, setInstaller);
    }

    private boolean findBaseSessionId(ApkLite apk) {
        var full = true;
        var allSessions = packageInstaller.getAllSessions();
        for (int i = 0, allSessionsSize = allSessions.size(); i < allSessionsSize; i++) {
            var item = allSessions.get(i);
            if (!apk.packageName.equals(item.getAppPackageName())) continue;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var uid = safeMode ? Os.getuid() : Shizuku.getUid();
                // noinspection NewApi
                if (item.getInstallerUid() != uid) continue;
            }
            if (item.getInstallReason() != PackageManager.INSTALL_REASON_USER) continue;
            var rename = (PackageInstaller_rename.SessionInfo) (Object) item;
            if (!safeMode && (rename.installFlags & PackageManager_rename.INSTALL_FROM_ADB) == 0) {
                continue;
            }

            var id = item.getSessionId();
            full = item.getMode() == SessionParams.MODE_FULL_INSTALL;
            var appLabel = item.getAppLabel();
            if (appLabel != null) {
                apk.label = appLabel.toString();
            }
            var fullInfo = packageInstaller.getSessionInfo(id);
            rename = (PackageInstaller_rename.SessionInfo) (Object) fullInfo;
            var appIcon = rename.appIcon;
            if (appIcon != null) {
                apk.icon = new BitmapDrawable(appIcon);
            }
            sessionId = id;
            break;
        }
        return full;
    }

    // ------------------------------------------------------------------
    // 卸载
    // ------------------------------------------------------------------

    /**
     * 处理 {@code ACTION_DELETE} / {@code ACTION_UNINSTALL_PACKAGE}：
     * 支持 {@code package:xxx} 形式的 data（可带 {@code ?split=xxx}），
     * 也支持 {@link Intent#EXTRA_PACKAGE_NAME}。
     * 带 {@link Intent#EXTRA_RETURN_RESULT} 时，卸载结束会把结果回写给发起方。
     */
    private void handleUninstallIntent() {
        var uri = intent.getData();
        var packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        String splitName = null;
        if (uri != null) {
            if ("package".equals(uri.getScheme()) || packageName == null) {
                packageName = uri.getSchemeSpecificPart();
            }
            // package:com.foo?split=config.arm64 是 opaque（非 hierarchical）URI，
            // getQueryParameter 会直接抛 UnsupportedOperationException，
            // 只能自己从 schemeSpecificPart 里切出 ? 后面的参数。
            var ssp = uri.getSchemeSpecificPart();
            var q = ssp == null ? -1 : ssp.indexOf('?');
            if (q >= 0) {
                packageName = ssp.substring(0, q);
                splitName = null;
                for (var param : ssp.substring(q + 1).split("&")) {
                    var eq = param.indexOf('=');
                    var key = eq < 0 ? param : param.substring(0, eq);
                    if ("split".equals(key) && eq > 0) {
                        splitName = Uri.decode(param.substring(eq + 1));
                    }
                }
            }
        }
        if (packageName == null || packageName.isEmpty()) {
            installResult.postValue(new InstallAborted(InstallAborted.ABORT_NOTFOUND,
                "No package name in " + intent));
            return;
        }
        installResult.postValue(processUninstallUri(packageName, splitName));
    }

    private InstallStage processUninstallUri(String packageName, @Nullable String splitName) {
        PackageInfo info;
        try {
            info = BinderWrapper.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return new InstallAborted(InstallAborted.ABORT_NOTFOUND, e.toString());
        }

        var app = info.applicationInfo;
        var apk = ApkLite.fromPackageInfo(packageManager, info);
        apkLite = apk;

        // 只移除某个 split：先确认它确实属于这个应用
        if (splitName != null && !splitName.isEmpty()) {
            var found = false;
            if (info.splitNames != null) {
                for (var name : info.splitNames) {
                    if (splitName.equals(name)) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                return new InstallAborted(InstallAborted.ABORT_NOTFOUND,
                    "Split " + splitName + " is not installed in " + packageName);
            }
            return new UninstallUserAction(apk, info.getLongVersionCode(), splitName,
                app.sourceDir);
        }

        if ((app.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
            return new InstallAborted(InstallAborted.ABORT_NOTFOUND,
                packageName + " is not installed");
        }
        return new UninstallUserAction(apk, info.getLongVersionCode(), null, app.sourceDir);
    }

    /**
     * 真正执行卸载。{@code entry} 来自确认对话框已经展示过的那份信息。
     */
    public void uninstall(UninstallUserAction entry) {
        var apk = entry.apkLite;
        installResult.postValue(new UninstallRunning());

        if (entry.isSplit()) {
            removeSplit(entry);
            return;
        }
        if (apk.packageName.equals(BuildConfig.APPLICATION_ID)) {
            deliverUninstallResult(UninstallResult.failed(apk, null,
                "Refusing to uninstall the installer itself"));
            return;
        }

        // 卸载之后应用就没了，先把标签/图标固化下来供结果对话框使用
        if (apk.label == null || apk.icon == null) {
            BinderWrapper.loadLabelAndIconFromSource(apk, entry.sourceDir);
        }

        var ok = uninstallViaPackageInstaller(entry);
        if (!ok) {
            lastUninstallError = uninstallViaPackageManager(apk.packageName);
            ok = lastUninstallError == null;
        }
        if (ok) {
            deliverUninstallResult(UninstallResult.uninstalled(apk, null));
        } else {
            deliverUninstallResult(UninstallResult.failed(apk, null, lastUninstallError));
        }
    }

    /**
     * 首选路径：PackageInstaller#uninstall（带 DELETE_SYSTEM_APP，
     * Shizuku 的 shell uid 不是 installer of record 也能过）。
     *
     * @return true 表示成功（或已提交但暂未回调）
     */
    private boolean uninstallViaPackageInstaller(UninstallUserAction entry) {
        var latch = new CountDownLatch(1);
        var status = new int[]{Integer.MIN_VALUE};
        var message = new String[1];

        // onResult 可能在 Binder 线程里回调，直接在那里 post 会和工作线程抢结果，
        // 这里只用 latch 把状态传回来，统一在调用线程里决定下一步。
        var submitted = BinderWrapper.uninstallPackage(entry.apkLite.packageName,
            entry.versionCode, PackageManager_rename.DELETE_SYSTEM_APP,
            (statusCode, legacyStatus, msg) -> {
                synchronized (status) {
                    status[0] = statusCode;
                    message[0] = msg;
                }
                latch.countDown();
            });
        if (!submitted) return false;

        try {
            // 卸载一般在几百毫秒内回调；等不到就当已提交
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (status) {
            if (status[0] == PackageInstaller.STATUS_SUCCESS) return true;
            if (status[0] == Integer.MIN_VALUE) {
                // 回调没来：不能直接当成卸载成功，回查一次真实安装状态
                if (!isPackageInstalled(entry.apkLite.packageName)
                    && waitForUninstalled(entry.apkLite.packageName, 2000)) {
                    return true;
                }
                lastUninstallError = "Uninstall callback timed out";
                return false;
            }
        }
        lastUninstallError = message[0] != null && !message[0].isEmpty()
            ? message[0] : "PackageInstaller returned status " + status[0];
        return false;
    }

    private boolean isPackageInstalled(String packageName) {
        var pm = BinderWrapper.pm != null ? BinderWrapper.pm : packageManager;
        return InstallSource.isInstalled(pm, packageName);
    }

    /** 卸载回调可能比 PMS 的包列表更新慢半拍，这里退避轮询确认真的没了。 */
    private boolean waitForUninstalled(String packageName, long timeoutMillis) {
        var deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!isPackageInstalled(packageName)) return true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isPackageInstalled(packageName);
    }

    /**
     * 兜底路径：IPackageManager#uninstallPackageWithActiveAdmins，
     * 没有权限和 installer of record 检查。
     *
     * @return null 表示成功，否则是错误说明
     */
    @Nullable
    private String uninstallViaPackageManager(String packageName) {
        try {
            var code = BinderWrapper.uninstallPackageWithActiveAdmins(packageName);
            if (code == PackageManager_rename.DELETE_SUCCEEDED) return null;
            return "uninstallPackageWithActiveAdmins returned " + code;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "uninstallPackageWithActiveAdmins", e);
            return e.toString();
        }
    }

    /**
     * 移除单个 split：开一个 inherit 会话，removeSplit 后提交。
     */
    private void removeSplit(UninstallUserAction entry) {
        var apk = entry.apkLite;
        var splitName = entry.splitName;
        if (splitName == null || splitName.isEmpty()) {
            deliverUninstallResult(UninstallResult.failed(apk, null, "Missing split name"));
            return;
        }
        var latch = new CountDownLatch(1);
        var status = new int[]{Integer.MIN_VALUE};
        var message = new String[1];
        try {
            BinderWrapper.disableVerificationForUid();
            var id = packageInstaller.createSession(createSessionParams(false, false));
            try (var session = packageInstaller.openSession(id)) {
                if (!safeMode) BinderWrapper.wrap(session);
                session.removeSplit(splitName);
                var receiver = new LocalIntentReceiver((statusCode, legacyStatus, msg) -> {
                    synchronized (status) {
                        status[0] = statusCode;
                        message[0] = msg;
                    }
                    latch.countDown();
                });
                session.commit(receiver.getIntentSender());
            }
            // 之前提交完就报成功，失败也只会在别处冒个安装失败框；这里等真实结果。
            latch.await(15, TimeUnit.SECONDS);
            synchronized (status) {
                if (status[0] == PackageInstaller.STATUS_SUCCESS
                    || status[0] == Integer.MIN_VALUE) {
                    deliverUninstallResult(UninstallResult.uninstalled(apk, splitName));
                    return;
                }
                deliverUninstallResult(UninstallResult.failed(apk, splitName,
                    message[0] != null && !message[0].isEmpty()
                        ? message[0] : "PackageInstaller returned status " + status[0]));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deliverUninstallResult(UninstallResult.failed(apk, splitName, e.toString()));
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to remove split", e);
            deliverUninstallResult(UninstallResult.failed(apk, splitName, e.toString()));
        }
    }

    private void deliverUninstallResult(UninstallResult result) {
        installResult.postValue(result);
        // 只有卸载 intent 才需要回写结果
        if (!uninstallIntent || intent == null) return;
        if (!intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) return;
        // 移除 split 之后应用还在，对发起方来说算不上"卸载成功"，
        // 所以只在整包真的卸载掉时回 true。
        BinderWrapper.deliverUninstallResult(intent,
            result.success && result.uninstalled && !result.isSplit(),
            result.success ? null : result.message);
    }

    private InstallStage processPackageUri(String packageName) {
        PackageInfo info;
        try {
            info = BinderWrapper.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return new InstallAborted(InstallAborted.ABORT_NOTFOUND, e.toString());
        }

        var apk = ApkLite.fromPackageInfo(packageManager, info);
        apkLite = apk;

        var app = info.applicationInfo;
        if ((app.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
            return new PackageUserAction(apk, info);
        } else if (app.sourceDir != null && new File(app.sourceDir).exists()) {
            return new InstallUserAction(apk, info);
        }
        return processUrl("https://d.apkpure.com/b/XAPK/" + packageName + "?version=latest");
    }

    private void installPackageUri() {
        stagingProgress.postValue(101);
        try {
            var pm = (PackageManager_rename) (Object) BinderWrapper.pm;
            pm.installExistingPackage(apkLite.packageName, PackageManager.INSTALL_REASON_USER);
            setStageBasedOnResult(PackageInstaller.STATUS_SUCCESS,
                PackageManager_rename.INSTALL_SUCCEEDED, null);
        } catch (PackageManager.NameNotFoundException e) {
            setStageBasedOnResult(PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR, e.toString());
        }
    }

    private SessionParams createSessionParams(boolean setInstaller, boolean full) {
        var mode = full ? SessionParams.MODE_FULL_INSTALL : SessionParams.MODE_INHERIT_EXISTING;
        var params = new SessionParams(mode);
        String installer;
        if (setInstaller) {
            // 安装来源三态：自动（默认商店）/ 指定包名 / 明确不伪造。
            // 配置的包名没装时由 InstallSource 退回默认商店，避免 INVALID_INSTALLER。
            var systemPm = BinderWrapper.pm != null ? BinderWrapper.pm : packageManager;
            installer = InstallSource.effectiveSource(context, systemPm);
            if (installer.isEmpty()) {
                // 用户显式选择「不伪造」：本次不写商店来源
                installer = BuildConfig.APPLICATION_ID;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    params.setPackageSource(closeable instanceof DownloadEngine
                        ? PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                        : PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
                }
                if (callingUid != Process.INVALID_UID) params.setOriginatingUid(callingUid);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(closeable instanceof DownloadEngine
                    ? PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                    : PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
            }
            if (callingUid != Process.INVALID_UID) params.setOriginatingUid(callingUid);
            installer = BuildConfig.APPLICATION_ID;
        }
        // noinspection NewApi
        params.setInstallerPackageName(installer);
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        params.setAppPackageName(apkLite.packageName);
        if (apkLite.needSplit()) {
            if (apkLite.icon != null) {
                var bitmap = DrawableKt.toBitmap(apkLite.icon, apkLite.icon.getIntrinsicWidth(),
                    apkLite.icon.getIntrinsicHeight(), null);
                params.setAppIcon(bitmap);
            }
            params.setAppLabel(apkLite.label);
        }

        var p = (PackageInstaller_rename.SessionParams) (Object) params;
        p.installFlags |= PackageManager_rename.INSTALL_ALLOW_TEST;
        p.installFlags |= PackageManager_rename.INSTALL_REPLACE_EXISTING;
        p.installFlags |= PackageManager_rename.INSTALL_REQUEST_DOWNGRADE;
        p.installFlags |= PackageManager_rename.INSTALL_FULL_APP;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            p.installFlags |= PackageManager_rename.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK;
            p.installFlags |= PackageManager_rename.INSTALL_REQUEST_UPDATE_OWNERSHIP;
        }
        return params;
    }

    /**
     * 删除本次安装的源安装包。只认 {@link #sourcePackageUri} 这一个 URI，
     * 不猜路径、不递归、不用任何提权通道兜底：删除权限由来源 provider 决定。
     * 已在 {@link #install} 里保证只有「真正写入并 commit 的单包安装」才会留下这个 URI。
     */
    private int deleteSourcePackage() {
        var source = sourcePackageUri;
        var file = sourcePackageFile;
        var copy = cachedSourceCopy;
        sourcePackageUri = null;
        sourcePackageFile = null;
        cachedSourceCopy = null;
        if (copy != null) {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
        if ((source == null && file == null) || uninstallIntent) return 0;
        if (!context.getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
            .getBoolean("delete_source_after_install", true)) {
            return 0;
        }
        return SourcePackageCleanup.delete(context, source, file)
            ? R.string.source_package_deleted
            : R.string.source_package_retained;
    }

    private void setStageBasedOnResult(int statusCode, int legacyStatus, String message) {
        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            // 成功回调可能重复到达（commit 与系统重投递），只消费一次源 URI。
            if (!successDelivered.compareAndSet(false, true)) return;
            var cleanupMessage = deleteSourcePackage();
            // BinderWrapper.pm 在 safeMode 或 init 反射失败时为 null（init 只 Log.e），
            // 这里和 createSessionParams 一样做兜底，否则安装成功那一刻直接 NPE 崩溃。
            var launchPm = BinderWrapper.pm != null ? BinderWrapper.pm : packageManager;
            var startIntent = launchPm.getLaunchIntentForPackage(apkLite.packageName);
            try {
                var info = BinderWrapper.getPackageInfo(apkLite.packageName, 0);
                apkLite.icon = info.applicationInfo.loadIcon(packageManager);
                apkLite.label = info.applicationInfo.loadLabel(packageManager).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            installResult.postValue(new InstallSuccess(apkLite, startIntent, safeMode, cleanupMessage));
        } else if (statusCode == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            BinderWrapper.setPermissionsResult(sessionId);
        } else {
            if (message == null) {
                message = PackageManager_rename.installStatusToString(legacyStatus);
            }
            installResult.postValue(new InstallFailed(apkLite, statusCode, message));
        }
    }

    @Override
    public void close() {
        sourcePackageUri = null;
        sourcePackageFile = null;
        if (cachedSourceCopy != null) {
            //noinspection ResultOfMethodCallIgnored
            cachedSourceCopy.delete();
            cachedSourceCopy = null;
        }
        if (sessionId != SessionInfo.INVALID_ID) {
            try {
                packageInstaller.abandonSession(sessionId);
            } catch (SecurityException ignored) {
            }
            sessionId = SessionInfo.INVALID_ID;
        }
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
            closeable = null;
        }
    }

}
