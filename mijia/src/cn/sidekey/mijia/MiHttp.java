// SPDX-License-Identifier: GPL-3.0-or-later
package cn.sidekey.mijia;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

final class MiHttp implements AutoCloseable {
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mijia-timeout"); thread.setDaemon(true); return thread;
    });
    private final long deadline;
    private final ScheduledFuture<?> alarm;
    private volatile boolean cancelled;
    private volatile HttpURLConnection active;
    MiHttp(long milliseconds) {
        deadline = System.nanoTime() + milliseconds * 1000000;
        alarm = CLOCK.schedule(this::cancel, milliseconds, TimeUnit.MILLISECONDS);
    }
    void check() throws Failure {
        if (cancelled || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
            throw new Failure("TIMEOUT", "米家请求超时，请稍后重试");
    }
    synchronized void pause(long milliseconds) throws Failure, InterruptedException {
        long until = Math.min(deadline, System.nanoTime() + milliseconds * 1000000);
        for (;;) {
            check();
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }
    void cancel() {
        synchronized (this) { cancelled = true; notifyAll(); }
        HttpURLConnection connection = active; if (connection != null) connection.disconnect();
    }
    public void close() { alarm.cancel(false); cancel(); }
    static URL allowed(String text) throws Exception {
        URL url = new URL(text); String host = url.getHost().toLowerCase(Locale.ROOT);
        boolean xiaomi = host.equals("account.xiaomi.com") || host.endsWith(".account.xiaomi.com")
                || host.equals("xiaomi.com") || host.endsWith(".xiaomi.com")
                || host.equals("mi.com") || host.endsWith(".mi.com")
                || host.equals("mijia.tech") || host.endsWith(".mijia.tech");
        if (!url.getProtocol().equals("https") || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || !(xiaomi || host.equals("home.miot-spec.com") || host.equals("miot-spec.org")))
            throw new Failure("PROTOCOL", "服务返回了无法识别的地址");
        return url;
    }
    static String form(Map<String, String> values) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return result.toString();
    }
    static Map<String, String> query(String location) throws Exception {
        Map<String, String> result = new LinkedHashMap<>(); String query = allowed(location).getQuery();
        if (query == null) throw new Failure("LOGIN", "登录响应缺少必要参数");
        for (String pair : query.split("&")) {
            String[] values = pair.split("=", 2);
            result.put(URLDecoder.decode(values[0], "UTF-8"), URLDecoder.decode(values.length > 1 ? values[1] : "", "UTF-8"));
        }
        return result;
    }
    byte[] request(String address, Map<String, String> headers, String body, CookieManager cookies, int readTimeout) throws Exception {
        URL url = allowed(address);
        for (int redirect = 0; redirect < 6; redirect++) {
            check(); HttpURLConnection connection = (HttpURLConnection) url.openConnection(); active = connection;
            try {
                int remaining = (int) Math.max(1, (deadline - System.nanoTime()) / 1000000);
                connection.setConnectTimeout(Math.min(8000, remaining));
                connection.setReadTimeout(Math.min(readTimeout, remaining));
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept-Encoding", "identity");
                for (Map.Entry<String, String> h : headers.entrySet()) connection.setRequestProperty(h.getKey(), h.getValue());
                if (cookies != null) for (Map.Entry<String, List<String>> entry : cookies.get(url.toURI(), Collections.emptyMap()).entrySet())
                    if (!entry.getValue().isEmpty()) connection.setRequestProperty(entry.getKey(), String.join("; ", entry.getValue()));
                if (body != null) {
                    connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                }
                int status = connection.getResponseCode(); check();
                if (cookies != null) cookies.put(url.toURI(), connection.getHeaderFields());
                if (status >= 300 && status <= 399) {
                    if (body != null) throw new Failure("PROTOCOL", "控制接口要求重定向，已停止发送");
                    URL next = allowed(new URL(url, connection.getHeaderField("Location")).toString());
                    if (!next.getHost().equalsIgnoreCase(url.getHost())) {
                        headers = new LinkedHashMap<>(headers); headers.remove("Cookie");
                    }
                    url = next; continue;
                }
                if (status == 401 || status == 403) throw new Failure("AUTH", "米家登录已失效，请重新登录");
                if (status != 200) throw new Failure("HTTP_" + status, "米家服务 HTTP " + status);
                try (InputStream raw = connection.getInputStream(); InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw) {
                    return read(input, 2 * 1024 * 1024);
                }
            } catch (SocketTimeoutException error) { throw new Failure("TIMEOUT", "米家请求超时，请稍后重试"); }
            finally { connection.disconnect(); active = null; }
        }
        throw new Failure("PROTOCOL", "服务重定向次数过多");
    }
    byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int size;
        while ((size = input.read(chunk)) >= 0) {
            check(); if (bytes.size() + size > limit) throw new Failure("LIMIT", "米家响应超过处理范围");
            bytes.write(chunk, 0, size);
        }
        return bytes.toByteArray();
    }
    String get(String url, Map<String, String> headers, CookieManager cookies, int timeout) throws Exception {
        return new String(request(url, headers, null, cookies, timeout), StandardCharsets.UTF_8);
    }
}
