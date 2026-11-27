# -*- coding: utf-8 -*-
"""修复下载中心：旧分支搜索框占位色不可见；ShaderTab 版本下拉加宽 + OptiFine 空框占位。"""
import io, os, sys

ROOT = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui"

def load(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n")

def save(p, s):
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)

def rep1(path, old, new, tag):
    s = load(path)
    n = s.count(old)
    if n != 1:
        print(f"[FAIL] {tag}: anchor count={n}")
        sys.exit(1)
    save(path, s.replace(old, new))
    print(f"[OK] {tag}")

# 1. ModrinthTab 非 mod 分支：搜索框补占位色（ else 块，12 空格缩进）
mt = os.path.join(ROOT, "ModrinthTab.java")
old_mt = ('        } else {\n'
          '            searchBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");')
new_mt = ('        } else {\n'
          '            searchBox.setStyle("-fx-prompt-text-fill:rgba(255,255,255,0.55);");\n'
          '            searchBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");')
rep1(mt, old_mt, new_mt, "ModrinthTab searchBox prompt")

# 2. ModpackTab：搜索框补占位色
mp = os.path.join(ROOT, "ModpackTab.java")
old_mp = '        searchBox.setPrefWidth(340);\n'
new_mp = ('        searchBox.setPrefWidth(340);\n'
          '        searchBox.setStyle("-fx-prompt-text-fill:rgba(255,255,255,0.55);");\n')
rep1(mp, old_mp, new_mp, "ModpackTab searchBox prompt")

# 3. ShaderTab：版本下拉加宽；OptiFine 空框补占位提示
st = os.path.join(ROOT, "ShaderTab.java")
old_st1 = '        versionBox.setPrefWidth(220);\n'
new_st1 = '        versionBox.setPrefWidth(300);\n'
rep1(st, old_st1, new_st1, "ShaderTab versionBox width")

old_st2 = ('        versionBox.setPromptText("安装到的版本（必选）");\n')
new_st2 = ('        versionBox.setPromptText("安装到的版本（必选）");\n'
           '        optifineBox.setPromptText("查询后选择 OptiFine 版本");\n')
rep1(st, old_st2, new_st2, "ShaderTab optifineBox prompt")

print("DONE")
