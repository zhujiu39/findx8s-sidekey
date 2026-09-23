package cn.sidekey.mijia;

import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class MijiaTest {
    private static int checks;
    static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    interface Throwing { void run() throws Exception; }
    static void rejects(Throwing fn, String code) throws Exception {
        try { fn.run(); throw new AssertionError("expected " + code); }
        catch (Failure error) { check(code.equals(error.code), "failure code " + error.code); }
    }
    static JSONObject await(MijiaBridge bridge, JSONObject request) throws Exception {
        JSONObject start = bridge.handle(request); String id = start.getString("job"); long limit = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < limit) {
            JSONObject job = bridge.handle(Json.obj("op", "job", "id", id));
            if (job.getString("state").equals("done")) return job.getJSONObject("result");
            Thread.sleep(10);
        }
        throw new AssertionError("job timeout");
    }
    static class FakeCloud extends MiCloud {
        Object actual = false; int writes, actions, scenes, reads; int writeCode, actionCode;
        boolean disconnect, offline, applyThenDisconnect, applyDespiteCode, offlineAfterWrite, sceneTurnsOn; String account = "10001";
        java.util.concurrent.CountDownLatch writeEntered, releaseWrite;
        java.util.concurrent.CountDownLatch readEntered, releaseRead;
        int blockAtRead;
        List<Object> afterWriteReads = Collections.emptyList();
        final Queue<Object> delayedReads = new ArrayDeque<>();
        final List<String> availabilityQueries = new ArrayList<>(), propertyQueries = new ArrayList<>();
        final Map<String, Boolean> availability = new HashMap<>();
        boolean availabilityFailure, omitAvailability; Object otherActual = true;
        FakeCloud(PrivateStore store) { super(store); }
        @Override JSONObject authenticated(MiHttp h) throws Exception { return Json.obj("userId", account); }
        @Override JSONArray homes(MiHttp h, JSONObject a) throws Exception { return new JSONArray().put(home(h, a, "1")); }
        @Override JSONObject home(MiHttp h, JSONObject a, String id) throws Exception { return Json.obj("id", "1", "uid", 10001, "name", "fixture"); }
        @Override JSONObject catalog(MiHttp h, JSONObject a, String id) throws Exception {
            return Json.obj("devices", new JSONArray().put(Json.obj("did", "2", "home", "1", "model", "test.light.fixture", "online", true))
                    .put(Json.obj("did", "4", "home", "1", "model", "test.light.fixture", "online", true)),
                    "scenes", new JSONArray().put(Json.obj("id", "3", "name", "fixture", "home", "1")), "owner", 10001, "sceneError", "");
        }
        @Override Object call(MiHttp h, JSONObject a, String uri, JSONObject data) throws Exception {
            if (uri.equals("/v2/home/device_list_page")) {
                JSONArray dids = data.getJSONArray("dids"), list = new JSONArray();
                for (int i = 0; i < dids.length(); i++) {
                    String did = dids.getString(i); availabilityQueries.add(did);
                    if (!omitAvailability) list.put(Json.obj("did", did, "isOnline", availability.getOrDefault(did, !offline)));
                }
                if (availabilityFailure) throw new java.io.IOException("synthetic credential=DO_NOT_PRINT");
                return Json.obj("list", list);
            }
            if (uri.endsWith("NewRunScene")) { scenes++; if (sceneTurnsOn) actual = true; return true; }
            if (uri.equals("/miotspec/action")) { actions++; return Json.obj("code", actionCode); }
            JSONArray params = data.getJSONArray("params"), result = new JSONArray();
            for (int i = 0; i < params.length(); i++) {
                JSONObject param = params.getJSONObject(i), item = Json.copy(param);
                if (uri.endsWith("/set")) {
                    if (writeEntered != null) { writeEntered.countDown(); if (!releaseWrite.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("blocked write timeout"); }
                    writes++; if (disconnect) throw new java.net.SocketTimeoutException("synthetic credential=DO_NOT_PRINT");
                    if (writeCode == 0 || applyDespiteCode) actual = param.get("value"); item.put("code", writeCode);
                    delayedReads.clear(); delayedReads.addAll(afterWriteReads); afterWriteReads = Collections.emptyList();
                    if (applyThenDisconnect) throw new java.net.SocketTimeoutException("synthetic credential=DO_NOT_PRINT");
                    if (offlineAfterWrite) offline = true;
                } else {
                    reads++;
                    propertyQueries.add(param.getString("did"));
                    if (reads == blockAtRead) { readEntered.countDown(); if (!releaseRead.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("blocked read timeout"); }
                    Object reported = param.getString("did").equals("4") ? otherActual : delayedReads.isEmpty() ? actual : delayedReads.remove();
                    item.put("code", offline || !(reported instanceof Boolean) ? -704042011 : 0).put("value", reported);
                }
                result.put(item);
            }
            return result;
        }
    }
    static void cancelledLogin(PrivateStore store) throws Exception {
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        MiCloud cloud=new FakeCloud(store) {
            @Override JSONObject prepareLogin(MiHttp h,JSONObject identity) throws Exception {
                return Json.obj("image","data:image/png;base64,fixture","loginUrl","https://account.xiaomi.com/fixture","lp","PRIVATE_POLLING_URL");
            }
            @Override JSONObject completeLogin(MiHttp h,JSONObject identity,JSONObject qr) throws Exception {
                entered.countDown();
                try { release.await(2,java.util.concurrent.TimeUnit.SECONDS); }
                catch(InterruptedException cancelled) { /* 模拟取消后仍迟到的网络响应。 */ }
                return Json.obj("userId","10001","serviceToken","LATE_SECRET");
            }
        };
        try(MijiaBridge bridge=new MijiaBridge(store,cloud)) {
            check(await(bridge,Json.obj("op","login-start")).optBoolean("ok"),"login prepared asynchronously");
            check(entered.await(1,java.util.concurrent.TimeUnit.SECONDS),"login worker started");
            check(!bridge.handle(Json.obj("op","status")).toString().contains("PRIVATE_POLLING_URL"),"private polling URL excluded");
            bridge.handle(Json.obj("op","login-cancel")); release.countDown();
        }
        check(!Files.exists(store.root.resolve("auth.json")),"late cancelled login must not restore credentials");
    }
    static String menu(MijiaBridge bridge, String token, JSONArray ids) throws Exception {
        JSONObject request=Json.obj("op","menu-states","session",token,"ids",ids);
        long until=System.currentTimeMillis()+5000; String state;
        do {
            state=bridge.handle(request).getString("states");
            if (!state.equals("P")) return state;
            Thread.sleep(10);
        } while(System.currentTimeMillis()<until);
        throw new AssertionError("menu state timeout");
    }
    static String menuResult(MijiaBridge bridge, JSONObject control) throws Exception {
        JSONObject request = Json.obj("op", "menu-result", "session", control.getString("session"), "requestId", control.getString("requestId"));
        long until = System.currentTimeMillis() + 5000;
        do {
            String state = bridge.handle(request).getString("states");
            if (!state.equals("P")) return state;
            Thread.sleep(10);
        } while (System.currentTimeMillis() < until);
        throw new AssertionError("menu control timeout");
    }
    static void menuControls(MijiaBridge bridge, FakeCloud cloud, PrivateStore store, String token, JSONArray visible) throws Exception {
        cloud.actual = false;
        JSONObject control = Json.obj("op", "menu-control", "session", token, "ids", visible, "id", visible.getString(0), "requestId", MiCloud.randomId());
        cloud.writeEntered = new java.util.concurrent.CountDownLatch(1); cloud.releaseWrite = new java.util.concurrent.CountDownLatch(1);
        int writes = cloud.writes, reads = cloud.reads;
        check(bridge.handle(control).getString("states").equals("P"), "menu control returns immediately while worker executes");
        check(cloud.writeEntered.await(1, java.util.concurrent.TimeUnit.SECONDS), "control reaches write stage");
        check(bridge.handle(control).getString("states").equals("P") && cloud.writes == writes, "duplicate request never queues another control");
        JSONObject poll = Json.obj("op", "menu-result", "session", token, "requestId", control.getString("requestId"));
        check(bridge.handle(poll).getString("states").equals("P"), "no stale state returned before operation completes");
        cloud.releaseWrite.countDown();
        check(menuResult(bridge, control).equals("D11n"), "successful click displays freshly read on state");
        cloud.writeEntered = cloud.releaseWrite = null;
        check(cloud.writes == writes + 1 && cloud.reads == reads + 2, "one pre-read and one post-read, duplicate bindings merged");
        check(bridge.handle(Json.obj("op", "status")).getJSONObject("last").optString("state").equals("confirmed"), "menu readback confirms action");
        for (int i = 0; i < 5; i++) check(bridge.handle(poll).getString("states").equals("D11n"), "local result stays stable");
        check(cloud.reads == reads + 2 && cloud.writes == writes + 1, "idle result polling never reads cloud or repeats write");
        JSONObject second = Json.copy(control).put("requestId", MiCloud.randomId()); bridge.handle(second);
        check(menuResult(bridge, second).equals("D00n"), "next click fetches fresh off state");
        check(menuResult(bridge, control).equals("D11n"), "old result cannot change newer operation");
        JSONObject scene = Json.copy(control).put("id", visible.getString(2)).put("requestId", MiCloud.randomId());
        cloud.sceneTurnsOn = true; reads = cloud.reads; int scenes = cloud.scenes; bridge.handle(scene);
        check(menuResult(bridge, scene).equals("D11n") && cloud.scenes == scenes + 1 && cloud.reads == reads + 1, "scene refreshes visible device states once after execution");
        cloud.sceneTurnsOn = false;
        cloud.applyThenDisconnect = true; writes = cloud.writes;
        JSONObject uncertain = Json.copy(control).put("requestId", MiCloud.randomId()); bridge.handle(uncertain);
        check(menuResult(bridge, uncertain).equals("D00n") && cloud.writes == writes + 1, "lost control reply rereads actual state without resending");
        check(bridge.handle(Json.obj("op", "status")).getJSONObject("last").optString("code").equals("UNKNOWN"), "ambiguous write remains explicit in result log");
        cloud.applyThenDisconnect = false; cloud.offlineAfterWrite = true;
        JSONObject offline = Json.copy(control).put("requestId", MiCloud.randomId()); bridge.handle(offline);
        check(menuResult(bridge, offline).equals("Doon"), "device disconnecting after control displays offline");
        cloud.offlineAfterWrite = cloud.offline = false;
        cloud.actual = false; cloud.writeCode = 1;
        JSONObject accepted = Json.copy(control).put("requestId", MiCloud.randomId()); bridge.handle(accepted);
        check(menuResult(bridge, accepted).equals("Duun"), "persistent contrary readback is online with unknown power state");
        check(bridge.handle(Json.obj("op", "status")).getJSONObject("last").optString("state").equals("accepted"), "unchanged readback is not confirmation");
        cloud.writeCode = 0;
        rejects(() -> bridge.handle(Json.copy(control).put("id", visible.getString(2))), "SESSION");
        rejects(() -> bridge.handle(Json.copy(poll).put("session", String.join("", Collections.nCopies(64, "f")))), "EXPIRED");
        writes = cloud.writes;
        try (MijiaBridge restarted = new MijiaBridge(store, cloud)) { rejects(() -> restarted.handle(poll), "EXPIRED"); }
        check(cloud.writes == writes, "service restart result polling cannot repeat control");
        String log = new String(Files.readAllBytes(store.root.resolve("debug.log")), java.nio.charset.StandardCharsets.UTF_8);
        check(log.contains("操作后读取") && log.contains("发送设置") && log.contains("ms"), "diagnostics contain stage timings");
        check(!log.contains("DO_NOT_PRINT") && !log.contains("SYNTHETIC_TEST_ONLY") && !log.contains(visible.getString(0)), "diagnostics exclude credentials, exception contents and binding IDs");
    }
    static void delayedMenuState(MijiaBridge bridge, FakeCloud cloud, PrivateStore store, String token, JSONArray visible) throws Exception {
        JSONObject alias = await(bridge, Json.obj("op", "binding-save", "name", "fixture off alias", "action",
                Json.obj("kind", "set", "value", false, "home", "1", "did", "2", "siid", 2, "piid", 1)));
        JSONArray ids = new JSONArray().put(visible.getString(0)).put(alias.getString("id")).put(visible.getString(2));
        JSONObject off = Json.obj("op", "menu-control", "session", token, "ids", ids, "id", visible.getString(0), "requestId", MiCloud.randomId());
        cloud.actual = true; cloud.afterWriteReads = Arrays.<Object>asList(true, true);
        int writes = cloud.writes, reads = cloud.reads;
        cloud.readEntered = new java.util.concurrent.CountDownLatch(1); cloud.releaseRead = new java.util.concurrent.CountDownLatch(1);
        cloud.blockAtRead = reads + 3;
        bridge.handle(off);
        try {
            check(cloud.readEntered.await(2, java.util.concurrent.TimeUnit.SECONDS), "stale first read starts a bounded read-only recheck");
            check(Boolean.FALSE.equals(cloud.actual), "light is physically off while cloud still reports on");
            check(bridge.handle(Json.obj("op", "menu-result", "session", token, "requestId", off.getString("requestId"))).getString("states").equals("P"),
                    "stale on state is never published while confirming off");
        } finally { cloud.releaseRead.countDown(); }
        check(menuResult(bridge, off).equals("D00n"), "delayed off readback updates all bindings of the same property");
        check(cloud.writes == writes + 1 && cloud.reads == reads + 4, "stale read retries only reads and stops immediately when matching");
        cloud.blockAtRead = 0;
        JSONObject on = Json.copy(off).put("requestId", MiCloud.randomId());
        cloud.afterWriteReads = Arrays.<Object>asList(false); reads = cloud.reads; writes = cloud.writes;
        bridge.handle(on);
        check(menuResult(bridge, on).equals("D11n"), "delayed on readback is also confirmed");
        check(cloud.reads == reads + 3 && cloud.writes == writes + 1, "one stale read adds only one recheck");
        JSONObject setOff = Json.copy(off).put("id", alias.getString("id")).put("requestId", MiCloud.randomId());
        cloud.afterWriteReads = Arrays.<Object>asList(true); reads = cloud.reads; writes = cloud.writes;
        bridge.handle(setOff);
        check(menuResult(bridge, setOff).equals("D00n"), "explicit turn-off also waits for delayed state confirmation");
        check(cloud.reads == reads + 2 && cloud.writes == writes + 1, "explicit turn-off avoids unnecessary pre-read and never repeats write");
        JSONObject recovered = Json.copy(off).put("requestId", MiCloud.randomId());
        cloud.afterWriteReads = Arrays.<Object>asList("unavailable"); bridge.handle(recovered);
        check(menuResult(bridge, recovered).equals("D11n"), "temporarily unavailable state can recover during confirmation");
        cloud.writeCode = 1; reads = cloud.reads; writes = cloud.writes;
        JSONObject unconfirmed = Json.copy(off).put("requestId", MiCloud.randomId()); bridge.handle(unconfirmed);
        check(menuResult(bridge, unconfirmed).equals("Duun"), "unconfirmed stale state is masked for distinct aliases of the same property");
        check(cloud.reads == reads + 6 && cloud.writes == writes + 1, "confirmation has exactly one initial read and at most four rechecks");
        check(bridge.handle(Json.obj("op", "status")).getJSONObject("last").optString("code").equals("STATE_UNCONFIRMED"), "failure to converge is explicit in diagnostics");
        cloud.writeCode = 0;
        String log = new String(Files.readAllBytes(store.root.resolve("debug.log")), java.nio.charset.StandardCharsets.UTF_8);
        check(log.contains("目标=0，读回=1") && log.contains("第 3 次，目标=0，读回=0"), "debug logs show stale read values and eventual confirmation");
        check(!log.contains(alias.getString("id")), "readback logs still exclude full binding IDs");
    }
    static void cancellableReadWait() throws Exception {
        try (MiHttp http = new MiHttp(30)) {
            rejects(() -> http.pause(2000), "TIMEOUT");
        }
        try (MiHttp http = new MiHttp(5000)) {
            java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            Thread waiting = new Thread(() -> {
                entered.countDown();
                try { http.pause(4000); }
                catch (Failure error) { cancelled.set(error.code.equals("TIMEOUT")); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            waiting.start(); entered.await(); http.cancel(); waiting.join(1000);
            check(!waiting.isAlive() && cancelled.get(), "cancellation wakes readback wait without waiting for full delay");
        }
    }
    static void uncertainMenuState(MijiaBridge bridge, FakeCloud cloud, PrivateStore store, String token, JSONArray visible) throws Exception {
        JSONObject alias = await(bridge, Json.obj("op", "binding-save", "name", "fixture timeout off", "action",
                Json.obj("kind", "set", "value", false, "home", "1", "did", "2", "siid", 2, "piid", 1)));
        JSONArray ids = new JSONArray().put(visible.getString(0)).put(alias.getString("id")).put(visible.getString(2));
        JSONObject base = Json.obj("op", "menu-control", "session", token, "ids", ids, "id", visible.getString(0));
        cloud.actual = false; cloud.writeCode = -704083036; cloud.applyDespiteCode = true;
        cloud.afterWriteReads = Arrays.<Object>asList(false, false);
        int writes = cloud.writes, reads = cloud.reads;
        JSONObject delayed = Json.copy(base).put("requestId", MiCloud.randomId()); bridge.handle(delayed);
        check(menuResult(bridge, delayed).equals("D11n"), "device timeout can converge to on instead of publishing the first stale off");
        check(cloud.writes == writes + 1 && cloud.reads == reads + 4, "timeout recovery only rereads and stops at target");
        JSONObject last = bridge.handle(Json.obj("op", "status")).getJSONObject("last");
        check(last.optString("state").equals("confirmed") && last.optString("code").equals("DEVICE_-704083036"), "confirmed readback keeps original device timeout code");
        check(last.optString("message").contains("超时") && last.optString("message").contains("已读回目标状态"), "result distinguishes timeout from observed target state");
        JSONObject completed = bridge.handle(Json.obj("op", "job", "id", delayed.getString("requestId"))).getJSONObject("result");
        check(completed.optBoolean("ok") && !completed.has("expected") && !completed.has("uncertain"), "confirmed timeout result removes internal reconciliation fields");

        cloud.writeCode = -704053036; cloud.afterWriteReads = Arrays.<Object>asList(true);
        writes = cloud.writes; reads = cloud.reads;
        JSONObject setOff = Json.copy(base).put("id", alias.getString("id")).put("requestId", MiCloud.randomId()); bridge.handle(setOff);
        check(menuResult(bridge, setOff).equals("D00n"), "second official timeout code supports explicit off convergence");
        check(cloud.writes == writes + 1 && cloud.reads == reads + 2, "explicit off timeout never adds pre-read or repeats control");
        check(bridge.handle(Json.obj("op", "status")).getJSONObject("last").optString("code").equals("DEVICE_-704053036"), "second timeout code is preserved");

        cloud.writeCode = -704083036; cloud.applyDespiteCode = false;
        writes = cloud.writes; reads = cloud.reads;
        JSONObject unresolved = Json.copy(base).put("requestId", MiCloud.randomId()); bridge.handle(unresolved);
        check(menuResult(bridge, unresolved).equals("Duun"), "device timeout with unchanged readback remains uncertain for every alias");
        check(cloud.writes == writes + 1 && cloud.reads == reads + 6, "timeout has at most five post-reads and one write");
        last = bridge.handle(Json.obj("op", "status")).getJSONObject("last");
        check(last.optString("state").equals("unknown") && last.optString("code").equals("DEVICE_-704083036"), "unresolved timeout is unknown and never overwritten by generic convergence error");
        check(!last.optString("message").contains("已接收") && !last.optString("message").contains("拒绝"), "timeout never claims acceptance or definite rejection");
        check(menuResult(bridge, unresolved).equals("Duun") && cloud.writes == writes + 1 && cloud.reads == reads + 6, "local result queries do not retry timed-out control or cloud reads");

        cloud.writeCode = 0; cloud.applyThenDisconnect = true; cloud.afterWriteReads = Arrays.<Object>asList(false, false);
        writes = cloud.writes;
        JSONObject disconnected = Json.copy(base).put("requestId", MiCloud.randomId()); bridge.handle(disconnected);
        check(menuResult(bridge, disconnected).equals("D11n") && cloud.writes == writes + 1, "lost response plus stale cache converges without resending");
        last = bridge.handle(Json.obj("op", "status")).getJSONObject("last");
        check(last.optString("state").equals("confirmed") && last.optString("code").equals("UNKNOWN"), "network ambiguity remains diagnostic even after state confirmation");

        cloud.applyThenDisconnect = false; cloud.writeCode = -704030023;
        writes = cloud.writes; reads = cloud.reads;
        JSONObject rejected = Json.copy(base).put("requestId", MiCloud.randomId()); bridge.handle(rejected);
        check(menuResult(bridge, rejected).equals("D11n"), "definite rejection still refreshes actual state once");
        check(cloud.writes == writes + 1 && cloud.reads == reads + 2, "definite rejection does not enter timeout confirmation loop");
        last = bridge.handle(Json.obj("op", "status")).getJSONObject("last");
        check(last.optString("state").equals("error") && last.optString("code").equals("DEVICE_-704030023"), "definite rejection retains error semantics");
        cloud.writeCode = 0;

        cloud.actionCode = -704083036; int actions = cloud.actions;
        JSONObject actionResult = await(bridge, Json.obj("op", "run", "action",
                Json.obj("kind", "action", "home", "1", "did", "2", "siid", 2, "aiid", 1, "values", new JSONArray())));
        check(!actionResult.optBoolean("ok") && actionResult.optString("state").equals("unknown") && cloud.actions == actions + 1, "non-boolean action timeout is explicit and never resent");
        cloud.actionCode = 0;
        String log = new String(Files.readAllBytes(store.root.resolve("debug.log")), java.nio.charset.StandardCharsets.UTF_8);
        check(log.contains("当前=0") && log.contains("siid=2，piid=1，操作=toggle，目标=1") && log.contains("结果=设备操作超时"), "diagnostics identify pre-state, intended switch value and timeout phase");
        check(!log.contains("DO_NOT_PRINT") && !log.contains("SYNTHETIC_TEST_ONLY") && !log.contains(alias.getString("id")), "timeout diagnostics remain redacted");
    }
    static void deviceAvailability(MijiaBridge bridge, FakeCloud cloud, JSONArray visible) throws Exception {
        JSONObject other = await(bridge, Json.obj("op", "binding-save", "name", "fixture other device", "action",
                Json.obj("kind", "toggle", "home", "1", "did", "4", "siid", 2, "piid", 1)));
        JSONObject deviceAction = Json.obj("kind", "action", "home", "1", "did", "2", "siid", 2, "aiid", 1, "values", new JSONArray());
        JSONObject action = await(bridge, Json.obj("op", "binding-save", "name", "fixture device action", "action", deviceAction));
        JSONArray ids = new JSONArray().put(visible.getString(0)).put(visible.getString(0)).put(other.getString("id"))
                .put(action.getString("id")).put(visible.getString(2));
        String token = MiCloud.randomId() + MiCloud.randomId();
        cloud.actual = cloud.otherActual = true; cloud.availabilityQueries.clear(); cloud.propertyQueries.clear();
        check(menu(bridge, token, ids).equals("D111an"), "menu distinguishes online switches, device actions and scenes");
        check(cloud.availabilityQueries.equals(Arrays.asList("2", "4")), "opening batches unique visible devices once");
        check(cloud.propertyQueries.equals(Arrays.asList("2", "4")), "opening reads each unique online property once");

        cloud.availability.put("2", false); cloud.availabilityQueries.clear(); cloud.propertyQueries.clear();
        check(menu(bridge, MiCloud.randomId() + MiCloud.randomId(), ids).equals("Doo1on"), "offline dominates a retained true property and a stale online catalog");
        check(cloud.propertyQueries.equals(Collections.singletonList("4")), "offline device properties are never read as current state");
        int writes = cloud.writes, actions = cloud.actions, reads = cloud.reads;
        for (JSONObject descriptor : Arrays.asList(
                Json.obj("kind", "toggle", "home", "1", "did", "2", "siid", 2, "piid", 1),
                Json.obj("kind", "set", "value", false, "home", "1", "did", "2", "siid", 2, "piid", 1), deviceAction)) {
            JSONObject result = await(bridge, Json.obj("op", "run", "action", descriptor));
            check(!result.optBoolean("ok") && result.optString("code").equals("OFFLINE"), "backend rejects offline control even outside menu");
        }
        check(cloud.writes == writes && cloud.actions == actions && cloud.reads == reads, "offline sends no property control, action or toggle pre-read");
        JSONObject base = Json.obj("op", "menu-control", "session", token, "ids", ids, "id", visible.getString(0));
        JSONObject offline = Json.copy(base).put("requestId", MiCloud.randomId());
        cloud.availabilityQueries.clear(); cloud.propertyQueries.clear(); bridge.handle(offline);
        check(menuResult(bridge, offline).equals("Doo-on"), "disconnect since menu opening refreshes only selected device and its aliases");
        check(cloud.writes == writes && cloud.propertyQueries.isEmpty() && !cloud.availabilityQueries.contains("4"), "backend guard prevents sending after device goes offline");

        cloud.availability.put("2", true); cloud.otherActual = false;
        cloud.availabilityQueries.clear(); cloud.propertyQueries.clear();
        JSONObject off = Json.copy(base).put("requestId", MiCloud.randomId()); bridge.handle(off);
        check(menuResult(bridge, off).equals("D00-an"), "device control returns a delta and retains unrelated device state");
        check(cloud.availabilityQueries.equals(Arrays.asList("2", "2")), "only target availability checked before and after operation");
        check(cloud.propertyQueries.equals(Arrays.asList("2", "2")), "only target property is read before and after toggle");
        int availabilityReads = cloud.availabilityQueries.size(); reads = cloud.reads;
        for (int i = 0; i < 3; i++) menuResult(bridge, off);
        check(cloud.availabilityQueries.size() == availabilityReads && cloud.reads == reads, "idle polling never rereads availability or properties");
        check(menu(bridge, MiCloud.randomId() + MiCloud.randomId(), ids).equals("D000an"), "reopening recovers online controls and refreshes all visible devices");

        writes = cloud.writes;
        cloud.availabilityFailure = true; cloud.propertyQueries.clear();
        JSONObject failed = Json.copy(base).put("requestId", MiCloud.randomId()); bridge.handle(failed);
        check(menuResult(bridge, failed).equals("D??-?n") && cloud.writes == writes && cloud.propertyQueries.isEmpty(), "network failure stays unknown, preserves unrelated devices and never sends control");
        cloud.availabilityFailure = false; cloud.omitAvailability = true;
        JSONObject missing = Json.copy(base).put("requestId", MiCloud.randomId()); bridge.handle(missing);
        check(menuResult(bridge, missing).equals("D??-?n") && cloud.writes == writes, "missing device info cannot fabricate online or offline state");
        cloud.omitAvailability = false; cloud.availability.clear();
    }
    static void onlineProtocol(PrivateStore store) throws Exception {
        final List<Integer> batchSizes = new ArrayList<>();
        MiCloud cloud = new MiCloud(store) {
            @Override Object call(MiHttp http, JSONObject auth, String uri, JSONObject data) throws Exception {
                check(uri.equals("/v2/home/device_list_page"), "availability uses device info endpoint");
                JSONArray dids = data.getJSONArray("dids"), list = new JSONArray(); batchSizes.add(dids.length());
                for (int i = 0; i < dids.length(); i++) {
                    String did = dids.getString(i);
                    JSONObject item = Json.obj("did", did);
                    if (did.equals("1")) item.put("isOnline", "false");
                    else if (!did.equals("2")) item.put("isOnline", !did.equals("0"));
                    list.put(item);
                }
                list.put(Json.obj("did", "unrequested", "isOnline", true));
                return Json.obj("list", list);
            }
        };
        List<String> dids = new ArrayList<>(); for (int i = 0; i < 151; i++) dids.add(String.valueOf(i)); dids.add("0");
        try (MiHttp http = new MiHttp(1000)) {
            Map<String, Boolean> states = cloud.online(http, new JSONObject(), dids);
            check(batchSizes.equals(Arrays.asList(150, 1)), "availability deduplicates and bounds batches");
            check(Boolean.FALSE.equals(states.get("0")) && Boolean.TRUE.equals(states.get("150")), "explicit online/offline values preserved");
            check(!states.containsKey("1") && !states.containsKey("2") && !states.containsKey("unrequested"), "invalid, missing and unsolicited flags excluded");
        }
        MiCloud stuck = new MiCloud(store) {
            @Override Object call(MiHttp http, JSONObject auth, String uri, JSONObject data) throws Exception {
                return Json.obj("list", new JSONArray(), "has_more", true, "next_start_did", "3");
            }
        };
        try (MiHttp http = new MiHttp(1000)) { rejects(() -> stuck.online(http, new JSONObject(), Collections.singleton("2")), "PROTOCOL"); }
    }
    public static void main(String[] args) throws Exception {
        JSONObject roomHome=Json.obj("roomlist",new JSONArray()
                .put(Json.obj("name","客厅","dids",new JSONArray().put("2").put(3)))
                .put(Json.obj("name"," ","dids",new JSONArray().put("4"))));
        Map<String,String> roomNames=MiCloud.roomNames(roomHome);
        check("客厅".equals(roomNames.get("2")) && "客厅".equals(roomNames.get("3")), "room names for string and numeric did");
        check(!roomNames.containsKey("4") && MiCloud.roomNames(new JSONObject()).isEmpty(), "missing room info stays ungrouped");
        if (args.length > 0 && args[0].equals("live")) {
            Path path=Files.createTempDirectory("mijia-live-");
            try (MiHttp http=new MiHttp(45000)) {
                JSONObject qr=new MiCloud(new PrivateStore(path)).prepareLogin(http,MiCloud.identity());
                check(qr.getString("image").startsWith("data:image/"),"live QR");
                JSONObject spec=new MiSpec(new PrivateStore(path)).get(http,"yeelink.light.lamp4");
                check(MiSpec.find(spec,2,1,false).getString("name").equals("开关"),"live spec Chinese name");
                System.out.println("真实服务匿名扫码握手、二维码读取和公开设备规格解析通过；未登录账号，未保存凭据。");
            } finally { Files.deleteIfExists(path.resolve("spec-yeelink.light.lamp4.json")); Files.deleteIfExists(path); }
            return;
        }
        JSONObject prefixed = MiCloud.loginResponse("&&&START&&&{\"code\":0}"); check(prefixed.getInt("code") == 0, "login prefix");
        // RC4-drop1024 对照 RFC 6229 的 40-bit key、offset 1024 测试向量。
        byte[] stream = MiCrypto.rc4("AQIDBAU=", new byte[16]);
        StringBuilder hex = new StringBuilder(); for (byte b : stream) hex.append(String.format("%02x", b & 255));
        check(hex.toString().equals("30abbcc7c20b01609f23ee2d5f6bb7df"), "RFC6229 vector");
        String signed = MiCrypto.signedNonce("AQIDBAUGBwg=", "AAECAwQFBgcICQoL");
        check(signed.equals("DFXr8deWFOsLCnohPorzia8O35FOiJYoKlRYl0a8apo="), "signed nonce reference");
        Map<String,String> params=MiCrypto.params("/miotspec/prop/get","{\"params\":[]}","AQIDBAUGBwg=","AAECAwQFBgcICQoL");
        check(new String(MiCrypto.rc4(signed,MiCrypto.decode(params.get("data"))),java.nio.charset.StandardCharsets.UTF_8).equals("{\"params\":[]}"),"encrypted data roundtrip");
        check(params.keySet().toString().equals("[data, rc4_hash__, signature, ssecurity, _nonce]"),"signature field order");
        rejects(()->MiHttp.allowed("https://account.xiaomi.com.attacker.invalid/"),"PROTOCOL");
        rejects(()->MiHttp.allowed("http://account.xiaomi.com/"),"PROTOCOL");
        rejects(()->MiHttp.allowed("https://account.xiaomi.com:8080/"),"PROTOCOL");
        cancellableReadWait();
        check(MiHttp.query("https://account.xiaomi.com/a?x=a%2Bb&z=1").get("x").equals("a+b"),"query encoding");
        JSONObject property=Json.obj("siid",2,"piid",1,"format","bool","read",true,"write",true,"values",new JSONArray());
        MiSpec.value(property,false); rejects(()->MiSpec.value(property,0),"VALUE");
        JSONObject level=Json.obj("siid",2,"piid",2,"format","uint8","read",true,"write",true,"range",new JSONArray("[1,99,2]"),"values",new JSONArray());
        MiSpec.value(level,51); for(int invalid:new int[]{0,50,100,257}) rejects(()->MiSpec.value(level,invalid),"VALUE");
        String page="<script data-page=\"app\" type=\"application/json\">{\"props\":{\"tree\":{\"services\":[{\"iid\":2,\"description\":\"Light\",\"properties\":[{\"iid\":1,\"type\":\"on\",\"description\":\"Switch\",\"format\":\"bool\",\"access\":[\"read\",\"write\"]}],\"actions\":[{\"iid\":1,\"description\":\"Toggle\",\"in\":[]}]}]}}}</script>";
        check(MiSpec.parse(page,"fixture").getJSONArray("properties").getJSONObject(0).getString("name").equals("Switch"),"missing translation allowed");
        rejects(()->MiSpec.parse("<html>not spec</html>","fixture"),"SPEC");
        Path folder=Files.createTempDirectory("sidekey-mijia-test-");
        try {
            PrivateStore store=new PrivateStore(folder);
            onlineProtocol(store);
            JSONObject spec=Json.obj("schema",1,"time",System.currentTimeMillis(),"properties",new JSONArray().put(property).put(level),
                    "actions",new JSONArray().put(Json.obj("siid",2,"aiid",1,"in",new JSONArray())));
            store.write("spec-test.light.fixture.json",spec);
            store.write("auth.json",Json.obj("userId","10001","serviceToken","SYNTHETIC_TEST_ONLY"));
            FakeCloud cloud=new FakeCloud(store);
            try(MijiaBridge bridge=new MijiaBridge(store)) {
                JSONObject status=bridge.handle(Json.obj("op","status")); check(!status.toString().contains("SYNTHETIC_TEST_ONLY"),"no credentials in status");
            }
            try(MijiaBridge bridge=new MijiaBridge(store,cloud)) {
                JSONObject action=Json.obj("kind","toggle","home","1","did","2","siid",2,"piid",1);
                JSONObject result=await(bridge,Json.obj("op","run","action",action)); check(result.optString("state").equals("confirmed"),"toggle readback"); check(cloud.writes==1 && Boolean.TRUE.equals(cloud.actual),"toggle once");
                cloud.disconnect=true;result=await(bridge,Json.obj("op","run","action",action));check(result.optString("code").equals("UNKNOWN"),"ambiguous timeout");check(cloud.writes==2,"no retry on write timeout");cloud.disconnect=false;
                cloud.writeCode=1;result=await(bridge,Json.obj("op","run","action",action));check(result.optString("state").equals("accepted"),"accepted != confirmed");cloud.writeCode=0;
                cloud.offline=true;int before=cloud.writes;result=await(bridge,Json.obj("op","run","action",action));check(!result.optBoolean("ok") && cloud.writes==before,"offline blocks toggle");cloud.offline=false;
                JSONObject binding=await(bridge,Json.obj("op","binding-save","action",action,"name","fixture switch"));check(binding.optBoolean("ok"),"binding persisted");
                JSONObject sceneBinding=await(bridge,Json.obj("op","binding-save","action",Json.obj("kind","scene","home","1","scene","3"),"name","fixture scene"));
                String tokenA=String.join("",Collections.nCopies(64,"a")), tokenB=String.join("",Collections.nCopies(64,"b")), tokenC=String.join("",Collections.nCopies(64,"c"));
                JSONArray visible=new JSONArray().put(binding.getString("id")).put(binding.getString("id")).put(sceneBinding.getString("id"));
                int readsBefore=cloud.reads;
                check(menu(bridge,tokenA,visible).equals("D11n"),"menu shows live on and scene has no switch state");
                check(cloud.reads==readsBefore+1,"duplicate visible switches batched into one read");
                cloud.actual=false;
                check(menu(bridge,tokenA,visible).equals("D11n") && cloud.reads==readsBefore+1,"same menu session never rereads cloud");
                check(menu(bridge,tokenB,visible).equals("D00n") && cloud.reads==readsBefore+2,"new menu session fetches current off state once");
                cloud.offline=true;
                check(menu(bridge,tokenC,visible).equals("Doon"),"offline switch is distinct from power off or unknown");cloud.offline=false;
                rejects(()->bridge.handle(Json.obj("op","menu-states","session",tokenA,"ids",new JSONArray().put(sceneBinding.getString("id")))),"SESSION");
                result=await(bridge,Json.obj("op","trigger","id",binding.getString("id")));check(result.optBoolean("ok"),"binding executes");
                cloud.account="other";result=await(bridge,Json.obj("op","trigger","id",binding.getString("id")));check(result.optString("code").equals("BINDING"),"account binding isolation");cloud.account="10001";
                JSONObject request=Json.obj("op","run","action",action,"requestId","abcdabcdabcdabcdabcdabcdabcdabcd");
                String first=bridge.handle(request).getString("job");check(first.equals(bridge.handle(request).getString("job")),"dedupe request id");
                result=await(bridge,Json.obj("op","run","action",Json.obj("kind","scene","home","1","scene","3")));check(result.optString("state").equals("accepted") && cloud.scenes==1,"scene accepted");
                menuControls(bridge, cloud, store, tokenA, visible);
                delayedMenuState(bridge, cloud, store, tokenA, visible);
                uncertainMenuState(bridge, cloud, store, tokenA, visible);
                deviceAvailability(bridge, cloud, visible);
                result=await(bridge,Json.obj("op","logout"));check(result.optBoolean("ok") && !Files.exists(folder.resolve("auth.json")) && !Files.exists(folder.resolve("bindings.json")),"logout erases private auth");
            }
            JSONObject redacted=Failure.json(new java.io.IOException("token=SECRET"));check(!redacted.toString().contains("SECRET"),"exception redaction");
            cancelledLogin(store);
            store.write("last.json",Json.obj("state","pending"));
            try(MijiaBridge bridge=new MijiaBridge(store)) {check(bridge.handle(Json.obj("op","status")).getJSONObject("last").getString("state").equals("unknown"),"restart uncertain operation");}
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(folder)) {for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);}
        }
        System.out.println("米家主机测试通过："+checks+" 项断言（合成设备，无真实账号）。");
    }
}
