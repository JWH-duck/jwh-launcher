# -*- coding: utf-8 -*-
# 补丁21：
#  - 用 JwhDialog（无装饰圆角 Stage）替换 Ui 内全部 Alert；
#  - 删除旧 styleDialog 与未用 import；
#  - progressDialog 面板补圆角。
import io, re

UI = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\Ui.java"
with io.open(UI, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")

def rep(src, old, new, tag):
    if old not in src:
        raise SystemExit("NOT FOUND: " + tag)
    return src.replace(old, new, 1)

# 1. 清理未用 import
s = rep(s,
"""import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
""",
"""import javafx.application.Platform;
""", "imports")

# 2. 删除旧 styleDialog 方法（含其上注释），直到「列表滚轮接管」注释前
start = "    /** 弹窗统一样式：放大字号 + 归属主窗口 + 居中置顶。 */"
end = "    /**\n     * 列表滚轮接管"
i0 = s.find(start)
i1 = s.find(end)
if i0 < 0 or i1 < 0:
    raise SystemExit("styleDialog block not found")
s = s[:i0] + s[i1:]

# 3. error
s = rep(s,
"""    public static void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, null, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(title);
        javafx.scene.control.Label body = new javafx.scene.control.Label(msg == null ? "" : msg);
        body.setWrapText(true);
        body.setMaxWidth(460);
        body.setStyle("-fx-font-size:13px;");
        a.getDialogPane().setContent(body);
        a.getDialogPane().setPrefWidth(520);
        styleDialog(a);
        a.showAndWait();
    }""",
"""    public static void error(String title, String msg) {
        javafx.scene.control.Label body = new javafx.scene.control.Label(msg == null ? "" : msg);
        body.setWrapText(true);
        body.setMaxWidth(460);
        body.setStyle("-fx-font-size:13px;");
        new JwhDialog<Void>(title, body, java.util.List.of(
                new JwhDialog.ButtonDef<>("确定", null, true))).showAndWait();
    }""", "error")

# 4. info
s = rep(s,
"""        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(title);
        styleDialog(a);
        a.showAndWait();
    }""",
"""        javafx.scene.control.Label body = new javafx.scene.control.Label(msg == null ? "" : msg);
        body.setWrapText(true);
        body.setMaxWidth(430);
        body.setStyle("-fx-font-size:13px;");
        new JwhDialog<Void>(title, body, java.util.List.of(
                new JwhDialog.ButtonDef<>("确定", null, true))).showAndWait();
    }""", "info")

# 5. confirm
s = rep(s,
"""    public static boolean confirm(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO);
        a.setTitle(title);
        a.setHeaderText(title);
        styleDialog(a);
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }""",
"""    public static boolean confirm(String title, String msg) {
        javafx.scene.control.Label body = new javafx.scene.control.Label(msg == null ? "" : msg);
        body.setWrapText(true);
        body.setMaxWidth(430);
        body.setStyle("-fx-font-size:13px;");
        Boolean ok = new JwhDialog<Boolean>(title, body, java.util.List.of(
                new JwhDialog.ButtonDef<>("确定", Boolean.TRUE, false),
                new JwhDialog.ButtonDef<>("取消", Boolean.FALSE, true))).showAndWait();
        return Boolean.TRUE.equals(ok);
    }""", "confirm")

# 6. packExportOptions 尾部
s = rep(s,
"""        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.YES, ButtonType.NO);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().setContent(box);
        a.getDialogPane().setPrefWidth(520);
        styleDialog(a);
        if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return null;
        return new int[]{saves.isSelected() ? 1 : 0, mr.isSelected() ? 1 : 0};""",
"""        Boolean ok = new JwhDialog<Boolean>(title, box, java.util.List.of(
                new JwhDialog.ButtonDef<>("确定", Boolean.TRUE, false),
                new JwhDialog.ButtonDef<>("取消", Boolean.FALSE, true))).showAndWait();
        if (!Boolean.TRUE.equals(ok)) return null;
        return new int[]{saves.isSelected() ? 1 : 0, mr.isSelected() ? 1 : 0};""", "packExport")

# 7. promptText 尾部（连同 okType/cancelType 定义一起替换）
s = rep(s,
"""        javafx.scene.control.ButtonType okType =
                new javafx.scene.control.ButtonType("确定", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancelType =
                new javafx.scene.control.ButtonType("取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", okType, cancelType);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().setContent(box);
        a.getDialogPane().setPrefWidth(500);
        styleDialog(a);
        // 弹窗打开后光标直接落在输入框里
        javafx.application.Platform.runLater(field::requestFocus);
        if (a.showAndWait().orElse(cancelType) != okType) return null;""",
"""        Boolean ok = new JwhDialog<Boolean>(title, box, java.util.List.of(
                new JwhDialog.ButtonDef<>("确定", Boolean.TRUE, false),
                new JwhDialog.ButtonDef<>("取消", Boolean.FALSE, true))).showAndWait();
        // 弹窗打开后光标直接落在输入框里
        javafx.application.Platform.runLater(field::requestFocus);
        if (!Boolean.TRUE.equals(ok)) return null;""", "promptText")

# 8. progressDialog 面板圆角
s = rep(s,
"""        box.setStyle("-fx-background-color:" + BG + ";");""",
"""        box.setStyle("-fx-background-color:" + BG + "; -fx-background-radius:16; -fx-border-radius:16;");""",
"progress-radius")

with io.open(UI, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK Ui rewritten")
