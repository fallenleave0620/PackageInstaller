package io.github.vvb2060.packageinstaller.core;

import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender_rename;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller_rename;
import android.content.pm.PackageManager_rename;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.Nullable;

public class LocalIntentReceiver {
    private final ResultObserver mResultObserver;
    private final java.util.concurrent.Executor mExecutor;

    private final IIntentSender mLocalSender = new IIntentSender.Stub() {
        @Override
        public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                         IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            int legacyStatus = intent.getIntExtra(PackageInstaller_rename.EXTRA_LEGACY_STATUS,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR);
            if (mResultObserver != null) {
                mExecutor.execute(() -> mResultObserver.onResult(status, legacyStatus, statusMessage));
            }
        }
    };

    public LocalIntentReceiver(@Nullable ResultObserver observer) {
        this(Runnable::run, observer);
    }

    public LocalIntentReceiver(java.util.concurrent.Executor executor,
                               @Nullable ResultObserver observer) {
        mExecutor = executor;
        mResultObserver = observer;
    }

    public IntentSender getIntentSender() {
        return (IntentSender) (Object) new IntentSender_rename((IIntentSender) mLocalSender);
    }

    public interface ResultObserver {
        void onResult(int status, int legacyStatus, String message);
    }
}
