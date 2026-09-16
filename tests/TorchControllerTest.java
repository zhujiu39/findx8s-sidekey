import cn.sidekey.TorchController;

public final class TorchControllerTest {
    private static void check(boolean value, String text) {
        if (!value) throw new AssertionError(text);
    }
    private static void fails(RunnableTask task, String text) throws Exception {
        try { task.run(); } catch (IllegalStateException e) { return; }
        throw new AssertionError(text);
    }
    interface RunnableTask { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        final TorchController[] ref = new TorchController[1];
        final int[] calls = {0}, level = {0};
        ref[0] = new TorchController((enabled, maximum) -> {
            calls[0]++; level[0] = maximum;
            ref[0].onState(true, enabled);
            ref[0].onStrength(enabled ? maximum : 0);
        }, 5);
        TorchController c = ref[0];
        c.onState(true, false); c.toggle(100);
        check(c.snapshot().enabled && level[0] == 5, "开启使用系统最高档");
        c.toggle(100); check(!c.snapshot().enabled, "再次操作关闭");
        c.onState(true, true); c.toggle(100);
        check(!c.snapshot().enabled, "控制中心开灯后，动作应关灯");
        c.onState(true, false); c.toggle(100);
        check(c.snapshot().enabled, "控制中心关灯后，动作应开灯");
        c.onState(false, false); int previous = calls[0];
        fails(() -> c.toggle(100), "不可用时必须失败");
        check(calls[0] == previous, "相机占用时不发送请求");
        TorchController timeout = new TorchController((enabled, maximum) -> calls[0]++, 1);
        previous = calls[0];
        fails(() -> timeout.toggle(20), "没有初始状态必须超时");
        check(calls[0] == previous, "未知状态时不能猜测开关");
        timeout.onState(true, false);
        fails(() -> timeout.toggle(20), "API 未返回状态必须失败");
        check(calls[0] == previous + 1, "切换请求不自动重试");
        TorchController broken = new TorchController((enabled, maximum) -> {
            throw new IllegalStateException("系统拒绝");
        }, 1);
        broken.onState(true, false);
        fails(() -> broken.toggle(20), "系统错误应传给调用方");
        check(!broken.snapshot().enabled, "失败后不伪造开灯状态");
        final TorchController[] delayed = new TorchController[1];
        delayed[0] = new TorchController((enabled, maximum) -> {
            check(maximum == 1, "单档设备只使用默认档位");
            new Thread(() -> {
                try { Thread.sleep(15); } catch (InterruptedException e) { throw new AssertionError(e); }
                delayed[0].onState(true, enabled);
            }).start();
        }, 1);
        delayed[0].onState(true, false);
        delayed[0].toggle(500);
        check(delayed[0].snapshot().enabled, "异步系统回调应唤醒切换等待");
        System.out.println("通过：最高亮度、重复切换、外部同步、占用、初始超时、回调超时、不重试、API 失败。");
        System.out.println("通过：单档默认亮度、异步回调确认。");
    }
}
