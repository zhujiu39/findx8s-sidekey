package cn.sidekey;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/** 只读的 app_process 入口；由模块以 Root 调用，不需要先安装菜单 APK。 */
public final class AppCatalog {
    @SuppressWarnings("unchecked")
    public static void main(String[] arguments) {
        try {
            if (android.os.Process.myUid() != 0) throw new IllegalArgumentException("应用列表必须由模块服务读取");
            boolean iconsOnly = arguments.length == 3 && "icons".equals(arguments[0]);
            if (arguments.length != 0 && !iconsOnly) throw new IllegalArgumentException("应用查询参数无效");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context context = (Context)threadClass.getMethod("getSystemContext").invoke(thread);
            Method currentUser = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser");
            int user = (Integer)currentUser.invoke(null);
            if (user < 0) throw new IllegalStateException("无法确认当前 Android 用户");
            Set<String> wanted = new HashSet<>();
            if (iconsOnly) {
                if (Integer.parseInt(arguments[1]) != user) throw new IllegalStateException("Android 用户已切换，请刷新应用列表");
                String[] names = arguments[2].split(",", -1);
                if (names.length > 16) throw new IllegalArgumentException("一次最多读取 16 个图标");
                for (String name : names) {
                    if (name.length() > 512 || !name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("图标查询包名无效");
                    wanted.add(name);
                }
            }
            PackageManager packages = context.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            Method query = PackageManager.class.getMethod("queryIntentActivitiesAsUser", Intent.class, int.class, int.class);
            List<ResolveInfo> activities = (List<ResolveInfo>)query.invoke(packages, intent, 0, user);
            if (activities == null) throw new IllegalStateException("系统没有返回应用列表");
            AppCatalogModel model = new AppCatalogModel();
            JSONArray icons = new JSONArray();
            Set<String> readIcons = new HashSet<>();
            int labelFallbacks = 0;
            for (ResolveInfo info : activities) {
                if (info == null || info.activityInfo == null || !info.activityInfo.exported) continue;
                String packageName = info.activityInfo.packageName;
                if (iconsOnly) {
                    if (!wanted.contains(packageName) || !readIcons.add(packageName)) continue;
                    JSONObject item = new JSONObject(); item.put("packageName", packageName);
                    try { item.put("icon", icon(packages.getApplicationIcon(info.activityInfo.applicationInfo))); }
                    catch (RuntimeException error) { item.put("icon", ""); }
                    icons.put(item); continue;
                }
                CharSequence label;
                try { label = packages.getApplicationLabel(info.activityInfo.applicationInfo); }
                catch (RuntimeException error) { label = info.activityInfo.packageName; labelFallbacks++; }
                model.add(info.activityInfo.packageName, label);
            }
            if (user != (Integer)currentUser.invoke(null)) throw new IllegalStateException("Android 用户已切换，请重新读取应用列表");
            JSONArray apps = new JSONArray();
            for (AppCatalogModel.Entry entry : model.sorted(Locale.getDefault())) {
                JSONObject app = new JSONObject();
                app.put("packageName", entry.packageName); app.put("label", entry.label); apps.put(app);
            }
            JSONObject response = new JSONObject();
            response.put("version", 1); response.put("user", user); response.put("apps", apps);
            if (iconsOnly) response.put("icons", icons);
            response.put("labelFallbacks", labelFallbacks);
            String text = response.toString();
            if (text.length() > 2097152) throw new IllegalStateException("应用列表响应过长");
            new PrintStream(System.out, true, "UTF-8").println(text);
            System.exit(0);
        } catch (Exception error) {
            Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException)error).getTargetException() : error;
            System.err.println("读取应用列表失败：" + cause.getClass().getSimpleName() + " " + String.valueOf(cause.getMessage()));
            System.exit(1);
        }
    }

    private static String icon(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0, 0, 72, 72); drawable.draw(new Canvas(bitmap));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes) || bytes.size() > 32768) return "";
            return "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } finally { bitmap.recycle(); }
    }
}
