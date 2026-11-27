# -*- coding: utf-8 -*-
import io

MT = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(MT, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")

def rep(old, new, tag, n=1):
    global s
    assert s.count(old) == n, (tag, s.count(old))
    s = s.replace(old, new)

# A. 内置 OptiFine 构造补 author / versions
rep(
'        return new ModrinthApi.Hit(OPTIFINE_ID, OPTIFINE_ID, "OptiFine",\n'
'                "传统光影优化模组，安装后为独立版本；经 BMCLAPI 镜像获取官方安装器。",\n'
'                "", 0, List.of("optifine"));\n',
'        return new ModrinthApi.Hit(OPTIFINE_ID, OPTIFINE_ID, "OptiFine",\n'
'                "传统光影优化模组，安装后为独立版本；经 BMCLAPI 镜像获取官方安装器。",\n'
'                "", 0, List.of("optifine"), "", List.of());\n',
"builtin")

# B. CurseForge 转换补 author / versions
rep(
'                            merged.add(new ModrinthApi.Hit(h.projectId(), "", h.title(), h.description(),\n'
'                                    h.logoUrl(), h.downloads(), new ArrayList<>()));\n',
'                            merged.add(new ModrinthApi.Hit(h.projectId(), "", h.title(), h.description(),\n'
'                                    h.logoUrl(), h.downloads(), new ArrayList<>(), "", new ArrayList<>()));\n',
"cf")

# C. gridLike() 方法（在 modLike() 后追加）
rep(
'    private boolean modLike() {\n'
'        return type == Type.MOD || type == Type.LOADER;\n'
'    }\n',
'    private boolean modLike() {\n'
'        return type == Type.MOD || type == Type.LOADER;\n'
'    }\n'
'\n'
'    /** 走新深色双列卡片网格 + 单行筛选条的类型：Mod / 加载器 / 光影包。 */\n'
'    private boolean gridLike() {\n'
'        return type == Type.MOD || type == Type.LOADER || type == Type.SHADER;\n'
'    }\n',
"gridLike")

# D. asTab 局部筛选条分支用 gridLike
rep('        boolean modLike = type == Type.MOD || type == Type.LOADER;\n',
    '        boolean modLike = gridLike();\n', "astab")

# E. cardNode 路由
rep('        if (modLike()) return modCard(h);\n',
    '        if (gridLike()) return modCard(h);\n', "cardnode")

# F. searchPrompt 加光影包
rep(
'        if (type == Type.LOADER) return "搜索光影加载器…";\n',
'        if (type == Type.LOADER) return "搜索光影加载器…";\n'
'        if (type == Type.SHADER) return "搜索光影包…";\n',
"prompt")

# G. 卡片高度按类型
rep(
'        card.setMinHeight(104);\n'
'        card.setPrefHeight(104);\n'
'        card.setMaxHeight(104);\n',
'        int cardH = type == Type.SHADER ? 150 : 104;\n'
'        card.setMinHeight(cardH);\n'
'        card.setPrefHeight(cardH);\n'
'        card.setMaxHeight(cardH);\n',
"height")

# H. 图标尺寸 + info/底部构建
old_body = (
'        ImageView iconView = new ImageView();\n'
'        iconView.setFitWidth(40);\n'
'        iconView.setFitHeight(40);\n'
'        iconView.setPreserveRatio(true);\n'
'        iconView.setSmooth(true);\n'
'        Label titleLabel = new Label(isBuiltin(h) ? h.title() : ModNames.display(h.slug(), h.title()));\n'
'        titleLabel.setStyle("-fx-text-fill:#ffffff; -fx-font-size:15px; -fx-font-weight:bold;");\n'
'        titleLabel.setMaxWidth(Double.MAX_VALUE);\n'
'        titleLabel.setMinHeight(22);\n'
'        Label descLabel = new Label(isBuiltin(h) ? h.description() : ModNames.desc(h.slug(), h.title(), h.description()));\n'
'        descLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");\n'
'        descLabel.setWrapText(true);\n'
'        descLabel.setMaxWidth(Double.MAX_VALUE);\n'
'        descLabel.setPrefHeight(34);\n'
'        descLabel.setMaxHeight(34);\n'
'        VBox info = new VBox(6, titleLabel, descLabel);\n'
'        info.setMaxWidth(Double.MAX_VALUE);\n'
'        HBox.setHgrow(info, Priority.ALWAYS);\n'
'\n'
'        Label sourceTag = new Label(isBuiltin(h) ? "BMCLAPI" : sourceTagText());\n'
'        sourceTag.setStyle("-fx-background-color:#2c2336; -fx-text-fill:#b9bfc7; -fx-font-size:11px;"\n'
'                + " -fx-background-radius:6; -fx-padding:3 8;");\n'
'        Label dlLabel = new Label(isBuiltin(h) ? "独立版本" : formatDownloads(h.downloads()) + " 下载");\n'
'        dlLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");\n'
'        Region spacer = new Region();\n'
'        HBox bottom = new HBox(10, sourceTag, spacer, dlLabel);\n'
'        bottom.setAlignment(Pos.CENTER_LEFT);\n'
'        HBox.setHgrow(spacer, Priority.ALWAYS);\n')
new_body = (
'        boolean shader = type == Type.SHADER;\n'
'        int iconSize = shader ? 48 : 40;\n'
'        ImageView iconView = new ImageView();\n'
'        iconView.setFitWidth(iconSize);\n'
'        iconView.setFitHeight(iconSize);\n'
'        iconView.setPreserveRatio(true);\n'
'        iconView.setSmooth(true);\n'
'        Label titleLabel = new Label(isBuiltin(h) ? h.title() : ModNames.display(h.slug(), h.title()));\n'
'        titleLabel.setStyle("-fx-text-fill:#ffffff; -fx-font-size:15px; -fx-font-weight:bold;");\n'
'        titleLabel.setMaxWidth(Double.MAX_VALUE);\n'
'        titleLabel.setMinHeight(22);\n'
'        Label descLabel = new Label(isBuiltin(h) ? h.description() : ModNames.desc(h.slug(), h.title(), h.description()));\n'
'        descLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");\n'
'        descLabel.setWrapText(true);\n'
'        descLabel.setMaxWidth(Double.MAX_VALUE);\n'
'        descLabel.setPrefHeight(34);\n'
'        descLabel.setMaxHeight(34);\n'
'\n'
'        List<javafx.scene.Node> infoChildren = new ArrayList<>();\n'
'        infoChildren.add(titleLabel);\n'
'        if (shader && h.author() != null && !h.author().isBlank()) {\n'
'            Label authorLabel = new Label("by " + h.author());\n'
'            authorLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");\n'
'            authorLabel.setMaxWidth(Double.MAX_VALUE);\n'
'            infoChildren.add(authorLabel);\n'
'        }\n'
'        infoChildren.add(descLabel);\n'
'        VBox info = new VBox(6);\n'
'        info.getChildren().addAll(infoChildren);\n'
'        info.setMaxWidth(Double.MAX_VALUE);\n'
'        HBox.setHgrow(info, Priority.ALWAYS);\n'
'\n'
'        List<javafx.scene.Node> bottomChildren = new ArrayList<>();\n'
'        String ver = shader ? versionTag(h) : null;\n'
'        if (ver != null) {\n'
'            Label versionTag = new Label(ver);\n'
'            versionTag.setStyle("-fx-background-color:#2c2336; -fx-text-fill:#b9bfc7; -fx-font-size:11px;"\n'
'                    + " -fx-background-radius:6; -fx-padding:3 8;");\n'
'            bottomChildren.add(versionTag);\n'
'        }\n'
'        Label sourceTag = new Label(isBuiltin(h) ? "BMCLAPI" : sourceTagText());\n'
'        sourceTag.setStyle("-fx-background-color:#2c2336; -fx-text-fill:#b9bfc7; -fx-font-size:11px;"\n'
'                + " -fx-background-radius:6; -fx-padding:3 8;");\n'
'        bottomChildren.add(sourceTag);\n'
'        Region spacer = new Region();\n'
'        Label dlLabel = new Label(isBuiltin(h) ? "独立版本" : formatDownloads(h.downloads()) + " 下载");\n'
'        dlLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");\n'
'        bottomChildren.add(spacer);\n'
'        bottomChildren.add(dlLabel);\n'
'        HBox bottom = new HBox(10);\n'
'        bottom.getChildren().addAll(bottomChildren);\n'
'        bottom.setAlignment(Pos.CENTER_LEFT);\n'
'        HBox.setHgrow(spacer, Priority.ALWAYS);\n')
rep(old_body, new_body, "body")

# I. versionTag helper（formatDownloads 后追加）
rep(
'    /** 下载量格式化：1.2M / 3.4K，不足千显示原值。 */\n'
'    private static String formatDownloads(long d) {\n'
'        if (d >= 1_000_000) return String.format(Locale.US, "%.1fM", d / 1_000_000.0);\n'
'        if (d >= 1_000) return String.format(Locale.US, "%.1fK", d / 1_000.0);\n'
'        return String.valueOf(d);\n'
'    }\n',
'    /** 下载量格式化：1.2M / 3.4K，不足千显示原值。 */\n'
'    private static String formatDownloads(long d) {\n'
'        if (d >= 1_000_000) return String.format(Locale.US, "%.1fM", d / 1_000_000.0);\n'
'        if (d >= 1_000) return String.format(Locale.US, "%.1fK", d / 1_000.0);\n'
'        return String.valueOf(d);\n'
'    }\n'
'\n'
'    /** 卡片版本胶囊：优先匹配当前所选 MC 版本，否则取列表末项（最新）。无版本信息返回 null。 */\n'
'    private String versionTag(ModrinthApi.Hit h) {\n'
'        if (h.versions() == null || h.versions().isEmpty()) return null;\n'
'        String mc = selectedMc();\n'
'        if (mc != null && !mc.isBlank()) {\n'
'            for (String v : h.versions()) {\n'
'                if (mc.equals(v)) return v;\n'
'            }\n'
'        }\n'
'        return h.versions().get(h.versions().size() - 1);\n'
'    }\n',
"helper")

with io.open(MT, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK ModrinthTab")
