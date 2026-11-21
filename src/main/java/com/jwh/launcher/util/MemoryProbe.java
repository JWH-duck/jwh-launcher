package com.jwh.launcher.util;

/**
 * 内存自动分配：按本机物理内存推算安全分配值。
 * 规则：推荐最大 = 物理内存一半，向下对齐到 1GB，下限 2GB、上限 16GB——
 * 上限即"安全域值"：机器内存再大也不把游戏堆开到物理内存一半以上，
 * 避免游戏抢占系统/其他程序内存导致整机卡顿或崩溃。
 */
public class MemoryProbe {

    private MemoryProbe() {
    }

    /** 推荐最大内存（MB）。探测失败（非 HotSpot 平台等）时回退默认 4G。 */
    public static int recommendMaxMemoryMB() {
        long total = totalPhysicalMB();
        if (total <= 0) return 4096;
        long raw = total / 2;
        long aligned = (raw / 1024) * 1024; // 向下对齐到 1GB
        return (int) Math.max(2048, Math.min(16384, aligned));
    }

    /** 推荐最小内存（MB）：最大值的 1/4，对齐 256MB，下限 512、上限 2048。 */
    public static int recommendMinMemoryMB(int maxMB) {
        long raw = maxMB / 4;
        long aligned = (raw / 256) * 256;
        return (int) Math.max(512, Math.min(2048, aligned));
    }

    /** 本机物理内存总量（MB）；探测失败返回 0。 */
    private static long totalPhysicalMB() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return os.getTotalPhysicalMemorySize() / (1024 * 1024);
        } catch (Exception e) {
            return 0;
        }
    }
}
