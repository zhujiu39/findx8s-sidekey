import cn.sidekey.AppCatalogModel;
import java.util.List;
import java.util.Locale;
public final class AppCatalogModelTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] arguments) {
        AppCatalogModel model = new AppCatalogModel();
        model.add("com.demo", "设置\n管理"); model.add("com.demo", "设置\n管理");
        model.add("com.empty", null); model.add("bad;id", "不合法");
        model.add("com.same", "A"); model.add("com.same", "B");
        List<AppCatalogModel.Entry> list = model.sorted(Locale.CHINA);
        check(list.size() == 3);
        check(list.stream().anyMatch(entry -> entry.packageName.equals("com.demo") && entry.label.equals("设置 管理")));
        check(list.stream().anyMatch(entry -> entry.packageName.equals("com.empty") && entry.label.equals("com.empty")));
        check(list.stream().anyMatch(entry -> entry.packageName.equals("com.same") && entry.label.equals("A")));
        StringBuilder emoji = new StringBuilder(); for (int i=0;i<200;i++) emoji.append("😀");
        model.add("com.emoji", emoji);
        String label = model.sorted(Locale.CHINA).stream().filter(entry -> entry.packageName.equals("com.emoji")).findFirst().get().label;
        check(label.codePointCount(0, label.length()) == 128 && label.length() == 256);
        AppCatalogModel full = new AppCatalogModel();
        for (int i=0;i<AppCatalogModel.MAX_APPS;i++) full.add("com.app" + i, "应用");
        full.add("com.app0", "重复项");
        boolean rejected = false;
        try { full.add("com.excess", "超量"); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected && full.sorted(Locale.CHINA).size() == AppCatalogModel.MAX_APPS);
        System.out.println("PASS: app catalog deduplication, invalid package rejection, label fallback, Unicode and size bounds");
    }
}
