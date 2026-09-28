package cn.sidekey.menu;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 只展示已校验的云端文本；不推断设备在线状态，不创建控制入口。 */
final class ReadingContent extends LinearLayout {
    private final int muted;
    private String content = "";
    private int contentColor;

    ReadingContent(Context context, int muted) {
        super(context); this.muted = muted; setOrientation(VERTICAL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void update(String value, int color) {
        if (content.equals(value) && contentColor == color) return;
        content = value; contentColor = color; removeAllViews();
        String[] lines = value.split("\n", -1);
        for (String line : lines) {
            int split = line.lastIndexOf('：');
            String label = split >= 0 ? line.substring(0, split) : "";
            String reading = split >= 0 ? line.substring(split + 1) : line;
            reading = reading.replace(" °C", "°C").replace(" °F", "°F").replace(" %", "%");
            SpannableStringBuilder text = new SpannableStringBuilder();
            if (!label.isEmpty()) {
                text.append(label).append(' ');
                text.setSpan(new ForegroundColorSpan(muted), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new RelativeSizeSpan(12f / 14f), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            text.append(reading);
            TextView row = new TextView(getContext()); row.setText(text); row.setTextSize(14); row.setTextColor(color);
            row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); row.setFontFeatureSettings("tnum");
            row.setIncludeFontPadding(false); row.setPadding(0, dp(4), 0, dp(2));
            // 名称和数值同行；长自定义文案自然换行，不截掉读数或单位。
            addView(row, new LayoutParams(-1, -2));
        }
    }

    String value() { return content; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
