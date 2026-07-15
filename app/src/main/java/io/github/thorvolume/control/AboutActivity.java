package io.github.thorvolume.control;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/** 集中展示版本更新、项目地址、许可证和隐私说明。 */
public final class AboutActivity extends AppCompatActivity {
    private Button checkUpdates;
    private TextView updateStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ((TextView) findViewById(R.id.version)).setText(getString(
                R.string.about_version, BuildConfig.VERSION_NAME, getString(R.string.edition_name)));
        TextView dependencies = (TextView) findViewById(R.id.dependencies);
        String dependencySummary = SecondaryVolumeGateway.dependencySummary(this);
        if (dependencySummary != null && dependencySummary.length() > 0) {
            dependencies.setText(dependencySummary);
            dependencies.setVisibility(View.VISIBLE);
        }
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        findViewById(R.id.open_github).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { AppLinks.openProject(AboutActivity.this); }
        });
        checkUpdates = (Button) findViewById(R.id.check_updates);
        updateStatus = (TextView) findViewById(R.id.update_status);
        checkUpdates.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { checkForUpdates(); }
        });
    }

    private void checkForUpdates() {
        if (!checkUpdates.isEnabled()) return;
        checkUpdates.setEnabled(false);
        checkUpdates.setText(R.string.checking_updates);
        updateStatus.setText(R.string.update_status_checking);
        UpdateChecker.check(this, new UpdateChecker.Callback() {
            @Override public void onComplete(UpdateChecker.Result result) {
                checkUpdates.setEnabled(true);
                checkUpdates.setText(R.string.check_updates);
                if (isFinishing()) return;
                showUpdateResult(result);
            }
        });
    }

    private void showUpdateResult(final UpdateChecker.Result result) {
        if (result == null || !result.ok) {
            String detail = result == null ? getString(R.string.update_network_error) : result.error;
            updateStatus.setText(R.string.update_status_failed);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.update_check_failed)
                    .setMessage(detail)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_update);
        dialog.setCanceledOnTouchOutside(true);

        TextView title = (TextView) dialog.findViewById(R.id.update_dialog_title);
        TextView version = (TextView) dialog.findViewById(R.id.update_dialog_version);
        TextView summary = (TextView) dialog.findViewById(R.id.update_dialog_summary);
        final Button secondary = (Button) dialog.findViewById(R.id.update_dialog_secondary);
        Button primary = (Button) dialog.findViewById(R.id.update_dialog_primary);

        title.setText(result.updateAvailable ? R.string.update_available : R.string.update_is_latest);
        version.setText(
                result.updateAvailable ? result.latestVersion : result.currentVersion);
        summary.setText(
                result.updateAvailable
                        ? getString(R.string.update_installed_version, result.currentVersion)
                        : getString(R.string.update_latest_installed));

        if (!result.updateAvailable) {
            updateStatus.setText(getString(R.string.update_status_latest, result.currentVersion));
            secondary.setVisibility(View.GONE);
            primary.setText(android.R.string.ok);
            primary.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { dialog.dismiss(); }
            });
        } else {
            updateStatus.setText(getString(R.string.update_status_available, result.latestVersion));
            secondary.setText(R.string.later);
            secondary.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { dialog.dismiss(); }
            });
            primary.setText(R.string.view_release);
            primary.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    dialog.dismiss();
                    AppLinks.openRelease(AboutActivity.this, result.releaseUrl);
                }
            });
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.58f;
            int margin = Math.round(40f * getResources().getDisplayMetrics().density);
            attributes.width = Math.max(1,
                    getResources().getDisplayMetrics().widthPixels - margin);
            window.setAttributes(attributes);
        }
        dialog.show();
    }
}
