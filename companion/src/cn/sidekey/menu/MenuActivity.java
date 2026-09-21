package cn.sidekey.menu;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.view.ViewGroup;
import java.io.ByteArrayOutputStream;
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
    private MenuPanelHost root;
    private LinearLayout panel;
    private ListView scroll;
    private Session session;
    private boolean closing, dispatched, entered;
    private int selection = -1, position, widthDp, appGapDp;
    private boolean right;
    private int foreground, muted, surface, tileTop, stroke;
    private JSONArray currentItems;

    private static final class Session {
        final int port;
        final String token;
        volatile int torchState = -1;
        Session(int port, String token) { this.port = port; this.token = token; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0f);
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
            right = "right".equals(intent.getStringExtra("side"));
            position = Math.max(10, Math.min(90, intent.getIntExtra("position", 35)));
            widthDp = Math.max(160, Math.min(360, intent.getIntExtra("width", 196)));
            appGapDp = Math.max(0, Math.min(32, intent.getIntExtra("gap", 12)));
            final Session current = session;
            network.execute(() -> {
                try {
                    JSONArray items = loadItems(current);
                    handler.post(() -> { if (session == current && !closing) {
                        try {
                            currentItems = items; build(items);
                            network.execute(() -> {
                                boolean valid = request(current, "PING");
                                handler.post(() -> { if (session == current && !closing) { if (valid) enter(); else closeNow(); } });
                            });
                        } catch (Exception error) { closeNow(); }
                    }});
                } catch (Exception error) {
                    android.util.Log.e("SidekeyMenu", "读取菜单失败", error);
                    handler.post(() -> { if (session == current) closeNow(); });
                }
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
        boolean wasEntered = entered;
        panel.animate().cancel();
        try {
            build(currentItems);
            // 已打开的会话在旋转或换主题后继续显示；未握手的会话继续等待原回调。
            panel.setAlpha(wasEntered ? 1 : 0);
        } catch (Exception exception) { closeNow(); }
    }

    private void build(JSONArray items) throws Exception {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        foreground = Color.parseColor(dark ? "#F3F4F6" : "#252932");
        muted = Color.parseColor(dark ? "#AFB3BA" : "#868D96");
        surface = Color.parseColor(dark ? "#ED303237" : "#EDE3E5E8");
        tileTop = Color.parseColor(dark ? "#393E45" : "#FFFFFF");
        stroke = Color.parseColor(dark ? "#545960" : "#D6DBE1");
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(16));
        GradientDrawable backdrop = background(surface, 24); backdrop.setStroke(dp(1), stroke);
        panel.setBackground(backdrop); panel.setElevation(dp(16));
        panel.setAlpha(0); panel.setClickable(true);
        TextView grip = text("—", 17, muted); grip.setGravity(Gravity.CENTER);
        grip.setContentDescription("收起快捷菜单"); grip.setOnClickListener(view -> dismiss());
        panel.addView(grip, new LinearLayout.LayoutParams(-1, dp(24)));
        scroll = new ListView(this); scroll.setDivider(null); scroll.setSelector(android.R.color.transparent);
        scroll.setVerticalScrollBarEnabled(false); scroll.setClipToPadding(false);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        populate(items, false);
        root = new MenuPanelHost(this, panel, right, position, widthDp, new MenuPanelHost.Listener() {
            @Override public void compactChanged(boolean compact) {
                try { populate(items, compact); }
                catch (Exception exception) { handler.post(MenuActivity.this::closeNow); }
            }
            @Override public void positioned() {
                if (!entered) panel.setTranslationX(root.offscreenTranslation());
            }
        });
        root.setOnClickListener(view -> dismiss());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            ((MenuPanelHost)view).setSafeInsets(bars);
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
    }

    private final android.util.LruCache<String, AppVisual> appVisuals = new android.util.LruCache<>(128);
    private static final class AppVisual {
        final String name; final android.graphics.drawable.Drawable icon;
        AppVisual(String name, android.graphics.drawable.Drawable icon) { this.name = name; this.icon = icon; }
    }

    private void populate(JSONArray items, boolean compact) throws Exception {
        panel.setPadding(dp(12), dp(4), dp(12), dp(10));
        java.util.ArrayList<JSONObject> apps = new java.util.ArrayList<>(), switches = new java.util.ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i); String type = item.getString("type");
            if ("app".equals(type) || "app_freeform".equals(type)) apps.add(item);
            else if (!"none".equals(type)) switches.add(item);
        }
        ListView old = scroll;
        scroll = new ListView(this); scroll.setDivider(null); scroll.setSelector(android.R.color.transparent);
        scroll.setVerticalScrollBarEnabled(false); scroll.setClipToPadding(false);
        scroll.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return switches.size() + (apps.size() + 1) / 2; }
            @Override public Object getItem(int index) { return index; }
            @Override public long getItemId(int index) { return index; }
            @Override public boolean isEnabled(int index) { return false; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int index) { return index < switches.size() ? 0 : 1; }
            @Override public View getView(int index, View recycled, ViewGroup parent) {
                if (index < switches.size()) {
                    SwitchRow row = recycled instanceof SwitchRow ? (SwitchRow)recycled : new SwitchRow();
                    row.bind(switches.get(index));
                    row.setPadding(0, 0, 0, index < getCount() - 1 ? dp(8) : 0);
                    return row;
                }
                AppRow row = recycled instanceof AppRow ? (AppRow)recycled : new AppRow();
                for (int column = 0; column < 2; column++) {
                    int offset = (index - switches.size()) * 2 + column;
                    row.cells[column].bind(offset < apps.size() ? apps.get(offset) : null);
                }
                row.setPadding(0, 0, 0, index < getCount() - 1 ? dp(appGapDp) : 0);
                return row;
            }
        });
        panel.removeView(old); panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        updateSwitches();
    }

    private void bindSelection(View view, JSONObject item) {
        final int index = item.optInt("index", -1);
        view.setOnClickListener(clicked -> { if (!closing && entered && index >= 0) { selection = index; dismiss(); } });
    }

    private final class AppRow extends LinearLayout {
        final AppCell[] cells = new AppCell[2];
        AppRow() {
            super(MenuActivity.this); setOrientation(HORIZONTAL); setBaselineAligned(false);
            for (int i = 0; i < 2; i++) {
                if (i > 0) addView(new View(MenuActivity.this), new LinearLayout.LayoutParams(dp(appGapDp), 1));
                cells[i] = new AppCell(); addView(cells[i], new LinearLayout.LayoutParams(0, -2, 1));
            }
        }
    }

    private final class AppCell extends LinearLayout {
        final ImageView image; final TextView label;
        AppCell() {
            super(MenuActivity.this); setOrientation(VERTICAL); setGravity(Gravity.CENTER); setPadding(dp(2), dp(10), dp(2), dp(8));
            image = new ImageView(MenuActivity.this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(image, new LinearLayout.LayoutParams(dp(44), dp(44)));
            label = text("", 11, foreground); label.setGravity(Gravity.CENTER); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, Math.max(dp(30), label.getLineHeight() * 2)); size.topMargin = dp(5); addView(label, size);
        }
        void bind(JSONObject item) {
            setOnClickListener(null); image.setImageDrawable(null);
            if (item == null) {
                setVisibility(View.INVISIBLE); setEnabled(false); label.setText(""); return;
            }
            setVisibility(View.VISIBLE); setEnabled(true); setAlpha(1);
            String packageName = item.optString("packageName"), name = item.optString("name");
            AppVisual visual = appVisuals.get(packageName);
            if (visual == null && packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
                try {
                    ApplicationInfo app = getPackageManager().getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0));
                    visual = new AppVisual(getPackageManager().getApplicationLabel(app).toString(), getPackageManager().getApplicationIcon(app)); appVisuals.put(packageName, visual);
                } catch (PackageManager.NameNotFoundException | RuntimeException error) { android.util.Log.w("SidekeyMenu", "应用信息暂不可读：" + packageName, error); }
            }
            label.setText(visual == null ? name : visual.name);
            image.setImageDrawable(visual == null ? getPackageManager().getDefaultActivityIcon() : visual.icon);
            setContentDescription(label.getText()); bindSelection(this, item);
        }
    }

    private void updateSwitches() {
        if (scroll == null) return;
        for (int i = 0; i < scroll.getChildCount(); i++)
            if (scroll.getChildAt(i) instanceof SwitchRow) ((SwitchRow)scroll.getChildAt(i)).updateState();
    }

    private final class SwitchRow extends LinearLayout {
        final LinearLayout card;
        final TextView name;
        final SwitchGlyph control;
        SwitchRow() {
            super(MenuActivity.this); setOrientation(VERTICAL);
            card = new LinearLayout(MenuActivity.this); card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(10), dp(8), dp(8), dp(8)); card.setBackground(background(tileTop, 16));
            name = text("", 12, foreground); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            control = new SwitchGlyph();
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(32), dp(20)); size.leftMargin = dp(5);
            card.addView(control, size); addView(card, new LinearLayout.LayoutParams(-1, dp(56)));
        }
        void bind(JSONObject item) {
            name.setText(item.optString("name")); control.stateful = "torch".equals(item.optString("type"));
            card.setContentDescription(name.getText()); bindSelection(card, item); updateState();
        }
        void updateState() { control.state = session == null ? -1 : session.torchState; control.invalidate(); }
    }

    private final class SwitchGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean stateful;
        int state = -1;
        SwitchGlyph() { super(MenuActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setColor(stateful && state == 1 ? Color.rgb(83, 221, 161) : stroke);
            canvas.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, paint);
            paint.setColor(stateful && state >= 0 ? Color.WHITE : muted);
            if (stateful && state >= 0) {
                float radius = h / 2 - dp(3), center = state == 1 ? w - h / 2 : h / 2;
                canvas.drawCircle(center, h / 2, radius, paint);
            } else {
                // 无系统状态的单次动作显示执行符号，不伪造已开启状态。
                paint.setTextSize(dp(16)); paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(stateful ? "—" : "›", w / 2, h / 2 + dp(5), paint);
            }
        }
    }

    private void enter() {
        if (closing || entered) return;
        if (root.getWidth() == 0) { root.post(this::enter); return; }
        panel.setTranslationX(root.offscreenTranslation()); entered = true;
        panel.animate().translationX(0).alpha(1).setDuration(280)
            .setInterpolator(new PathInterpolator(0.2f, 0, 0, 1)).start();
        panel.announceForAccessibility("快捷菜单");
    }

    private void dismiss() {
        if (closing) return;
        closing = true; handler.removeCallbacksAndMessages(null);
        if (panel == null) { closeNow(); return; }
        panel.animate().translationX(root.offscreenTranslation()).alpha(0)
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

    private static String exchange(Session current, String command, int limit) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), current.port), 1000);
            socket.setSoTimeout(1500);
            int separator = command.indexOf(' ');
            String line = separator < 0 ? command + " " + current.token :
                command.substring(0, separator) + " " + current.token + command.substring(separator);
            socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); InputStream input = new java.io.BufferedInputStream(socket.getInputStream());
            int value;
            while (bytes.size() < limit && (value = input.read()) != -1) {
                if (value == '\n') return bytes.toString("UTF-8");
                bytes.write(value);
            }
            throw new IllegalStateException("菜单响应不完整或过长");
        }
    }

    private static JSONArray loadItems(Session current) throws Exception {
        JSONArray result = new JSONArray(); int offset = 0;
        for (int page = 0; page < 130; page++) {
            JSONObject response = new JSONObject(exchange(current, "ITEMS " + offset, 32768));
            JSONArray items = response.getJSONArray("items");
            if (items.length() > 16) throw new IllegalStateException("菜单分页越界");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item.getInt("index") != result.length()) throw new IllegalStateException("菜单顺序异常");
                result.put(item);
            }
            int next = response.getInt("next");
            if (next == -1) return result;
            if (next != offset + 16 || result.length() > 2060) throw new IllegalStateException("菜单分页无效");
            offset = next;
        }
        throw new IllegalStateException("菜单超过应用目录范围");
    }

    private static boolean request(Session current, String command) {
        try {
            String result = exchange(current, command, 32);
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
