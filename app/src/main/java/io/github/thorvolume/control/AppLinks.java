package io.github.thorvolume.control;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

/** 统一管理对外项目链接，仓库地址变化时只需修改字符串资源。 */
final class AppLinks {
    private AppLinks() {}

    static void openProject(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(activity.getString(R.string.project_url)));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(intent);
        } catch (Throwable error) {
            Prefs.recordError(activity, activity.getString(R.string.error_open_link), error);
            Ui.toast(activity, activity.getString(R.string.link_open_failed));
        }
    }
}
