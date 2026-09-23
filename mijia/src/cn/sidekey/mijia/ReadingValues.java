// SPDX-License-Identifier: GPL-3.0-or-later
package cn.sidekey.mijia;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** 按 MIOT 属性语义显示读数，不用可写设定值代替传感器测量值。 */
final class ReadingValues {
    static final int MAX_PROPERTIES = 4, MAX_BYTES = 384;
    private ReadingValues() { }
    static String type(JSONObject property) {
        String value = property.optString("type");
        String[] parts = value.split(":");
        return parts.length > 3 && "property".equals(parts[2]) ? parts[3] : value;
    }
    static boolean setpoint(JSONObject property) {
        String type = type(property).toLowerCase(Locale.ROOT), name = property.optString("name");
        return type.matches(".*(?:target|setpoint|set-point|set|desired|preset|setting)-temperature.*") ||
                type.matches(".*temperature-(?:target|setpoint|set-point|set|setting|preset).*") ||
                (name.toLowerCase(Locale.ROOT).contains("temperature") &&
                    name.toLowerCase(Locale.ROOT).matches(".*\\b(?:target|setpoint|set|desired|preset|setting)\\b.*")) ||
                name.matches(".*(?:设定|设置|目标|预设|调节).*温度.*|.*温度.*(?:设定|设置|目标|预设|调节).*");
    }
    static boolean eligible(JSONObject property) {
        return (property.optBoolean("read") || property.optBoolean("notify")) && !property.optBoolean("write") &&
                !setpoint(property) && property.optString("format").matches("bool|string|float|u?int(?:8|16|32|64)?");
    }
    static void decorate(JSONObject property) throws Exception {
        String name = property.optString("name", type(property));
        if ("temperature".equals(type(property)) && eligible(property)) name = "当前温度";
        if (setpoint(property) && !name.matches(".*(?:设定|设置|目标|预设).*")) name += "（设定值）";
        property.put("displayName", name).put("displayUnit", unit(property.optString("unit")))
                .put("reading", eligible(property)).put("setpoint", setpoint(property));
    }
    static String unit(String value) {
        switch (value) {
            case "celsius": return "°C";
            case "fahrenheit": return "°F";
            case "kelvin": return "K";
            case "percentage": return "%";
            case "pascal": return "Pa";
            case "hectopascal": return "hPa";
            case "lux": return "lx";
            case "watt": return "W";
            case "kilowatt-hour": return "kWh";
            case "volt": return "V";
            case "ampere": return "A";
            case "microgram-per-cubic-meter": return "μg/m³";
            case "ppm": return "ppm";
            case "none": case "null": return "";
            default: return clean(value, 24);
        }
    }
    static String key(String did, JSONObject property) {
        return did + ":" + property.optInt("siid") + ":" + property.optInt("piid");
    }
    static String value(JSONObject property, JSONObject state) {
        if (state == null || state.optInt("code", -1) != 0 || state.isNull("value")) return "暂无数据";
        Object raw = state.opt("value"); String format = property.optString("format");
        boolean valid = "bool".equals(format) ? raw instanceof Boolean : "string".equals(format) ? raw instanceof String : raw instanceof Number;
        if (!valid) return "暂无数据";
        if (raw instanceof Number) {
            double number = ((Number) raw).doubleValue();
            if (Double.isInfinite(number) || Double.isNaN(number)) return "暂无数据";
            JSONArray range = property.optJSONArray("range");
            if (range != null && range.length() >= 2 && (number < range.optDouble(0) || number > range.optDouble(1))) return "暂无数据";
        }
        JSONArray choices = Json.array(property, "values");
        for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i);
            if (choice != null && MiSpec.same(choice.opt("value"), raw)) return clean(choice.optString("name"), 30);
        }
        if (raw instanceof Boolean) return (Boolean) raw ? "是" : "否";
        String text = raw instanceof Number ? new BigDecimal(raw.toString()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : raw.toString();
        if (text.isEmpty()) return "暂无数据";
        String unit = property.optString("displayUnit");
        return clean(text + (unit.isEmpty() ? "" : " " + unit), 30);
    }
    static String card(String did, List<JSONObject> properties, Map<String, JSONObject> states) {
        StringBuilder text = new StringBuilder();
        for (JSONObject property : properties) {
            if (text.length() > 0) text.append('\n');
            String name = property.optString("displayName", property.optString("name"));
            String service = property.optString("service");
            text.append(service.isEmpty() ? clean(name, 60) : clean(service, 25) + " · " + clean(name, 30)).append("：")
                    .append(value(property, states.get(key(did, property))));
        }
        return text.toString();
    }
    static String clean(String text, int maxBytes) {
        String safe = text.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").trim();
        if (safe.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return safe;
        StringBuilder out = new StringBuilder(); int bytes = 0;
        for (int i = 0; i < safe.length();) {
            int code = safe.codePointAt(i); String next = new String(Character.toChars(code));
            int size = next.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes - 3) break;
            out.append(next); bytes += size; i += Character.charCount(code);
        }
        return out.append('…').toString();
    }
}
