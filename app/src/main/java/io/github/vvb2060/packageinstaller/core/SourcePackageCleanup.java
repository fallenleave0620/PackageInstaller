package io.github.vvb2060.packageinstaller.core;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.Locale;

import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 删除本次安装的源安装包。
 *
 * <p>真实场景里「从别的应用发起安装」拿到的授权千差万别：
 * 有的只给 {@code FLAG_GRANT_READ_URI_PERMISSION}（provider 拒绝 delete），
 * 有的干脆传 {@code file://}（没有 provider 可删）。所以这里按能力分级：
 * <ol>
 *   <li>content:// 且 provider 允许 → 直接 {@code deleteDocument}/{@code delete}</li>
 *   <li>否则把 URI 还原成真实路径 → 先用本进程删，再退到 Shizuku 的 shell 身份 {@code rm -f}</li>
 * </ol>
 *
 * <p>安全约束：只删**单个普通文件**，且后缀必须是安装包；路径只能由 URI 精确推导，
 * 不猜文件名、不删目录、不递归。
 */
public final class SourcePackageCleanup {
    private static final String TAG = "SourcePackageCleanup";

    private static final String AUTHORITY_EXTERNAL_STORAGE =
        "com.android.externalstorage.documents";
    private static final String AUTHORITY_DOWNLOADS =
        "com.android.providers.downloads.documents";

    private SourcePackageCleanup() {}

    /**
     * @param uri  本次安装的源 content URI，可能为 null
     * @param file 本次安装的源文件（file:// 或已解析出的路径），可能为 null
     * @return true 表示确认已删除
     */
    public static boolean delete(Context context, Uri uri, File file) {
        if (deleteViaProvider(context, uri)) return true;
        return deleteFile(resolvePath(context, uri, file));
    }

    /**
     * 分区存储下本进程读不了 /sdcard 上的文件（EACCES）。让 shell 用 {@code cat} 把内容
     * 通过管道吐过来，由本进程写进自己的私有 cache —— 这样目标文件的属主是应用自己，
     * 绕开「shell 写出来的文件 app 仍读不了」的问题。
     *
     * @return 拷贝出的临时文件；失败返回 null
     */
    public static File copyToCache(Context context, File source) {
        var path = sharedPackagePath(source.getAbsolutePath());
        if (path == null) return null;
        var target = new File(context.getCacheDir(), "source-" + System.nanoTime() + ".apk");
        try {
            var binder = Shizuku.getBinder();
            if (binder == null) return null;
            var service = IShizukuService.Stub.asInterface(binder);
            var remote = service.newProcess(new String[]{"cat", "--", path}, null, null);
            if (remote == null) return null;
            var pipe = remote.getInputStream();
            try (var in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe);
                 var out = new java.io.FileOutputStream(target)) {
                var buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            var code = remote.waitFor();
            remote.destroy();
            if (code == 0 && target.isFile() && target.length() > 0) return target;
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        } catch (Exception e) {
            Log.w(TAG, "shell cat failed", e);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        }
    }

    private static boolean deleteViaProvider(Context context, Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return false;
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                if (DocumentsContract.deleteDocument(context.getContentResolver(), uri)) return true;
            } else if (context.getContentResolver().delete(uri, null, null) > 0) {
                return true;
            }
        } catch (Exception e) {
            // 只给了读授权时会走到这里，继续尝试按路径删。
            Log.d(TAG, "provider refused delete: " + e.getClass().getSimpleName());
        }
        return false;
    }

    /** 把 URI 还原成真实路径；无法精确推导时返回 null。 */
    private static String resolvePath(Context context, Uri uri, File file) {
        if (file != null) return sharedPackagePath(file.getAbsolutePath());
        if (uri == null) return null;

        var scheme = uri.getScheme();
        if (ContentResolver.SCHEME_FILE.equals(scheme)) return uri.getPath();
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme)) return null;

        var authority = uri.getAuthority();
        var documentId = documentId(uri);

        if (AUTHORITY_EXTERNAL_STORAGE.equals(authority) && documentId != null) {
            var separator = documentId.indexOf(':');
            if (separator > 0) {
                var volume = documentId.substring(0, separator);
                var relative = documentId.substring(separator + 1);
                var base = "primary".equalsIgnoreCase(volume)
                    ? Environment.getExternalStorageDirectory().getAbsolutePath()
                    : "/storage/" + volume;
                return base + "/" + relative;
            }
        }

        if (AUTHORITY_DOWNLOADS.equals(authority) && documentId != null) {
            if (documentId.startsWith("raw:")) return documentId.substring(4);
            // Only msf IDs are MediaStore IDs. Legacy download IDs belong to another table.
            if (documentId.startsWith("msf:")) {
                return queryData(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    documentId.substring(4));
            }
        }

        if (uri.toString().startsWith("content://media/")) {
            return queryData(context, uri, null);
        }

        return null;
    }

    private static String documentId(Uri uri) {
        try {
            return DocumentsContract.getDocumentId(uri);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String queryData(Context context, Uri uri, String id) {
        var projection = new String[]{MediaStore.MediaColumns.DATA};
        var selection = id != null ? "_id=?" : null;
        var args = id != null ? new String[]{id} : null;
        try (var cursor = context.getContentResolver()
            .query(uri, projection, selection, args, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                var column = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (column != -1) return cursor.getString(column);
            }
        } catch (Exception e) {
            Log.d(TAG, "cannot resolve data column: " + e.getClass().getSimpleName());
        }
        return null;
    }

    private static boolean deleteFile(String path) {
        path = sharedPackagePath(path);
        if (path == null) return false;

        var file = new File(path);
        // 只处理安装包文件本身：不删目录、不删非安装包。
        if (!file.isFile() || !isInstallPackage(file.getName())) return false;

        try {
            if (file.delete()) return true;
        } catch (Exception e) {
            Log.d(TAG, "direct delete failed: " + e.getClass().getSimpleName());
        }

        // 外部存储上别人的文件本进程删不掉，借 Shizuku 的 shell 身份删这一个文件。
        try {
            var binder = Shizuku.getBinder();
            if (binder == null) return false;
            var service = IShizukuService.Stub.asInterface(binder);
            var remote = service.newProcess(new String[]{"rm", "-f", "--", path}, null, null);
            if (remote == null) return false;
            var code = remote.waitFor();
            remote.destroy();
            return code == 0 && !file.exists();
        } catch (Exception e) {
            Log.w(TAG, "shell rm failed", e);
            return false;
        }
    }

    /** Canonicalize before privileged access; never touch installed APKs or private app data. */
    static String sharedPackagePath(String path) {
        if (path == null) return null;
        try {
            var file = new File(path).getCanonicalFile();
            var resolved = file.getPath();
            var root = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (!resolved.startsWith(root + "/") || !isInstallPackage(file.getName())) return null;
            var relative = resolved.substring(root.length() + 1);
            if (relative.equals("Android") || relative.startsWith("Android/")) return null;
            return resolved;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static boolean isInstallPackage(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".apks")
            || lower.endsWith(".xapk") || lower.endsWith(".apkm");
    }
}
