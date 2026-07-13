package io.github.thorvolume.control;

import android.os.Bundle;

/** 运行在 Shizuku UserService 进程中的副屏音量接口。 */
interface ISecondaryVolumeService {
    Bundle getVolume() = 0;
    Bundle setVolume(int value) = 1;
    Bundle adjustVolume(int delta) = 2;

    // Shizuku 约定销毁方法使用 AIDL ID 16777114，生成后的 Binder code 为 16777115。
    void destroy() = 16777114;
}
