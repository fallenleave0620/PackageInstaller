package android.app;

import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IActivityManager extends IInterface {
    int startActivityAsUser(IApplicationThread caller, String callingPackage,
                            Intent intent, String resolvedType, IBinder resultTo, String resultWho,
                            int requestCode, int flags, ProfilerInfo profilerInfo,
                            Bundle options, int userId) throws RemoteException;

    /**
     * @hide 直接把一个 PendingIntent/IntentSender 发出去。
     * 公开的 {@code IntentSender#sendIntent} 会先做 {@code resolveTypeIfNeeded}，
     * 需要一个 Context；这里走底层 AIDL，就能在没有 Context 的地方回写卸载结果。
     */
    int sendIntentSender(IApplicationThread caller, IIntentSender target, IBinder whitelistToken,
                         int code, Intent intent, String resolvedType,
                         IIntentReceiver finishedReceiver, String requiredPermission,
                         Bundle options) throws RemoteException;

    abstract class Stub extends Binder implements IActivityManager {
        public static IActivityManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
