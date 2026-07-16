package io.github.thorvolume.control;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

/**
 * 捕获实体模式键与音量键的无障碍服务。
 *
 * <p>服务只请求按键过滤能力，不读取窗口内容。主屏模式把音量键交还系统；
 * 副屏、联动和焦点跟随模式会按需消费音量键，并委托 {@link VolumeControl} 完成实际调整。</p>
 */
public final class ThorKeyService extends AccessibilityService {
    private static final long FALLBACK_REPEAT_DELAY_MS = 50L;
    /** AudioService 在媒体音量变化后发送的系统广播；Android 未公开对应常量。 */
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE =
            "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final long SCREEN_OFF_SYNC_WAKE_MS = 5000L;

    private Handler handler;
    private FeedbackOverlay feedbackOverlay;
    // 模式键按下期间的状态；同时记录 keyCode 与 scanCode，以识别对应的抬起事件。
    private boolean switchHeld;
    private boolean switchLongTriggered;
    private int heldSwitchCode;
    private int heldSwitchScan;
    private int capturedCode;
    private int capturedScan;
    private boolean volumeHeld;
    private boolean heldVolumeIncrease;
    /** 一次按住期间使用的实际目标模式，避免焦点变化导致长按途中切换屏幕。 */
    private int heldVolumeMode = Prefs.MODE_MAIN;
    private int heldVolumeCode;
    private int heldVolumeScan;
    /** AYN focus_change 的最新值；奇数为上屏，偶数为下屏。 */
    private volatile long focusChangeValue = FocusChangeSetting.MISSING_VALUE;
    private ContentObserver focusChangeObserver;
    private boolean focusChangeObserverRegistered;
    private boolean focusChangeErrorRecorded;
    private boolean volumeReceiverRegistered;
    private boolean screenOffSyncInFlight;
    private int pendingScreenOffSecondary = -1;
    private int appliedScreenOffSecondary = -1;
    private PowerManager.WakeLock screenOffSyncWakeLock;

    /**
     * 熄屏时系统不会把音量键送入无障碍过滤链，但仍会改变媒体音量。
     * 联动模式通过监听这个结果，把最新的绝对值补同步到副屏。
     */
    private final BroadcastReceiver volumeChangedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_VOLUME_CHANGED.equals(intent.getAction())) return;
            int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
            if (stream != AudioManager.STREAM_MUSIC) return;
            if (Prefs.getMode(ThorKeyService.this) != Prefs.MODE_SYNC) return;
            if (isDeviceInteractive()) return;
            requestScreenOffSync();
        }
    };

    /** 到达用户配置的长按阈值后，循环切换四种音量键模式。 */
    private final Runnable switchLongPress = new Runnable() {
        @Override public void run() {
            if (!switchHeld) return;
            try {
                switchLongTriggered = true;
                int mode = Prefs.nextMode(ThorKeyService.this);
                if (mode == Prefs.MODE_SYNC) {
                    VolumeControl.syncSecondaryToMain(ThorKeyService.this, false, null);
                }
                giveModeFeedback(mode);
            } catch (Throwable error) {
                Prefs.recordError(ThorKeyService.this, getString(R.string.error_switch_mode), error);
                Ui.toast(ThorKeyService.this, getString(R.string.mode_switch_failed));
            }
        }
    };

    /** 不依赖设备产生 repeatCount，由服务自行驱动长按连续调节。 */
    private final Runnable volumeRepeat = new Runnable() {
        @Override public void run() {
            if (!volumeHeld) return;
            adjustHeldVolume(heldVolumeMode);
            if (volumeHeld && handler != null) {
                handler.postDelayed(this, systemKeyRepeatDelay());
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        stopFocusChangeTracking();
        handler = new Handler(Looper.getMainLooper());
        if (feedbackOverlay != null) feedbackOverlay.destroy();
        feedbackOverlay = new FeedbackOverlay(this);
        registerVolumeReceiver();
        startFocusChangeTracking();
    }

    @Override public boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        try {
            // 录入模式优先级最高，防止待绑定按键先触发原有功能。
            int captureTarget = Prefs.getCaptureTarget(this);
            if (captureTarget != Prefs.CAPTURE_NONE || isCapturedRelease(event)) {
                return handleCapture(event, captureTarget);
            }

            if (Prefs.isModeKeyEnabled(this)) {
                Prefs.Binding switchBinding = Prefs.getSwitchBinding(this);
                // 按下后继续按已记录值匹配，避免录入配置在按住期间发生变化。
                if (switchBinding.matches(event) || (switchHeld && matchesHeldSwitch(event))) {
                    return handleSwitch(event);
                }
            } else if (switchHeld) {
                // 用户关闭模式键后立即停止拦截，避免保留未完成的长按任务。
                cancelHeldSwitch();
            }

            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) return handleVolume(event, true);
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) return handleVolume(event, false);
            return false;
        } catch (Throwable error) {
            Prefs.recordError(this, getString(R.string.error_key_event, Integer.valueOf(event.getKeyCode())), error);
            Ui.toast(this, getString(R.string.key_processing_failed));
            return true;
        }
    }

    private boolean handleCapture(KeyEvent event, int target) {
        // 只在第一次 ACTION_DOWN 时保存，忽略系统产生的长按重复事件。
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && target == Prefs.CAPTURE_SWITCH) {
            int code = event.getKeyCode();
            int scan = event.getScanCode();
            if (Prefs.saveSwitchBinding(this, code, scan)) {
                capturedCode = code;
                capturedScan = scan;
                Ui.toast(this, getString(R.string.key_capture_success, Prefs.bindingLabel(this, new Prefs.Binding(code, scan))));
            } else {
                Prefs.cancelCapture(this);
                Ui.toast(this, getString(R.string.key_capture_invalid));
            }
            return true;
        }
        if (isCapturedRelease(event)) {
            // 吞掉刚录入按键的抬起事件，避免它落到 Activity 或系统。
            capturedCode = 0;
            capturedScan = 0;
            return true;
        }
        return target != Prefs.CAPTURE_NONE;
    }

    private boolean isCapturedRelease(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_UP) return false;
        if (capturedCode != 0 && event.getKeyCode() == capturedCode) return true;
        return capturedScan > 0 && event.getScanCode() == capturedScan;
    }

    private boolean handleSwitch(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!switchHeld && event.getRepeatCount() == 0) {
                switchHeld = true;
                switchLongTriggered = false;
                heldSwitchCode = event.getKeyCode();
                heldSwitchScan = event.getScanCode();
                if (handler != null) {
                    handler.removeCallbacks(switchLongPress);
                    handler.postDelayed(switchLongPress, Prefs.getHoldMs(this));
                }
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (handler != null) handler.removeCallbacks(switchLongPress);
            boolean wasLong = switchLongTriggered;
            int releasedCode = heldSwitchCode;
            int releasedScan = heldSwitchScan;
            switchHeld = false;
            switchLongTriggered = false;
            heldSwitchCode = 0;
            heldSwitchScan = 0;
            // 长按只切模式；短按则尽量恢复返回、主页或最近任务的原始语义。
            if (!wasLong) performShortAction(new Prefs.Binding(releasedCode, releasedScan));
            return true;
        }
        return true;
    }

    private boolean matchesHeldSwitch(KeyEvent event) {
        if (heldSwitchCode != 0 && event.getKeyCode() == heldSwitchCode) return true;
        return heldSwitchScan > 0 && event.getScanCode() == heldSwitchScan;
    }

    private void cancelHeldSwitch() {
        if (handler != null) handler.removeCallbacks(switchLongPress);
        switchHeld = false;
        switchLongTriggered = false;
        heldSwitchCode = 0;
        heldSwitchScan = 0;
    }

    private void performShortAction(Prefs.Binding binding) {
        int code = binding == null ? KeyEvent.KEYCODE_UNKNOWN : binding.keyCode;
        if (code == KeyEvent.KEYCODE_BACK) performGlobalAction(GLOBAL_ACTION_BACK);
        else if (code == KeyEvent.KEYCODE_HOME) performGlobalAction(GLOBAL_ACTION_HOME);
        else if (code == KeyEvent.KEYCODE_APP_SWITCH) performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    private boolean handleVolume(KeyEvent event, boolean increase) {
        if (volumeHeld && matchesHeldVolume(event)) {
            boolean handledBySystem = heldVolumeMode == Prefs.MODE_MAIN;
            if (event.getAction() == KeyEvent.ACTION_UP) cancelHeldVolume();
            // 忽略设备额外产生的重复 ACTION_DOWN，避免与内部定时器叠加。
            return !handledBySystem;
        }

        int mode = resolveVolumeMode(Prefs.getMode(this));
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // 所有模式都锁定按下时的目标；主屏路径仍交由 Android 原生处理。
            cancelHeldVolume();
            volumeHeld = true;
            heldVolumeIncrease = increase;
            heldVolumeMode = mode;
            heldVolumeCode = event.getKeyCode();
            heldVolumeScan = event.getScanCode();
            if (mode == Prefs.MODE_MAIN) return false;
            adjustHeldVolume(mode);
            if (handler != null) {
                handler.postDelayed(volumeRepeat, systemKeyRepeatTimeout());
            }
        }
        return mode != Prefs.MODE_MAIN;
    }

    private void adjustHeldVolume(int mode) {
        int delta = (heldVolumeIncrease ? 1 : -1) * Prefs.getStep(this);
        if (mode == Prefs.MODE_SYNC) {
            VolumeControl.adjustSynced(this, delta);
        } else {
            VolumeControl.adjustSecondary(this, delta, true, null);
        }
    }

    /** 把焦点跟随模式解析成一次按键实际使用的主屏或副屏目标。 */
    private int resolveVolumeMode(int configuredMode) {
        if (configuredMode != Prefs.MODE_FOCUS) return configuredMode;
        return FocusChangeSetting.volumeMode(focusChangeValue);
    }

    /** 注册 AYN 焦点计数器监听；不再依赖无障碍窗口或触摸事件推断屏幕。 */
    private void startFocusChangeTracking() {
        focusChangeObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                refreshFocusChangeValue();
            }

            @Override public void onChange(boolean selfChange, Uri uri) {
                refreshFocusChangeValue();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    FocusChangeSetting.uri(), false, focusChangeObserver);
            focusChangeObserverRegistered = true;
        } catch (Throwable error) {
            recordFocusChangeError(error);
        }
        refreshFocusChangeValue();
    }

    private void refreshFocusChangeValue() {
        try {
            focusChangeValue = FocusChangeSetting.read(this);
            focusChangeErrorRecorded = false;
        } catch (Throwable error) {
            focusChangeValue = FocusChangeSetting.MISSING_VALUE;
            recordFocusChangeError(error);
        }
    }

    private void recordFocusChangeError(Throwable error) {
        if (focusChangeErrorRecorded) return;
        focusChangeErrorRecorded = true;
        Prefs.recordError(this, getString(R.string.error_track_focus), error);
    }

    private void stopFocusChangeTracking() {
        if (focusChangeObserverRegistered && focusChangeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(focusChangeObserver);
            } catch (Throwable ignored) {}
        }
        focusChangeObserverRegistered = false;
        focusChangeObserver = null;
    }

    private void registerVolumeReceiver() {
        if (volumeReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_VOLUME_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(volumeChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(volumeChangedReceiver, filter);
            }
            volumeReceiverRegistered = true;
        } catch (Throwable error) {
            Prefs.recordError(this, getString(R.string.error_key_event,
                    Integer.valueOf(KeyEvent.KEYCODE_VOLUME_UP)), error);
        }
    }

    private boolean isDeviceInteractive() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return power == null || power.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void requestScreenOffSync() {
        int main = VolumeControl.readMain(this);
        int max = VolumeControl.mainMax(this);
        pendingScreenOffSecondary = (int) Math.round(
                (main * (double) VolumeControl.SECONDARY_MAX) / Math.max(1, max));
        refreshScreenOffSyncWakeLock();
        drainScreenOffSync();
    }

    /** 连续按键只保留最新目标，避免 Shizuku/Root 写入队列在长按时不断堆积。 */
    private void drainScreenOffSync() {
        if (screenOffSyncInFlight) return;
        if (Prefs.getMode(this) != Prefs.MODE_SYNC || pendingScreenOffSecondary < 0) {
            finishScreenOffSync();
            return;
        }
        final int target = pendingScreenOffSecondary;
        pendingScreenOffSecondary = -1;
        if (target == appliedScreenOffSecondary) {
            finishScreenOffSync();
            return;
        }
        screenOffSyncInFlight = true;
        VolumeControl.setSecondary(this, target, false, new VolumeControl.VolumeCallback() {
            @Override public void onComplete(boolean ok, int value, String error) {
                screenOffSyncInFlight = false;
                if (ok) appliedScreenOffSecondary = value;
                if (pendingScreenOffSecondary >= 0
                        && pendingScreenOffSecondary != appliedScreenOffSecondary) {
                    drainScreenOffSync();
                } else {
                    finishScreenOffSync();
                }
            }
        });
    }

    private void refreshScreenOffSyncWakeLock() {
        try {
            if (screenOffSyncWakeLock == null) {
                PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (power == null) return;
                screenOffSyncWakeLock = power.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "ThorVolumeLink:screenOffSync");
                screenOffSyncWakeLock.setReferenceCounted(false);
            }
            if (screenOffSyncWakeLock.isHeld()) screenOffSyncWakeLock.release();
            screenOffSyncWakeLock.acquire(SCREEN_OFF_SYNC_WAKE_MS);
        } catch (Throwable ignored) {}
    }

    private void finishScreenOffSync() {
        pendingScreenOffSecondary = -1;
        try {
            if (screenOffSyncWakeLock != null && screenOffSyncWakeLock.isHeld()) {
                screenOffSyncWakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    private boolean matchesHeldVolume(KeyEvent event) {
        if (heldVolumeCode != 0 && event.getKeyCode() == heldVolumeCode) return true;
        return heldVolumeScan > 0 && event.getScanCode() == heldVolumeScan;
    }

    private void cancelHeldVolume() {
        if (handler != null) handler.removeCallbacks(volumeRepeat);
        volumeHeld = false;
        heldVolumeIncrease = false;
        heldVolumeMode = Prefs.MODE_MAIN;
        heldVolumeCode = 0;
        heldVolumeScan = 0;
    }

    /** 使用当前设备与系统设置实际采用的首次按键重复等待时间。 */
    private static long systemKeyRepeatTimeout() {
        try {
            return Math.max(1, ViewConfiguration.getKeyRepeatTimeout());
        } catch (Throwable ignored) {
            return Math.max(1, ViewConfiguration.getLongPressTimeout());
        }
    }

    /** 使用当前设备与系统设置实际采用的连续按键重复间隔。 */
    private static long systemKeyRepeatDelay() {
        try {
            return Math.max(1, ViewConfiguration.getKeyRepeatDelay());
        } catch (Throwable ignored) {
            return FALLBACK_REPEAT_DELAY_MS;
        }
    }

    private void giveModeFeedback(int mode) {
        FeedbackDisplay.show(this,
                getString(R.string.mode_changed, Prefs.modeLabel(this, mode)));
        if (!Prefs.isVibrationFeedbackEnabled(this)) return;
        try {
            // 用不同段数表示四种模式，便于不看屏幕操作。
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null) return;
            if (mode == Prefs.MODE_MAIN) vibrator.vibrate(70L);
            else if (mode == Prefs.MODE_SECONDARY) vibrator.vibrate(new long[] {0L, 55L, 55L, 55L}, -1);
            else if (mode == Prefs.MODE_SYNC) vibrator.vibrate(new long[] {0L, 45L, 45L, 45L, 45L, 45L}, -1);
            else vibrator.vibrate(new long[] {0L, 40L, 40L, 40L, 40L, 40L, 40L, 40L}, -1);
        } catch (Throwable error) {
            Prefs.recordError(this, getString(R.string.error_vibration), error);
        }
    }

    /** 本服务只使用无障碍按键过滤能力，不订阅或处理界面事件。 */
    @Override public void onAccessibilityEvent(AccessibilityEvent ignored) {}

    @Override public void onInterrupt() {
        cancelHeldSwitch();
        cancelHeldVolume();
    }

    @Override public void onDestroy() {
        if (handler != null) handler.removeCallbacks(switchLongPress);
        cancelHeldVolume();
        stopFocusChangeTracking();
        if (volumeReceiverRegistered) {
            try {
                unregisterReceiver(volumeChangedReceiver);
            } catch (Throwable ignored) {}
            volumeReceiverRegistered = false;
        }
        finishScreenOffSync();
        if (feedbackOverlay != null) {
            feedbackOverlay.destroy();
            feedbackOverlay = null;
        }
        super.onDestroy();
    }
}
