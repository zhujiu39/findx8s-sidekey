package cn.sidekey;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import org.json.JSONArray;
import org.json.JSONObject;

/** 只从本机订阅元数据取用量；跳过节点列表，不访问订阅地址。 */
final class SurfingSubscriptions {
    static JSONObject read(HttpURLConnection connection) throws Exception {
        ArrayList<JSONObject> items = new ArrayList<>();
        int omitted = 0;
        try (JsonReader reader = new JsonReader(new InputStreamReader(new FilterInputStream(connection.getInputStream()) {
            private int remaining = 4 * 1024 * 1024;
            @Override public int read() throws IOException {
                if (--remaining < 0) throw new IOException("订阅统计响应过长");
                return super.read();
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (remaining <= 0) throw new IOException("订阅统计响应过长");
                int count = in.read(bytes, offset, Math.min(length, remaining));
                if (count > 0) remaining -= count;
                return count;
            }
        }, StandardCharsets.UTF_8))) {
            boolean found = false;
            reader.beginObject();
            while (reader.hasNext()) {
                if (!"providers".equals(reader.nextName())) { reader.skipValue(); continue; }
                if (found) throw new IOException("订阅统计字段重复");
                found = true;
                reader.beginObject();
                int count = 0;
                while (reader.hasNext()) {
                    if (++count > 1024) throw new IOException("订阅列表过长");
                    String name = reader.nextName();
                    JSONObject item = provider(reader, name);
                    if (item == null) continue;
                    if (items.size() < 32) items.add(item); else omitted++;
                }
                reader.endObject();
            }
            reader.endObject();
            if (!found || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("订阅统计格式无效");
        }
        items.sort(Comparator.comparing(item -> item.optString("name")));
        JSONArray entries = new JSONArray();
        for (JSONObject item : items) entries.put(item);
        return new JSONObject().put("state", items.isEmpty() ? "empty" : "ok").put("items", entries).put("omitted", omitted);
    }

    private static JSONObject provider(JsonReader reader, String name) throws Exception {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null; }
        long up = -1, down = -1, total = -1;
        String updated = "";
        boolean info = false;
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if ("subscriptionInfo".equals(key) && reader.peek() == JsonToken.BEGIN_OBJECT) {
                if (info) throw new IOException("订阅用量字段重复");
                info = true;
                reader.beginObject();
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "Upload": up = number(reader); break;
                        case "Download": down = number(reader); break;
                        case "Total": total = number(reader); break;
                        default: reader.skipValue(); break;
                    }
                }
                reader.endObject();
            } else if ("updatedAt".equals(key) && reader.peek() == JsonToken.STRING) {
                String value = reader.nextString();
                if (value.length() <= 64) updated = value;
            } else reader.skipValue();
        }
        reader.endObject();
        if (!info) return null;
        JSONObject result = new JSONObject().put("name", displayName(name)).put("updatedAt", updated);
        if (up >= 0 && down >= 0 && up <= Long.MAX_VALUE - down) result.put("used", up + down);
        // Total 为 0 也可能是订阅没有上报额度，不能据此认定为无限流量。
        if (total > 0) result.put("total", total);
        return result;
    }

    private static long number(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) { reader.skipValue(); return -1; }
        String value = reader.nextString();
        if (!value.matches("[0-9]{1,19}")) return -1;
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { return -1; }
    }

    private static String displayName(String name) {
        StringBuilder label = new StringBuilder();
        for (int i = 0, count = 0; i < name.length() && count < 48; count++) {
            int code = name.codePointAt(i); i += Character.charCount(code);
            if (!Character.isISOControl(code)) label.appendCodePoint(code);
        }
        return label.length() == 0 ? "订阅" : label.toString();
    }
}
