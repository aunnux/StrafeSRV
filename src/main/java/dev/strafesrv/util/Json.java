package dev.strafesrv.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Null-safe helpers around Gson. */
public final class Json {
    public static final Gson GSON = new Gson();

    private Json() {}

    public static JsonElement parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return JsonParser.parseString(s);
        } catch (JsonParseException e) {
            return null;
        }
    }

    public static JsonObject parseObject(String s) {
        JsonElement e = parse(s);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public static int integer(JsonObject o, String key, int def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsInt();
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    public static String firstNonNull(String... values) {
        for (String v : values) if (v != null) return v;
        return null;
    }
}
