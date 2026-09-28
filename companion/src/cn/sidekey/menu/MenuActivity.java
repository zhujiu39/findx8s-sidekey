package cn.sidekey.menu;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
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
import android.util.TypedValue;
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
    private boolean closing, dispatched, entered, selecting;
    private int selection = -1, position, widthDp, appGapDp;
    private boolean right;
    private int foreground, muted, surface, tileTop, stroke, accent, tileOn, tilePending, tileUnknown;
    private int tileBorder, stateBorder, onInk, onMuted, pendingInk, warning;
    private JSONArray currentItems;

    private static final class Session {
        final int port;
        final String token;
        volatile int torchState = -1;
        volatile String mijiaStates;
        volatile String[] mijiaReadings;
        volatile boolean pendingMijia;
        volatile int mijiaRevision;
        boolean mijiaOperating, torchSelecting;
        boolean hasSurfing, surfingSelecting, surfingAwaitingResult;
        volatile int surfingRevision;
        volatile SurfingState surfing = SurfingState.unknown();
        int itemCount;
        Session(int port, String token) { this.port = port; this.token = token; }
    }

    private static final class MenuData {
        final JSONArray items = new JSONArray();
        int widthDp, gapDp;
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
        closing = false; dispatched = false; entered = false; selecting = false; selection = -1;
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
            final Session current = session;
            network.execute(() -> {
                try {
                    MenuData data = loadItems(current);
                    handler.post(() -> { if (session == current && !closing) {
                        try {
                            widthDp = data.widthDp; appGapDp = data.gapDp;
                            currentItems = data.items; build(data.items);
                            network.execute(() -> {
                                boolean valid = request(current, "PING");
                                if (valid) readMijiaStates(current, 0);
                                handler.post(() -> { if (session == current && !closing) { if (valid) {
                                    updateSwitches(); enter(); pollMijia(current, current.mijiaRevision); pollSurfing(current);
                                } else closeNow(); } });
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
        foreground = Color.parseColor(dark ? "#F5F5F7" : "#1D1D1F");
        muted = Color.parseColor(dark ? "#AEAEB2" : "#6E6E73");
        surface = Color.parseColor(dark ? "#F2262629" : "#F2F5F5F7");
        tileTop = Color.parseColor(dark ? "#363639" : "#FFFFFF");
        stroke = Color.parseColor(dark ? "#24FFFFFF" : "#20FFFFFF");
        accent = Color.parseColor(dark ? "#409CFF" : "#007AFF");
        tileOn = Color.parseColor(dark ? "#30D158" : "#34C759");
        tilePending = Color.parseColor(dark ? "#0A84FF" : "#0066CC");
        tileUnknown = Color.parseColor(dark ? "#FF9F0A" : "#FF9500");
        tileBorder = Color.parseColor(dark ? "#5C5C62" : "#D7D7DC");
        stateBorder = Color.parseColor(dark ? "#33FFFFFF" : "#26000000");
        onInk = Color.parseColor("#092711"); onMuted = Color.parseColor("#164822");
        pendingInk = Color.parseColor(dark ? "#001B36" : "#FFFFFF");
        warning = Color.parseColor("#402300");
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(16));
        GradientDrawable backdrop = background(surface, 26); backdrop.setStroke(dp(1), stroke);
        panel.setBackground(backdrop); panel.setElevation(dp(12)); panel.setClipToOutline(true);
        panel.setAlpha(0); panel.setClickable(true);
        ImageView grip = new ImageView(this); grip.setScaleType(ImageView.ScaleType.FIT_CENTER);
        grip.setPadding(0, dp(12), 0, dp(12)); SfSymbols.apply(grip, "minus", muted);
        grip.setContentDescription("收起快捷菜单"); grip.setOnClickListener(view -> dismiss());
        panel.addView(grip, new LinearLayout.LayoutParams(-1, dp(44)));
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
        panel.setPadding(dp(10), 0, dp(10), dp(12));
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
                    row.setPadding(0, 0, 0, index < getCount() - 1 ? dp(6) : 0);
                    return row;
                }
                AppRow row = recycled instanceof AppRow ? (AppRow)recycled : new AppRow();
                for (int column = 0; column < 2; column++) {
                    int offset = (index - switches.size()) * 2 + column;
                    row.cells[column].bind(offset < apps.size() ? apps.get(offset) : null);
                }
                row.setHasNext(index < getCount() - 1);
                return row;
            }
        });
        panel.removeView(old); panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        updateSwitches();
    }

    private void bindSelection(View view, JSONObject item) {
        final int index = item.optInt("index", -1);
        final String type = item.optString("type");
        view.setOnClickListener(clicked -> {
            if (closing || !entered || selecting || index < 0) return;
            if ("app".equals(type) || "app_freeform".equals(type)) {
                selection = index; dismiss();
            } else selectSwitch(index, type);
        });
    }

    private void selectSwitch(int index, String type) {
        final Session current = session;
        if (current == null) return;
        final boolean mijia = "mijia".equals(type);
        final boolean surfing = "surfing".equals(type);
        if (surfing && (current.surfingSelecting || !current.surfing.canToggle())) return;
        if (surfing) { current.surfingSelecting = true; current.surfingAwaitingResult = true; current.surfingRevision++; updateSwitches(); }
        if (mijia && (current.mijiaOperating || !MijiaSwitchState.enabled(
                MijiaSwitchState.at(current.mijiaStates, index, current.pendingMijia)))) return;
        if (mijia) {
            synchronized (current) {
                current.mijiaRevision++; current.pendingMijia = true; current.mijiaOperating = true;
                current.mijiaStates = MijiaSwitchState.pending(current.mijiaStates, index);
            }
            updateSwitches();
        }
        selecting = true;
        if ("torch".equals(type)) { current.torchSelecting = true; updateSwitches(); }
        network.execute(() -> {
            boolean accepted = request(current, "SELECT " + index);
            if (surfing) readSurfing(current);
            handler.post(() -> {
                if (session != current || closing) return;
                selecting = false;
                if (surfing) current.surfingSelecting = false;
                if (!accepted) {
                    if (surfing) current.surfingAwaitingResult = false;
                    Toast.makeText(getApplicationContext(), "快捷开关请求未确认，请查看运行日志", Toast.LENGTH_SHORT).show();
                }
                if ("torch".equals(type)) { current.torchSelecting = false; current.torchState = -1; }
                if (mijia) pollMijia(current, current.mijiaRevision);
                updateSwitches();
            });
        });
    }

    private final class AppRow extends ViewGroup {
        final AppCell[] cells = new AppCell[2];
        private AppGridGeometry geometry;
        private boolean hasNext;
        AppRow() {
            super(MenuActivity.this);
            for (int i = 0; i < 2; i++) { cells[i] = new AppCell(); addView(cells[i], new ViewGroup.LayoutParams(-2, -2)); }
        }
        void setHasNext(boolean next) {
            if (hasNext != next) { hasNext = next; requestLayout(); }
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = 0;
            geometry = new AppGridGeometry(width, getResources().getDisplayMetrics().density, appGapDp);
            for (AppCell cell : cells) {
                cell.setIconSize(geometry.iconSize);
                cell.measure(MeasureSpec.makeMeasureSpec(geometry.cellWidth, MeasureSpec.EXACTLY),
                             MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                height = Math.max(height, cell.getMeasuredHeight());
            }
            setMeasuredDimension(width, resolveSize(geometry.rowHeight(height, hasNext), heightSpec));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            if (geometry == null) return;
            for (int i = 0; i < 2; i++) {
                int x = i == 0 ? geometry.left : geometry.right;
                cells[i].layout(x, 0, x + geometry.cellWidth, cells[i].getMeasuredHeight());
            }
        }
    }

    private final class AppCell extends LinearLayout {
        final ImageView image; final TextView label;
        AppCell() {
            super(MenuActivity.this); setOrientation(VERTICAL); setGravity(Gravity.CENTER);
            setMinimumHeight(dp(44)); pressFeedback(this);
            image = new ImageView(MenuActivity.this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(image, new LinearLayout.LayoutParams(dp(44), dp(44)));
            label = text("", 12, foreground); label.setGravity(Gravity.CENTER); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, Math.max(dp(30), label.getLineHeight() * 2)); size.topMargin = dp(5); addView(label, size);
        }
        void setIconSize(int pixels) {
            ViewGroup.LayoutParams size = image.getLayoutParams();
            if (size.width == pixels && size.height == pixels) return;
            size.width = pixels; size.height = pixels; image.setLayoutParams(size);
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
            image.setImageDrawable(visual == null ? SfSymbols.drawable(MenuActivity.this, "app.fill", accent) : visual.icon);
            setContentDescription(label.getText()); bindSelection(this, item);
        }
    }

    private void updateSwitches() {
        if (scroll == null) return;
        Session current = session;
        if (current != null && current.surfingAwaitingResult && !current.surfingSelecting) {
            SurfingState result = current.surfing;
            boolean finished = !result.busy && (result.result != 0 ||
                !("starting".equals(result.state) || "stopping".equals(result.state) || "unknown".equals(result.state)));
            if (finished) {
                current.surfingAwaitingResult = false;
                if (result.result != 0) Toast.makeText(this, "Surfing 操作未完成，请查看运行日志", Toast.LENGTH_SHORT).show();
            }
        }
        for (int i = 0; i < scroll.getChildCount(); i++)
            if (scroll.getChildAt(i) instanceof SwitchRow) ((SwitchRow)scroll.getChildAt(i)).updateState();
    }

    private final class SwitchRow extends LinearLayout {
        final LinearLayout card;
        final TextView name, status;
        final ReadingContent readings;
        final ImageView mark;
        final SurfingContent trafficPanel;
        boolean mijia, surfing;
        int index, markColor, lastFill;
        String type;
        SwitchRow() {
            super(MenuActivity.this); setOrientation(VERTICAL);
            card = new LinearLayout(MenuActivity.this); card.setOrientation(VERTICAL);
            int horizontal = dp(widthDp < 160 ? 6 : 10);
            card.setPadding(horizontal, dp(8), horizontal, dp(8)); card.setBackground(background(tileTop, 16));
            card.setElevation(dp(1)); card.setClipToOutline(true);
            pressFeedback(card);
            LinearLayout header = new LinearLayout(MenuActivity.this); header.setGravity(Gravity.CENTER_VERTICAL);
            header.setOrientation(LinearLayout.HORIZONTAL);
            mark = new ImageView(MenuActivity.this); mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(18), dp(22)); iconSize.rightMargin = dp(8);
            header.addView(mark, iconSize); mark.setVisibility(widthDp < 220 ? View.GONE : View.VISIBLE);
            name = text("", 12, foreground); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
            name.setAutoSizeTextTypeUniformWithConfiguration(10, 12, 1, TypedValue.COMPLEX_UNIT_SP);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            LinearLayout labels = new LinearLayout(MenuActivity.this); labels.setOrientation(VERTICAL);
            labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
            status = text("", 10, muted); status.setMaxLines(2); status.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams statusSize = new LinearLayout.LayoutParams(-1, -2); statusSize.topMargin = dp(4);
            labels.addView(status, statusSize); status.setVisibility(View.GONE);
            header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            header.setMinimumHeight(dp(32)); card.addView(header, new LinearLayout.LayoutParams(-1, -2));
            readings = new ReadingContent(MenuActivity.this, muted); readings.setVisibility(View.GONE);
            card.addView(readings, new LinearLayout.LayoutParams(-1, -2));
            trafficPanel = new SurfingContent(MenuActivity.this, onInk, onMuted, onInk, widthDp);
            card.addView(trafficPanel, new LinearLayout.LayoutParams(-1, -2));
            card.setMinimumHeight(dp(48)); addView(card, new LinearLayout.LayoutParams(-1, -2));
        }
        void bind(JSONObject item) {
            card.setScaleX(1); card.setScaleY(1);
            type = item.optString("type"); name.setText(item.optString("name"));
            mijia = "mijia".equals(type); index = item.optInt("index", -1);
            surfing = "surfing".equals(type); trafficPanel.setVisibility(View.GONE);
            if (surfing) trafficPanel.bind(item);
            markColor = mijia ? Color.rgb(235, 145, 38) : surfing ? Color.rgb(157, 101, 226) : accent;
            SfSymbols.apply(mark, SfSymbols.action(type), markColor);
            card.setContentDescription(name.getText()); bindSelection(card, item); updateState();
        }
        void updateState() {
            if (surfing) { updateSurfing(); return; }
            char value = session == null ? '?' : MijiaSwitchState.at(session.mijiaStates, index, session.pendingMijia);
            boolean displayOnly = mijia && MijiaSwitchState.displayOnly(value);
            readings.setVisibility(displayOnly ? View.VISIBLE : View.GONE);
            String[] texts = session == null ? null : session.mijiaReadings;
            String reading = texts != null && index >= 0 && index < texts.length ? texts[index] : null;
            if (displayOnly) readings.update(reading == null || reading.isEmpty() ? "暂无数据" : reading, value == 's' || value == 'q' ? muted : foreground);
            boolean torch = "torch".equals(type);
            int torchState = session == null ? -1 : session.torchState;
            boolean torchPending = torch && session != null && session.torchSelecting;
            char appearance = mijia ? MijiaSwitchState.power(value) ? value : 'a' :
                torch ? torchPending ? '~' : torchState == 1 ? '1' : torchState == 0 ? '0' : '?' : 'a';
            boolean unconfirmedReading = displayOnly && value != 'r';
            status.setVisibility(unconfirmedReading ? View.VISIBLE : View.GONE);
            if (mijia) {
                String description = session != null && session.mijiaOperating && value == '~' ? "执行并刷新中" : MijiaSwitchState.description(value);
                status.setText(unconfirmedReading ? "最近上报 · 待确认" : "");
                card.setContentDescription(name.getText() + "，" + description + (displayOnly ? "，" + readings.value() : ""));
                card.setStateDescription(description);
                card.setTooltipText(name.getText() + "，" + description);
            } else {
                String description = torch ? torchPending ? "切换中" : torchState == 1 ? "已开启" : torchState == 0 ? "已关闭" : "状态未确认" : "轻触执行";
                status.setText(""); card.setStateDescription(description);
                card.setContentDescription(name.getText() + "，" + description);
                card.setTooltipText(name.getText() + "，" + description);
            }
            boolean waiting = mijia && session != null && session.mijiaOperating;
            card.setEnabled(!torchPending && !waiting && (!mijia || MijiaSwitchState.enabled(value)));
            renderCard(appearance, displayOnly);
        }
        void updateSurfing() {
            SurfingState value = session == null ? SurfingState.unknown() : session.surfing;
            boolean submitting = session != null && session.surfingSelecting;
            readings.setVisibility(View.GONE);
            status.setVisibility(View.GONE); status.setText("");
            // 只有确认开启时显示统计，关闭、切换中或状态未知均不占位、不朗读旧数据。
            boolean showDetails = !submitting && value.power() == '1';
            trafficPanel.setVisibility(showDetails ? View.VISIBLE : View.GONE);
            if (showDetails) trafficPanel.update(value);
            String state = submitting ? "切换中…" : value.status();
            StringBuilder description = new StringBuilder(name.getText()).append('，').append(state);
            if (showDetails) description.append(trafficPanel.description());
            card.setContentDescription(description.toString()); card.setStateDescription(state);
            card.setTooltipText(name.getText() + "，" + state);
            card.setEnabled(!submitting && value.canToggle()); card.setAlpha(1f);
            renderCard(submitting ? '~' : value.power(), false);
        }
        void renderCard(char value, boolean displayOnly) {
            boolean on = value == '1', pending = value == '~', unknown = value == '?' || value == 'u' || value == 'o';
            boolean colored = on || pending || unknown;
            int fill = on ? tileOn : pending ? tilePending : unknown ? tileUnknown : tileTop;
            if (lastFill != fill) {
                GradientDrawable frame = background(fill, 16);
                frame.setStroke(dp(1), colored ? stateBorder : tileBorder);
                card.setBackground(frame); lastFill = fill;
            }
            int ink = on ? onInk : pending ? pendingInk : unknown ? warning : foreground;
            name.setTextColor(ink); status.setTextColor(colored ? ink : muted);
            // 所有状态保留完整底框，禁用通过原交互规则处理，不再淡化整卡背景。
            card.setAlpha(1f); card.setActivated(on);
            // 只按读回状态着色；未知与切换中保留标记，不把它们当成关闭。
            String icon = pending ? "arrow.clockwise" : unknown ? "exclamationmark.circle.fill" :
                displayOnly ? "thermometer.medium" : SfSymbols.action(type);
            SfSymbols.apply(mark, icon, colored ? ink : markColor);
            mark.setVisibility(pending || unknown || widthDp >= 220 ? View.VISIBLE : View.GONE);
        }
    }

    private void pressFeedback(View target) {
        target.setOnTouchListener((view, event) -> {
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                float scale = event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN ? 0.975f : 1f;
                if (event.getActionMasked() != android.view.MotionEvent.ACTION_MOVE)
                    view.animate().scaleX(scale).scaleY(scale).setDuration(scale < 1 ? 100 : 180).start();
            }
            return false;
        });
    }

    private void enter() {
        if (closing || entered) return;
        if (root.getWidth() == 0) { root.post(this::enter); return; }
        panel.setTranslationX(root.offscreenTranslation()); entered = true;
        panel.announceForAccessibility("快捷菜单");
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { panel.setTranslationX(0); panel.setAlpha(1); return; }
        panel.animate().translationX(0).alpha(1).setDuration(300)
            .setInterpolator(new PathInterpolator(0.22f, 0.8f, 0.25f, 1)).start();
    }

    private void dismiss() {
        if (closing) return;
        closing = true; handler.removeCallbacksAndMessages(null);
        if (panel == null || !android.animation.ValueAnimator.areAnimatorsEnabled()) { closeNow(); return; }
        panel.animate().translationX(root.offscreenTranslation()).alpha(0)
            .setDuration(180).withEndAction(this::closeNow).start();
    }

    private void closeNow() {
        closing = true; handler.removeCallbacksAndMessages(null);
        finish();
    }


    @Override protected void onStop() {
        super.onStop();
        // 应用启动等菜单完全离开前台；快捷开关已在点击时提交。
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

    private void pollSurfing(Session current) {
        if (!current.hasSurfing || session != current || closing) return;
        network.execute(() -> {
            readSurfing(current);
            handler.post(() -> {
                if (session != current || closing) return;
                updateSwitches(); handler.postDelayed(() -> pollSurfing(current), 1000);
            });
        });
    }

    private static void readSurfing(Session current) {
        int revision = current.surfingRevision;
        SurfingState value;
        try { value = SurfingState.decode(exchange(current, "SURFING", 32768)); }
        catch (Exception error) { value = SurfingState.unknown(); }
        if (current.surfingRevision == revision) current.surfing = value;
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

    private void pollMijia(Session current, int revision) {
        if (!current.pendingMijia) return;
        handler.postDelayed(() -> {
            if (session != current || closing || !current.pendingMijia || current.mijiaRevision != revision) return;
            network.execute(() -> {
                readMijiaStates(current, revision);
                handler.post(() -> {
                    if (session != current || closing || current.mijiaRevision != revision) return;
                    if (!current.pendingMijia) current.mijiaOperating = false;
                    updateSwitches();
                    pollMijia(current, revision);
                });
            });
        }, 150);
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

    private static MenuData loadItems(Session current) throws Exception {
        MenuData data = new MenuData(); JSONArray result = data.items; int offset = 0;
        for (int page = 0; page < 130; page++) {
            JSONObject response = new JSONObject(exchange(current, "ITEMS " + offset, 32768));
            JSONObject layout = response.getJSONObject("layout");
            int width = layout.getInt("width"), gap = layout.getInt("gap");
            if (width < 120 || width > 360 || gap < 0 || gap > 32) throw new IllegalStateException("菜单布局参数越界");
            if (page == 0) { data.widthDp = width; data.gapDp = gap; }
            else if (width != data.widthDp || gap != data.gapDp) throw new IllegalStateException("菜单布局会话发生变化");
            JSONArray items = response.getJSONArray("items");
            if (items.length() > 16) throw new IllegalStateException("菜单分页越界");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item.getInt("index") != result.length()) throw new IllegalStateException("菜单顺序异常");
                result.put(item);
                if ("mijia".equals(item.optString("type"))) current.pendingMijia = true;
                if ("surfing".equals(item.optString("type"))) current.hasSurfing = true;
            }
            int next = response.getInt("next");
            if (next == -1) { current.itemCount = result.length(); return data; }
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

    private static void readMijiaStates(Session current, int revision) {
        if (!current.pendingMijia || current.mijiaRevision != revision) return;
        String states = null;
        String[] readings = null;
        try {
            states = MijiaSwitchState.decode(exchange(current, "STATES", 4096), current.itemCount);
            if (states != null && states.indexOf('~') < 0) {
                readings = new String[current.itemCount];
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                for (int offset = 0; offset < current.itemCount; offset += 16) {
                    if (current.mijiaRevision != revision) return;
                    int end = Math.min(offset + 16, current.itemCount); boolean needed = false;
                    for (int i = offset; i < end; i++) if (MijiaSwitchState.displayOnly(states.charAt(i))) needed = true;
                    if (!needed) continue;
                    try {
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("读数分页超时");
                        String response = exchange(current, "READINGS " + offset, 16384);
                        if (!response.startsWith("READINGS ")) throw new IllegalStateException("读数分页无效");
                        String[] fields = response.substring(9).split("\\|", -1);
                        if (fields.length != end - offset) throw new IllegalStateException("读数分页数量无效");
                        for (int i = offset; i < end; i++) readings[i] = decodeReading(fields[i - offset]);
                    } catch (Exception error) {
                        for (int i = offset; i < end; i++) readings[i] = "本次读数获取失败";
                    }
                }
            }
        } catch (Exception ignored) { }
        synchronized (current) {
            if (!current.pendingMijia || current.mijiaRevision != revision) return;
            current.mijiaStates = states == null ? MijiaSwitchState.failed(current.mijiaStates) : states;
            if (readings != null) current.mijiaReadings = readings;
            current.pendingMijia = states != null && states.indexOf('~') >= 0;
        }
    }

    private static String decodeReading(String hex) throws Exception {
        if (hex.length() > 768 || hex.length() % 2 != 0 || !hex.matches("[0-9a-f]*")) throw new IllegalStateException("读数长度或编码无效");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        String value = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)) && value.charAt(i) != '\n')
            throw new IllegalStateException("读数包含无效控制符");
        return value;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL); view.setIncludeFontPadding(false); return view;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
}
