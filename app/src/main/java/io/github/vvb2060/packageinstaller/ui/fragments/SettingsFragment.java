package io.github.vvb2060.packageinstaller.ui.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.vvb2060.packageinstaller.R;
import io.github.vvb2060.packageinstaller.core.InstallSource;
import io.github.vvb2060.packageinstaller.installmodel.SettingsUserAction;

public final class SettingsFragment extends BaseDialogFragment {

    private final SettingsUserAction mDialogData;
    private TextView mInstallSourceRow;

    public SettingsFragment(SettingsUserAction dialogData) {
        super(dialogData);
        mDialogData = dialogData;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Log.d("SettingsFragment", "onCreateDialog: " + savedInstanceState);
        View dialogView = getLayoutInflater().inflate(R.layout.install_content_view, null);
        TextView textView = dialogView.requireViewById(R.id.message);
        textView.setText(R.string.settings_safe_mode);
        CheckBox checkBox = dialogView.requireViewById(R.id.checkbox);
        checkBox.setVisibility(View.VISIBLE);
        checkBox.setText(R.string.safe_mode);
        if (savedInstanceState == null) {
            checkBox.setChecked(mDialogData.safeMode);
        }

        // 安装来源：点一下弹出预设商店列表（也可以手填包名）
        var container = (LinearLayout) ((ViewGroup) dialogView).getChildAt(0);
        mInstallSourceRow = createInstallSourceRow(container);
        mInstallSourceRow.setOnClickListener(v -> showInstallSourcePicker());
        updateInstallSourceRow();

        return new AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_name)
            .setIcon(R.drawable.ic_app_icon)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, (_, _) -> {
                var checked = checkBox.isChecked();
                if (checked != mDialogData.safeMode) {
                    mViewModel.setSafeMode(checked);
                }
                finish();
            })
            .setNegativeButton(android.R.string.cancel, (_, _) -> finish())
            .setNeutralButton(R.string.launch, (_, _) -> {
                var web = Uri.parse("https://github.com/vvb2060/PackageInstaller");
                var intent = new Intent(Intent.ACTION_VIEW, web);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivitySafe(intent, false);
                finish();
            })
            .create();
    }

    private TextView createInstallSourceRow(LinearLayout container) {
        var context = requireContext();
        var row = new TextView(context);
        row.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        row.setPadding(0, dp(16), 0, 0);
        row.setLineSpacing(0, 1.15f);
        row.setClickable(true);
        var outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);
        container.addView(row);

        var hint = new TextView(context);
        hint.setText(R.string.install_source_hint);
        hint.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        hint.setAlpha(0.7f);
        hint.setPadding(0, dp(4), 0, 0);
        container.addView(hint);
        return row;
    }

    private int dp(int value) {
        var density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private void updateInstallSourceRow() {
        if (mInstallSourceRow == null) return;
        var context = requireContext();
        var pm = InstallSource.packageManager(context);
        var configured = InstallSource.get(context);
        CharSequence summary;
        if (configured == null) {
            summary = getString(R.string.install_source_auto,
                InstallSource.label(pm, InstallSource.defaultSource(pm)));
        } else if (configured.isEmpty()) {
            summary = getString(R.string.install_source_none);
        } else {
            var name = InstallSource.label(pm, configured);
            summary = InstallSource.isInstalled(pm, configured)
                ? getString(R.string.install_source_configured, name, configured)
                : getString(R.string.install_source_unresolved, name, configured);
        }
        mInstallSourceRow.setText(getString(R.string.install_source_summary, summary));
    }

    private void showInstallSourcePicker() {
        var context = requireContext();
        var pm = InstallSource.packageManager(context);
        var current = InstallSource.get(context);
        var presets = InstallSource.PRESETS;

        var items = new CharSequence[presets.size() + 3];
        items[0] = getString(R.string.install_source_auto,
            InstallSource.label(pm, InstallSource.defaultSource(pm)));
        items[1] = getString(R.string.install_source_none);
        for (int i = 0; i < presets.size(); i++) {
            var packageName = presets.get(i);
            var name = InstallSource.label(pm, packageName);
            items[i + 2] = InstallSource.isInstalled(pm, packageName)
                ? getString(R.string.install_source_configured, name, packageName)
                : getString(R.string.install_source_unavailable, name, packageName);
        }
        int customIndex = items.length - 1;
        items[customIndex] = getString(R.string.install_source_custom);

        int checkedIndex;
        if (current == null) {
            checkedIndex = 0;
        } else if (current.isEmpty()) {
            checkedIndex = 1;
        } else {
            int presetIndex = presets.indexOf(current);
            checkedIndex = presetIndex >= 0 ? presetIndex + 2 : customIndex;
        }

        new AlertDialog.Builder(context)
            .setTitle(R.string.install_source)
            .setSingleChoiceItems(items, checkedIndex, (dialog, which) -> {
                dialog.dismiss();
                if (which == 0) {
                    InstallSource.reset(context);
                    updateInstallSourceRow();
                } else if (which == 1) {
                    InstallSource.set(context, InstallSource.NONE);
                    updateInstallSourceRow();
                } else if (which == customIndex) {
                    showCustomInstallSourceDialog();
                } else {
                    InstallSource.set(context, presets.get(which - 2));
                    updateInstallSourceRow();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showCustomInstallSourceDialog() {
        var context = requireContext();
        var input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.install_source_custom_hint);
        var current = InstallSource.get(context);
        if (current != null && !current.isEmpty() && !InstallSource.PRESETS.contains(current)) {
            input.setText(current);
        }
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(context)
            .setTitle(R.string.install_source_custom_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (_, _) -> {
                var packageName = input.getText().toString().trim();
                if (packageName.isEmpty()) return;
                InstallSource.set(context, packageName);
                updateInstallSourceRow();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
