package com.honor.hb;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Honor MagicOS (com.android.settings) port of HyperBackground.
 *
 * Two responsibilities:
 *  - inside com.android.settings: install the rendering bridge that turns the saved
 *    appearance into an actual background for Honor's Settings activities;
 *  - inside our own module package: replace the original HyperOS-only Compose UI
 *    with a trimmed settings page (see HonorSettingsUi) that only offers what works
 *    on MagicOS, keeping the source module's look and feel.
 */
public final class HonorHookEntry extends XposedModule {

    private static final String TAG = "HonorBackground";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String MODULE_PACKAGE = "com.ciallo.hyperbackground";
    private static final String MODULE_MAIN_ACTIVITY = "com.ciallo.hyperbackground.ui.MainActivity";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /**
     * Every app that hosts a settings page the user can reach from the settings home screen.
     *
     * <p>MagicOS does not keep its settings in one process: 蓝牙, 显示和亮度, 辅助功能... are
     * {@code com.android.settings} activities, but the entries below open a dedicated system app
     * - 通知和状态栏 / 应用 / 电池 / 隐私和安全 are {@code com.hihonor.systemmanager}, 荣耀互联 is
     * {@code com.hihonor.controlcenter}, 荣耀 AI & YOYO is {@code com.hihonor.assistant}, 账号中心 is
     * {@code com.hihonor.id}, 荣耀钱包 is {@code com.hihonor.wallet} and 健康使用手机 is
     * {@code com.hihonor.parentcontrol}. Hooking only {@code com.android.settings} is why those
     * pages kept the stock white cards while the rest of settings was glass.
     *
     * <p>Each of these has to be enabled in the module's LSPosed scope as well; a package that is
     * not in scope never loads this class at all.
     */
    private static final java.util.Set<String> TARGET_PACKAGES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                SETTINGS_PACKAGE,
                "com.hihonor.systemmanager",
                "com.hihonor.controlcenter",
                "com.hihonor.assistant",
                "com.hihonor.id",
                "com.hihonor.wallet",
                "com.hihonor.parentcontrol"));

    private static volatile XposedModule sInstance;
    private int sSkipped;

    public HonorHookEntry() {
        super();
    }

    /** Provides a handle to the attached module for the rendering side. */
    static XposedModule getInstance() {
        return sInstance;
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            String pkg = param.getPackageName();
            if (TARGET_PACKAGES.contains(pkg)) {
                sInstance = this;
                installLifecycleHooks();
                log(4, TAG, "installed settings-background hooks for " + pkg);
                HonorRenderer.onProcessLoaded(this);
                return;
            }
            if (MODULE_PACKAGE.equals(pkg)) {
                installModuleUiHooks();
                log(4, TAG, "installed trimmed-settings-ui hooks for " + pkg);
                return;
            }
            if (SYSTEMUI_PACKAGE.equals(pkg)) {
                installStartingWindowHooks(param);
                return;
            }
        } catch (Throwable t) {
            safeLog(4, "install hooks failed: " + t);
            for (StackTraceElement e : t.getStackTrace()) {
                safeLog(4, "  at " + e);
            }
        }
    }

    // ---- settings process: background rendering -----------------------------

    private void installLifecycleHooks() throws Exception {
        Method onCreate = Instrumentation.class.getDeclaredMethod(
                "callActivityOnCreate", Activity.class, Bundle.class);
        hook(onCreate).intercept(new ActivityHook());
        safeLog(4, "hooked Instrumentation.callActivityOnCreate");

        Method onResume = Instrumentation.class.getDeclaredMethod(
                "callActivityOnResume", Activity.class);
        hook(onResume).intercept(new ActivityHook());
        safeLog(4, "hooked Instrumentation.callActivityOnResume");

        try {
            // Fallback in case Instrumentation callbacks are not reached on some builds.
            Method activityResume = Activity.class.getDeclaredMethod("onResume");
            hook(activityResume).intercept(new ActivityHook());
            safeLog(4, "hooked Activity.onResume");
        } catch (Throwable t) {
            safeLog(4, "Activity.onResume hook failed: " + t);
        }
    }

    /** Applies the module appearance right after the framework dispatched the lifecycle event. */
    private final class ActivityHook implements XposedInterface.Hooker {
        ActivityHook() {
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object arg0 = null;
            try {
                arg0 = chain.getArg(0);
                safeLog(4, "intercept " + chain.getExecutable().getName()
                        + " arg0=" + (arg0 == null ? "null" : arg0.getClass().getName()));
            } catch (Throwable ignored) {
            }
            Object result = chain.proceed();
            try {
                if (arg0 instanceof Activity) {
                    applyTo((Activity) arg0);
                }
            } catch (Throwable t) {
                safeLog(4, "apply after proceed failed: " + t);
            }
            return result;
        }
    }

    private void applyTo(Activity activity) {
        try {
            if (activity == null) {
                return;
            }
            String pkg = activity.getPackageName();
            if (pkg == null || !TARGET_PACKAGES.contains(pkg)) {
                return;
            }
            HonorRenderer.onActivityReady(this, activity);
        } catch (Throwable t) {
            safeLog(4, "apply to " + (activity == null ? "null" : activity.getClass().getName()) + " failed: " + t);
        }
    }

    // ---- module app process: trimmed settings UI ----------------------------

    private void installModuleUiHooks() throws Exception {
        Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        hook(onCreate).intercept(new ModuleCreateHook());
        safeLog(4, "hooked Activity.onCreate (module UI)");

        Method onResult = Activity.class.getDeclaredMethod(
                "onActivityResult", int.class, int.class, Intent.class);
        hook(onResult).intercept(new ModuleResultHook());
        safeLog(4, "hooked Activity.onActivityResult (module UI)");
    }

    private final class ModuleCreateHook implements XposedInterface.Hooker {
        ModuleCreateHook() {
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                // Activity#onCreate is an instance method: "this" is the Activity and arg0 is the Bundle.
                Object self = chain.getThisObject();
                safeLog(4, "module onCreate: "
                        + (self == null ? "null" : self.getClass().getName()));
                if (self instanceof Activity) {
                    final Activity activity = (Activity) self;
                    if (MODULE_MAIN_ACTIVITY.equals(activity.getClass().getName())) {
                        safeLog(4, "posting trimmed ui for " + activity.getClass().getName());
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    HonorSettingsUi.show(activity);
                                    safeLog(4, "trimmed ui shown");
                                } catch (Throwable t) {
                                    safeLog(4, "trimmed ui failed: " + t);
                                }
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                safeLog(4, "module create hook failed: " + t);
            }
            return result;
        }
    }

    private final class ModuleResultHook implements XposedInterface.Hooker {
        ModuleResultHook() {
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                // Activity#onActivityResult is an instance method: "this" is the Activity.
                Object self = chain.getThisObject();
                if (self instanceof Activity
                        && MODULE_MAIN_ACTIVITY.equals(self.getClass().getName())) {
                    int requestCode = (Integer) chain.getArg(0);
                    int resultCode = (Integer) chain.getArg(1);
                    Intent data = (Intent) chain.getArg(2);
                    HonorSettingsUi.onPickResult((Activity) self, requestCode, resultCode, data);
                }
            } catch (Throwable t) {
                safeLog(4, "module result hook failed: " + t);
            }
            return result;
        }
    }

    // ---- systemui process: suppress the white starting window for settings ----

    /**
     * Honor's Settings uses Theme.Magic.Settings, whose starting window is a solid (almost
     * fully white) splash screen that SystemUI draws before the activity's first frame. On a
     * photo background that reads as a white flash every time a settings page opens. The
     * surface is created inside the systemui process, so the only place to skip it for our
     * target package is the startingsurface drawer.
     */
    private void installStartingWindowHooks(XposedModuleInterface.PackageLoadedParam param) {
        try {
            ClassLoader loader = param.getDefaultClassLoader();
            Class<?> infoClass = Class.forName("android.window.StartingWindowInfo", false, loader);
            Class<?> snapshotClass = null;
            try {
                snapshotClass = Class.forName("android.window.TaskSnapshot", false, loader);
            } catch (Throwable ignored) {
            }
            String[] drawerNames = {
                    "com.android.wm.shell.startingsurface.StartingSurfaceDrawer",
                    "com.android.wm.shell.startingsurface.HnSplashscreenWindowCreator",
                    "com.android.wm.shell.startingsurface.SplashscreenWindowCreator",
                    "com.android.wm.shell.startingsurface.SnapshotWindowCreator",
            };
            int installed = 0;
            for (String clsName : drawerNames) {
                Class<?> cls;
                try {
                    cls = Class.forName(clsName, false, loader);
                } catch (Throwable t) {
                    safeLog(4, "starting-surface class unavailable: " + clsName);
                    continue;
                }
                installed += hookIfPresent(cls, "addSplashScreenStartingWindow", infoClass, int.class);
                installed += hookIfPresent(cls, "addWindowlessStartingSurface", infoClass);
                if (snapshotClass != null) {
                    installed += hookIfPresent(cls, "makeTaskSnapshotWindow", infoClass, snapshotClass);
                }
            }
            safeLog(4, "starting-window suppression installed (" + installed
                    + " hooks) for " + SETTINGS_PACKAGE);
        } catch (Throwable t) {
            safeLog(4, "starting-window hooks failed: " + t);
        }
    }

    private int hookIfPresent(Class<?> cls, String name, Class<?>... types) {
        try {
            Method m = cls.getDeclaredMethod(name, types);
            m.setAccessible(true);
            hook(m).intercept(new SkipStartingSurfaceHook(cls.getSimpleName() + "." + name));
            safeLog(4, "hooked " + cls.getSimpleName() + "." + name);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private final class SkipStartingSurfaceHook implements XposedInterface.Hooker {
        private final String methodName;

        SkipStartingSurfaceHook(String methodName) {
            this.methodName = methodName;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                Object info = chain.getArg(0);
                int calls = sSkipped++;
                String pkg = startingWindowPackage(info);
                if (calls < 12) {
                    safeLog(4, "starting window call " + methodName + " pkg=" + pkg
                            + " type=" + startingWindowType(info)
                            + " fields=" + (calls < 3 ? describeFields(info) : ""));
                }
                if (SETTINGS_PACKAGE.equals(pkg)) {
                    if (calls < 12) {
                        safeLog(4, "skipped starting window (" + methodName + ") for " + pkg);
                    }
                    return null;
                }
            } catch (Throwable t) {
                safeLog(4, "starting-window probe failed: " + t);
            }
            return chain.proceed();
        }
    }

    private static String describeFields(Object info) {
        if (info == null) {
            return "null-info";
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : info.getClass().getFields()) {
                Object v;
                try {
                    v = f.get(info);
                } catch (Throwable t) {
                    v = "<err>";
                }
                sb.append(f.getName());
                if (v instanceof ComponentName) {
                    sb.append('=').append(((ComponentName) v).getPackageName());
                } else if (v instanceof ActivityInfo) {
                    sb.append('=').append(((ActivityInfo) v).packageName);
                } else if (v != null && v.getClass().getName()
                        .endsWith("ActivityManager$RunningTaskInfo")) {
                    sb.append("=<task:");
                    try {
                        for (Field ff : v.getClass().getFields()) {
                            Object sv = ff.get(v);
                            if (sv instanceof ComponentName) {
                                sb.append(((ComponentName) sv).getPackageName());
                            } else if (sv instanceof ActivityInfo) {
                                sb.append(((ActivityInfo) sv).packageName);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    sb.append('>');
                } else {
                    sb.append('=').append(v == null ? "null" : v.getClass().getSimpleName());
                }
                sb.append(' ');
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static int startingWindowType(Object startingWindowInfo) {
        try {
            return startingWindowInfo.getClass().getField("startingWindowTypeParameter")
                    .getInt(startingWindowInfo);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Resolves the target package of a StartingWindowInfo. Honor's startingsurface passes
     * copies whose {@code targetActivityInfo} may be null, so fall back to any ActivityInfo /
     * ComponentName field, including the ones nested inside {@code taskInfo}.
     */
    private static String startingWindowPackage(Object startingWindowInfo) {
        if (startingWindowInfo == null) {
            return null;
        }
        try {
            for (Field f : startingWindowInfo.getClass().getFields()) {
                Object v = f.get(startingWindowInfo);
                if (v instanceof ActivityInfo) {
                    return ((ActivityInfo) v).packageName;
                }
                if (v instanceof ComponentName) {
                    return ((ComponentName) v).getPackageName();
                }
                if (v != null && v.getClass().getName()
                        .equals("android.app.ActivityManager$RunningTaskInfo")) {
                    String nested = taskPackage(v);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String taskPackage(Object runningTaskInfo) {
        try {
            for (Field f : runningTaskInfo.getClass().getFields()) {
                Object v = f.get(runningTaskInfo);
                if (v instanceof ComponentName) {
                    return ((ComponentName) v).getPackageName();
                }
                if (v instanceof ActivityInfo) {
                    return ((ActivityInfo) v).packageName;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void safeLog(int priority, String msg) {
        try {
            log(priority, TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
