// SPDX-License-Identifier: GPL-3.0-or-later
package cn.sidekey.mijia;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class MijiaMain {
    private static final String DATA = "/data/adb/oppo_sidekey/mijia";
    private static final String SOCKET = DATA + "/control.sock";
    private static JSONObject receive(InputStream stream, int max) throws Exception {
        DataInputStream input = new DataInputStream(stream); int length = input.readInt();
        if (length <= 0 || length > max) throw new Failure("LIMIT", "本地请求长度无效");
        byte[] bytes = new byte[length]; input.readFully(bytes); return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    private static void send(OutputStream stream, JSONObject object) throws Exception {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2 * 1024 * 1024) throw new Failure("LIMIT", "本地响应过大");
        DataOutputStream output = new DataOutputStream(stream); output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    private static JSONObject client(JSONObject request) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            try (LocalSocket socket = new LocalSocket()) {
                try { socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.FILESYSTEM)); }
                catch (IOException error) {
                    if (attempt == 19) throw new Failure("SERVICE", "米家服务未就绪，请重启手机或重新安装模块");
                    Thread.sleep(150); continue;
                }
                if (socket.getPeerCredentials().getUid() != 0) throw new Failure("SERVICE", "米家服务身份无效");
                socket.setSoTimeout(4000); send(socket.getOutputStream(), request); return receive(socket.getInputStream(), 2 * 1024 * 1024);
            }
        }
        throw new Failure("SERVICE", "米家服务连接失败");
    }
    private static void server(String module) throws Exception {
        PrivateStore store = new PrivateStore(Paths.get(DATA));
        try (RandomAccessFile lockFile = new RandomAccessFile(DATA + "/service.lock", "rw"); FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) return;
            Files.write(Paths.get(DATA, "service.pid"), String.valueOf(Process.myPid()).getBytes(StandardCharsets.US_ASCII));
            Files.deleteIfExists(Paths.get(SOCKET));
            try (LocalSocket bound = new LocalSocket(); MijiaBridge bridge = new MijiaBridge(store)) {
                bound.bind(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.FILESYSTEM));
                android.system.Os.chmod(SOCKET, 0600);
                try (LocalServerSocket listener = new LocalServerSocket(bound.getFileDescriptor())) {
                    ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
                    watchdog.scheduleWithFixedDelay(() -> {
                        if (Files.exists(Paths.get(module, "disable")) || Files.exists(Paths.get(module, "remove")) || !Files.exists(Paths.get(module, "module.prop"))) {
                            bridge.close(); try { listener.close(); } catch (IOException ignored) { }
                        }
                    }, 2, 2, TimeUnit.SECONDS);
                    try {
                        while (true) {
                            try (LocalSocket socket = listener.accept()) {
                                if (socket.getPeerCredentials().getUid() != 0) continue;
                                socket.setSoTimeout(4000); JSONObject result;
                                try {
                                    JSONObject request = receive(socket.getInputStream(), 32768);
                                    if (request.optString("op").equals("shutdown")) { send(socket.getOutputStream(), Json.obj("ok", true)); break; }
                                    result = bridge.handle(request);
                                } catch (Exception error) { result = Failure.json(error); }
                                try { send(socket.getOutputStream(), result); } catch (IOException ignored) { /* 客户端退出后任务仍由服务持有。 */ }
                            }
                        }
                    } finally { watchdog.shutdownNow(); }
                }
            } finally { Files.deleteIfExists(Paths.get(SOCKET)); Files.deleteIfExists(Paths.get(DATA, "service.pid")); }
        }
    }
    public static void main(String[] args) {
        int exit = 0;
        try {
            if (Process.myUid() != 0) throw new Failure("PERMISSION", "请通过 KernelSU 打开模块");
            if (args.length == 2 && args[0].equals("server")) server(args[1]);
            else {
                JSONObject request;
                if (args.length == 2 && args[0].equals("request")) request = new JSONObject(new String(Json.unhex(args[1]), StandardCharsets.UTF_8));
                else if (args.length == 2 && args[0].equals("trigger") && args[1].matches("[a-f0-9]{32}")) request = Json.obj("op", "trigger", "id", args[1]);
                else if (args.length == 1 && args[0].equals("stop")) request = Json.obj("op", "shutdown");
                else throw new Failure("INVALID", "米家服务参数无效");
                JSONObject result = client(request); System.out.println(result.toString());
                if (args[0].equals("trigger") && !result.optBoolean("ok")) exit = 1;
            }
        } catch (Exception error) {
            System.out.println(Failure.json(error).toString());
            exit = args.length > 0 && args[0].equals("request") ? 0 : 1;
        }
        System.exit(exit);
    }
}
