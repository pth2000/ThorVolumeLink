package io.github.thorvolume.control;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** 由已连接的按键无障碍服务显示、不获取焦点的短暂反馈悬浮层。 */
final class FeedbackOverlay {
    private static final long DISPLAY_MS = 1200L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<FeedbackOverlay> active = new WeakReference<FeedbackOverlay>(null);

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private TextView messageView;
    private boolean attached;
    private int animationGeneration;

    private final Runnable hide = new Runnable() {
        @Override public void run() {
            if (!attached || messageView == null) return;
            final int generation = ++animationGeneration;
            messageView.animate().cancel();
            messageView.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (generation == animationGeneration) removeView();
                        }
                    })
                    .start();
        }
    };

    FeedbackOverlay(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
        active = new WeakReference<FeedbackOverlay>(this);
    }

    static boolean show(String text) {
        final FeedbackOverlay overlay = active.get();
        if (overlay == null || text == null || text.length() == 0 || Build.VERSION.SDK_INT < 22) {
            return false;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return overlay.showNow(text);
        final String safeText = text;
        MAIN.post(new Runnable() {
            @Override public void run() { overlay.showNow(safeText); }
        });
        return true;
    }

    private boolean showNow(String text) {
        if (windowManager == null) return false;
        try {
            if (messageView == null) messageView = createView();
            messageView.setText(text);
            animationGeneration++;
            messageView.animate().cancel();
            if (!attached) {
                messageView.setAlpha(0f);
                windowManager.addView(messageView, createLayoutParams());
                attached = true;
                messageView.animate()
                        .alpha(1f)
                        .setDuration(150L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else {
                messageView.setAlpha(1f);
            }
            MAIN.removeCallbacks(hide);
            MAIN.postDelayed(hide, DISPLAY_MS);
            return true;
        } catch (Throwable error) {
            attached = false;
            Prefs.recordError(service, service.getString(R.string.error_feedback_overlay), error);
            return false;
        }
    }

    private TextView createView() {
        TextView view = new TextView(service);
        view.setBackgroundResource(R.drawable.feedback_overlay_background);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(1);
        view.setMinHeight(dp(48));
        view.setPadding(dp(20), dp(10), dp(20), dp(10));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(8));
        return view;
    }

    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(72);
        params.setTitle("Thor Volume Link feedback");
        return params;
    }

    void destroy() {
        animationGeneration++;
        MAIN.removeCallbacks(hide);
        if (messageView != null) messageView.animate().cancel();
        removeView();
        FeedbackOverlay current = active.get();
        if (current == this) active.clear();
    }

    private void removeView() {
        if (!attached || messageView == null || windowManager == null) return;
        try {
            windowManager.removeView(messageView);
        } catch (Throwable ignored) {
        } finally {
            attached = false;
            messageView.setAlpha(1f);
        }
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
