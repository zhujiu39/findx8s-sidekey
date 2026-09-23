import cn.sidekey.LogTransfer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

public final class LogTransferTest {
    private static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
    static void rejects(Action action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        Path path = Files.createTempFile("sidekey-log-test-", ".log");
        try {
            StringBuilder content = new StringBuilder("完整日志开头\n");
            for (int i = 0; i < 5000; i++) content.append("米家读回，目标=0，实际=1：").append(i).append('\n');
            content.append("完整日志结尾\n");
            byte[] original = content.toString().getBytes(StandardCharsets.UTF_8);
            check(original.length > 65536, "fixture exceeds old per-file truncation");
            Files.write(path, original);
            ByteArrayOutputStream wire = new ByteArrayOutputStream(); LogTransfer.send(path.toFile(), wire);
            byte[] packet = wire.toByteArray(); ByteArrayOutputStream restored = new ByteArrayOutputStream();
            long length = LogTransfer.receive(new ByteArrayInputStream(packet), restored);
            check(length == original.length && Arrays.equals(restored.toByteArray(), original), "full Unicode logs survive length/hash framing without truncation");
            byte[] corrupt = packet.clone(); corrupt[corrupt.length - 10] ^= 1;
            rejects(() -> LogTransfer.receive(new ByteArrayInputStream(corrupt), new ByteArrayOutputStream()), "checksum failure must not succeed");
            rejects(() -> LogTransfer.receive(new ByteArrayInputStream(Arrays.copyOf(packet, packet.length - 1)), new ByteArrayOutputStream()), "partial transfer must not succeed");
            rejects(() -> LogTransfer.receive(new ByteArrayInputStream(Arrays.copyOf(packet, 4)), new ByteArrayOutputStream()), "partial header must not succeed");
            byte[] wrong = packet.clone(); wrong[0] = 0;
            rejects(() -> LogTransfer.receive(new ByteArrayInputStream(wrong), new ByteArrayOutputStream()), "invalid protocol must not succeed");
            for (long invalid : new long[]{-1, 0, LogTransfer.MAX_BYTES + 1}) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(bytes);
                data.writeInt(LogTransfer.MAGIC); data.writeLong(invalid);
                rejects(() -> LogTransfer.receive(new ByteArrayInputStream(bytes.toByteArray()), new ByteArrayOutputStream()), "invalid length must be rejected before allocation");
            }
            rejects(() -> LogTransfer.receive(new ByteArrayInputStream(packet), new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("synthetic storage full"); }
            }), "storage failure must not succeed");
            InputStream oversized = new InputStream() {
                long remaining = LogTransfer.MAX_BYTES + 1;
                @Override public int read() { return -1; }
                @Override public int read(byte[] buffer) {
                    if (remaining == 0) return -1;
                    int size = (int) Math.min(buffer.length, remaining); remaining -= size; return size;
                }
            };
            rejects(() -> LogTransfer.collect(oversized, new OutputStream() {
                @Override public void write(int value) { }
                @Override public void write(byte[] buffer, int offset, int length) { }
            }), "oversized collection must fail instead of silently truncating");
            Files.write(path, new byte[0]);
            rejects(() -> LogTransfer.send(path.toFile(), new ByteArrayOutputStream()), "empty snapshot must not succeed");
        } finally { Files.deleteIfExists(path); }
        System.out.println("PASS: " + checks + " log transfer checks, full UTF-8 logs, SHA-256, truncated streams, invalid sizes and storage failure");
    }
}
