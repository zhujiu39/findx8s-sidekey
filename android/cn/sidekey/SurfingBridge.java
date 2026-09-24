package cn.sidekey;

import android.os.SystemClock;
import android.util.JsonReader;
import android.util.Xml;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.*;

/** Root 侧只读取本机流量统计，启停交给 Surfing 自己的服务处理。 */
public final class SurfingBridge {
    private static final Path MODULE = Paths.get("/data/adb/modules/Surfing");
    private static final Path BOX = Paths.get("/data/adb/box_bll");
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final String user;
    private String core = "clash", address = "http://127.0.0.1:9090", secret = "";
    private HttpURLConnection stream;
    private BufferedReader traffic;
    private long fallbackAt, fallbackUp = -1, fallbackDown = -1;
    private String lastProblem = "";

    private SurfingBridge() throws Exception {
        user = command("/system/bin/am", "get-current-user");
        if (!user.matches("[0-9]{1,6}")) throw new IOException("无法确定当前 Android 用户");
        Path config = BOX.resolve("scripts/box.config");
        if (Files.isRegularFile(config)) {
            for (String line : readSmall(config, 65536).split("\n")) {
                if (line.trim().startsWith("bin_name=")) {
                    String value = scalar(line.trim().substring(9));
                    if (value.matches("clash|mihomo|sing-box|xray|v2ray|hysteria")) core = value;
                    else throw new IOException("Surfing 核心配置无法识别");
                    break;
                }
            }
        }
        Path preferences = Paths.get("/data/user", user, "com.github.surfing/shared_prefs/ApiSettingsPrefs.xml");
        if (Files.isRegularFile(preferences)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(readSmall(preferences, 32768)));
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG || !"string".equals(parser.getName())) continue;
                String name = parser.getAttributeValue(null, "name");
                if ("api_url".equals(name)) address = parser.nextText().trim();
                else if ("api_secret".equals(name)) secret = parser.nextText();
            }
        } else {
            Path yaml = BOX.resolve(core + "/config.yaml");
            if (Files.isRegularFile(yaml)) {
                for (String line : readSmall(yaml, 2 * 1024 * 1024).split("\n")) {
                    if (line.startsWith("external-controller:")) address = "http://" + scalar(line.substring(20));
                    else if (line.startsWith("secret:")) secret = scalar(line.substring(7));
                }
            }
        }
        URI uri = new URI(address);
        String host = uri.getHost();
        if ("0.0.0.0".equals(host) || "localhost".equalsIgnoreCase(host)) host = "127.0.0.1";
        if ("[::]".equals(host) || "::".equals(host)) host = "[::1]";
        if (!"http".equals(uri.getScheme()) || !("127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host)) ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
                !(uri.getPath().isEmpty() || "/".equals(uri.getPath())) || secret.length() > 4096 || secret.indexOf('\n') >= 0 || secret.indexOf('\r') >= 0)
            throw new IOException("Surfing API 请使用本机 HTTP 地址");
        int port = uri.getPort() < 0 ? 80 : uri.getPort();
        if (port < 1 || port > 65535) throw new IOException("Surfing API 端口无效");
        address = "http://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
    }

    private static String readSmall(Path path, int limit) throws IOException {
        if (Files.size(path) > limit) throw new IOException("Surfing 配置超过读取上限");
        try (InputStream input = Files.newInputStream(path); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > limit) throw new IOException("Surfing 配置超过读取上限");
                bytes.write(buffer, 0, count);
            }
            return bytes.toString("UTF-8");
        }
    }

    private static String scalar(String text) throws Exception {
        text = text.trim();
        if (text.startsWith("\"")) {
            boolean escaped = false;
            for (int i = 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' && !escaped) return new org.json.JSONArray("[" + text.substring(0, i + 1) + "]").getString(0);
                escaped = c == '\\' && !escaped;
            }
            throw new IOException("Surfing 配置引号未闭合");
        }
        if (text.startsWith("'")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') { out.append('\''); i++; }
                    else return out.toString();
                } else out.append(c);
            }
            throw new IOException("Surfing 配置引号未闭合");
        }
        return text.replaceFirst("\\s+#.*$", "").trim();
    }

    private String command(String... arguments) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        ScheduledFuture<?> timeout = timer.schedule(process::destroyForcibly, 4, TimeUnit.SECONDS);
        try (InputStream input = process.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[1024]; int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() + count > 8192) throw new IOException("Surfing 控制服务响应过长");
                output.write(bytes, 0, count);
            }
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("Surfing 系统命令失败或超时");
            String text = output.toString("UTF-8").trim();
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("exception") || lower.contains("failure") || lower.contains("not found"))
                throw new IOException("Surfing 控制服务未响应，请确认 SurfingTile 已安装并获 Root 授权");
            return text;
        } finally { timeout.cancel(false); process.destroyForcibly(); }
    }

    private boolean coreRunning() throws IOException {
        Path pidFile = BOX.resolve("run/" + core + ".pid");
        if (Files.isRegularFile(pidFile)) {
            String pid = readSmall(pidFile, 32).trim();
            if (pid.matches("[0-9]{1,9}") && ownsProcess(Paths.get("/proc", pid))) return true;
        }
        // PID 文件可能在热重载后滞后，只接受 Surfing 工具目录中的实际可执行文件。
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(Paths.get("/proc"))) {
            for (Path entry : entries) if (entry.getFileName().toString().matches("[0-9]+") && ownsProcess(entry)) return true;
        }
        return false;
    }

    private boolean ownsProcess(Path entry) {
        try {
            String executable = Files.readSymbolicLink(entry.resolve("exe")).toString().replace(" (deleted)", "");
            return executable.equals(BOX.resolve("bin/" + core).toString());
        } catch (IOException ignored) { return false; }
    }

    private String state() throws Exception {
        if (!Files.isRegularFile(MODULE.resolve("module.prop"))) return "missing";
        if (Files.exists(MODULE.resolve("remove"))) return "removing";
        if (Files.exists(BOX.resolve("manual"))) return "manual";
        boolean running = coreRunning(), disabled = Files.exists(MODULE.resolve("disable"));
        String reported = command("/system/bin/settings", "get", "global", "surfing");
        if (running && !disabled && "已开启".equals(reported)) return "on";
        if (!running && disabled && "已关闭".equals(reported)) return "off";
        if (reported.contains("正在") || reported.contains("中"))
            return reported.contains("关") || reported.contains("停止") ? "stopping" : "starting";
        if (disabled && running) return "stopping";
        if (!disabled && !running) return "stopped";
        return running ? "on" : "off";
    }

    private HttpURLConnection connect(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(address + path).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(1200); connection.setReadTimeout(2200);
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (!secret.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + secret);
        try {
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new IOException("本机 API 密钥不匹配，请检查 SurfingTile 的 API 设置");
            if (status != 200) throw new IOException("本机 API 返回 HTTP " + status);
            return connection;
        } catch (Exception error) { connection.disconnect(); throw error; }
    }

    private static long counter(JSONObject data, String key) throws Exception {
        Object value = data.opt(key);
        if (!(value instanceof Number) || value instanceof Double || value instanceof Float || ((Number)value).longValue() < 0)
            throw new IOException("流量接口缺少有效的 " + key);
        return ((Number)value).longValue();
    }

    private JSONObject sample() throws Exception {
        if (traffic == null) {
            stream = connect("/traffic");
            traffic = new BufferedReader(new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8));
        }
        StringBuilder line = new StringBuilder(); int value;
        while ((value = traffic.read()) != -1 && value != '\n') {
            if (line.length() >= 2048) throw new IOException("流量接口响应过长");
            line.append((char)value);
        }
        if (value == -1) throw new EOFException("流量连接已关闭");
        JSONObject source = new JSONObject(line.toString());
        JSONObject result = new JSONObject().put("up", counter(source, "up")).put("down", counter(source, "down"));
        if (source.has("upTotal") && source.has("downTotal"))
            return result.put("uploadTotal", counter(source, "upTotal")).put("downloadTotal", counter(source, "downTotal"));
        // 旧核心的 /traffic 仅有速率；只取 /connections 顶层计数，不保存连接列表。
        if (SystemClock.elapsedRealtime() >= fallbackAt) {
            HttpURLConnection connection = connect("/connections");
            ScheduledFuture<?> timeout = timer.schedule(connection::disconnect, 2500, TimeUnit.MILLISECONDS);
            try (JsonReader reader = new JsonReader(new InputStreamReader(new FilterInputStream(connection.getInputStream()) {
                private int remaining = 1024 * 1024;
                @Override public int read() throws IOException { if (--remaining < 0) throw new IOException("统计响应过长"); return super.read(); }
                @Override public int read(byte[] b, int offset, int length) throws IOException {
                    if (remaining <= 0) throw new IOException("统计响应过长");
                    int count = in.read(b, offset, Math.min(length, remaining)); if (count > 0) remaining -= count; return count;
                }
            }, StandardCharsets.UTF_8))) {
                long up = -1, down = -1; reader.beginObject();
                while (reader.hasNext() && (up < 0 || down < 0)) {
                    String key = reader.nextName();
                    if ("uploadTotal".equals(key)) up = reader.nextLong();
                    else if ("downloadTotal".equals(key)) down = reader.nextLong();
                    else reader.skipValue();
                }
                if (up < 0 || down < 0) throw new IOException("累计流量不可用");
                fallbackUp = up; fallbackDown = down; fallbackAt = SystemClock.elapsedRealtime() + 2500;
            } finally { timeout.cancel(false); connection.disconnect(); }
        }
        return result.put("uploadTotal", fallbackUp).put("downloadTotal", fallbackDown);
    }

    private void disconnect() {
        if (stream != null) stream.disconnect();
        if (traffic != null) try { traffic.close(); } catch (IOException ignored) { }
        traffic = null; stream = null; fallbackAt = 0; fallbackUp = fallbackDown = -1;
    }

    private static void emit(JSONObject result) {
        System.out.println(result.toString()); System.out.flush();
    }

    private void watch() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 65000;
        while (SystemClock.elapsedRealtime() < deadline) {
            long started = SystemClock.elapsedRealtime();
            JSONObject result = new JSONObject().put("state", "unknown");
            try {
                String state = state(); result.put("state", state);
                if ("on".equals(state)) result.put("traffic", sample()); else disconnect();
                lastProblem = "";
            } catch (Exception error) {
                disconnect(); String message = friendly(error);
                result.put("message", message);
                if (!message.equals(lastProblem)) { log(message); lastProblem = message; }
            }
            emit(result);
            if (System.out.checkError()) return;
            long delay = 1000 - (SystemClock.elapsedRealtime() - started);
            if (delay > 0) Thread.sleep(delay);
        }
    }

    private void toggle() throws Exception {
        String before = state();
        if (!("on".equals(before) || "off".equals(before) || "stopped".equals(before)))
            throw new IOException("Surfing 当前不可切换：" + before);
        boolean enable = !"on".equals(before);
        log(enable ? "提交开启请求" : "提交关闭请求");
        command("/system/bin/am", "startservice", "--user", user, "-n", "com.github.surfing/.service.SurfingTileService",
                "-a", "com.github.surfing.service.ACTION_" + (enable ? "START" : "STOP") + "_CLASH");
        long deadline = SystemClock.elapsedRealtime() + 42000;
        int stable = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(700);
            String observed = state();
            boolean ready = enable ? "on".equals(observed) : "off".equals(observed);
            if (ready && ++stable >= 2) { log(enable ? "已确认开启" : "已确认关闭"); return; }
            if (!ready) stable = 0;
        }
        throw new IOException("Surfing 启停超时，请查看 SurfingTile 和模块运行状态");
    }

    private static String friendly(Exception error) {
        if (error instanceof SocketTimeoutException) return "本机流量接口超时";
        if (error instanceof ConnectException) return "本机流量接口未就绪";
        if (error instanceof IOException && error.getMessage() != null && !error.getMessage().contains("http") && !error.getMessage().contains("127.0.0.1"))
            return error.getMessage().substring(0, Math.min(100, error.getMessage().length()));
        return "Surfing 数据读取失败（" + error.getClass().getSimpleName() + "）";
    }

    private static void log(String text) {
        System.err.println("[" + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new java.util.Date()) + "] Surfing：" + text);
    }

    public static void main(String[] args) {
        SurfingBridge bridge = null; int result = 0;
        try {
            if (args.length != 1 || !("watch".equals(args[0]) || "toggle".equals(args[0]))) throw new IllegalArgumentException("操作无效");
            bridge = new SurfingBridge();
            if ("watch".equals(args[0])) bridge.watch(); else bridge.toggle();
        } catch (Exception error) {
            result = 1; String message = friendly(error); log(message);
            if (args.length == 1 && "watch".equals(args[0])) try { emit(new JSONObject().put("state", "unknown").put("message", message)); } catch (Exception ignored) { }
        } finally {
            if (bridge != null) { bridge.disconnect(); bridge.timer.shutdownNow(); }
        }
        System.exit(result);
    }
}
