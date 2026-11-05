package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.core.BinderWrapper;
import io.github.vvb2060.packageinstaller.installmodel.UninstallUserAction;

/**
 * 卸载确认框：和系统卸载框一样提供"停用/启用"这个附加选项。
 * （上游那个"仅清除数据"勾选框已按需求去掉，要清数据走系统的应用信息页。）
 * 真正卸载走 {@code InstallRepository#uninstall}，
 * 之后由 {@link UninstallRunningFragment} → {@link UninstallResultFragment} 展示结果。
 */
public final class UninstallConfirmationFragment extends BaseDialogFragment {

    private final UninstallUserAction mDialogData;
    private AlertDialog mDialog;

    public UninstallConfirmationFragment(UninstallUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        TextView textView = dialogView.requireViewById(R.id.message);
        textView.setText(mDialogData.isSplit()
            ? R.string.remove_split_confirm_question
            : R.string.uninstall_confirm_question);
        var apk = mDialogData.apkLite;
        var packageName = apk.packageName;

        var enabled = isEnabled(packageName);
        var builder = new AlertDialog.Builder(requireContext())
            .setTitle(apk.getLabel())
            .setIcon(apk.getIcon())
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, (_, _) -> finish())
            .setPositiveButton(R.string.uninstall,
                (_, _) -> mViewModel.initiateUninstall(mDialogData));

        // 停用/启用不适用于 split
        if (!mDialogData.isSplit() && enabled != null) {
            builder.setNeutralButton(enabled ? R.string.disable : R.string.enable, (_, _) -> {
                BinderWrapper.setPackageEnabled(packageName, !enabled);
                finish();
            });
        }
        mDialog = builder.create();
        return mDialog;
    }

    @Nullable
    private Boolean isEnabled(String packageName) {
        try {
            var info = BinderWrapper.getPackageInfo(packageName, 0);
            var app = info.applicationInfo;
            return app == null ? null : app.enabled;
        } catch (Exception e) {
            return null;
        }
    }
}
