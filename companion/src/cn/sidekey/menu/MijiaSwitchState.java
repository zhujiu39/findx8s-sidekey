package cn.sidekey.menu;

/** 会话内的轻量状态映射，与绘制和网络实现分离。 */
public final class MijiaSwitchState {
    private MijiaSwitchState() { }
    public static String decode(String response, int count) {
        if (response == null || count < 0 || count > 2060 || !response.startsWith("STATES ") || response.length() != count + 7) return null;
        String states = response.substring(7);
        for (int i = 0; i < states.length(); i++) if ("01?n~".indexOf(states.charAt(i)) < 0) return null;
        return states;
    }
    public static char at(String states, int index, boolean pending) {
        return states != null && index >= 0 && index < states.length() ? states.charAt(index) : pending ? '~' : '?';
    }
    public static String description(char state) {
        switch (state) {
            case '1': return "已开启";
            case '0': return "已关闭";
            case '~': return "读取中";
            case 'n': return "执行动作";
            default: return "状态未知";
        }
    }
}
