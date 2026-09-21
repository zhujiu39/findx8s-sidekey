import cn.sidekey.menu.MenuGeometry;

public final class MenuGeometryTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void within(int width, int height, int left, int top, int right, int bottom, float density) {
        for (boolean edge : new boolean[]{false, true}) {
            MenuGeometry bounds = new MenuGeometry(width, height, left, top, right, bottom, density, edge);
            check(bounds.width > 0 && bounds.width <= Math.round(360 * density), "面板宽度上限");
            check(bounds.left >= left && bounds.left + bounds.width <= width - right, "左右安全边界");
            check(bounds.maxHeight > 0 && bounds.maxHeight <= height - top - bottom, "可用高度");
            for (int position : new int[]{10, 35, 50, 90}) {
                for (int panelHeight : new int[]{1, Math.min(Math.round(316 * density), bounds.maxHeight), bounds.maxHeight}) {
                    int y = bounds.top(panelHeight, position);
                    check(y >= top && y + panelHeight <= height - bottom, "上下安全边界");
                }
            }
        }
    }
    public static void main(String[] arguments) {
        // 截图分辨率仅作为窗口测试尺寸，不假设设备实际 density 和系统栏高度。
        for (float density : new float[]{1, 2, 2.75f, 3, 3.5f, 4}) {
            within(2640, 1216, 120, 80, 0, 72, density);
            within(2640, 1216, 0, 80, 120, 72, density);
            within(1216, 2640, 0, 120, 0, 72, density);
            within(844, 390, 44, 0, 44, 21, density);
            within(320, 240, 0, 24, 0, 24, density);
        }
        MenuGeometry portrait = new MenuGeometry(390, 844, 0, 44, 0, 34, 1, false);
        MenuGeometry landscape = new MenuGeometry(844, 390, 44, 0, 0, 21, 1, false);
        check(portrait.width == 360 && !portrait.compact, "竖屏标准布局");
        check(landscape.width == 360 && landscape.compact, "横屏限制宽度并启用紧凑布局");
        check(landscape.left == 54 && landscape.maxHeight == 345, "横屏避开侧面挖孔和底部手势区");
        check(landscape.top(316, 35) + 316 <= 369, "横屏常规字体下完整容纳内容");
        MenuGeometry tiny = new MenuGeometry(1, 1, 9, 9, 9, 9, 3, false);
        check(tiny.width == 1 && tiny.maxHeight == 1 && tiny.top(1, 90) == 0, "瞬态零空间不出现负尺寸");
        System.out.println("PASS: portrait / landscape / reverse landscape, screenshot-size windows, density, safe insets, position bounds and small-window fallback");
    }
}
