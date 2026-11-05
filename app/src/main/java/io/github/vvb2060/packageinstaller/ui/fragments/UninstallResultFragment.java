package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.UninstallResult;

/**
 * 卸载结果框。成功时显示"已卸载"，失败时把原因一起显示出来。
 */
public final class UninstallResultFragment extends BaseDialogFragment {

    private final UninstallResult mDialogData;

    public UninstallResultFragment(UninstallResult dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var context = requireContext();
        var isSplit = mDialogData.isSplit();
        int res;
        if (mDialogData.success) {
            res = isSplit ? R.string.split_removed : R.string.uninstall_done;
        } else {
            res = isSplit ? R.string.split_remove_failed : R.string.uninstall_failed;
        }
        var text = context.getString(res);
        if (!mDialogData.success && mDialogData.message != null) {
            text = text + "\n\n" + mDialogData.message;
        }
        var apk = mDialogData.apkLite;
        return new AlertDialog.Builder(context)
            .setTitle(apk.getLabel())
            .setIcon(apk.getIcon())
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, (_, _) -> finish())
            .create();
    }
}
