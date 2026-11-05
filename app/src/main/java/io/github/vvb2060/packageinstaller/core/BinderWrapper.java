package io.github.vvb2060.packageinstaller.core;

import android.app.IActivityManager;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.IPackageManager_rename;
import android.content.pm.PackageManager_rename;
import android.content.pm.VersionedPackage;
import android.content.res.AssetManager;
import android.content.res.ApkAssets;
import android.content.res.Resources;
import android.os.Build;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Settings;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.lsposed.hiddenapibypass.LSPass;

import java.io.File;
import java.io.IOException;

import org.xmlpull.v1.XmlPullParserException;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.apk.ApkLite;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class BinderWrapper {
    private static final String TAG = BinderWrapper.class.getSimpleName();

    /**
     * 卸载结果 Intent 里附带"是否成功"的 key（和系统 ACTION_UNINSTALL_PACKAGE
     * 约定一致：{@code "android.intent.extra.UNINSTALLED"}）。
     */
    public static final String EXTRA_UNINSTALLED = "android.intent.extra.UNINSTALLED";
    private static boolean initialized;

    private static IActivityManager am;
    private static IDeviceIdleController deviceIdle;
    private static IPackageInstaller pi;
    static PackageManager pm;
    static PackageInstaller installer;

    static {
        LSPass.setHiddenApiExemptions("");
    }

    public static void init(Context context) {
        if (initialized) return;

        var activity = SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE);
        am = IActivityManager.Stub.asInterface(new ShizukuBinderWrapper(activity));
        var deviceidle = SystemServiceHelper.getSystemService("deviceidle");
        deviceIdle = IDeviceIdleController.Stub.asInterface(new ShizukuBinderWrapper(deviceidle));

        try {
            deletePmRef(context);
            pm = context.getPackageManager();
            deletePmRef(context);

            deleteInstallerRef();
            installer = pm.getPackageInstaller();
            deleteInstallerRef();

            var field = pm.getClass().getDeclaredField("mPM");
            field.setAccessible(true);
            var mPM = (IPackageManager) field.get(pm);
            var wrapper = new ShizukuBinderWrapper(mPM.asBinder());
            field.set(pm, IPackageManager.Stub.asInterface(wrapper));

            // noinspection SoonBlockedPrivateApi
            field = installer.getClass().getDeclaredField("mInstaller");
            field.setAccessible(true);
            var mInstaller = (IPackageInstaller) field.get(installer);
            wrapper = new ShizukuBinderWrapper(mInstaller.asBinder());
            pi = IPackageInstaller.Stub.asInterface(wrapper);
            field.set(installer, pi);

            initialized = true;
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "init", e);
        }
    }

    private static void deleteInstallerRef() throws ReflectiveOperationException {
        var field = pm.getClass().getDeclaredField("mInstaller");
        field.setAccessible(true);
        field.set(pm, null);
    }

    private static void deletePmRef(Context context) throws ReflectiveOperationException {
        var contextImpl = context;
        while (contextImpl instanceof ContextWrapper contextWrapper) {
            contextImpl = contextWrapper.getBaseContext();
        }
        var field = contextImpl.getClass().getDeclaredField("mPackageManager");
        field.setAccessible(true);
        field.set(contextImpl, null);
    }

    private static void wrapGlobalSettings(Runnable callback) {
        try {
            // noinspection SoonBlockedPrivateApi,JavaReflectionMemberAccess
            var field = Settings.Global.class.getDeclaredField("sProviderHolder");
            field.setAccessible(true);
            var holder = field.get(null);
            assert holder != null;

            field = holder.getClass().getDeclaredField("mContentProvider");
            field.setAccessible(true);
            var provider = field.get(holder);
            assert provider != null;

            field = provider.getClass().getDeclaredField("mRemote");
            field.setAccessible(true);
            var binder = (IBinder) field.get(provider);
            assert binder != null;

            field.set(provider, new ShizukuBinderWrapper(binder));
            callback.run();
            field.set(provider, binder);
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "wrapGlobalSettings", e);
        }
    }

    public static void wrap(PackageInstaller.Session session) {
        try {
            // noinspection SoonBlockedPrivateApi
            var field = session.getClass().getDeclaredField("mSession");
            field.setAccessible(true);
            var mSession = (IPackageInstallerSession) field.get(session);
            assert mSession != null;
            var wrapper = new ShizukuBinderWrapper(mSession.asBinder());
            field.set(session, IPackageInstallerSession.Stub.asInterface(wrapper));
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "wrap", e);
        }
    }

    public static void disableAdbVerify(Context context) {
        String name = "verifier_verify_adb_installs";
        var included = Settings.Global.getInt(context.getContentResolver(), name, 1) != 0;
        if (included) {
            wrapGlobalSettings(() -> {
                var contextWrapper = new ShizukuContext(context);
                var contentResolver = new ContentResolver(contextWrapper) {
                };
                Settings.Global.putInt(contentResolver, name, 0);
            });
        }
    }

    public static void startActivity(Intent intent) {
        int userId = Os.getuid() / 100000;
        try {
            am.startActivityAsUser(null, "com.android.shell", intent, intent.getType(),
                null, null, 0, 0, null, null, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * 以 shell 身份向 {@code packageName} 发一个显式 intent。
     * 用于"装完接着去卸载"这类接力：本应用自己的 activity 已经在退出流程里，
     * 让系统直接转交给下一跳，比在本进程里切 stage 稳。
     */
    public static void startActivityForPackage(String packageName, Intent intent) {
        intent.setPackage(packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    public static PackageInfo getPackageInfo(String packageName, int flags)
        throws PackageManager.NameNotFoundException {
        var matchAll = PackageManager_rename.MATCH_KNOWN_PACKAGES
            | PackageManager_rename.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS
            | PackageManager_rename.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES;
        var pkg = new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST);
        return pm.getPackageInfo(pkg, flags | matchAll);
    }

    /**
     * 通过 PackageInstaller 卸载（或移除 split）。调用方的 Shizuku uid 是 shell，
     * 通常不是该应用的 installer of record，所以必须带 {@code DELETE_SYSTEM_APP}，
     * 否则会被 InstallSource 的匹配检查拒绝。
     * <p>
     * 注意：{@code PackageInstaller#uninstall} 会把标志位转交给
     * {@code IPackageInstaller#uninstall}，事务号必须在 {@link IPackageInstaller}
     * 里按 AIDL 顺序声明对（见该接口的注释）。
     *
     * @param flags {@link PackageManager_rename#DELETE_SYSTEM_APP} 等
     * @return true 表示请求已发出，结果通过 callback 异步回调
     */
    public static boolean uninstallPackage(String packageName, long versionCode, int flags,
                                           @Nullable LocalIntentReceiver.ResultObserver callback) {
        var receiver = new LocalIntentReceiver(callback);
        var versionedPackage = versionCode < 0
            ? new VersionedPackage(packageName, PackageManager_rename.VERSION_CODE_HIGHEST)
            : new VersionedPackage(packageName, versionCode);
        try {
            // noinspection MissingPermission NewApi WrongConstant
            installer.uninstall(versionedPackage, flags, receiver.getIntentSender());
            return true;
        } catch (SecurityException | IllegalArgumentException | UnsupportedOperationException
                 | IllegalStateException e) {
            Log.w(TAG, "Failed to uninstall package", e);
            return false;
        }
    }

    /**
     * 应用当初就是以 ACTION_DELETE / ACTION_UNINSTALL_PACKAGE 拉起的：
     * 以调用方（shell，也就是 Shizuku）的身份把结果回写给它。
     * <p>
     * 不能用 {@code Activity#setResult}：本应用移除了 {@code appComponentFactory}
     * （见 AndroidManifest），拿不到自己 activity 的实例。
     * <p>
     * 实现上直接走 {@code IActivityManager.sendIntentSender}，因为
     * {@code IntentSender.sendIntent(...)} 内部是 {@code intent.resolveTypeIfNeeded(
     * context.getContentResolver())}，context 传 null 会 NPE。
     *
     * @return true 表示已经交给系统送达
     */
    public static boolean deliverUninstallResult(Intent intent, boolean success,
                                                 @Nullable String message) {
        // EXTRA_RESULT_RECEIVER 里放的是发起方给的 PendingIntent 解出来的 IntentSender
        var receiver = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER, IntentSender.class)
            : (IntentSender) intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER);
        if (receiver == null) {
            Log.d(TAG, "No result receiver in " + intent);
            return false;
        }

        var result = new Intent();
        // 结果码就是系统的 resultCode：Activity.RESULT_OK(=PackageManager.DELETE_SUCCEEDED)
        // 或 PackageManager.DELETE_FAILED_*；额外再塞一份布尔值方便调用方判断。
        result.putExtra(EXTRA_UNINSTALLED, success);
        if (message != null) result.putExtra(Intent.EXTRA_TEXT, message);

        try {
            // IntentSender 是 final 的，只能反射拿到它内部的 IIntentSender 和
            // whitelist token，直接走 IActivityManager.sendIntentSender。
            // （IntentSender#sendIntent(ctx, ...) 内部会 ctx.getContentResolver()，ctx 不能为 null）
            var targetField = IntentSender.class.getDeclaredField("mTarget");
            targetField.setAccessible(true);
            var target = (IIntentSender) targetField.get(receiver);
            @SuppressWarnings("SoonBlockedPrivateApi")
            var tokenField = IntentSender.class.getDeclaredField("mWhitelistToken");
            tokenField.setAccessible(true);
            var token = (IBinder) tokenField.get(receiver);

            // caller 传 null 即可：系统侧只用它来校验/回收，不影响发送。
            var code = am.sendIntentSender(null, target, token, success ? 1 : -1, result,
                null, null, null, null);
            if (code < 0) {
                Log.w(TAG, "sendIntentSender returned " + code);
                return false;
            }
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to deliver uninstall result", e);
            return false;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Failed to access IntentSender internals", e);
            return false;
        }
    }

    /**
     * 通过 IPackageManager.uninstallPackageWithActiveAdmins 卸载。
     * 该方法没有任何权限/installer of record 检查，只依赖调用方本身的 uid（shell），
     * 作为 PackageInstaller 路径失败时的兜底。
     *
     * @return {@link PackageManager_rename#DELETE_SUCCEEDED} 或 DELETE_FAILED_* 错误码
     */
    public static int uninstallPackageWithActiveAdmins(String packageName) throws RemoteException {
        // PackageManager 里没有对外暴露这个方法；它挂在内部的 IPackageManager 代理上，
        // 而那个代理在 init() 里已经被换成 Shizuku 包装过的 binder，直接 transact 即可。
        var data = Parcel.obtain();
        var reply = Parcel.obtain();
        try {
            var field = pm.getClass().getDeclaredField("mPM");
            field.setAccessible(true);
            var ipm = (IPackageManager) field.get(pm);

            data.writeInterfaceToken(IPackageManager_rename.DESCRIPTOR);
            data.writeString(packageName);
            // 事务号从接口的真实 AIDL 顺序里数出来（见 IPackageManager_rename 的说明）
            if (!ipm.asBinder().transact(IPackageManager_rename.TRANSACTION_uninstallPackageWithActiveAdmins,
                data, reply, 0)) {
                throw new RemoteException("transact failed");
            }
            reply.readException();
            return reply.readInt();
        } catch (ReflectiveOperationException e) {
            throw new RemoteException("mPM: " + e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * 卸载之后应用就没了，提前把标签/图标从 APK 里读出来固化住，
     * 免得结果对话框只剩一个裸包名。
     */
    public static void loadLabelAndIconFromSource(ApkLite apkLite, String sourceDir) {
        if (sourceDir == null) return;
        try (var pfd = ParcelFileDescriptor.open(new File(sourceDir),
            ParcelFileDescriptor.MODE_READ_ONLY)) {
            // AssetManager 的构造器是 @hide 的，用 newInstance 反射创建；
            // 把 APK 作为 AssetManager 的一个 ApkAssets 挂进去，Resources 就能解析它自己的资源。
            var ctor = AssetManager.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            var am = ctor.newInstance();
            var assets = ApkAssets.loadFromFd(pfd.getFileDescriptor(), sourceDir, 0, 0,
                ApkAssets.PROPERTY_DYNAMIC, null);
            AssetManager.class.getMethod("addApkAssets", ApkAssets.class).invoke(am, assets);
            var res = new Resources(am, null, null);
            try (var parser = assets.openXml("AndroidManifest.xml")) {
                var info = ApkLite.fromAndroidManifest(parser, res);
                if (info.label != null) apkLite.label = info.label;
                if (info.icon != null) apkLite.icon = info.icon;
            }
        } catch (IOException | XmlPullParserException | RuntimeException
                 | ReflectiveOperationException | LinkageError e) {
            Log.w(TAG, "Failed to load label from " + sourceDir, e);
        }
    }

    public static void setPackageEnabled(String packageName, boolean enabled) {
        var newState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        try {
            pm.setApplicationEnabledSetting(packageName, newState, 0);
        } catch (SecurityException e) {
            Log.w(TAG, "Failed to set package enabled state", e);
        }
    }

    public static void addPowerSaveWhitelistApp() {
        try {
            int userId = Os.getuid() / 100000;
            deviceIdle.addPowerSaveTempWhitelistApp(BuildConfig.APPLICATION_ID,
                1000, userId, 316, "shell");
            deviceIdle.addPowerSaveWhitelistApp(BuildConfig.APPLICATION_ID);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public static void removePowerSaveWhitelistApp() {
        try {
            deviceIdle.removePowerSaveWhitelistApp(BuildConfig.APPLICATION_ID);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public static void disableVerificationForUid() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pi.disableVerificationForUid(Os.getuid());
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public static void addDeveloperVerificationExperiment(String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                pi.addDeveloperVerificationExperiment(packageName, 3, new int[]{1});
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public static void setPermissionsResult(int sessionId) {
        try {
            pi.setPermissionsResult(sessionId, true);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private static final class ShizukuContext extends ContextWrapper {
        ShizukuContext(Context context) {
            super(context);
        }

        @NonNull
        @Override
        @RequiresApi(Build.VERSION_CODES.S)
        public AttributionSource getAttributionSource() {
            var builder = new AttributionSource.Builder(Shizuku.getUid())
                .setPackageName("com.android.shell");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                builder.setPid(Process.INVALID_PID);
            }
            return builder.build();
        }

        @NonNull
        @Override
        public String getOpPackageName() {
            return "com.android.shell";
        }
    }
}
