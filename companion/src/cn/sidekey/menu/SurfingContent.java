package cn.sidekey.menu;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import org.json.JSONObject;

/** 根据本地勾选项排版；文案可以隐藏，但数据来源和状态语义保持不变。 */
final class SurfingContent extends LinearLayout {
    private static final String[] KEYS = {"up", "down", "upload", "download", "note", "quota", "used", "total"};
    private static final String[] DEFAULTS = {"↑ 上传速率", "↓ 下载速率", "↑ 上传流量", "↓ 下载流量", "本次核心统计", "订阅流量", "已用", "总额"};
    private final int foreground, muted, accent, divider;
    private final int columns;
    private final String[] labels = DEFAULTS.clone();
    private final TextView[] values = new TextView[4];
    private TextView note;
    private LinearLayout quota;
    private String quotaSnapshot = "";
    private int fields;
    private String description = "";

    SurfingContent(Context context, int foreground, int muted, int accent, int panelWidth) {
        super(context); this.foreground = foreground; this.muted = muted; this.accent = accent;
        divider = (muted & 0x00FFFFFF) | 0x20000000; columns = panelWidth < 180 ? 1 : 2;
        setOrientation(VERTICAL); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void bind(JSONObject item) {
        fields = item.optInt("surfing_fields", 31) & 31;
        JSONObject text = item.optJSONObject("surfing_text");
        for (int i = 0; i < labels.length; i++) labels[i] = text == null ? DEFAULTS[i] : text.optString(KEYS[i], DEFAULTS[i]);
        removeAllViews(); java.util.Arrays.fill(values, null); quotaSnapshot = "";
        if ((fields & 15) != 0) addDivider(this);
        LinearLayout pair = null; int count = 0;
        for (int i = 0; i < 4; i++) {
            if ((fields & (1 << i)) == 0) continue;
            if (count % columns == 0) { pair = new LinearLayout(getContext()); addView(pair, new LayoutParams(-1, -2)); }
            LinearLayout cell = new LinearLayout(getContext()); cell.setOrientation(VERTICAL);
            cell.setPadding(count % columns == 0 ? 0 : dp(4), dp(10), count % columns == 0 && columns == 2 ? dp(4) : 0, 0);
            if (!labels[i].isEmpty()) {
                TextView label = text(labels[i], 10, muted); label.setMaxLines(3);
                cell.addView(label, new LayoutParams(-1, -2));
            }
            TextView value = text("—", 14, foreground); value.setSingleLine();
            value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); value.setFontFeatureSettings("tnum");
            value.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP);
            LayoutParams valueSize = new LayoutParams(-1, Math.max(dp(24), value.getLineHeight() + dp(4)));
            values[i] = value; cell.addView(value, valueSize);
            if (pair != null) pair.addView(cell, new LayoutParams(0, -2, 1));
            count++;
        }
        note = text("", 10, muted); note.setMaxLines(3); note.setPadding(0, dp(10), 0, 0);
        addView(note, new LayoutParams(-1, -2));
        quota = null;
        if ((fields & 16) != 0) {
            addDivider(this);
            if (!labels[5].isEmpty()) {
                TextView title = text(labels[5], 11, foreground); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                title.setMaxLines(3); title.setPadding(0, dp(10), 0, dp(5)); addView(title, new LayoutParams(-1, -2));
            }
            quota = new LinearLayout(getContext()); quota.setOrientation(VERTICAL);
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
            String value = quotaText(state);
            if (!quotaSnapshot.equals(value)) { quotaSnapshot = value; renderQuota(state, value); }
            content.append('，').append(labels[5]).append('，').append(value);
        }
        description = content.toString();
    }

    String description() { return description; }

    private void renderQuota(SurfingState state, String fallback) {
        quota.removeAllViews();
        if (!("ok".equals(state.quotaState) || "cached".equals(state.quotaState)) || state.subscriptions.length == 0) {
            quota.addView(text(fallback, 11, muted), new LayoutParams(-1, -2)); return;
        }
        if ("cached".equals(state.quotaState)) quota.addView(text("上次读取", 10, muted), new LayoutParams(-1, -2));
        for (int i = 0; i < state.subscriptions.length; i++) {
            SurfingState.Subscription item = state.subscriptions[i];
            if (i > 0) addDivider(quota);
            if (state.subscriptions.length > 1 || state.omitted > 0) {
                TextView name = text(item.name, 11, foreground); name.setMaxLines(3); name.setPadding(0, dp(7), 0, dp(4));
                quota.addView(name, new LayoutParams(-1, -2));
            }
            TextView used = text(withLabel(labels[6], item.used < 0 ? "未提供" : SurfingState.bytes(item.used)), 12, foreground);
            used.setPadding(0, dp(4), 0, 0); used.setLineSpacing(dp(2), 1f); used.setFontFeatureSettings("tnum");
            quota.addView(used, new LayoutParams(-1, -2));
            TextView total = text(withLabel(labels[7], item.total <= 0 ? "未提供" : SurfingState.bytes(item.total)), 11, muted);
            total.setPadding(0, dp(4), 0, 0); total.setLineSpacing(dp(2), 1f); total.setFontFeatureSettings("tnum");
            quota.addView(total, new LayoutParams(-1, -2));
            if (item.used >= 0 && item.total > 0) {
                ProgressBar progress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
                progress.setIndeterminate(false); progress.setMax(1000);
                progress.setProgress((int)Math.min(1000, (double)item.used / item.total * 1000));
                progress.setProgressTintList(ColorStateList.valueOf(accent));
                progress.setProgressBackgroundTintList(ColorStateList.valueOf(divider));
                LayoutParams size = new LayoutParams(-1, dp(5)); size.topMargin = dp(8); size.bottomMargin = dp(3);
                quota.addView(progress, size);
            }
            String updated = item.updatedTime();
            if (!updated.isEmpty()) {
                TextView time = text("更新 " + updated, 9, muted); time.setPadding(0, dp(5), 0, 0);
                quota.addView(time, new LayoutParams(-1, -2));
            }
        }
        if (state.omitted > 0) {
            TextView extra = text("另有 " + state.omitted + " 项未展示", 10, muted); extra.setPadding(0, dp(8), 0, 0);
            quota.addView(extra, new LayoutParams(-1, -2));
        }
    }

    private void addDivider(LinearLayout host) {
        View line = new View(getContext()); line.setBackgroundColor(divider);
        LayoutParams size = new LayoutParams(-1, 1); size.topMargin = dp(12); host.addView(line, size);
    }

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
