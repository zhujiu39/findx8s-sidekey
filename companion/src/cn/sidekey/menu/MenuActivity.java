package cn.sidekey.menu;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
import android.text.TextUtils;
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
    private int foreground, muted, surface, tileTop, tileBottom, stroke;
    private JSONArray currentItems;
    private final java.util.ArrayList<SwitchGlyph> torchSwitches = new java.util.ArrayList<>();

    private static final class Session {
        final int port;
        final String token;
        volatile int torchState = -1;
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
            currentItems = items; build(items);
            final Session current = session;
            network.execute(() -> {
                boolean valid = request(current, "PING");
                handler.post(() -> { if (session == current && !closing) {
                    if (valid) { updateSwitches(); enter(); } else closeNow();
                }});
            });
            handler.postDelayed(() -> { if (session == current) dismiss(); }, 59000);
            heartbeat(current);
        } catch (Exception exception) {
            Toast.makeText(this, "菜单不可用，请在模块 WebUI 检查配置和日志", Toast.LENGTH_LONG).show();
            closeNow();
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (closing || currentItems == null) return;
        boolean wasEntered = entered; entered = false;
        try {
            build(currentItems);
            root.post(() -> {
                placePanel(); entered = wasEntered;
                panel.setTranslationX(0); panel.setAlpha(wasEntered ? 1 : 0); shade.setAlpha(wasEntered ? 1 : 0);
                updateSwitches();
            });
        } catch (Exception exception) { closeNow(); }
    }

    private void build(JSONArray items) throws Exception {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        foreground = Color.parseColor(dark ? "#F3F4F6" : "#252932");
        muted = Color.parseColor(dark ? "#AFB3BA" : "#868D96");
        surface = Color.parseColor(dark ? "#F52A2E33" : "#F5F7F8FA");
        tileTop = Color.parseColor(dark ? "#393E45" : "#FFFFFF");
        tileBottom = Color.parseColor(dark ? "#30353B" : "#EAEDF1");
        stroke = Color.parseColor(dark ? "#545960" : "#D6DBE1");
        torchSwitches.clear();
        root = new FrameLayout(this);
        shade = new View(this); shade.setBackgroundColor(0x55000000); shade.setAlpha(0);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        root.setOnClickListener(view -> dismiss());
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(16));
        GradientDrawable backdrop = background(surface, 24); backdrop.setStroke(dp(1), stroke);
        panel.setBackground(backdrop); panel.setElevation(dp(16));
        panel.setAlpha(0); panel.setClickable(true);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("快捷菜单", 19, foreground); title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView close = text("×", 25, muted); close.setGravity(Gravity.CENTER); close.setContentDescription("关闭菜单");
        close.setOnClickListener(view -> dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44))); panel.addView(heading);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        JSONObject[] slots = new JSONObject[12]; int[] indexes = new int[12];
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i); int slot = item.getInt("slot");
            if (slot < 0 || slot >= 12 || slots[slot] != null) throw new IllegalArgumentException("无效位置");
            slots[slot] = item; indexes[slot] = i;
        }
        for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
            if (rowIndex == 2) {
                TextView subtitle = text("快捷开关", 16, foreground); subtitle.setTypeface(null, Typeface.BOLD);
                subtitle.setPadding(dp(2), dp(17), 0, dp(10)); content.addView(subtitle);
            }
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 4; column++) {
                final int slot = rowIndex * 4 + column;
                JSONObject item = slots[slot];
                String type = item == null ? "none" : item.getString("type");
                String name = item == null ? (slot < 8 ? String.format(java.util.Locale.ROOT, "APP %02d", slot + 1) : String.format(java.util.Locale.ROOT, "开关 %02d", slot - 7)) : item.getString("name");
                String icon = item == null ? "" : item.getString("icon");
                if (name.length() > 96 || icon.length() > 24) throw new IllegalArgumentException("文本过长");
                LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(3), dp(10), dp(3), dp(10));
                GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{tileTop, tileBottom});
                card.setCornerRadius(dp(13)); card.setStroke(dp(1), stroke); tile.setBackground(card);
                if (slot < 8) {
                    if (icon.isEmpty()) tile.addView(new AppGlyph(), new LinearLayout.LayoutParams(dp(32), dp(32)));
                    else { TextView symbol = text(icon, 26, foreground); symbol.setGravity(Gravity.CENTER); tile.addView(symbol, new LinearLayout.LayoutParams(-1, dp(34))); }
                }
                TextView label = text(name, 12, foreground); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END); label.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(-1, dp(34));
                if (slot < 8) labelLayout.topMargin = dp(4);
                tile.addView(label, labelLayout);
                if (slot >= 8) {
                    SwitchGlyph control = new SwitchGlyph("torch".equals(type), item == null || "none".equals(type));
                    tile.addView(control, new LinearLayout.LayoutParams(dp(38), dp(23)));
                    if ("torch".equals(type)) torchSwitches.add(control);
                }
                boolean configured = !"none".equals(type);
                tile.setAlpha(configured ? 1 : 0.65f); tile.setEnabled(configured); tile.setFocusable(configured);
                tile.setContentDescription(name + (configured ? "" : "，未配置"));
                if (configured) {
                    final int chosen = indexes[slot];
                    tile.setOnClickListener(view -> { if (!closing && entered) { selection = chosen; dismiss(); } });
                }
                LinearLayout.LayoutParams tileLayout = new LinearLayout.LayoutParams(0, slot < 8 ? dp(94) : dp(86), 1);
                if (column < 3) tileLayout.rightMargin = dp(8);
                row.addView(tile, tileLayout);
            }
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, -2);
            if (rowIndex == 1) rowLayout.topMargin = dp(8);
            content.addView(row, rowLayout);
        }
        scroll.addView(content); panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(dp(360), -2, Gravity.TOP | (right ? Gravity.RIGHT : Gravity.LEFT));
        layout.leftMargin = dp(10); layout.rightMargin = dp(10);
        root.addView(panel, layout); setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom); root.post(this::placePanel);
            return insets;
        });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if (r-l != or-ol || b-t != ob-ot) placePanel(); });
        updateSwitches();
    }

    private void updateSwitches() {
        for (SwitchGlyph control : torchSwitches) { control.state = session == null ? -1 : session.torchState; control.invalidate(); }
    }

    private final class AppGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        AppGlyph() { super(MenuActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(muted);
            float gap = dp(3), size = (getWidth() - gap) / 2f;
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
                float left = x * (size + gap), top = y * (size + gap);
                canvas.drawRoundRect(new RectF(left, top, left + size, top + size), dp(4), dp(4), paint);
            }
        }
    }

    private final class SwitchGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean stateful, empty;
        int state = -1;
        SwitchGlyph(boolean stateful, boolean empty) { super(MenuActivity.this); this.stateful = stateful; this.empty = empty; }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setColor(stateful && state == 1 ? Color.rgb(83, 221, 161) : stroke);
            canvas.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, paint);
            paint.setColor(stateful && state >= 0 ? Color.WHITE : muted);
            if (empty || (stateful && state >= 0)) {
                float radius = h / 2 - dp(3), center = state == 1 ? w - h / 2 : h / 2;
                canvas.drawCircle(center, h / 2, radius, paint);
            } else {
                // 无系统状态的单次动作显示执行符号，不伪造已开启状态。
                paint.setTextSize(dp(16)); paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(stateful ? "—" : "›", w / 2, h / 2 + dp(5), paint);
            }
        }
    }

    private void placePanel() {
        if (root == null || panel == null || root.getWidth() == 0) return;
        int available = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        int width = Math.min(dp(720), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(20));
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
                    if (valid) { updateSwitches(); heartbeat(current); } else dismiss();
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
            StringBuilder response = new StringBuilder();
            int value;
            while (response.length() < 16 && (value = input.read()) != -1 && value != '\n') response.append((char)value);
            String result = response.toString();
            if (result.startsWith("OK ")) {
                current.torchState = Integer.parseInt(result.substring(3));
                return current.torchState >= -1 && current.torchState <= 1;
            }
            return "OK".equals(result);
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
