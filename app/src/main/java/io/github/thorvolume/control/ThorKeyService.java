package io.github.thorvolume.control;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 捕获实体模式键与音量键的无障碍服务。
 *
 * <p>服务只请求按键过滤能力，不读取窗口内容。主屏模式把音量键交还系统；
 * 副屏和联动模式会消费音量键，并委托 {@link VolumeControl} 完成实际调整。</p>
 */
public final class ThorKeyService extends AccessibilityService {
    /** 某些设备会高频派发长按重复事件，用时间门限避免一次产生多档跳变。 */
    private static final long VOLUME_REPEAT_GUARD_MS = 90L;

    private Handler handler;
    // 模式键按下期间的状态；同时记录 keyCode 与 scanCode，以识别对应的抬起事件。
    private boolean switchHeld;
    private boolean switchLongTriggered;
    private int heldSwitchCode;
    private int heldSwitchScan;
    private int capturedCode;
    private int capturedScan;
    private long lastVolumeAdjustAt;

    /** 到达用户配置的长按阈值后，循环切换主屏、副屏和联动模式。 */
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

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
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
        int mode = Prefs.getMode(this);
        // 返回 false 让 Android 继续按原生路径处理主屏音量。
        if (mode == Prefs.MODE_MAIN) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            long now = SystemClock.uptimeMillis();
            if (event.getRepeatCount() == 0 || now - lastVolumeAdjustAt >= VOLUME_REPEAT_GUARD_MS) {
                lastVolumeAdjustAt = now;
                int delta = (increase ? 1 : -1) * Prefs.getStep(this);
                if (mode == Prefs.MODE_SYNC) {
                    VolumeControl.adjustSynced(this, delta);
                } else {
                    VolumeControl.adjustSecondary(this, delta, true, null);
                }
            }
        }
        return true;
    }

    private void giveModeFeedback(int mode) {
        Ui.toast(this, getString(R.string.mode_changed, Prefs.modeLabel(this, mode)));
        try {
            // 用 1、2、3 段振动分别表示主屏、副屏和联动，便于不看屏幕操作。
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null) return;
            if (mode == Prefs.MODE_MAIN) vibrator.vibrate(70L);
            else if (mode == Prefs.MODE_SECONDARY) vibrator.vibrate(new long[] {0L, 55L, 55L, 55L}, -1);
            else vibrator.vibrate(new long[] {0L, 45L, 45L, 45L, 45L, 45L}, -1);
        } catch (Throwable error) {
            Prefs.recordError(this, getString(R.string.error_vibration), error);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (handler != null) handler.removeCallbacks(switchLongPress);
        super.onDestroy();
    }
}
