package android.content.res;

import android.content.res.loader.AssetsProvider;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;
import java.io.IOException;

public class ApkAssets {

    public static final int PROPERTY_SYSTEM = 1 << 0;
    public static final int PROPERTY_DYNAMIC = 1 << 1;

    public static native ApkAssets loadFromFd(FileDescriptor fd, String friendlyName,
                                              long offset, long length, int flags,
                                              AssetsProvider assets) throws IOException;

    public native String getAssetPath();

    public native XmlResourceParser openXml(String fileName) throws IOException;

    public native void close();
}
