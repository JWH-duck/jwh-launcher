# -*- coding: utf-8 -*-
import io, re
JA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(JA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

# 1) 字段：新增 sortBox
old_fields = """    private final TextField searchBox = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<String> sourceBox = new ComboBox<>();
    private final Button searchBtn = new Button("搜索");
    private final Button refreshBtn = new Button("刷新");"""
new_fields = """    private final TextField searchBox = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<String> sourceBox = new ComboBox<>();
    private final ComboBox<String> sortBox = new ComboBox<>();
    private final Button searchBtn = new Button("搜索");
    private final Button refreshBtn = new Button("刷新");"""
assert t.count(old_fields) == 1, "fields anchor"
t = t.replace(old_fields, new_fields, 1)

# 2) import 补 Comparator / Locale
old_imp = "import java.util.ArrayList;\nimport java.util.HashSet;\nimport java.util.List;\nimport java.util.Set;\n"
new_imp = ("import java.util.ArrayList;\nimport java.util.Comparator;\nimport java.util.HashSet;\n"
           "import java.util.List;\nimport java.util.Locale;\nimport java.util.Set;\n")
assert t.count(old_imp) == 1, "import anchor"
t = t.replace(old_imp, new_imp, 1)

with io.open(JA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("step1 fields+imports ok")
