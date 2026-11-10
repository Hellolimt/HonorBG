package com.honor.hb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Trimmed settings page that replaces the original HyperOS-only Compose UI inside
 * our own module process.
 *
 * <p>Only what the Honor port honours is offered: background image, the liquid-glass recipe,
 * the in-block divider, geometry, and a one-tap reset. All glass keys and their defaults live
 * in {@link GlassConfig}; this class never hard-codes a key or a fallback value twice.
 *
 * <p>Every write goes through {@code pr}'s editor, which mirrors the whole preference map into
 * the LSPosed remote channel on commit, and bumps {@link GlassConfig#KEY_VERSION} in the same
 * editor. The settings app picks that up on its next onResume and repaints in place - no
 * reboot and no force-stop needed.
 *
 * <p>Style mimics the source module (Material3 light surface, white 20dp cards, green accent)
 * while staying on plain framework widgets.
 */
final class HonorSettingsUi {

    private static final int REQ_PICK = 0x4b02;
    private static final String SLOT = "home";

    private static final int[] SWATCHES = {
            0xFFFFFF, 0xB0B0B8, 0x64B5F6, 0x4DD0E1,
            0x81C784, 0xFFB74D, 0xE57373, 0xBA68C8
    };

    // Palette is set per show() call so the trimmed UI follows the system dark mode.
    private static int C_SURFACE = 0xFFFAF8FE;
    private static int C_CARD = 0xFFFFFFFF;
    private static int C_TEXT = 0xFF1B1B1F;
    private static int C_TEXT2 = 0xFF5D5F67;
    private static int C_GREEN = 0xFF67AD5B;
    private static int C_GREEN_TRACK = 0xFFE0E0E6;
    private static int C_GREEN_PALE = 0xFFE1EFDB;
    private static int C_GREEN_DEEP = 0xFF33691E;
    private static int C_DIVIDER = 0xFFEFEAF3;

    private HonorSettingsUi() {
    }

    // ---- page ---------------------------------------------------------------

    static void show(Activity a) {
        if (a == null || a.isFinishing()) {
            return;
        }
        SharedPreferences sp = prefs(a);
        if (sp == null) {
            showFallback(a, "模块偏好不可用（pr 类加载失败）");
            return;
        }
        try {
            applyPalette(a);
            applyWindow(a);
            ScrollView sv = new ScrollView(a);
            sv.setBackgroundColor(C_SURFACE);
            sv.setFillViewport(true);
            sv.setClipToPadding(false);
            sv.setFitsSystemWindows(true);

            LinearLayout root = page(a);
            addTitle(root, "HonorBG", "荣耀 MagicOS 精简版 · 背景与玻璃卡片");

            buildBackgroundCard(a, sp, root);
            buildGlassCard(a, sp, root);
            buildDividerCard(a, sp, root);
            buildGeometryCard(a, sp, root);
            buildAboutCard(a, sp, root);

            sv.addView(root);
            a.setContentView(sv);
        } catch (Throwable t) {
            showFallback(a, "设置页面加载失败: " + t);
        }
    }

    private static void buildBackgroundCard(Activity a, SharedPreferences sp, LinearLayout root) {
        LinearLayout card = card(a);
        section(card, "背景");
        addSwitchRow(card, "启用背景", "在「设置」页面显示背景图",
                getBoolean(sp, "honor_home_enabled", true),
                (b, checked) -> setBoolean(sp, "honor_home_enabled", checked));
        String mime = getString(sp, "mime_home", "");
        addActionRow(card, "背景图片",
                mime == null || mime.length() == 0 ? "未选择 · 建议竖屏图片" : "已选择 · 点击右侧更换",
                "选择", v -> pick(a));
        addActionRow(card, "清除背景", "恢复系统默认外观", "清除", v -> clearBackground(a, sp));
        addSlider(card, "背景不透明度", getInt(sp, "opacity_home", 100), 0, 100,
                v -> v == 100 ? "100% · 默认" : v + "%",
                v -> setInt(sp, "opacity_home", v));
        addCard(root, a, card);
    }

    private static void buildGlassCard(Activity a, SharedPreferences sp, LinearLayout root) {
        LinearLayout card = card(a);
        section(card, "液态玻璃");
        addSwitchRow(card, "玻璃效果总开关", "关闭后移除玻璃块，设置项直接透出背景图",
                getBoolean(sp, GlassConfig.KEY_GLASS_ENABLED, GlassConfig.DEF_GLASS_ENABLED),
                (b, checked) -> setBoolean(sp, GlassConfig.KEY_GLASS_ENABLED, checked));

        addSlider(card, "玻璃不透明度",
                Math.round(getInt(sp, GlassConfig.KEY_FILL_ALPHA, GlassConfig.DEF_FILL_ALPHA)
                        * 100f / 255f),
                0, 100, v -> v + "%",
                v -> setInt(sp, GlassConfig.KEY_FILL_ALPHA, Math.round(v * 255f / 100f)));
        addSlider(card, "反射 / 高光",
                Math.round(getInt(sp, GlassConfig.KEY_SHEEN, GlassConfig.DEF_SHEEN)
                        * 100f / 80f),
                0, 100, v -> v + "%",
                v -> setInt(sp, GlassConfig.KEY_SHEEN, Math.round(v * 80f / 100f)));
        addSlider(card, "折射光带",
                getInt(sp, GlassConfig.KEY_REFRACTION, GlassConfig.DEF_REFRACTION), 0, 100,
                v -> v + "%",
                v -> setInt(sp, GlassConfig.KEY_REFRACTION, v));

        addDivider(card, a);
        addSwitchRow(card, "轮廓描边", "块四周的单层渐变描边；强度为 0 时等同于关闭",
                getBoolean(sp, GlassConfig.KEY_STROKE_ENABLED, GlassConfig.DEF_STROKE_ENABLED),
                (b, checked) -> setBoolean(sp, GlassConfig.KEY_STROKE_ENABLED, checked));
        addSlider(card, "描边强度",
                getInt(sp, GlassConfig.KEY_STROKE_ALPHA, GlassConfig.DEF_STROKE_ALPHA), 0, 100,
                v -> v + "%",
                v -> setInt(sp, GlassConfig.KEY_STROKE_ALPHA, v));
        addSlider(card, "描边粗细",
                getInt(sp, GlassConfig.KEY_STROKE_WIDTH, GlassConfig.DEF_STROKE_WIDTH), 5, 30,
                v -> (v / 10f) + " dp",
                v -> setInt(sp, GlassConfig.KEY_STROKE_WIDTH, v));
        addCard(root, a, card);
    }

    private static void buildDividerCard(Activity a, SharedPreferences sp, LinearLayout root) {
        LinearLayout card = card(a);
        section(card, "组内分隔线");
        addSwitchRow(card, "显示分隔线", "同一玻璃块内两行之间的细线",
                getBoolean(sp, GlassConfig.KEY_DIVIDER_ENABLED, GlassConfig.DEF_DIVIDER_ENABLED),
                (b, checked) -> setBoolean(sp, GlassConfig.KEY_DIVIDER_ENABLED, checked));
        addSwitchRow(card, "自定义颜色", "关闭时跟随系统深浅色；开启后颜色与颜色不透明度才生效",
                getBoolean(sp, GlassConfig.KEY_DIVIDER_CUSTOM, GlassConfig.DEF_DIVIDER_CUSTOM),
                (b, checked) -> setBoolean(sp, GlassConfig.KEY_DIVIDER_CUSTOM, checked));

        addColorRow(card, a, sp, getInt(sp, GlassConfig.KEY_DIVIDER_COLOR,
                GlassConfig.DEF_DIVIDER_COLOR));
        addSlider(card, "颜色不透明度（仅自定义色）",
                getInt(sp, GlassConfig.KEY_DIVIDER_ALPHA, GlassConfig.DEF_DIVIDER_ALPHA), 0, 100,
                v -> v + "%",
                v -> setInt(sp, GlassConfig.KEY_DIVIDER_ALPHA, v));
        addSlider(card, "线宽",
                getInt(sp, GlassConfig.KEY_DIVIDER_WIDTH, GlassConfig.DEF_DIVIDER_WIDTH), 2, 40,
                v -> (v / 10f) + " px",
                v -> setInt(sp, GlassConfig.KEY_DIVIDER_WIDTH, v));
        addSlider(card, "左右内缩",
                getInt(sp, GlassConfig.KEY_DIVIDER_INSET, GlassConfig.DEF_DIVIDER_INSET), 0, 320,
                v -> (v / 10f) + " dp",
                v -> setInt(sp, GlassConfig.KEY_DIVIDER_INSET, v));
        addCard(root, a, card);
    }

    private static void buildGeometryCard(Activity a, SharedPreferences sp, LinearLayout root) {
        LinearLayout card = card(a);
        section(card, "几何（默认即完美版数值）");
        addSlider(card, "圆角半径",
                getInt(sp, GlassConfig.KEY_CORNER, GlassConfig.DEF_CORNER), 0, 300,
                v -> (v / 10f) + " dp",
                v -> setInt(sp, GlassConfig.KEY_CORNER, v));
        addSlider(card, "左右边距",
                getInt(sp, GlassConfig.KEY_H_INSET, GlassConfig.DEF_H_INSET), 0, 280,
                v -> (v / 10f) + " dp",
                v -> setInt(sp, GlassConfig.KEY_H_INSET, v));
        addSlider(card, "上下边距",
                getInt(sp, GlassConfig.KEY_V_INSET, GlassConfig.DEF_V_INSET), 0, 120,
                v -> (v / 10f) + " dp",
                v -> setInt(sp, GlassConfig.KEY_V_INSET, v));
        addCard(root, a, card);
    }

    private static void buildAboutCard(Activity a, SharedPreferences sp, LinearLayout root) {
        LinearLayout card = card(a);
        section(card, "关于");
        TextView body = new TextView(a);
        body.setText("HonorBG 1.4.6 · 荣耀 MagicOS 精简版\n"
                + "· 作者：ZOQOO   ·   GitHub：Hellolimt\n"
                + "· 参数修改后切回「设置」即生效，无需重启设备。\n"
                + "· 所有新增效果默认关闭，未调整时画面与 beta25 完美版完全一致。\n"
                + "· 恢复默认会覆写全部玻璃参数，回到 18dp 圆角 / 14dp 边距 / 1dp 描边。\n"
                + "· 颜色不透明度仅在「自定义颜色」开启时生效。\n"
                + "· 背景不透明度 100% 为默认；调低会按两层叠加压暗壁纸，不是玻璃透明度。");
        body.setTextColor(C_TEXT2);
        body.setTextSize(13);
        body.setLineSpacing(dp(a, 3), 1f);
        card.addView(body);
        addDivider(card, a);
        addActionRow(card, "恢复默认玻璃参数", "不影响背景图片与背景开关", "恢复",
                v -> restoreDefaults(a, sp));
        addActionRow(card, "项目主页", "github.com/Hellolimt/HonorBG", "打开", v -> {
            try {
                a.startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/Hellolimt/HonorBG")));
            } catch (Throwable t) {
                toast(a, "无法打开: " + t);
            }
        });
        addCard(root, a, card);
    }

    private static void restoreDefaults(Activity a, SharedPreferences sp) {
        try {
            // Overwrite, do not remove(): pr mirrors the local map into LSPosed's remote
            // channel, and a removed key is not guaranteed to disappear on the remote side.
            SharedPreferences.Editor e = editor(sp); // also bumps the version -> repaint
            GlassConfig.writeDefaults(e);
            e.commit();
            toast(a, "已恢复 beta25 默认外观");
            show(a);
        } catch (Throwable t) {
            toast(a, "恢复失败: " + t);
        }
    }

    private static void showFallback(Activity a, String msg) {
        try {
            TextView tv = new TextView(a);
            tv.setText(msg);
            tv.setTextColor(C_TEXT);
            tv.setTextSize(15);
            tv.setPadding(dp(a, 24), dp(a, 48), dp(a, 24), dp(a, 24));
            tv.setBackgroundColor(C_SURFACE);
            a.setContentView(tv);
        } catch (Throwable ignored) {
        }
    }

    // ---- palette / window ---------------------------------------------------

    private static boolean isDark(Context c) {
        try {
            int night = c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyPalette(Activity a) {
        if (isDark(a)) {
            C_SURFACE = 0xFF121317;
            C_CARD = 0xFF1E1F24;
            C_TEXT = 0xFFE7E7EB;
            C_TEXT2 = 0xFFA9AAB2;
            C_GREEN_TRACK = 0xFF2C2D34;
            C_GREEN_PALE = 0xFF29361F;
            C_GREEN_DEEP = 0xFFA9D69B;
            C_DIVIDER = 0xFF2A2B31;
        } else {
            C_SURFACE = 0xFFFAF8FE;
            C_CARD = 0xFFFFFFFF;
            C_TEXT = 0xFF1B1B1F;
            C_TEXT2 = 0xFF5D5F67;
            C_GREEN_TRACK = 0xFFE0E0E6;
            C_GREEN_PALE = 0xFFE1EFDB;
            C_GREEN_DEEP = 0xFF33691E;
            C_DIVIDER = 0xFFEFEAF3;
        }
        C_GREEN = 0xFF67AD5B;
    }

    private static void applyWindow(Activity a) {
        try {
            Window w = a.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(C_SURFACE));
            w.setStatusBarColor(C_SURFACE);
            w.setNavigationBarColor(C_SURFACE);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                int flags = 0;
                if (!isDark(a)) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                w.getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- background actions -------------------------------------------------

    private static void pick(Activity a) {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("image/*");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivityForResult(it, REQ_PICK);
        } catch (Throwable t) {
            toast(a, "无法打开图片选择器: " + t);
        }
    }

    private static void clearBackground(Activity a, SharedPreferences sp) {
        try {
            // Explicit empty values, for the same remote-mirror reason as restoreDefaults():
            // mime="" and modified=-1 both make AppearanceState report "no wallpaper".
            SharedPreferences.Editor e = editor(sp);
            e.putString("mime_home", "");
            e.putLong("modified_home", -1L);
            e.putInt("size_home", 0);
            e.putBoolean("honor_home_enabled", true);
            e.commit();
            java.io.File local = new java.io.File(a.getFilesDir(), "backgrounds/" + SLOT + ".bin");
            local.delete();
            toast(a, "已清除背景");
            show(a);
        } catch (Throwable t) {
            toast(a, "清除失败: " + t);
        }
    }

    static void onPickResult(Activity a, int requestCode, int resultCode, Intent data) {
        if (a == null || requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        try {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            boolean ok = importImage(a, uri);
            toast(a, ok ? "背景已更新，切回设置即生效" : "导入失败");
            if (ok) {
                show(a);
            }
        } catch (Throwable t) {
            toast(a, "导入失败: " + t);
        }
    }

    /** Delegates the import to the original module code: pr.p(slot, uri, mime). */
    private static boolean importImage(Activity a, Uri uri) {
        try {
            String mime = a.getContentResolver().getType(uri);
            if (mime == null || mime.length() == 0) {
                mime = "image/jpeg";
            }
            Class<?> cls = Class.forName("pr", false, a.getClassLoader());
            Constructor<?> ctor = cls.getConstructor(Context.class);
            Object wrapper = ctor.newInstance(a);
            Method p;
            try {
                p = cls.getMethod("p", String.class, Uri.class, String.class);
            } catch (NoSuchMethodException ignored) {
                p = cls.getDeclaredMethod("p", String.class, Uri.class, String.class);
                p.setAccessible(true);
            }
            p.invoke(wrapper, SLOT, uri, mime);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- preference bridge --------------------------------------------------

    /**
     * The original module wraps SharedPreferences("backgrounds") in the obfuscated class
     * {@code pr}; its editor synchronises every change into the remote channel that the hook
     * side reads through getRemotePreferences("backgrounds").
     */
    private static SharedPreferences prefs(Context c) {
        try {
            Class<?> cls = Class.forName("pr", false, c.getClassLoader());
            Constructor<?> ctor = cls.getConstructor(Context.class);
            return (SharedPreferences) ctor.newInstance(c);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String getString(SharedPreferences sp, String key, String def) {
        try {
            return sp.getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static int getInt(SharedPreferences sp, String key, int def) {
        try {
            return sp.getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean getBoolean(SharedPreferences sp, String key, boolean def) {
        try {
            return sp.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** One editor = one remote sync, and a version bump so the renderer repaints. */
    private static SharedPreferences.Editor editor(SharedPreferences sp) {
        SharedPreferences.Editor e = sp.edit();
        e.putInt(GlassConfig.KEY_VERSION, getInt(sp, GlassConfig.KEY_VERSION, 0) + 1);
        return e;
    }

    private static void setInt(SharedPreferences sp, String key, int value) {
        try {
            editor(sp).putInt(key, value).commit();
        } catch (Throwable ignored) {
        }
    }

    private static void setBoolean(SharedPreferences sp, String key, boolean value) {
        try {
            editor(sp).putBoolean(key, value).commit();
        } catch (Throwable ignored) {
        }
    }

    // ---- widgets ------------------------------------------------------------

    private interface Formatter {
        String label(int value);
    }

    private interface Writer {
        void commit(int value);
    }

    private static LinearLayout page(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 20);
        root.setPadding(pad, dp(c, 16), pad, dp(c, 36));
        return root;
    }

    private static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(c, 20));
        card.setBackground(bg);
        card.setElevation(dp(c, 1));
        int pad = dp(c, 18);
        card.setPadding(pad, pad, pad, pad);
        return card;
    }

    private static void addCard(LinearLayout root, Context c, LinearLayout card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 14);
        root.addView(card, lp);
    }

    private static void addTitle(LinearLayout root, String title, String subtitle) {
        Context c = root.getContext();
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(C_TEXT);
        t.setTextSize(28);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(t);
        TextView s = new TextView(c);
        s.setText(subtitle);
        s.setTextColor(C_TEXT2);
        s.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 4);
        lp.bottomMargin = dp(c, 18);
        root.addView(s, lp);
    }

    private static void section(LinearLayout parent, String text) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(C_GREEN_DEEP);
        t.setTextSize(13);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 8);
        parent.addView(t, lp);
    }

    private static void addDivider(LinearLayout parent, Context c) {
        View v = new View(c);
        v.setBackgroundColor(C_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        lp.topMargin = dp(c, 8);
        lp.bottomMargin = dp(c, 8);
        parent.addView(v, lp);
    }

    /** The one text column shared by switch rows and action rows. */
    private static LinearLayout textColumn(Context c, String title, String subtitle) {
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(C_TEXT);
        t.setTextSize(16);
        texts.addView(t);
        TextView s = new TextView(c);
        s.setText(subtitle);
        s.setTextColor(C_TEXT2);
        s.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 2);
        texts.addView(s, lp);
        return texts;
    }

    private static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));
        return row;
    }

    private static void addSwitchRow(LinearLayout parent, String title, String subtitle,
                                     boolean checked, CompoundButton.OnCheckedChangeListener l) {
        Context c = parent.getContext();
        LinearLayout row = row(c);
        row.addView(textColumn(c, title, subtitle), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(c);
        sw.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        try {
            sw.setTrackTintList(new ColorStateList(states, new int[]{C_GREEN, C_GREEN_TRACK}));
            sw.setThumbTintList(new ColorStateList(states,
                    new int[]{0xFFFFFFFF, 0xFFF2F0F5}));
        } catch (Throwable ignored) {
        }
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        parent.addView(row);
    }

    private static void addActionRow(LinearLayout parent, String title, String subtitle,
                                     String btnText, View.OnClickListener click) {
        Context c = parent.getContext();
        LinearLayout row = row(c);
        row.addView(textColumn(c, title, subtitle), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button(c, btnText, click));
        parent.addView(row);
    }

    private static TextView button(Context c, String text, View.OnClickListener click) {
        TextView btn = new TextView(c);
        btn.setText(text);
        btn.setTextColor(C_GREEN_DEEP);
        btn.setTextSize(14);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_GREEN_PALE);
        bg.setCornerRadius(dp(c, 100));
        btn.setBackground(bg);
        btn.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        btn.setOnClickListener(click);
        return btn;
    }

    private static void addSlider(LinearLayout parent, String title, int value,
                                  int min, int max, Formatter fmt, final Writer writer) {
        Context c = parent.getContext();
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(c, 6), 0, 0);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(C_TEXT);
        t.setTextSize(15);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView valueView = new TextView(c);
        valueView.setTextColor(C_TEXT2);
        valueView.setTextSize(14);
        valueView.setText(fmt.label(value));
        head.addView(valueView);
        parent.addView(head);

        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        try {
            sb.getProgressDrawable().setTint(C_GREEN);
            sb.getThumb().setTint(C_GREEN);
        } catch (Throwable ignored) {
        }
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                valueView.setText(fmt.label(progress + min));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                writer.commit(bar.getProgress() + min);
            }
        });
        parent.addView(sb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Hex input + preview + eight preset swatches. */
    private static void addColorRow(LinearLayout parent, final Activity a,
                                    final SharedPreferences sp, int initialRgb) {
        Context c = parent.getContext();
        TextView title = new TextView(c);
        title.setText("颜色（#RRGGBB）");
        title.setTextColor(C_TEXT);
        title.setTextSize(15);
        LinearLayout.LayoutParams lpTitle = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTitle.topMargin = dp(c, 6);
        parent.addView(title, lpTitle);

        LinearLayout line = row(c);
        final EditText input = new EditText(c);
        input.setText(String.format("#%06X", initialRgb & 0xFFFFFF));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setTextColor(C_TEXT);
        input.setTextSize(14);
        line.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final View preview = new View(c);
        preview.setBackgroundColor(0xFF000000 | (initialRgb & 0xFFFFFF));
        LinearLayout.LayoutParams lpPreview = new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34));
        lpPreview.leftMargin = dp(c, 10);
        line.addView(preview, lpPreview);
        line.addView(button(c, "应用", v -> {
            Integer rgb = parseHex(input.getText().toString());
            if (rgb == null) {
                toast(a, "颜色格式应为 #RRGGBB");
                return;
            }
            setInt(sp, GlassConfig.KEY_DIVIDER_COLOR, rgb);
            setBoolean(sp, GlassConfig.KEY_DIVIDER_CUSTOM, true);
            preview.setBackgroundColor(0xFF000000 | rgb);
        }));
        parent.addView(line);

        LinearLayout swatches = new LinearLayout(c);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setPadding(0, dp(c, 6), 0, dp(c, 4));
        for (final int rgb : SWATCHES) {
            View dot = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF000000 | rgb);
            g.setCornerRadius(dp(c, 100));
            g.setStroke(Math.max(1, dp(c, 0.5f)), C_DIVIDER);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30));
            lp.rightMargin = dp(c, 10);
            dot.setOnClickListener(v -> {
                setInt(sp, GlassConfig.KEY_DIVIDER_COLOR, rgb);
                setBoolean(sp, GlassConfig.KEY_DIVIDER_CUSTOM, true);
                input.setText(String.format("#%06X", rgb));
                preview.setBackgroundColor(0xFF000000 | rgb);
            });
            swatches.addView(dot, lp);
        }
        parent.addView(swatches);
    }

    private static Integer parseHex(String s) {
        try {
            String t = s.trim();
            if (t.startsWith("#")) {
                t = t.substring(1);
            }
            if (t.length() == 6) {
                return (int) (Long.parseLong(t, 16) & 0xFFFFFFL);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static void toast(Activity a, String msg) {
        try {
            Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
