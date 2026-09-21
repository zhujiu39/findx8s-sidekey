package cn.sidekey;

/** 小窗启动只提交一次；以目标用户和应用的可见小窗状态确认结果。 */
public final class ZoomController {
    public interface Backend {
        int currentUser() throws Exception;
        boolean supported() throws Exception;
        int start() throws Exception;
        State state() throws Exception;
        long now();
        void pause(long milliseconds) throws Exception;
        void log(String message);
    }

    public static final class State {
        public final boolean shown, normalZoom, hasBounds;
        public final String packageName;
        public final int user;
        public State(boolean shown, boolean normalZoom, boolean hasBounds, String packageName, int user) {
            this.shown = shown; this.normalZoom = normalZoom; this.hasBounds = hasBounds;
            this.packageName = packageName; this.user = user;
        }
        public boolean matches(String target, int targetUser) {
            return shown && normalZoom && hasBounds && target.equals(packageName) && user == targetUser;
        }
        @Override public String toString() {
            return "应用=" + packageName + "，用户=" + user + "，可见=" + shown + "，展开小窗=" + normalZoom + "，有效区域=" + hasBounds;
        }
    }

    public static void launch(Backend backend, String packageName, int user) throws Exception {
        if (backend == null || packageName == null || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") || user < 0)
            throw new IllegalArgumentException("小窗参数无效");
        if (backend.currentUser() != user) throw new IllegalStateException("Android 用户已切换，取消小窗启动");
        if (!backend.supported()) throw new IllegalStateException("系统报告不支持 OPlus 小窗");
        // 先验证状态查询可用，避免提交后才发现当前 ROM 接口不兼容。
        backend.state();
        if (backend.currentUser() != user) throw new IllegalStateException("Android 用户已切换，取消小窗启动");
        int result = backend.start();
        backend.log("OPlus 小窗启动返回码：" + result);
        if (result < 0 || result >= 100) throw new IllegalStateException("系统拒绝小窗请求，返回码 " + result);
        long deadline = backend.now() + 4000;
        int stable = 0;
        State last = null;
        while (backend.now() < deadline) {
            if (backend.currentUser() != user) throw new IllegalStateException("确认小窗时 Android 用户发生切换");
            last = backend.state();
            stable = last != null && last.matches(packageName, user) ? stable + 1 : 0;
            // 跨两个状态采样确认，不能将一次启动回执或瞬时状态当作已打开。
            if (stable >= 2) { backend.log("小窗状态已确认：" + last); return; }
            backend.pause(Math.min(120, Math.max(1, deadline - backend.now())));
        }
        throw new IllegalStateException("未确认目标应用进入小窗；最后状态：" + (last == null ? "不可读" : last));
    }
}
