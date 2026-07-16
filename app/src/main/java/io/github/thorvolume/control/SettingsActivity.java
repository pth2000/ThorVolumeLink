package io.github.thorvolume.control;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

/** 按键行为、语言、外观和项目入口的集中设置页。 */
public final class SettingsActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SwitchCompat modeKeyEnabled;
    private SwitchCompat visualFeedback;
    private SwitchCompat vibrationFeedback;
    private TextView visualFeedbackSummary;
    private TextView vibrationFeedbackSummary;
    private TextView modeKeySummary;
    private TextView switchKeyStatus;
    private TextView holdStatus;
    private TextView stepStatus;
    private TextView languageStatus;
    private TextView nightStatus;
    private Button changeSwitchKey;
    private Button changeHold;
    private AlertDialog captureDialog;
    private int localCapturedCode;
    private int localCapturedScan;

    private final Runnable capturePoll = new Runnable() {
        @Override public void run() {
            if (Prefs.getCaptureTarget(SettingsActivity.this) == Prefs.CAPTURE_NONE) {
                closeCaptureDialog();
                refreshState();
            } else {
                handler.postDelayed(this, 180L);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Prefs.markOnboardingSeen(this);

        modeKeyEnabled = (SwitchCompat) findViewById(R.id.mode_key_enabled);
        visualFeedback = (SwitchCompat) findViewById(R.id.visual_feedback);
        vibrationFeedback = (SwitchCompat) findViewById(R.id.vibration_feedback);
        visualFeedbackSummary = (TextView) findViewById(R.id.visual_feedback_summary);
        vibrationFeedbackSummary = (TextView) findViewById(R.id.vibration_feedback_summary);
        modeKeySummary = (TextView) findViewById(R.id.mode_key_summary);
        switchKeyStatus = (TextView) findViewById(R.id.switch_key_status);
        holdStatus = (TextView) findViewById(R.id.hold_status);
        stepStatus = (TextView) findViewById(R.id.step_status);
        languageStatus = (TextView) findViewById(R.id.language_status);
        nightStatus = (TextView) findViewById(R.id.night_status);
        changeSwitchKey = (Button) findViewById(R.id.change_switch_key);
        changeHold = (Button) findViewById(R.id.change_hold);

        modeKeyEnabled.setChecked(Prefs.isModeKeyEnabled(this));
        modeKeyEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setModeKeyEnabled(SettingsActivity.this, isChecked);
                refreshKeyControls();
            }
        });
        visualFeedback.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setVisualFeedbackEnabled(SettingsActivity.this, isChecked);
                refreshVisualFeedback();
            }
        });
        vibrationFeedback.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setVibrationFeedbackEnabled(SettingsActivity.this, isChecked);
                refreshVibrationFeedback();
            }
        });
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        changeSwitchKey.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { beginCapture(); }
        });
        changeHold.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showHoldDialog(); }
        });
        findViewById(R.id.change_step).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showStepDialog(); }
        });
        findViewById(R.id.reset_controls).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Prefs.resetControlSettings(SettingsActivity.this);
                modeKeyEnabled.setChecked(true);
                Ui.toast(SettingsActivity.this, getString(R.string.controls_reset));
                refreshState();
            }
        });
        findViewById(R.id.change_language).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showLanguageDialog(); }
        });
        findViewById(R.id.change_night).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showNightDialog(); }
        });
        findViewById(R.id.open_about).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
            }
        });
        findViewById(R.id.open_developer_tools).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(SettingsActivity.this, DeveloperToolsActivity.class));
            }
        });
        refreshState();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshState();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(capturePoll);
        closeCaptureDialog();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (Prefs.getCaptureTarget(this) != Prefs.CAPTURE_NONE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                int code = event.getKeyCode();
                int scan = event.getScanCode();
                if (Prefs.saveSwitchBinding(this, code, scan)) {
                    localCapturedCode = code;
                    localCapturedScan = scan;
                    Ui.toast(this, getString(R.string.key_capture_success,
                            Prefs.bindingLabel(this, new Prefs.Binding(code, scan))));
                } else {
                    Prefs.cancelCapture(this);
                    Ui.toast(this, getString(R.string.key_capture_invalid));
                }
                return true;
            }
            return true;
        }

        if (localCapturedCode != 0 || localCapturedScan != 0) {
            boolean match = (localCapturedCode != 0 && event.getKeyCode() == localCapturedCode)
                    || (localCapturedScan > 0 && event.getScanCode() == localCapturedScan);
            if (match) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    localCapturedCode = 0;
                    localCapturedScan = 0;
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void refreshState() {
        if (modeKeyEnabled == null) return;
        boolean enabled = Prefs.isModeKeyEnabled(this);
        if (modeKeyEnabled.isChecked() != enabled) modeKeyEnabled.setChecked(enabled);
        refreshKeyControls();
        refreshVisualFeedback();
        refreshVibrationFeedback();
        switchKeyStatus.setText(getString(R.string.switch_key_value,
                Prefs.bindingLabel(this, Prefs.getSwitchBinding(this))));
        holdStatus.setText(getString(R.string.hold_duration_value, Integer.valueOf(Prefs.getHoldMs(this))));
        stepStatus.setText(getString(R.string.volume_step_value, Integer.valueOf(Prefs.getStep(this))));
        refreshLanguage();
        refreshNightMode();
        findViewById(R.id.developer_tools_section).setVisibility(
                Prefs.areDeveloperToolsEnabled(this) ? View.VISIBLE : View.GONE);
    }

    private void refreshKeyControls() {
        boolean enabled = modeKeyEnabled.isChecked();
        modeKeySummary.setText(enabled ? R.string.mode_key_enabled_summary : R.string.mode_key_disabled_summary);
        changeSwitchKey.setEnabled(enabled);
        changeHold.setEnabled(enabled);
        switchKeyStatus.setAlpha(enabled ? 1f : 0.45f);
        holdStatus.setAlpha(enabled ? 1f : 0.45f);
        changeSwitchKey.setAlpha(enabled ? 1f : 0.45f);
        changeHold.setAlpha(enabled ? 1f : 0.45f);
    }

    private void refreshVisualFeedback() {
        if (visualFeedback == null || visualFeedbackSummary == null) return;
        boolean enabled = Prefs.isVisualFeedbackEnabled(this);
        if (visualFeedback.isChecked() != enabled) visualFeedback.setChecked(enabled);
        visualFeedbackSummary.setText(enabled
                ? R.string.visual_feedback_enabled_summary
                : R.string.visual_feedback_disabled_summary);
    }

    private void refreshVibrationFeedback() {
        if (vibrationFeedback == null || vibrationFeedbackSummary == null) return;
        boolean enabled = Prefs.isVibrationFeedbackEnabled(this);
        if (vibrationFeedback.isChecked() != enabled) vibrationFeedback.setChecked(enabled);
        vibrationFeedbackSummary.setText(enabled
                ? R.string.vibration_feedback_enabled_summary
                : R.string.vibration_feedback_disabled_summary);
    }

    private void refreshLanguage() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        String value;
        if (locales.isEmpty() || locales.get(0) == null) value = getString(R.string.language_system);
        else if (locales.get(0).getLanguage().equals("zh")) value = getString(R.string.language_chinese);
        else value = getString(R.string.language_english);
        languageStatus.setText(getString(R.string.setting_value, getString(R.string.language), value));
    }

    private void refreshNightMode() {
        int mode = Prefs.getNightMode(this);
        int label = mode == Prefs.NIGHT_LIGHT ? R.string.night_light
                : mode == Prefs.NIGHT_DARK ? R.string.night_dark : R.string.night_system;
        nightStatus.setText(getString(R.string.setting_value, getString(R.string.night_mode), getString(label)));
    }

    private void beginCapture() {
        if (!isAccessibilityEnabled()) Ui.toast(this, getString(R.string.key_capture_accessibility_hint));
        Prefs.beginCapture(this);
        handler.removeCallbacks(capturePoll);
        closeCaptureDialog();
        captureDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.key_capture_title)
                .setMessage(R.string.key_capture_message)
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Prefs.cancelCapture(SettingsActivity.this);
                        dialog.dismiss();
                    }
                })
                .create();
        captureDialog.show();
        handler.postDelayed(capturePoll, 180L);
    }

    private void closeCaptureDialog() {
        if (captureDialog != null) {
            try { captureDialog.dismiss(); } catch (Throwable ignored) {}
            captureDialog = null;
        }
    }

    private void showHoldDialog() {
        final int[] values = new int[] {300, 500, 650, 800, 1000, 1200, 1500, 2000, 2500};
        final String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] == 800
                    ? getString(R.string.hold_recommended, Integer.valueOf(values[i]))
                    : getString(R.string.hold_plain, Integer.valueOf(values[i]));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.hold_dialog_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which >= 0 && which < values.length) Prefs.setHoldMs(SettingsActivity.this, values[which]);
                        dialog.dismiss();
                        refreshState();
                    }
                })
                .show();
    }

    private void showStepDialog() {
        final int[] values = new int[] {1, 2, 3, 4, 5};
        final String[] labels = new String[] {
                getString(R.string.step_precise), getString(R.string.step_plain, Integer.valueOf(2)),
                getString(R.string.step_plain, Integer.valueOf(3)), getString(R.string.step_plain, Integer.valueOf(4)),
                getString(R.string.step_fast)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.step_dialog_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which >= 0 && which < values.length) Prefs.setStep(SettingsActivity.this, values[which]);
                        dialog.dismiss();
                        refreshState();
                    }
                })
                .show();
    }

    private void showLanguageDialog() {
        final String[] labels = new String[] {
                getString(R.string.language_system), getString(R.string.language_chinese), getString(R.string.language_english)
        };
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        int checked = 0;
        if (!current.isEmpty() && current.get(0) != null) checked = current.get(0).getLanguage().equals("zh") ? 1 : 2;
        new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        LocaleListCompat locales = which == 1 ? LocaleListCompat.forLanguageTags("zh-CN")
                                : which == 2 ? LocaleListCompat.forLanguageTags("en")
                                : LocaleListCompat.getEmptyLocaleList();
                        dialog.dismiss();
                        AppCompatDelegate.setApplicationLocales(locales);
                    }
                })
                .show();
    }

    private void showNightDialog() {
        final String[] labels = new String[] {
                getString(R.string.night_system), getString(R.string.night_light), getString(R.string.night_dark)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.night_mode)
                .setSingleChoiceItems(labels, Prefs.getNightMode(this), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Prefs.setNightMode(SettingsActivity.this, which);
                        dialog.dismiss();
                        int appCompatMode = which == Prefs.NIGHT_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                                : which == Prefs.NIGHT_DARK ? AppCompatDelegate.MODE_NIGHT_YES
                                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                        AppCompatDelegate.setDefaultNightMode(appCompatMode);
                    }
                })
                .show();
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
            if (enabled == null) return false;
            return enabled.toLowerCase(Locale.US).contains(getPackageName().toLowerCase(Locale.US));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
