package io.github.thorvolume.control;

import android.content.Context;
import android.media.AudioManager;

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
        SecondaryVolumeGateway.read(context, wrap(context, false, callback));
    }

    static void adjustSecondary(final Context context, int delta, final boolean showFeedback, final VolumeCallback callback) {
        SecondaryVolumeGateway.adjust(context, delta, wrap(context, showFeedback, callback));
    }

    static void setSecondary(final Context context, int value, final boolean showFeedback, final VolumeCallback callback) {
        SecondaryVolumeGateway.set(context, clamp(value), wrap(context, showFeedback, callback));
    }

    private static SecondaryVolumeCallback wrap(final Context context, final boolean showFeedback, final VolumeCallback callback) {
        // 将 Flavor 后端的裸回调转换成统一的错误持久化、Toast 和业务回调。
        return new SecondaryVolumeCallback() {
            @Override public void onComplete(boolean ok, int value, String error) {
                if (ok) {
                    Prefs.clearLastError(context);
                    if (showFeedback && FeedbackNotifications.shouldShowFeedback(context)) {
                        Ui.toast(context, context.getString(R.string.secondary_feedback, Integer.valueOf(value)));
                    }
                } else {
                    String detail = error == null ? "" : error;
                    Prefs.recordError(context, context.getString(R.string.error_write_secondary), new IllegalStateException(detail));
                    if (showFeedback) Ui.toast(context, detail.length() == 0 ? context.getString(R.string.secondary_adjust_failed) : detail);
                }
                if (callback != null) callback.onComplete(ok, value, error);
            }
        };
    }

    static void adjustSynced(final Context context, int delta) {
        final int main = adjustMain(context, delta, true);
        final int max = mainMax(context);
        // 以百分比映射不同的档位范围，例如主屏 12/25 会对应副屏约 7/15。
        final int secondary = (int) Math.round((main * 15.0d) / Math.max(1, max));
        setSecondary(context, secondary, false, new VolumeCallback() {
            @Override public void onComplete(boolean ok, int value, String error) {
                // 系统音量面板已经反馈主屏变化，联动成功时不再弹出重复提示。
                if (!ok) Ui.toast(context, context.getString(R.string.linked_sync_failed));
            }
        });
    }

    /** 立即将副屏设置为主屏当前音量所对应的相对比例。 */
    static void syncSecondaryToMain(final Context context, final boolean showFeedback, final VolumeCallback callback) {
        final int main = readMain(context);
        final int max = mainMax(context);
        int secondary = (int) Math.round((main * 15.0d) / Math.max(1, max));
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
