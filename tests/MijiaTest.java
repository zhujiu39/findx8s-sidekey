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
        Object actual = false; int writes, actions, scenes, reads; int writeCode; boolean disconnect, offline, applyThenDisconnect, offlineAfterWrite, sceneTurnsOn; String account = "10001";
        java.util.concurrent.CountDownLatch writeEntered, releaseWrite;
        FakeCloud(PrivateStore store) { super(store); }
        @Override JSONObject authenticated(MiHttp h) throws Exception { return Json.obj("userId", account); }
        @Override JSONArray homes(MiHttp h, JSONObject a) throws Exception { return new JSONArray().put(home(h, a, "1")); }
        @Override JSONObject home(MiHttp h, JSONObject a, String id) throws Exception { return Json.obj("id", "1", "uid", 10001, "name", "fixture"); }
        @Override JSONObject catalog(MiHttp h, JSONObject a, String id) throws Exception {
            return Json.obj("devices", new JSONArray().put(Json.obj("did", "2", "home", "1", "model", "test.light.fixture", "online", true)),
                    "scenes", new JSONArray().put(Json.obj("id", "3", "name", "fixture", "home", "1")), "owner", 10001, "sceneError", "");
        }
        @Override Object call(MiHttp h, JSONObject a, String uri, JSONObject data) throws Exception {
            if (uri.endsWith("NewRunScene")) { scenes++; if (sceneTurnsOn) actual = true; return true; }
            if (uri.equals("/miotspec/action")) { actions++; return Json.obj("code", 0); }
            JSONArray params = data.getJSONArray("params"), result = new JSONArray();
            for (int i = 0; i < params.length(); i++) {
                JSONObject param = params.getJSONObject(i), item = Json.copy(param);
                if (uri.endsWith("/set")) {
                    if (writeEntered != null) { writeEntered.countDown(); if (!releaseWrite.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("blocked write timeout"); }
                    writes++; if (disconnect) throw new java.net.SocketTimeoutException("synthetic credential=DO_NOT_PRINT");
                    if (writeCode == 0) actual = param.get("value"); item.put("code", writeCode);
                    if (applyThenDisconnect) throw new java.net.SocketTimeoutException("synthetic credential=DO_NOT_PRINT");
                    if (offlineAfterWrite) offline = true;
                } else { reads++; item.put("code", offline ? -704042011 : 0).put("value", actual); }
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
        check(menuResult(bridge, offline).equals("D??n"), "only real read failure produces unknown state");
        cloud.offlineAfterWrite = cloud.offline = false;
        cloud.actual = false; cloud.writeCode = 1;
        JSONObject accepted = Json.copy(control).put("requestId", MiCloud.randomId()); bridge.handle(accepted);
        check(menuResult(bridge, accepted).equals("D00n"), "gateway acceptance never fakes desired on state");
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
                check(menu(bridge,tokenC,visible).equals("D??n"),"offline switch is unknown, never off");cloud.offline=false;
                rejects(()->bridge.handle(Json.obj("op","menu-states","session",tokenA,"ids",new JSONArray().put(sceneBinding.getString("id")))),"SESSION");
                result=await(bridge,Json.obj("op","trigger","id",binding.getString("id")));check(result.optBoolean("ok"),"binding executes");
                cloud.account="other";result=await(bridge,Json.obj("op","trigger","id",binding.getString("id")));check(result.optString("code").equals("BINDING"),"account binding isolation");cloud.account="10001";
                JSONObject request=Json.obj("op","run","action",action,"requestId","abcdabcdabcdabcdabcdabcdabcdabcd");
                String first=bridge.handle(request).getString("job");check(first.equals(bridge.handle(request).getString("job")),"dedupe request id");
                result=await(bridge,Json.obj("op","run","action",Json.obj("kind","scene","home","1","scene","3")));check(result.optString("state").equals("accepted") && cloud.scenes==1,"scene accepted");
                menuControls(bridge, cloud, store, tokenA, visible);
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
