package com.lawrence.travel.infrastructure.lifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.DoubleSupplier;

/** 部署容量85%停止接纳、80%恢复；只控制新增输入/后台领取，读取停止删除不排队。 */
public final class DiskAdmission {
    private final DoubleSupplier used;
    private boolean stopped;

    /**
     * @param probe 确定性容量比例，仅受信装配/测试提供，不来自用户或模型
     */
    public DiskAdmission(DoubleSupplier probe) {
        this.used = probe;
    }

    /**
     * @param roots PG同机卷/附件卷的服务器受信根
     * @return 任一卷达到阈值即停止接纳
     */
    public static DiskAdmission volumes(Path... roots) {
        return new DiskAdmission(
                () -> {
                    double maximum = 0;
                    for (var root : roots)
                        try {
                            Files.createDirectories(root);
                            var fs = Files.getFileStore(root);
                            if (fs.getTotalSpace() <= 0) return 1.0;
                            maximum =
                                    Math.max(
                                            maximum,
                                            1.0
                                                    - (double) fs.getUsableSpace()
                                                            / fs.getTotalSpace());
                        } catch (java.io.IOException failed) {
                            return 1.0;
                        }
                    return maximum;
                });
    }

    /**
     * @return 不明/不可读容量失败关闭，滞回不影响清理器
     */
    public synchronized boolean accepting() {
        double ratio;
        try {
            ratio = used.getAsDouble();
        } catch (RuntimeException unavailable) {
            stopped = true;
            return false;
        }
        if (!Double.isFinite(ratio) || ratio < 0 || ratio >= 0.85) stopped = true;
        else if (ratio <= 0.80) stopped = false;
        return !stopped;
    }
}
