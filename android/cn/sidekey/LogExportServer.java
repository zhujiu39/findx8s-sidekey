package cn.sidekey;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** 一次导出使用一个短期本机会话；Root 仅提供固定日志快照，选定文档由普通应用写入。 */
public final class LogExportServer {
    private static final Path DATA = Paths.get("/data/adb/oppo_sidekey");
    private final String id, module;
    private final Path status;
    private LogExportServer(String module, String id) {
        this.module = module; this.id = id; this.status = DATA.resolve("log-export-" + id + ".json");
    }
    private void status(String state, String message) throws Exception {
        JSONObject result = new JSONObject().put("id", id).put("state", state).put("message", message);
        Path temporary = status.resolveSibling(status.getFileName() + ".tmp");
        Files.write(temporary, result.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, status, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private static String command(String... arguments) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("系统命令超时");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int count;
            try (InputStream input = process.getInputStream()) {
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 8192) throw new IOException("系统响应过长");
                    output.write(buffer, 0, count);
                }
            }
            String text = output.toString("UTF-8").trim();
            if (process.exitValue() != 0 || text.startsWith("Error") || text.contains("\nError"))
                throw new IOException("无法启动系统文件选择器，请在 WebUI 修复快捷菜单后重试");
            return text;
        } finally { process.destroyForcibly(); }
    }
    private void snapshot(Path target, ScheduledExecutorService clock) throws Exception {
        Process process = new ProcessBuilder("/system/bin/sh", module + "/scripts/logs.sh", "full")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        ScheduledFuture<?> timeout = clock.schedule(process::destroyForcibly, 20, TimeUnit.SECONDS);
        try (InputStream input = process.getInputStream(); OutputStream output = Files.newOutputStream(target)) {
            LogTransfer.collect(input, output);
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("完整日志收集失败");
        } finally { timeout.cancel(false); process.destroyForcibly(); }
    }
    private void cleanup() throws IOException {
        long stale = System.currentTimeMillis() - 86400000L;
        List<Path> results = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(DATA, "log-export-*")) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || path.equals(status)) continue;
                if ((name.endsWith(".log") || name.matches("log-export-[a-f0-9]{32}\\.json(?:\\.tmp)?")) &&
                        Files.getLastModifiedTime(path).toMillis() < stale) Files.deleteIfExists(path);
                else if (name.matches("log-export-[a-f0-9]{32}\\.json")) results.add(path);
            }
        }
        results.sort(Comparator.comparingLong(path -> path.toFile().lastModified()));
        for (int i = 0; i < results.size() - 15; i++) Files.deleteIfExists(results.get(i));
    }
    private void run() throws Exception {
        status("preparing", "正在收集完整日志");
        try (RandomAccessFile file = new RandomAccessFile(DATA.resolve("log-export.lock").toFile(), "rw");
             FileLock lock = file.getChannel().tryLock()) {
            if (lock == null) { status("error", "已有日志导出正在进行，请先完成或取消"); return; }
            cleanup();
            Path snapshot = Files.createTempFile(DATA, "log-export-", ".log");
            ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
            try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
                snapshot(snapshot, clock);
                String user = command("/system/bin/am", "get-current-user");
                if (!user.matches("[0-9]{1,6}")) throw new IOException("无法确定当前 Android 用户");
                byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
                StringBuilder token = new StringBuilder(); for (byte b : random) token.append(String.format(Locale.ROOT, "%02x", b & 255));
                String name = "findx8s-sidekey_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".log";
                command("/system/bin/am", "start", "--user", user, "-f", "0x10000000", "-n", "cn.sidekey.menu/.LogExportActivity",
                        "--ei", "port", String.valueOf(server.getLocalPort()), "--es", "token", token.toString(), "--es", "name", name);
                status("choosing", "请选择日志保存位置");
                server.setSoTimeout(1000); long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
                while (System.nanoTime() < until) {
                    Socket client;
                    try { client = server.accept(); } catch (SocketTimeoutException waiting) { continue; }
                    try (Socket socket = client) {
                        socket.setSoTimeout(1500);
                        DataInputStream input = new DataInputStream(socket.getInputStream());
                        String credential;
                        try { credential = input.readUTF(); } catch (IOException invalid) { continue; }
                        if (!java.security.MessageDigest.isEqual(token.toString().getBytes(StandardCharsets.US_ASCII), credential.getBytes(StandardCharsets.UTF_8))) continue;
                        int operation = input.readUnsignedByte();
                        if (operation == LogTransfer.CANCEL) { status("cancelled", "已取消导出日志"); return; }
                        if (operation != LogTransfer.READ) continue;
                        status("saving", "正在写入日志文件"); socket.setSoTimeout(60000);
                        ScheduledFuture<?> deadline = clock.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, 60, TimeUnit.SECONDS);
                        try {
                            LogTransfer.send(snapshot.toFile(), socket.getOutputStream());
                            int result = input.readUnsignedByte();
                            if (result != LogTransfer.SAVED) throw new IOException("日志写入失败，请重新导出");
                            status("saved", "完整日志已保存到所选位置");
                            try { socket.getOutputStream().write(LogTransfer.SAVED); socket.getOutputStream().flush(); }
                            catch (IOException ignored) { /* 客户端已确认文件关闭，回执丢失不撤销保存结果。 */ }
                            return;
                        } finally { deadline.cancel(false); }
                    }
                }
                status("error", "保存位置选择超时，请重新导出");
            } finally { clock.shutdownNow(); Files.deleteIfExists(snapshot); }
        }
    }
    public static void main(String[] args) {
        if (android.os.Process.myUid() != 0 || args.length != 2 || !args[1].matches("[a-f0-9]{32}")) return;
        LogExportServer export = new LogExportServer(args[0], args[1]);
        try { export.run(); }
        catch (Exception error) {
            try { export.status("error", error instanceof IOException && error.getMessage() != null && !error.getMessage().contains("/") ?
                    error.getMessage() : "日志导出中断，请重试；若仍失败请修复快捷菜单组件"); }
            catch (Exception ignored) { System.err.println("无法保存日志导出结果"); }
        }
    }
}
