package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.InstallSuccess;

public final class InstallSuccessFragment extends BaseDialogFragment {

    private final InstallSuccess mDialogData;
    private AlertDialog mDialog;

    public InstallSuccessFragment(InstallSuccess dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var context = requireContext();
        var message = context.getString(R.string.install_done);
        if (mDialogData.cleanupMessage != 0) {
            message += "\n\n" + context.getString(mDialogData.cleanupMessage);
        }
        mDialog = new AlertDialog.Builder(context)
            .setTitle(mDialogData.apkLite.getLabel())
            .setIcon(mDialogData.apkLite.getIcon())
            .setMessage(message)
            .setNegativeButton(R.string.done, (_, _) -> finish())
            .setPositiveButton(R.string.launch, (_, _) -> {
                startActivitySafe(resolveLaunchIntent(), mDialogData.safeMode);
                finish();
            })
            .create();
        return mDialog;
    }

    @Nullable
    private Intent resolveLaunchIntent() {
        var packageName = mDialogData.apkLite.packageName;
        var intent = requireContext().getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) intent = mDialogData.startIntent;
        if (intent == null) return null;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    public void onStart() {
        super.onStart();
        Button launchButton = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (resolveLaunchIntent() == null) {
            launchButton.setVisibility(View.GONE);
        }
    }
}
