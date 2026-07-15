package io.github.thorvolume.control;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

/** 统一管理对外项目链接，仓库地址变化时只需修改字符串资源。 */
final class AppLinks {
    private AppLinks() {}

    static void openProject(Activity activity) {
        open(activity, activity.getString(R.string.project_url));
    }

    static void openRelease(Activity activity, String url) {
        Uri parsed = Uri.parse(url == null ? "" : url);
        boolean trusted = "https".equalsIgnoreCase(parsed.getScheme())
                && "github.com".equalsIgnoreCase(parsed.getHost())
                && parsed.getPath() != null
                && parsed.getPath().startsWith("/pth2000/ThorVolumeLink/releases/");
        open(activity, trusted ? url : activity.getString(R.string.latest_release_url));
    }

    private static void open(Activity activity, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(intent);
        } catch (Throwable error) {
            Prefs.recordError(activity, activity.getString(R.string.error_open_link), error);
            Ui.toast(activity, activity.getString(R.string.link_open_failed));
        }
    }
}
