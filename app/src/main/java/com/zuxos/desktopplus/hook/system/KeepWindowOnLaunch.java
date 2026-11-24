package com.zuxos.desktopplus.hook.system;

import android.app.ActivityOptions;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: a window on the monitor stays as it is when the app in it opens another
 * app's screen - Files' save or open screen, a picker, a chooser. Such a screen starts inside the
 * opener's window, and Android then gives that window the mode worked out for the new screen
 * ({@code TaskDisplayArea.getOrCreateRootTask}: a reused task takes the launch's windowing mode),
 * which on ZUI's monitor desktop is floating: a maximised app turned into a small window. It also
 * lays the window out again ({@code LaunchParamsController.layoutTask}), so a maximised app lost
 * the size it had floating. ZUI already leaves the layout alone in its PC and tablet desktop modes
 * ("do not layoutTask"); on the monitor it did not.
 *
 * <p>So while ZUI's monitor desktop is on, for a window there that has already been on screen and
 * a launch that asks for no mode or bounds of its own, the launch asks for the mode the window
 * has, and the layout is left out, as ZUI does in its other desktop modes. New windows, launches
 * that ask for a mode (our Full screen, the game launcher), the tablet and ZUI's other modes are
 * untouched. The switch is read at boot and when the settings change ({@link SystemBridge}).
 */
public final class KeepWindowOnLaunch {

    private static final int FULLSCREEN = 1;
    private static final int FREEFORM = 5;

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    private static Class<?> sTask;
    private static Method sDesktopOn;
    private static Method sDesktopDisplay;
    private static Field sHasBeenVisible;
    private static Field sRecordIntent;
    private static Method sLaunchMode;

    private KeepWindowOnLaunch() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> area = Class.forName(SystemNames.TASK_DISPLAY_AREA, false, loader);
            Class<?> controller = Class.forName(SystemNames.LAUNCH_PARAMS_CONTROLLER, false,
                    loader);
            Class<?> desktop = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER, false, loader);
            sTask = Class.forName(SystemNames.TASK, false, loader);
            Class<?> record = Class.forName(SystemNames.ACTIVITY_RECORD, false, loader);
            sDesktopOn = desktop.getMethod(SystemNames.IS_UNDER_EXTERNAL_DISPLAY_DESKTOP_MODE);
            sDesktopDisplay = desktop.getMethod(
                    SystemNames.GET_EXTERNAL_DISPLAY_DESKTOP_DISPLAY_ID);
            sHasBeenVisible = sTask.getDeclaredField(SystemNames.M_HAS_BEEN_VISIBLE);
            sHasBeenVisible.setAccessible(true);
            sRecordIntent = record.getDeclaredField(SystemNames.INTENT);
            sRecordIntent.setAccessible(true);
            sLaunchMode = ActivityOptions.class.getMethod(SystemNames.GET_LAUNCH_WINDOWING_MODE);
            int hooked = 0;
            for (Method m : area.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                // The one that takes the windowing mode itself, and the task it may reuse.
                if (m.getName().equals(SystemNames.GET_OR_CREATE_ROOT_TASK) && p.length == 8
                        && p[1] == int.class && p[4] == sTask
                        && p[6] == ActivityOptions.class) {
                    XposedBridge.hookMethod(m, ROOT_TASK);
                    hooked++;
                }
            }
            if (hooked == 0) {
                L.w("keep window: Android's root task choice not found - a window opening "
                        + "another app may still turn floating");
                return;
            }
            hooked += XposedBridge.hookAllMethods(controller, SystemNames.LAYOUT_TASK, LAYOUT)
                    .size();
            reread();
            L.i("keep window: a window on the monitor stays as it is when it opens another app x"
                    + hooked + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("keep window: not installed, a window opening another app may still turn "
                    + "floating (" + t + ")");
        }
    }

    /** The switch, read off the launch path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.keepWindowOnLaunch();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    /** The mode a launch gives a window it reuses: the one the window has, when it is ours. */
    private static final XC_MethodHook ROOT_TASK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object task = param.args[4];
                int asked = (Integer) param.args[1];
                if ((asked != FULLSCREEN && asked != FREEFORM)
                        || !kept(task, param.args[0], param.args[6], false)
                        || !Boolean.TRUE.equals(Reflect.call(task, SystemNames.IS_ROOT_TASK))) {
                    return;
                }
                Object mode = Reflect.call(task, SystemNames.GET_WINDOWING_MODE);
                if (!(mode instanceof Integer) || (Integer) mode == asked
                        || ((Integer) mode != FULLSCREEN && (Integer) mode != FREEFORM)) {
                    return;
                }
                param.args[1] = mode;
                said(task, param.args[0], (Integer) mode == FULLSCREEN ? "maximised" : "floating");
            } catch (Throwable t) {
                if (L.once("KeepWindowOnLaunch.root")) {
                    L.w("keep window: a launch's window left to Android (" + t + ")");
                }
            }
        }
    };

    /** The layout of a window a launch reuses: left out on the monitor, as ZUI's desktops do. */
    private static final XC_MethodHook LAYOUT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length == 5 && kept(param.args[0], param.args[2], param.args[4],
                        true)) {
                    param.setResult(false);
                    said(param.args[0], param.args[2], "at its size");
                }
            } catch (Throwable t) {
                if (L.once("KeepWindowOnLaunch.layout")) {
                    L.w("keep window: a window's layout left to Android (" + t + ")");
                }
            }
        }
    };

    /**
     * Whether a launch into this task leaves the window as it is: our switch on, ZUI's monitor
     * desktop on and the task on it, the window already shown, and the launch asking for no mode
     * (nor bounds, for the layout) and carrying no mark of ours for full screen.
     */
    private static boolean kept(Object task, Object record, Object options, boolean layout)
            throws Exception {
        if (!sOn || !sTask.isInstance(task) || !sHasBeenVisible.getBoolean(task)
                || !Boolean.TRUE.equals(sDesktopOn.invoke(null))) {
            return false;
        }
        Object display = Reflect.call(task, SystemNames.GET_DISPLAY_ID);
        if (!(display instanceof Integer) || !display.equals(sDesktopDisplay.invoke(null))) {
            return false;
        }
        if (options instanceof ActivityOptions) {
            Object mode = sLaunchMode.invoke(options);
            if (!Integer.valueOf(0).equals(mode)
                    || (layout && ((ActivityOptions) options).getLaunchBounds() != null)) {
                return false;
            }
        }
        return record == null || !SystemFullscreen.wantsFullScreen(sRecordIntent.get(record));
    }

    /** One line a task, the first time a launch into it is kept from changing it. */
    private static void said(Object task, Object record, String how) {
        Object id = Reflect.field(task, SystemNames.M_TASK_ID);
        if (L.once("KeepWindowOnLaunch." + id + how)) {
            Object opened = record == null ? null
                    : Reflect.field(record, SystemNames.SHORT_COMPONENT_NAME);
            L.i("keep window: task " + id + " (" + Reflect.field(task, SystemNames.REAL_ACTIVITY)
                    + ") kept " + how + " when " + opened + " opened in it");
        }
    }
}
