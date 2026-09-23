package cn.sidekey;

import java.io.*;
import java.security.MessageDigest;

/** 日志通过本机流传输，长度与 SHA-256 校验通过后才允许报告保存成功。 */
public final class LogTransfer {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final int MAGIC = 0x534b4c31;
    public static final int READ = 1, CANCEL = 2, SAVED = 3, FAILED = 4;
    private LogTransfer() { }
    public static long collect(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16384]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_BYTES) throw new IOException("日志超过 64 MiB，导出已停止，未截取内容");
            output.write(buffer, 0, count);
        }
        return total;
    }
    public static byte[] digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }
    public static void send(File file, OutputStream stream) throws Exception {
        long length = file.length();
        if (length <= 0 || length > MAX_BYTES) throw new IOException("日志文件长度无效");
        DataOutputStream output = new DataOutputStream(stream);
        output.writeInt(MAGIC); output.writeLong(length); output.write(digest(file));
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384]; long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new EOFException("日志快照不完整");
                output.write(buffer, 0, count); remaining -= count;
            }
        }
        output.flush();
    }
    public static long receive(InputStream stream, OutputStream output) throws Exception {
        DataInputStream input = new DataInputStream(stream);
        if (input.readInt() != MAGIC) throw new IOException("日志传输格式无效");
        long length = input.readLong();
        if (length <= 0 || length > MAX_BYTES) throw new IOException("日志文件长度无效");
        byte[] expected = new byte[32]; input.readFully(expected);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16384]; long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("日志传输中断，文件不完整");
            output.write(buffer, 0, count); digest.update(buffer, 0, count); remaining -= count;
        }
        if (!MessageDigest.isEqual(expected, digest.digest())) throw new IOException("日志完整性校验失败");
        output.flush();
        return length;
    }
}
