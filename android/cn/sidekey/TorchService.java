package cn.sidekey;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @功能：在 Root app_process 内调用系统手电筒接口，并保留 Binder 所有权。
 * @日期：2026-09-16
 * @使用说明：由原生监听服务管理生命周期；无相机预览、不写 sysfs、不修改状态设置项。
 */
public final class TorchService {
    private final AtomicFile statusFile;
    private CameraManager manager;
    private TorchController controller;
    private String cameraId = "";
    private volatile String error = "";
    private HandlerThread callbackThread;
    private final AtomicLong deadline = new AtomicLong(0);

    private TorchService(String directory) {
        statusFile = new AtomicFile(new File(directory, "torch.json"));
    }

    @SuppressWarnings("deprecation")
    private void initialize() throws Exception {
        // app_process 没有 Activity；只借用系统 Context 获取 CameraManager。
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) throw new IllegalStateException("无法连接相机服务");
        int maximum = 1;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            if (Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) &&
                Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING))) {
                cameraId = id;
                Integer level = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                if (level != null && level > 1) maximum = level;
                break;
            }
        }
        if (cameraId.isEmpty()) throw new IllegalStateException("系统未提供带闪光灯的后置相机");
        controller = new TorchController((enabled, level) -> {
            if (enabled && level > 1) manager.turnOnTorchWithStrengthLevel(cameraId, level);
            else manager.setTorchMode(cameraId, enabled);
        }, maximum);
        callbackThread = new HandlerThread("sidekey-torch-callback");
        callbackThread.start();
        manager.registerTorchCallback(new CameraManager.TorchCallback() {
            @Override public void onTorchModeChanged(String id, boolean enabled) {
                if (!cameraId.equals(id)) return;
                controller.onState(true, enabled);
                if (enabled) {
                    try { controller.onStrength(manager.getTorchStrengthLevel(id)); }
                    catch (Exception e) { log("读取实际亮度失败：" + describe(e)); }
                }
                error = "";
                publish();
            }
            @Override public void onTorchModeUnavailable(String id) {
                if (!cameraId.equals(id)) return;
                controller.onState(false, false);
                publish();
            }
            @Override public void onTorchStrengthLevelChanged(String id, int level) {
                if (!cameraId.equals(id)) return;
                controller.onStrength(level);
                publish();
            }
        }, new Handler(callbackThread.getLooper()));
        log("相机 " + cameraId + "，系统亮度上限 " + maximum +
            (maximum > 1 ? "，使用最高档位" : "，系统仅开放默认亮度"));
        publish();
    }

    private synchronized void publish() {
        FileOutputStream out = null;
        try {
            JSONObject status = new JSONObject();
            status.put("pid", Process.myPid());
            status.put("camera", cameraId);
            status.put("error", error);
            status.put("updated_ms", SystemClock.elapsedRealtime());
            if (controller != null) {
                TorchController.Snapshot s = controller.snapshot();
                status.put("known", s.known); status.put("available", s.available);
                status.put("enabled", s.enabled); status.put("strength", s.strength);
                status.put("maximum", s.maximum);
            }
            out = statusFile.startWrite();
            out.write(status.toString().getBytes(StandardCharsets.UTF_8));
            statusFile.finishWrite(out);
        } catch (Exception e) {
            if (out != null) statusFile.failWrite(out);
            log("写入手电筒状态失败：" + describe(e));
        }
    }

    private void serve(String socketName) throws Exception {
        // 协议为单字节 T 请求、以换行结束的 OK/ERR 响应；只接收 root 客户端。
        try (LocalServerSocket server = new LocalServerSocket(socketName)) {
            while (true) {
                try (LocalSocket client = server.accept()) {
                    if (client.getPeerCredentials().getUid() != 0) continue;
                    client.setSoTimeout(1000);
                    if (client.getInputStream().read() != 'T') continue;
                    deadline.set(SystemClock.elapsedRealtime() + 7000);
                    String response;
                    try {
                        controller.toggle(1800);
                        error = "";
                        TorchController.Snapshot s = controller.snapshot();
                        response = "OK " + (s.enabled ? "手电筒已开启" : "手电筒已关闭");
                    } catch (Exception e) {
                        error = describe(e); response = "ERR " + error;
                    }
                    publish(); log(response);
                    client.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    log("客户端请求异常：" + describe(e));
                } finally { deadline.set(0); }
            }
        }
    }

    private static String describe(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        String result = cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
        result = result.replace('\n', ' ').replace('\r', ' ');
        return result.length() > 160 ? result.substring(0, 160) : result;
    }

    private static void log(String text) { System.err.println("[手电筒] " + text); }

    private void startWatchdog() {
        deadline.set(SystemClock.elapsedRealtime() + 10000);
        Thread watchdog = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                long end = deadline.get();
                if (end != 0 && SystemClock.elapsedRealtime() >= end) {
                    log("相机服务调用超时，服务退出并释放自己持有的手电筒");
                    Runtime.getRuntime().halt(124);
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }, "sidekey-torch-watchdog");
        watchdog.setDaemon(true); watchdog.start();
    }

    public static void main(String[] args) {
        if (Process.myUid() != 0 || args.length != 2) System.exit(2);
        TorchService service = new TorchService(args[0]);
        try {
            service.startWatchdog();
            service.initialize();
            service.deadline.set(0);
            service.serve(args[1]);
        } catch (Throwable e) {
            service.error = describe(e); log(service.error); service.publish();
            System.exit(1);
        }
    }
}
