// SPDX-License-Identifier: GPL-3.0-or-later
// 登录流程与接口适配自 Do1e/mijia-api 4.2.1，来源和修改范围见 mijia/README.md。
package cn.sidekey.mijia;

import java.io.ByteArrayInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

class MiCloud {
    static final String SERVICE = "https://account.xiaomi.com/pass/serviceLogin?_json=true&sid=mijia&_locale=zh_CN";
    private final PrivateStore store;
    MiCloud(PrivateStore store) { this.store = store; }
    static String randomId() {
        byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes);
        StringBuilder text = new StringBuilder(); for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    static JSONObject identity() throws Exception {
        String a = randomId().toUpperCase(Locale.ROOT) + "12345678";
        String pass = randomId().substring(0, 16);
        return Json.obj("deviceId", randomId().substring(0, 16), "pass_o", pass,
                "ua", "Android-15-11.0.701-Xiaomi-23046RP50C-OS2.0.212.0.VMYCNXM-" + a + "-CN-"
                        + randomId().toUpperCase(Locale.ROOT) + "-" + randomId().toUpperCase(Locale.ROOT)
                        + "-SmartHome-MI_APP_STORE-" + a + "|" + a + "|" + pass + "-64");
    }
    static Map<String, String> headers(JSONObject auth) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", auth.optString("ua", "Sidekey-Mijia/1.1"));
        headers.put("Content-Type", "application/x-www-form-urlencoded"); return headers;
    }
    static JSONObject loginResponse(String text) throws Exception {
        return new JSONObject(text.startsWith("&&&START&&&") ? text.substring(11) : text);
    }
    private static void loginCode(JSONObject data) throws Exception {
        if (data.optInt("code", 0) != 0) throw new Failure("LOGIN_" + data.optInt("code"), "扫码未完成、已取消或已过期，请重新生成二维码");
    }
    JSONObject prepareLogin(MiHttp http, JSONObject identity) throws Exception {
        Map<String, String> headers = headers(identity);
        headers.put("Cookie", "deviceId=" + identity.getString("deviceId") + ";pass_o=" + identity.getString("pass_o") + ";uLocale=zh_CN;");
        JSONObject service = loginResponse(http.get(SERVICE, headers, null, 12000));
        Map<String, String> params = MiHttp.query(service.getString("location"));
        params.put("theme", ""); params.put("bizDeviceType", ""); params.put("_hasLogo", "false");
        params.put("_qrsize", "240"); params.put("_dc", String.valueOf(System.currentTimeMillis()));
        JSONObject qr = loginResponse(http.get("https://account.xiaomi.com/longPolling/loginUrl?" + MiHttp.form(params), headers(identity), null, 12000));
        loginCode(qr);
        MiHttp.allowed(qr.getString("lp")); MiHttp.allowed(qr.getString("loginUrl"));
        byte[] image = http.request(qr.getString("qr"), headers(identity), null, null, 12000);
        boolean png = image.length > 8 && image[0] == (byte) 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G';
        boolean jpeg = image.length > 3 && image[0] == (byte) 0xff && image[1] == (byte) 0xd8;
        if ((!png && !jpeg) || image.length > 256 * 1024) throw new Failure("PROTOCOL", "无法读取登录二维码图片");
        qr.put("image", "data:image/" + (png ? "png" : "jpeg") + ";base64," + MiCrypto.b64(image)); return qr;
    }
    JSONObject completeLogin(MiHttp http, JSONObject identity, JSONObject qr) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        JSONObject response = loginResponse(http.get(qr.getString("lp"), headers(identity), cookies, 125000)); loginCode(response);
        JSONObject auth = Json.copy(identity);
        for (String key : new String[]{"psecurity", "nonce", "ssecurity", "passToken", "userId", "cUserId"}) auth.put(key, response.get(key));
        http.get(response.getString("location"), headers(identity), cookies, 12000);
        collectCookies(auth, cookies);
        validateAuth(auth); return auth;
    }
    private static void collectCookies(JSONObject auth, CookieManager cookies) throws Exception {
        for (HttpCookie cookie : cookies.getCookieStore().getCookies())
            if (Arrays.asList("serviceToken", "cUserId", "userId", "passToken").contains(cookie.getName())) auth.put(cookie.getName(), cookie.getValue());
    }
    static void validateAuth(JSONObject auth) throws Exception {
        for (String key : new String[]{"ua", "ssecurity", "userId", "cUserId", "serviceToken", "deviceId", "pass_o"}) {
            String value = String.valueOf(auth.opt(key));
            if (value.isEmpty() || value.equals("null") || value.length() > 2048 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf(';') >= 0)
                throw new Failure("AUTH", "登录凭据不完整，请重新扫码");
        }
        if (MiCrypto.decode(auth.getString("ssecurity")).length < 8) throw new Failure("AUTH", "登录凭据格式无效");
    }
    JSONObject authenticated(MiHttp http) throws Exception {
        JSONObject auth = store.read("auth.json");
        if (!auth.has("serviceToken")) throw new Failure("AUTH", "请先登录米家账号");
        validateAuth(auth);
        if (System.currentTimeMillis() - auth.optLong("checked", 0) < 60000) return auth;
        try { call(http, auth, "/v2/message/v2/check_new_msg", Json.obj("begin_at", System.currentTimeMillis() / 1000 - 3600)); }
        catch (Failure failure) {
            if (!failure.code.equals("AUTH")) throw failure;
            if (auth.optString("passToken").isEmpty()) throw failure;
            Map<String, String> headers = headers(auth);
            headers.put("Cookie", "deviceId=" + auth.getString("deviceId") + ";pass_o=" + auth.getString("pass_o")
                    + ";passToken=" + auth.getString("passToken") + ";userId=" + auth.get("userId") + ";cUserId=" + auth.getString("cUserId") + ";uLocale=zh_CN;");
            JSONObject refreshed = loginResponse(http.get(SERVICE, headers, null, 12000));
            if (refreshed.optInt("code", -1) != 0) throw failure;
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            http.get(refreshed.getString("location"), headers(auth), cookies, 12000);
            collectCookies(auth, cookies); auth.put("ssecurity", refreshed.getString("ssecurity"));
            call(http, auth, "/v2/message/v2/check_new_msg", Json.obj("begin_at", System.currentTimeMillis() / 1000 - 3600));
        }
        auth.put("checked", System.currentTimeMillis()); store.write("auth.json", auth); return auth;
    }
    Object call(MiHttp http, JSONObject auth, String uri, JSONObject data) throws Exception {
        String nonce = MiCrypto.nonce(); Map<String, String> headers = headers(auth);
        headers.put("miot-accept-encoding", "GZIP"); headers.put("miot-encrypt-algorithm", "ENCRYPT-RC4");
        headers.put("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2");
        headers.put("Cookie", "cUserId=" + auth.getString("cUserId") + ";yetAnotherServiceToken=" + auth.getString("serviceToken")
                + ";serviceToken=" + auth.getString("serviceToken") + ";timezone_id=Asia/Shanghai;timezone=GMT+08:00;is_daylight=0;dst_offset=0;channel=MI_APP_STORE;countryCode=CN;PassportDeviceId=" + auth.getString("deviceId") + ";locale=zh_CN");
        byte[] raw = http.request("https://api.mijia.tech/app" + uri, headers,
                MiHttp.form(MiCrypto.params(uri, data.toString(), auth.getString("ssecurity"), nonce)), null, 12000);
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (!text.startsWith("{")) {
            byte[] decoded = MiCrypto.rc4(MiCrypto.signedNonce(auth.getString("ssecurity"), nonce), MiCrypto.decode(text));
            if (decoded.length >= 2 && decoded[0] == (byte) 0x1f && decoded[1] == (byte) 0x8b)
                decoded = http.read(new GZIPInputStream(new ByteArrayInputStream(decoded)), 2 * 1024 * 1024);
            text = new String(decoded, StandardCharsets.UTF_8);
        }
        JSONObject result = new JSONObject(text); int code = result.optInt("code", -1);
        if (code == 3 || code == -3 || code == 401) throw new Failure("AUTH", "米家登录已失效，请重新登录");
        if (code != 0 || !result.has("result")) throw new Failure("CLOUD_" + code, "米家服务返回错误 " + code);
        return result.get("result");
    }
    Map<String, Boolean> online(MiHttp http, JSONObject auth, Collection<String> dids) throws Exception {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(dids));
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (int first = 0; first < unique.size(); first += 150) {
            List<String> batch = unique.subList(first, Math.min(first + 150, unique.size()));
            String cursor = ""; boolean more = true;
            for (int page = 0; more && page < 20; page++) {
                http.check();
                JSONObject params = Json.obj("dids", new JSONArray(batch), "limit", 200,
                        "get_split_device", true, "get_third_device", true);
                if (!cursor.isEmpty()) params.put("start_did", cursor);
                JSONObject result = (JSONObject) call(http, auth, "/v2/home/device_list_page", params);
                JSONArray list = result.getJSONArray("list");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject device = list.getJSONObject(i); String did = device.optString("did");
                    // 只采纳明确的在线标志；缺失或非法字段不能被当成离线或在线。
                    if (batch.contains(did) && device.opt("isOnline") instanceof Boolean)
                        states.put(did, (Boolean) device.get("isOnline"));
                }
                more = result.optBoolean("has_more", false);
                String next = result.optString("next_start_did");
                if (more && (next.isEmpty() || next.equals(cursor))) throw new Failure("PROTOCOL", "在线状态分页未推进");
                cursor = next;
            }
            if (more) throw new Failure("LIMIT", "在线状态超出读取范围");
        }
        return states;
    }
    JSONArray homes(MiHttp http, JSONObject auth) throws Exception {
        JSONObject result = (JSONObject) call(http, auth, "/v2/homeroom/gethome_merged",
                Json.obj("fg", true, "fetch_share", true, "fetch_share_dev", true, "fetch_cariot", true, "limit", 300, "app_ver", 7, "plat_form", 0));
        return result.getJSONArray("homelist");
    }
    JSONObject home(MiHttp http, JSONObject auth, String id) throws Exception {
        JSONArray homes = homes(http, auth);
        for (int i = 0; i < homes.length(); i++) if (String.valueOf(homes.getJSONObject(i).get("id")).equals(id)) return homes.getJSONObject(i);
        throw new Failure("HOME", "该家庭已删除或未向当前账号共享，请刷新家庭列表");
    }
    static Map<String, String> roomNames(JSONObject home) {
        Map<String, String> names = new HashMap<>();
        JSONArray rooms = Json.array(home, "roomlist");
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject room = rooms.optJSONObject(i);
            if (room == null) continue;
            String name = room.optString("name", "").trim();
            if (name.isEmpty()) continue;
            JSONArray dids = Json.array(room, "dids");
            for (int j = 0; j < dids.length(); j++) names.put(String.valueOf(dids.opt(j)), name);
        }
        return names;
    }
    JSONObject catalog(MiHttp http, JSONObject auth, String id) throws Exception {
        JSONObject home = home(http, auth, id); JSONArray devices = new JSONArray();
        Map<String, String> rooms = roomNames(home);
        String cursor = ""; boolean more = true;
        for (int page = 0; more && page < 20; page++) {
            JSONObject result = (JSONObject) call(http, auth, "/home/home_device_list", Json.obj("home_owner", home.getLong("uid"),
                    "home_id", Long.parseLong(id), "limit", 200, "start_did", cursor, "get_split_device", true, "support_smart_home", true, "get_cariot_device", true, "get_third_device", true));
            JSONArray list = Json.array(result, "device_info");
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                String did = String.valueOf(item.get("did"));
                devices.put(Json.obj("did", did, "name", item.optString("name", "未命名设备"),
                        "model", item.optString("model"), "online", item.optBoolean("isOnline", false),
                        "home", id, "room", rooms.getOrDefault(did, "未分组")));
            }
            String next = result.optString("max_did"); more = result.optBoolean("has_more", false);
            if (more && (next.isEmpty() || next.equals(cursor))) throw new Failure("PROTOCOL", "设备分页未推进，请重试");
            cursor = next;
        }
        if (more) throw new Failure("LIMIT", "该家庭的设备数量超过当前读取范围");
        JSONArray scenes = new JSONArray(); String sceneError = "";
        try {
            JSONObject result = (JSONObject) call(http, auth, "/appgateway/miot/appsceneservice/AppSceneService/GetSimpleSceneList",
                    Json.obj("app_version", 12, "get_type", 2, "home_id", id, "owner_uid", home.getLong("uid")));
            JSONArray list = Json.array(result, "manual_scene_info_list");
            for (int i = 0; i < list.length(); i++) { JSONObject item = list.getJSONObject(i);
                scenes.put(Json.obj("id", String.valueOf(item.get("scene_id")), "name", item.optString("name", item.optString("scene_name", "未命名场景")), "home", id)); }
        } catch (Exception error) { sceneError = Failure.json(error).optString("message"); }
        return Json.obj("devices", devices, "scenes", scenes, "sceneError", sceneError, "home", id, "owner", home.getLong("uid"));
    }
}
