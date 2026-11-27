# -*- coding: utf-8 -*-
# 补丁22：弹窗 StageStyle 由 UNDECORATED 改为 TRANSPARENT，消除圆角外侧白色直角残留。
import io

BASE = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher"

def patch(path, old, new, tag):
    with io.open(path, "r", encoding="utf-8") as f:
        s = f.read().replace("\r\n", "\n")
    if old not in s:
        raise SystemExit("NOT FOUND: " + tag)
    s = s.replace(old, new, 1)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)
    print("OK", tag)

patch(BASE + r"\ui\JwhDialog.java",
      "stage = new Stage(StageStyle.UNDECORATED);",
      "stage = new Stage(StageStyle.TRANSPARENT);",
      "JwhDialog")

patch(BASE + r"\ui\DownloadProgressDialog.java",
      "stage = new Stage(StageStyle.UNDECORATED);",
      "stage = new Stage(StageStyle.TRANSPARENT);",
      "DownloadProgressDialog")

patch(BASE + r"\ui\Ui.java",
      "javafx.stage.Stage stage = new javafx.stage.Stage(javafx.stage.StageStyle.UNDECORATED);",
      "javafx.stage.Stage stage = new javafx.stage.Stage(javafx.stage.StageStyle.TRANSPARENT);",
      "Ui.progressDialog")
