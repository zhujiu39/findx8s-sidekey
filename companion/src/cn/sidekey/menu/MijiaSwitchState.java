package cn.sidekey.menu;

/** 会话内的轻量状态映射，与绘制和网络实现分离。 */
public final class MijiaSwitchState {
    private MijiaSwitchState() { }
    public static String decode(String response, int count) {
        if (response == null || count < 0 || count > 2060 || !response.startsWith("STATES ") || response.length() != count + 7) return null;
        String states = response.substring(7);
        for (int i = 0; i < states.length(); i++) if ("01?noau~".indexOf(states.charAt(i)) < 0) return null;
        return states;
    }
    public static char at(String states, int index, boolean pending) {
        return states != null && index >= 0 && index < states.length() ? states.charAt(index) : pending ? '~' : '?';
    }
    public static boolean online(char state) { return "01ua".indexOf(state) >= 0; }
    public static boolean enabled(char state) { return online(state) || state == 'n'; }
    public static boolean power(char state) { return state != 'n' && state != 'a'; }
    public static String status(char state) {
        if (online(state)) return "在线";
        if (state == 'o') return "离线";
        if (state == '~') return "读取中";
        return state == 'n' ? "手动场景" : "状态未知";
    }
    public static String pending(String states, int index) {
        if (states == null || index < 0 || index >= states.length()) return states;
        char[] values = states.toCharArray(); values[index] = '~'; return new String(values);
    }
    public static String failed(String states) { return states == null ? null : states.replace('~', '?'); }
    public static String description(char state) {
        switch (state) {
            case '1': return "已开启";
            case '0': return "已关闭";
            case 'o': return "离线，无法操作";
            case 'u': return "在线，开关状态未确认";
            case 'a': return "在线，执行动作";
            case '~': return "读取中";
            case 'n': return "执行动作";
            default: return "状态未知";
        }
    }
}
