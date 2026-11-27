# -*- coding: utf-8 -*-
import io
LA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(LA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

def rep(old, new, tag):
    global t
    assert t.count(old) == 1, f"anchor not unique/found: {tag}"
    t = t.replace(old, new, 1)
    print("ok:", tag)

rep(
    '''            private final Label idLabel = new Label();
            private final Label metaLabel = new Label();''',
    '''            private final Label idLabel = new Label();
            private final Label typeLabel = new Label();
            private final Label metaLabel = new Label();''',
    "typeLabel field")

rep(
    '''                idLabel.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:15px; -fx-font-weight:bold;");
                metaLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
                HBox spacer = new HBox();
                // 只做弹性占位，避免 MAX_VALUE 宽度把版本名挤成省略号“…”
                HBox.setHgrow(spacer, Priority.ALWAYS);
                info.getChildren().addAll(idLabel, metaLabel);''',
    '''                idLabel.setStyle("-fx-text-fill:#ffffff; -fx-font-size:15px; -fx-font-weight:bold;");
                typeLabel.setStyle("-fx-text-fill:#d982c2; -fx-font-size:15px; -fx-font-weight:bold;");
                metaLabel.setStyle("-fx-text-fill:rgba(255,255,255,0.5); -fx-font-size:12px;");
                HBox titleLine = new HBox(6, idLabel, typeLabel);
                titleLine.setAlignment(Pos.CENTER_LEFT);
                HBox spacer = new HBox();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                info.getChildren().addAll(titleLine, metaLabel);''',
    "cell styles + title line")

rep(
    '''            private void applyRowStyle(boolean selected) {
                // 选中：深紫半透明条（稿 st22 #141414 @25%），未选中透明
                setStyle(selected
                        ? "-fx-background-color:rgba(20,20,20,0.25); -fx-background-radius:8; -fx-padding:12 14;"
                        : "-fx-background-color:rgba(20,20,20,0.12); -fx-background-radius:8; -fx-padding:12 14;");
            }''',
    '''            private void applyRowStyle(boolean selected) {
                // 深色行：未选中透明 + 底部分隔细线；选中浅白半透明条
                setStyle(selected
                        ? "-fx-background-color:rgba(255,255,255,0.06); -fx-background-radius:8; -fx-padding:12 14;"
                        : "-fx-background-color:transparent; -fx-border-color:rgba(255,255,255,0.08); -fx-border-width:0 0 1 0; -fx-padding:12 14;");
            }''',
    "row style dark")

rep(
    '''                    boolean installed = app.localVersions.exists(v.id());
                    idLabel.setText(v.id() + "   [" + v.typeLabel() + "]" + (installed ? "  ✓已安装" : ""));
                    metaLabel.setText(fmtTime(v.releaseTime()));''',
    '''                    boolean installed = app.localVersions.exists(v.id());
                    idLabel.setText(v.id() + (installed ? "  ✓已安装" : ""));
                    typeLabel.setText("[" + v.typeLabel() + "]");
                    metaLabel.setText(fmtTime(v.releaseTime()));''',
    "split id/type text")

with io.open(LA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("java part 2 done")
