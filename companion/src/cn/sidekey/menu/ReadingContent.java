package cn.sidekey.menu;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 只展示已校验的云端文本；不推断设备在线状态，不创建控制入口。 */
final class ReadingContent extends LinearLayout {
    private final int muted;
    private final boolean twoColumns;
    private String content = "";
    private int contentColor;

    ReadingContent(Context context, int muted, int panelWidth) {
        super(context); this.muted = muted; twoColumns = panelWidth >= 240; setOrientation(VERTICAL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void update(String value, int color) {
        if (content.equals(value) && contentColor == color) return;
        content = value; contentColor = color; removeAllViews();
        String[] lines = value.split("\n", -1);
        LinearLayout row = null;
        int columns = twoColumns ? 2 : 1;
        for (int index = 0; index < lines.length; index++) {
            if (index % columns == 0) { row = new LinearLayout(getContext()); addView(row, new LayoutParams(-1, -2)); }
            LinearLayout cell = new LinearLayout(getContext()); cell.setOrientation(VERTICAL);
            cell.setPadding(0, dp(10), index % columns == 0 && columns == 2 ? dp(8) : 0, dp(2));
            String line = lines[index]; int split = line.lastIndexOf('：');
            String label = split >= 0 ? line.substring(0, split) : "";
            String reading = split >= 0 ? line.substring(split + 1) : line;
            TextView number = text(reading, 18, color); number.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            number.setFontFeatureSettings("tnum"); cell.addView(number, new LayoutParams(-1, -2));
            if (!label.isEmpty()) {
                TextView name = text(label, 10, muted); name.setPadding(0, dp(4), 0, 0);
                cell.addView(name, new LayoutParams(-1, -2));
            }
            if (row != null) row.addView(cell, new LayoutParams(0, -2, 1));
        }
    }

    String value() { return content; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView text = new TextView(getContext()); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setMaxLines(3); text.setEllipsize(TextUtils.TruncateAt.END); text.setIncludeFontPadding(false);
        return text;
    }
}
