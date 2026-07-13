package io.github.thorvolume.control;

import android.content.Context;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 运行在 Shizuku shell/root 进程中的副屏音量 UserService。
 *
 * <p>Binder Stub/Proxy 由 {@code ISecondaryVolumeService.aidl} 生成；本类只负责
 * 串行执行 AYN 私有系统设置的读取与写入。</p>
 */
public final class SecondaryVolumeUserService extends ISecondaryVolumeService.Stub {
    private static final String SETTINGS = "/system/bin/settings";
    private static final String KEY = "secondary_screen_volume_level";

    /** 保证并发 Binder 请求中的“读取后增减再写入”是一个原子区段。 */
    private final Object lock = new Object();

    // Shizuku v13 优先使用 Context 构造函数；旧版本使用无参构造函数。
    public SecondaryVolumeUserService() {}
    public SecondaryVolumeUserService(Context context) {}

    @Override
    public Bundle getVolume() {
        synchronized (lock) {
            try {
                return SecondaryVolumeResult.success(readSetting());
            } catch (Throwable error) {
                return SecondaryVolumeResult.failure(error);
            }
        }
    }

    @Override
    public Bundle setVolume(int value) {
        synchronized (lock) {
            try {
                int safe = clamp(value);
                writeSetting(safe);
                return SecondaryVolumeResult.success(safe);
            } catch (Throwable error) {
                return SecondaryVolumeResult.failure(error);
            }
        }
    }

    @Override
    public Bundle adjustVolume(int delta) {
        synchronized (lock) {
            try {
                int target = clamp(readSetting() + delta);
                writeSetting(target);
                return SecondaryVolumeResult.success(target);
            } catch (Throwable error) {
                return SecondaryVolumeResult.failure(error);
            }
        }
    }

    /** Shizuku 在移除旧版本 daemon UserService 时调用。 */
    @Override
    public void destroy() {
        System.exit(0);
    }

    private static int readSetting() throws Exception {
        String output = run(new String[] {SETTINGS, "get", "system", KEY});
        if (output == null) return 0;
        output = output.trim();
        if (output.length() == 0 || "null".equalsIgnoreCase(output)) return 0;
        return clamp(Integer.parseInt(output));
    }

    private static void writeSetting(int value) throws Exception {
        run(new String[] {SETTINGS, "put", "system", KEY, String.valueOf(clamp(value))});
    }

    private static String run(String[] command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (output.length() > 0) output.append('\n');
            output.append(line);
        }
        int exit = process.waitFor();
        reader.close();
        if (exit != 0) {
            throw new IllegalStateException("settings exited with code " + exit
                    + (output.length() == 0 ? "" : " : " + output));
        }
        return output.toString();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(15, value));
    }
}
