package cn.sidekey.menu;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 仅呈现本次菜单的名称和索引；Root 命令保留在模块守护进程。 */
public final class MenuActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private LinearLayout panel;
    private View shade;
    private Session session;
    private boolean closing, dispatched, entered;
    private int selection = -1, position;
    private boolean right;
    private int foreground, muted, surface;

    private static final class Session {
        final int port;
        final String token;
        Session(int port, String token) { this.port = port; this.token = token; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // targetSdk 35 使用系统边到边布局；内容通过 WindowInsets 避让状态栏和挖孔。
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::dismiss);
        open(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (session != null && !dispatched) send(session, "CLOSE", false);
        handler.removeCallbacksAndMessages(null);
        if (panel != null) panel.animate().cancel();
        setIntent(intent); open(intent);
    }

    private void open(Intent intent) {
        closing = false; dispatched = false; entered = false; selection = -1;
        try {
            String token = intent.getStringExtra("token");
            int port = intent.getIntExtra("port", 0);
            if (token == null || !token.matches("[0-9a-f]{64}") || port < 1 || port > 65535)
                throw new IllegalArgumentException("无效会话");
            session = new Session(port, token);
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            if (keyguard == null || keyguard.isKeyguardLocked()) { closeNow(); return; }
            String source = intent.getStringExtra("items");
            if (source == null || source.length() > 10000) throw new IllegalArgumentException("菜单过长");
            JSONArray items = new JSONArray(source);
            if (items.length() > 12) throw new IllegalArgumentException("菜单项过多");
            right = "right".equals(intent.getStringExtra("side"));
            position = Math.max(10, Math.min(90, intent.getIntExtra("position", 35)));
            build(items);
            final Session current = session;
            network.execute(() -> {
                boolean valid = request(current, "PING");
                handler.post(() -> { if (session == current && !closing) {
                    if (valid) enter(); else closeNow();
                }});
            });
            handler.postDelayed(() -> { if (session == current) dismiss(); }, 59000);
            heartbeat(current);
        } catch (Exception exception) {
            Toast.makeText(this, "菜单不可用，请在模块 WebUI 检查配置和日志", Toast.LENGTH_LONG).show();
            closeNow();
        }
    }

    private void build(JSONArray items) throws Exception {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        foreground = Color.parseColor(dark ? "#EEF2EF" : "#223A30");
        muted = Color.parseColor(dark ? "#ABB9B0" : "#748078");
        surface = Color.parseColor(dark ? "#F22A342E" : "#F7FAFCF9");
        root = new FrameLayout(this);
        shade = new View(this); shade.setBackgroundColor(0x55000000); shade.setAlpha(0);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        root.setOnClickListener(view -> dismiss());
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(16));
        panel.setBackground(background(surface, 26)); panel.setElevation(dp(16));
        panel.setAlpha(0); panel.setClickable(true);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("快捷菜单", 17, foreground); title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView close = text("×", 26, muted); close.setGravity(Gravity.CENTER); close.setContentDescription("关闭菜单");
        close.setOnClickListener(view -> dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48))); panel.addView(heading);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        if (items.length() == 0) {
            TextView empty = text("这里还空着\n去模块 WebUI 添加你的捷径", 14, muted);
            empty.setPadding(dp(2), dp(24), dp(2), dp(28)); empty.setLineSpacing(dp(6), 1);
            list.addView(empty);
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String name = item.getString("name"), icon = item.getString("icon");
            if (name.length() > 96 || icon.length() > 24) throw new IllegalArgumentException("文本过长");
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8)); row.setMinimumHeight(dp(60));
            android.util.TypedValue value = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
            row.setBackgroundResource(value.resourceId);
            if (!icon.isEmpty()) {
                TextView symbol = text(icon, 23, foreground); symbol.setGravity(Gravity.CENTER);
                row.addView(symbol, new LinearLayout.LayoutParams(dp(42), dp(44)));
            }
            TextView label = text(name, 16, foreground); label.setMaxLines(3);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            row.setContentDescription(name); row.setFocusable(true);
            final int chosen = index;
            row.setOnClickListener(view -> { if (!closing && entered) { selection = chosen; dismiss(); } });
            list.addView(row);
        }
        scroll.addView(list); panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(dp(284), -2, Gravity.TOP | (right ? Gravity.RIGHT : Gravity.LEFT));
        layout.leftMargin = dp(10); layout.rightMargin = dp(10);
        root.addView(panel, layout); setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom); root.post(this::placePanel);
            return insets;
        });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if (r-l != or-ol || b-t != ob-ot) placePanel(); });
    }

    private void placePanel() {
        if (root == null || panel == null || root.getWidth() == 0) return;
        int available = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        int width = Math.min(dp(284), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(40));
        int maxHeight = Math.max(dp(48), available - dp(28));
        panel.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY),
                      View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)panel.getLayoutParams();
        params.width = width; params.height = panel.getMeasuredHeight();
        params.topMargin = Math.max(dp(12), Math.min(available - params.height - dp(12), available * position / 100 - params.height / 2));
        panel.setLayoutParams(params);
        if (!entered) panel.setTranslationX((right ? 1 : -1) * (width + dp(30)));
    }

    private void enter() {
        if (closing || entered) return;
        if (root.getWidth() == 0) { root.post(this::enter); return; }
        placePanel(); entered = true;
        shade.animate().alpha(1).setDuration(240).start();
        panel.animate().translationX(0).alpha(1).setDuration(280)
            .setInterpolator(new PathInterpolator(0.2f, 0, 0, 1)).start();
        panel.announceForAccessibility("快捷菜单");
    }

    private void dismiss() {
        if (closing) return;
        closing = true; handler.removeCallbacksAndMessages(null);
        if (panel == null) { closeNow(); return; }
        shade.animate().alpha(0).setDuration(160).start();
        panel.animate().translationX((right ? 1 : -1) * (panel.getWidth() + dp(30))).alpha(0)
            .setDuration(180).withEndAction(this::closeNow).start();
    }

    private void closeNow() {
        closing = true; handler.removeCallbacksAndMessages(null);
        finish();
    }


    @Override protected void onStop() {
        super.onStop();
        // 等透明 Activity 完全离开前台后才执行动作，避免截屏或返回键作用在菜单上。
        dispatch();
        if (!isFinishing()) closeNow();
    }

    private void dispatch() {
        if (session == null || dispatched) return;
        dispatched = true;
        send(session, selection < 0 ? "CLOSE" : "SELECT " + selection, selection >= 0);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); dispatch(); network.shutdown(); super.onDestroy();
    }

    private void heartbeat(Session current) {
        handler.postDelayed(() -> {
            if (session != current || closing) return;
            network.execute(() -> {
                boolean valid = request(current, "PING");
                handler.post(() -> { if (session == current && !closing) {
                    if (valid) heartbeat(current); else dismiss();
                }});
            });
        }, 2000);
    }

    private void send(Session current, String command, boolean report) {
        network.execute(() -> {
            boolean accepted = request(current, command);
            if (report && !accepted) handler.post(() -> Toast.makeText(getApplicationContext(),
                "菜单会话已失效或服务未响应，请重新按侧键", Toast.LENGTH_LONG).show());
        });
    }

    private static boolean request(Session current, String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), current.port), 1000);
            socket.setSoTimeout(1500);
            int separator = command.indexOf(' ');
            String line = separator < 0 ? command + " " + current.token :
                command.substring(0, separator) + " " + current.token + command.substring(separator);
            socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            InputStream input = socket.getInputStream();
            return input.read() == 'O' && input.read() == 'K' && input.read() == '\n';
        } catch (Exception ignored) { return false; }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
}
