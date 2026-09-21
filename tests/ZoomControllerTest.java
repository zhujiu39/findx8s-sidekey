import cn.sidekey.ZoomController;
import cn.sidekey.ZoomWindowContract;

public final class ZoomControllerTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static ZoomController.State visible(String name, int user) {
        return new ZoomController.State(true, true, true, name, user);
    }
    private static final class Fake implements ZoomController.Backend {
        String scenario;
        long time;
        int starts;
        Fake(String scenario) { this.scenario = scenario; }
        public int currentUser() { return "switch_user".equals(scenario) && time >= 120 ? 11 : 10; }
        public boolean supported() { return !"unsupported".equals(scenario); }
        public int start() {
            starts++;
            if ("security".equals(scenario)) throw new SecurityException("Permission denied");
            return "rejected".equals(scenario) ? -1 : "aborted".equals(scenario) ? 102 : 0;
        }
        public ZoomController.State state() {
            if ("query_error".equals(scenario)) throw new IllegalStateException("state API unavailable");
            if ("null".equals(scenario)) return null;
            if ("wrong_pkg".equals(scenario)) return visible("com.other", 10);
            if ("wrong_user".equals(scenario)) return visible("com.demo", 11);
            if ("fullscreen".equals(scenario)) return new ZoomController.State(true, false, true, "com.demo", 10);
            if ("hidden".equals(scenario)) return new ZoomController.State(false, true, true, "com.demo", 10);
            if ("empty_bounds".equals(scenario)) return new ZoomController.State(true, true, false, "com.demo", 10);
            if ("transient".equals(scenario) && time > 0) return null;
            if ("delayed".equals(scenario) && time < 240) return null;
            return visible("com.demo", 10);
        }
        public long now() { return time; }
        public void pause(long duration) { check(duration > 0 && duration <= 120); time += duration; }
        public void log(String message) {}
    }
    public static final class Rect { public int left = 0, top = 0, right = 400, bottom = 800; }
    public static final class Info {
        public boolean windowShown = true;
        public int windowType = 1, zoomUserId = 10;
        public String zoomPkg = "com.demo";
        public Rect zoomRect = new Rect();
    }
    public static final class FakeIntent {}
    public static final class FakeBundle {}
    public static class Vendor {
        public static final int WINDOWING_MODE_ZOOM = 100, WINDOW_TYPE_ZOOM = 1;
        public static final String EXTRA_WINDOW_MODE = "extra_window_mode";
        static Vendor instance = new Vendor();
        final Info info = new Info();
        boolean denied;
        Object expectedIntent, expectedBundle;
        public static Vendor getInstance() { return instance; }
        public boolean isSupportZoomWindowMode() { return true; }
        public int startZoomWindow(FakeIntent intent, FakeBundle bundle, int user, String caller) {
            if (denied) throw new SecurityException("OEM rejection");
            check(intent == expectedIntent && bundle == expectedBundle && user == 10 && "android".equals(caller));
            return 0;
        }
        public Info getCurrentZoomWindowState() { return info; }
    }
    public static final class WrongMode extends Vendor { public static final int WINDOWING_MODE_ZOOM = 5; }

    public static void main(String[] arguments) throws Exception {
        for (String scenario : new String[]{"ok", "delayed"}) {
            Fake fake = new Fake(scenario); ZoomController.launch(fake, "com.demo", 10);
            check(fake.starts == 1 && fake.time >= 120 && fake.time < 4000);
        }
        for (String scenario : new String[]{"unsupported","query_error","security","rejected","aborted","null","wrong_pkg","wrong_user","fullscreen","hidden","empty_bounds","transient","switch_user"}) {
            Fake fake = new Fake(scenario);
            try { ZoomController.launch(fake, "com.demo", 10); throw new AssertionError("接受了失败状态：" + scenario); }
            catch (IllegalStateException | SecurityException expected) { check(expected.getMessage() != null); }
            check(fake.starts == ("unsupported".equals(scenario) || "query_error".equals(scenario) ? 0 : 1));
            check(fake.time <= 4000);
        }
        ZoomWindowContract api = new ZoomWindowContract(Vendor.class, FakeIntent.class, FakeBundle.class);
        check(api.mode == 100 && api.supported() && api.state().matches("com.demo",10));
        Vendor.instance.expectedIntent = new FakeIntent(); Vendor.instance.expectedBundle = new FakeBundle();
        check(api.start(Vendor.instance.expectedIntent, Vendor.instance.expectedBundle, 10, "android") == 0);
        Vendor.instance.info.windowType = 2; check(!api.state().matches("com.demo",10));
        Vendor.instance.info.windowType = 1; Vendor.instance.info.zoomRect.right = 0; check(!api.state().matches("com.demo",10));
        Vendor.instance.denied = true;
        try { api.start(new FakeIntent(), new FakeBundle(), 10, "android"); throw new AssertionError(); }
        catch (SecurityException expected) { check("OEM rejection".equals(expected.getMessage())); }
        try { new ZoomWindowContract(WrongMode.class, FakeIntent.class, FakeBundle.class); throw new AssertionError(); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("常量")); }
        try { new ZoomWindowContract(Vendor.class, String.class, FakeBundle.class); throw new AssertionError(); }
        catch (NoSuchMethodException expected) { check(expected.getMessage().contains("startZoomWindow")); }
        System.out.println("PASS: OPlus contract signatures, target/user/window state, delayed confirmation, permission/return errors, bounded waiting and one launch only");
    }
}
