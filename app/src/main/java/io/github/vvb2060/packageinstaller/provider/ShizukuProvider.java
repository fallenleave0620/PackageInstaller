package io.github.vvb2060.packageinstaller.provider;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.vvb2060.packageinstaller.BuildConfig;
import io.github.vvb2060.packageinstaller.core.BinderWrapper;
import io.github.vvb2060.packageinstaller.core.PreferredActivity;
import moe.shizuku.api.BinderContainer;
import rikka.shizuku.Shizuku;
import rikka.sui.Sui;

public class ShizukuProvider extends ContentProvider {
    private static final String TAG = ShizukuProvider.class.getSimpleName();
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private static final String ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER";
    private static final String PERMISSION_MANAGER = "moe.shizuku.manager.permission.MANAGER";

    @Override
    public boolean onCreate() {
        Shizuku.addBinderReceivedListener(() -> {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                var context = getContext();
                BinderWrapper.init(context);
                PreferredActivity.set(context.getPackageManager());
            }
        });
        // Sui 要无条件 init：它负责把 Sui 的 binder 塞进 Shizuku 的库并做自授权，
        // 只在非 Sui 环境调用会在 Sui 永久授权后依然拿不到 binder。
        Sui.init(BuildConfig.APPLICATION_ID);
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        var reply = new Bundle();
        if (Sui.isSui()) return reply;
        if (extras == null) return reply;
        if (method.equals(rikka.shizuku.ShizukuProvider.METHOD_SEND_BINDER)) {
            handleSendBinder(extras);
        }
        return reply;
    }

    @SuppressLint("RestrictedApi")
    private void handleSendBinder(@NonNull Bundle extras) {
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        BinderContainer parcelable = extras.getParcelable(EXTRA_BINDER);
        if (parcelable == null) return;
        var binder = parcelable.binder;
        if (binder == null) return;
        getContext().getMainExecutor().execute(() ->
            Shizuku.onBinderReceived(binder, BuildConfig.APPLICATION_ID));
    }

    @SuppressLint("RestrictedApi")
    public static void waitForBinder(Context context) {
        if (Shizuku.pingBinder() && Shizuku.getBinder() != null) return;

        var latch = new CountDownLatch(1);
        var receiver = new Binder() {
            @Override
            protected boolean onTransact(int code, @NonNull Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == 1) {
                    var binder = data.readStrongBinder();
                    if (binder != null) {
                        context.getMainExecutor().execute(() -> {
                            Shizuku.onBinderReceived(binder, BuildConfig.APPLICATION_ID);
                            latch.countDown();
                        });
                    } else {
                        Log.w(TAG, "shizuku is not running");
                        latch.countDown();
                    }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        var data = new Bundle();
        data.putBinder("binder", receiver);
        var intent = new Intent(ACTION_REQUEST_BINDER)
            .setPackage(rikka.shizuku.ShizukuProvider.MANAGER_APPLICATION_ID)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("data", data);

        // Sui 里没有 moe.shizuku.manager 这个"授权管理器"应用去接 REQUEST_BINDER 广播：
        // 带上 PERMISSION_MANAGER 这个 receiverPermission，系统广播在"没有任何接收者持有该权限"
        // 时会直接失败、根本投不出去（等于白等 5 秒）。Sui 自己 init 过就别再走广播。
        if (Sui.isSui()) {
            Log.d(TAG, "Using Sui, skip REQUEST_BINDER broadcast");
            return;
        }

        context.sendOrderedBroadcast(intent, PERMISSION_MANAGER, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                latch.countDown();
            }
        }, null, 0, null, null);
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    @Override
    public final Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public final String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public final Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public final int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public final int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
