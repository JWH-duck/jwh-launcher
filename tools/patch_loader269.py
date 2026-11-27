# -*- coding: utf-8 -*-
"""#269 光影加载器：从 ShaderTab 表单替换为 ModrinthTab LOADER 卡片网格。"""
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

# R1 枚举新增 LOADER
rep(mt,
"""        MOD("mod", "Mod 模组", true),
        SHADER("shader", "光影包", false),
        RESOURCEPACK("resourcepack", "材质包", false);""",
"""        MOD("mod", "Mod 模组", true),
        LOADER("mod", "光影加载器", true),
        SHADER("shader", "光影包", false),
        RESOURCEPACK("resourcepack", "材质包", false);""",
"R1 enum LOADER")

# R2/R3 新样式分支变量
rep(mt, "        boolean mod = type == Type.MOD;\n",
    "        boolean modLike = type == Type.MOD || type == Type.LOADER;\n", "R2 modLike var")
rep(mt, "        if (mod) {\n", "        if (modLike) {\n", "R3 style branch")

# R4 卡片分流
rep(mt, "        if (type == Type.MOD) return modCard(h);\n",
    "        if (modLike()) return modCard(h);\n", "R4 cardNode")

# R5 搜索占位文案
rep(mt,
"""    private String searchPrompt() {
        return type == Type.MOD ? "搜索模组…" : "搜索材质包，例如：XeKr / 原版高亮";
    }""",
"""    private String searchPrompt() {
        if (type == Type.MOD) return "搜索模组…";
        if (type == Type.LOADER) return "搜索光影加载器…";
        return "搜索材质包，例如：XeKr / 原版高亮";
    }""", "R5 searchPrompt")

# R6 网页兜底分类
rep(mt, '            String cls = type == Type.MOD ? "mods" : "texture-packs";\n',
    '            String cls = modLike() ? "mods" : "texture-packs";\n', "R6 curseforge cls")

# R7 apiType 映射
rep(mt,
"""            case MOD -> ModrinthApi.Type.MOD;
            case SHADER -> ModrinthApi.Type.SHADER;""",
"""            case MOD -> ModrinthApi.Type.MOD;
            case LOADER -> ModrinthApi.Type.MOD;
            case SHADER -> ModrinthApi.Type.SHADER;""", "R7 apiType")

# R8 详情/下载适配判断：type==Type.MOD -> modLike()（前置下载判断 1008 保留 MOD）
rep(mt, "            curLd[0] = type == Type.MOD ? ld : null;\n",
    "            curLd[0] = modLike() ? ld : null;\n", "R8a curLd")
rep(mt, "                if (type == Type.MOD && (ld == null || v.loaders() == null || !v.loaders().contains(ld))) continue;\n",
    "                if (modLike() && (ld == null || v.loaders() == null || !v.loaders().contains(ld))) continue;\n", "R8b fillVersions")
rep(mt,
"""                adaptHint.setText(type == Type.MOD
                        ? "未检测到mod适配的加载器"
                        : "未检测到适配的实例（请先在「游戏版本」中安装对应版本的游戏）");""",
"""                adaptHint.setText(modLike()
                        ? "未检测到mod适配的加载器"
                        : "未检测到适配的实例（请先在「游戏版本」中安装对应版本的游戏）");""", "R8c adapt empty")
rep(mt,
"""            adaptHint.setText(type == Type.MOD
                    ? "默认使用实例管理中已有的游戏版本与加载器，可下拉切换其他适配实例。"
                    : "默认使用实例管理中已有的游戏版本，可下拉切换其他适配实例。");""",
"""            adaptHint.setText(modLike()
                    ? "默认使用实例管理中已有的游戏版本与加载器，可下拉切换其他适配实例。"
                    : "默认使用实例管理中已有的游戏版本，可下拉切换其他适配实例。");""", "R8d adapt ok")
rep(mt, "                if (type == Type.MOD && loaderOf.get(id) == null) continue;\n",
    "                if (modLike() && loaderOf.get(id) == null) continue;\n", "R8e cf candidates")
rep(mt, "                    if (type == Type.MOD && (ld == null || !loaders.contains(ld))) continue;\n",
    "                    if (modLike() && (ld == null || !loaders.contains(ld))) continue;\n", "R8f async candidates")
rep(mt, "                    if (type == Type.MOD && loaderOf.get(id) == null) continue;\n",
    "                    if (modLike() && loaderOf.get(id) == null) continue;\n", "R8g err candidates")
rep(mt, "            String loader = type == Type.MOD ? loaderOf.get(id) : null;\n",
    "            String loader = modLike() ? loaderOf.get(id) : null;\n", "R8h download loader")
rep(mt, '                            + (type == Type.MOD ? " 与所选加载器" : "") + " 的文件。");\n',
    '                            + (modLike() ? " 与所选加载器" : "") + " 的文件。");\n', "R8i cf err")
rep(mt, '                        + (type == Type.MOD ? " 与所选加载器" : "") + " 的版本。");\n',
    '                        + (modLike() ? " 与所选加载器" : "") + " 的版本。");\n', "R8j version err")

# R9 落点目录
rep(mt,
"""            case MOD -> "mods";
            case SHADER -> "shaderpacks";""",
"""            case MOD -> "mods";
            case LOADER -> "mods";
            case SHADER -> "shaderpacks";""", "R9 subdir")

# R10 辅助方法（插在 selectedLoader 注释前）
anchor = "    /** 当前选中实例的 id → Modrinth 加载器参数（纯原版 → null，即不限加载器）。 */"
rep(mt, anchor,
"""    /** Mod 与光影加载器同属装入 mods 目录、按加载器实例匹配的类型。 */
    private boolean modLike() {
        return type == Type.MOD || type == Type.LOADER;
    }

""" + anchor, "R10 modLike helper")

# R11 LOADER 空关键词默认 loader 检索词
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

# ===== DownloadView.java =====
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
