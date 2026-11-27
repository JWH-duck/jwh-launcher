# -*- coding: utf-8 -*-
import io

DV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(DV, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")

# 1) 类型下拉：去内层框样式类
old1 = '        filter.getSelectionModel().select(1);\n        filter.getStyleClass().add("jwh-capsule");\n'
assert s.count(old1) == 1
new1 = ('        filter.getSelectionModel().select(1);\n'
        '        filter.getStyleClass().add("jwh-capsule");\n'
        '        filter.getStyleClass().add("jwh-capsule-plain");\n')
s = s.replace(old1, new1, 1)

# 2) 加载器平台：不锁默认项，占位引导「选择加载器」；去内层框
old2 = ('        loaderPlatform.getItems().addAll("无（仅原版）", "Fabric", "Quilt", "Forge", "NeoForge");\n'
        '        loaderPlatform.getSelectionModel().selectFirst();\n'
        '        loaderPlatform.getStyleClass().add("jwh-capsule");\n')
assert s.count(old2) == 1
new2 = ('        loaderPlatform.getItems().addAll("无（仅原版）", "Fabric", "Quilt", "Forge", "NeoForge");\n'
        '        loaderPlatform.setPromptText("选择加载器");\n'
        '        loaderPlatform.getStyleClass().add("jwh-capsule");\n'
        '        loaderPlatform.getStyleClass().add("jwh-capsule-plain");\n')
s = s.replace(old2, new2, 1)

# 3) 删除「刷新加载器」按钮整块
old3 = ('        Button refreshLoaderBtn = new Button("刷新加载器");\n'
        '        refreshLoaderBtn.getStyleClass().add("ghost-btn");\n'
        '        refreshLoaderBtn.setOnAction(e -> {\n'
        '            VersionManifest.Version v = list.getSelectionModel().getSelectedItem();\n'
        '            String plat = loaderPlatform.getSelectionModel().getSelectedItem();\n'
        '            if (v == null || plat == null || "无（仅原版）".equals(plat)) {\n'
        '                Ui.error("未选择", "请先在上方列表选择游戏版本，并选择加载器平台。");\n'
        '                return;\n'
        '            }\n'
        '            loaderVersionBox.setDisable(false);\n'
        '            fillLoaderVersions(v.id(), plat, loaderVersionBox, true);\n'
        '            app.setStatus("正在刷新 " + plat + " 加载器版本列表…");\n'
        '        });\n\n')
assert s.count(old3) == 1
s = s.replace(old3, "", 1)

# 4) 筛选条去掉刷新加载器按钮
old4 = ('        filterBar.getChildren().addAll(filter, loaderPlatform, loaderVersionBox, barSpacer,\n'
        '                refreshBtn, refreshLoaderBtn, searchBox);\n')
assert s.count(old4) == 1
new4 = ('        filterBar.getChildren().addAll(filter, loaderPlatform, loaderVersionBox, barSpacer,\n'
        '                refreshBtn, searchBox);\n')
s = s.replace(old4, new4, 1)

with io.open(DV, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)

# 5) CSS 追加：plain 下拉清除内层 cell / 箭头按钮的深色底板与描边
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    c = f.read().replace("\r\n", "\n")
rule = ('\n/* 下载页筛选下拉：去除内层深色圆角矩形框，外层胶囊保留 */\n'
        '.jwh-capsule-plain .list-cell {\n'
        '    -fx-background-color: transparent;\n'
        '    -fx-border-color: transparent;\n'
        '}\n'
        '.jwh-capsule-plain .arrow-button {\n'
        '    -fx-background-color: transparent;\n'
        '}\n')
if ".jwh-capsule-plain" not in c:
    c = c.rstrip("\n") + "\n" + rule
with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(c)

print("patch 263 applied")
