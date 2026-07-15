package io.github.thorvolume.control;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/** 在任何页面创建前应用用户选择的全局外观设置。 */
public final class ThorApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
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
}
