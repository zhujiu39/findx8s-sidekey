package cn.sidekey;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Root 独立进程的小窗入口，外层脚本和动作进程均设有总超时。 */
public final class ZoomWindowLauncher {
    private static Class<?> managerClass() throws Exception {
        String name = "com.oplus.zoomwindow.OplusZoomWindowManager";
        try { return Class.forName(name); }
        catch (ClassNotFoundException absent) {
            // 部分 ROM 的扩展框架不在 app_process 默认类路径，仅从系统只读目录加载。
            StringBuilder paths = new StringBuilder();
            for (String path : new String[]{"/system/framework/oplus-framework.jar", "/system_ext/framework/oplus-framework.jar"}) {
                if (new File(path).isFile()) { if (paths.length() != 0) paths.append(File.pathSeparator); paths.append(path); }
            }
            if (paths.length() == 0) throw new IllegalStateException("当前 ROM 未提供 OPlus 小窗框架", absent);
            return Class.forName(name, true, new PathClassLoader(paths.toString(), ZoomWindowLauncher.class.getClassLoader()));
        }
    }

    @SuppressWarnings("deprecation")
    public static void main(String[] arguments) {
        try {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
            System.setErr(new PrintStream(System.err, true, "UTF-8"));
            if (android.os.Process.myUid() != 0) throw new IllegalArgumentException("小窗必须由模块 Root 服务启动");
            if (arguments.length != 2 || !arguments[1].matches("[0-9]{1,6}")) throw new IllegalArgumentException("小窗参数无效");
            final int user = Integer.parseInt(arguments[1]);
            ComponentName component = ComponentName.unflattenFromString(arguments[0]);
            if (component == null || !component.getPackageName().matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") ||
                !component.getClassName().matches("[A-Za-z0-9_.$]+")) throw new IllegalArgumentException("小窗应用入口无效");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context context = (Context)threadClass.getMethod("getSystemContext").invoke(thread);
            final Method currentUser = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser");
            final ZoomWindowContract api = new ZoomWindowContract(managerClass(), Intent.class, Bundle.class);
            final String caller = context.getPackageName();
            final Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra(api.modeExtra, api.mode);
            ActivityOptions options = ActivityOptions.makeBasic();
            ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(options, api.mode);
            final Bundle bundle = options.toBundle();
            System.out.println("小窗接口：OplusZoomWindowManager.startZoomWindow；模式=" + api.mode + "；API=" + android.os.Build.VERSION.SDK_INT);
            ZoomController.launch(new ZoomController.Backend() {
                public int currentUser() throws Exception { return (Integer)currentUser.invoke(null); }
                public boolean supported() throws Exception { return api.supported(); }
                public int start() throws Exception { return api.start(intent, bundle, user, caller); }
                public ZoomController.State state() throws Exception { return api.state(); }
                public long now() { return SystemClock.elapsedRealtime(); }
                public void pause(long milliseconds) throws InterruptedException { Thread.sleep(milliseconds); }
                public void log(String message) { System.out.println(message); }
            }, component.getPackageName(), user);
            System.out.println("SIDEKEY_ZOOM_CONFIRMED " + component.getPackageName() + " " + user);
            System.exit(0);
        } catch (Exception | LinkageError error) {
            Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException)error).getTargetException() : error;
            System.err.println("小窗启动失败：" + cause.getClass().getSimpleName() + " " + cause.getMessage());
            System.err.println("已停止，不会改为普通全屏打开。");
            System.exit(1);
        }
    }
}
