package cn.sidekey.menu;

/** 两列应用的实际像素位置；间距不再被等分列宽和单元格固定留白稀释。 */
public final class AppGridGeometry {
    public final int cellWidth, iconSize, left, right, columnGap, rowGap;

    public AppGridGeometry(int availableWidth, float density, int gapDp) {
        int width = Math.max(0, availableWidth);
        rowGap = Math.max(0, Math.round(Math.max(0, Math.min(32, gapDp)) * density));
        // 120 dp 面板扣除两侧内边距后仍可容纳两个 32 dp 图标与最大间距。
        columnGap = Math.min(rowGap, Math.max(0, width - 2 * Math.round(32 * density)));
        cellWidth = Math.max(0, Math.min(Math.round(64 * density), (width - columnGap) / 2));
        iconSize = Math.min(Math.round(44 * density), cellWidth);
        left = Math.max(0, (width - 2 * cellWidth - columnGap) / 2);
        right = left + cellWidth + columnGap;
    }

    public int rowHeight(int contentHeight, boolean hasNext) {
        return Math.max(0, contentHeight) + (hasNext ? rowGap : 0);
    }
}
