package com.zuxos.desktopplus.hook.system;

import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.SurfaceControl;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.logic.ScreenFit;

import java.lang.reflect.Method;
import java.util.function.Predicate;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: a screen saver on both screens while ZUI's monitor desktop is on.
 *
 * <p>Android runs one screen saver at a time, in one activity on one screen
 * ({@code ActivityTaskManagerService.startDreamActivityInternal}, which names no screen), and ZUI
 * adds nothing for a second one. So once that activity is started, the other screen gets a live
 * copy of it: Android's own surface mirror ({@code SurfaceControl.mirrorSurface}, as its display
 * mirroring uses), put in that screen's overlay layer above everything, scaled to fill it. The
 * screen saver draws once; the compositor shows it twice.
 *
 * <p>Filling, not fitting: the tablet's 16:10 on the monitor's 16:9 loses a few percent at the top
 * and bottom, and needs no black bars of its own. That way the only layer this ever adds is the
 * mirror, and a mirror whose screen saver has gone shows nothing: one left behind could never
 * cover the desktop.
 *
 * <p>A screen saver ends at input on its own window, which input on the other screen never
 * reaches. The power manager hears every input with its screen
 * ({@code userActivityFromNative}, which Motorola's code already uses to wake its desktop), so
 * input on any screen but the screen saver's ends it, as if it had been on the screen saver. The
 * first click or key there still reaches the window under the copy; a mouse moved first is not a
 * click. That hook is on every input of both screens, so it is put on only while a copy is up and
 * taken off with it: with no screen saver showing, input takes exactly the path it always did.
 *
 * <p>The copy goes when the dream manager says the screen saver stopped
 * ({@code reportDreamingStopped}, for every dream, previews included), and before any new one.
 */
public final class DreamBothScreens {

    /** {@code WindowConfiguration.ACTIVITY_TYPE_DREAM}. */
    private static final int DREAM_TYPE = 5;
    /**
     * Input on the other screen this soon after the copy came up does not end it: the mouse is
     * often still moving from the click on Preview that started the screen saver.
     */
    private static final long GRACE_MS = 1000L;
    private static final int MAX_LINES = 12;
    private static final long HOUR_MS = 3_600_000L;

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    private static Method sDesktopOn;
    private static Method sDesktopDisplay;
    private static Method sMirror;
    private static Method sGetActivity;
    /** The power manager's input from each screen, hooked only while a copy is up. */
    private static Method sInput;
    private static XC_MethodHook.Unhook sInputHook;

    /** The copy on screen, its screen and the screen saver's own; guarded by the class. */
    private static SurfaceControl sCopy;
    private static int sCopyScreen = -1;
    /** The screen saver's own screen while a copy is up, else -1: the one read on each input. */
    private static volatile int sDreamScreen = -1;
    private static volatile long sCopySince;
    private static long sHour = -1;
    private static int sLines;

    private DreamBothScreens() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        reread();
        try {
            Class<?> desktop = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER, false, loader);
            sDesktopOn = desktop.getMethod(SystemNames.IS_UNDER_EXTERNAL_DISPLAY_DESKTOP_MODE);
            sDesktopDisplay = desktop.getMethod(
                    SystemNames.GET_EXTERNAL_DISPLAY_DESKTOP_DISPLAY_ID);
            sMirror = SurfaceControl.class.getMethod(SystemNames.MIRROR_SURFACE,
                    SurfaceControl.class);
            sGetActivity = Class.forName(SystemNames.ROOT_WINDOW_CONTAINER, false, loader)
                    .getMethod(SystemNames.GET_ACTIVITY, Predicate.class);
            Class<?> tasks = Class.forName(SystemNames.ACTIVITY_TASK_MANAGER_SERVICE, false,
                    loader);
            Class<?> dreams = Class.forName(SystemNames.DREAM_MANAGER_SERVICE, false, loader);
            Class<?> power = Class.forName(SystemNames.POWER_MANAGER_SERVICE, false, loader);
            int hooked = XposedBridge.hookAllMethods(tasks,
                    SystemNames.START_DREAM_ACTIVITY_INTERNAL, STARTED).size();
            hooked += XposedBridge.hookAllMethods(dreams, SystemNames.REPORT_DREAMING_STOPPED,
                    STOPPED).size();
            sInput = power.getDeclaredMethod(SystemNames.USER_ACTIVITY_FROM_NATIVE, long.class,
                    int.class, int.class, int.class);
            L.i("screen saver: a copy on the other screen while the monitor's desktop is on x"
                    + hooked + ", input on it watched only while it is up"
                    + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            sMirror = null;
            L.w("screen saver: on one screen only, as Android has it (" + t + ")");
        }
    }

    /** The switch, read off the system's path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.dreamBothScreens();
        } catch (Throwable t) {
            // The last value stands.
        }
        if (!sOn) {
            remove("switched off");
        }
    }

    private static final XC_MethodHook STARTED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sOn || sMirror == null || param.getResult() == null
                        || !Boolean.TRUE.equals(sDesktopOn.invoke(null))) {
                    return;
                }
                copy(param.thisObject);
            } catch (Throwable t) {
                // The screen saver shows on its own screen, as Android has it.
                if (L.once("DreamBothScreens.copy")) {
                    L.w("screen saver: no copy on the other screen this time (" + t + ")");
                }
            }
        }
    };

    private static final XC_MethodHook STOPPED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                remove("the screen saver stopped");
            } catch (Throwable ignored) {
                // The mirror of a screen saver that has gone shows nothing.
            }
        }
    };

    private static final XC_MethodHook INPUT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            // Every input passes here: one read when no copy is up.
            int dream = sDreamScreen;
            if (dream < 0) {
                return;
            }
            try {
                if (param.args.length < 3 || !(param.args[2] instanceof Integer)
                        || (Integer) param.args[2] == dream
                        || SystemClock.uptimeMillis() - sCopySince < GRACE_MS) {
                    return;
                }
                int screen = (Integer) param.args[2];
                sDreamScreen = -1;
                Object dreams = Reflect.field(param.thisObject, SystemNames.M_DREAM_MANAGER);
                Object handler = Reflect.field(param.thisObject, SystemNames.M_HANDLER);
                Runnable end = () -> {
                    try {
                        Reflect.call(dreams, SystemNames.STOP_DREAM, false,
                                "input on the screen with the screen saver's copy");
                    } catch (Throwable ignored) {
                        // The screen saver goes on; input on its own screen still ends it.
                    }
                };
                // Off the input thread: the dream manager takes its own lock.
                if (!(handler instanceof Handler) || !((Handler) handler).post(end)) {
                    end.run();
                }
                if (take()) {
                    L.i("screen saver: input on screen " + screen + " ended it");
                }
            } catch (Throwable ignored) {
                // The screen saver goes on; input on its own screen still ends it.
            }
        }
    };

    /** Puts the copy of the screen saver just started on the other screen. */
    private static void copy(Object tasks) throws Exception {
        Object lock = Reflect.field(tasks, SystemNames.M_GLOBAL_LOCK);
        Object root = Reflect.field(tasks, SystemNames.M_ROOT_WINDOW_CONTAINER);
        Object desktop = sDesktopDisplay.invoke(null);
        if (lock == null || root == null || !(desktop instanceof Integer)
                || (Integer) desktop <= 0) {
            return;
        }
        SurfaceControl copy;
        int from;
        int to;
        float[] fit;
        synchronized (lock) {
            Predicate<Object> dream = a -> Integer.valueOf(DREAM_TYPE)
                    .equals(Reflect.call(a, SystemNames.GET_ACTIVITY_TYPE))
                    && !Boolean.TRUE.equals(Reflect.field(a, SystemNames.FINISHING));
            Object activity = sGetActivity.invoke(root, dream);
            Object own = Reflect.call(activity, SystemNames.GET_SURFACE_CONTROL);
            Object screen = Reflect.call(activity, SystemNames.GET_DISPLAY_ID);
            if (!(own instanceof SurfaceControl) || !(screen instanceof Integer)) {
                return;
            }
            from = (Integer) screen;
            // The other screen: the tablet for a screen saver on the monitor, and back.
            to = from == 0 ? (Integer) desktop : from == (Integer) desktop ? 0 : -1;
            if (to < 0) {
                return;
            }
            Method screens = root.getClass().getMethod(SystemNames.GET_DISPLAY_CONTENT,
                    int.class);
            Object source = screens.invoke(root, from);
            Object target = screens.invoke(root, to);
            Object layer = Reflect.call(target, SystemNames.GET_OVERLAY_LAYER);
            fit = ScreenFit.cover(width(source), height(source), width(target), height(target));
            if (!(layer instanceof SurfaceControl) || fit == null
                    || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return;
            }
            copy = (SurfaceControl) sMirror.invoke(null, own);
            if (copy == null) {
                return;
            }
            try {
                new SurfaceControl.Transaction()
                        .reparent(copy, (SurfaceControl) layer)
                        .setLayer(copy, Integer.MAX_VALUE)
                        .setScale(copy, fit[0], fit[0])
                        .setPosition(copy, fit[1], fit[2])
                        .setVisibility(copy, true)
                        .apply();
            } catch (Throwable t) {
                copy.release();
                throw t;
            }
        }
        synchronized (DreamBothScreens.class) {
            drop();
            sCopy = copy;
            sCopyScreen = to;
            sCopySince = SystemClock.uptimeMillis();
            sDreamScreen = from;
            try {
                sInputHook = XposedBridge.hookMethod(sInput, INPUT);
            } catch (Throwable t) {
                // The copy stays; input on the screen saver's own screen still ends it.
                sInputHook = null;
            }
        }
        if (take()) {
            L.i("screen saver: a copy on screen " + to + " (it runs on screen " + from
                    + "), filling it at scale " + fit[0]);
        }
    }

    /** Takes the copy off its screen, if one is up. */
    private static void remove(String why) {
        int screen;
        synchronized (DreamBothScreens.class) {
            if (sCopy == null) {
                return;
            }
            screen = sCopyScreen;
            drop();
        }
        if (take()) {
            L.i("screen saver: its copy on screen " + screen + " removed - " + why);
        }
    }

    /** Holding the class: removes and releases the copy, and takes the input hook off. */
    private static void drop() {
        sDreamScreen = -1;
        XC_MethodHook.Unhook input = sInputHook;
        sInputHook = null;
        if (input != null) {
            try {
                input.unhook();
            } catch (Throwable ignored) {
                // Left on, it reads one value per input and returns.
            }
        }
        SurfaceControl copy = sCopy;
        sCopy = null;
        sCopyScreen = -1;
        if (copy == null) {
            return;
        }
        try {
            new SurfaceControl.Transaction().reparent(copy, null).apply();
        } finally {
            copy.release();
        }
    }

    private static int width(Object screen) {
        Object w = Reflect.field(Reflect.call(screen, SystemNames.GET_DISPLAY_INFO),
                SystemNames.LOGICAL_WIDTH);
        return w instanceof Integer ? (Integer) w : 0;
    }

    private static int height(Object screen) {
        Object h = Reflect.field(Reflect.call(screen, SystemNames.GET_DISPLAY_INFO),
                SystemNames.LOGICAL_HEIGHT);
        return h instanceof Integer ? (Integer) h : 0;
    }

    /** Whether a line is written: at most {@link #MAX_LINES} an hour. */
    private static synchronized boolean take() {
        long hour = SystemClock.elapsedRealtime() / HOUR_MS;
        if (hour != sHour) {
            sHour = hour;
            sLines = 0;
        }
        return sLines++ < MAX_LINES;
    }
}
