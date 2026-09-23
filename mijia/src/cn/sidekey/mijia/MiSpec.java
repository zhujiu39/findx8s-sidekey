// SPDX-License-Identifier: GPL-3.0-or-later
// 设备规格解析适配自 Do1e/mijia-api devices.py。
package cn.sidekey.mijia;

import java.util.*;
import java.util.regex.*;
import java.net.URLEncoder;
import org.json.*;

final class MiSpec {
    private final PrivateStore store;
    MiSpec(PrivateStore store) { this.store = store; }
    JSONObject get(MiHttp http, String model) throws Exception {
        if (!model.matches("[A-Za-z0-9_.-]{1,120}")) throw new Failure("SPEC", "设备没有有效的 MIOT 型号");
        String file = "spec-" + model + ".json"; JSONObject cached = store.read(file);
        if (cached.optInt("schema") == 2 && System.currentTimeMillis() - cached.optLong("time") < 7 * 86400000L) return cached;
        Map<String, String> headers = new LinkedHashMap<>(); headers.put("User-Agent", "mijiaAPI/4.2.1");
        JSONObject parsed = parse(http.get("https://home.miot-spec.com/spec/" + model, headers, null, 12000), model);
        // 展示页的 tree 可能省略单位；按同一规格 URN 补齐官方属性元数据，失败时不猜单位。
        String urn = parsed.optString("urn");
        if (urn.startsWith("urn:miot-spec-v2:device:") && urn.length() < 300) try {
            JSONObject raw = new JSONObject(http.get("https://miot-spec.org/miot-spec-v2/instance?type=" +
                    URLEncoder.encode(urn, "UTF-8"), headers, null, 5000));
            if (!urn.equals(raw.optString("type"))) throw new Failure("SPEC", "单位来源与设备规格不匹配");
            JSONArray services = Json.array(raw, "services");
            for (int s = 0; s < services.length(); s++) {
                JSONObject service = services.getJSONObject(s); JSONArray properties = Json.array(service, "properties");
                for (int p = 0; p < properties.length(); p++) {
                    JSONObject property = properties.getJSONObject(p);
                    try { find(parsed, service.getInt("iid"), property.getInt("iid"), false).put("unit", property.optString("unit", "")); }
                    catch (Failure ignored) { }
                }
            }
        } catch (Exception error) { store.debug("规格单位读取失败，保留原始属性定义"); }
        JSONArray properties = parsed.getJSONArray("properties");
        for (int i = 0; i < properties.length(); i++) ReadingValues.decorate(properties.getJSONObject(i));
        parsed.put("schema", 2); parsed.put("time", System.currentTimeMillis()); store.write(file, parsed); return parsed;
    }
    static JSONObject parse(String html, String model) throws Exception {
        Matcher matcher = Pattern.compile("<script data-page=\"app\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL).matcher(html);
        if (!matcher.find()) throw new Failure("SPEC", "该型号暂时没有可用的 MIOT 属性信息");
        JSONObject props = new JSONObject(matcher.group(1)).getJSONObject("props");
        JSONObject i18n = props.optJSONObject("i18n"); i18n = i18n == null ? null : i18n.optJSONObject("zh_cn");
        if (i18n == null) i18n = new JSONObject();
        JSONArray properties = new JSONArray(), actions = new JSONArray(), services = props.getJSONObject("tree").getJSONArray("services");
        for (int s = 0; s < services.length(); s++) {
            JSONObject service = services.getJSONObject(s); int siid = service.getInt("iid");
            String prefix = String.format(Locale.ROOT, "service:%03d", siid);
            String serviceName = i18n.optString(prefix, service.optString("description", service.optString("type")));
            JSONArray source = Json.array(service, "properties");
            for (int p = 0; p < source.length(); p++) {
                JSONObject property = source.getJSONObject(p); int piid = property.getInt("iid");
                JSONArray access = Json.array(property, "access"), values = new JSONArray(), list = Json.array(property, "valueList");
                for (int v = 0; v < list.length(); v++) { JSONObject option = list.getJSONObject(v);
                    values.put(Json.obj("value", option.get("value"), "name", i18n.optString(option.optString("i18nKey"), option.optString("description")))); }
                properties.put(Json.obj("siid", siid, "piid", piid, "type", property.optString("type"), "service", serviceName,
                        "name", i18n.optString(prefix + String.format(Locale.ROOT, ":property:%03d", piid), property.optString("description")),
                        "format", property.optString("format"), "read", contains(access, "read"), "write", contains(access, "write"),
                        "notify", contains(access, "notify"), "unit", property.optString("unit", ""),
                        "range", property.opt("valueRange"), "values", values));
            }
            source = Json.array(service, "actions");
            for (int a = 0; a < source.length(); a++) { JSONObject action = source.getJSONObject(a); int aiid = action.getInt("iid");
                actions.put(Json.obj("siid", siid, "aiid", aiid, "service", serviceName,
                        "name", i18n.optString(prefix + String.format(Locale.ROOT, ":action:%03d", aiid), action.optString("description")),
                        "in", Json.array(action, "in"))); }
        }
        for (int i = 0; i < properties.length(); i++) ReadingValues.decorate(properties.getJSONObject(i));
        JSONObject identity = props.optJSONObject("spec");
        return Json.obj("model", model, "urn", identity == null ? "" : identity.optString("urn"), "properties", properties, "actions", actions);
    }
    static boolean contains(JSONArray values, String value) {
        for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
        return false;
    }
    static JSONObject find(JSONObject spec, int siid, int iid, boolean action) throws Exception {
        JSONArray list = spec.getJSONArray(action ? "actions" : "properties");
        for (int i = 0; i < list.length(); i++) { JSONObject item = list.getJSONObject(i);
            if (item.getInt("siid") == siid && item.getInt(action ? "aiid" : "piid") == iid) return item; }
        throw new Failure("SPEC", "该设备不支持所选操作，请重新选择");
    }
    static Object value(JSONObject property, Object value) throws Exception {
        String format = property.getString("format"); boolean valid = false;
        if (format.equals("bool")) valid = value instanceof Boolean;
        else if (format.equals("string")) valid = value instanceof String && ((String) value).length() <= 256;
        else if (format.matches("u?int(8|16|32|64)?") || format.equals("float")) {
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue(); valid = !Double.isInfinite(number) && !Double.isNaN(number);
                if (!format.equals("float")) valid &= number == Math.rint(number) && Math.abs(number) <= 9007199254740991L;
                if (format.startsWith("uint")) valid &= number >= 0;
                if (format.matches("u?int(8|16|32)")) {
                    boolean unsigned = format.startsWith("u"); int bits = Integer.parseInt(format.replaceAll("\\D", ""));
                    valid &= number >= (unsigned ? 0 : -Math.pow(2, bits - 1)) && number <= Math.pow(2, unsigned ? bits : bits - 1) - 1;
                }
                JSONArray range = property.optJSONArray("range");
                if (range != null && range.length() >= 2) {
                    double min = range.getDouble(0), max = range.getDouble(1); valid &= number >= min && number <= max;
                    double step = range.length() > 2 ? range.getDouble(2) : 0;
                    if (step > 0) valid &= Math.abs((number - min) / step - Math.rint((number - min) / step)) < 0.00001;
                }
            }
        }
        JSONArray values = Json.array(property, "values");
        if (values.length() > 0) {
            boolean found = false;
            for (int i = 0; i < values.length(); i++) if (same(values.getJSONObject(i).get("value"), value)) found = true;
            valid &= found;
        }
        if (!valid) throw new Failure("VALUE", "数值类型、范围或步长不符合设备要求");
        return value;
    }
    static boolean same(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
        return Objects.equals(left, right);
    }
}
