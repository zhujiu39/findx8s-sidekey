// SPDX-License-Identifier: GPL-3.0-or-later
package cn.sidekey.mijia;

import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class MijiaBridge implements AutoCloseable {
    private final PrivateStore store;
    private final MiCloud cloud;
    private final MiSpec specs;
    private final MenuStates menuStates;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    private final ExecutorService loginWorker = Executors.newSingleThreadExecutor();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Set<MiHttp> requests = Collections.synchronizedSet(new HashSet<>());
    private JSONObject login = new JSONObject(), last = new JSONObject();
    private MiHttp loginRequest;
    private Future<?> loginFuture;
    private int loginGeneration;
    private boolean closing;
    private static final class Job {
        final String id, op; final long created = System.currentTimeMillis();
        volatile JSONObject result;
        Job(String id, String op) { this.id = id; this.op = op; }
    }
    MijiaBridge(PrivateStore store) throws Exception {
        this(store, new MiCloud(store));
    }
    MijiaBridge(PrivateStore store, MiCloud cloud) throws Exception {
        this(store, cloud, new MenuStates());
    }
    MijiaBridge(PrivateStore store, MiCloud cloud, MenuStates menuStates) throws Exception {
        this.store = store; this.cloud = cloud; specs = new MiSpec(store);
        this.menuStates = menuStates;
        last = store.read("last.json");
        if (last.optString("state").equals("pending")) {
            last.put("state", "unknown").put("message", "服务曾中断，上一条指令结果未知；请检查设备实际状态"); store.write("last.json", last);
        }
    }
    synchronized JSONObject handle(JSONObject request) throws Exception {
        String op = Json.text(request, "op", 32);
        if (closing) throw new Failure("STOPPED", "米家服务正在停止");
        if (op.equals("menu-states")) return menuStates(request);
        if (op.equals("status")) {
            JSONObject auth = store.read("auth.json"); String uid = auth.optString("userId");
            return Json.obj("ok", true, "loggedIn", auth.has("serviceToken"), "account", uid.length() > 4 ? "•••• " + uid.substring(uid.length() - 4) : "",
                    "region", "中国大陆", "bindings", bindings(auth), "last", Json.copy(last), "login", publicLogin());
        }
        if (op.equals("job")) {
            Job job = jobs.get(Json.text(request, "id", 32));
            if (job == null) throw new Failure("EXPIRED", "请求记录已过期或服务已重启；控制结果请以设备实际状态为准");
            return Json.obj("ok", true, "state", job.result == null ? "pending" : "done", "result", job.result);
        }
        if (op.equals("login-status")) return Json.obj("ok", true, "login", publicLogin());
        if (op.equals("login-cancel")) { cancelLogin(); return Json.obj("ok", true); }
        if (op.equals("logout")) cancelLogin();
        if (!Arrays.asList("homes", "catalog", "device", "run", "binding-save", "binding-delete", "trigger", "login-start", "logout").contains(op))
            throw new Failure("INVALID", "未知米家操作");
        String id = request.optString("requestId", MiCloud.randomId());
        if (!id.matches("[a-f0-9]{32}")) throw new Failure("INVALID", "请求编号无效");
        if (jobs.containsKey(id)) return Json.obj("ok", true, "job", id);
        Iterator<Job> iterator = jobs.values().iterator();
        while (iterator.hasNext()) { Job job = iterator.next(); if (job.result != null && (jobs.size() >= 32 || System.currentTimeMillis() - job.created > 600000)) iterator.remove(); }
        if (jobs.size() >= 32) throw new Failure("BUSY", "米家任务繁忙，请稍后重试");
        if (op.equals("login-start") && (login.optString("state").equals("waiting") || login.optString("state").equals("preparing")))
            throw new Failure("BUSY", "已有登录二维码，请先取消或等待完成");
        if (op.equals("login-start") && store.read("auth.json").has("serviceToken"))
            throw new Failure("LOGIN", "切换账号前请先退出当前米家账号");
        final Job job = new Job(id, op); final JSONObject copy = Json.copy(request);
        if (op.equals("login-start")) login = Json.obj("state", "preparing", "message", "正在生成二维码");
        final int generation = loginGeneration;
        jobs.put(id, job);
        try { worker.execute(() -> perform(job, copy, generation)); }
        catch (RejectedExecutionException error) {
            jobs.remove(id); if (op.equals("login-start")) login = Json.obj("state", "error", "message", "任务繁忙，请重试");
            throw new Failure("BUSY", "最多同时等待 4 个米家任务，请稍后重试");
        }
        return Json.obj("ok", true, "job", id);
    }
    private void perform(Job job, JSONObject request, int generation) {
        boolean control = job.op.equals("run") || job.op.equals("trigger");
        try (MiHttp http = new MiHttp(55000)) {
            requests.add(http);
            try {
                if (System.currentTimeMillis() - job.created > 60000) throw new Failure("EXPIRED", "排队时间过长，指令未执行，请重新操作");
                if (control) record(Json.obj("state", "pending", "message", "米家指令处理中", "time", System.currentTimeMillis()));
                job.result = operate(http, request, generation);
            } finally { requests.remove(http); }
        } catch (Exception error) { job.result = Failure.json(error); }
        if (control) try {
            record(Json.obj("state", job.result.optBoolean("ok") ? job.result.optString("state", "accepted") : "error",
                    "message", job.result.optString("message", "指令处理完成"), "code", job.result.optString("code"), "time", System.currentTimeMillis()));
        } catch (Exception error) { job.result = Failure.json(new Failure("STORAGE", "指令已处理，但执行记录保存失败，请检查设备状态")); }
        if (job.op.equals("login-start") && !job.result.optBoolean("ok")) synchronized (this) {
            if (generation == loginGeneration) try { login = Json.copy(job.result).put("state", "error"); } catch (Exception ignored) { }
        }
    }
    private synchronized void record(JSONObject value) throws Exception { last = value; store.write("last.json", last); }
    private JSONObject operate(MiHttp http, JSONObject request, int generation) throws Exception {
        String op = request.getString("op");
        if (op.equals("login-start")) { startLogin(http, generation); return Json.obj("ok", true); }
        if (op.equals("logout")) {
            store.delete("auth.json"); store.delete("bindings.json");
            menuStates.clear();
            return Json.obj("ok", true, "message", "已退出米家并删除本机登录凭据和米家动作");
        }
        JSONObject auth = cloud.authenticated(http);
        if (op.equals("homes")) {
            JSONArray source = cloud.homes(http, auth), homes = new JSONArray();
            for (int i = 0; i < source.length(); i++) { JSONObject home = source.getJSONObject(i);
                homes.put(Json.obj("id", String.valueOf(home.get("id")), "name", home.optString("name", "我的家"))); }
            return Json.obj("ok", true, "homes", homes);
        }
        if (op.equals("catalog")) {
            JSONObject catalog = cloud.catalog(http, auth, Json.id(request, "home"));
            catalog.put("account", auth.get("userId")); catalog.put("time", System.currentTimeMillis());
            store.write("catalog-" + Json.id(request, "home") + ".json", catalog);
            return Json.obj("ok", true, "devices", catalog.getJSONArray("devices"), "scenes", catalog.getJSONArray("scenes"), "sceneError", catalog.getString("sceneError"));
        }
        if (op.equals("device")) {
            JSONObject device = device(http, auth, request), spec = specs.get(http, device.getString("model"));
            JSONArray states = new JSONArray(), properties = spec.getJSONArray("properties"), batch = new JSONArray();
            for (int i = 0; i < properties.length(); i++) {
                JSONObject property = properties.getJSONObject(i);
                if (property.optBoolean("read")) batch.put(Json.obj("did", device.getString("did"), "siid", property.getInt("siid"), "piid", property.getInt("piid")));
                if (batch.length() == 20 || (i == properties.length() - 1 && batch.length() > 0)) {
                    JSONArray part = (JSONArray) cloud.call(http, auth, "/miotspec/prop/get", Json.obj("params", batch, "datasource", 1));
                    for (int j = 0; j < part.length(); j++) { JSONObject value = part.getJSONObject(j);
                        states.put(Json.obj("siid", value.get("siid"), "piid", value.get("piid"), "code", value.optInt("code", -1), "value", value.opt("value"))); }
                    batch = new JSONArray();
                }
            }
            return Json.obj("ok", true, "device", device, "spec", spec, "states", states);
        }
        if (op.equals("binding-save")) {
            JSONObject descriptor = normalize(http, auth, request.getJSONObject("action"));
            String name = Json.text(request, "name", 96), id = MiCloud.randomId();
            JSONObject all = store.read("bindings.json");
            if (all.length() >= 256) throw new Failure("LIMIT", "米家动作最多保存 256 项，请先移除不再使用的动作");
            all.put(id, Json.obj("name", name, "action", descriptor, "account", auth.get("userId"))); store.write("bindings.json", all);
            return Json.obj("ok", true, "id", id, "name", name);
        }
        if (op.equals("binding-delete")) {
            JSONObject all = store.read("bindings.json"); all.remove(Json.id(request, "id")); store.write("bindings.json", all); menuStates.clear(); return Json.obj("ok", true);
        }
        JSONObject descriptor = request.optJSONObject("action");
        if (op.equals("trigger")) {
            JSONObject binding = store.read("bindings.json").optJSONObject(Json.id(request, "id"));
            if (binding == null || !String.valueOf(binding.get("account")).equals(String.valueOf(auth.get("userId"))))
                throw new Failure("BINDING", "米家动作已删除或账号已更换，请重新绑定");
            descriptor = binding.getJSONObject("action");
        }
        if (descriptor == null) throw new Failure("INVALID", "未选择米家动作");
        return run(http, auth, normalize(http, auth, descriptor));
    }

    private JSONObject menuStates(JSONObject request) throws Exception {
        JSONArray ids = request.getJSONArray("ids");
        if (ids.length() > 256) throw new Failure("LIMIT", "快捷栏状态请求过多");
        String session = Json.text(request, "session", 64);
        if (!session.matches("[a-f0-9]{64}")) throw new Failure("INVALID", "快捷栏状态会话无效");
        for (int i = 0; i < ids.length(); i++)
            if (!ids.getString(i).matches("[a-f0-9]{32}")) throw new Failure("INVALID", "米家动作编号无效");
        JSONObject auth = store.read("auth.json"), all = store.read("bindings.json");
        String account = auth.has("serviceToken") ? auth.optString("userId") : "";
        MenuStates.Snapshot previous = menuStates.find(session, account, ids.toString());
        if (previous != null) return Json.obj("states", menuStates.wire(previous));
        StringBuilder states = new StringBuilder(); Map<String, JSONObject> switches = new LinkedHashMap<>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            JSONObject binding = all.optJSONObject(id);
            if (account.isEmpty() || binding == null || !account.equals(binding.optString("account"))) { states.append('?'); continue; }
            JSONObject action = binding.getJSONObject("action");
            states.append(MenuStates.switchAction(action) ? '?' : 'n');
            if (MenuStates.switchAction(action)) switches.put(id, Json.copy(action));
        }
        final MenuStates.Snapshot snapshot = menuStates.create(session, account, ids.toString(), states.toString());
        if (switches.isEmpty()) menuStates.complete(snapshot, states.toString());
        else try { worker.execute(() -> refreshMenuStates(snapshot, ids, switches)); }
        catch (RejectedExecutionException error) { menuStates.complete(snapshot, states.toString()); }
        // 此窄接口只有 n/未知/关/开，不携带账号、设备标识或控制参数。
        return Json.obj("states", menuStates.wire(snapshot));
    }

    private void refreshMenuStates(MenuStates.Snapshot snapshot, JSONArray ids, Map<String, JSONObject> switches) {
        Map<String, Character> values = new LinkedHashMap<>();
        for (String id : switches.keySet()) values.put(id, '?');
        try (MiHttp http = new MiHttp(15000)) {
            requests.add(http);
            try {
                if (System.nanoTime() - snapshot.created > 10000000000L) throw new Failure("EXPIRED", "快捷栏状态读取排队超时");
                JSONObject auth = cloud.authenticated(http);
                if (!snapshot.account.equals(auth.getString("userId"))) throw new Failure("AUTH", "米家账号已更换");
                Map<String, JSONObject> params = new LinkedHashMap<>(); Map<String, List<String>> groups = new LinkedHashMap<>();
                for (Map.Entry<String, JSONObject> entry : switches.entrySet()) {
                    JSONObject action = entry.getValue();
                    try {
                        JSONObject device = device(http, auth, action);
                        JSONObject property = MiSpec.find(specs.get(http, device.getString("model")), action.getInt("siid"), action.getInt("piid"), false);
                        if (!property.optBoolean("read") || !property.optString("format").equals("bool")) { values.put(entry.getKey(), 'n'); continue; }
                        String key = action.getString("did") + ":" + action.getInt("siid") + ":" + action.getInt("piid");
                        params.put(key, Json.obj("did", action.getString("did"), "siid", action.getInt("siid"), "piid", action.getInt("piid")));
                        groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry.getKey());
                    } catch (Exception ignored) { /* 单个设备规格不可用时保留未知，不影响其余设备。 */ }
                }
                List<String> keys = new ArrayList<>(params.keySet());
                for (int first = 0; first < keys.size(); first += 20) {
                    int end = Math.min(first + 20, keys.size()); JSONArray batch = new JSONArray();
                    for (int i = first; i < end; i++) batch.put(params.get(keys.get(i)));
                    JSONArray response = (JSONArray) cloud.call(http, auth, "/miotspec/prop/get", Json.obj("params", batch, "datasource", 1));
                    for (int i = first; i < end; i++) {
                        String key = keys.get(i); char state = '?';
                        try { state = MenuStates.value(matching(response, params.get(key))); } catch (Exception ignored) { }
                        for (String id : groups.get(key)) values.put(id, state);
                    }
                }
            } finally { requests.remove(http); }
        } catch (Exception ignored) { /* 网络或登录失效不伪装为关闭；已成功读取的批次仍可展示。 */ }
        try {
            boolean sameAccount = snapshot.account.equals(store.read("auth.json").optString("userId"));
            char[] result = snapshot.states.toCharArray();
            for (int i = 0; i < ids.length(); i++) {
                Character value = values.get(ids.getString(i));
                if (!sameAccount) result[i] = '?'; else if (value != null) result[i] = value;
            }
            menuStates.complete(snapshot, new String(result));
        } catch (Exception ignored) { menuStates.complete(snapshot, snapshot.states); }
    }
    private JSONObject catalog(MiHttp http, JSONObject auth, String home) throws Exception {
        JSONObject cached = store.read("catalog-" + home + ".json");
        if (String.valueOf(auth.get("userId")).equals(cached.optString("account")) && System.currentTimeMillis() - cached.optLong("time") < 86400000L) return cached;
        JSONObject result = cloud.catalog(http, auth, home); result.put("account", auth.get("userId")); result.put("time", System.currentTimeMillis());
        store.write("catalog-" + home + ".json", result); return result;
    }
    private JSONObject device(MiHttp http, JSONObject auth, JSONObject request) throws Exception {
        String home = Json.id(request, "home"), did = Json.id(request, "did");
        JSONArray devices = catalog(http, auth, home).getJSONArray("devices");
        for (int i = 0; i < devices.length(); i++) if (did.equals(devices.getJSONObject(i).getString("did"))) return devices.getJSONObject(i);
        throw new Failure("DEVICE", "找不到该设备，请刷新家庭设备列表");
    }
    private JSONObject normalize(MiHttp http, JSONObject auth, JSONObject source) throws Exception {
        String kind = Json.text(source, "kind", 16), home = Json.id(source, "home");
        if (kind.equals("scene")) {
            String id = Json.id(source, "scene"); JSONArray scenes = catalog(http, auth, home).getJSONArray("scenes");
            for (int i = 0; i < scenes.length(); i++) if (scenes.getJSONObject(i).getString("id").equals(id)) return Json.obj("kind", kind, "home", home, "scene", id);
            throw new Failure("SCENE", "找不到该手动场景，请刷新后重新选择");
        }
        if (!Arrays.asList("set", "toggle", "action").contains(kind)) throw new Failure("INVALID", "设备操作无效");
        JSONObject device = device(http, auth, source), spec = specs.get(http, device.getString("model"));
        int siid = Json.iid(source, "siid"), iid = Json.iid(source, kind.equals("action") ? "aiid" : "piid");
        JSONObject property = MiSpec.find(spec, siid, iid, kind.equals("action"));
        JSONObject result = Json.obj("kind", kind, "home", home, "did", device.getString("did"), "siid", siid, kind.equals("action") ? "aiid" : "piid", iid);
        if (kind.equals("action")) {
            JSONArray args = source.optJSONArray("values"), inputs = property.getJSONArray("in");
            if (args == null || args.length() != inputs.length()) throw new Failure("VALUE", "设备动作参数不完整");
            for (int i = 0; i < args.length(); i++) MiSpec.value(MiSpec.find(spec, siid, inputs.getInt(i), false), args.get(i));
            result.put("values", args);
        } else {
            if (!property.optBoolean("write")) throw new Failure("READ_ONLY", "该属性仅可读取");
            if (kind.equals("toggle") && (!property.optBoolean("read") || !property.getString("format").equals("bool")))
                throw new Failure("VALUE", "只有可读写的布尔开关支持切换");
            if (kind.equals("set")) result.put("value", MiSpec.value(property, source.get("value")));
        }
        return result;
    }
    private JSONObject readProperty(MiHttp http, JSONObject auth, JSONObject param) throws Exception {
        JSONArray values = (JSONArray) cloud.call(http, auth, "/miotspec/prop/get", Json.obj("params", new JSONArray().put(param), "datasource", 1));
        JSONObject value = matching(values, param);
        if (value.optInt("code", -1) != 0 || !value.has("value")) throw new Failure("DEVICE_" + value.optInt("code", -1), "读取设备状态失败，设备可能离线或不支持该属性");
        return value;
    }
    static JSONObject matching(JSONArray values, JSONObject param) throws Exception {
        for (int i = 0; i < values.length(); i++) { JSONObject value = values.getJSONObject(i);
            if (value.optString("did").equals(param.getString("did")) && value.optInt("siid") == param.getInt("siid") && value.optInt("piid") == param.getInt("piid")) return value; }
        throw new Failure("PROTOCOL", "响应未包含目标设备属性，结果未确认");
    }
    private JSONObject run(MiHttp http, JSONObject auth, JSONObject descriptor) throws Exception {
        String kind = descriptor.getString("kind");
        if (kind.equals("scene")) {
            JSONObject home = cloud.home(http, auth, descriptor.getString("home")); Object result;
            try { result = cloud.call(http, auth, "/appgateway/miot/appsceneservice/AppSceneService/NewRunScene", Json.obj("scene_id", descriptor.getString("scene"),
                        "scene_type", 2, "phone_id", "null", "home_id", descriptor.getString("home"), "owner_uid", home.getLong("uid"))); }
            catch (Exception error) { throw controlError(error); }
            if (Boolean.FALSE.equals(result) || (result instanceof JSONObject && ((JSONObject) result).optInt("code", 0) != 0)) throw new Failure("SCENE", "米家拒绝执行该场景");
            return Json.obj("ok", true, "state", "accepted", "message", "米家已受理场景，请以设备实际状态为准");
        }
        JSONObject param = Json.obj("did", descriptor.getString("did"), "siid", descriptor.getInt("siid"));
        if (kind.equals("action")) {
            param.put("aiid", descriptor.getInt("aiid")); param.put("in", descriptor.getJSONArray("values"));
            JSONObject result;
            try { result = (JSONObject) cloud.call(http, auth, "/miotspec/action", Json.obj("params", param)); }
            catch (Exception error) { throw controlError(error); }
            int code = result.optInt("code", -1);
            if (code != 0 && code != 1) throw new Failure("DEVICE_" + code, "设备拒绝动作，返回码 " + code);
            return Json.obj("ok", true, "state", "accepted", "message", code == 1 ? "网关已接收，尚未确认设备执行" : "设备已回应动作请求，请以实际状态为准");
        }
        param.put("piid", descriptor.getInt("piid")); Object value = descriptor.opt("value");
        if (kind.equals("toggle")) {
            Object current = readProperty(http, auth, param).get("value");
            if (!(current instanceof Boolean)) throw new Failure("VALUE", "设备未返回布尔开关状态，未发送切换指令");
            value = !((Boolean) current);
        }
        JSONObject write = Json.copy(param); write.put("value", value); JSONArray response;
        try { response = (JSONArray) cloud.call(http, auth, "/miotspec/prop/set", Json.obj("params", new JSONArray().put(write))); }
        catch (Exception error) { throw controlError(error); }
        JSONObject result = matching(response, param); int code = result.optInt("code", -1);
        if (code != 0 && code != 1) throw new Failure("DEVICE_" + code, "设备拒绝设置，返回码 " + code);
        // 写入只发送一次；读回失败不重发，防止重复切换或重复触发。
        try {
            JSONObject actual = readProperty(http, auth, param);
            if (MiSpec.same(value, actual.get("value"))) return Json.obj("ok", true, "state", "confirmed", "message", "已读回并确认设备状态", "value", actual.get("value"));
            return Json.obj("ok", true, "state", "accepted", "message", "指令已接收，设备状态尚未更新，请稍后刷新", "value", actual.get("value"));
        } catch (Exception error) { return Json.obj("ok", true, "state", "accepted", "message", "指令已接收，但未能读回状态，请检查设备"); }
    }
    private static Failure controlError(Exception error) {
        if (error instanceof Failure && !((Failure) error).code.equals("TIMEOUT") && !((Failure) error).code.startsWith("HTTP_")) return (Failure) error;
        return new Failure("UNKNOWN", "控制请求中断，设备执行结果未知；不会自动重发，请先检查实际状态");
    }
    private synchronized JSONArray bindings(JSONObject auth) throws Exception {
        JSONArray result = new JSONArray(); JSONObject saved = store.read("bindings.json"); Iterator<String> keys = saved.keys();
        while (keys.hasNext()) { String id = keys.next(); JSONObject item = saved.getJSONObject(id);
            if (item.optString("account").equals(auth.optString("userId"))) result.put(Json.obj("id", id, "name", item.getString("name"))); }
        return result;
    }
    private synchronized JSONObject publicLogin() throws Exception { return Json.copy(login); }
    private synchronized void cancelLogin() throws Exception {
        loginGeneration++; if (loginRequest != null) loginRequest.cancel();
        if (loginFuture != null) loginFuture.cancel(true);
        login = Json.obj("state", "cancelled", "message", "登录已取消");
    }
    private void startLogin(MiHttp http, int generation) throws Exception {
        JSONObject identity = MiCloud.identity(), qr = cloud.prepareLogin(http, identity);
        synchronized (this) {
            if (generation != loginGeneration || closing) throw new Failure("CANCELLED", "登录已取消");
            login = Json.obj("state", "waiting", "image", qr.getString("image"), "url", qr.getString("loginUrl"), "expires", System.currentTimeMillis() + 125000,
                    "message", "使用米家扫一扫确认登录；本机可截图后从米家相册识别");
            loginFuture = loginWorker.submit(() -> {
                try (MiHttp wait = new MiHttp(150000)) {
                    synchronized (this) { if (generation != loginGeneration || closing) return; loginRequest = wait; }
                    JSONObject auth = cloud.completeLogin(wait, identity, qr);
                    synchronized (this) {
                        if (generation != loginGeneration || closing) return;
                        auth.put("checked", 0); store.write("auth.json", auth); login = Json.obj("state", "done", "message", "登录成功");
                    }
                } catch (Exception error) { synchronized (this) {
                    if (generation == loginGeneration && !closing) {
                        login = Failure.json(error); try { login.put("state", "error"); } catch (JSONException ignored) { /* 固定字符串键不会触发此错误。 */ }
                    }
                } }
            });
        }
    }
    public synchronized void close() {
        closing = true; try { cancelLogin(); } catch (Exception ignored) { /* 停止时仍继续关闭网络连接和线程。 */ }
        synchronized (requests) { for (MiHttp request : requests) request.cancel(); }
        worker.shutdownNow(); loginWorker.shutdownNow();
    }
}
