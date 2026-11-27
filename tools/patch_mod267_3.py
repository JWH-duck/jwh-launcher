# -*- coding: utf-8 -*-
import io
JA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(JA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

# 1) cardNode 入口分流到新卡片
old_sig = "    private StackPane cardNode(ModrinthApi.Hit h) {\n        StackPane card = new StackPane();"
new_sig = ("    private StackPane cardNode(ModrinthApi.Hit h) {\n"
           "        if (type == Type.MOD) return modCard(h);\n"
           "        StackPane card = new StackPane();")
assert t.count(old_sig) == 1, "cardNode sig anchor"
t = t.replace(old_sig, new_sig, 1)

# 2) 两处填充后应用排序
old_add = "                items.addAll(merged);"
assert t.count(old_add) == 2, "addAll count=%d" % t.count(old_add)
t = t.replace(old_add, "                items.addAll(merged);\n                applySort();")

# 3) 在 cardNode 方法结束后追加新卡片与辅助方法
anchor = "            }\n        }\n        return card;\n    }\n"
assert t.count(anchor) == 1, "cardNode end anchor"
helpers = '''            }
        }
        return card;
    }

    /** Mod 新样式卡片：深色底 + 图标 + 标题/描述 + 来源胶囊 + 右下下载量（不含作者/版本胶囊）。 */
    private StackPane modCard(ModrinthApi.Hit h) {
        StackPane card = new StackPane();
        card.setPadding(new Insets(12));
        String baseStyle = "-fx-background-color:#2f2538; -fx-background-radius:12; -fx-border-radius:12;"
                + " -fx-border-color:rgba(255,255,255,0.08); -fx-border-width:1;";
        card.setStyle(baseStyle);
        card.setMinWidth(0);
        card.setPrefWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMinHeight(104);
        card.setPrefHeight(104);
        card.setMaxHeight(104);
        card.setAlignment(Pos.TOP_LEFT);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> openDetailPage(h));
        card.setOnMouseEntered(e -> card.setStyle(baseStyle.replace("rgba(255,255,255,0.08)", "#d982c2")));
        card.setOnMouseExited(e -> card.setStyle(baseStyle));

        ImageView iconView = new ImageView();
        iconView.setFitWidth(40);
        iconView.setFitHeight(40);
        iconView.setPreserveRatio(true);
        iconView.setSmooth(true);
        Label titleLabel = new Label(ModNames.display(h.slug(), h.title()));
        titleLabel.setStyle("-fx-text-fill:#ffffff; -fx-font-size:15px; -fx-font-weight:bold;");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleLabel.setMinHeight(22);
        Label descLabel = new Label(ModNames.desc(h.slug(), h.title(), h.description()));
        descLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);
        descLabel.setPrefHeight(34);
        descLabel.setMaxHeight(34);
        VBox info = new VBox(6, titleLabel, descLabel);
        info.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label sourceTag = new Label(sourceTagText());
        sourceTag.setStyle("-fx-background-color:#2c2336; -fx-text-fill:#b9bfc7; -fx-font-size:11px;"
                + " -fx-background-radius:6; -fx-padding:3 8;");
        Label dlLabel = new Label(formatDownloads(h.downloads()) + " 下载");
        dlLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");
        Region spacer = new Region();
        HBox bottom = new HBox(10, sourceTag, spacer, dlLabel);
        bottom.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);

        VBox content = new VBox(10, new HBox(12, iconView, info), bottom);
        content.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().add(content);

        iconView.setImage(Ui.GRASS_BLOCK);
        String icon = h.iconUrl();
        if (icon != null && !icon.isBlank()) {
            String currentIconUrl = icon;
            Image cached = IconCache.cached(icon);
            if (cached != null) {
                iconView.setImage(cached);
            } else {
                IconCache.ensure(icon, img -> {
                    if (icon.equals(currentIconUrl)) iconView.setImage(img);
                });
            }
        }
        return card;
    }

    private String sourceTagText() {
        return "CurseForge".equals(sourceBox.getValue()) ? "CurseForge" : "Modrinth";
    }

    /** 按当前排序下拉重排本地结果（不重新请求接口）。 */
    private void applySort() {
        String s = sortBox.getValue();
        Comparator<ModrinthApi.Hit> cmp;
        if ("下载量（低→高）".equals(s)) {
            cmp = Comparator.comparingLong(ModrinthApi.Hit::downloads);
        } else if ("名称 A–Z".equals(s)) {
            cmp = Comparator.comparing(h -> h.title() == null ? "" : h.title().toLowerCase(Locale.ROOT));
        } else {
            cmp = Comparator.comparingLong(ModrinthApi.Hit::downloads).reversed();
        }
        items.sort(cmp);
    }

    /** 下载量格式化：1.2M / 3.4K，不足千显示原值。 */
    private static String formatDownloads(long d) {
        if (d >= 1_000_000) return String.format(Locale.US, "%.1fM", d / 1_000_000.0);
        if (d >= 1_000) return String.format(Locale.US, "%.1fK", d / 1_000.0);
        return String.valueOf(d);
    }
'''
t = t.replace(anchor, helpers, 1)

with io.open(JA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("step3 card branch + sort + format ok")
