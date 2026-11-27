# -*- coding: utf-8 -*-
"""#270 补写未落盘项：import Files、helpers、卡片文案、searchAll 注入内置条目。"""
import io, sys

MT = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"

def load():
    with io.open(MT, "r", encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n")

def save(s):
    with io.open(MT, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)

def rep(s, old, new, tag, expect=1):
    n = s.count(old)
    if n != expect:
        print(f"[FAIL] {tag}: anchor count={n} expect={expect}")
        sys.exit(1)
    print(f"[OK] {tag}")
    return s.replace(old, new)

s = load()

s = rep(s,
    "import java.nio.file.Path;\n",
    "import java.nio.file.Files;\nimport java.nio.file.Path;\n",
    "import Files")

anchor_helper = "    private boolean modLike() {\n"
helpers = '''    /** 内置 OptiFine 条目标记：不走 Modrinth 检索，经 BMCLAPI 镜像获取安装器。 */
    private static final String OPTIFINE_ID = "builtin-optifine";

    private static boolean isBuiltin(ModrinthApi.Hit h) {
        return OPTIFINE_ID.equals(h.projectId());
    }

    /** 内置 OptiFine 静态条目（标题/描述写死，不来自搜索接口）。 */
    private static ModrinthApi.Hit builtinOptifine() {
        return new ModrinthApi.Hit(OPTIFINE_ID, "optifine", "OptiFine",
                "传统光影优化模组，安装后为独立版本；经 BMCLAPI 镜像获取官方安装器。",
                "", 0, List.of("optifine"));
    }

    /** 内置条目固定置顶（排序后再移回首项）。 */
    private void pinBuiltin() {
        for (int i = 0; i < items.size(); i++) {
            if (OPTIFINE_ID.equals(items.get(i).projectId())) {
                ModrinthApi.Hit b = items.remove(i);
                items.add(0, b);
                return;
            }
        }
    }

'''
s = rep(s, anchor_helper, helpers + anchor_helper, "helpers")

s = rep(s,
    '        Label sourceTag = new Label(sourceTagText());\n',
    '        Label sourceTag = new Label(isBuiltin(h) ? "BMCLAPI" : sourceTagText());\n',
    "source tag")

s = rep(s,
    '        Label dlLabel = new Label(formatDownloads(h.downloads()) + " 下载");\n',
    '        Label dlLabel = new Label(isBuiltin(h) ? "独立版本" : formatDownloads(h.downloads()) + " 下载");\n',
    "dl label")

s = rep(s,
    "        if (merged.isEmpty() && firstErr != null) throw firstErr;\n        return merged;\n",
    "        if (merged.isEmpty() && firstErr != null) throw firstErr;\n"
    "        if (type == Type.LOADER) merged.add(0, builtinOptifine());\n"
    "        return merged;\n",
    "inject builtin into searchAll")

save(s)
print("DONE")
