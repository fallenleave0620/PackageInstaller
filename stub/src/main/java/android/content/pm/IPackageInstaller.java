package android.content.pm;

import android.content.IntentSender;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import androidx.annotation.RequiresApi;

import java.util.List;

/**
 * IPackageInstaller 的隐藏 API 声明。
 * <p>
 * Binder 只按事务号（方法在接口中的<b>位置</b>）分发调用，所以这里的声明顺序
 * 必须与系统里的 IPackageInstaller.aidl 完全一致（android-15 / android-16 均已核对）：
 * <pre>
 * createSession, updateSessionAppIcon, updateSessionAppLabel, abandonSession, openSession,
 * getSessionInfo, getAllSessions, getMySessions, getStagedSessions, registerCallback,
 * unregisterCallback, uninstall, uninstallExistingPackage, installExistingPackage,
 * setPermissionsResult, bypassNextStagedInstallerCheck, bypassNextAllowedApexUpdateCheck,
 * disableVerificationForUid, ...
 * </pre>
 * 之前缺了 uninstall / uninstallExistingPackage / installExistingPackage，
 * 导致 disableVerificationForUid 被发到事务号 12，实际打到 uninstall 上。
 */
public interface IPackageInstaller extends IInterface {

    int createSession(PackageInstaller_rename.SessionParams params, String installerPackageName,
                      String installerAttributionTag, int userId) throws RemoteException;

    void updateSessionAppIcon(int sessionId, Bitmap appIcon) throws RemoteException;

    void updateSessionAppLabel(int sessionId, String appLabel) throws RemoteException;

    void abandonSession(int sessionId) throws RemoteException;

    IPackageInstallerSession openSession(int sessionId) throws RemoteException;

    PackageInstaller_rename.SessionInfo getSessionInfo(int sessionId) throws RemoteException;

    void uninstall(VersionedPackage versionedPackage, String callerPackageName, int flags,
                   IntentSender statusReceiver, int userId) throws RemoteException;

    void uninstallExistingPackage(VersionedPackage versionedPackage, String callerPackageName,
                                  IntentSender statusReceiver, int userId) throws RemoteException;

    void installExistingPackage(String packageName, int installFlags, int installReason,
                                IntentSender statusReceiver, int userId,
                                List<String> whiteListedPermissions) throws RemoteException;

    void setPermissionsResult(int sessionId, boolean accepted) throws RemoteException;

    void bypassNextStagedInstallerCheck(boolean value) throws RemoteException;

    void bypassNextAllowedApexUpdateCheck(boolean value) throws RemoteException;

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    void disableVerificationForUid(int uid) throws RemoteException;

    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    void addDeveloperVerificationExperiment(String packageName, int verificationPolicy,
                                            int[] results) throws RemoteException;

    abstract class Stub extends Binder implements IPackageInstaller {

        public static IPackageInstaller asInterface(IBinder binder) {
            throw new UnsupportedOperationException();
        }
    }
}
