package cn.sidekey;

import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 限制应用列表的大小并按包名去重，避免同一应用的多个桌面入口重复出现。 */
public final class AppCatalogModel {
    public static final int MAX_APPS = 2048;
    public static final class Entry {
        public final String packageName, label;
        public Entry(String packageName, String label) { this.packageName = packageName; this.label = label; }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    public void add(String packageName, CharSequence label) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") ||
            packageName.getBytes(StandardCharsets.UTF_8).length > 512) return;
        if (!entries.containsKey(packageName) && entries.size() >= MAX_APPS)
            throw new IllegalStateException("可启动应用超过 2048 个，请减少当前用户的应用后重试");
        String cleaned = label == null ? "" : label.toString().replaceAll("[\\p{Cntrl}]", " ").trim();
        if (cleaned.isEmpty()) cleaned = packageName;
        if (cleaned.codePointCount(0, cleaned.length()) > 128) cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, 128));
        Entry previous = entries.get(packageName);
        // 多入口按标签确定稳定顺序，包管理器返回顺序变化不会改变选择结果。
        if (previous == null || cleaned.compareTo(previous.label) < 0) entries.put(packageName, new Entry(packageName, cleaned));
    }
    public List<Entry> sorted(Locale locale) {
        ArrayList<Entry> result = new ArrayList<>(entries.values());
        final Collator collator = Collator.getInstance(locale);
        result.sort((a, b) -> { int compared = collator.compare(a.label, b.label); return compared != 0 ? compared : a.packageName.compareTo(b.packageName); });
        return result;
    }
}
