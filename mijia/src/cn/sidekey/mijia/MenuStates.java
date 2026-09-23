// SPDX-License-Identifier: GPL-3.0-or-later
package cn.sidekey.mijia;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** 每次展开或操作完成后创建一次读取快照；本地取结果不会再次访问米家。 */
final class MenuStates {
    static final class Snapshot {
        final String account, ids;
        final long created = System.nanoTime();
        String states;
        String[] readings;
        boolean done;
        Snapshot(String account, String ids, String states) { this.account = account; this.ids = ids; this.states = states; }
        String wire() {
            if (!done) return "P";
            StringBuilder wire = new StringBuilder("D").append(states);
            if (readings != null) for (String reading : readings) {
                wire.append('|');
                if (reading == null) continue;
                for (byte value : reading.getBytes(StandardCharsets.UTF_8)) {
                    wire.append("0123456789abcdef".charAt((value & 255) >>> 4));
                    wire.append("0123456789abcdef".charAt(value & 15));
                }
            }
            return wire.toString();
        }
    }
    private final Map<String, Snapshot> sessions = new LinkedHashMap<>();
    synchronized Snapshot find(String session, String account, String ids) throws Exception {
        Snapshot value = sessions.get(session);
        if (value != null && (!value.account.equals(account) || !value.ids.equals(ids)))
            throw new Failure("SESSION", "快捷栏状态会话已失效，请重新打开");
        return value;
    }
    synchronized Snapshot create(String session, String account, String ids, String states) {
        while (sessions.size() >= 8) sessions.remove(sessions.keySet().iterator().next());
        Snapshot value = new Snapshot(account, ids, states); sessions.put(session, value); return value;
    }
    synchronized void complete(Snapshot snapshot, String states) {
        complete(snapshot, states, null);
    }
    synchronized void complete(Snapshot snapshot, String states, String[] readings) {
        if (states.length() != snapshot.states.length() || (readings != null && readings.length != states.length()))
            throw new IllegalArgumentException("读数快照长度不一致");
        if (readings != null) for (String value : readings)
            if (value != null && value.getBytes(StandardCharsets.UTF_8).length > ReadingValues.MAX_BYTES)
                throw new IllegalArgumentException("读数卡片长度越界");
        if (!snapshot.done) { snapshot.states = states; snapshot.readings = readings; snapshot.done = true; }
    }
    synchronized String wire(Snapshot snapshot) { return snapshot.wire(); }
    synchronized void clear() {
        for (Snapshot snapshot : sessions.values()) {
            char[] unknown = new char[snapshot.states.length()]; Arrays.fill(unknown, '?');
            snapshot.states = new String(unknown); snapshot.readings = null; snapshot.done = true;
        }
    }
    static boolean switchAction(JSONObject action) {
        return action.optString("kind").equals("toggle") ||
            (action.optString("kind").equals("set") && action.opt("value") instanceof Boolean);
    }
    static char value(JSONObject result) {
        return result != null && result.optInt("code", -1) == 0 && result.opt("value") instanceof Boolean ?
            ((Boolean) result.opt("value") ? '1' : '0') : '?';
    }
}
