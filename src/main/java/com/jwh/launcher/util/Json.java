package com.jwh.launcher.util;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JSON 便捷工具：nullable 取值，避免到处判空。
 */
public final class Json {

    private Json() {
    }

    public static JSONObject obj(String text) {
        return new JSONObject(text);
    }

    public static String optStr(JSONObject o, String key, String def) {
        return o != null && o.has(key) && !o.isNull(key) ? o.optString(key) : def;
    }

    public static String optStr(JSONObject o, String key) {
        return optStr(o, key, null);
    }

    public static JSONObject optObj(JSONObject o, String key) {
        return o == null || o.isNull(key) || !o.has(key) ? null : o.optJSONObject(key);
    }

    public static JSONArray optArr(JSONObject o, String key) {
        return o == null || o.isNull(key) || !o.has(key) ? null : o.optJSONArray(key);
    }
}
