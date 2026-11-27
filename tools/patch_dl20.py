# -*- coding: utf-8 -*-
# 补丁20：修 Alert 去标题栏监听——挂载后立即检查当前 window，windowProperty 通知 + runLater 兜底。
import io
UI = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\Ui.java"
with io.open(UI, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")

old = """        // 去掉 Alert 外层系统装饰标题栏：监听场景/窗口绑定，在 Stage 显示前改为无装饰样式。
        try {
            javafx.scene.control.DialogPane dp = a.getDialogPane();
            dp.sceneProperty().addListener((obs, oldSc, sc) -> {
                if (sc == null) return;
                sc.windowProperty().addListener((o2, oldW, win) -> {
                    if (win instanceof javafx.stage.Stage st) {
                        try { st.initStyle(javafx.stage.StageStyle.UNDECORATED); } catch (Exception ignored) { }
                    }
                });
            });
        } catch (Exception ignored) {
        }"""

new = """        // 去掉 Alert 外层系统装饰标题栏：在 Stage 显示前改为无装饰样式。
        // 挂载后立即检查当前 window；scene/window 绑定通知 + 一次线程兜底，避免错过初始值。
        try {
            javafx.scene.control.DialogPane dp = a.getDialogPane();
            Runnable applyStyle = () -> {
                try {
                    javafx.stage.Window w = dp.getScene() == null ? null : dp.getScene().getWindow();
                    if (w instanceof javafx.stage.Stage st) {
                        st.initStyle(javafx.stage.StageStyle.UNDECORATED);
                    }
                } catch (Exception ignored) {
                }
            };
            dp.sceneProperty().addListener((obs, oldSc, sc) -> {
                if (sc == null) return;
                sc.windowProperty().addListener((o2, oldW, win) -> applyStyle.run());
                applyStyle.run();
                Platform.runLater(applyStyle);
            });
            applyStyle.run();
        } catch (Exception ignored) {
        }"""

if old not in s:
    raise SystemExit("old block not found")
s = s.replace(old, new, 1)
with io.open(UI, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK Ui styleDialog")
