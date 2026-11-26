package com.zuxos.desktopplus.hook.system;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.logic.MonitorDesktop;

import java.lang.reflect.Method;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: ZUI's monitor desktop on the monitor's screen when that screen comes back
 * quickly.
 *
 * <p>ZUI starts its monitor desktop ({@code OVDesktopController.enterExternalDisplayDesktopMode})
 * when its setting {@code zui_dp_display_pc_mode} turns on, for the external screens the window
 * manager has at that moment. The setting is watched on ZUI's handler, on the display thread; the
 * window manager takes a new screen on a thread of its own. When the monitor's screen goes and
 * comes back within a second, the setting can turn on before the window manager has the new
 * screen: ZUI's start finds none, its desktop screen stays -1 and the window manager's
 * {@code mForceDesktopModeOnExternalDisplays} stays off. The new screen is then made without
 * floating windows and without decorations, so Android never starts a home there: a black monitor,
 * Home doing nothing, every app full screen, SystemUI's title bars on ZUI's ordinary path, and the
 * mouse and the keyboard of the monitor's apps on the tablet, for as long as the monitor stays
 * plugged in.
 *
 * <p>So after ZUI's own step for an added screen
 * ({@code prepareEnterExternalDisplayDesktopModeInternal}, inside the window manager's
 * {@code onDisplayAdded}), a check goes on ZUI's handler, behind anything ZUI's own observer has
 * queued there. It takes the window manager's lock, so the screen is in the window manager by
 * then, and where ZUI's desktop missed it ({@link MonitorDesktop#shouldStart}) it runs ZUI's own
 * start once more, as when the setting turns on after a screen is there: the flag, floating
 * windows, the keyboard's rule, the wallpaper, and ZUI's home a second later. Otherwise it only
 * writes down each external screen added and removed, with ZUI's state, at most
 * {@link #MAX_LINES} lines an hour.
 */
public final class MonitorDesktopBack {

    private static final int MAX_LINES = 12;
    private static final long HOUR_MS = 3_600_000L;

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    private static Method sEnter;
    private static Method sDesktopOn;
    private static Method sDesktopDisplay;
    private static Method sMirrorDisplay;
    private static Method sWorkMode;
    private static long sHour = -1;
    private static int sLines;

    private MonitorDesktopBack() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        reread();
        try {
            Class<?> desktop = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER, false, loader);
            sEnter = desktop.getMethod(SystemNames.ENTER_EXTERNAL_DISPLAY_DESKTOP_MODE);
            sDesktopOn = desktop.getMethod(SystemNames.IS_UNDER_EXTERNAL_DISPLAY_DESKTOP_MODE);
            sDesktopDisplay = desktop.getMethod(
                    SystemNames.GET_EXTERNAL_DISPLAY_DESKTOP_DISPLAY_ID);
            sMirrorDisplay = desktop.getMethod(SystemNames.GET_MIRROR_DISPLAY_DESKTOP_DISPLAY_ID);
            sWorkMode = desktop.getMethod(SystemNames.IS_UNDER_PC_DESKTOP_MODE);
            int hooked = XposedBridge.hookAllMethods(desktop,
                    SystemNames.PREPARE_ENTER_EXTERNAL_DISPLAY_DESKTOP_MODE_INTERNAL, ADDED).size();
            Class<?> internal = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER_INTERNAL, false,
                    loader);
            hooked += XposedBridge.hookAllMethods(internal,
                    SystemNames.ON_DISPLAY_REMOVED_FOR_ZUI_DESKTOP, REMOVED).size();
            L.i("monitor desktop: ZUI's desktop started again on a screen it missed x" + hooked
                    + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            sEnter = null;
            L.w("monitor desktop: left to ZUI as the monitor's screen comes (" + t + ")");
        }
    }

    /** The switch, read off the system's path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.monitorDesktopBack();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    /** {@code prepareEnterExternalDisplayDesktopModeInternal(int)}: an external screen added. */
    private static final XC_MethodHook ADDED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!(param.args.length > 0 && param.args[0] instanceof Integer)) {
                    return;
                }
                int screen = (Integer) param.args[0];
                if (take()) {
                    L.i("monitor desktop: screen " + screen + " added - " + state(null));
                }
                Object controller = param.thisObject;
                Object handler = Reflect.field(controller, SystemNames.M_HANDLER);
                if (!sOn || sEnter == null || !(handler instanceof Handler)) {
                    return;
                }
                // Behind whatever ZUI's own observer has queued: its own start comes first.
                ((Handler) handler).post(() -> check(controller, screen));
            } catch (Throwable t) {
                if (L.once("MonitorDesktopBack.added")) {
                    L.w("monitor desktop: an added screen left to ZUI (" + t + ")");
                }
            }
        }
    };

    /** {@code onDisplayRemovedForZuiDesktop(int, DisplayContent)}: read only. */
    private static final XC_MethodHook REMOVED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length < 2 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int type = typeOf(param.args[1]);
                if (type != MonitorDesktop.TYPE_EXTERNAL && type != MonitorDesktop.TYPE_OVERLAY) {
                    return;
                }
                if (take()) {
                    L.i("monitor desktop: screen " + param.args[0] + " removed - "
                            + state(null));
                }
            } catch (Throwable ignored) {
                // A line less; ZUI's own step went through untouched.
            }
        }
    };

    /**
     * On ZUI's handler, once the window manager has the screen: ZUI's own start, where its
     * desktop missed the screen.
     */
    private static void check(Object controller, int screen) {
        try {
            if (!sOn) {
                return;
            }
            Object wm = Reflect.field(controller, SystemNames.M_WINDOW_MANAGER);
            Object lock = Reflect.field(wm, SystemNames.M_GLOBAL_LOCK);
            Object root = Reflect.field(controller, SystemNames.M_ROOT_WINDOW_CONTAINER);
            Object context = Reflect.field(controller, SystemNames.M_CONTEXT);
            if (wm == null || lock == null || root == null || !(context instanceof Context)) {
                return;
            }
            Method screens = root.getClass().getMethod(SystemNames.GET_DISPLAY_CONTENT,
                    int.class);
            int type;
            boolean there;
            int desktop;
            boolean desktopThere;
            boolean flag;
            // The window manager adds the screen under this lock: once it is ours, it is there.
            synchronized (lock) {
                Object added = screens.invoke(root, screen);
                there = added != null;
                type = there ? typeOf(added) : -1;
                desktop = (Integer) sDesktopDisplay.invoke(null);
                desktopThere = desktop > 0 && screens.invoke(root, desktop) != null;
                flag = Boolean.TRUE.equals(Reflect.field(wm,
                        SystemNames.M_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS));
            }
            int setting = Settings.System.getInt(((Context) context).getContentResolver(),
                    SystemNames.ZUI_DP_DISPLAY_PC_MODE, 0);
            boolean work = Boolean.TRUE.equals(sWorkMode.invoke(null));
            if (!MonitorDesktop.shouldStart(setting, type, screen, there, work, desktop,
                    desktopThere, flag)) {
                return;
            }
            String before = state(wm) + ", " + keyboard(wm, screen);
            // ZUI's own start, on its own thread and outside the lock, as its observer calls it.
            sEnter.invoke(controller);
            L.i("monitor desktop: screen " + screen + " came without ZUI's desktop (" + before
                    + ") - ZUI's own start run again, now " + state(wm) + ", "
                    + keyboard(wm, screen));
        } catch (Throwable t) {
            if (L.once("MonitorDesktopBack.check")) {
                L.w("monitor desktop: screen " + screen + " left to ZUI (" + t + ")");
            }
        }
    }

    /** ZUI's monitor desktop as it stands, for the log; the window manager's flag with it. */
    private static String state(Object wm) {
        StringBuilder b = new StringBuilder("ZUI's desktop ");
        try {
            b.append(Boolean.TRUE.equals(sDesktopOn.invoke(null)) ? "on" : "off")
                    .append(", its screen ").append(sDesktopDisplay.invoke(null))
                    .append(", its mirror ").append(sMirrorDisplay.invoke(null));
            if (Boolean.TRUE.equals(sWorkMode.invoke(null))) {
                b.append(", work mode on");
            }
            if (wm != null) {
                b.append(", the window manager's flag ").append(Reflect.field(wm,
                        SystemNames.M_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS));
            }
        } catch (Throwable t) {
            b.append("unread (").append(t).append(')');
        }
        return b.toString();
    }

    /**
     * Where Android shows the keyboard of an app on the screen: the window manager's cached rule,
     * which is all the keyboard service asks. On a monitor it reads "its own screen" only while
     * the window manager's flag is on. The map is replaced whole, never changed in place, so it
     * is read without the lock, as the keyboard service reads it.
     */
    private static String keyboard(Object wm, int screen) {
        Object rules = Reflect.field(wm, SystemNames.M_DISPLAY_IME_POLICY_CACHE);
        if (!(rules instanceof Map)) {
            return "its keyboard unread";
        }
        // Android's DISPLAY_IME_POLICY_LOCAL and _HIDE; anything else, or no rule for the
        // screen, is its fallback, the tablet.
        Object rule = ((Map<?, ?>) rules).get(screen);
        if (Integer.valueOf(0).equals(rule)) {
            return "its keyboard on its own screen";
        }
        if (Integer.valueOf(2).equals(rule)) {
            return "its keyboard hidden";
        }
        return "its keyboard on the tablet";
    }

    /** A screen's type as the window manager has it ({@code DisplayInfo.type}); -1 if unread. */
    private static int typeOf(Object screen) {
        Object type = Reflect.field(Reflect.field(screen, SystemNames.M_DISPLAY_INFO),
                SystemNames.TYPE);
        return type instanceof Integer ? (Integer) type : -1;
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
