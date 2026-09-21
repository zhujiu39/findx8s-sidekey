package cn.sidekey.menu;

import android.content.Context;
import android.graphics.Insets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** 每轮测量都按最新窗口约束内容，不把上一方向的测量高度固定到新窗口。 */
final class MenuPanelHost extends FrameLayout {
    interface Listener {
        void compactChanged(boolean compact);
        void positioned();
    }
    private final LinearLayout panel;
    private final Listener listener;
    private final boolean right;
    private final int position, widthDp;
    private Boolean compact;
    private MenuGeometry geometry;
    private Insets safeInsets = Insets.NONE;

    MenuPanelHost(Context context, LinearLayout panel, boolean right, int position, int widthDp, Listener listener) {
        super(context);
        this.panel = panel; this.right = right; this.position = position; this.widthDp = widthDp; this.listener = listener;
        // 窗口其余区域保持透明，仅面板按系统栏、挖孔和当前方向避让。
        setClipToPadding(false);
        addView(panel);
    }

    void setSafeInsets(Insets insets) {
        Insets next = insets == null ? Insets.NONE : insets;
        if (safeInsets.equals(next)) return;
        safeInsets = next;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        geometry = new MenuGeometry(width, height, safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom,
                                    getResources().getDisplayMetrics().density, right, widthDp);
        if (compact == null || compact != geometry.compact) {
            compact = geometry.compact; listener.compactChanged(compact);
        }
        panel.measure(MeasureSpec.makeMeasureSpec(geometry.width, MeasureSpec.EXACTLY),
                      MeasureSpec.makeMeasureSpec(geometry.maxHeight, MeasureSpec.AT_MOST));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (geometry == null) return;
        int y = geometry.top(panel.getMeasuredHeight(), position);
        panel.layout(geometry.left, y, geometry.left + panel.getMeasuredWidth(), y + panel.getMeasuredHeight());
        listener.positioned();
    }

    float offscreenTranslation() {
        return right ? getWidth() - panel.getLeft() : -panel.getRight();
    }
}
