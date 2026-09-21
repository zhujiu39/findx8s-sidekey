import cn.sidekey.menu.AppGridGeometry;

public final class AppGridGeometryTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] arguments) {
        for (float density : new float[]{1, 1.5f, 2, 2.75f, 3, 3.5f, 4}) {
            for (int widthDp = 120; widthDp <= 360; widthDp++) for (int gapDp : new int[]{0, 1, 12, 24, 32}) {
                int available = Math.round(widthDp * density) - 2 * Math.round(12 * density);
                AppGridGeometry g = new AppGridGeometry(available, density, gapDp);
                check(g.left >= 0 && g.right + g.cellWidth <= available, "两列均在面板内");
                check(g.iconSize > 0 && g.iconSize <= g.cellWidth, "窄栏图标不越界");
                check(g.right - g.left - g.cellWidth == g.columnGap, "排布间隙等于计算间距");
                check(Math.abs(g.columnGap - Math.round(gapDp * density)) <= 2, "合法面板宽度按 dp 应用间距");
                check(g.rowHeight(79, true) - g.rowHeight(79, false) == Math.round(gapDp * density), "实际行高包含完整间距");
            }
        }
        AppGridGeometry compact = new AppGridGeometry(172, 1, 0);
        AppGridGeometry loose = new AppGridGeometry(172, 1, 32);
        check(compact.cellWidth == loose.cellWidth, "间距不能被等分列宽吸收");
        check((loose.right - loose.left) - (compact.right - compact.left) == 32, "两列中心距离完整增加 32 px");
        check(loose.rowHeight(79, true) - compact.rowHeight(79, true) == 32, "两行距离完整增加 32 px");
        check(loose.rowHeight(79, false) == 79, "末行不留下多余空白");
        AppGridGeometry narrow = new AppGridGeometry(96, 1, 32);
        check(narrow.cellWidth == 32 && narrow.iconSize == 32 && narrow.columnGap == 32, "120 dp 最窄栏容纳最大间距");
        for (int available : new int[]{0, 1, 31, 63}) {
            AppGridGeometry g = new AppGridGeometry(available, 1, 32);
            check(g.cellWidth >= 0 && g.iconSize >= 0 && g.right + g.cellWidth <= available, "瞬态小窗口无负尺寸和越界");
        }
        System.out.println("PASS: actual two-column coordinates and row heights at gap 0/12/32, 120-360 dp widths, narrow icons and densities");
    }
}
