package io.github.thorvolume.control;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/** 应用的主控制面板，集中展示当前模式、实时音量和服务状态。 */
public final class MainActivity extends AppCompatActivity {
    private static final long LIVE_REFRESH_MS = 800L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView backendStatus;
    private TextView authorizationStatus;
    private TextView accessibilityStatus;
    private TextView modeStatus;
    private TextView modeDescription;
    private TextView mainVolumeStatus;
    private TextView secondaryVolumeStatus;
    private TextView errorStatus;
    private View firstUseCard;
    private Button requestAuthorization;
    private Button openAccessibility;
    private Button modeMain;
    private Button modeSecondary;
    private Button modeSync;

    private boolean resumed;
    private boolean secondaryReadInFlight;
    private boolean secondaryHasValue;

    private final BackendStatusListener backendListener = new BackendStatusListener() {
        @Override public void onStatusChanged() {
            refreshAll();
        }
    };

    private final Runnable liveRefresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshState();
            refreshVolumes();
            handler.postDelayed(this, LIVE_REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        backendStatus = (TextView) findViewById(R.id.backend_status);
        authorizationStatus = (TextView) findViewById(R.id.authorization_status);
        accessibilityStatus = (TextView) findViewById(R.id.accessibility_status);
        modeStatus = (TextView) findViewById(R.id.mode_status);
        modeDescription = (TextView) findViewById(R.id.mode_description);
        mainVolumeStatus = (TextView) findViewById(R.id.main_volume_status);
        secondaryVolumeStatus = (TextView) findViewById(R.id.secondary_volume_status);
        errorStatus = (TextView) findViewById(R.id.error_status);
        firstUseCard = findViewById(R.id.first_use_card);
        requestAuthorization = (Button) findViewById(R.id.request_authorization);
        openAccessibility = (Button) findViewById(R.id.open_accessibility);
        modeMain = (Button) findViewById(R.id.mode_main);
        modeSecondary = (Button) findViewById(R.id.mode_secondary);
        modeSync = (Button) findViewById(R.id.mode_sync);

        findViewById(R.id.open_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openSettings(); }
        });
        findViewById(R.id.open_key_controls).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Prefs.markOnboardingSeen(MainActivity.this);
                openSettings();
            }
        });
        findViewById(R.id.dismiss_first_use).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Prefs.markOnboardingSeen(MainActivity.this);
                firstUseCard.setVisibility(View.GONE);
            }
        });
        requestAuthorization.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { requestAuthorization(); }
        });
        openAccessibility.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openAccessibilitySettings(); }
        });
        modeMain.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { setMode(Prefs.MODE_MAIN); }
        });
        modeSecondary.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { setMode(Prefs.MODE_SECONDARY); }
        });
        modeSync.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                setMode(Prefs.MODE_SYNC);
                VolumeControl.syncSecondaryToMain(MainActivity.this, false, null);
            }
        });
        findViewById(R.id.sync_now).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                VolumeControl.syncSecondaryToMain(MainActivity.this, true, new VolumeControl.VolumeCallback() {
                    @Override public void onComplete(boolean ok, int value, String error) { refreshVolumes(); }
                });
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        SecondaryVolumeGateway.setStatusListener(backendListener);
        handler.removeCallbacks(liveRefresh);
        refreshAll();
        handler.postDelayed(liveRefresh, LIVE_REFRESH_MS);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(liveRefresh);
        SecondaryVolumeGateway.setStatusListener(null);
        super.onPause();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void requestAuthorization() {
        SecondaryVolumeGateway.requestAuthorization(this, new SecondaryVolumeCallback() {
            @Override public void onComplete(boolean ok, int value, String error) {
                if (ok) {
                    Ui.toast(MainActivity.this, getString(R.string.authorization_complete));
                } else if (error != null && error.length() > 0) {
                    Ui.toast(MainActivity.this, error);
                }
                refreshAll();
            }
        });
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent("android.settings.ACCESSIBILITY_SETTINGS"));
        } catch (Throwable error) {
            Prefs.recordError(this, getString(R.string.error_open_accessibility), error);
            Ui.toast(this, getString(R.string.open_accessibility_failed));
        }
    }

    private void setMode(int mode) {
        Prefs.setMode(this, mode);
        Ui.toast(this, getString(R.string.mode_changed, Prefs.modeLabel(this, mode)));
        refreshState();
    }

    private void refreshAll() {
        if (backendStatus == null) return;
        firstUseCard.setVisibility(Prefs.hasSeenOnboarding(this) ? View.GONE : View.VISIBLE);
        refreshState();
        refreshVolumes();
    }

    private void refreshState() {
        if (backendStatus == null) return;

        boolean backendAvailable = SecondaryVolumeGateway.isBackendAvailable(this);
        boolean authorization = SecondaryVolumeGateway.hasAuthorization(this);
        boolean serviceEnabled = isAccessibilityEnabled();
        int mode = Prefs.getMode(this);

        backendStatus.setText(SecondaryVolumeGateway.backendStatus(this));
        backendStatus.setBackgroundResource(backendAvailable ? R.drawable.status_ok : R.drawable.status_warn);
        authorizationStatus.setText(SecondaryVolumeGateway.authorizationStatus(this));
        authorizationStatus.setBackgroundResource(authorization ? R.drawable.status_ok : R.drawable.status_warn);
        accessibilityStatus.setText(serviceEnabled ? R.string.accessibility_enabled : R.string.accessibility_disabled);
        accessibilityStatus.setBackgroundResource(serviceEnabled ? R.drawable.status_ok : R.drawable.status_warn);
        requestAuthorization.setText(SecondaryVolumeGateway.authorizationActionLabel(this));
        requestAuthorization.setVisibility(authorization ? View.GONE : View.VISIBLE);
        openAccessibility.setText(serviceEnabled ? R.string.view_accessibility : R.string.enable_accessibility);

        modeStatus.setText(Prefs.modeLabel(this, mode));
        if (mode == Prefs.MODE_MAIN) modeDescription.setText(R.string.mode_main_description);
        else if (mode == Prefs.MODE_SECONDARY) modeDescription.setText(R.string.mode_secondary_description);
        else modeDescription.setText(R.string.mode_sync_description);
        modeMain.setBackgroundResource(mode == Prefs.MODE_MAIN ? R.drawable.segment_selected : R.drawable.segment_unselected);
        modeSecondary.setBackgroundResource(mode == Prefs.MODE_SECONDARY ? R.drawable.segment_selected : R.drawable.segment_unselected);
        modeSync.setBackgroundResource(mode == Prefs.MODE_SYNC ? R.drawable.segment_selected : R.drawable.segment_unselected);

        String error = Prefs.getLastError(this);
        if (error == null || error.length() == 0) {
            errorStatus.setVisibility(View.GONE);
        } else {
            errorStatus.setText(getString(R.string.recent_error, error));
            errorStatus.setVisibility(View.VISIBLE);
        }
    }

    private void refreshVolumes() {
        if (mainVolumeStatus == null) return;
        int main = VolumeControl.readMain(this);
        int max = VolumeControl.mainMax(this);
        mainVolumeStatus.setText(getString(R.string.main_volume_value, Integer.valueOf(main), Integer.valueOf(max)));

        boolean backendAvailable = SecondaryVolumeGateway.isBackendAvailable(this);
        boolean authorization = SecondaryVolumeGateway.hasAuthorization(this);
        if (!backendAvailable) {
            secondaryReadInFlight = false;
            secondaryHasValue = false;
            secondaryVolumeStatus.setText(R.string.secondary_unavailable);
            return;
        }
        if (!authorization) {
            secondaryReadInFlight = false;
            secondaryHasValue = false;
            secondaryVolumeStatus.setText(R.string.secondary_waiting_authorization);
            return;
        }
        if (secondaryReadInFlight) return;
        if (!secondaryHasValue) secondaryVolumeStatus.setText(R.string.secondary_reading);
        secondaryReadInFlight = true;
        VolumeControl.readSecondary(this, new VolumeControl.VolumeCallback() {
            @Override public void onComplete(boolean ok, int value, String error) {
                secondaryReadInFlight = false;
                if (!resumed) return;
                if (ok) {
                    secondaryHasValue = true;
                    secondaryVolumeStatus.setText(getString(R.string.secondary_volume_value, Integer.valueOf(value)));
                } else {
                    secondaryHasValue = false;
                    secondaryVolumeStatus.setText(R.string.secondary_read_failed);
                }
            }
        });
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
            if (enabled == null) return false;
            return enabled.toLowerCase(Locale.US).contains(getPackageName().toLowerCase(Locale.US));
        } catch (Throwable error) {
            Prefs.recordError(this, getString(R.string.error_check_accessibility), error);
            return false;
        }
    }
}
