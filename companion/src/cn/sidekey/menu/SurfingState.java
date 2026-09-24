package cn.sidekey.menu;

import java.util.Locale;
import org.json.JSONObject;

/** 仅包含展示所需状态及四项统计，不接触控制接口地址、密钥或连接列表。 */
final class SurfingState {
    final String state, message;
    final boolean busy;
    final int result;
    final long up, down, uploadTotal, downloadTotal;

    private SurfingState(String state, String message, boolean busy, int result, long up, long down, long uploadTotal, long downloadTotal) {
        this.state = state; this.message = message; this.busy = busy; this.result = result;
        this.up = up; this.down = down; this.uploadTotal = uploadTotal; this.downloadTotal = downloadTotal;
    }

    static SurfingState unknown() { return new SurfingState("unknown", "", false, 0, -1, -1, -1, -1); }
    static SurfingState decode(String response) throws Exception {
        JSONObject root = new JSONObject(response), data = root.getJSONObject("data");
        String state = data.getString("state");
        if (!("|on|off|starting|stopping|stopped|missing|manual|removing|unknown|").contains("|" + state + "|") || state.isEmpty() || state.indexOf('|') >= 0)
            throw new IllegalArgumentException("Surfing 状态无效");
        String message = data.optString("message");
        if (message.length() > 160) throw new IllegalArgumentException("Surfing 提示过长");
        JSONObject traffic = data.optJSONObject("traffic");
        return new SurfingState(state, message, root.getBoolean("busy"), root.getInt("result"),
                number(traffic, "up"), number(traffic, "down"), number(traffic, "uploadTotal"), number(traffic, "downloadTotal"));
    }

    private static long number(JSONObject object, String key) throws Exception {
        if (object == null || !object.has(key)) return -1;
        Object value = object.get(key);
        if (!(value instanceof Number) || value instanceof Double || value instanceof Float || ((Number)value).longValue() < 0)
            throw new IllegalArgumentException("Surfing 统计无效");
        return ((Number)value).longValue();
    }

    boolean canToggle() { return !busy && ("on".equals(state) || "off".equals(state) || "stopped".equals(state)); }
    char power() { return busy || "starting".equals(state) || "stopping".equals(state) ? '~' :
        "on".equals(state) ? '1' : "off".equals(state) || "stopped".equals(state) ? '0' : '?'; }
    String status() {
        if (busy) return "切换中…";
        switch (state) {
            case "on": return "已开启";
            case "off": return "已关闭";
            case "starting": return "正在开启…";
            case "stopping": return "正在关闭…";
            case "stopped": return "核心未运行";
            case "missing": return "未安装 Surfing";
            case "manual": return "手动模式";
            case "removing": return "等待卸载";
            default: return message.isEmpty() || "正在读取状态".equals(message) ? "读取中…" : "状态未知";
        }
    }
    String note() {
        if (!busy && result != 0) return "操作未完成，请查看日志";
        if (!message.isEmpty() && !"正在读取状态".equals(message)) return "统计暂不可用，请查看日志";
        return "本次核心统计";
    }
    String metric(int index) {
        long value = index == 0 ? up : index == 1 ? down : index == 2 ? uploadTotal : downloadTotal;
        if (value < 0) return "—";
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};
        int unit = 0; double amount = value;
        while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit++; }
        return (unit == 0 ? Long.toString(value) : String.format(Locale.ROOT, "%.1f", amount)) + " " + units[unit] + (index < 2 ? "/s" : "");
    }
}
