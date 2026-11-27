# -*- coding: utf-8 -*-
import io

API = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\download\ModrinthApi.java"
with io.open(API, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")

# 1) record 补 author / versions
old_rec = ('    /** 搜索结果条目。 */\n'
           '    public record Hit(String projectId, String slug, String title, String description,\n'
           '                      String iconUrl, long downloads, List<String> categories) {\n'
           '    }\n')
new_rec = ('    /** 搜索结果条目。 */\n'
           '    public record Hit(String projectId, String slug, String title, String description,\n'
           '                      String iconUrl, long downloads, List<String> categories,\n'
           '                      String author, List<String> versions) {\n'
           '    }\n')
assert s.count(old_rec) == 1, ("rec", s.count(old_rec))
s = s.replace(old_rec, new_rec)

# 2) search 解析 author / versions
old_loop = ('                List<String> cats = new ArrayList<>();\n'
            '                JSONArray c = h.optJSONArray("categories");\n'
            '                if (c != null) {\n'
            '                    for (int j = 0; j < c.length(); j++) cats.add(c.getString(j));\n'
            '                }\n'
            '                out.add(new Hit(\n'
            '                        h.optString("project_id"),\n'
            '                        h.optString("slug"),\n'
            '                        h.optString("title"),\n'
            '                        h.optString("description"),\n'
            '                        h.optString("icon_url"),\n'
            '                        h.optLong("downloads", 0),\n'
            '                        cats));\n')
new_loop = ('                List<String> cats = new ArrayList<>();\n'
            '                JSONArray c = h.optJSONArray("categories");\n'
            '                if (c != null) {\n'
            '                    for (int j = 0; j < c.length(); j++) cats.add(c.getString(j));\n'
            '                }\n'
            '                List<String> vers = new ArrayList<>();\n'
            '                JSONArray vv = h.optJSONArray("versions");\n'
            '                if (vv != null) {\n'
            '                    for (int j = 0; j < vv.length(); j++) vers.add(vv.getString(j));\n'
            '                }\n'
            '                out.add(new Hit(\n'
            '                        h.optString("project_id"),\n'
            '                        h.optString("slug"),\n'
            '                        h.optString("title"),\n'
            '                        h.optString("description"),\n'
            '                        h.optString("icon_url"),\n'
            '                        h.optLong("downloads", 0),\n'
            '                        cats,\n'
            '                        h.optString("author"),\n'
            '                        vers));\n')
assert s.count(old_loop) == 1, ("loop", s.count(old_loop))
s = s.replace(old_loop, new_loop)

with io.open(API, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK ModrinthApi")
