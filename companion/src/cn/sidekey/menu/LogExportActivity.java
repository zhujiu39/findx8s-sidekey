package cn.sidekey.menu;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import cn.sidekey.LogTransfer;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** 系统文件选择器决定目标位置；日志只写入用户本次创建的文档。 */
@SuppressWarnings("deprecation")
public final class LogExportActivity extends Activity {
    private static final int CREATE_LOG = 10;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    private final CancellationSignal cancellation = new CancellationSignal();
    private volatile Socket socket;
    private volatile ParcelFileDescriptor document;
    private volatile boolean terminal;
    private String token, name;
    private int port;
    private boolean saving;
    private TextView message;

    @Override public void onCreate(Bundle state) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        message = new TextView(this); message.setGravity(Gravity.CENTER); message.setTextSize(16);
        message.setPadding(32, 32, 32, 32); message.setText("请选择完整日志的保存位置"); setContentView(message);
        port = getIntent().getIntExtra("port", -1); token = getIntent().getStringExtra("token"); name = getIntent().getStringExtra("name");
        if (port < 1024 || port > 65535 || token == null || !token.matches("[a-f0-9]{64}") ||
                name == null || !name.matches("findx8s-sidekey_[0-9]{8}_[0-9]{6}\\.log")) {
            finishWith("日志导出会话无效，请从 WebUI 重新导出"); return;
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::cancel);
        if (state != null) {
            if (state.getBoolean("saving")) finishWith("日志导出曾中断，请重新导出并检查目标文件");
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(create, CREATE_LOG); }
        catch (Exception error) { cancelRemote(); finishWith("无法打开系统文件选择器，请检查系统文件管理器"); }
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("saving", saving); super.onSaveInstanceState(state); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CREATE_LOG || terminal || saving) return;
        Uri uri = data == null ? null : data.getData();
        if (result != RESULT_OK || uri == null) { cancel(); return; }
        if (!"content".equals(uri.getScheme())) { cancelRemote(); finishWith("文件管理器返回了无效的保存位置"); return; }
        saving = true; message.setText("正在保存完整日志…");
        worker.execute(() -> save(uri));
    }
    private Socket connect(int operation) throws IOException {
        Socket current = new Socket(); socket = current;
        current.connect(new InetSocketAddress("127.0.0.1", port), 4000); current.setSoTimeout(10000);
        DataOutputStream output = new DataOutputStream(current.getOutputStream());
        output.writeUTF(token); output.writeByte(operation); output.flush();
        return current;
    }
    private void save(Uri uri) {
        boolean complete = false;
        ScheduledFuture<?> timeout = clock.schedule(this::closeIo, 55, TimeUnit.SECONDS);
        try (Socket current = connect(LogTransfer.READ)) {
            document = getContentResolver().openFileDescriptor(uri, "w", cancellation);
            if (document == null) throw new IOException("无法打开所选日志文件");
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(document)) {
                LogTransfer.receive(current.getInputStream(), output);
            }
            document = null; complete = true;
            current.getOutputStream().write(LogTransfer.SAVED); current.getOutputStream().flush();
            if (current.getInputStream().read() != LogTransfer.SAVED) throw new IOException("保存回执未确认");
            finishWith("完整日志已保存");
        } catch (Exception error) {
            if (!complete) {
                boolean removed = false;
                try { removed = DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { }
                finishWith(removed ? "日志保存失败，未完成的文件已清理，请重试" : "日志保存失败，请删除可能不完整的文件后重试");
            } else finishWith("完整日志已保存，但保存回执未确认");
        } finally { timeout.cancel(false); closeIo(); }
    }
    private void cancelRemote() {
        new Thread(() -> {
            try (Socket ignored = connect(LogTransfer.CANCEL)) { }
            catch (IOException ignored) { /* 导出会话已退出时无需继续等待，Root 端会按超时清理。 */ }
        }, "sidekey-log-cancel").start();
    }
    private void cancel() {
        if (saving) { closeIo(); return; }
        cancelRemote(); finishWith("已取消导出日志");
    }
    private void closeIo() {
        cancellation.cancel();
        Socket current = socket; if (current != null) try { current.close(); } catch (IOException ignored) { }
        ParcelFileDescriptor opened = document; if (opened != null) try { opened.close(); } catch (IOException ignored) { }
    }
    private void finishWith(String text) {
        runOnUiThread(() -> {
            if (terminal) return;
            terminal = true; Toast.makeText(this, text, Toast.LENGTH_LONG).show(); finish();
        });
    }
    @Override protected void onDestroy() {
        if (saving) closeIo();
        worker.shutdownNow(); clock.shutdownNow(); super.onDestroy();
    }
}
