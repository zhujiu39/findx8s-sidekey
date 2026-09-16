package cn.sidekey;

/** 系统回调是状态来源；同一次切换只提交一次请求，超时不重复切换。 */
public final class TorchController {
    public interface Backend {
        void set(boolean enabled, int strength) throws Exception;
    }
    private final Backend backend;
    private final int maximum;
    private boolean known, available, enabled;
    private int strength;

    public TorchController(Backend backend, int maximum) {
        if (backend == null || maximum < 1) throw new IllegalArgumentException("无效的手电筒参数");
        this.backend = backend;
        this.maximum = maximum;
    }

    public synchronized void onState(boolean available, boolean enabled) {
        this.known = true;
        this.available = available;
        this.enabled = enabled;
        if (!enabled) strength = 0;
        notifyAll();
    }

    public synchronized void onStrength(int value) { strength = value; }

    public synchronized Snapshot snapshot() {
        return new Snapshot(known, available, enabled, strength, maximum);
    }

    public void toggle(long timeoutMs) throws Exception {
        if (timeoutMs <= 0) throw new IllegalArgumentException("超时必须大于零");
        boolean target;
        synchronized (this) {
            long deadline = System.nanoTime() + timeoutMs * 1000000L;
            while (!known) awaitUntil(deadline);
            if (!available) throw new IllegalStateException("手电筒不可用：相机占用或系统限制");
            target = !enabled;
        }
        backend.set(target, maximum);
        synchronized (this) {
            long deadline = System.nanoTime() + timeoutMs * 1000000L;
            while (available && enabled != target) awaitUntil(deadline);
            if (!available) throw new IllegalStateException("切换期间手电筒变为不可用");
        }
    }

    private void awaitUntil(long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IllegalStateException("未收到手电筒状态确认；请查看实际灯光状态");
        wait(Math.max(1L, remaining / 1000000L));
    }

    public static final class Snapshot {
        public final boolean known, available, enabled;
        public final int strength, maximum;
        Snapshot(boolean known, boolean available, boolean enabled, int strength, int maximum) {
            this.known = known; this.available = available; this.enabled = enabled;
            this.strength = strength; this.maximum = maximum;
        }
    }
}
