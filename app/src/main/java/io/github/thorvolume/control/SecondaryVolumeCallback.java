package io.github.thorvolume.control;

/** 副屏音量异步操作的统一完成回调。 */
interface SecondaryVolumeCallback {
    /**
     * @param ok 操作是否成功
     * @param value 成功时读取或写入后的音量值，范围为 0～15
     * @param error 失败原因；成功时为空字符串
     */
    void onComplete(boolean ok, int value, String error);
}
