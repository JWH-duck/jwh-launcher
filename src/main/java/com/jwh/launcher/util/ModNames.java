package com.jwh.launcher.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mod 名称/描述汉化表（Mod 列表显示）：下载中心列表里的 Mod 名称
 * 显示为「中文名 (原名)」，常用 Mod 的描述也显示中文；未收录的保持 Modrinth/CF
 * 原文。匹配键优先 slug，其次原 title（均小写匹配）。
 */
public final class ModNames {

    private static final Map<String, String> NAMES = new HashMap<>();
    private static final Map<String, String> DESCS = new HashMap<>();

    static {
        // ---------- 优化 / 性能 ----------
        put("sodium", "钠");
        put("sodium-extra", "钠扩展");
        put("sodium-extra-fabric", "钠扩展");
        put("rubidium", "铷");
        put("embeddium", "铱");
        put("lithium", "锂");
        put("canary", "金丝雀");
        put("krypton", "氪");
        put("ferrite-core", "铁氧体核心");
        put("ferritecore", "铁氧体核心");
        put("entityculling", "实体渲染机制优化");
        put("entity-culling", "实体渲染机制优化");
        put("cull-leaves", "优化树叶渲染");
        put("cull-less-leaves", "优化树叶渲染");
        put("c2me", "C2ME 区块生成优化");
        put("c2me-fabric", "C2ME 区块生成优化");
        put("modernfix", "现代修复");
        put("modern-fix", "现代修复");
        put("very-many-fixes", "更多修复");
        put("dynamic-fps", "动态帧率");
        put("immediatelyfast", "即时快速");
        put("memoryleakfix", "内存泄漏修复");
        put("memory-leak-fix", "内存泄漏修复");
        put("lazy-language-loader", "懒加载语言");
        put("smoothboot", "Smooth Boot 平滑启动");
        put("starlight", "星光");
        put("lazydfu", "懒 DFU");
        put("fastload", "快速加载");
        put("moreculling", "更多剔除");
        put("particle-core", "粒子核心");
        put("dashloader", "Dash 加载器");
        put("analog-paintings", "模拟油画");
        put("interpolation-fix", "插值修复");
        put("chunk-sending", "区块发送优化");
        put("server-core", "服务端核心优化");
        put("enhanced-block-entities", "增强方块实体");
        put("ebe", "增强方块实体");
        put("translucency-fix", "半透明渲染修复");
        put("fabric-trims", "盔甲纹饰支持");

        // ---------- 光影 / 渲染 ----------
        put("iris", "Iris 光影加载器");
        put("iris-fabric", "Iris 光影加载器");
        put("optifine", "高清修复");
        put("oculus", "Oculus 光影加载器");
        put("shadersmod", "光影模组");
        put("complementary-reimagined", "Complementary Reimagined 光影");
        put("complementary-unbound", "Complementary Unbound 光影");
        put("bsl-shaders", "BSL 光影");
        put("sildurs-vibrant-shaders", "索德斯鲜活光影");
        put("sildurs-enhanced-default", "索德斯增强默认光影");
        put("sora-shaders", "索拉光影");
        put("chocapic13-shaders", "Chocapic13 光影");
        put("ptgi-remastered", "PTGI 重制光影");
        put("seus-renewed", "SEUS 焕新光影");
        put("makeup-ultra-fast-shaders", "MakeUp 极速光影");
        put("kawa-shaders", "Kawa 光影");
        put("rethinking-voxels", "Rethinking Voxels 光影");
        put("solashaders", "Solas 光影");
        put("photon-shader", "Photon 光影");
        put("nostalgia-shader", "Nostalgia 光影");
        put("fabulously-optimized", "妙不可言优化整合");
        put("vanilla-tweaks", "原版微调材质");
        put("continuity", "连接纹理");

        // ---------- API / 前置库 ----------
        put("fabric-api", "Fabric API");
        put("fabric-language-kotlin", "Fabric Kotlin 语言支持");
        put("fabric-language-kotlin-fork", "Fabric Kotlin 语言支持");
        put("quilted-fabric-api", "Quilted Fabric API");
        put("quilt-standard-libraries", "Quilt 标准库");
        put("qsl", "Quilt 标准库");
        put("architectury-api", "Architectury API");
        put("architectury", "Architectury API");
        put("cloth-config", "布料配置 API");
        put("cloth-config2", "布料配置 API");
        put("forge-config-api-port", "Forge 配置 API 移植");
        put("yet-another-config-lib", "YACL 配置库");
        put("yacl", "YACL 配置库");
        put("owo-lib", "owo lib 库");
        put("playeranimator", "玩家动画库");
        put("creativecore", "CreativeCore 核心");
        put("geckolib", "GeckoLib 动画库");
        put("gecko-lib", "GeckoLib 动画库");
        put("iceberg", "Iceberg 冰山");
        put("trinkets", "饰品栏 API");
        put("curios", "好奇饰品栏");
        put("curios-api", "好奇饰品栏 API");
        put("patchouli", "帕秋莉手册");
        put("bookshelf", "书架库");
        put("jei", "JEI 物品管理器");
        put("just-enough-items", "JEI 物品管理器");
        put("roughly-enough-items", "REI 物品管理器");
        put("rei", "REI 物品管理器");
        put("emi", "EMI 物品管理器");
        put("emi-fabric", "EMI 物品管理器");
        put("jade", "Jade 万物附着");
        put("jade-addons", "Jade 万物附着扩展");
        put("wthit", "WTHIT 附着的科技");
        put("hwyla", "HWYLA 附着的科技");
        put("modmenu", "模组菜单");
        put("mod-menu", "模组菜单");
        put("catalogue", "目录");
        put("configured", "配置界面");
        put("controlify", "控制器支持");
        put("puzzles-lib", "谜题库");
        put("citadel", "Citadel 库");
        put("geckolib-fabric", "GeckoLib 动画库");
        put("azurelib", "AzureLib 动画库");
        put("kotlinforforge", "Forge Kotlin 语言支持");
        put("fabric-language-kotlin-fork", "Fabric Kotlin 语言支持");
        put("balm", "Balm 桥接库");
        put("collective", "Collective 合集库");
        put("saturn", "土星优化");
        put("spark", "Spark 性能报告");
        put("spark-fabric", "Spark 性能报告");

        // ---------- 内容 / 玩法 ----------
        put("alexs-mobs", "阿莱克斯的生物");
        put("alexs-caves", "阿莱克斯的洞穴");
        put("alexs-caves-fabric", "阿莱克斯的洞穴");
        put("farmers-delight", "农夫乐事");
        put("farmersdelight", "农夫乐事");
        put("create", "机械动力");
        put("create-fabric", "机械动力");
        put("twilightforest", "暮色森林");
        put("aether", "天境");
        put("iceandfire", "冰与火之歌");
        put("dragon-survival", "龙之生存");
        put("botania", "植物魔法");
        put("blood-magic", "血魔法");
        put("biomesoplenty", "更多生物群系");
        put("terralith", "地形秘境");
        put("betterend", "更好的末地");
        put("betternether", "更好的下界");
        put("oh-the-biomes-weve-gone", "我们的生物群系");
        put("waystones", "路石");
        put("fwaystones", "快速路石");
        put("gravestone", "墓碑");
        put("corpse", "死亡尸体");
        put("tombstone", "坟墓");
        put("xaros-minimap", "Xaero 小地图");
        put("xaeros-minimap", "Xaero 小地图");
        put("xaeros-world-map", "Xaero 世界地图");
        put("journeymap", "旅行地图");
        put("voxelmap", "体素地图");
        put("pl3xmap", "Pl3xMap 网页地图");
        put("squaremap", "SquareMap 网页地图");
        put("dynmap", "Dynmap 网页地图");
        put("camera-utils", "相机工具");
        put("zoomify", "缩放");
        put("not-enough-animations", "更多动画");
        put("first-person-model", "第一人称模型");
        put("betterf3", "更好的 F3");
        put("apple-skin", "苹果皮");
        put("appleskin", "苹果皮");
        put("inventory-profiles-next", "快捷栏切换");
        put("litematica", "投影");
        put("malilib", "MaliLib 库");
        put("tweakeroo", "Tweakeroo 小助手");
        put("minihud", "迷你 HUD");
        put("worldedit", "创世神");
        put("better-advancements", "更好的进度");
        put("carry-on", "携带物品");
        put("creeper-overhaul", "苦力怕翻新");
        put("easy-magic", "简易附魔");
        put("easy-anvils", "简易铁砧");
        put("elevatorid", "电梯");
        put("ironchest", "铁箱子");
        put("mousetweaks", "鼠标手势优化");
        put("right-click-harvest", "右键收获");
        put("sound-physics-remastered", "声音物理重制");
        put("supplementaries", "附属");
        put("quark", "夸克");
        put("charm", "魅力");
        put("custom-splash-screen", "自定义加载画面");
        put("seamless-loading-screen", "无缝加载界面");
        put("yungs-better-mineshafts", "更好的矿井");
        put("yungs-better-strongholds", "更好的要塞");
        put("yungs-better-dungeons", "更好的地牢");
        put("yungs-better-nether-fortresses", "更好的下界堡垒");
        put("yungs-bridges", "更好的桥梁");
        put("yungs-api", "YUNG's API");
        put("entity-model-features", "实体模型特性");
        put("emf", "实体模型特性");
        put("entity-texture-features", "实体纹理特性");
        put("etf", "实体纹理特性");
        put("netherportalfix", "下界传送门修复");
        put("easiervillagertrading", "简化村民交易");
        put("villager-trades", "简化村民交易");
        put("calibrated-access", "校准访问");
        put("polytone", "Polytone 音色");
        put("ambient-environment", "环境音效");
        put("sound-replacements", "音效替换");
        put("mobs-properties", "生物属性");
        put("wandering-trapper", "流浪猎人");

        // ---------- 材质包 ----------
        put("faithful", "Faithful 32x 材质");
        put("faithful-32x", "Faithful 32x 材质");
        put("xekr-vanilla-tweaks", "XeKr 原版高亮");
        put("puredbcraft", "PureBDcraft 材质");
        put("stay-true", "Stay True 材质");
        put("bare-bones", "Bare Bones 材质");
        put("jerms-better-leaves", "Jerm 更好的树叶");
        put("modernity", "Modernity 材质");
        put("john-smith-legacy", "John Smith 遗产材质");
        put("dokucraft", "DokuCraft 材质");
        put("soartex-fanver", "Soartex 粉丝版材质");
        put("mizunos-16-craft", "Mizuno's 16 Craft 材质");
        put("conquest", "Conquest 材质");
        put("excalibur", "Excalibur 材质");
        put("blocks-of-the-past", "过去之块材质");
        put("vanilla-tweaks-textures", "原版微调材质");

        // ---------- 常用描述汉化 ----------
        desc("sodium", "一个高性能的渲染引擎优化模组，大幅提升 Minecraft 帧率与画面流畅度。");
        desc("iris", "现代光影加载器，兼容 OptiFine 光影包，与钠等优化模组搭配使用。");
        desc("optifine", "经典优化模组，提供高清画质、光影支持与游戏性能优化。");
        desc("fabric-api", "Fabric 模组生态的核心 API 前置，绝大多数 Fabric 模组都需要它。");
        desc("jei", "物品管理器，按配方查看物品合成与用途。");
        desc("roughly-enough-items", "物品管理器，支持查看配方与用途，兼容性更好的 REI。");
        desc("emi", "新一代物品管理器，快速、可扩展，支持查看配方与用途。");
        desc("modmenu", "在游戏中查看已安装 Mod 列表与信息的界面模组。");
        desc("create", "以机械动力为核心玩法的大型模组，齿轮传动、自动化机器与更多创意机械。");
        desc("alexs-mobs", "为 Minecraft 添加大量新生物的扩展模组，每个生物都有独特机制。");
        desc("lithium", "轻量级通用优化模组，提升服务端与单机游戏整体性能。");
        desc("entityculling", "利用路径追踪跳过不可见实体/方块渲染，显著提升帧率。");
        desc("cloth-config", "模组配置界面库，许多模组用它提供图形化设置页。");
        desc("patchouli", "模组内嵌的图文手册框架，用于显示模组教程与百科。");
    }

    private static void put(String key, String zh) {
        NAMES.put(key.toLowerCase(Locale.ROOT), zh);
    }

    private static void desc(String key, String zh) {
        DESCS.put(key.toLowerCase(Locale.ROOT), zh);
    }

    private ModNames() {
    }

    /** 显示名称：有汉化 → 「中文名 (原名)」；无汉化或原名已是中文 → 原名。 */
    public static String display(String slug, String title) {
        String zh = lookup(slug, title, NAMES);
        if (zh == null) return title == null ? "" : title;
        String t = title == null ? "" : title;
        if (zh.equals(t) || t.contains(zh)) return t;
        return zh + " (" + t + ")";
    }

    /** 描述：有汉化 → 中文描述；无 → 原文。 */
    public static String desc(String slug, String title, String description) {
        String zh = lookup(slug, title, DESCS);
        return zh != null ? zh : (description == null ? "" : description);
    }

    private static String lookup(String slug, String title, Map<String, String> table) {
        if (slug != null && !slug.isBlank()) {
            String v = table.get(slug.toLowerCase(Locale.ROOT));
            if (v != null) return v;
        }
        if (title != null && !title.isBlank()) {
            String v = table.get(title.toLowerCase(Locale.ROOT));
            if (v != null) return v;
        }
        return null;
    }

    /**
     * 中英文搜索支持：输入中文名（如「钠」「小地图」）时，返回汉化表中所有
     * 中文名与关键词匹配的条目对应的英文 slug 列表，用于扩展搜索关键词。
     * 英文关键词通常返回空，由搜索 API 原生处理。
     */
    public static List<String> searchKeys(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.trim().toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            String zh = e.getValue().toLowerCase(Locale.ROOT);
            if (zh.contains(q) || q.contains(zh)) {
                out.add(e.getKey());
            }
        }
        return new ArrayList<>(out);
    }
}
