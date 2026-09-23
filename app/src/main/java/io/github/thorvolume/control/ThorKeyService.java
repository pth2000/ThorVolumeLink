package io.github.thorvolume.control;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
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
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

/**
 * 捕获实体模式键与音量键的无障碍服务。
 *
 * <p>服务请求按键过滤能力，并仅使用界面事件的屏幕 ID 校正焦点，不读取窗口内容。
 * 主屏模式把音量键交还系统；
 * 副屏、联动和焦点跟随模式会按需消费音量键，并委托 {@link VolumeControl} 完成实际调整。</p>
 */
public final class ThorKeyService extends AccessibilityService {
    private static final long FALLBACK_REPEAT_DELAY_MS = 50L;
    /** AudioService 在媒体音量变化后发送的系统广播；Android 未公开对应常量。 */
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE =
            "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final long LINKED_SYNC_WAKE_MS = 5000L;
    /** 等待厂商 focus_change 写入与窗口事件收敛，避免把新屏幕与旧计数配对。 */
    private static final long FOCUS_CALIBRATION_SETTLE_MS = 180L;

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
    /** AYN focus_change 的最新值；奇偶与屏幕的映射会按开机周期校正。 */
    private volatile long focusChangeValue = FocusChangeSetting.MISSING_VALUE;
    private ContentObserver focusChangeObserver;
    private boolean focusChangeObserverRegistered;
    private boolean focusChangeErrorRecorded;
    private boolean focusCalibrationInFlight;
    private boolean focusCalibrationPending;
    private int pendingAccessibilityDisplay = -1;
    /** 两种候选奇偶映射分别从哪些屏幕取得过一致的直接交互证据。 */
    private final int[] accessibilityEvidence = new int[2];
    private boolean volumeReceiverRegistered;
    /** 副屏相对调整的后端写入尚未返回时，累计后续按键步进而不是继续排队。 */
    private boolean secondaryAdjustInFlight;
    private int pendingSecondaryDelta;
    private boolean linkedSyncInFlight;
    private int linkedSyncTarget = -1;
    private int pendingLinkedSecondary = -1;
    /** 最近一次已知的主屏档位；保持平衡模式用它判断副屏是否被手动改过。 */
    private int lastMainVolume = -1;
    private PowerManager.WakeLock linkedSyncWakeLock;

    /**
     * 系统媒体音量变化广播既用于补偿熄屏按键，也可在用户启用自动跟随时
     * 覆盖音量面板、媒体应用等非本服务发起的主屏音量变化。
     */
    private final BroadcastReceiver volumeChangedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_VOLUME_CHANGED.equals(intent.getAction())) return;
            int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
            if (stream != AudioManager.STREAM_MUSIC) return;
            // 无论本次是否需要同步，都先记录主屏档位，供之后的平衡学习使用。
            int previousMain = lastMainVolume;
            lastMainVolume = VolumeControl.readMain(ThorKeyService.this);
            if (Prefs.getMode(ThorKeyService.this) != Prefs.MODE_SYNC) return;
            boolean interactive = isDeviceInteractive();
            if (interactive && !Prefs.isLinkedAutoFollowEnabled(ThorKeyService.this)) return;
            // 用变化前的主屏档位核对副屏，避免把主屏刚发生的变化误认为用户改了平衡。
            if (previousMain >= 0 && isLinkedSyncIdle()) {
                VolumeControl.learnLinkedBalance(ThorKeyService.this, previousMain,
                        VolumeControl.mainMax(ThorKeyService.this));
            }
            requestLinkedSync(!interactive);
        }
    };

    /** 到达用户配置的长按阈值后，循环切换四种音量键模式。 */
    private final Runnable switchLongPress = new Runnable() {
        @Override public void run() {
            if (!switchHeld) return;
            try {
                switchLongTriggered = true;
                int mode = Prefs.nextMode(ThorKeyService.this);
                if (mode == Prefs.MODE_SYNC
                        && !Prefs.isLinkedBalanceEnabled(ThorKeyService.this)) {
                    // 保持平衡模式下不主动对齐，以免抹掉用户已经调好的音量差。
                    VolumeControl.syncSecondaryToMain(ThorKeyService.this, false, null);
                } else if (mode == Prefs.MODE_FOCUS) {
                    ensurePrivilegedFocusCalibration();
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

    /** 无障碍事件与 Settings 写入可能先后到达，稍后再使用最新计数建立锚点。 */
    private final Runnable accessibilityFocusCalibration = new Runnable() {
        @Override public void run() {
            int displayId = pendingAccessibilityDisplay;
            pendingAccessibilityDisplay = -1;
            if (displayId < 0) return;
            try {
                focusChangeValue = FocusChangeSetting.read(ThorKeyService.this);
                FocusChangeSetting.observe(ThorKeyService.this, focusChangeValue);
                if (!FocusChangeSetting.needsCalibrationFrom(
                        ThorKeyService.this, focusChangeValue,
                        FocusChangeSetting.ANCHOR_ACCESSIBILITY)) return;
                int valueParity = (int) (focusChangeValue & 1L);
                int primaryParity = displayId == Display.DEFAULT_DISPLAY
                        ? valueParity : valueParity ^ 1;
                int displayEvidence = displayId == Display.DEFAULT_DISPLAY ? 1 : 2;
                accessibilityEvidence[primaryParity] |= displayEvidence;
                // 单次界面事件可能来自过渡窗口；上下屏都支持同一映射后才接受。
                if (accessibilityEvidence[primaryParity] == 3) {
                    FocusChangeSetting.calibrateFromDisplay(
                            ThorKeyService.this, displayId,
                            FocusChangeSetting.ANCHOR_ACCESSIBILITY,
                            focusChangeValue);
                }
            } catch (Throwable error) {
                recordFocusChangeError(error);
            }
        }
    };

    /** 按完整组件名判断本版本的按键服务是否已在系统无障碍设置中启用。 */
    static boolean isEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.length() == 0) return false;
            // Standard 的包名是 Lite 包名的前缀，子串匹配会把另一版本的服务误判为本版本。
            ComponentName self = new ComponentName(context, ThorKeyService.class);
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            for (String entry : splitter) {
                if (self.equals(ComponentName.unflattenFromString(entry))) return true;
            }
            return false;
        } catch (Throwable error) {
            Prefs.recordError(context, context.getString(R.string.error_check_accessibility), error);
            return false;
        }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        stopFocusChangeTracking();
        handler = new Handler(Looper.getMainLooper());
        if (feedbackOverlay != null) feedbackOverlay.destroy();
        feedbackOverlay = new FeedbackOverlay(this);
        registerVolumeReceiver();
        lastMainVolume = VolumeControl.readMain(this);
        if (Prefs.getMode(this) == Prefs.MODE_SYNC
                && Prefs.isLinkedAutoFollowEnabled(this)
                && !Prefs.isLinkedBalanceEnabled(this)) {
            requestLinkedSync(false);
        }
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

    /**
     * 按键重复由定时器驱动，不等待后端返回。Shizuku/Root 每次写入都要跨进程，
     * 因此未完成期间的调整需要合并，否则长按会堆积请求、松手后音量继续变化。
     */
    private void adjustHeldVolume(int mode) {
        int delta = (heldVolumeIncrease ? 1 : -1) * Prefs.getStep(this);
        if (mode == Prefs.MODE_SYNC) {
            // 调整前先核对副屏是否被手动改过；长按连续写入期间跳过，避免读到旧值。
            if (isLinkedSyncIdle()) {
                VolumeControl.learnLinkedBalance(
                        this, VolumeControl.readMain(this), VolumeControl.mainMax(this));
            }
            // 主屏由 AudioManager 同步完成；副屏目标与自动跟随共用同一个合并队列。
            lastMainVolume = VolumeControl.adjustMain(
                    this, delta, Prefs.isLinkedSystemVolumeUiEnabled(this));
            requestLinkedSync(false);
        } else {
            pendingSecondaryDelta += delta;
            drainSecondaryAdjust();
        }
    }

    /** 上一次写入完成前累计步进，完成后一次性写入，保证每次按键都被计入。 */
    private void drainSecondaryAdjust() {
        if (secondaryAdjustInFlight || pendingSecondaryDelta == 0) return;
        final int delta = pendingSecondaryDelta;
        pendingSecondaryDelta = 0;
        secondaryAdjustInFlight = true;
        VolumeControl.adjustSecondary(this, delta, true, new VolumeControl.VolumeCallback() {
            @Override public void onComplete(boolean ok, int value, String error) {
                secondaryAdjustInFlight = false;
                drainSecondaryAdjust();
            }
        });
    }

    /** 把焦点跟随模式解析成一次按键实际使用的主屏或副屏目标。 */
    private int resolveVolumeMode(int configuredMode) {
        if (configuredMode != Prefs.MODE_FOCUS) return configuredMode;
        if (!FocusChangeSetting.isCalibrated(this, focusChangeValue)) {
            ensurePrivilegedFocusCalibration();
            return Prefs.MODE_MAIN;
        }
        return FocusChangeSetting.volumeMode(this, focusChangeValue);
    }

    /** 注册 AYN 焦点计数器监听；屏幕奇偶映射由分级校正来源提供。 */
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
            FocusChangeSetting.observe(this, focusChangeValue);
            boolean needsAccessibility = FocusChangeSetting.needsCalibrationFrom(
                    this, focusChangeValue, FocusChangeSetting.ANCHOR_ACCESSIBILITY);
            boolean calibrated = FocusChangeSetting.isCalibrated(this, focusChangeValue);
            if (needsAccessibility && pendingAccessibilityDisplay >= 0 && handler != null) {
                handler.removeCallbacks(accessibilityFocusCalibration);
                handler.postDelayed(accessibilityFocusCalibration, FOCUS_CALIBRATION_SETTLE_MS);
            } else if (!needsAccessibility && pendingAccessibilityDisplay >= 0) {
                if (handler != null) handler.removeCallbacks(accessibilityFocusCalibration);
                pendingAccessibilityDisplay = -1;
            }
            if (!calibrated && Prefs.getMode(this) == Prefs.MODE_FOCUS) {
                ensurePrivilegedFocusCalibration();
            }
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
        if (handler != null) handler.removeCallbacks(accessibilityFocusCalibration);
        pendingAccessibilityDisplay = -1;
        accessibilityEvidence[0] = 0;
        accessibilityEvidence[1] = 0;
        focusCalibrationInFlight = false;
        focusCalibrationPending = false;
    }

    /** 缺少可靠映射时启动系统查询；Lite 版会直接返回。 */
    private void ensurePrivilegedFocusCalibration() {
        if (!FocusChangeSetting.needsCalibrationFrom(
                this, focusChangeValue, FocusChangeSetting.ANCHOR_PRIVILEGED)) return;
        requestPrivilegedFocusCalibration();
    }

    /** Standard 版通过 Input Dispatcher 为本次开机建立一次映射。 */
    private void requestPrivilegedFocusCalibration() {
        if (!SecondaryVolumeGateway.canReadFocusedDisplay()) return;
        if (focusCalibrationInFlight) {
            focusCalibrationPending = true;
            return;
        }
        focusCalibrationInFlight = true;
        focusCalibrationPending = false;
        final long valueBefore = FocusChangeSetting.read(this);
        SecondaryVolumeGateway.readFocusedDisplay(this, new SecondaryVolumeCallback() {
            @Override public void onComplete(boolean ok, int displayId, String error) {
                focusCalibrationInFlight = false;
                boolean retry = focusCalibrationPending;
                focusCalibrationPending = false;
                long valueAfter = FocusChangeSetting.read(ThorKeyService.this);
                focusChangeValue = valueAfter;
                // 计数不可用时没有可校正的对象，不能重试，否则会无限循环执行 dumpsys。
                if (ok && displayId >= 0 && FocusChangeSetting.isAvailable(valueAfter)) {
                    if (valueBefore == valueAfter) {
                        FocusChangeSetting.calibrateFromDisplay(
                                ThorKeyService.this, displayId,
                                FocusChangeSetting.ANCHOR_PRIVILEGED, valueAfter);
                    } else {
                        // 查询期间发生焦点切换，丢弃不一致快照并在稳定后重试。
                        retry = true;
                    }
                }
                if (retry) ensurePrivilegedFocusCalibration();
            }
        });
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

    private boolean isLinkedSyncIdle() {
        return !linkedSyncInFlight && pendingLinkedSecondary < 0;
    }

    private void requestLinkedSync(boolean keepAwake) {
        int target = VolumeControl.linkedTarget(
                this, VolumeControl.readMain(this), VolumeControl.mainMax(this));
        // 若最新目标与正在写入的目标相同，无需再追加一次相同写入。
        pendingLinkedSecondary = linkedSyncInFlight && target == linkedSyncTarget ? -1 : target;
        if (keepAwake) refreshLinkedSyncWakeLock();
        drainLinkedSync();
    }

    /** 连续变化只保留最新目标，避免 Shizuku/Root 写入队列不断堆积。 */
    private void drainLinkedSync() {
        if (linkedSyncInFlight) return;
        if (Prefs.getMode(this) != Prefs.MODE_SYNC || pendingLinkedSecondary < 0) {
            finishLinkedSync();
            return;
        }
        final int target = pendingLinkedSecondary;
        pendingLinkedSecondary = -1;
        linkedSyncInFlight = true;
        linkedSyncTarget = target;
        VolumeControl.setSecondary(this, target, false, new VolumeControl.VolumeCallback() {
            @Override public void onComplete(boolean ok, int value, String error) {
                linkedSyncInFlight = false;
                linkedSyncTarget = -1;
                if (!ok) Ui.toast(ThorKeyService.this, getString(R.string.linked_sync_failed));
                if (pendingLinkedSecondary >= 0) {
                    drainLinkedSync();
                } else {
                    finishLinkedSync();
                }
            }
        });
    }

    private void refreshLinkedSyncWakeLock() {
        try {
            if (linkedSyncWakeLock == null) {
                PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (power == null) return;
                linkedSyncWakeLock = power.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "ThorVolumeLink:linkedSync");
                linkedSyncWakeLock.setReferenceCounted(false);
            }
            if (linkedSyncWakeLock.isHeld()) linkedSyncWakeLock.release();
            linkedSyncWakeLock.acquire(LINKED_SYNC_WAKE_MS);
        } catch (Throwable ignored) {}
    }

    private void finishLinkedSync() {
        pendingLinkedSecondary = -1;
        try {
            if (linkedSyncWakeLock != null && linkedSyncWakeLock.isHeld()) {
                linkedSyncWakeLock.release();
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

    /** 只取事件来源屏幕 ID 并延迟与计数配对，不读取窗口内容。 */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || Build.VERSION.SDK_INT < 33) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        if (!FocusChangeSetting.needsCalibrationFrom(
                this, focusChangeValue,
                FocusChangeSetting.ANCHOR_ACCESSIBILITY)) return;
        int displayId = event.getDisplayId();
        if (displayId < 0) return;
        pendingAccessibilityDisplay = displayId;
        if (handler != null) {
            handler.removeCallbacks(accessibilityFocusCalibration);
            handler.postDelayed(accessibilityFocusCalibration, FOCUS_CALIBRATION_SETTLE_MS);
        }
    }

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
        finishLinkedSync();
        if (feedbackOverlay != null) {
            feedbackOverlay.destroy();
            feedbackOverlay = null;
        }
        super.onDestroy();
    }
}
