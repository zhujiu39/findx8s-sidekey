package cn.sidekey.menu;

/** 使用当前窗口和安全边距计算侧边面板范围；像素计算可独立于 Android 测试。 */
public final class MenuGeometry {
    public final int left, width, maxHeight;
    public final boolean compact;
    private final int safeTop, safeHeight, gap;

    public MenuGeometry(int windowWidth, int windowHeight, int insetLeft, int insetTop,
                        int insetRight, int insetBottom, float density, boolean right) {
        int w = Math.max(1, windowWidth), h = Math.max(1, windowHeight);
        int safeLeft = clamp(insetLeft, 0, w - 1);
        safeTop = clamp(insetTop, 0, h - 1);
        int safeWidth = Math.max(1, w - safeLeft - Math.max(0, insetRight));
        safeHeight = Math.max(1, h - safeTop - Math.max(0, insetBottom));
        int margin = Math.min(px(10, density), (safeWidth - 1) / 2);
        gap = Math.min(px(12, density), (safeHeight - 1) / 2);
        width = Math.min(px(360, density), safeWidth - margin * 2);
        maxHeight = safeHeight - gap * 2;
        left = right ? safeLeft + safeWidth - margin - width : safeLeft + margin;
        compact = safeWidth > safeHeight || safeHeight < px(440, density);
    }

    public int top(int panelHeight, int position) {
        int height = clamp(panelHeight, 0, maxHeight);
        int desired = safeTop + Math.round(safeHeight * (clamp(position, 10, 90) / 100f)) - height / 2;
        return clamp(desired, safeTop + gap, safeTop + safeHeight - gap - height);
    }

    private static int px(int dp, float density) { return Math.max(1, Math.round(dp * density)); }
    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
