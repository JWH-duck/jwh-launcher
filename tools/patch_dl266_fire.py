# -*- coding: utf-8 -*-
import io
LA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(LA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

old = "        refreshBtn.fire();\n"
new = '''        Ui.async(() -> {
            manifest = VersionManifest.fetch(app.config.minecraftPath().resolve("launcher_cache_manifest.json"),
                    app.config.manifestUrl());
            return null;
        }, ok -> {
            applyFilter(filter, list);
            app.setStatus("清单已刷新");
        }, err -> Ui.error("拉取失败", err));
'''
assert t.count(old) == 1, "fire anchor not found"
t = t.replace(old, new, 1)
with io.open(LA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("refreshBtn.fire replaced with direct async load")
