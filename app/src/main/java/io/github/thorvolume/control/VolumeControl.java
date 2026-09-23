package io.github.thorvolume.control;

import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;

/**
 * 主屏与副屏音量操作的业务层。
 *
 * <p>主屏使用 Android {@link AudioManager}，副屏通过
 * {@link SecondaryVolumeGateway} 交给当前 Flavor 的后端。所有公开给界面和
 * 按键服务的副屏结果都会统一整理错误记录和用户反馈。</p>
 */
final class VolumeControl {
    /** AYN Thor 厂商副屏音量设置的固定范围。 */
    static final int SECONDARY_MIN = 0;
    static final int SECONDARY_MAX = 15;
    static final String SECONDARY_SETTING_KEY = "secondary_screen_volume_level";

    /** 面向业务层调用方的异步音量回调。 */
    interface VolumeCallback {
        void onComplete(boolean ok, int value, String error);
    }

    private VolumeControl() {}

    static int readMain(Context context) {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            return audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable error) {
            Prefs.recordError(context, context.getString(R.string.error_read_main), error);
            return 0;
        }
    }

    static int mainMax(Context context) {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            return audio == null ? 15 : Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } catch (Throwable error) {
            Prefs.recordError(context, context.getString(R.string.error_read_main_max), error);
            return 15;
        }
    }

    static int adjustMain(Context context, int delta, boolean showSystemUi) {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) throw new IllegalStateException("AudioManager unavailable");
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            // 主屏最大档位由设备决定，不能假设同样是 15 档。
            int target = Math.max(0, Math.min(max, current + delta));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, showSystemUi ? AudioManager.FLAG_SHOW_UI : 0);
            return target;
        } catch (Throwable error) {
            Prefs.recordError(context, context.getString(R.string.error_adjust_main), error);
            return readMain(context);
        }
    }

    static void readSecondary(final Context context, final VolumeCallback callback) {
        SecondaryVolumeGateway.read(context,
                wrap(context, false, R.string.error_read_secondary, callback));
    }

    /** 快速判断当前固件是否提供 AYN 副屏音量设置项。 */
    static boolean hasSecondarySetting(Context context) {
        try {
            return Settings.System.getString(context.getContentResolver(),
                    SECONDARY_SETTING_KEY) != null;
        } catch (Throwable error) {
            Prefs.recordError(context, context.getString(R.string.error_read_secondary), error);
            return false;
        }
    }

    static void adjustSecondary(final Context context, int delta, final boolean showFeedback, final VolumeCallback callback) {
        SecondaryVolumeGateway.adjust(context, delta,
                wrap(context, showFeedback, R.string.error_write_secondary, callback));
    }

    static void setSecondary(final Context context, int value, final boolean showFeedback, final VolumeCallback callback) {
        SecondaryVolumeGateway.set(context, clamp(value),
                wrap(context, showFeedback, R.string.error_write_secondary, callback));
    }

    private static SecondaryVolumeCallback wrap(final Context context, final boolean showFeedback,
                                                final int errorStage,
                                                final VolumeCallback callback) {
        // 将 Flavor 后端的裸回调转换成统一的错误持久化、Toast 和业务回调。
        return new SecondaryVolumeCallback() {
            @Override public void onComplete(boolean ok, int value, String error) {
                if (ok) {
                    Prefs.clearLastError(context);
                    if (showFeedback) {
                        FeedbackDisplay.show(context,
                                context.getString(R.string.secondary_feedback, Integer.valueOf(value)));
                    }
                } else {
                    String detail = error == null ? "" : error;
                    Prefs.recordError(context, context.getString(errorStage),
                            new IllegalStateException(detail));
                    if (showFeedback) Ui.toast(context, detail.length() == 0 ? context.getString(R.string.secondary_adjust_failed) : detail);
                }
                if (callback != null) callback.onComplete(ok, value, error);
            }
        };
    }

    /** 主屏档位按百分比映射到副屏 0～15 档，例如主屏 12/25 对应副屏约 7/15。 */
    static int mappedSecondary(int main, int max) {
        return clamp((int) Math.round((main * (double) SECONDARY_MAX) / Math.max(1, max)));
    }

    /**
     * 联动模式下主屏当前档位对应的副屏目标。
     *
     * <p>保持平衡模式会叠加用户手动调出的偏移。主屏归零时两屏一起静音，
     * 主屏再次调高后偏移会自动恢复，而不会像纯相对增减那样在边界处永久漂移。</p>
     */
    static int linkedTarget(Context context, int main, int max) {
        if (main <= 0) return 0;
        int mapped = mappedSecondary(main, max);
        if (!Prefs.isLinkedBalanceEnabled(context)) return mapped;
        return clamp(mapped + Prefs.getLinkedBalance(context));
    }

    /**
     * 保持平衡模式下，把副屏当前值与联动预期值的差记为新的偏移。
     *
     * <p>调用方需保证没有联动写入正在进行，否则读到的可能是尚未落盘的旧值；
     * {@code main} 应是上一次联动写入时的主屏档位，而不是刚刚变化后的值。</p>
     */
    static void learnLinkedBalance(Context context, int main, int max) {
        if (!Prefs.isLinkedBalanceEnabled(context)) return;
        int current = readSecondaryNow(context);
        if (current < 0 || current == linkedTarget(context, main, max)) return;
        Prefs.setLinkedBalance(context, current - mappedSecondary(main, max));
    }

    /** 同步读取副屏当前档位；读取不需要特权，设置项缺失时返回 -1。 */
    private static int readSecondaryNow(Context context) {
        try {
            return clamp(Settings.System.getInt(context.getContentResolver(), SECONDARY_SETTING_KEY));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 立即把副屏对齐到主屏当前音量的相对比例；保持平衡模式下同时清除已记录的偏移。 */
    static void syncSecondaryToMain(final Context context, final boolean showFeedback, final VolumeCallback callback) {
        final int main = readMain(context);
        final int max = mainMax(context);
        if (Prefs.isLinkedBalanceEnabled(context)) Prefs.setLinkedBalance(context, 0);
        int secondary = linkedTarget(context, main, max);
        setSecondary(context, secondary, false, new VolumeCallback() {
            @Override public void onComplete(boolean ok, int value, String error) {
                if (showFeedback) {
                    if (ok) {
                        int percent = (int) Math.round((main * 100.0d) / Math.max(1, max));
                        Ui.toast(context, context.getString(R.string.sync_success, Integer.valueOf(percent)));
                    } else {
                        Ui.toast(context, context.getString(R.string.sync_failed));
                    }
                }
                if (callback != null) callback.onComplete(ok, value, error);
            }
        });
    }

    static int clamp(int value) {
        return Math.max(0, Math.min(15, value));
    }
}
