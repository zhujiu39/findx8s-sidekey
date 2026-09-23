import cn.sidekey.menu.MijiaSwitchState;

public final class MenuSwitchStateTest {
    private static void check(boolean good) { if (!good) throw new AssertionError("menu switch state"); }
    public static void main(String[] args) {
        String states = MijiaSwitchState.decode("STATES ~1?0n", 5);
        check("~1?0n".equals(states));
        check(MijiaSwitchState.at(states, 1, true) == '1');
        check(MijiaSwitchState.at(null, 1, true) == '~');
        check(MijiaSwitchState.at(null, 1, false) == '?');
        check(MijiaSwitchState.decode("STATES 01x", 3) == null);
        check(MijiaSwitchState.decode("STATES 0", 2) == null);
        check(MijiaSwitchState.description('?').equals("状态未知"));
        check("o1ua?n".equals(MijiaSwitchState.decode("STATES o1ua?n", 6)));
        check(MijiaSwitchState.status('o').equals("离线") && !MijiaSwitchState.enabled('o'));
        for (char online : new char[]{'1', '0', 'u', 'a'}) {
            check(MijiaSwitchState.status(online).equals("在线") && MijiaSwitchState.enabled(online));
        }
        check(!MijiaSwitchState.enabled('?') && !MijiaSwitchState.enabled('~'));
        check(!MijiaSwitchState.power('a') && !MijiaSwitchState.power('n') && MijiaSwitchState.enabled('n'));
        check("1~on".equals(MijiaSwitchState.pending("10on", 1)));
        check("1?on".equals(MijiaSwitchState.failed("1~on")));
        check(MijiaSwitchState.decode("STATES -", 1) == null);
    }
}
