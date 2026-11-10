package com.honor.hb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
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
 * The page only offers what the Honor port actually honours:
 *  - background image (enable switch, pick / clear, opacity),
 *  - card glass (tint opacity + sheen),
 *  - about.
 * The remaining options of the source module (device card, dialpad, MMS channel,
 * random backgrounds, component scopes, ...) were removed because they depend on
 * MIUI/HyperOS internals that do not exist on MagicOS.
 *
 * Style mimics the source module (Material3 light surface, white 20dp cards,
 * green accent) while staying on plain framework widgets.
 */
final class HonorSettingsUi {

    private static final int REQ_PICK = 0x4b02;
    private static final String PREFS = "backgrounds";
    private static final String SLOT = "home";

    // Palette is set per show() call so the trimmed UI follows the system dark mode.
    // Light defaults below; show() reassigns them when uiMode reports night.
    private static int C_SURFACE = 0xFFFAF8FE;
    private static int C_CARD = 0xFFFFFFFF;
    private static int C_TEXT = 0xFF1B1B1F;
    private static int C_TEXT2 = 0xFF5D5F67;
    private static int C_GREEN = 0xFF67AD5B;
    private static int C_GREEN_TRACK = 0xFFE0E0E6;
    private static int C_GREEN_PALE = 0xFFE1EFDB;
    private static int C_GREEN_DEEP = 0xFF33691E;
    private static int C_DIVIDER = 0xFFEFEAF3;

    private static boolean isDark(Context c) {
        try {
            int night = c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

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

            LinearLayout root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(a, 20);
            root.setPadding(pad, dp(a, 16), pad, dp(a, 36));

            // header
            TextView title = new TextView(a);
            title.setText("HyperBG");
            title.setTextColor(C_TEXT);
            title.setTextSize(28);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            root.addView(title);

            TextView sub = new TextView(a);
            sub.setText("荣耀 MagicOS 精简版 · 背景与玻璃卡片");
            sub.setTextColor(C_TEXT2);
            sub.setTextSize(13);
            LinearLayout.LayoutParams lpSub = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpSub.topMargin = dp(a, 4);
            lpSub.bottomMargin = dp(a, 18);
            root.addView(sub, lpSub);

            // -- card: background --
            LinearLayout bgCard = card(a);
            boolean honorEnabled = getBoolean(sp, "honor_home_enabled", true);
            addSwitchRow(bgCard, "启用背景", "在「设置」页面显示背景图", honorEnabled,
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton b, boolean checked) {
                            putBoolean(sp, "honor_home_enabled", checked);
                        }
                    });
            String mime = getString(sp, "mime_home", "");
            addActionRow(bgCard, "背景图片",
                    mime == null || mime.length() == 0 ? "未选择 · 建议竖屏图片" : "已选择 · 点击右侧更换",
                    "选择", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pick(a);
                        }
                    });
            addActionRow(bgCard, "清除背景", "恢复系统默认外观", "清除", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearBackground(a, sp);
                }
            });
            addSliderRow(bgCard, "不透明度", getInt(sp, "opacity_home", 100), "%",
                    new int[]{0, 100}, true, new SliderWriter() {
                        @Override
                        public void onCommit(int value) {
                            putInt(sp, "opacity_home", value);
                        }
                    });
            addCard(root, a, bgCard);

            // -- card: glass --
            LinearLayout glassCard = card(a);
            boolean glassEnabled = getBoolean(sp, "honor_glass_enabled", true);
            addSwitchRow(glassCard, "玻璃卡片", "对设置卡片应用磨砂玻璃效果", glassEnabled,
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton b, boolean checked) {
                            putBoolean(sp, "honor_glass_enabled", checked);
                        }
                    });
            addDivider(glassCard, a);
            addSliderRow(glassCard, "玻璃透明度",
                    Math.round(getInt(sp, "honor_glass_alpha", 0x99) * 100f / 255f), "%",
                    new int[]{0, 100}, false, new SliderWriter() {
                        @Override
                        public void onCommit(int pct) {
                            putInt(sp, "honor_glass_alpha", Math.round(pct * 255f / 100f));
                        }
                    });
            addSliderRow(glassCard, "反光强度",
                    Math.round(getInt(sp, "honor_glass_sheen", 40) * 100f / 80f), "%",
                    new int[]{0, 100}, false, new SliderWriter() {
                        @Override
                        public void onCommit(int pct) {
                            putInt(sp, "honor_glass_sheen", Math.round(pct * 80f / 100f));
                        }
                    });
            addCard(root, a, glassCard);

            // -- card: about --
            LinearLayout about = card(a);
            TextView aboutTitle = new TextView(a);
            aboutTitle.setText("HyperBG 1.4.5-beta2 · 荣耀精简版");
            aboutTitle.setTextColor(C_TEXT);
            aboutTitle.setTextSize(16);
            aboutTitle.setTypeface(aboutTitle.getTypeface(), android.graphics.Typeface.BOLD);
            about.addView(aboutTitle);

            TextView aboutBody = new TextView(a);
            aboutBody.setText("仅作用于「设置」应用（含所有子页面）。\n"
                    + "· 背景图与玻璃卡片参数修改后，重新打开系统设置即生效。\n"
                    + "· 卡片尺寸、圆角与系统原生卡片保持一致。\n"
                    + "· 来源：HyperBackground 1.4.5-beta2（HyperOS 版）的精简移植。");
            aboutBody.setTextColor(C_TEXT2);
            aboutBody.setTextSize(13);
            aboutBody.setLineSpacing(dp(a, 3), 1f);
            LinearLayout.LayoutParams lpBody = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpBody.topMargin = dp(a, 8);
            about.addView(aboutBody, lpBody);
            addCard(root, a, about);

            sv.addView(root);
            a.setContentView(sv);
        } catch (Throwable t) {
            showFallback(a, "设置页面加载失败: " + t);
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

    private static void applyPalette(Activity a) {
        if (isDark(a)) {
            C_SURFACE = 0xFF121317;
            C_CARD = 0xFF1E1F24;
            C_TEXT = 0xFFE7E7EB;
            C_TEXT2 = 0xFFA9AAB2;
            C_GREEN = 0xFF67AD5B;
            C_GREEN_TRACK = 0xFF2C2D34;
            C_GREEN_PALE = 0xFF29361F;
            C_GREEN_DEEP = 0xFFA9D69B;
            C_DIVIDER = 0xFF2A2B31;
        } else {
            C_SURFACE = 0xFFFAF8FE;
            C_CARD = 0xFFFFFFFF;
            C_TEXT = 0xFF1B1B1F;
            C_TEXT2 = 0xFF5D5F67;
            C_GREEN = 0xFF67AD5B;
            C_GREEN_TRACK = 0xFFE0E0E6;
            C_GREEN_PALE = 0xFFE1EFDB;
            C_GREEN_DEEP = 0xFF33691E;
            C_DIVIDER = 0xFFEFEAF3;
        }
    }

    private static void applyWindow(Activity a) {
        try {
            Window w = a.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(C_SURFACE));
            w.setStatusBarColor(C_SURFACE);
            w.setNavigationBarColor(C_SURFACE);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                boolean dark = isDark(a);
                int flags = 0;
                if (!dark) {
                    // light background -> dark system icons
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
            SharedPreferences.Editor e = sp.edit();
            e.remove("mime_home");
            e.remove("size_home");
            e.remove("modified_home");
            e.remove("honor_home_enabled");
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
            toast(a, ok ? "背景已更新，重新打开设置生效" : "导入失败");
            if (ok) {
                show(a);
            }
        } catch (Throwable t) {
            toast(a, "导入失败: " + t);
        }
    }

    /**
     * Delegates the whole import to the original module code:
     * pr.p(slot, uri, mime) copies the bytes into files/backgrounds/home.bin,
     * pushes them into the remote (LSPosed) module files and records
     * mime_home / size_home / modified_home in the synced preference group.
     */
    @SuppressWarnings("unchecked")
    private static boolean importImage(Activity a, Uri uri) {
        try {
            String mime = a.getContentResolver().getType(uri);
            if (mime == null || mime.length() == 0) {
                mime = "image/jpeg";
            }
            Class<?> cls = Class.forName("pr", false, a.getClassLoader());
            Constructor<?> ctor = cls.getConstructor(Context.class);
            Object wrapper = ctor.newInstance(a);
            Method p = null;
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
     * The original module wraps SharedPreferences("backgrounds") in the obfuscated
     * class {@code pr}. Its editor synchronises every change into the remote preference
     * channel the hook side reads through getRemotePreferences("backgrounds").
     */
    private static SharedPreferences prefs(Context c) {
        try {
            Class<?> cls = Class.forName("pr", false, c.getClassLoader());
            Constructor<?> ctor = cls.getConstructor(Context.class);
            Object o = ctor.newInstance(c);
            return (SharedPreferences) o;
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

    private static void putInt(SharedPreferences sp, String key, int value) {
        try {
            sp.edit().putInt(key, value).commit();
        } catch (Throwable ignored) {
        }
    }

    private static void putBoolean(SharedPreferences sp, String key, boolean value) {
        try {
            sp.edit().putBoolean(key, value).commit();
        } catch (Throwable ignored) {
        }
    }

    // ---- widgets ------------------------------------------------------------

    private interface SliderWriter {
        void onCommit(int value);
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

    private static void addDivider(LinearLayout parent, Context c) {
        View v = new View(c);
        v.setBackgroundColor(C_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        lp.topMargin = dp(c, 2);
        lp.bottomMargin = dp(c, 2);
        parent.addView(v, lp);
    }

    private static void addSwitchRow(LinearLayout parent, String title, String subtitle,
                                     boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));

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
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpS.topMargin = dp(c, 2);
        texts.addView(s, lpS);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(c);
        sw.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        int[] colors = {C_GREEN, C_GREEN_TRACK};
        try {
            sw.setTrackTintList(new ColorStateList(states, colors));
        } catch (Throwable ignored) {
        }
        try {
            sw.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFF2F0F5}));
        } catch (Throwable ignored) {
        }
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        parent.addView(row);
    }

    private static void addActionRow(LinearLayout parent, String title, String subtitle,
                                     String btnText, View.OnClickListener click) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));

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
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpS.topMargin = dp(c, 2);
        texts.addView(s, lpS);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btn = new TextView(c);
        btn.setText(btnText);
        btn.setTextColor(C_GREEN_DEEP);
        btn.setTextSize(14);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_GREEN_PALE);
        bg.setCornerRadius(dp(c, 100));
        btn.setBackground(bg);
        btn.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        btn.setOnClickListener(click);
        row.addView(btn);
        parent.addView(row);
    }

    private static void addSliderRow(LinearLayout parent, String title, int initialValue,
                                     String unit, int[] range, boolean trackFirst,
                                     final SliderWriter writer) {
        Context c = parent.getContext();
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(c, 6), 0, 0);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(C_TEXT);
        t.setTextSize(16);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(c);
        value.setTextColor(C_TEXT2);
        value.setTextSize(14);
        value.setText(initialValue + unit);
        head.addView(value);
        parent.addView(head);

        SeekBar sb = new SeekBar(c);
        sb.setMax(range[1] - range[0]);
        sb.setProgress(initialValue - range[0]);
        try {
            sb.getProgressDrawable().setTint(C_GREEN);
        } catch (Throwable ignored) {
        }
        try {
            sb.getThumb().setTint(C_GREEN);
        } catch (Throwable ignored) {
        }
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText((progress + 0) + unit);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                writer.onCommit(bar.getProgress() + 0);
            }
        });
        parent.addView(sb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
