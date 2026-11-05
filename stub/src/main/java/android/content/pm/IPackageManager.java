package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IPackageManager extends IInterface {

    IPackageInstaller getPackageInstaller() throws RemoteException;

    void clearPackagePreferredActivities(String packageName) throws RemoteException;

    /**
     * 隐藏 API：绕过权限检查直接走删除流程，返回 {@code PackageManager.DELETE_*} 结果码。
     * <p>
     * 这里没有按真实 AIDL 顺序完整声明（前面还有几十个方法），Binder 按位置分发，
     * 所以它的事务号对不上——只作编译期占位，不要真正调用。
     */
    int uninstallPackageWithActiveAdmins(String packageName) throws RemoteException;

    abstract class Stub extends Binder implements IPackageManager {

        public static IPackageManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
