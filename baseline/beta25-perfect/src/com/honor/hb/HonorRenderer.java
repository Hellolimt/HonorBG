package com.honor.hb;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * Applies the module appearance to an Honor Settings activity: a full-window wallpaper layer
 * behind the content, and one glass block per system group in front of that layer.
 *
 * Two invariants drive the whole class:
 *
 *  1. <b>The first frame is already the final frame.</b> The background layer and the window
 *     background are installed inside {@code Activity.onCreate} (before the first measure), and
 *     the list is stripped in the first pre-draw pass, which runs after layout but before the
 *     first frame is drawn. Nothing that changes the window is done from a timer; a late
 *     {@code apply()} (e.g. a decor that is not measured yet) is retried from
 *     {@link ViewTreeObserver.OnPreDrawListener} and recycled rows are caught by an
 *     OnChildAttachStateChangeListener, not by polling.
 *
 *  2. <b>No per-row card, but per-row glass.</b> A group is painted as one block, split across
 *     its rows: each row carries a {@link RowGlass} share, so the block follows the row's own
 *     render node while the list scrolls. A container-wide panel cannot be used, because a
 *     RecyclerView never re-records its container's display list during a scroll, which leaves
 *     the panel frozen where it was last recorded (a translucent white residue).
 *     A strip pass stays cheap - identity checks only, no drawable build, no bitmap sampling.
 */
final class HonorRenderer {

    private static final String TAG = "HonorBackground";
    private static final String VIEW_TAG = "honor_background_layer";
    private static final long MAX_BYTES = 96L * 1024L * 1024L;
    /** Full-resolution cap for the wallpaper decode (covers a 2800px-tall screen 1:1). */
    private static final int MAX_DECODE_DIMENSION = 2880;
    /** First-frame decode cap: a soft copy is indistinguishable under the blur, and it is
     *  ready in a few ms, so the very first frame of a cold process already has a wallpaper. */
    private static final int PREVIEW_DECODE_DIMENSION = 720;

    /** Card glass: 0x99 = 60% white (bigger = more opaque). Configurable via the trimmed module UI. */
    private static volatile int sGlassAlpha = 0x99;
    /** Card glass: diagonal sheen strength added to the top-left of the block. Configurable. */
    private static volatile int sGlassSheen = 40;
    /** Master switch for the card-glass effect (honor_glass_enabled). */
    private static volatile boolean sGlassEnabled = true;
    /** Eye-care page: the block uses the high-opacity scrim fill so body text stays readable. */
    private static volatile boolean sEyePage;

    /**
     * Containers that must never be touched: the appbar / search host / system bars draw their
     * own blur + scrim and are not part of any grouped list. This is the only place where
     * resource-ids are used - as an exception list, as required.
     */
    private static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList(
            "toolbar",
            "appbar_layout",
            "collapsing_toolbar",
            "hnblurappbar_background_layout",
            "hnblurappbar_follow_header_container",
            "hnblurappbar_follow_header_divider",
            "search_layout",
            "search_framelayout",
            "search_view",
            "search_bar",
            "search_plate",
            "search_edit_frame",
            "titleContainer",
            "actionbar_layout_icon2",
            "split_action_bar",
            "content_bottom_container",
            "statusBarBackground",
            "navigationBarBackground",
            "mask_layer",
            "sv_holder",
            "scrollbarview"));

    private static final Object LOCK = new Object();
    private static final Set<String> sWatched = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    /** Lists that already carry the recycled-row listener. */
    private static final java.util.WeakHashMap<View, Boolean> sChildWatched = new java.util.WeakHashMap<>();
    /** Top-chrome views whose background was already dropped once (later passes only clamp it). */
    private static final java.util.WeakHashMap<View, Boolean> sTopChrome = new java.util.WeakHashMap<>();
    /** Lists that carry a per-frame pre-draw guard, mapped to the observer they registered on. */
    private static final java.util.WeakHashMap<ViewGroup, ViewTreeObserver> sGuardObserver =
            new java.util.WeakHashMap<>();
    /** Nested cards (2-column tiles) already found inside a block, re-cleared on every frame. */
    private static final java.util.WeakHashMap<ViewGroup, List<View>> sNestedCards =
            new java.util.WeakHashMap<>();
    /** Last full nested-card scan per list (scanning samples drawables, so it is throttled). */
    private static final java.util.WeakHashMap<ViewGroup, Long> sNestedScanAt =
            new java.util.WeakHashMap<>();
    /** Child count at the last nested-card scan: a change means "rescan now, not in 400ms". */
    private static final java.util.WeakHashMap<ViewGroup, Integer> sNestedChildCount =
            new java.util.WeakHashMap<>();
    /**
     * The glass column of a list, in container coordinates: {left, right}. Every block of one
     * list is painted on this one column, so blocks can never disagree about where they start.
     */
    private static final java.util.WeakHashMap<ViewGroup, int[]> sColumn =
            new java.util.WeakHashMap<>();
    private static int sTileLog;
    /**
     * Lists whose rows this module paints. Used by {@link #hookCardBackground} to tell "a row we
     * own" from every other view the settings app puts a card on (search bars, dialogs, the
     * dashboard hero...).
     */
    private static final java.util.WeakHashMap<ViewGroup, Boolean> sManaged =
            new java.util.WeakHashMap<>();
    /** Cache: drawable class -> is this one of MagicOS' group-card drawables. Hot path. */
    private static final java.util.HashMap<Class<?>, Boolean> sCardClasses =
            new java.util.HashMap<>();

    /** Full-resolution wallpaper (asynchronous). */
    private static Bitmap sFull;
    private static String sFullKey;
    /** Fast low-resolution wallpaper used for the first frame of a cold process. */
    private static Bitmap sPreview;
    private static String sPreviewKey;
    /** Bitmap currently installed on the layer (to upgrade preview -> full without a flash). */
    private static Bitmap sLayerBitmap;
    /** Prefs key of the wallpaper currently installed on the layer. */
    private static String sLayerKey;
    private static XposedModule sModule;
    private static WeakReference<Activity> sPending;
    private static volatile boolean sPreloading;
    private static volatile long sLastStrip;
    private static boolean sStripPosted;
    /** True while the ~500ms post-layout strip is queued ({@link #scheduleLateSettle}). */
    private static boolean sLateSettlePending;
    /** True while the ~1400ms final settle is queued (end of the sub-page return animation). */
    private static boolean sFinalSettlePending;
    /** Debounced scroll-end repaint: one runnable per decor, held through a weak reference. */
    private static WeakReference<ViewGroup> sScrollDecor;
    private static Runnable sScrollSettle;
    /** When the current page was first seen; bounds the inflate-time card refusal below. */
    private static volatile long sPageEnterAt;
    private static int sVisited;
    private static int sStripLog;
    private static int sSlowLog;
    private static int sDecoLog;
    /** Decoration classes whose draw callbacks are already muted (hooked once per class). */
    private static final java.util.Set<Class<?>> sDecoMuted = new java.util.HashSet<>();
    private static int sRowLog;
    private static int sBlockLog;
    private static int sSampleLog;
    /** How many "top chrome" lines have been logged (keeps the log bounded). */
    private static int sTbLog;

    /** Cached id -> entry name; getResourceEntryName is slow and this may run per frame. */
    private static final java.util.HashMap<Integer, String> sIdNames = new java.util.HashMap<>();

    private HonorRenderer() {
    }

    /** Live glass alpha (honor_glass_alpha), read by the painters on every frame. */
    static int glassAlpha() {
        return sGlassAlpha;
    }

    /** Live sheen strength (honor_glass_sheen). */
    static int glassSheen() {
        return sGlassSheen;
    }

    static void onProcessLoaded(XposedModule module) {
        sModule = module;
        // Warm the wallpaper decode before the first settings page is opened. Doing this on the
        // main thread inside the first pre-draw pass used to hold the first frame back by
        // hundreds of ms, which is what made the white window background visible.
        preloadAsync();
        hookCardBackground();
        hookCardForeground();
        log(4, "hb build " + BUILD_TAG);
    }

    /** Bumped on every build so the LSPosed log proves which dex is actually running. */
    private static final String BUILD_TAG = "1.4.5-beta25";

    /**
     * Keeps MagicOS' group card off the rows we paint.
     *
     * <p>Most settings pages paint their group card with an {@code ItemDecoration}: on 蓝牙,
     * 荣耀互联, 应用... the decoration's {@code onDraw} calls
     * {@code row.setBackgroundResource(HnCardDrawable)} - <b>inside the list's own draw</b>, i.e.
     * after the pre-draw guard has already put the glass on. That is too late to fix from a
     * pre-draw pass: the row draws the opaque card for that very frame, and the next frame the
     * decoration does it again. The blocks then read as plain white cards on those pages while
     * pages whose card comes from the row's own layout (显示和亮度) look right - the "these
     * second-level menus are not styled like the rest" symptom.
     *
     * <p>The card is applied through {@code View.setBackground(Drawable)} (setBackgroundResource
     * goes through it too), so that one call is intercepted: when the drawable is one of the ROM's
     * card drawables and the view is a member row of a list this module manages, the assignment
     * is swallowed and the row keeps (or gets) its {@link RowGlass}. Everything else - other
     * views, other drawables, other lists - is passed straight through, and the check is a class
     * lookup plus one weak-map hit, so the per-frame cost stays flat.
     */
    private static void hookCardBackground() {
        int installed = 0;
        installed += hookBackgroundMethod("setBackground");
        // Not a synonym on Android: setBackgroundDrawable IS the implementation and setBackground
        // forwards to it, but code that calls setBackgroundDrawable directly never reaches the
        // forwarding method. The mode tiles on 声音和触感 are set that way, which is why they kept
        // their white card after only setBackground was intercepted.
        installed += hookBackgroundMethod("setBackgroundDrawable");
        log(4, "group-card hooks installed: " + installed);
    }

    /**
     * The second way a grid tile paints its card: a foreground.
     *
     * <p>账号中心's 账号安全 tile stays pure white after its background is gone, while the tile
     * and every child report a null background - the card is installed as a
     * {@code setForeground} StateListDrawable and re-applied on every bind, so the background
     * hook never sees it and a one-off clear is undone on the next frame. The same refusal is
     * applied to it: a tile this module already cleared does not get a near-white foreground
     * back. Ripples and coloured highlights still pass through.
     */
    private static void hookCardForeground() {
        try {
            Method m = View.class.getDeclaredMethod("setForeground", Drawable.class);
            sModule.hook(m).intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                @Override
                public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain)
                        throws Throwable {
                    try {
                        if (handleForegroundAssignment(chain)) {
                            return null;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            log(4, "foreground hook installed");
        } catch (Throwable t) {
            log(4, "foreground hook failed: " + t);
        }
    }

    private static boolean handleForegroundAssignment(
            io.github.libxposed.api.XposedInterface.Chain chain) {
        Object arg = chain.getArg(0);
        Object self = chain.getThisObject();
        if (!(arg instanceof Drawable) || !(self instanceof View) || !sGlassEnabled) {
            return false;
        }
        View v = (View) self;
        ViewGroup host = managedAncestor(v);
        if (host == null) {
            return false;
        }
        List<View> nested = sNestedCards.get(host);
        if (nested == null || !nested.contains(v)) {
            return false;
        }
        if (!isNearWhite(v.getContext(), (Drawable) arg)) {
            return false;
        }
        clearRow(v);
        return true;
    }

    private static int hookBackgroundMethod(String name) {
        try {
            Method m = View.class.getDeclaredMethod(name, Drawable.class);
            sModule.hook(m).intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                @Override
                public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain)
                        throws Throwable {
                    try {
                        if (handleCardAssignment(chain)) {
                            return null;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            return 1;
        } catch (Throwable t) {
            log(4, "group-card hook failed for " + name + ": " + t);
            return 0;
        }
    }

    /** @return true when the assignment was handled and must not reach the framework. */
    private static boolean handleCardAssignment(io.github.libxposed.api.XposedInterface.Chain chain) {
        Object arg = chain.getArg(0);
        Object self = chain.getThisObject();
        if (!(arg instanceof Drawable) || !(self instanceof View) || !sGlassEnabled) {
            return false;
        }
        View row = (View) self;
        ViewGroup host = managedAncestor(row);
        if (host == null) {
            // The very first frame is inflated before any strip pass has registered a list, so
            // managedAncestor() is null exactly when the ROM assigns the cards that flash white
            // on entry (荣耀互联's service grid, every 3-4 entries). The hook itself has been
            // installed since process load, so it CAN see those assignments. Refuse near-white
            // cards on a settings list for the first 1.5s of a page - after that the normal,
            // strictly scoped list rules apply and nothing outside a settings list is touched.
            if (sPageEnterAt > 0L && SystemClock.uptimeMillis() - sPageEnterAt < 1500L
                    && (row.getWidth() <= 0
                            || row.getWidth() >= Math.round(90f * density(row)))
                    && insideSettingsList(row)
                    && (isGroupCard((Drawable) arg)
                            || isNearWhite(row.getContext(), (Drawable) arg)
                            || isCardLikeDrawable((Drawable) arg))) {
                // Refuse the assignment, but do NOT call setBackground(null) on every attempt:
                // the ROM retries the card on each layout pass, so clearing would requestLayout
                // in a loop and is itself a source of "the white block stayed behind". Only the
                // first time (an old white card is still attached) is an explicit clear needed.
                Drawable old = row.getBackground();
                if (old != null && isNearWhite(row.getContext(), old)) {
                    clearRow(row);
                }
                // Refusing an assignment changes no drawable, so nothing invalidates the view:
                // the card painted on the previous frame can survive as a stuck white block.
                invalidateView(row);
                return true;
            }
            return false;
        }
        // A list that is not measured yet cannot answer isMember(): its rows are, at that
        // moment, "not members of any block", and clearing their card would leave transparent
        // holes inside a block - the "返回后块被分开" symptom when the dashboard rebinds its rows
        // while the fragment view is still 0px wide. The native card passes through instead and
        // the strip pass replaces it with glass as soon as the list is laid out.
        if (host.getWidth() <= 0) {
            return false;
        }
        // A tile this module already cleared once (响铃 / 振动 / 静音, and the info grid on
        // About phone). Those carry a plain white GradientDrawable, not one of the ROM's card
        // drawables, so the class test below cannot recognise them - but they are on a list we
        // own, and the settings page re-applies them once the page entrance animation ends,
        // i.e. after the strip pass. Refusing that assignment is what makes the glass stick
        // without the user having to scroll.
        List<View> nested = sNestedCards.get(host);
        if (nested != null && nested.contains(row)) {
            clearTile(row);
            invalidateView(row);
            return true;
        }
        if (!isGroupCard((Drawable) arg)) {
            return false;
        }
        if (GlassBlocks.isMember(host, row, density(row))) {
            if (!(row.getBackground() instanceof RowGlass)) {
                // Re-enters the hook with a RowGlass, which is not a card drawable, so the
                // nested call goes straight through. Pin it at once: this hook can fire from an
                // ItemDecoration.onDraw, i.e. during the very frame that is being drawn.
                RowGlass glass = new RowGlass(row, sEyePage);
                row.setBackground(glass);
                pinRowGlass(glass, row, GlassBlocks.compute(host, density(row)));
            } else {
                // Already glass: refusing the assignment touches no drawable, so the row keeps
                // the pixels of its last draw. Repaint with the current (settled) layout.
                invalidateView(row);
            }
        } else {
            // A card nested inside a block: the block behind it is the surface now, so the
            // tile must not paint its own.
            clearRow(row);
            invalidateView(row);
        }
        return true;
    }

    /** Card backgrounds that are not named "...CardDrawable" (selectors, shapes, ripples). */
    private static boolean isCardLikeDrawable(Drawable d) {
        String n = d.getClass().getName();
        return n.contains("Card") || n.contains("StateList") || n.contains("Gradient")
                || n.contains("Ripple") || n.contains("Inset") || n.contains("Layer");
    }

    /** True when the view sits inside a (possibly not-yet-measured) settings list. */
    private static boolean insideSettingsList(View v) {
        android.view.ViewParent p = v == null ? null : v.getParent();
        for (int i = 0; i < 10 && p instanceof View; i++) {
            String cn = p.getClass().getName();
            if (cn.contains("RecyclerView") || cn.contains("ListView")
                    || cn.contains("ScrollView")) {
                return true;
            }
            p = ((View) p).getParent();
        }
        return false;
    }

    /**
     * The list this view belongs to, walking up a few levels: a row is a direct child, but the
     * cards nested inside a row (grid tiles, the three mode tiles on 声音和触感) are two or three
     * levels down and are re-carded by the same decoration.
     */
    private static ViewGroup managedAncestor(View v) {
        android.view.ViewParent p = v == null ? null : v.getParent();
        for (int i = 0; i < 8 && p instanceof View; i++) {
            View pv = (View) p;
            if (sManaged.containsKey(pv)) {
                return (ViewGroup) pv;
            }
            p = pv.getParent();
        }
        return null;
    }

    /** MagicOS' card drawables are all named {@code ...CardDrawable} (HnCardDrawable, ...). */
    private static boolean isGroupCard(Drawable d) {
        Class<?> c = d.getClass();
        Boolean known = sCardClasses.get(c);
        if (known == null) {
            known = c.getName().contains("CardDrawable");
            sCardClasses.put(c, known);
        }
        return known;
    }

    static void onActivityReady(final XposedModule module, final Activity activity) {
        if (module == null) {
            return;
        }
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null && Looper.myLooper() != null && Looper.myLooper() != mainLooper) {
            new Handler(mainLooper).post(new Runnable() {
                @Override
                public void run() {
                    onActivityReady(module, activity);
                }
            });
            return;
        }
        try {
            apply(module, activity);
        } catch (Throwable t) {
            log(4, "render failed for " + activity.getClass().getName() + ": " + t);
        }
    }

    // ---- page entry ---------------------------------------------------------

    private static void apply(XposedModule module, Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        ViewGroup decor = decorOf(activity);
        if (decor == null) {
            return;
        }
        AppearanceState st = AppearanceState.read(module);
        if (st == null) {
            log(4, "desktop appearance: module preferences unavailable");
            return;
        }
        applyState(st, activity);
        if (!st.enabled) {
            return; // master switch off: the page stays stock, nothing is installed
        }
        sPageEnterAt = SystemClock.uptimeMillis();
        if (decor.findViewWithTag(VIEW_TAG) != null) {
            // Already installed for this activity instance (onResume, or the decode callback
            // arriving a frame later): only sync the wallpaper and refresh the rows.
            syncLayer(activity, decor, st);
            strip(decor);
            return;
        }
        log(4, "appearance: enabled=" + st.enabled + " mime=" + st.mime + " opacity=" + st.opacity
                + " blurEnabled=" + st.blurEnabled + " blurDp=" + st.blurRadiusDp
                + " glassAlpha=" + st.glassAlpha + " glassSheen=" + st.glassSheen
                + " on " + activity.getClass().getName());
        if (st.mime != null && st.mime.startsWith("video/")) {
            log(4, "background: videos are not supported on Magicos, skipped (" + st.mime + ")");
            return;
        }

        Bitmap bitmap = cachedBitmap(st);
        if (bitmap == null) {
            // Cold process: even the preview is still decoding. Previously this branch just
            // returned, which left the theme's untouched white cards on screen for the whole
            // 200-500ms decode - the "white block flashes, and once in a while it stays" symptom.
            // Install the glass with a neutral placeholder instead: the strip pass and every
            // guard are live from the first frame, and syncLayer() swaps the real wallpaper in
            // as soon as the decode lands. buildLayer()/WallpaperDrawable tolerate a null bitmap.
            sPending = new WeakReference<>(activity);
            preloadAsync();
            install(activity, decor, st, null);
            return;
        }
        install(activity, decor, st, bitmap);
    }

    private static void applyState(AppearanceState st, Activity activity) {
        sGlassAlpha = st.glassAlpha;
        sGlassSheen = st.glassSheen;
        sGlassEnabled = st.glassEnabled;
        String activityCls = activity.getClass().getName();
        sEyePage = activityCls.contains("OasisEyeComfort");
    }

    /**
     * Installs the wallpaper layer and neutralises the theme window background. Everything here
     * happens before the first measure, so the first frame the compositor sees already carries
     * the wallpaper instead of the theme's white surface, and the layer is measured together
     * with the content instead of being added after layout (a view added during pre-draw is
     * still 0x0 for that frame - that was the "one frame without wallpaper" flash).
     */
    private static void install(Activity activity, ViewGroup decor, AppearanceState st, Bitmap bitmap) {
        Window window = activity.getWindow();
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content != null) {
                content.setBackground(null);
            }
        } catch (Throwable ignored) {
        }
        try {
            decor.setBackground(null);
        } catch (Throwable ignored) {
        }
        try {
            if (window != null) {
                window.setStatusBarColor(0);
            }
        } catch (Throwable ignored) {
        }
        // 1. Window background = the wallpaper itself. Any frame drawn before/while the content
        //    is painted shows the photo, never the theme's white surface.
        setWindowWallpaper(activity, st, bitmap);

        ImageView layer = buildLayer(activity, st, bitmap);
        sLayerBitmap = bitmap;
        // null while no wallpaper is decoded yet: syncLayer() then takes the "new wallpaper"
        // branch instead of thinking the placeholder already shows the right image.
        sLayerKey = bitmap == null ? null : bitmapKey(st);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        try {
            decor.addView(layer, 0, lp);
            log(4, "layer attached: decorChildren=" + decor.getChildCount()
                    + " decor=" + decor.getWidth() + "x" + decor.getHeight());
        } catch (Throwable t) {
            log(4, "decor insertion failed (" + t + "), falling back to content");
            View content = activity.findViewById(android.R.id.content);
            if (content instanceof ViewGroup) {
                ((ViewGroup) content).addView(layer, 0, lp);
            } else {
                return;
            }
        }

        if (decor.getWidth() > 0 && decor.getHeight() > 0) {
            strip(decor);
        } else {
            armFirstStrip(decor);
        }
        armSettleStrips(decor, 6);
        watch(decor);
        // One bounded late pass: MagicOS re-applies the page's own cards when the entrance
        // animation of a sub-page ends, which is well after the settle frames. The assignment
        // itself is refused by the setBackground hook (see handleCardAssignment); this pass
        // only catches whatever came in through a path the hook does not see.
        try {
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    strip(decor);
                    // Replacing/clearing a background invalidates the row it belongs to, but a
                    // row that already holds a RowGlass is left untouched by strip(). Repaint
                    // the whole page so those rows recompute first/last against the final layout.
                    forcePageRedraw(decor);
                }
            }, 800L);
        } catch (Throwable ignored) {
        }
    }

    private static ImageView buildLayer(Activity activity, AppearanceState st, Bitmap bitmap) {
        ImageView layer = new ImageView(activity.getApplicationContext());
        layer.setTag(VIEW_TAG);
        layer.setScaleType(ImageView.ScaleType.CENTER_CROP);
        layer.setImageBitmap(bitmap);
        if (bitmap == null) {
            // Neutral dark placeholder for the few hundred ms the background decode needs. It
            // keeps a transparent window background from showing whatever is behind the settings
            // window, and it matches the dark glass instead of flashing the theme's white.
            // ImageView draws its content on top of this, so the real wallpaper hides it.
            layer.setBackgroundColor(0xff191a1f);
        }
        layer.setClickable(false);
        layer.setFocusable(false);
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layer.setAlpha(clampAlpha(st.opacity));
        if (st.blurEnabled && st.blurRadiusDp > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                float density = activity.getResources().getDisplayMetrics().density;
                float radiusPx = st.blurRadiusDp * density;
                layer.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.MIRROR));
            } catch (Throwable ignored) {
            }
        }
        return layer;
    }

    private static ViewGroup decorOf(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return null;
            }
            View decor = window.peekDecorView();
            if (decor == null) {
                decor = window.getDecorView();
            }
            return decor instanceof ViewGroup ? (ViewGroup) decor : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Retries the strip before the first frame is drawn (only needed if the decor was 0x0). */
    private static void armFirstStrip(final ViewGroup decor) {
        try {
            decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                        decor.getViewTreeObserver().removeOnPreDrawListener(this);
                        strip(decor);
                    }
                    return true;
                }
            });
        } catch (Throwable t) {
            log(4, "defer failed: " + t);
        }
    }

    /**
     * Runs the strip pass again on the next few frames.
     *
     * <p>A page is not always laid out in one pass: 更多显示设置 fills its rows after the first
     * measure, so the strip that ran inside {@code onActivityReady} saw a half-built tree, found
     * no block and left the stock white cards in place - they only went away once the user
     * pulled the list, which triggered a layout and with it another strip. Four pre-draw passes
     * cover that without a timer and without a per-frame listener that outlives the page's
     * first layout.
     */
    private static void armSettleStrips(final ViewGroup decor, final int frames) {
        if (decor == null || frames <= 0) {
            return;
        }
        try {
            decor.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        private int left = frames;

                        @Override
                        public boolean onPreDraw() {
                            if (--left <= 0) {
                                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                            }
                            if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                                strip(decor);
                            }
                            return true;
                        }
                    });
        } catch (Throwable t) {
            log(4, "settle strips unavailable: " + t);
        }
    }

    /**
     * Re-strips on layout (rows added/changed) and on recycled-row attach. No repeating timer:
     * the previous build walked the whole tree every 120ms from a pre-draw listener, which cost
     * a layout pass per tick and made the first frames of a page unstable.
     */
    private static void watch(final ViewGroup decor) {
        if (decor == null || !sWatched.add(String.valueOf(System.identityHashCode(decor)))) {
            return;
        }
        try {
            final ViewTreeObserver vt = decor.getViewTreeObserver();
            vt.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    strip(decor);
                    // Returning from a sub-page rebinds the dashboard's rows, and MagicOS
                    // re-applies some cards well after the first layout (entrance animation
                    // end, decoration re-added on fragment re-attach). Those late passes are
                    // what made a block come back as separate cards until the user scrolled:
                    // nothing re-ran the strip between the rebind and the first user scroll.
                    armSettleStrips(decor, 4);
                    scheduleLateSettle(decor);
                }
            });
            vt.addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
                @Override
                public void onScrollChanged() {
                    scheduleStrip(decor);
                    // A scroll does NOT produce a global layout, so install()/onGlobalLayout's
                    // settle passes never run for it. Without a scroll-end repaint the rows that
                    // were drawn mid-scroll keep their (possibly split) pixels indefinitely.
                    scheduleScrollSettle(decor);
                }
            });
        } catch (Throwable t) {
            log(4, "layout watch failed: " + t);
        }
    }

    private static void scheduleStrip(final ViewGroup decor) {
        if (sStripPosted) {
            return;
        }
        sStripPosted = true;
        try {
            decor.post(new Runnable() {
                @Override
                public void run() {
                    sStripPosted = false;
                    strip(decor);
                }
            });
        } catch (Throwable t) {
            sStripPosted = false;
        }
    }

    /**
     * Safety-net repaint once a scroll ends (NOT the fix for split blocks).
     *
     * <p>guardFrame() now re-pins every row in pre-draw, so no split frame is produced while
     * scrolling. This pass only catches a card that the ROM re-binds after the last scroll event,
     * when no further pre-draw would otherwise occur: one strip + full page repaint 160ms after
     * the last scroll event, plus the two existing late settles. The callback is removed and
     * re-posted on every scroll event, so a continuous scroll schedules exactly one repaint,
     * never a per-frame loop.
     */
    private static void scheduleScrollSettle(final ViewGroup decor) {
        ViewGroup prev = sScrollDecor == null ? null : sScrollDecor.get();
        if (prev != decor || sScrollSettle == null) {
            sScrollDecor = new WeakReference<>(decor);
            sScrollSettle = new Runnable() {
                @Override
                public void run() {
                    strip(decor);
                    forcePageRedraw(decor);
                    // MagicOS can re-bind dashboard rows slightly after the scroll settles;
                    // reuse the two existing late passes instead of adding another timer.
                    scheduleLateSettle(decor);
                }
            };
        } else {
            try {
                decor.removeCallbacks(sScrollSettle);
            } catch (Throwable ignored) {
            }
        }
        try {
            decor.postDelayed(sScrollSettle, 160L);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Two coalesced settles after the last layout burst: ~500ms for the card passes that land
     * late, and ~1400ms for the one that lands at the END of a sub-page return animation.
     *
     * <p>Returning from a sub-page does not create a new Activity, so install()'s settle frames
     * never run again. The dashboard rebinds its rows when the animation finishes; if the last
     * strip ran while the animation still had the list in a transient state, every row painted
     * itself as a standalone rounded card (first && last), and because nothing invalidated the
     * rows afterwards those wrong pixels stayed on screen until the user scrolled. A final
     * strip + full page repaint after the animation fixes both the split blocks and any white
     * card that slipped in outside the settle window. No repeating timer is installed.
     */
    private static void scheduleLateSettle(final ViewGroup decor) {
        if (!sLateSettlePending) {
            sLateSettlePending = true;
            postSettle(decor, 500L, false);
        }
        if (!sFinalSettlePending) {
            sFinalSettlePending = true;
            postSettle(decor, 1400L, true);
        }
    }

    private static void postSettle(final ViewGroup decor, final long delay, final boolean last) {
        try {
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (last) {
                        sFinalSettlePending = false;
                    } else {
                        sLateSettlePending = false;
                    }
                    strip(decor);
                    forcePageRedraw(decor);
                }
            }, delay);
        } catch (Throwable t) {
            if (last) {
                sFinalSettlePending = false;
            } else {
                sLateSettlePending = false;
            }
        }
    }

    /** invalidate() that can never throw out of a background-assignment hook. */
    private static void invalidateView(View v) {
        try {
            v.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Invalidates every view of the page (except the wallpaper layer) so each one records a
     * fresh display list. invalidate() does not request a layout, so this cannot feed back into
     * onGlobalLayout / the strip guards. RowGlass derives "first/last in block" from the parent's
     * child order at draw time, so this is what makes a settled layout actually reach the screen.
     */
    private static void forcePageRedraw(View v) {
        if (v == null) {
            return;
        }
        try {
            if (VIEW_TAG.equals(v.getTag())) {
                return;
            }
            v.invalidate();
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n;
            try {
                n = g.getChildCount();
            } catch (Throwable ignored) {
                return;
            }
            for (int i = 0; i < n; i++) {
                try {
                    forcePageRedraw(g.getChildAt(i));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---- strip pass ---------------------------------------------------------

    private static void strip(ViewGroup root) {
        if (root == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastStrip < 32L) {
            scheduleStrip(root);
            return;
        }
        sLastStrip = now;
        sVisited = 0;
        long t0 = now;
        int changed;
        try {
            changed = walk(root);
        } catch (Throwable t) {
            log(4, "strip failed: " + t);
            return;
        }
        long dt = SystemClock.uptimeMillis() - t0;
        if (sStripLog < 60) {
            sStripLog++;
            log(4, "strip: visited=" + sVisited + " changed=" + changed + " in " + dt + "ms");
        } else if (dt >= 8 && sSlowLog < 12) {
            sSlowLog++;
            log(4, "slow strip: " + dt + "ms changed=" + changed + " views=" + sVisited);
        }
    }

    private static int walk(View v) {
        if (v == null) {
            return 0;
        }
        if (VIEW_TAG.equals(v.getTag())) {
            return 0; // never touch the wallpaper layer itself
        }
        sVisited++;
        int changed = 0;
        String idName = idNameFor(v);
        if (idName != null && BLACKLIST.contains(idName)) {
            return 0;
        }
        if ("content_frame".equals(idName) || "list".equals(idName)) {
            // The two containers MagicOS paints with its own opaque pane. Clearing them is the
            // one carry-over from the id-based build: without it the pane covers the wallpaper
            // on pages whose pane is not a plain flat colour and the geometry rules cannot see it.
            changed += clearForce(v);
        }
        changed += glassStandaloneCard(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            String cn = g.getClass().getName();
            boolean recycler = cn.contains("RecyclerView");
            // 应用通知管理's whole list of app switches lives in NotificationMainFragmentListView,
            // an AbsListView (not a RecyclerView) nested inside a LinearLayout. It was invisible
            // to stripList, so every switch row kept its own surface and the bottom of 通知和状态栏
            // had no merged glass block. A real list is wide, has at least two laid-out rows and
            // is an AbsListView; narrow index bars and empty search result lists are left alone.
            boolean adapterList = g instanceof android.widget.AbsListView
                    && g.getWidth() >= Math.round(180f * density(g))
                    && g.getChildCount() >= 2;
            if (recycler) {
                return stripList(g); // rows are owned by the list pass, never re-wrapped below
            }
            if (adapterList) {
                // 通知和状态栏 puts HnListCardLayout rows (which draw their own white card)
                // INSIDE its ListView. Give the list its merged block, then keep walking into
                // the children so glassStandaloneCard()/clearCover() can still reach them.
                changed += stripList(g);
            } else {
                int stacked = stripStack(g);
                if (stacked >= 0) {
                    return stacked; // same rule for plain stacked containers on scrollable pages
                }
            }
        }
        changed += clearTopChrome(v);
        changed += clearCover(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = 0;
            try {
                n = g.getChildCount();
            } catch (Throwable ignored) {
            }
            for (int i = 0; i < n; i++) {
                try {
                    changed += walk(g.getChildAt(i));
                } catch (Throwable ignored) {
                }
            }
        }
        return changed;
    }

    /**
     * Recomputes the list's glass column and makes it the one every {@link RowGlass} share of
     * that list paints on.
     *
     * <p>Why a per-list column and not a per-row inset: MagicOS paints a group card at a fixed
     * inset from the list (49px = 14dp on this device) and does <b>not</b> care how wide the row
     * view itself is. On "About phone" the hero row spans the full 1272px while the info grid
     * below it is a 1146px view sitting at x=63 - insetting each row by 14dp put the two shares
     * of the same block at 49..1223 and 112..1167, a 63px step right where they meet. A row's
     * own bounds are the hard limit for a background drawable, so the column is the <i>union</i>
     * of those limits: max(row.left) .. min(row.right), never inside the 14dp inset.
     */
    private static void updateColumn(ViewGroup list) {
        int cw = list.getWidth();
        if (cw <= 0) {
            return;
        }
        int inset = Math.round(GlassStyle.H_INSET_DP * density(list));
        int left = inset;
        int right = cw - inset;
        int n = list.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = list.getChildAt(i);
            if (v == null || v.getVisibility() != View.VISIBLE) {
                continue;
            }
            int w = v.getWidth();
            if (w < cw * GlassStyle.MIN_ROW_WIDTH_FRAC) {
                continue; // labels, tiles, decoration stubs: too narrow to define the column
            }
            if (v.getLeft() > left) {
                left = v.getLeft();
            }
            if (v.getRight() < right) {
                right = v.getRight();
            }
        }
        // A row inset further than the 14dp column pulls the column in so that the block stays
        // one continuous rectangle - but only up to 6dp: a single abnormally margined row must
        // not shrink every block of the page.
        int maxExtra = Math.round(6f * density(list));
        left = Math.min(left, inset + maxExtra);
        right = Math.max(right, cw - inset - maxExtra);
        if (right - left < 16) {
            left = inset;
            right = cw - inset;
        }
        // Updated in place: this runs from the pre-draw guard, i.e. once per frame during a
        // scroll, so it must not allocate.
        int[] cur = sColumn.get(list);
        if (cur == null) {
            sColumn.put(list, new int[]{left, right});
        } else {
            cur[0] = left;
            cur[1] = right;
        }
    }

    /** The list's glass column in container coordinates, or null when not computed yet. */
    static int[] columnFor(ViewGroup list) {
        return sColumn.get(list);
    }

    /**
     * A free-standing card outside any list: the switch bar MagicOS puts above the content of
     * 蓝牙 / 荣耀互联 ({@code HnListCardLayout}, the title plus its toggle).
     *
     * <p>Those pages end up with one stock white card sitting above a page of glass blocks, which
     * is the clearest "this page is not styled" tell. The card's own bounds are the panel, so it
     * only needs a single {@link SurfaceGlass}; its padding is read before the background is
     * swapped and written back afterwards, because the native card carries the inset as drawable
     * padding and losing it would move the title and the toggle.
     */
    private static int glassStandaloneCard(View v) {
        if (!sGlassEnabled || !(v instanceof ViewGroup) || v.getVisibility() != View.VISIBLE) {
            return 0;
        }
        if (!v.getClass().getName().contains("HnListCardLayout")) {
            return 0;
        }
        Drawable bg = v.getBackground();
        if (bg instanceof SurfaceGlass || bg instanceof WallpaperDrawable) {
            return 0;
        }
        int w = v.getWidth();
        int h = v.getHeight();
        float density = density(v);
        if (w < Math.round(120f * density) || h < Math.round(48f * density)) {
            return 0;
        }
        int l = v.getPaddingLeft();
        int t = v.getPaddingTop();
        int r = v.getPaddingRight();
        int b = v.getPaddingBottom();
        try {
            v.setBackground(new SurfaceGlass(v));
            if (v.getPaddingLeft() != l || v.getPaddingTop() != t
                    || v.getPaddingRight() != r || v.getPaddingBottom() != b) {
                v.setPadding(l, t, r, b);
            }
            return 1;
        } catch (Throwable t2) {
            return 0;
        }
    }

    /**
     * List page: every block member gets its own {@link RowGlass} share of the merged block
     * (its native card is replaced, padding preserved so nothing moves), non-members lose
     * their card, the native decoration that paints an opaque group card is dropped, and the
     * container pane stays transparent so the wallpaper shows through.
     *
     * A Drawable is used instead of a RecyclerView.ItemDecoration on purpose: libxposed loads
     * the module dex in its own class loader, so {@code androidx.recyclerview} classes (which
     * the settings app does ship) are NOT resolvable from module code - an ItemDecoration
     * subclass dies with NoClassDefFoundError. Painting per row (rather than one panel on the
     * container) is what makes the glass follow the rows during a scroll.
     */
    private static int stripList(ViewGroup list) {
        int changed = 0;
        float density = density(list);

        sManaged.put(list, Boolean.TRUE);
        changed += dropCardDecorations(list);
        updateColumn(list);

        List<GlassBlocks.Block> blocks = GlassBlocks.compute(list, density);
        int members = 0;
        for (GlassBlocks.Block b : blocks) {
            members += b.count;
        }
        // Every row owns its share of the glass (see RowGlass): that is the only way the block
        // stays glued to the rows while the list scrolls, because RecyclerView moves the rows'
        // own render nodes and never re-records the container's display list.
        changed += applyRowGlass(list, density, blocks);
        // A list inside a row (应用 puts its whole app list in one) is invisible to the row
        // pass: the row owns its own glass, but the inner list lays out its own white rows on
        // top of it, which is why that page kept a solid white slab under 应用设置.
        changed += stripNestedLists(list, density);
        // The cards of a 2-column grid live *inside* such a container, one level deeper, where
        // the list pass cannot see them.
        changed += sweepNestedCards(list, blocks, density);
        if (sBlockLog < 40 && !blocks.isEmpty()) {
            sBlockLog++;
            StringBuilder sb = new StringBuilder("blocks: ");
            for (GlassBlocks.Block b : blocks) {
                sb.append('[').append(b.count).append(',').append(b.top).append('-')
                        .append(b.bottom).append(']');
            }
            log(4, sb + " rows=" + members + " on " + idNameFor(list));
        }

        changed += hidePane(list);
        ensureChildListener(list);
        ensureFrameGuard(list);
        return changed;
    }

    /**
     * Registers and strips lists that live inside a row.
     *
     * <p>应用 builds 应用设置 as one row whose content is a second {@code HwRecyclerView}
     * ({@code external_app_settings_list}) holding the app entries. The row pass paints the
     * row and stops - it never walks into the inner list - so the inner rows painted their own
     * white on top and the whole block read as an opaque slab. Handing the inner list to
     * {@link #stripList} lets its rows join a block like any other page, and the block behind
     * them shows through.
     *
     * <p>Only lists that are actually laid out and tall enough to hold two rows are taken, so a
     * one-line spinner or a horizontal chip strip is left alone.
     */
    private static int stripNestedLists(ViewGroup list, float density) {
        int changed = 0;
        for (int i = 0, n = list.getChildCount(); i < n; i++) {
            ViewGroup nested = findNestedList(list.getChildAt(i), 0, Math.round(140f * density));
            if (nested != null && nested != list && !sManaged.containsKey(nested)) {
                changed += stripList(nested);
            }
        }
        return changed;
    }

    private static ViewGroup findNestedList(View v, int depth, int minH) {
        if (depth > 4 || !(v instanceof ViewGroup)) {
            return null;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0, n = g.getChildCount(); i < n; i++) {
            View c = g.getChildAt(i);
            if (c instanceof ViewGroup && c.getHeight() >= minH
                    && c.getClass().getName().contains("RecyclerView")) {
                return (ViewGroup) c;
            }
            ViewGroup deep = findNestedList(c, depth + 1, minH);
            if (deep != null) {
                return deep;
            }
        }
        return null;
    }

    /**
     * Installs one {@link RowGlass} per block member and strips everything else. A row that is
     * too narrow to join a block (2-column tiles) simply keeps no background at all, so it can
     * never show a native white card.
     */
    private static int applyRowGlass(ViewGroup list, float density,
                                     List<GlassBlocks.Block> blocks) {
        int cw = list.getWidth();
        if (cw <= 0) {
            return 0;
        }
        int changed = 0;
        int n = list.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = list.getChildAt(i);
            if (v == null || v.getVisibility() != View.VISIBLE) {
                continue;
            }
            String id = idNameFor(v);
            if (id != null && BLACKLIST.contains(id)) {
                continue;
            }
            if (sGlassEnabled && GlassBlocks.isMember(list, v, density)) {
                Drawable bg = v.getBackground();
                RowGlass glass;
                if (bg instanceof RowGlass) {
                    glass = (RowGlass) bg;
                } else {
                    glass = new RowGlass(v, sEyePage);
                    v.setBackground(glass);
                    changed++;
                }
                // Pin the grouping BEFORE the next draw. Even when the background did not
                // change, the strip pass re-evaluates the whole list and keeps every share in
                // agreement - this is what stops a slow scroll from painting one row as a
                // standalone card while its neighbours stay merged.
                pinRowGlass(glass, v, blocks);
                // A member row can still carry white cards below it. 荣耀互联 nests an entire
                // GridLayout (1230x1650) inside the row, and each card_item is only 47% of that
                // grid's width - so the old 72% rule skipped them and their white card stayed on
                // the very first frame. The threshold is relative to EACH level, hence 35/12:
                // wide enough to still ignore icon plates, low enough to reach a 2-column card.
                changed += scrubSurface(v, 35, 12);
            } else if (rowShaped(v, cw, density)) {
                changed += clearRow(v);
            }
        }
        return changed;
    }

    /** Resolves one row's share from the block list computed over the whole container. */
    private static void pinRowGlass(RowGlass glass, View v, List<GlassBlocks.Block> blocks) {
        if (glass == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        int top = v.getTop();
        int bottom = v.getBottom();
        for (GlassBlocks.Block b : blocks) {
            if (top < b.top - 2 || bottom > b.bottom + 2) {
                continue;
            }
            boolean first = Math.abs(top - b.top) <= 2;
            boolean last = Math.abs(bottom - b.bottom) <= 2;
            // Relative offsets survive scrolling: RecyclerView offsets every child of a block
            // by the same amount, so the ramp stays aligned with the block.
            glass.pinBlock(first, last, b.top - top, b.bottom - top);
            return;
        }
    }

    /** A row-shaped child: wide enough to be a preference row and tall enough to be a card. */
    private static boolean rowShaped(View v, int cw, float density) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return false;
        }
        return v.getWidth() >= Math.round(cw * 0.35f)
                && v.getHeight() >= Math.round(36f * density);
    }

    /**
     * The list's own pane: the glass replaces it, so it has to stay transparent.
     *
     * Alpha instead of {@code setBackground(null)} on purpose - setBackground calls
     * requestLayout(), and MagicOS' SettingsRecyclerView re-applies its pane during layout, so
     * doing that from a pre-draw pass loops layout<->background forever and the list stops
     * scrolling. setAlpha only invalidates the drawable.
     */
    private static int hidePane(ViewGroup g) {
        Drawable bg = g.getBackground();
        if (bg == null || bg instanceof WallpaperDrawable) {
            return 0;
        }
        try {
            bg.mutate();
            bg.setAlpha(0);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Cards that sit <b>inside</b> a block, on top of the glass this module already painted.
     *
     * <p>Two shapes of the same problem: "About phone" puts 设备名称 / 处理器 / 运行内存 in one
     * grid container (three cards, 40-46% of the width each, one level below the row the block
     * algorithm sees), and 声音和触感 puts 响铃 / 振动 / 静音 in a bare {@code LinearLayout} row
     * that is itself a block member. Clearing the row's own background leaves those cards white,
     * so they are found geometrically - a ViewGroup that is narrow-but-not-full-width, tall
     * enough to be a card, at least two of them in one block - and stripped, which lets the
     * single block behind them show through.
     *
     * <p>Runs for every managed container, list or stack: the tile row on 声音和触感 lives on a
     * ScrollView page, and the same rows appear on RecyclerView pages elsewhere.
     *
     * Discovery samples drawables, so it is throttled; the views it found are remembered and
     * re-cleared on every frame (identity check only - RecyclerView reapplies the card on bind).
     */
    private static int sweepNestedCards(ViewGroup list, List<GlassBlocks.Block> blocks, float density) {
        int changed = 0;
        List<View> known = sNestedCards.get(list);
        boolean stale = false;
        if (known != null) {
            for (int i = 0; i < known.size(); i++) {
                View v = known.get(i);
                // A tile that is no longer attached means the page rebuilt its grid (MagicOS'
                // SoundModeBoxView removes and re-adds its children when the sound mode
                // changes or the page finishes settling). The remembered views are dead, so
                // the scan must run again even inside the throttle window.
                if (v == null || v.getParent() == null) {
                    stale = true;
                } else {
                    changed += clearTile(v);
                }
            }
        }
        int cw = list.getWidth();
        if (cw <= 0) {
            return changed;
        }
        long now = SystemClock.uptimeMillis();
        // A container that just gained rows (the ViewPager page of 声音和触感 is inflated after
        // the first layout, 更多显示设置 fills its list the same way) must be rescanned at once,
        // otherwise the newly added cards keep their native white until the user scrolls - the
        // "pull down and it fixes itself" symptom. The throttle only guards the steady state.
        int cc = list.getChildCount();
        Integer lastCC = sNestedChildCount.get(list);
        boolean grew = lastCC == null || lastCC != cc;
        if (grew) {
            sNestedChildCount.put(list, cc);
        }
        Long last = sNestedScanAt.get(list);
        boolean undiscovered = known == null || known.isEmpty();
        if (!grew && !stale && !undiscovered && last != null && now - last < 400L) {
            return changed;
        }
        sNestedScanAt.put(list, now);

        int minW = Math.round(cw * 0.15f);
        // Cards up to 80% of the list: 荣耀互联's service cards are ~75% wide, wider than a
        // classic 2-column tile. Their parent row is excluded by the same upper bound.
        int maxW = Math.round(cw * 0.80f);
        int minH = Math.round(32f * density);
        // Carry the tiles already known forward. A tile this module cleared has no background
        // left, so the geometric test below can no longer recognise it - and dropping it from
        // the list also drops the setBackground refusal in handleCardAssignment, which lets the
        // page put its white card straight back on the next bind. That is why 账号安全 on
        // 账号中心 stayed white while 个人信息 (same row, same shape) went transparent: the
        // first pass cleared both, the second pass could only see the one whose card came back.
        List<View> found = new ArrayList<>();
        if (known != null) {
            for (int i = 0; i < known.size(); i++) {
                View v = known.get(i);
                if (v != null && v.getParent() != null) {
                    found.add(v);
                }
            }
        }
        StringBuilder logLine = null;
        for (GlassBlocks.Block b : blocks) {
            List<View> cand = new ArrayList<>();
            for (int i = b.first; i <= b.last(); i++) {
                collectTiles(list.getChildAt(i), minW, maxW, minH, cand, 0);
                if (cand.size() > 8) {
                    break;
                }
            }
            // A grid is discovered one card at a time: on 荣耀互联 the service tiles get their
            // card drawables over several consecutive layout passes, so the first frame often
            // has exactly ONE white tile. Requiring two ("it must be a grid") left that one
            // painted for a frame - the flash - and when the remaining tiles were bound after
            // the last settle pass it stayed there. The geometric + near-white test above is
            // already specific enough on its own; any such card inside a glass block must go.
            if (!cand.isEmpty()) {
                for (View v : cand) {
                    if (!found.contains(v)) {
                        found.add(v);
                    }
                    changed += clearTile(v);
                }
                if (sTileLog < 8) {
                    sTileLog++;
                    if (logLine == null) {
                        logLine = new StringBuilder("grid cards: ");
                    }
                    logLine.append('[').append(cand.size()).append(']');
                }
            }
        }
        sNestedCards.put(list, found);
        if (logLine != null) {
            log(4, logLine + " on " + idNameFor(list));
        }
        return changed;
    }

    /** Collects nested card-shaped views: ViewGroup, 28-62% wide, >= 32dp tall, opaque flat bg. */
    private static void collectTiles(View v, int minW, int maxW, int minH, List<View> out, int depth) {
        if (depth > 3 || !(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) v;
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = g.getChildAt(i);
            if (c == null) {
                continue;
            }
            if (c instanceof ViewGroup && c.getVisibility() == View.VISIBLE) {
                if (isTileCard(c, minW, maxW, minH)) {
                    out.add(c);
                }
            }
            collectTiles(c, minW, maxW, minH, out, depth + 1);
        }
    }

    /**
     * A grid tile card: a wide-but-not-full-width ViewGroup carrying an opaque card.
     *
     * <p>Four flavours, all "a card whose colour cannot be read from the drawable API alone",
     * which is why the class names come first: {@code HwCardView} (background is an
     * {@code androidx.cardview} RoundRectDrawable), Honor's card container
     * ({@code HnListCardLayout}, whose background is a {@code StateListDrawable} - a selector
     * that only resolves to a white shape once it is rendered), one of MagicOS' own card
     * drawables ({@code ...CardDrawable}), and a hand-rolled card (FrameLayout +
     * GradientDrawable), which is read straight off the drawable.
     *
     * <p>Falling back to rendering the drawable is what makes the 响铃 / 振动 / 静音 tiles work:
     * their white comes from a selector on the inner {@code HnListCardLayout}, while the outer
     * {@code RelativeLayout} carries a plain white GradientDrawable. Clearing only the outer one
     * leaves the tile white, which is exactly what happened before.
     */
    private static boolean isTileCard(View c, int minW, int maxW, int minH) {
        int w = c.getWidth();
        int h = c.getHeight();
        if (w < minW || w > maxW || h < minH) {
            return false;
        }
        Drawable bg = c.getBackground();
        if (bg == null || bg instanceof RowGlass || bg instanceof SurfaceGlass
                || bg instanceof WallpaperDrawable) {
            return false;
        }
        String cls = c.getClass().getName();
        if (cls.contains("CardView") || cls.contains("CardLayout")) {
            return true;
        }
        if (isGroupCard(bg)) {
            return true;
        }
        Integer color = flatColorOf(bg);
        if (color != null && isFlatSurface(color)) {
            return true;
        }
        int sampled = sampleColor(c.getContext(), bg);
        if (isFlatSurface(sampled)) {
            return true;
        }
        // 荣耀互联's 2-column service tiles use a semi-transparent near-white card. Its alpha
        // is below 255, so isFlatSurface() rejects it and the tile is never discovered until a
        // later pass - that is the white slab that flashes on entry and (every few entries)
        // stays behind. Judge the colour of the card, not only its opacity.
        int a = sampled >>> 24;
        int r = (sampled >> 16) & 0xff;
        int g = (sampled >> 8) & 0xff;
        int b = sampled & 0xff;
        return a > 0 && r >= 205 && g >= 205 && b >= 205
                && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 30;
    }

    /** Exact fill colour of a card drawable without rendering an 8x8 bitmap (API 24+). */
    private static Integer flatColorOf(Drawable d) {
        if (d == null || d instanceof RowGlass || d instanceof WallpaperDrawable) {
            return null;
        }
        try {
            if (d instanceof ColorDrawable) {
                return ((ColorDrawable) d).getColor();
            }
            if (d instanceof GradientDrawable) {
                ColorStateList csl = ((GradientDrawable) d).getColor();
                return csl == null ? null : csl.getDefaultColor();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Per-frame guard. RecyclerView reapplies a recycled row's native card during bind, which
     * happens inside layout - after our throttled strip pass and before the frame is drawn.
     * Fast flings therefore used to show a frame or two of white card per recycled row.
     * A pre-draw listener runs after layout and before draw, so clearing here means the white
     * card is never composited at all. It also reinstalls the block background if the container
     * reset it (SettingsRecyclerView does that on layout), which otherwise blinks the glass off.
     */
    private static void ensureFrameGuard(final ViewGroup list) {
        try {
            ViewTreeObserver vt = list.getViewTreeObserver();
            if (!vt.isAlive() || sGuardObserver.get(list) == vt) {
                return;
            }
            vt.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    try {
                        guardFrame(list);
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            });
            sGuardObserver.put(list, vt);
        } catch (Throwable t) {
            if (sDecoLog < 20) {
                sDecoLog++;
                log(4, "frame guard unavailable: " + t);
            }
        }
    }

    /**
     * Tiles are re-created by some pages while they settle (声音和触感 rebuilds its 声音模式 box
     * through {@code removeAllViews}/{@code addView}). The remembered views then belong to
     * detached instances, so clearing them does nothing and the freshly built tiles keep their
     * native white card until a scroll happens to trigger another strip pass. Rescanning from
     * the guard - eight frames apart, and never more than once per throttle window - picks the
     * new instances up while staying off the hot path.
     */
    private static void rescanNested(ViewGroup list, float density) {
        // Until the first tile has been discovered, retry on EVERY pre-draw frame. The grid on
        // 荣耀互联 gets its card drawables one layout later than the first strip pass, and a
        // 400ms throttle / 8-frame stride is exactly the window in which its white slab is
        // composited ("flashes on entry, every 3-4 entries stays behind").
        List<View> known = sNestedCards.get(list);
        boolean undiscovered = known == null || known.isEmpty();
        if (!undiscovered && (++sGuardTick & 7) != 0) {
            return;
        }
        sweepNestedCards(list, GlassBlocks.compute(list, density), density);
    }

    private static int sGuardTick;

    private static void guardFrame(ViewGroup list) {
        int cw = list.getWidth();
        if (cw <= 0) {
            return;
        }
        hidePane(list);
        updateColumn(list);
        float density = density(list);
        // Resolve the grouping for the WHOLE list here, in pre-draw, before any row is drawn.
        // beta24 pinned only from strip(), which is posted and can run AFTER this frame's draw;
        // a row freshly attached during a slow scroll then drew with the transient child order
        // and showed a standalone rounded card for a frame - "split, then flashes back".
        List<GlassBlocks.Block> blocks = GlassBlocks.compute(list, density);
        int n = list.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = list.getChildAt(i);
            if (v == null || v.getVisibility() != View.VISIBLE) {
                continue;
            }
            String id = idNameFor(v);
            if (id != null && BLACKLIST.contains(id)) {
                continue;
            }
            boolean member = sGlassEnabled && GlassBlocks.isMember(list, v, density);
            Drawable bg = v.getBackground();
            if (member) {
                // RecyclerView rebinds rows inside layout, i.e. after the strip pass: the card
                // (or a stale RowGlass of another position) is replaced here, in the last
                // moment before the frame is drawn, so nothing white is ever composited.
                if (!(bg instanceof RowGlass)) {
                    bg = new RowGlass(v, sEyePage);
                    v.setBackground(bg);
                }
                // Repin on every frame so all shares of a block draw with ONE answer. pinBlock()
                // invalidates only when a value actually changed, so a steady scroll adds no
                // extra repaint; the 160ms scroll settle is now just a safety net, not the fix.
                pinRowGlass((RowGlass) bg, v, blocks);
                // For the first two seconds of a page, also scrub inside the row every other
                // frame. 荣耀互联 builds its service grid asynchronously: the cards appear well
                // after the settle strips have finished, which is the white slab that flashes
                // on entry. A per-frame scrub bounds it to at most one frame.
                if ((sGuardTick & 1) == 0
                        && SystemClock.uptimeMillis() - sPageEnterAt < 2000L) {
                    scrubSurface(v, 35, 12);
                }
            } else if (bg != null && !(bg instanceof WallpaperDrawable)
                    && rowShaped(v, cw, density)) {
                clearRow(v);
            }
        }
        List<View> nested = sNestedCards.get(list);
        if (nested != null) {
            for (int i = 0; i < nested.size(); i++) {
                View v = nested.get(i);
                if (v != null && v.getBackground() != null) {
                    clearTile(v);
                }
            }
        }
        rescanNested(list, density);
    }

    /**
     * Fallback for pages that are not a RecyclerView: a vertical stack of full-width rows
     * directly under a scroll view. Returns -1 when the container does not look like a list,
     * so the caller keeps walking into it.
     */
    private static int stripStack(ViewGroup g) {
        if (g.getChildCount() < 2 || !insideScroller(g)) {
            return -1;
        }
        float density = density(g);
        List<GlassBlocks.Block> blocks = GlassBlocks.compute(g, density);
        if (blocks.isEmpty()) {
            return -1;
        }
        int members = 0;
        for (GlassBlocks.Block b : blocks) {
            members += b.count;
        }
        if (members < 2) {
            return -1;
        }
        int changed = 0;
        sManaged.put(g, Boolean.TRUE);
        updateColumn(g);
        for (GlassBlocks.Block b : blocks) {
            for (int i = b.first; i <= b.last(); i++) {
                View row = g.getChildAt(i);
                if (row == null) {
                    continue;
                }
                if (sGlassEnabled && !(row.getBackground() instanceof RowGlass)) {
                    row.setBackground(new RowGlass(row, sEyePage));
                    changed++;
                }
            }
        }
        changed += hidePane(g);
        // Same rule as the list pass: a block that contains a grid of small cards (the
        // 响铃 / 振动 / 静音 tiles on 声音和触感) shows those cards on top of its glass.
        changed += sweepNestedCards(g, blocks, density);
        // Free-standing HnListCardLayout panels on a stacked page (荣耀钱包's 快捷方式 block) draw
        // their card inside onDraw, so they stay white even though no row owns a card. Clearing
        // them lets the block behind - or the wallpaper - show through.
        for (int i = 0, n = g.getChildCount(); i < n; i++) {
            changed += clearListCardDeep(g.getChildAt(i), 0);
        }
        // Stack pages need the same last-moment guard as lists: the tiles of 声音和触感 are
        // re-carded by the page itself while it settles, which is after every strip pass.
        ensureFrameGuard(g);
        return changed;
    }

    /**
     * Clears a free-standing {@code HnListCardLayout} anywhere below a stacked container.
     *
     * <p>荣耀钱包 puts 快捷键唤起刷卡页 / 指纹唤起刷卡页 in two of them, two levels below the scroll
     * view's row, so the row pass never reaches them. They draw their card in {@code onDraw},
     * which is why that block stayed a solid white slab under an otherwise glass page.
     */
    private static int clearListCardDeep(View v, int depth) {
        if (v == null || depth > 3) {
            return 0;
        }
        int changed = 0;
        if (v.getClass().getName().contains("ListCardLayout")) {
            changed += clearRow(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                changed += clearListCardDeep(g.getChildAt(i), depth + 1);
            }
        }
        return changed;
    }

    private static boolean insideScroller(View v) {        try {
            android.view.ViewParent p = v.getParent();
            for (int depth = 0; depth < 8 && p instanceof View; depth++) {
                String cn = p.getClass().getName();
                if (cn.contains("ScrollView") || cn.contains("RecyclerView")) {
                    return true;
                }
                p = ((View) p).getParent();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Strips a card that lives <b>inside</b> a block (grid tile).
     *
     * <p>A background is not the only way MagicOS paints such a card. The 响铃 / 振动 / 静音
     * tiles carry an {@code HnListCardLayout} whose card keeps being drawn after the background
     * is gone: the layout draws it itself (its {@code onDraw} still runs because the view is
     * not marked "will not draw") and it may also carry a foreground. Removing all three
     * sources is what actually makes the tile transparent - clearing the background alone left
     * them white, which is why they only went away after a scroll had re-cleared every layer.
     */
    private static int clearTile(View v) {
        int changed = clearRow(v);
        try {
            if (v.getForeground() != null) {
                v.setForeground(null);
                changed++;
            }
        } catch (Throwable ignored) {
        }
        // The third layer a card can hide in: the view overlay. 账号中心's 账号安全 tile reported
        // no background and no foreground for the whole subtree, yet drew a pure white rounded
        // rectangle - the card is an overlay drawable, which is drawn after the content and is
        // therefore invisible to both hooks above. It is also why hiding the tile made the white
        // go away: an overlay is not drawn for a view that is not shown.
        try {
            android.view.ViewOverlay ov = v.getOverlay();
            if (ov != null) {
                ov.clear();
            }
        } catch (Throwable ignored) {
        }
        changed += scrubSurface(v, 75, 70);
        return changed;
    }

    /**
     * Removes the white that a container paints <b>below</b> its own background.
     *
     * <p>账号中心 puts 个人信息 and 账号安全 side by side in one row. Clearing the row's card
     * background made 个人信息 transparent but left 账号安全 pure white: on that card the
     * outer {@code FrameLayout} carries no card at all, the white comes from a child wrapper
     * that fills it (inset only by the card's padding, 444 of 542 = 82%). The same shape appears
     * wherever the ROM builds a card out of a white container inside a transparent frame, so
     * the surface is scrubbed downwards instead of only at its top level.
     *
     * <p>The width/height percentages are the caller's: a grid tile is mostly its own card
     * (75/70), while a full-width row that hosts one inset panel - 护眼统计 on 荣耀绿洲护眼,
     * a LinearLayout at 90% of the row and 38% of its height - needs a lower bar (72/28).
     * Everything smaller is left alone, which keeps the icon plates, the ripple layers and the
     * text backgrounds inside a card intact.
     */
    private static int scrubSurface(View v, int minWpct, int minHpct) {
        if (!(v instanceof ViewGroup)) {
            return 0;
        }
        ViewGroup g = (ViewGroup) v;
        int tw = v.getWidth();
        int th = v.getHeight();
        if (tw <= 0 || th <= 0) {
            return 0;
        }
        int changed = 0;
        for (int i = 0, n = g.getChildCount(); i < n; i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() != View.VISIBLE
                    || c.getWidth() * 100 < tw * minWpct
                    || c.getHeight() * 100 < th * minHpct) {
                continue;
            }
            Drawable bg = c.getBackground();
            if (bg != null && !(bg instanceof RowGlass) && !(bg instanceof SurfaceGlass)
                    && !(bg instanceof WallpaperDrawable) && isNearWhite(c.getContext(), bg)) {
                try {
                    c.setBackground(null);
                    changed++;
                } catch (Throwable ignored) {
                }
            }
            Drawable fg = c.getForeground();
            if (fg != null && isNearWhite(c.getContext(), fg)) {
                try {
                    c.setForeground(null);
                    changed++;
                } catch (Throwable ignored) {
                }
            }
            changed += scrubSurface(c, minWpct, minHpct);
        }
        return changed;
    }

    /** A flat, opaque, almost-white fill - the colour of an untouched MagicOS card. */
    private static boolean isNearWhite(Context ctx, Drawable d) {
        Integer flat = flatColorOf(d);
        int color = flat != null ? flat : sampleColor(ctx, d);
        if ((color >>> 24) != 255) {
            return false;
        }
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        return Math.min(r, Math.min(g, b)) >= 0xf0;
    }

    /**
     * Removes a row's own card but keeps its padding, so the row content does not move by a
     * single pixel when the native card (which carries padding) is replaced by the block.
     */
    private static int clearRow(View v) {
        if (v == null) {
            return 0;
        }
        Drawable bg = v.getBackground();
        if (bg == null || bg instanceof RowGlass || bg instanceof SurfaceGlass) {
            return 0;
        }
        int l = v.getPaddingLeft();
        int t = v.getPaddingTop();
        int r = v.getPaddingRight();
        int b = v.getPaddingBottom();
        try {
            v.setBackground(null);
            if (v.getPaddingLeft() != l || v.getPaddingTop() != t
                    || v.getPaddingRight() != r || v.getPaddingBottom() != b) {
                v.setPadding(l, t, r, b);
            }
        } catch (Throwable t2) {
            return 0;
        }
        // HnListCardLayout (and its siblings) do not only carry the card as a background: the
        // layout draws the card shape itself in onDraw, so a cleared background still leaves a
        // white card behind. The 响铃 / 振动 / 静音 tiles and the two cards at the bottom of
        // 荣耀钱包 (快捷键唤起刷卡页 / 指纹唤起刷卡页) are all this class. Children still draw
        // normally - willNotDraw only skips the container's own onDraw.
        try {
            if (v.getClass().getName().contains("ListCardLayout")) {
                v.setWillNotDraw(true);
            }
        } catch (Throwable ignored) {
        }
        if (sRowLog < 12) {
            sRowLog++;
            log(4, "row card cleared: " + v.getClass().getSimpleName()
                    + "#" + idNameFor(v) + " " + v.getWidth() + "x" + v.getHeight());
        }
        return 1;
    }

    /** Clears a container background unconditionally (used only for the two pane ids above). */
    private static int clearForce(View v) {
        if (v.getBackground() == null) {
            return 0;
        }
        int l = v.getPaddingLeft();
        int t = v.getPaddingTop();
        int r = v.getPaddingRight();
        int b = v.getPaddingBottom();
        try {
            v.setBackground(null);
            if (v.getPaddingLeft() != l || v.getPaddingTop() != t
                    || v.getPaddingRight() != r || v.getPaddingBottom() != b) {
                v.setPadding(l, t, r, b);
            }
        } catch (Throwable t2) {
            return 0;
        }
        return 1;
    }

    /** Clears an opaque flat surface that would cover the wallpaper layer. */
    /**
     * The page's top chrome: MagicOS' blurred app bar.
     *
     * <p>Every second-level page puts a {@code HnGradientBlurTopContainer} over the content with a
     * {@code HnBlurTopPattern} inside it, and that container paints a near-white surface behind
     * the title, the search field and the status-bar strip - it is what {@code
     * app:id/hn_blur_top_view} looks like before anything scrolls. It is not a card and not a row,
     * so neither the group rule nor {@link #clearCover} reaches it: the blur surface is drawn by
     * the container itself (its background is either a blurred bitmap or transparent with the
     * paint happening in {@code onDraw}), which is why 通知和状态栏 and 账号中心 kept a white band
     * across the whole top while the rest of the page was glass.
     *
     * <p>The band fades out while scrolling, and that behaviour is kept: only the paint is
     * silenced ({@code setWillNotDraw} plus a transparent/clamped background). Nothing touches
     * layout, alpha or the container's scroll callbacks, so the fade the page drives still runs -
     * it simply has no opaque surface left to reveal.
     *
     * <p>{@code setBackground(null)} is called at most once per view. MagicOS re-applies the blur
     * surface on scroll, and since setBackground() calls requestLayout() doing that from a
     * scroll pass would re-layout the bar on every frame. Later assignments only get their alpha
     * clamped, which invalidates the drawable without touching layout.
     */
    private static int clearTopChrome(View v) {
        String cn = v.getClass().getName();
        if (!(cn.contains("HnBlurTopPattern") || cn.contains("HnGradientBlurTopContainer")
                || cn.contains("HwAppBarLayout") || cn.contains("HwToolbar"))) {
            return 0;
        }
        int changed = 0;
        Drawable bg = v.getBackground();
        if (bg != null && !(bg instanceof RowGlass) && !(bg instanceof SurfaceGlass)
                && !(bg instanceof WallpaperDrawable)) {
            if (!sTopChrome.containsKey(v)) {
                try {
                    v.setBackground(null);
                    changed++;
                } catch (Throwable ignored) {
                }
            } else {
                try {
                    bg.mutate();
                    bg.setAlpha(0);
                    changed++;
                } catch (Throwable ignored) {
                }
            }
        }
        if (v instanceof ViewGroup) {
            try {
                if (!v.willNotDraw()) {
                    ((ViewGroup) v).setWillNotDraw(true);
                    changed++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (changed > 0) {
            sTopChrome.put(v, Boolean.TRUE);
            if (sTbLog < 12) {
                sTbLog++;
                log(4, "top chrome: " + cn.substring(cn.lastIndexOf('.') + 1)
                        + "#" + idNameFor(v) + " " + v.getWidth() + "x" + v.getHeight()
                        + " bg=" + (bg == null ? "null" : bg.getClass().getSimpleName()));
            }
        }
        return changed;
    }

    private static int clearCover(View v) {
        Drawable bg = v.getBackground();
        if (bg == null || bg instanceof RowGlass || bg instanceof SurfaceGlass
                || bg instanceof WallpaperDrawable) {
            return 0;
        }
        Context ctx = v.getContext();
        int color = sampleColor(ctx, bg);
        if (sSampleLog < 10) {
            sSampleLog++;
            log(4, "sample: " + v.getClass().getSimpleName() + "#" + idNameFor(v)
                    + " " + v.getWidth() + "x" + v.getHeight()
                    + " drawable=" + bg.getClass().getSimpleName()
                    + " color=#" + Integer.toHexString(color));
        }
        if (!isFlatSurface(color)) {
            return 0;
        }
        int l = v.getPaddingLeft();
        int t = v.getPaddingTop();
        int r = v.getPaddingRight();
        int b = v.getPaddingBottom();
        try {
            v.setBackground(null);
            if (v.getPaddingLeft() != l || v.getPaddingTop() != t
                    || v.getPaddingRight() != r || v.getPaddingBottom() != b) {
                v.setPadding(l, t, r, b);
            }
        } catch (Throwable t2) {
            return 0;
        }
        return 1;
    }

    /**
     * Honor paints one opaque rounded card behind each preference group from an
     * ItemDecoration ({@code gm.a}); it is not a view, so it can only be removed from the
     * RecyclerView's decoration list. Everything else is kept (dividers, spacing).
     */
    /**
     * Stops a decoration from painting, keeps it for its offsets.
     *
     * <p>账号中心's grid cards are not drawn by the cards at all: every view in 账号安全 reports
     * no background, no foreground and {@code willNotDraw}, yet the whole tile rectangle is pure
     * white - because the list's {@code ItemDecoration} paints it straight onto the canvas in
     * {@code onDraw}. No background hook can reach that paint, and hiding the tile made it
     * disappear only because the decoration skips views that are not shown.
     *
     * <p>So the decoration keeps its {@code getItemOffsets} (the 20dp gap between system groups
     * comes from there - dropping the whole decoration merges every block into one) but its draw
     * callbacks are muted. Hooking the abstract base is not possible from the module class
     * loader, so each concrete decoration is hooked by reflection on its own class; the
     * parameter types are never resolved, only the arity and the name are checked.
     */
    private static void muteDecorationDraw(Object deco) {
        Class<?> c = deco.getClass();
        if (!sDecoMuted.add(c)) {
            return;
        }
        int muted = 0;
        try {
            for (Method m : c.getMethods()) {
                String n = m.getName();
                if ((!"onDraw".equals(n) && !"onDrawOver".equals(n))
                        || m.getParameterCount() != 3) {
                    continue;
                }
                try {
                    sModule.hook(m).intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override
                        public Object intercept(
                                io.github.libxposed.api.XposedInterface.Chain chain) {
                            return null;
                        }
                    });
                    muted++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (muted > 0 && sDecoLog < 10) {
            sDecoLog++;
            log(4, "decoration draw muted: " + c.getName() + " (" + muted + ")");
        }
    }

    private static int dropCardDecorations(ViewGroup list) {
        // Only RecyclerView carries mItemDecorations. The field lookup walks the whole class
        // hierarchy on an AbsListView and always fails, so skip it there.
        if (!list.getClass().getName().contains("RecyclerView")) {
            return 0;
        }
        try {
            Class<?> c = list.getClass();
            java.lang.reflect.Field df = null;
            while (c != null && df == null) {
                try {
                    df = c.getDeclaredField("mItemDecorations");
                } catch (Throwable ignored) {
                }
                c = c.getSuperclass();
            }
            if (df == null) {
                return 0;
            }
            df.setAccessible(true);
            Object o = df.get(list);
            if (!(o instanceof List)) {
                return 0;
            }
            List<Object> keep = new ArrayList<>();
            int dropped = 0;
            StringBuilder names = new StringBuilder();
            for (Object d : (List<?>) o) {
                if (d == null) {
                    continue;
                }
                String dn = d.getClass().getName();
                names.append(' ').append(dn);
                // Only the dashboard's own card decoration is dropped: it paints an opaque
                // group card over the glass. The other decorations must stay, because their
                // getItemOffsets are what creates the 20dp gap between system groups - dropping
                // them makes every row contiguous and merges the whole page into one block.
                if ("gm.a".equals(dn)) {
                    dropped++;
                    continue;
                }
                muteDecorationDraw(d);
                keep.add(d);
            }
            if (dropped > 0) {
                df.set(list, keep);
                if (sDecoLog < 6) {
                    sDecoLog++;
                    log(4, "dropped native decorations (" + dropped + ") from "
                            + idNameFor(list) + ":" + names);
                }
            }
            return dropped;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * RecyclerView re-applies a recycled row's native card on bind without a layout pass, so
     * the strip is triggered from the list itself instead of a polling timer.
     */
    private static void ensureChildListener(final ViewGroup list) {
        // AbsListView has no OnChildAttachStateChangeListener; skip the reflection entirely.
        if (sChildWatched.containsKey(list)
                || !list.getClass().getName().contains("RecyclerView")) {
            return;
        }
        try {
            Class<?> iface = Class.forName(
                    "androidx.recyclerview.widget.RecyclerView$OnChildAttachStateChangeListener",
                    false, list.getClass().getClassLoader());
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method m, Object[] args) {
                            String name = m.getName();
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(p);
                            }
                            if ("equals".equals(name)) {
                                return args != null && args.length == 1 && args[0] == p;
                            }
                            if ("toString".equals(name)) {
                                return "honor-block-listener";
                            }
                            ViewGroup root = rootOf(list);
                            if (root != null) {
                                scheduleStrip(root);
                            }
                            return null;
                        }
                    });
            Method add = list.getClass().getMethod("addOnChildAttachStateChangeListener", iface);
            add.invoke(list, proxy);
            sChildWatched.put(list, Boolean.TRUE);
        } catch (Throwable t) {
            if (sDecoLog < 6) {
                sDecoLog++;
                log(4, "child listener unavailable: " + t);
            }
        }
    }

    private static ViewGroup rootOf(View v) {
        try {
            android.view.ViewParent p = v.getParent();
            View top = v;
            while (p instanceof View) {
                top = (View) p;
                p = top.getParent();
            }
            return top instanceof ViewGroup ? (ViewGroup) top : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static float density(View v) {
        try {
            return v.getResources().getDisplayMetrics().density;
        } catch (Throwable t) {
            return 3f;
        }
    }

    private static String idNameFor(View v) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return null;
        }
        String name = sIdNames.get(id);
        if (name != null) {
            return name;
        }
        try {
            name = v.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            name = "";
        }
        sIdNames.put(id, name);
        return name;
    }

    /** Samples the center pixel of a drawable by rendering it into an 8x8 bitmap. */
    private static int sampleColor(Context ctx, Drawable d) {
        if (d instanceof ColorDrawable) {
            return ((ColorDrawable) d).getColor();
        }
        Bitmap b = null;
        try {
            b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Drawable sample = d;
            Drawable.ConstantState cs = d.getConstantState();
            if (cs != null) {
                try {
                    sample = cs.newDrawable(ctx.getResources()).mutate();
                } catch (Throwable ignored) {
                }
            }
            sample.setBounds(0, 0, 8, 8);
            sample.draw(c);
            return b.getPixel(4, 4);
        } catch (Throwable t) {
            return 0;
        } finally {
            if (b != null) {
                b.recycle();
            }
        }
    }

    /** True for an opaque gray/white surface: alpha 255 and no visible color tint. */
    private static boolean isFlatSurface(int color) {
        if ((color >>> 24) != 255) {
            return false;
        }
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min <= 24;
    }

    // ---- wallpaper ----------------------------------------------------------

    /**
     * Cache key: the file the module UI wrote plus its modification stamp, so picking a new
     * wallpaper (or the module's random slot) invalidates the decode instead of showing the
     * previous photo.
     */
    private static String bitmapKey(AppearanceState st) {
        return st.mime + "|" + st.modified + "|" + st.candidates;
    }

    /** Full-resolution bitmap if it is already decoded, otherwise the fast preview. */
    private static Bitmap cachedBitmap(AppearanceState st) {
        String key = bitmapKey(st);
        synchronized (LOCK) {
            if (sFull != null && key.equals(sFullKey)) {
                return sFull;
            }
            if (sPreview != null && key.equals(sPreviewKey)) {
                return sPreview;
            }
            return null;
        }
    }

    /**
     * Decodes the wallpaper in two steps on a background thread:
     *   1. a 720px preview (a few ms) so a cold process can paint its very first frame with
     *      the wallpaper instead of the theme's white window background,
     *   2. the full-resolution bitmap, swapped in later without relayout.
     * The decode never runs on the main thread - that was the 300-450ms stall that kept the
     * white window background on screen.
     */
    private static void preloadAsync() {
        if (sPreloading) {
            return;
        }
        sPreloading = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    XposedModule m = sModule;
                    if (m == null) {
                        return;
                    }
                    AppearanceState st = AppearanceState.read(m);
                    if (st == null || !st.enabled) {
                        return;
                    }
                    String key = bitmapKey(st);

                    Bitmap preview = decodeBitmap(m, st, PREVIEW_DECODE_DIMENSION);
                    if (preview != null) {
                        synchronized (LOCK) {
                            // The previous preview may still be shown on another activity's
                            // layer, so it is dropped only by the GC, never recycled here.
                            sPreview = preview;
                            sPreviewKey = key;
                        }
                        log(4, "wallpaper preview: " + preview.getWidth() + "x" + preview.getHeight());
                        postRetry();
                    }

                    Bitmap full = decodeBitmap(m, st, MAX_DECODE_DIMENSION);
                    if (full != null) {
                        synchronized (LOCK) {
                            sFull = full;
                            sFullKey = key;
                        }
                        log(4, "wallpaper decoded: " + full.getWidth() + "x" + full.getHeight());
                        postRetry();
                    }
                } catch (Throwable t) {
                    log(4, "preload failed: " + t);
                } finally {
                    sPreloading = false;
                }
            }
        }, "honor-bg-preload");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void postRetry() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                retryPending();
            }
        });
    }

    private static void retryPending() {
        Activity a = sPending == null ? null : sPending.get();
        if (a == null) {
            return;
        }
        sPending = null;
        XposedModule m = sModule;
        if (m != null) {
            onActivityReady(m, a);
        }
    }

    /**
     * Keeps the layer in sync with the saved appearance: preview -> full resolution once the
     * background decode lands, and a brand new wallpaper when the user picked one in the module
     * UI. Same view, same size: no relayout, no second background layer, no flash.
     */
    private static void syncLayer(Activity activity, ViewGroup decor, AppearanceState st) {
        Bitmap bitmap = cachedBitmap(st);
        if (bitmap == null) {
            sPending = new WeakReference<>(activity);
            preloadAsync();
            return;
        }
        boolean sameKey = bitmapKey(st).equals(sLayerKey);
        View layer = decor.findViewWithTag(VIEW_TAG);
        try {
            if (layer instanceof ImageView) {
                ImageView iv = (ImageView) layer;
                if (!sameKey) {
                    iv.setImageBitmap(bitmap);
                    iv.setAlpha(clampAlpha(st.opacity));
                    sLayerBitmap = bitmap;
                    sLayerKey = bitmapKey(st);
                    setWindowWallpaper(activity, st, bitmap);
                    log(4, "wallpaper replaced");
                } else if (bitmap != sLayerBitmap) {
                    iv.setImageBitmap(bitmap);
                    sLayerBitmap = bitmap;
                    setWindowWallpaper(activity, st, bitmap);
                    log(4, "wallpaper upgraded to full resolution");
                }
            }
        } catch (Throwable t) {
            log(4, "wallpaper sync failed: " + t);
        }
    }

    private static float clampAlpha(int opacity) {
        float a = opacity / 100f;
        return a < 0f ? 0f : (a > 1f ? 1f : a);
    }

    private static void setWindowWallpaper(Activity activity, AppearanceState st, Bitmap bitmap) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            WallpaperDrawable d = new WallpaperDrawable(bitmap);
            d.setAlpha(Math.round(clampAlpha(st.opacity) * 255f));
            window.setBackgroundDrawable(d);
        } catch (Throwable t) {
            log(4, "window background failed: " + t);
        }
    }

    private static ParcelFileDescriptor openFirstRemoteFile(XposedModule module, List<String> candidates) {
        for (String path : candidates) {
            try {
                ParcelFileDescriptor pfd = module.openRemoteFile(path);
                if (pfd != null) {
                    log(4, "loaded remote file: " + path);
                    return pfd;
                }
            } catch (Throwable ignored) {
                // not present, try the next candidate
            }
        }
        return null;
    }

    private static Bitmap decodeBitmap(XposedModule module, AppearanceState st, int maxDimension) {
        ParcelFileDescriptor pfd = openFirstRemoteFile(module, st.candidates);
        if (pfd == null) {
            return null;
        }
        try {
            return decodeStream(pfd, maxDimension);
        } catch (Throwable t) {
            log(4, "decode failed: " + t);
            return null;
        } finally {
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bitmap decodeStream(ParcelFileDescriptor pfd, int maxDimension) throws Exception {
        long size = -1;
        try {
            size = pfd.getStatSize();
        } catch (Throwable ignored) {
        }
        if (size > MAX_BYTES) {
            log(4, "file too large: " + size);
            return null;
        }
        byte[] data = readAll(pfd);
        if (data == null || data.length == 0) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int maxSide = maxDimension > 0 ? maxDimension : MAX_DECODE_DIMENSION;
        int sample = 1;
        while ((bounds.outWidth / sample) > maxSide || (bounds.outHeight / sample) > maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static byte[] readAll(ParcelFileDescriptor pfd) throws Exception {
        InputStream in = new FileInputStream(pfd.getFileDescriptor());
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024 * 1024);
            byte[] buf = new byte[256 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    return null;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Window background that draws the wallpaper center-cropped, exactly like the layer below
     * it. Used only for the first frame, so a sharp copy of the bitmap is enough.
     */
    private static final class WallpaperDrawable extends Drawable {
        private final Bitmap bitmap;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);

        WallpaperDrawable(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            int bw = b.width();
            int bh = b.height();
            if (bw <= 0 || bh <= 0 || bitmap == null || bitmap.isRecycled()) {
                return;
            }
            float scale = Math.max(bw / (float) bitmap.getWidth(), bh / (float) bitmap.getHeight());
            float dw = bitmap.getWidth() * scale;
            float dh = bitmap.getHeight() * scale;
            float left = b.left + (bw - dw) / 2f;
            float top = b.top + (bh - dh) / 2f;
            android.graphics.RectF dst = new android.graphics.RectF(left, top, left + dw, top + dh);
            canvas.drawBitmap(bitmap, null, dst, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static void log(int priority, String msg) {
        try {
            XposedModule m = sModule;
            if (m != null) {
                m.log(priority, TAG, msg);
            }
        } catch (Throwable ignored) {
        }
    }
}
