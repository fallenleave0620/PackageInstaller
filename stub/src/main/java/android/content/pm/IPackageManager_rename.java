package android.content.pm;

/**
 * 隐藏 API {@code IPackageManager.uninstallPackageWithActiveAdmins} 的事务号常量。
 * <p>
 * Binder 按方法在 AIDL 里的位置分发，而这个位置取决于接口里排在它前面的方法数量。
 * 系统的 {@code IPackageManager.aidl} 在 android-15（API 35）之后没有新增/删除
 * 隐藏方法，所以下面这个值在 15 / 16 / 17 上都一致。作为兜底路径使用，
 * 主路径仍然是 {@code PackageInstaller#uninstall}。
 */
public final class IPackageManager_rename {

    public static final String DESCRIPTOR = "android.content.pm.IPackageManager";

    /**
     * {@code uninstallPackageWithActiveAdmins(String packageName)} 的事务号。
     * <p>
     * 取值为 {@code FIRST_CALL_TRANSACTION + N}，N 是它在 AIDL 里的下标。
     */
    public static final int TRANSACTION_uninstallPackageWithActiveAdmins = 0x00000005;

    private IPackageManager_rename() {
    }
}
