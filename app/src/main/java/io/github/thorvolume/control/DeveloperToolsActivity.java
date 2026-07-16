package io.github.thorvolume.control;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 汇总可在正式构建中按需启用的设备诊断工具。 */
public final class DeveloperToolsActivity extends AppCompatActivity {
    private static final String TAG = "DeveloperTools";
    private static final int MAX_HISTORY_LINES = 20;
    private static final String STATE_LISTENING = "focus_listener_requested";

    private final StringBuilder history = new StringBuilder();
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private SwitchCompat listenerSwitch;
    private TextView listenerSummary;
    private TextView statusView;
    private TextView reportView;
    private TextView historyView;
    private Uri focusUri;
    private ContentObserver focusObserver;
    private boolean listenerRequested;
    private boolean observerRegistered;
    private boolean updatingSwitch;
    private String observerError;
    private int notificationCount;
    private String latestReport = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Prefs.areDeveloperToolsEnabled(this)) {
            finish();
            return;
        }
        setContentView(R.layout.activity_developer_tools);

        listenerSwitch = (SwitchCompat) findViewById(R.id.focus_debug_listener);
        listenerSummary = (TextView) findViewById(R.id.focus_debug_listener_summary);
        statusView = (TextView) findViewById(R.id.focus_debug_status);
        reportView = (TextView) findViewById(R.id.focus_debug_report);
        historyView = (TextView) findViewById(R.id.focus_debug_history);

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        findViewById(R.id.focus_debug_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { refresh(false); }
        });
        findViewById(R.id.focus_debug_copy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { copyReport(); }
        });
        findViewById(R.id.disable_developer_tools).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { confirmDisableDeveloperTools(); }
        });

        focusUri = FocusChangeSetting.uri();
        focusObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override public void onChange(boolean selfChange, Uri uri) {
                notificationCount++;
                refresh(true);
            }
        };

        listenerRequested = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_LISTENING, false);
        updateListenerUi();
        listenerSwitch.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(
                            CompoundButton buttonView, boolean isChecked) {
                        if (updatingSwitch) return;
                        setListenerRequested(isChecked);
                    }
                });
        refresh(false);
    }

    @Override protected void onStart() {
        super.onStart();
        if (listenerRequested) {
            registerFocusObserver();
            refresh(false);
        }
    }

    @Override protected void onStop() {
        unregisterFocusObserver();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_LISTENING, listenerRequested);
        super.onSaveInstanceState(outState);
    }

    private void setListenerRequested(boolean enabled) {
        listenerRequested = enabled;
        if (enabled) registerFocusObserver();
        else unregisterFocusObserver();
        updateListenerUi();
        refresh(false);
    }

    private void registerFocusObserver() {
        if (observerRegistered || focusObserver == null || focusUri == null) return;
        try {
            getContentResolver().registerContentObserver(focusUri, false, focusObserver);
            observerRegistered = true;
            observerError = null;
            Log.i(TAG, "Observer registered for " + focusUri);
        } catch (Throwable error) {
            observerRegistered = false;
            observerError = describe(error);
            Log.e(TAG, "Observer registration failed", error);
        }
        updateListenerUi();
    }

    private void unregisterFocusObserver() {
        if (observerRegistered) {
            try {
                getContentResolver().unregisterContentObserver(focusObserver);
                Log.i(TAG, "Focus observer stopped");
            } catch (Throwable error) {
                Log.w(TAG, "Unable to unregister focus observer", error);
            }
        }
        observerRegistered = false;
        updateListenerUi();
    }

    private void updateListenerUi() {
        if (listenerSwitch == null || listenerSummary == null) return;
        updatingSwitch = true;
        listenerSwitch.setChecked(listenerRequested);
        updatingSwitch = false;
        listenerSummary.setText(observerRegistered
                ? R.string.focus_debug_listener_running
                : listenerRequested && observerError != null
                        ? R.string.focus_debug_listener_failed
                        : R.string.focus_debug_listener_stopped);
    }

    private void refresh(boolean fromObserver) {
        if (statusView == null) return;
        long value = FocusChangeSetting.MISSING_VALUE;
        Throwable readError = null;
        try {
            value = FocusChangeSetting.read(this);
        } catch (Throwable error) {
            readError = error;
        }

        boolean readable = readError == null && FocusChangeSetting.isAvailable(value);
        String target = readable
                ? getString(FocusChangeSetting.targetsPrimary(value)
                        ? R.string.focus_debug_target_primary
                        : R.string.focus_debug_target_secondary)
                : getString(R.string.focus_debug_target_unknown);
        String parity = readable
                ? getString(FocusChangeSetting.targetsPrimary(value)
                        ? R.string.focus_debug_parity_odd
                        : R.string.focus_debug_parity_even)
                : getString(R.string.focus_debug_value_unavailable);
        String readState = readError != null
                ? getString(R.string.focus_debug_read_error, describe(readError))
                : readable
                        ? getString(R.string.focus_debug_read_success)
                        : getString(R.string.focus_debug_value_missing);
        String observerState = observerRegistered
                ? getString(R.string.focus_debug_observer_active)
                : listenerRequested && observerError != null
                        ? getString(R.string.focus_debug_observer_error, observerError)
                        : getString(R.string.focus_debug_observer_stopped);
        String timestamp = timeFormat.format(new Date());

        latestReport = getString(
                R.string.focus_debug_report_format,
                getPackageName(),
                focusUri,
                readState,
                Long.valueOf(value),
                parity,
                target,
                Integer.valueOf(getActivityDisplayId()),
                observerState,
                Integer.valueOf(notificationCount),
                timestamp);

        statusView.setText(readable
                ? R.string.focus_debug_status_success
                : R.string.focus_debug_status_failure);
        reportView.setText(latestReport);
        appendHistory(timestamp, fromObserver, value, target, readError);
        Log.i(TAG, latestReport.replace('\n', ' '));
        if (readError != null) Log.e(TAG, "Settings.System read failed", readError);
    }

    private int getActivityDisplayId() {
        try {
            Display display = getWindowManager().getDefaultDisplay();
            return display == null ? -1 : display.getDisplayId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void appendHistory(
            String timestamp, boolean fromObserver, long value, String target, Throwable error) {
        String source = getString(fromObserver
                ? R.string.focus_debug_source_observer
                : R.string.focus_debug_source_manual);
        String line = error == null
                ? getString(R.string.focus_debug_history_line,
                        timestamp, source, Long.valueOf(value), target)
                : getString(R.string.focus_debug_history_error,
                        timestamp, source, describe(error));
        history.insert(0, line + '\n');

        int lines = 0;
        int cut = -1;
        for (int i = 0; i < history.length(); i++) {
            if (history.charAt(i) == '\n' && ++lines == MAX_HISTORY_LINES) {
                cut = i + 1;
                break;
            }
        }
        if (cut >= 0 && cut < history.length()) history.delete(cut, history.length());
        historyView.setText(history.toString().trim());
    }

    private void copyReport() {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
            String text = latestReport + "\n\n" + history.toString().trim();
            clipboard.setPrimaryClip(ClipData.newPlainText("Thor focus diagnostics", text));
            Ui.toast(this, getString(R.string.focus_debug_copied));
        } catch (Throwable error) {
            Log.e(TAG, "Unable to copy report", error);
            Ui.toast(this, getString(R.string.focus_debug_copy_failed, describe(error)));
        }
    }

    private void confirmDisableDeveloperTools() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.disable_developer_tools)
                .setMessage(R.string.disable_developer_tools_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.disable, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        setListenerRequested(false);
                        Prefs.setDeveloperToolsEnabled(DeveloperToolsActivity.this, false);
                        Ui.toast(DeveloperToolsActivity.this,
                                getString(R.string.developer_tools_disabled));
                        finish();
                    }
                })
                .show();
    }

    private static String describe(Throwable error) {
        if (error == null) return "unknown";
        String name = error.getClass().getSimpleName();
        String message = error.getMessage();
        return message == null || message.length() == 0 ? name : name + ": " + message;
    }
}
