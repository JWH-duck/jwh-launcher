package com.jwh.launcher;

import javafx.application.Application;

/**
 * 程序入口：JavaFX 应用。
 * 构建后通过 java -jar JWH-Launcher.jar 运行，或 IDE 直接运行本类。
 */
public class Main {

    public static void main(String[] args) {
        // Modrinth api/cdn 域名的 IPv4/IPv6 可达性会随网络时段反转，JDK HttpClient 又不做
        // 多地址回退；可达性择优由 ModrinthReachableResolver（SPI）按 TCP 探测自动排序处理，
        // 这里不设全局 preferIPv6Addresses，避免在纯 IPv4 网络上押错族。
        Application.launch(LauncherApp.class, args);
    }
}
