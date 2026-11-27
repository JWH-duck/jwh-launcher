# -*- coding: utf-8 -*-
"""#269 三跑：modLike helper + loader 默认检索词 + DownloadView 接线。"""
import io, os, sys

UI = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui"

def load(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n")

def save(p, s):
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)

def rep(path, old, new, tag, expect=1):
    s = load(path)
    n = s.count(old)
    if n != expect:
        print(f"[FAIL] {tag}: anchor count={n} expect={expect}")
        sys.exit(1)
    save(path, s.replace(old, new))
    print(f"[OK] {tag}")

mt = os.path.join(UI, "ModrinthTab.java")

anchor = "    /** 当前选中目标版本自带的加载器参数（纯原版 → null，即不限加载器）。 */"
rep(mt, anchor,
"""    /** Mod 与光影加载器同属装入 mods 目录、按加载器实例匹配的类型。 */
    private boolean modLike() {
        return type == Type.MOD || type == Type.LOADER;
    }

""" + anchor, "R10 modLike helper")

rep(mt,
"""        List<String> keys = new ArrayList<>();
        keys.add(q);
        if (hasHan(q)) {
            keys.addAll(ModNames.searchKeys(q));
        }""",
"""        List<String> keys = new ArrayList<>();
        if (type == Type.LOADER && (q == null || q.isBlank())) {
            keys.addAll(java.util.List.of("iris", "optifine", "canvas", "photon",
                    "bsl loader", "complementary loader", "oculus"));
        } else {
            keys.add(q);
            if (hasHan(q)) {
                keys.addAll(ModNames.searchKeys(q));
            }
        }""", "R11 loader default keys")

dv = os.path.join(UI, "DownloadView.java")
rep(dv, "    private ShaderTab shaderTab;\n", "    private ModrinthTab loaderTab;\n", "D1 field")
rep(dv, "        shaderTab = new ShaderTab(app, sink, this::attachControl, this::detachControl);\n",
    "        loaderTab = new ModrinthTab(app, ModrinthTab.Type.LOADER, sink, this::attachControl, this::detachControl);\n",
    "D2 instantiate")
rep(dv, "        Tab shaderTabT = shaderTab.asTab();\n", "        Tab loaderTabT = loaderTab.asTab();\n", "D3 tab node")
rep(dv, "        tabs.getTabs().addAll(versionTab, modTabT, shaderTabT, shaderPackTabT, rpTabT, modpackTabT);\n",
    "        tabs.getTabs().addAll(versionTab, modTabT, loaderTabT, shaderPackTabT, rpTabT, modpackTabT);\n", "D4 addAll")
rep(dv, "        if (shaderTab != null) shaderTab.refreshVersions();\n",
    "        if (loaderTab != null) loaderTab.refreshVersions();\n", "D5 refresh")

print("DONE")
