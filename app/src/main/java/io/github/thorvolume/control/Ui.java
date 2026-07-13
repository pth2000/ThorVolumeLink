package io.github.thorvolume.control;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** 与具体 Activity 无关的轻量界面反馈工具。 */
final class Ui {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {}

    /**
     * 在主线程显示短 Toast。
     *
     * <p>音量操作可能由工作线程或 Binder 线程完成，因此所有提示都统一切回
     * 主线程；显示失败时写入最近错误，避免异常打断按键服务。</p>
     */
    static void toast(final Context context, final String text) {
        if (context == null || text == null || text.length() == 0) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
                } catch (Throwable error) {
                    Prefs.recordError(context, context.getString(R.string.error_toast), error);
                }
            }
        });
    }
}
