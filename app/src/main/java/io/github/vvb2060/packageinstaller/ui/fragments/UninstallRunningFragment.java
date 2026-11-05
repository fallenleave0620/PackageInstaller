package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.installmodel.UninstallRunning;

/**
 * 卸载进行中的转菊花框。
 */
public final class UninstallRunningFragment extends BaseDialogFragment {

    public UninstallRunningFragment(UninstallRunning dialogData) {
        super(dialogData);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        TextView textView = dialogView.requireViewById(R.id.message);
        textView.setText(R.string.uninstalling);
        ProgressBar progressBar = dialogView.requireViewById(R.id.progress);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        setCancelable(false);
        return new AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create();
    }
}
