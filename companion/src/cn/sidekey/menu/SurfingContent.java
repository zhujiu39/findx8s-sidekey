package cn.sidekey.menu;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** 根据本地勾选项排版；文案可以隐藏，但数据来源和状态语义保持不变。 */
final class SurfingContent extends LinearLayout {
    private static final String[] KEYS = {"up", "down", "upload", "download", "note", "quota", "used", "total"};
    private static final String[] DEFAULTS = {"↑ 上传速率", "↓ 下载速率", "↑ 上传流量", "↓ 下载流量", "本次核心统计", "订阅流量", "已用", "总额"};
    private final int foreground, muted;
    private final String[] labels = DEFAULTS.clone();
    private final TextView[] values = new TextView[4];
    private TextView note, quota;
    private int fields;
    private String description = "";

    SurfingContent(Context context, int foreground, int muted) {
        super(context); this.foreground = foreground; this.muted = muted; setOrientation(VERTICAL);
    }

    void bind(JSONObject item) {
        fields = item.optInt("surfing_fields", 31) & 31;
        JSONObject text = item.optJSONObject("surfing_text");
        for (int i = 0; i < labels.length; i++) labels[i] = text == null ? DEFAULTS[i] : text.optString(KEYS[i], DEFAULTS[i]);
        removeAllViews(); java.util.Arrays.fill(values, null);
        LinearLayout pair = null; int count = 0;
        for (int i = 0; i < 4; i++) {
            if ((fields & (1 << i)) == 0) continue;
            if (count % 2 == 0) { pair = new LinearLayout(getContext()); addView(pair, new LayoutParams(-1, -2)); }
            LinearLayout cell = new LinearLayout(getContext()); cell.setOrientation(VERTICAL);
            cell.setPadding(count % 2 == 0 ? 0 : dp(3), dp(7), count % 2 == 0 ? dp(3) : 0, 0);
            if (!labels[i].isEmpty()) {
                TextView label = text(labels[i], 9, muted); label.setSingleLine();
                label.setAutoSizeTextTypeUniformWithConfiguration(7, 9, 1, TypedValue.COMPLEX_UNIT_SP);
                cell.addView(label, new LayoutParams(-1, dp(15)));
            }
            TextView value = text("—", 12, foreground); value.setSingleLine(); value.setTypeface(null, Typeface.BOLD);
            value.setAutoSizeTextTypeUniformWithConfiguration(7, 12, 1, TypedValue.COMPLEX_UNIT_SP);
            values[i] = value; cell.addView(value, new LayoutParams(-1, dp(21)));
            if (pair != null) pair.addView(cell, new LayoutParams(0, -2, 1));
            count++;
        }
        note = text("", 8, muted); note.setMaxLines(2); note.setPadding(0, dp(7), 0, 0);
        addView(note, new LayoutParams(-1, -2));
        quota = null;
        if ((fields & 16) != 0) {
            if (!labels[5].isEmpty()) {
                TextView title = text(labels[5], 10, foreground); title.setTypeface(null, Typeface.BOLD);
                title.setMaxLines(2); title.setPadding(0, dp(9), 0, dp(4)); addView(title, new LayoutParams(-1, -2));
            }
            quota = text("读取中…", 10, foreground); quota.setLineSpacing(dp(2), 1f);
            if (labels[5].isEmpty()) quota.setPadding(0, dp(8), 0, 0);
            addView(quota, new LayoutParams(-1, -2));
        }
    }

    void update(SurfingState state) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < values.length; i++) if (values[i] != null) {
            String value = state.metric(i); setText(values[i], value);
            content.append('，').append(labels[i].isEmpty() ? DEFAULTS[i] : labels[i]).append(value);
        }
        String warning = state.warning(), footer = warning.isEmpty() ? ((fields & 15) == 0 ? "" : labels[4]) : warning;
        setText(note, footer); note.setVisibility(footer.isEmpty() ? View.GONE : View.VISIBLE);
        if (quota != null) {
            String value = quotaText(state); setText(quota, value);
            content.append('，').append(labels[5]).append('，').append(value);
        }
        description = content.toString();
    }

    String description() { return description; }

    private String quotaText(SurfingState state) {
        switch (state.quotaState) {
            case "loading": return "读取中…";
            case "unavailable": return "开启后读取订阅用量";
            case "empty": return "订阅未提供用量";
            case "error": return "读取失败，请查看日志";
            case "ok": case "cached": break;
            default: return "订阅用量未知";
        }
        if (state.subscriptions.length == 0) return "订阅未提供用量";
        StringBuilder result = new StringBuilder();
        if ("cached".equals(state.quotaState)) result.append("上次读取\n");
        for (int i = 0; i < state.subscriptions.length; i++) {
            SurfingState.Subscription item = state.subscriptions[i];
            if (i > 0) result.append("\n\n");
            if (state.subscriptions.length > 1 || state.omitted > 0) result.append(item.name).append('\n');
            result.append(withLabel(labels[6], item.used < 0 ? "未提供" : SurfingState.bytes(item.used))).append('\n');
            result.append(withLabel(labels[7], item.total <= 0 ? "未提供" : SurfingState.bytes(item.total)));
            String updated = item.updatedTime();
            if (!updated.isEmpty()) result.append("\n更新 ").append(updated);
        }
        if (state.omitted > 0) result.append("\n另有 ").append(state.omitted).append(" 项未展示");
        return result.toString();
    }

    private static String withLabel(String label, String value) { return label.isEmpty() ? value : label + "：" + value; }
    private static void setText(TextView view, String value) { if (!TextUtils.equals(view.getText(), value)) view.setText(value); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String label, int size, int color) {
        TextView view = new TextView(getContext()); view.setText(label); view.setTextSize(size); view.setTextColor(color);
        view.setEllipsize(TextUtils.TruncateAt.END); view.setIncludeFontPadding(false); return view;
    }
}
