package io.github.thorvolume.control;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** 前台使用 Toast、后台使用无障碍悬浮层的统一屏幕反馈入口。 */
final class FeedbackDisplay {
    private static final String LEGACY_CHANNEL_ID = "key_feedback";
    private static final int LEGACY_NOTIFICATION_ID = 9419;

    private FeedbackDisplay() {}

    /** 清理由短暂采用系统通知的开发版本留下的通知和渠道。 */
    static void initialize(Context context) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            manager.cancel(LEGACY_NOTIFICATION_ID);
            if (Build.VERSION.SDK_INT >= 26) manager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
        } catch (Throwable ignored) {}
    }

    static void show(Context context, String text) {
        if (context == null || text == null || text.length() == 0) return;
        if (!Prefs.isVisualFeedbackEnabled(context)) return;
        if (ThorApplication.isAppForeground() || !FeedbackOverlay.show(text)) {
            Ui.toast(context, text);
        }
    }
}
