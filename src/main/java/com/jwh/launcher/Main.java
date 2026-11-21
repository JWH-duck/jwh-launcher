package com.jwh.launcher;

import javafx.application.Application;

/**
 * 程序入口：JavaFX 应用。
 * 构建后通过 java -jar JWH-Launcher.jar 运行，或 IDE 直接运行本类。
 */
public class Main {

    public static void main(String[] args) {
        // 实测（2026-11-03）：api.modrinth.com 仅 IPv4 可达（IPv6 连接超时），
        // cdn.modrinth.com IPv4/IPv6 均可达（IPv4 更快）。全局开 IPv6 优先会弄坏搜索/API，
        // 故保持 JVM 默认（IPv4 优先）；下载提速由 Http 层 HTTP/1.1 + 分段实现。
        Application.launch(LauncherApp.class, args);
    }
}
