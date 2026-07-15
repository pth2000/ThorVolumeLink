package io.github.thorvolume.control;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;

/** 管理后台 Toast 依赖的系统通知授权以及应用内反馈开关。 */
final class FeedbackNotifications {
    static final int REQUEST_CODE = 9418;

    private FeedbackNotifications() {}

    /** 系统关闭应用通知时，Android 也会抑制该应用从后台发送的 Toast。 */
    static boolean areAllowed(Context context) {
        try {
            return NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Throwable ignored) {
            return Build.VERSION.SDK_INT < 33
                    || ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    static boolean shouldShowFeedback(Context context) {
        return Prefs.isNotificationFeedbackEnabled(context) && areAllowed(context);
    }

    /**
     * 新版应用可以直接请求通知权限；旧目标 SDK 的弹窗时机由系统控制，
     * 权限被关闭后只能引导用户到应用通知设置中重新开启。
     */
    static void requestOrOpenSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33
                && activity.getApplicationInfo().targetSdkVersion >= 33) {
            ActivityCompat.requestPermissions(activity,
                    new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
            return;
        }
        openSystemSettings(activity);
    }

    private static void openSystemSettings(Activity activity) {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 26) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName()));
            }
            activity.startActivity(intent);
        } catch (Throwable error) {
            Prefs.recordError(activity, activity.getString(R.string.error_open_notification_settings), error);
            Ui.toast(activity, activity.getString(R.string.notification_settings_failed));
        }
    }
}
