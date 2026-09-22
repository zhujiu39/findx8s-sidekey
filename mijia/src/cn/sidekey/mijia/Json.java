// SPDX-License-Identifier: GPL-3.0-or-later
package cn.sidekey.mijia;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

final class Json {
    static JSONObject obj(Object... pairs) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    static JSONArray array(JSONObject object, String key) {
        JSONArray result = object.optJSONArray(key);
        return result == null ? new JSONArray() : result;
    }
    static String text(JSONObject object, String key, int max) throws Exception {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).isEmpty()
                || ((String) value).getBytes(StandardCharsets.UTF_8).length > max
                || ((String) value).indexOf('\0') >= 0) throw new Failure("INVALID", "参数无效：" + key);
        return (String) value;
    }
    static String id(JSONObject object, String key) throws Exception {
        String value = String.valueOf(object.get(key));
        if (!value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new Failure("INVALID", "标识无效：" + key);
        return value;
    }
    static int iid(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()
                || ((Number) value).intValue() < 1 || ((Number) value).intValue() > 65535)
            throw new Failure("INVALID", "设备属性编号无效");
        return ((Number) value).intValue();
    }
    static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    static byte[] unhex(String text) throws Exception {
        if (text.length() > 65536 || text.length() % 2 != 0 || !text.matches("[0-9a-f]*"))
            throw new Failure("INVALID", "请求编码无效");
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
