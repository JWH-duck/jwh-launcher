# -*- coding: utf-8 -*-
import io

F = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")

reps = []

# 1. gridLike() 纳入材质包
reps.append((
    "    /** 走新深色双列卡片网格 + 单行筛选条的类型：Mod / 加载器 / 光影包。 */\n"
    "    private boolean gridLike() {\n"
    "        return type == Type.MOD || type == Type.LOADER || type == Type.SHADER;\n"
    "    }\n",
    "    /** 走新深色双列卡片网格 + 单行筛选条的类型：Mod / 加载器 / 光影包 / 材质包。 */\n"
    "    private boolean gridLike() {\n"
    "        return type == Type.MOD || type == Type.LOADER || type == Type.SHADER || type == Type.RESOURCEPACK;\n"
    "    }\n"
))

# 2. 卡片高度：高卡片类型 光影包 + 材质包
reps.append((
    "        int cardH = type == Type.SHADER ? 150 : 104;\n",
    "        int cardH = (type == Type.SHADER || type == Type.RESOURCEPACK) ? 150 : 104;\n"
))

# 3. tall 判定替换 shader 局部变量
reps.append((
    "        boolean shader = type == Type.SHADER;\n"
    "        int iconSize = shader ? 48 : 40;\n",
    "        boolean tall = type == Type.SHADER || type == Type.RESOURCEPACK;\n"
    "        int iconSize = tall ? 48 : 40;\n"
))

# 4. 作者行
reps.append((
    "        if (shader && h.author() != null && !h.author().isBlank()) {\n",
    "        if (tall && h.author() != null && !h.author().isBlank()) {\n"
))

# 5. 版本胶囊
reps.append((
    "        String ver = shader ? versionTag(h) : null;\n",
    "        String ver = tall ? versionTag(h) : null;\n"
))

# 6. 搜索占位
reps.append((
    "        return \"搜索材质包，例如：XeKr / 原版高亮\";\n",
    "        return \"搜索材质包…\";\n"
))

for old, new in reps:
    c = s.count(old)
    assert c == 1, "anchor count=%d for: %r" % (c, old[:60])

for old, new in reps:
    s = s.replace(old, new)

with io.open(F, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK resourcepack grid: %d reps" % len(reps))
