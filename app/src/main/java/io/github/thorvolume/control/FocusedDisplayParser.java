package io.github.thorvolume.control;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 Input Dispatcher 的转储信息中取出当前聚焦的第一个逻辑屏幕 ID。 */
final class FocusedDisplayParser {
    private static final Pattern FOCUSED_DISPLAY = Pattern.compile(
            "(?m)^\\s*FocusedDisplayId:\\s*(-?\\d+)\\s*$");

    private FocusedDisplayParser() {}

    static int parse(String dump) {
        if (dump == null || dump.length() == 0) {
            throw new IllegalStateException("dumpsys input returned no output");
        }
        Matcher matcher = FOCUSED_DISPLAY.matcher(dump);
        if (!matcher.find()) {
            throw new IllegalStateException("FocusedDisplayId is missing from dumpsys input");
        }
        int displayId = Integer.parseInt(matcher.group(1));
        if (displayId < 0) {
            throw new IllegalStateException("FocusedDisplayId is invalid: " + displayId);
        }
        // 只取第一个，后续同名字段属于上次 ANR 快照。
        return displayId;
    }
}
