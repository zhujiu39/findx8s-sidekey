// SPDX-License-Identifier: GPL-3.0-or-later
package cn.sidekey.mijia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.json.JSONObject;

final class PrivateStore {
    final Path root;
    PrivateStore(Path root) throws Exception {
        this.root = root;
        if (Files.isSymbolicLink(root)) throw new IOException("unsafe directory");
        Files.createDirectories(root);
        permissions(root, true);
    }
    private static void permissions(Path path, boolean directory) throws IOException {
        // Android/Linux 必须严格设置权限；Windows 仅用于本地主机测试。
        if (!System.getProperty("os.name", "").startsWith("Windows"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
    private Path path(String name) throws IOException {
        if (!name.matches("[a-zA-Z0-9_.-]{1,180}")) throw new IOException("invalid name");
        Path path = root.resolve(name);
        if (Files.isSymbolicLink(path)) throw new IOException("unsafe file");
        return path;
    }
    synchronized JSONObject read(String name) throws Exception {
        Path path = path(name);
        if (!Files.exists(path)) return new JSONObject();
        if (Files.size(path) > 2 * 1024 * 1024) throw new IOException("file too large");
        permissions(path, false);
        return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }
    synchronized void write(String name, JSONObject data) throws Exception {
        Path destination = path(name), temporary = Files.createTempFile(root, "write-", ".tmp");
        try {
            permissions(temporary, false);
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("file too large");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(temporary.toFile())) {
                output.write(bytes); output.getFD().sync();
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    synchronized void delete(String name) throws Exception { Files.deleteIfExists(path(name)); }
    synchronized void debug(String message) {
        try {
            Path log = path("debug.log");
            if (Files.exists(log) && Files.size(log) > 32768)
                Files.move(log, path("debug.log.1"), StandardCopyOption.REPLACE_EXISTING);
            String line = String.format(java.util.Locale.ROOT, "[%tF %<tT] %s%n", System.currentTimeMillis(), message);
            Files.write(log, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            permissions(log, false);
        } catch (IOException error) { System.err.println("米家调试日志写入失败"); }
    }
}
