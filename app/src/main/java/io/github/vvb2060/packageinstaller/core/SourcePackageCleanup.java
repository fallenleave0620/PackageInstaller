package io.github.vvb2060.packageinstaller.core;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

/** Deletes only the exact source URI supplied for this installation. */
public final class SourcePackageCleanup {
    private SourcePackageCleanup() {}

    public static boolean delete(Context context, Uri source) {
        if (!"content".equals(source.getScheme())) return false;
        try {
            if (DocumentsContract.isDocumentUri(context, source)) {
                return DocumentsContract.deleteDocument(context.getContentResolver(), source);
            }
            // No filesystem guessing or privileged fallback: the provider enforces URI grants.
            return context.getContentResolver().delete(source, null, null) > 0;
        } catch (Exception e) {
            Log.w("SourcePackageCleanup", "Source package retained: " + e.getClass().getSimpleName());
            return false;
        }
    }
}
