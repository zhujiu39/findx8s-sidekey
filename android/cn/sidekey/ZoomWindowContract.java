package cn.sidekey;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** 运行时核对 OPlus 接口签名与状态字段，不使用固定 Binder 事务编号。 */
public final class ZoomWindowContract {
    private final Object manager;
    private final Method supported, start, state;
    private final Field shown, type, name, user, bounds, left, top, right, bottom;
    public final int mode, zoomType;
    public final String modeExtra;

    public ZoomWindowContract(Class<?> managerClass, Class<?> intentClass, Class<?> bundleClass) throws Exception {
        mode = managerClass.getField("WINDOWING_MODE_ZOOM").getInt(null);
        zoomType = managerClass.getField("WINDOW_TYPE_ZOOM").getInt(null);
        modeExtra = (String)managerClass.getField("EXTRA_WINDOW_MODE").get(null);
        if (mode != 100 || zoomType != 1 || !"extra_window_mode".equals(modeExtra))
            throw new IllegalStateException("OPlus 小窗常量与已支持接口不一致");
        manager = call(managerClass.getMethod("getInstance"), null);
        if (manager == null) throw new IllegalStateException("OPlus 小窗服务未初始化");
        supported = managerClass.getMethod("isSupportZoomWindowMode");
        start = managerClass.getMethod("startZoomWindow", intentClass, bundleClass, int.class, String.class);
        state = managerClass.getMethod("getCurrentZoomWindowState");
        if (start.getReturnType() != int.class || supported.getReturnType() != boolean.class)
            throw new IllegalStateException("OPlus 小窗返回类型不兼容");
        Class<?> info = state.getReturnType();
        shown = field(info, "windowShown", boolean.class); type = field(info, "windowType", int.class);
        name = field(info, "zoomPkg", String.class); user = field(info, "zoomUserId", int.class);
        bounds = info.getField("zoomRect"); Class<?> rect = bounds.getType();
        left = field(rect, "left", int.class); top = field(rect, "top", int.class);
        right = field(rect, "right", int.class); bottom = field(rect, "bottom", int.class);
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
        Field result = owner.getField(name);
        if (result.getType() != type) throw new IllegalStateException("小窗状态字段不兼容：" + name);
        return result;
    }
    private static Object call(Method method, Object instance, Object... arguments) throws Exception {
        try { return method.invoke(instance, arguments); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getTargetException();
            if (cause instanceof Exception) throw (Exception)cause;
            throw new IllegalStateException("OPlus 系统接口异常", cause);
        }
    }
    public boolean supported() throws Exception { return (Boolean)call(supported, manager); }
    public int start(Object intent, Object options, int userId, String caller) throws Exception {
        return (Integer)call(start, manager, intent, options, userId, caller);
    }
    public ZoomController.State state() throws Exception {
        Object info = call(state, manager);
        if (info == null) return null;
        Object rect = bounds.get(info);
        boolean area = rect != null && right.getInt(rect) > left.getInt(rect) && bottom.getInt(rect) > top.getInt(rect);
        return new ZoomController.State(shown.getBoolean(info), type.getInt(info) == zoomType, area, (String)name.get(info), user.getInt(info));
    }
}
