package com.darsha.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.List;

public class MainActivity extends Activity {

    // Top-row apps, in this order of preference:
    //  1. a list saved at install time:  am start -n com.darsha.tvhome/.MainActivity --es apps "pkg1,pkg2"
    //     (--es apps auto  goes back to 2; add --es stamp <unique> so it's applied only once, see saveConfig)
    //  2. apps.txt (built in as Apps.LIST), topped up with other TV apps installed on this TV.
    // Missing apps are skipped. Topping up stops at six apps, but a chosen list can be any length:
    // apps wrap into rows of six, like Apple TV, and the page scrolls down once they don't all fit.
    // On the TV, hold OK on an app (or press Menu) to move, rename, replace, hide or remove it.
    // Hold OK on Settings for Home settings: themes, hidden apps, reset and About.
    private static final String PREFS = "config";
    private static final List<String> NOT_APPS = Arrays.asList("com.android.vending", "com.android.tv.settings",
            "com.android.tv", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher");
    private static final List<String> LAUNCHERS = Arrays.asList("com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher");
    private static final String PLAY_STORE = "com.android.vending";
    private static final String SETTINGS = "settings";
    private static final String ALL = "all";
    private static final String HIDDEN = "hidden";
    private static final String THEME = "theme";
    private static final String LABEL = "label:";
    private static final String[] EDIT_ITEMS = {"Move", "Rename", "Replace", "App info", "Hide", "Remove from home"};
    private static final int PER_ROW = 6, MAX_INPUTS = 4;

    // Layout is designed at 1920x1080 and scaled to the TV's real UI size by px().
    private static final int MARGIN = 96, GAP = 36, RADIUS = 22;
    private static final int ROW1_TOP = 604, ROW_STEP = 224;
    private static final int GRID_MIN_TOP = 400; // more rows push the grid up, but not into the clock
    private float scale = 1f;

    private static Typeface regular, semibold;

    private FrameLayout root; // the page, inside a vertical scroller
    private int tileW, tileH, gridTop; // app grid geometry, set by buildTiles
    private final List<View> tiles = new ArrayList<>();
    private boolean dirty = true;
    private String focusedKey;
    private final List<String> rowApps = new ArrayList<>(); // the top row as shown, in order
    private String movingKey;   // the app being moved with left/right, if any
    private TextView moveHint;  // shown above the apps while moving
    private boolean longPressed; // OK was held: swallow the key-up so the app doesn't open
    private boolean showHidden;  // All apps: reveal hidden apps until the screen is closed

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dirty = true; }
    };

    private TvInputManager tvInputManager;
    private final Handler inputHandler = new Handler(Looper.getMainLooper());
    private final Map<String, HdmiTile> hdmiTiles = new HashMap<>();
    private boolean inputCallbackRegistered, resumed;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { updateHdmiTile(inputId, state); }
        @Override public void onInputAdded(String inputId) { refreshInputLayout(); }
        @Override public void onInputRemoved(String inputId) { refreshInputLayout(); }
        @Override public void onInputUpdated(String inputId) { refreshInputLayout(); }
    };

    private static final class HdmiTile {
        final FrameLayout card;
        final ImageView icon;
        final View dot;
        final TextView status;
        final String label;
        HdmiTile(FrameLayout card, ImageView icon, View dot, TextView status, String label) {
            this.card = card;
            this.icon = icon;
            this.dot = dot;
            this.status = status;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        saveConfig(getIntent());
        loadFonts();
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        getWindow().setBackgroundDrawable(background());

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        scroller.addView(root, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock date = clock("EEEE, d MMMM", "EEEE, d MMMM", px(30), 0xA8FFFFFF, regular);
        TextClock time = clock("h:mm", "H:mm", px(176), Color.WHITE, semibold);
        time.setLetterSpacing(-0.028f);
        time.setFontFeatureSettings("tnum");
        time.setIncludeFontPadding(false);
        clock.addView(date);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = px(-20); // pull the big digits up under the date
        clock.addView(time, tp);
        root.addView(clock, at(px(MARGIN), px(92), -2, -2));

        moveHint = text(px(24), 0xA8FFFFFF, regular);
        moveHint.setText("Use the arrows to move it, then press OK");
        moveHint.setVisibility(View.GONE);
        root.addView(moveHint, at(px(MARGIN), px(ROW1_TOP - 64), -2, -2));

        setContentView(scroller);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);

        if (!getSharedPreferences(PREFS, 0).getBoolean("hint_shown", false)) {
            getSharedPreferences(PREFS, 0).edit().putBoolean("hint_shown", true).apply();
            toast("Tip: hold OK (or press Menu) on Settings for themes, hidden apps and About");
        }
    }

    @Override
    protected void onDestroy() {
        if (tvInputManager != null && inputCallbackRegistered) {
            try { tvInputManager.unregisterCallback(inputCallback); } catch (Exception ignored) {}
            inputCallbackRegistered = false;
        }
        inputHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (tvInputManager != null && inputCallbackRegistered) {
            try { tvInputManager.unregisterCallback(inputCallback); } catch (Exception ignored) {}
            inputCallbackRegistered = false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (saveConfig(intent)) buildTiles();
    }

    // Saves an app list passed with --es apps "...". Returns true if it changed.
    // When Android kills the home screen in the background, it can start it again with the same old
    // intent; the stamp install.sh sends stops that from undoing changes made on the TV since.
    private boolean saveConfig(Intent intent) {
        String apps = intent == null ? null : intent.getStringExtra("apps");
        if (apps == null) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String stamp = intent.getStringExtra("stamp");
        if (stamp != null && stamp.equals(prefs.getString("stamp", null))) return false;
        apps = apps.trim();
        SharedPreferences.Editor e = prefs.edit();
        if (stamp != null) e.putString("stamp", stamp);
        if (apps.isEmpty() || apps.equalsIgnoreCase("auto")) e.remove("apps");
        else e.putString("apps", apps);
        e.apply();
        dirty = true;
        return true;
    }

    private List<String> appList(PackageManager pm) {
        List<String> out = new ArrayList<>();
        String saved = getSharedPreferences(PREFS, 0).getString("apps", null);
        String[] wanted = saved != null ? saved.split("[,\\s]+") : Apps.LIST;
        for (String p : wanted) if (!p.isEmpty() && !out.contains(p) && !isHidden(p) && launchIntent(pm, p) != null) out.add(p);
        if (saved == null) {
            // Top up with other TV apps people installed (or updated), skipping launchers and system tools.
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (out.size() >= PER_ROW) break;
                String p = ri.activityInfo.packageName;
                int flags = ri.activityInfo.applicationInfo.flags;
                boolean stockSystem = (flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (stockSystem || out.contains(p) || NOT_APPS.contains(p) || p.equals(getPackageName())
                        || isHidden(p)) continue;
                out.add(p);
            }
        }
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (tvInputManager == null) tvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tvInputManager != null && !inputCallbackRegistered) {
            try {
                tvInputManager.registerCallback(inputCallback, inputHandler);
                inputCallbackRegistered = true;
            } catch (Exception ignored) {}
        }
        // Rebuild only when apps or inputs were installed, removed or updated.
        if (dirty) {
            dirty = false;
            buildTiles();
        } else {
            refreshHdmiStates();
        }
        View f = getCurrentFocus();
        if (f == null && !tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    @Override
    public void onBackPressed() { /* home screen: nothing to go back to */ }

    // Static gradient, drawn once by the window: no extra view, no animation. The user can pick
    // Warm, Black (OLED) or Blue by holding OK on Settings.
    private Drawable background() {
        String theme = getSharedPreferences(PREFS, 0).getString(THEME, "warm");
        if ("black".equals(theme)) {
            GradientDrawable black = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0xFF000000, 0xFF000000});
            black.setDither(true);
            return black;
        }
        GradientDrawable base;
        GradientDrawable glow;
        GradientDrawable second;
        if ("blue".equals(theme)) {
            base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0xFF07111C, 0xFF04080D, 0xFF020304});
            glow = radial(0x332E86FF, 0.08f, -0.12f, px(1250));
            second = radial(0x2E00C2A8, 1.0f, 1.1f, px(1000));
        } else {
            base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0xFF15100F, 0xFF0A0809, 0xFF050405});
            glow = radial(0x36FF8048, 0.08f, -0.12f, px(1250));
            second = radial(0x2EAA3C8C, 1.0f, 1.1f, px(1000));
        }
        for (GradientDrawable g : new GradientDrawable[] {base, glow, second}) g.setDither(true);
        return new LayerDrawable(new Drawable[] {base, glow, second});
    }

    private static GradientDrawable radial(int color, float cx, float cy, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(r);
        return g;
    }

    private void buildTiles() {
        // While tiles are swapped out, removing the focused one would hand focus down the row (and
        // overwrite focusedKey), so hold focus off until the new tiles are in.
        String want = focusedKey;
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (View t : tiles) {
            View wrap = (View) t.getParent();
            ((ViewGroup) wrap.getParent()).removeView(wrap);
        }
        tiles.clear();
        hdmiTiles.clear();
        rowApps.clear();
        movingKey = null;
        moveHint.setVisibility(View.GONE);

        PackageManager pm = getPackageManager();
        int m = px(MARGIN), g = px(GAP);
        int w = (getResources().getDisplayMetrics().widthPixels - 2 * m - g * (PER_ROW - 1)) / PER_ROW;
        int h = w * 9 / 16;
        tileW = w;
        tileH = h;

        List<String> apps = appList(pm);
        // Apps fill rows of six. With more rows the grid starts higher, so up to two rows of apps and
        // the bottom row still fit on screen; past that the page scrolls.
        int launchable = 0;
        for (String pkg : apps) if (launchIntent(pm, pkg) != null) launchable++;
        int rows = Math.max(1, (launchable + PER_ROW - 1) / PER_ROW);
        gridTop = Math.max(px(GRID_MIN_TOP), px(ROW1_TOP) - (rows - 1) * px(ROW_STEP));
        int bottomTop = gridTop + rows * px(ROW_STEP);
        ((FrameLayout.LayoutParams) moveHint.getLayoutParams()).topMargin = gridTop - px(64);
        root.setMinimumHeight(bottomTop + h + px(MARGIN));

        for (String pkg : apps) {
            Intent li = launchIntent(pm, pkg);
            if (li == null) continue;
            Drawable banner = null;
            try {
                banner = pm.getActivityBanner(li);
                if (banner == null) banner = pm.getApplicationBanner(pkg);
            } catch (Exception ignored) {}
            String label = labelOf(pkg);
            // Apps without a TV banner get a plain tile with their normal icon.
            Drawable icon = null;
            if (banner == null) try { icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) {}
            int i = rowApps.size();
            rowApps.add(pkg);
            addTile(root, pkg, label, banner, null, icon, gridX(i), gridY(i), w, h);
        }

        // Bottom row: All apps, HDMI inputs, Settings and Play Store, six at most.
        int col = 0;
        boolean store = !apps.contains(PLAY_STORE) && launchIntent(pm, PLAY_STORE) != null;
        List<TvInputInfo> inputs = hdmiInputs();
        int room = PER_ROW - 2 - (store ? 1 : 0);
        if (inputs.size() > room) inputs = inputs.subList(0, room);
        addTile(root, ALL, "All apps", null, LineIcon.APPS, null, m + col++ * (w + g), bottomTop, w, h);
        int inputIndex = 0;
        for (TvInputInfo in : inputs) {
            inputIndex++;
            int port = portNumber(in);
            addTile(root, in.getId(), inputLabel(in), null, LineIcon.HDMI, null,
                    m + col++ * (w + g), bottomTop, w, h, port > 0 ? port : inputIndex);
        }
        addTile(root, SETTINGS, "Settings", null, LineIcon.SETTINGS, null, m + col++ * (w + g), bottomTop, w, h);
        if (store)
            addTile(root, PLAY_STORE, "Play Store", null, LineIcon.PLAY, null, m + col * (w + g), bottomTop, w, h);

        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View restore = null;
        for (View t : tiles) if (t.getTag().equals(want)) restore = t;
        if (restore != null) restore.requestFocus();
        else if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    private void addTile(ViewGroup parent, final String key, String label, Drawable banner, int[] lineIcon, Drawable appIcon,
                         int x, int y, int w, int h) {
        addTile(parent, key, label, banner, lineIcon, appIcon, x, y, w, h, 0);
    }

    private void addTile(ViewGroup parent, final String key, String label, Drawable banner, int[] lineIcon, Drawable appIcon,
                         int x, int y, int w, int h, int inputPort) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        final boolean hdmi = isInput(key);
        final int state = hdmi ? inputState(key) : -1;
        if (banner != null) {
            bg.setColor(0xFF1E1A1C);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(fit(banner, w, h));
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // Quiet "glass" tile: icon above label.
            bg.setColor(0x13FFFFFF);
            bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            int size = lineIcon != null ? px(44) : px(64);
            if (lineIcon != null) iv.setImageDrawable(new LineIcon(lineIcon));
            else if (appIcon != null) iv.setImageDrawable(fit(appIcon, size, size));
            View dot = null;
            if (hdmi) {
                iv.setAlpha(inputIconAlpha(state));
                FrameLayout iconBox = new FrameLayout(this);
                iconBox.addView(iv, new FrameLayout.LayoutParams(size, size));
                TextView badge = text(px(20), Color.WHITE, semibold);
                badge.setText(inputPort > 0 ? String.valueOf(inputPort) : "?");
                badge.setGravity(Gravity.CENTER);
                badge.setIncludeFontPadding(false);
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setShape(GradientDrawable.OVAL);
                badgeBg.setColor(0xCC000000);
                badgeBg.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
                badge.setBackground(badgeBg);
                iconBox.addView(badge, new FrameLayout.LayoutParams(px(28), px(28), Gravity.TOP | Gravity.END));
                dot = new View(this);
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setColor(inputStateColor(state));
                dotBg.setStroke(Math.max(1, px(2)), 0xCC000000);
                dot.setBackground(dotBg);
                iconBox.addView(dot, new FrameLayout.LayoutParams(px(14), px(14), Gravity.BOTTOM | Gravity.END));
                inner.addView(iconBox, new LinearLayout.LayoutParams(size, size));
            } else {
                inner.addView(iv, new LinearLayout.LayoutParams(size, size));
            }
            TextView t = text(px(24), 0xE0FFFFFF, regular);
            t.setText(label);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(16), 0, px(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = px(12);
            inner.addView(t, lp);
            if (hdmi) {
                TextView status = text(px(18), 0xCCFFFFFF, regular);
                status.setText(inputStateLabel(state));
                status.setSingleLine(true);
                status.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
                sp.topMargin = px(2);
                inner.addView(status, sp);
                hdmiTiles.put(key, new HdmiTile(card, iv, dot, status, label));
                card.setContentDescription(label + ", " + inputStateLabel(state));
            }
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setTag(key);

        // Banner tiles show their name underneath when focused.
        final TextView caption = banner == null ? null : text(px(24), Color.WHITE, regular);
        if (caption != null) {
            caption.setText(label);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            caption.setVisibility(View.INVISIBLE);
        }

        final boolean glass = banner == null;
        card.setOnFocusChangeListener((v, has) -> {
            if (has) focusedKey = key;
            if (glass) bg.setColor(has ? 0x38FFFFFF : 0x13FFFFFF);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? px(26) : 0)
                    .setDuration(140).withLayer().start();
            if (caption != null) caption.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        final boolean editable = rowApps.contains(key);
        final boolean settings = SETTINGS.equals(key);
        card.setOnClickListener(v -> open(key));
        if (editable) card.setOnLongClickListener(v -> { showEditMenu(key); return true; });
        card.setOnKeyListener((v, code, ev) -> {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (key.equals(movingKey)) return moveKey(key, code, ok, down);
            if ((editable || settings) && code == KeyEvent.KEYCODE_MENU) {
                if (!down) {
                    if (settings) showHomeSettings();
                    else showEditMenu(key);
                }
                return true;
            }
            if (!ok) return false;
            if (down) {
                if (ev.getRepeatCount() == 0) longPressed = false;
                else if ((editable || settings) && !longPressed && (ev.isLongPress()
                        || ev.getEventTime() - ev.getDownTime() >= ViewConfiguration.getLongPressTimeout())) {
                    longPressed = true;
                    if (settings) showHomeSettings();
                    else showEditMenu(key);
                }
            } else if (!longPressed) {
                open(key);
            }
            return true;
        });

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(w, h));
        if (caption != null) {
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(w, -2);
            cp.topMargin = h + px(22);
            wrap.addView(caption, cp);
        }
        parent.addView(wrap, at(x, y, w, -2));
        tiles.add(card);
    }

    private void open(String key) {
        Intent i;
        if (ALL.equals(key)) {
            showAllApps();
            return;
        } else if (SETTINGS.equals(key)) {
            i = new Intent(Settings.ACTION_SETTINGS);
        } else if (isInput(key)) {
            openInput(key);
            return;
        } else {
            i = launchIntent(getPackageManager(), key);
            if (i == null) return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    // Live TV packages and activities that can tune an HDMI passthrough input on Xiaomi/Amlogic TVs.
    private static final String[] LIVE_TV = {"com.android.tv", "com.droidlogic.droidlivetv",
            "com.droidlogic.android.tv"};
    private static final String[][] LIVE_ACTIVITIES = {
            {"com.android.tv", "com.android.tv.TvActivity"},
            {"com.droidlogic.droidlivetv", "com.droidlogic.droidlivetv.DroidLiveTvActivity"}};

    // Opens an HDMI input. The passthrough URI is what actually selects the input, so try the
    // system handler first, then each Live TV package directly. If a TV only exposes a plain
    // launcher activity, fall back to it. This replaces the old hardcoded activity names, which
    // did not match the Mi TV.
    private void openInput(String id) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        List<Intent> tries = new ArrayList<>();
        tries.add(view);
        for (String pkg : LIVE_TV) {
            Intent t = new Intent(view).setPackage(pkg);
            if (t.resolveActivity(getPackageManager()) != null) tries.add(t);
        }
        // The Mi TV's Live TV entry points, with the passthrough URI attached.
        for (String[] live : LIVE_ACTIVITIES) {
            Intent t = new Intent(view).setClassName(live[0], live[1]);
            if (t.resolveActivity(getPackageManager()) != null) tries.add(t);
        }
        for (String pkg : LIVE_TV) {
            Intent t = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (t != null) {
                t.putExtra("input_id", id);
                tries.add(t);
            }
        }
        for (Intent t : tries) {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(t);
                return;
            } catch (Exception ignored) {}
        }
        toast("Could not open this input");
    }

    interface Pick { void at(int which); }
    interface LongPick { void at(int which); }

    private void panel(String title, final List<String> labels, final List<String> iconPkgs, final Pick pick) {
        panel(title, labels, iconPkgs, pick, null);
    }

    // A side panel in the home screen's own look: a title, then one row per choice, with the same
    // glass focus as the tiles. Back closes it. iconPkgs (or null) puts each app's icon by its name.
    private void panel(String title, final List<String> labels, final List<String> iconPkgs, final Pick pick,
                       final LongPick longPick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(48), px(92), px(48), px(48));

        TextView head = text(px(40), Color.WHITE, semibold);
        head.setText(title);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(px(24), 0, px(24), 0);
        box.addView(head);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(RADIUS));
        focus.setColor(0x38FFFFFF);
        list.setSelector(focus);
        // Icons load as rows appear, drawn small to save RAM.
        final PackageManager pm = getPackageManager();
        final int size = px(48);
        final Drawable[] icons = new Drawable[labels.size()];
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.size(); }
            @Override public Object getItem(int pos) { return labels.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = convert != null ? (TextView) convert : text(px(30), 0xE6FFFFFF, regular);
                t.setText(labels.get(pos));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(px(88));
                t.setPadding(px(24), 0, px(24), 0);
                Drawable icon = null;
                if (iconPkgs != null) {
                    if (icons[pos] == null) {
                        try { icons[pos] = fit(pm.getApplicationIcon(iconPkgs.get(pos)), size, size); }
                        catch (Exception e) { icons[pos] = new ColorDrawable(Color.TRANSPARENT); }
                        icons[pos].setBounds(0, 0, size, size);
                    }
                    icon = icons[pos];
                }
                t.setCompoundDrawablesRelative(icon, null, null, null);
                t.setCompoundDrawablePadding(px(24));
                return t;
            }
        });
        list.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            pick.at(pos);
        });
        list.setOnItemLongClickListener((parent, v, pos, id) -> {
            if (longPick == null) return false;
            d.dismiss();
            longPick.at(pos);
            return true;
        });
        // D-pad long-press is not reliable on every TV remote, so Menu does the same job.
        list.setOnKeyListener((v, code, ev) -> {
            if (longPick == null || code != KeyEvent.KEYCODE_MENU) return false;
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                int pos = list.getSelectedItemPosition();
                if (pos >= 0) {
                    d.dismiss();
                    longPick.at(pos);
                }
            }
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = px(36);
        box.addView(list, lp);

        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0xF7161113));
        w.setLayout(px(640), -1);
        w.setGravity(Gravity.END);
        w.setDimAmount(0.5f);
        d.show();
        list.requestFocus();
        if (list.getSelectedItemPosition() < 0) list.setSelection(0);
    }

    private void appGrid(String title, final List<String> labels, final List<String> iconPkgs, final Pick pick,
                         final LongPick longPick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(MARGIN), px(64), px(MARGIN), px(48));

        TextView head = text(px(40), Color.WHITE, semibold);
        head.setText(title);
        box.addView(head);

        int count = 0;
        for (String p : iconPkgs) if (p != null) count++;
        TextView sub = text(px(24), 0xA8FFFFFF, regular);
        sub.setText(count == 1 ? "1 app" : count + " apps");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = px(8);
        box.addView(sub, sp);

        final PackageManager pm = getPackageManager();
        final int iconSize = px(64);
        final Drawable[] icons = new Drawable[labels.size()];
        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(px(24));
        grid.setVerticalSpacing(px(18));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(true);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(RADIUS));
        focus.setColor(0x38FFFFFF);
        grid.setSelector(focus);
        grid.setClipToPadding(false);
        grid.setPadding(0, px(28), 0, 0);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.size(); }
            @Override public Object getItem(int pos) { return labels.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                LinearLayout item = new LinearLayout(MainActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER);
                item.setPadding(px(12), px(14), px(12), px(12));
                item.setLayoutParams(new AbsListView.LayoutParams(-1, px(160)));

                ImageView iv = new ImageView(MainActivity.this);
                iv.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
                String pkg = iconPkgs.get(pos);
                if (pkg != null) {
                    if (icons[pos] == null) {
                        try { icons[pos] = fit(pm.getApplicationIcon(pkg), iconSize, iconSize); }
                        catch (Exception e) { icons[pos] = new ColorDrawable(Color.TRANSPARENT); }
                        icons[pos].setBounds(0, 0, iconSize, iconSize);
                    }
                    iv.setImageDrawable(icons[pos]);
                } else {
                    iv.setImageDrawable(new LineIcon(LineIcon.APPS));
                }
                item.addView(iv);

                TextView t = text(px(24), 0xE6FFFFFF, regular);
                t.setText(labels.get(pos));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = px(10);
                item.addView(t, lp);
                return item;
            }
        });
        grid.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            pick.at(pos);
        });
        grid.setOnItemLongClickListener((parent, v, pos, id) -> {
            if (longPick == null) return false;
            d.dismiss();
            longPick.at(pos);
            return true;
        });
        grid.setOnKeyListener((v, code, ev) -> {
            if (longPick == null || code != KeyEvent.KEYCODE_MENU) return false;
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                int pos = grid.getSelectedItemPosition();
                if (pos >= 0) {
                    d.dismiss();
                    longPick.at(pos);
                }
            }
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = px(28);
        box.addView(grid, lp);

        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0xF7161113));
        w.setLayout(-1, -1);
        w.setDimAmount(0f);
        d.show();
        grid.requestFocus();
        if (grid.getSelectedItemPosition() < 0) grid.setSelection(0);
    }

    // A small menu that drops down under a tile, in the same glass as the tiles. Opens above the
    // tile when there's no room below. Back closes it.
    private void dropdown(View anchor, String[] items, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = px(10), rowH = px(64);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        bg.setColor(0xFF231D1F);
        bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
        box.setBackground(bg);
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            final TextView t = text(px(26), 0xD9FFFFFF, regular);
            t.setText(items[i]);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(22), 0, px(22), 0);
            final GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(px(RADIUS - pad));
            focus.setColor(Color.TRANSPARENT);
            t.setBackground(focus);
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                focus.setColor(has ? 0x38FFFFFF : Color.TRANSPARENT);
                t.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
            });
            t.setOnClickListener(v -> {
                d.dismiss();
                pick.at(which);
            });
            box.addView(t, new LinearLayout.LayoutParams(-1, rowH));
        }
        d.setContentView(box);

        // Line it up with the tile as drawn (focused tiles are scaled up), below its name.
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int tileBottom = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY());
        int w = Math.max(Math.round(anchor.getWidth() * anchor.getScaleX()), px(320));
        int h = items.length * rowH + 2 * pad;
        int y = tileBottom + px(64);
        if (y + h > getResources().getDisplayMetrics().heightPixels - px(24)) y = at[1] - h - px(16);
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setGravity(Gravity.TOP | Gravity.START);
        android.view.WindowManager.LayoutParams lp = win.getAttributes();
        lp.x = at[0];
        lp.y = y;
        lp.width = w;
        lp.height = h;
        win.setAttributes(lp);
        d.show();
    }

    // Hold OK (or Menu) on a top-row app: move it, swap it for another, or take it off the home
    // screen. Removing only takes the tile away: the app stays installed and can be added back.
    private void showEditMenu(final String key) {
        final int index = rowApps.indexOf(key);
        if (index < 0) return;
        dropdown(tile(key), EDIT_ITEMS, which -> {
            if (which == 0) startMove(key);
            else if (which == 1) rename(key);
            else if (which == 2) showAddMenu(index);
            else if (which == 3) appInfo(key);
            else if (which == 4) hideApp(key);
            else removeFromHome(key);
        });
    }

    // Every launchable app that isn't on the top row yet, by name. The pick is added at the end,
    // or takes the place of the app at replaceIndex (-1 to add).
    private void showAddMenu(final int replaceIndex) {
        final PackageManager pm = getPackageManager();
        TreeMap<String, List<String>> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> seen = new HashSet<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (rowApps.contains(p) || !seen.add(p) || p.equals(getPackageName())
                        || isHidden(p) || (NOT_APPS.contains(p) && !p.equals(PLAY_STORE))) continue;
                String label = labelOf(p);
                List<String> list = byLabel.get(label);
                if (list == null) { list = new ArrayList<>(); byLabel.put(label, list); }
                list.add(p);
            }
        }
        if (byLabel.isEmpty()) {
            panel("Every app is already here", Collections.singletonList("OK"), null, which -> {});
            return;
        }
        final List<String> pkgs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byLabel.entrySet()) {
            for (String p : entry.getValue()) {
                labels.add(entry.getKey());
                pkgs.add(p);
            }
        }
        String title = replaceIndex < 0 ? "Add an app" : "Replace " + labelOf(rowApps.get(replaceIndex));
        panel(title, labels, pkgs, which -> {
            if (replaceIndex < 0) rowApps.add(pkgs.get(which));
            else rowApps.set(replaceIndex, pkgs.get(which));
            saveRow();
            focusedKey = pkgs.get(which);
            buildTiles();
        });
    }

    private String labelOf(String pkg) {
        String custom = getSharedPreferences(PREFS, 0).getString(LABEL + pkg, null);
        if (custom != null && !custom.trim().isEmpty()) return custom;
        return defaultLabel(pkg);
    }

    private String defaultLabel(String pkg) {
        PackageManager pm = getPackageManager();
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    // Saves the top row as the app list, so it no longer follows apps.txt.
    private void saveRow() {
        getSharedPreferences(PREFS, 0).edit().putString("apps", android.text.TextUtils.join(",", rowApps)).apply();
    }

    // Every launchable app, A-Z. Hidden apps stay out until "Show hidden apps" is picked.
    // Selecting launches; holding OK opens pin/rename/app-info/hide actions.
    private void showAllApps() {
        final PackageManager pm = getPackageManager();
        final Set<String> hidden = new HashSet<>();
        for (String p : hiddenApps()) if (launchIntent(pm, p) != null) hidden.add(p);
        TreeMap<String, List<String>> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> seen = new HashSet<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || LAUNCHERS.contains(p) || !seen.add(p)) continue;
                boolean hiddenApp = hidden.contains(p);
                if (hiddenApp && !showHidden) continue;
                if (launchIntent(pm, p) == null) continue;
                String label = labelOf(p) + (hiddenApp ? "  (hidden)" : "");
                List<String> list = byLabel.get(label);
                if (list == null) { list = new ArrayList<>(); byLabel.put(label, list); }
                list.add(p);
            }
        }
        if (byLabel.isEmpty() && hidden.isEmpty()) {
            panel("All apps", Collections.singletonList("No apps found"), null, which -> {});
            return;
        }
        final List<String> pkgs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byLabel.entrySet()) {
            for (String p : entry.getValue()) {
                labels.add(entry.getKey());
                pkgs.add(p);
            }
        }
        final boolean hasHidden = !hidden.isEmpty();
        if (hasHidden) {
            labels.add(0, (showHidden ? "Hide hidden apps" : "Show hidden apps") + "  (" + hidden.size() + ")");
            pkgs.add(0, null);
        }
        if (!getSharedPreferences(PREFS, 0).getBoolean("all_apps_hint", false)) {
            getSharedPreferences(PREFS, 0).edit().putBoolean("all_apps_hint", true).apply();
            toast("Hold OK (or press Menu) on an app for pin, rename, app info and hide");
        }
        appGrid("All apps", labels, pkgs, which -> {
            if (hasHidden && which == 0) {
                showHidden = !showHidden;
                root.post(this::showAllApps);
                return;
            }
            if (which >= 0 && which < pkgs.size() && pkgs.get(which) != null) open(pkgs.get(which));
        }, which -> {
            if (which >= 0 && which < pkgs.size() && pkgs.get(which) != null) {
                showAppActions(pkgs.get(which));
            }
        });
    }

    // Actions for one app in All apps. Pinning is the replacement for the old "Add app" tile.
    private void showAppActions(final String pkg) {
        final boolean onHome = rowApps.contains(pkg);
        final boolean hidden = isHidden(pkg);
        List<String> items = Arrays.asList(
                onHome ? "Remove from home" : "Pin to home",
                "Rename",
                "App info",
                hidden ? "Unhide app" : "Hide app");
        panel(labelOf(pkg), items, null, which -> {
            if (which == 0) {
                if (onHome) removeFromHome(pkg);
                else pinToHome(pkg);
            } else if (which == 1) {
                rename(pkg);
            } else if (which == 2) {
                appInfo(pkg);
            } else if (hidden) {
                unhideApp(pkg);
            } else {
                hideApp(pkg);
            }
        });
    }

    private void pinToHome(String pkg) {
        if (isHidden(pkg)) setHidden(pkg, false);
        if (!rowApps.contains(pkg)) {
            rowApps.add(pkg);
            saveRow();
        }
        focusedKey = pkg;
        buildTiles();
        toast("Pinned to home");
    }

    private void removeFromHome(String pkg) {
        int index = rowApps.indexOf(pkg);
        if (index < 0) return;
        rowApps.remove(index);
        saveRow();
        focusedKey = rowApps.isEmpty() ? ALL : rowApps.get(Math.min(index, rowApps.size() - 1));
        buildTiles();
        toast("Removed from home");
    }

    private void hideApp(String pkg) {
        setHidden(pkg, true);
        int index = rowApps.indexOf(pkg);
        if (index >= 0) {
            rowApps.remove(index);
            saveRow();
            focusedKey = rowApps.isEmpty() ? ALL : rowApps.get(Math.min(index, rowApps.size() - 1));
        }
        buildTiles();
        toast("Hidden. All apps > Show hidden apps to restore.");
    }

    private void unhideApp(String pkg) {
        setHidden(pkg, false);
        buildTiles();
        toast("App unhidden");
    }

    private Set<String> hiddenApps() {
        return new HashSet<>(getSharedPreferences(PREFS, 0)
                .getStringSet(HIDDEN, Collections.emptySet()));
    }

    private boolean isHidden(String pkg) {
        return hiddenApps().contains(pkg);
    }

    private void setHidden(String pkg, boolean hidden) {
        Set<String> set = hiddenApps();
        if (hidden) set.add(pkg);
        else set.remove(pkg);
        getSharedPreferences(PREFS, 0).edit().putStringSet(HIDDEN, set).apply();
    }

    // Renaming works from a home tile or the All apps action panel. Blank restores the app's name.
    private void rename(final String pkg) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(40), px(32), px(40), px(32));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF231D1F);
        bg.setCornerRadius(px(RADIUS));
        bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
        box.setBackground(bg);

        TextView title = text(px(32), Color.WHITE, semibold);
        title.setText("Rename " + labelOf(pkg));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(title);

        final EditText input = new EditText(this);
        input.setText(labelOf(pkg));
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x88FFFFFF);
        input.setHint("Leave blank for the app's own name");
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, px(72));
        ip.topMargin = px(20);
        box.addView(input, ip);

        final TextView save = text(px(28), Color.WHITE, semibold);
        save.setText("Save");
        save.setGravity(Gravity.CENTER);
        save.setFocusable(true);
        final GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(RADIUS));
        focus.setColor(0x13FFFFFF);
        save.setBackground(focus);
        save.setOnFocusChangeListener((v, has) -> focus.setColor(has ? 0x38FFFFFF : 0x13FFFFFF));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, px(72));
        sp.topMargin = px(18);
        box.addView(save, sp);

        final TextView reset = text(px(24), 0xB3FFFFFF, regular);
        reset.setText("Use the app's own name");
        reset.setGravity(Gravity.CENTER);
        reset.setFocusable(true);
        reset.setPadding(0, px(12), 0, px(12));
        reset.setOnFocusChangeListener((v, has) -> reset.setTextColor(has ? Color.WHITE : 0xB3FFFFFF));
        reset.setOnClickListener(v -> {
            getSharedPreferences(PREFS, 0).edit().remove(LABEL + pkg).apply();
            d.dismiss();
            buildTiles();
            toast("Name reset");
        });
        box.addView(reset, new LinearLayout.LayoutParams(-1, px(56)));

        save.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            SharedPreferences.Editor e = getSharedPreferences(PREFS, 0).edit();
            if (name.isEmpty() || name.equals(defaultLabel(pkg))) e.remove(LABEL + pkg);
            else e.putString(LABEL + pkg, name);
            e.apply();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            d.dismiss();
            buildTiles();
        });
        input.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                save.performClick();
                return true;
            }
            return false;
        });

        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setLayout(px(760), -2);
        w.setGravity(Gravity.CENTER);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        input.requestFocus();
        input.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void appInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (Exception e) { toast("Could not open app info"); }
    }

    // Hold OK on the Settings tile (or press Menu there) to open this.
    private void showHomeSettings() {
        final String theme = getThemeName();
        List<String> items = Arrays.asList(
                "Theme: " + themeLabel(theme),
                "Hidden apps",
                "Reset home layout",
                "About");
        panel("Home settings", items, null, which -> {
            if (which == 0) showThemeMenu();
            else if (which == 1) showHiddenApps();
            else if (which == 2) confirmReset();
            else showAbout();
        });
    }

    private void showThemeMenu() {
        final String current = getThemeName();
        List<String> items = Arrays.asList(
                "warm".equals(current) ? "Warm  (selected)" : "Warm",
                "black".equals(current) ? "Black (OLED)  (selected)" : "Black (OLED)",
                "blue".equals(current) ? "Blue  (selected)" : "Blue");
        panel("Theme", items, null, which -> {
            String theme = which == 0 ? "warm" : which == 1 ? "black" : "blue";
            getSharedPreferences(PREFS, 0).edit().putString(THEME, theme).apply();
            getWindow().setBackgroundDrawable(background());
            toast("Theme: " + themeLabel(theme));
        });
    }

    private String getThemeName() {
        return getSharedPreferences(PREFS, 0).getString(THEME, "warm");
    }

    private static String themeLabel(String theme) {
        if ("black".equals(theme)) return "Black (OLED)";
        if ("blue".equals(theme)) return "Blue";
        return "Warm";
    }

    private void showHiddenApps() {
        final PackageManager pm = getPackageManager();
        final Set<String> hidden = new HashSet<>();
        for (String p : hiddenApps()) if (launchIntent(pm, p) != null) hidden.add(p);
        if (hidden.isEmpty()) {
            panel("Hidden apps", Collections.singletonList("No hidden apps"), null, which -> {});
            return;
        }
        final List<String> pkgs = new ArrayList<>(hidden);
        Collections.sort(pkgs, (a, b) -> labelOf(a).compareToIgnoreCase(labelOf(b)));
        final List<String> labels = new ArrayList<>();
        for (String p : pkgs) labels.add(labelOf(p));
        panel("Hidden apps", labels, pkgs, which -> {
            setHidden(pkgs.get(which), false);
            toast("App unhidden");
            root.post(this::showHiddenApps);
        });
    }

    private void confirmReset() {
        panel("Reset home layout?", Arrays.asList("Cancel", "Reset apps and names"), null, which -> {
            if (which != 1) return;
            SharedPreferences prefs = getSharedPreferences(PREFS, 0);
            SharedPreferences.Editor e = prefs.edit();
            e.remove("apps");
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (entry.getKey().startsWith(LABEL)) e.remove(entry.getKey());
            }
            e.apply();
            focusedKey = ALL;
            buildTiles();
            toast("Home layout reset");
        });
    }

    private void showAbout() {
        String version = "1.2.0-personal";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
        panel("About", Arrays.asList(
                "Darsha Home " + version,
                "Package: " + getPackageName(),
                "Built locally for this TV",
                "No permissions. No internet access.",
                "Hold OK (or press Menu) on Settings for Home settings",
                "In All apps, hold OK (or press Menu) on an app for actions"), null, which -> {});
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // Where app number i sits in the grid.
    private int gridX(int i) {
        return px(MARGIN) + (i % PER_ROW) * (tileW + px(GAP));
    }

    private int gridY(int i) {
        return gridTop + (i / PER_ROW) * px(ROW_STEP);
    }

    private View tile(String key) {
        for (View t : tiles) if (t.getTag().equals(key)) return t;
        return null;
    }

    // Move mode: the tile gets an outline, the arrows move it through the grid, OK or Back finishes.
    private void startMove(String key) {
        View card = tile(key);
        if (card == null) return;
        movingKey = key;
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(px(RADIUS));
        outline.setStroke(px(4), Color.WHITE);
        ((FrameLayout) card).setForeground(outline);
        moveHint.setVisibility(View.VISIBLE);
        card.requestFocus();
    }

    private boolean moveKey(String key, int code, boolean ok, boolean down) {
        int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : code == KeyEvent.KEYCODE_DPAD_UP ? -PER_ROW : code == KeyEvent.KEYCODE_DPAD_DOWN ? PER_ROW : 0;
        if (step != 0) {
            int from = rowApps.indexOf(key), to = from + step;
            if (down && to >= 0 && to < rowApps.size()) {
                // Slide the tiles into their new places: no rebuild, so it stays quick on slow TVs.
                rowApps.add(to, rowApps.remove(from));
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                    View wrap = (View) tile(rowApps.get(i)).getParent();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wrap.getLayoutParams();
                    lp.leftMargin = gridX(i);
                    lp.topMargin = gridY(i);
                    wrap.setLayoutParams(lp);
                }
                // Scroll along with it once it has moved.
                final View card = tile(key);
                card.post(() -> card.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight())));
            }
            return true;
        }
        if (ok || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                movingKey = null;
                longPressed = false;
                ((FrameLayout) tile(key)).setForeground(null);
                moveHint.setVisibility(View.GONE);
                saveRow();
            }
            return true;
        }
        return true; // up/down and everything else stay put while moving
    }

    private static boolean isInput(String key) {
        return key.indexOf('/') > 0;
    }

    private int inputState(String id) {
        if (tvInputManager == null) tvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tvInputManager == null) return -1;
        try { return tvInputManager.getInputState(id); } catch (Exception ignored) { return -1; }
    }

    private static String inputStateLabel(int state) {
        if (state == TvInputManager.INPUT_STATE_CONNECTED) return "Active";
        if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) return "Standby";
        if (state == TvInputManager.INPUT_STATE_DISCONNECTED) return "No signal";
        return "Unknown";
    }

    private static int inputStateColor(int state) {
        if (state == TvInputManager.INPUT_STATE_CONNECTED) return 0xFF4CAF50;
        if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) return 0xFFFFB300;
        if (state == TvInputManager.INPUT_STATE_DISCONNECTED) return 0xFF9E9E9E;
        return 0xFF757575;
    }

    private static float inputIconAlpha(int state) {
        if (state == TvInputManager.INPUT_STATE_CONNECTED) return 1f;
        if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) return 0.72f;
        return 0.45f;
    }

    private void updateHdmiTile(String id, int state) {
        HdmiTile t = hdmiTiles.get(id);
        if (t == null) return;
        t.icon.setAlpha(inputIconAlpha(state));
        Drawable dot = t.dot.getBackground();
        if (dot instanceof GradientDrawable) ((GradientDrawable) dot).setColor(inputStateColor(state));
        t.status.setText(inputStateLabel(state));
        t.card.setContentDescription(t.label + ", " + inputStateLabel(state));
    }

    private void refreshHdmiStates() {
        for (String id : new ArrayList<>(hdmiTiles.keySet())) updateHdmiTile(id, inputState(id));
    }

    private void refreshInputLayout() {
        dirty = true;
        if (resumed) {
            dirty = false;
            buildTiles();
        }
    }

    private int portNumber(TvInputInfo in) {
        if (in == null) return 0;
        int n = numberAfter(in.getId().toLowerCase(java.util.Locale.US), "hdmi");
        if (n > 0) return n;
        CharSequence label = in.loadLabel(this);
        n = label == null ? 0 : firstNumber(label.toString());
        return n > 0 ? n : 0;
    }

    private static int numberAfter(String s, String token) {
        int i = s.indexOf(token);
        return i < 0 ? 0 : firstNumber(s.substring(i + token.length()));
    }

    private static int firstNumber(String s) {
        int n = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                if (n < 0) n = 0;
                n = n * 10 + (c - '0');
            } else if (n >= 0) {
                break;
            }
        }
        return n > 0 ? n : 0;
    }

    // The TV's own HDMI inputs (not devices behind them), in order, skipping hidden ones.
    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        if (tvInputManager == null) tvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tvInputManager == null) return out;
        for (TvInputInfo in : tvInputManager.getTvInputList()) {
            if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null && !in.isHidden(this)) out.add(in);
        }
        Collections.sort(out, (a, b) -> {
            int pa = portNumber(a), pb = portNumber(b);
            if (pa > 0 && pb > 0 && pa != pb) return pa - pb;
            return a.getId().compareTo(b.getId());
        });
        return out.size() > MAX_INPUTS ? out.subList(0, MAX_INPUTS) : out;
    }

    // The name set in Settings > Inputs (like "Bedroom"), else the TV's own name (like "HDMI 1").
    private String inputLabel(TvInputInfo in) {
        CharSequence custom = in.loadCustomLabel(this);
        if (custom != null && custom.length() > 0) return custom.toString();
        CharSequence label = in.loadLabel(this);
        return label != null && label.length() > 0 ? label.toString() : "HDMI";
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    // Xiaomi TVs ship MiLan Pro but don't register it by name; every other TV gets Roboto.
    private static void loadFonts() {
        if (regular != null) return;
        regular = fromFile("/system/fonts/MILanPro_Normal.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        semibold = fromFile("/system/fonts/MILanPro_SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    private static Typeface fromFile(String path, Typeface fallback) {
        try { return new File(path).exists() ? Typeface.createFromFile(path) : fallback; }
        catch (Exception e) { return fallback; }
    }

    // Draws a banner once into a tile-sized bitmap (center-cropped). Some apps ship huge banners
    // that Android 9 refuses to draw ("Canvas: trying to draw too large bitmap"); this also saves RAM.
    private Drawable fit(Drawable d, int w, int h) {
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
            float s = Math.max((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            d.draw(new Canvas(b));
            return new BitmapDrawable(getResources(), b);
        } catch (Throwable t) {
            return d;
        }
    }

    private int px(int v) {
        return Math.round(v * scale);
    }

    private TextClock clock(String f12, String f24, int px, int color, Typeface tf) {
        TextClock c = new TextClock(this);
        c.setFormat12Hour(f12);
        c.setFormat24Hour(f24);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        c.setTextColor(color);
        c.setTypeface(tf);
        return c;
    }

    private TextView text(int px, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }
}
