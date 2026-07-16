package io.github.thorvolume.control;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;

/** 在任何页面创建前应用用户选择的全局外观设置。 */
public final class ThorApplication extends Application {
    private static int startedActivities;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) { startedActivities++; }
            @Override public void onActivityStopped(Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
        FeedbackDisplay.initialize(this);
        Prefs.migrateDefaults(this);
        int mode = Prefs.getNightMode(this);
        if (mode == Prefs.NIGHT_LIGHT) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if (mode == Prefs.NIGHT_DARK) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    static boolean isAppForeground() {
        return startedActivities > 0;
    }
}
