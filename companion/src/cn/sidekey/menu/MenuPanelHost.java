package cn.sidekey.menu;

import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** 每轮测量都按最新窗口约束内容，不把上一方向的测量高度固定到新窗口。 */
final class MenuPanelHost extends FrameLayout {
    interface Listener {
        void compactChanged(boolean compact);
        void positioned();
    }
    private final View shade;
    private final LinearLayout panel;
    private final Listener listener;
    private final boolean right;
    private final int position;
    private Boolean compact;
    private MenuGeometry geometry;
    private Insets safeInsets = Insets.NONE;

    MenuPanelHost(Context context, View shade, LinearLayout panel, boolean right, int position, Listener listener) {
        super(context);
        this.shade = shade; this.panel = panel; this.right = right; this.position = position; this.listener = listener;
        // 遮罩覆盖整个窗口；安全边距只约束面板，不能裁掉系统栏区域的背景。
        setClipToPadding(false);
        addView(shade); addView(panel);
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
                                    getResources().getDisplayMetrics().density, right);
        if (compact == null || compact != geometry.compact) {
            compact = geometry.compact; listener.compactChanged(compact);
        }
        shade.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        panel.measure(MeasureSpec.makeMeasureSpec(geometry.width, MeasureSpec.EXACTLY),
                      MeasureSpec.makeMeasureSpec(geometry.maxHeight, MeasureSpec.AT_MOST));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        shade.layout(0, 0, right - left, bottom - top);
        if (geometry == null) return;
        int y = geometry.top(panel.getMeasuredHeight(), position);
        panel.layout(geometry.left, y, geometry.left + panel.getMeasuredWidth(), y + panel.getMeasuredHeight());
        listener.positioned();
    }

    float offscreenTranslation() {
        return right ? getWidth() - panel.getLeft() : -panel.getRight();
    }
}
