package io.github.thorvolume.control;

/**
 * 监听副屏控制后端的连接或授权状态变化。
 *
 * <p>Standard 版主要由 Shizuku Binder 生命周期触发；Lite 版没有外部连接，
 * 因此不会主动回调。</p>
 */
interface BackendStatusListener {
    /** 通知界面重新读取后端状态。 */
    void onStatusChanged();
}
