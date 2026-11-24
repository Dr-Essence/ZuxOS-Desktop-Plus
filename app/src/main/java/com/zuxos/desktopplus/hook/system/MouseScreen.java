package com.zuxos.desktopplus.hook.system;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: the mouse stays on the monitor's desktop, by ZUI's own rule. ZUI puts the
 * mouse on the monitor while the window manager's {@code mForceDesktopModeOnExternalDisplays} is
 * set and the monitor is not off ({@code InputManagerCallback.getPointerDisplayId}), and sets the
 * flag when its monitor desktop comes on
 * ({@code OVDesktopController.enterExternalDisplayDesktopMode}) - but never tells the input
 * system, which asks that rule only when the screens change. At boot the screens settle before
 * ZUI's desktop comes on, so the mouse stayed on the tablet. And when the screens wake, the input
 * system has the monitor back before the window manager has taken its new state
 * ({@code DisplayContent.onDisplayInfoUpdated}), so the rule still read it as off and gave the
 * tablet: the mouse was gone from the monitor until it was replugged.
 *
 * <p>So when ZUI turns its monitor desktop on or off, and when the window manager takes a screen's
 * going off or coming back while that desktop is on, the input system is handed its own screens
 * again ({@code InputManagerService.setDisplayViewportsInternal}, with the ones it has), the call
 * a change of screen makes, and it asks ZUI's rule again, which now reads what it lacked. It runs
 * on the display manager's thread, where those changes are handed over, so it never races one and
 * never runs under the window manager's lock. The rule's answer is written as it changes, with
 * what it read.
 *
 * <p>At the first lock after a boot ZUI's monitor desktop is not up yet - ZUX Home waits for the
 * first unlock - so the rule gave the tablet, and the monitor's lock screen could not be clicked.
 * So while the lock screen is up before ZUI's monitor desktop, with the monitor set to be that
 * desktop ({@code zui_dp_display_pc_mode} = 1) and on, the rule's tablet becomes that screen;
 * once ZUI's desktop is up, its rule decides alone. The switch is read at boot and when the
 * settings change ({@link SystemBridge}).
 */
public final class MouseScreen {

    /** {@code Display.STATE_OFF}: the one state ZUI's rule skips a screen for. */
    private static final int OFF = 1;
    /** {@code Display.TYPE_EXTERNAL}: the monitor, whatever number it gets as it comes. */
    private static final int EXTERNAL = 2;
    private static final long HOUR_MS = 3_600_000L;
    /** At most this many changes of the rule's answer written in an hour. */
    private static final int RULE_LINES_AN_HOUR = 12;

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    /** The display manager's thread's handler, the input system's screens, and its own setter. */
    private static Method sDisplayHandler;
    private static Field sScreens;
    private static Method sHand;
    /** ZUI's monitor desktop: whether it is on, and its screen. */
    private static Method sDesktopOn;
    private static Method sDesktopDisplay;
    /** A screen's state as the window manager has it: its info, and the info's state. */
    private static Field sScreenInfo;
    private static Field sState;
    private static Field sType;
    private static Field sDisplayId;
    /** The state a screen had as the window manager took a new one, on that thread. */
    private static final ThreadLocal<Integer> BEFORE = new ThreadLocal<>();
    /** The rule's answer last written, MIN_VALUE before the first; and this hour's lines. */
    private static volatile int sRuleDisplay = Integer.MIN_VALUE;
    private static long sRuleHour = -1;
    private static int sRuleLines;

    private MouseScreen() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> desktop = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER, false, loader);
            Class<?> input = Class.forName(SystemNames.INPUT_MANAGER_SERVICE, false, loader);
            sDisplayHandler = Class.forName(SystemNames.DISPLAY_THREAD, false, loader)
                    .getMethod(SystemNames.GET_HANDLER);
            sScreens = input.getDeclaredField(SystemNames.M_DISPLAY_VIEWPORT_LIST);
            sScreens.setAccessible(true);
            sHand = input.getDeclaredMethod(SystemNames.SET_DISPLAY_VIEWPORTS_INTERNAL,
                    List.class);
            sHand.setAccessible(true);
            sDesktopOn = desktop.getMethod(SystemNames.IS_UNDER_EXTERNAL_DISPLAY_DESKTOP_MODE);
            sDesktopDisplay = desktop.getMethod(
                    SystemNames.GET_EXTERNAL_DISPLAY_DESKTOP_DISPLAY_ID);
            Class<?> screen = Class.forName(SystemNames.DISPLAY_CONTENT, false, loader);
            sScreenInfo = screen.getDeclaredField(SystemNames.M_DISPLAY_INFO);
            sScreenInfo.setAccessible(true);
            Class<?> info = Class.forName(SystemNames.DISPLAY_INFO, false, loader);
            sState = info.getField(SystemNames.STATE);
            sType = info.getField(SystemNames.TYPE);
            sDisplayId = info.getField(SystemNames.DISPLAY_ID);
            int hooked = XposedBridge.hookAllMethods(desktop,
                    SystemNames.ENTER_EXTERNAL_DISPLAY_DESKTOP_MODE, CHANGED).size()
                    + XposedBridge.hookAllMethods(desktop,
                    SystemNames.EXIT_EXTERNAL_DISPLAY_DESKTOP_MODE, CHANGED).size();
            if (hooked == 0) {
                L.w("mouse screen: ZUI's monitor desktop not found - the mouse's screen is "
                        + "ZUI's alone");
                return;
            }
            int woke = XposedBridge.hookAllMethods(screen, SystemNames.ON_DISPLAY_INFO_UPDATED,
                    STATE).size();
            reread();
            L.i("mouse screen: asked again as ZUI's monitor desktop turns on and off x" + hooked
                    + ", and as a screen goes off or comes back x" + woke
                    + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("mouse screen: not installed, the mouse's screen is ZUI's alone (" + t + ")");
        }
        watchRule(loader);
    }

    /** The switch, read off ZUI's path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.mouseOnDesktop();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    private static final XC_MethodHook CHANGED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sOn) {
                    return;
                }
                boolean on = SystemNames.ENTER_EXTERNAL_DISPLAY_DESKTOP_MODE
                        .equals(param.method.getName());
                post("ZUI's monitor desktop " + (on ? "on" : "off"));
            } catch (Throwable t) {
                if (L.once("MouseScreen.post")) {
                    L.w("mouse screen: not asked again, the mouse stays where it was (" + t + ")");
                }
            }
        }
    };

    /**
     * A screen's new state reaching the window manager: when it went off or came back while ZUI's
     * monitor desktop is on, the rule that skips screens that are off is asked again.
     */
    private static final XC_MethodHook STATE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                BEFORE.set(sOn ? state(param.thisObject) : null);
            } catch (Throwable t) {
                BEFORE.remove();
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Integer before = BEFORE.get();
                BEFORE.remove();
                Integer after = state(param.thisObject);
                if (before == null || after == null || (before == OFF) == (after == OFF)
                        || !sOn || !Boolean.TRUE.equals(sDesktopOn.invoke(null))) {
                    return;
                }
                post("screen " + Reflect.call(param.thisObject, SystemNames.GET_DISPLAY_ID)
                        + (after == OFF ? " off" : " back"));
            } catch (Throwable t) {
                if (L.once("MouseScreen.state")) {
                    L.w("mouse screen: not asked again as a screen woke (" + t + ")");
                }
            }
        }
    };

    /** A screen's power state as the window manager has it; null when unknown. */
    private static Integer state(Object screen) throws IllegalAccessException {
        Object info = sScreenInfo.get(screen);
        return info == null ? null : sState.getInt(info);
    }

    private static void post(String why) throws Exception {
        Handler display = (Handler) sDisplayHandler.invoke(null);
        display.post(() -> askAgain(why));
    }

    /** The input system handed the screens it has, so it asks ZUI's rule for the mouse again. */
    private static void askAgain(String why) {
        try {
            // In the system the service is the input manager itself.
            Object input = Class.forName(SystemNames.SERVICE_MANAGER)
                    .getMethod(SystemNames.GET_SERVICE, String.class)
                    .invoke(null, SystemNames.INPUT_SERVICE);
            if (!sScreens.getDeclaringClass().isInstance(input)) {
                return;
            }
            Object list = sScreens.get(input);
            if (!(list instanceof List)) {
                return;
            }
            List<?> screens;
            synchronized (list) {
                screens = new ArrayList<>((List<?>) list);
            }
            if (screens.isEmpty()) {
                return;
            }
            sHand.invoke(input, screens);
            L.i("mouse screen: " + why + " - the mouse's screen asked again");
        } catch (Throwable t) {
            if (L.once("MouseScreen.ask")) {
                L.w("mouse screen: not asked again, the mouse stays where it was (" + t + ")");
            }
        }
    }

    /** ZUI's rule's answer written as it changes, with what it read. Read only. */
    private static void watchRule(ClassLoader loader) {
        try {
            Class<?> callback = Class.forName(SystemNames.INPUT_MANAGER_CALLBACK, false, loader);
            int hooked = XposedBridge.hookAllMethods(callback,
                    SystemNames.GET_POINTER_DISPLAY_ID, RULE).size();
            L.i("mouse screen: ZUI's rule written down as it changes x" + hooked);
        } catch (Throwable t) {
            L.w("mouse screen: ZUI's rule not written down (" + t + ")");
        }
    }

    private static final XC_MethodHook RULE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object result = param.getResult();
                if (Integer.valueOf(0).equals(result) && sOn) {
                    Integer early = beforeDesktop(param.thisObject);
                    if (early != null) {
                        param.setResult(early);
                        result = early;
                        if (L.once("MouseScreen.early")) {
                            L.i("mouse screen: the lock screen is up before ZUI's monitor desktop "
                                    + "- the mouse on screen " + early + ", that desktop to be");
                        }
                    }
                }
                if (!(result instanceof Integer) || (Integer) result == sRuleDisplay) {
                    return;
                }
                int display = (Integer) result;
                String last = ruleLine(display);
                if (last == null) {
                    return;
                }
                L.i("mouse screen: ZUI's rule gives screen " + display
                        + (display == 0 ? " (the tablet)" : "") + " - " + read(param.thisObject)
                        + last);
            } catch (Throwable ignored) {
                // A line less; the rule's answer went through untouched.
            }
        }
    };

    /**
     * The monitor that is to be ZUI's desktop, while the lock screen is up before that desktop
     * is: an external screen that is on, with {@code zui_dp_display_pc_mode} = 1. Null
     * otherwise, and once ZUI's desktop is up.
     */
    private static Integer beforeDesktop(Object callback) throws Exception {
        if (sDesktopOn == null || sType == null
                || Boolean.TRUE.equals(sDesktopOn.invoke(null))) {
            return null;
        }
        Object wm = Reflect.field(callback, SystemNames.M_SERVICE);
        Object policy = Reflect.field(wm, SystemNames.M_POLICY);
        if (!Boolean.TRUE.equals(Reflect.call(policy, SystemNames.IS_KEYGUARD_LOCKED))) {
            return null;
        }
        Object thread = Class.forName(SystemNames.ACTIVITY_THREAD)
                .getMethod(SystemNames.CURRENT_ACTIVITY_THREAD).invoke(null);
        Object context = Reflect.call(thread, SystemNames.GET_SYSTEM_CONTEXT);
        if (!(context instanceof Context) || Settings.System.getInt(
                ((Context) context).getContentResolver(), SystemNames.ZUI_DP_DISPLAY_PC_MODE, 0)
                != 1) {
            return null;
        }
        Object screens = Reflect.field(Reflect.field(wm, SystemNames.M_ROOT),
                SystemNames.M_CHILDREN);
        if (!(screens instanceof List)) {
            return null;
        }
        for (Object screen : new ArrayList<>((List<?>) screens)) {
            Object info = sScreenInfo.getDeclaringClass().isInstance(screen)
                    ? sScreenInfo.get(screen) : null;
            if (info != null && sType.getInt(info) == EXTERNAL && sState.getInt(info) != OFF
                    && sDisplayId.getInt(info) != 0) {
                return sDisplayId.getInt(info);
            }
        }
        return null;
    }

    /** Whether this change of answer is written: null when not, else the line's ending. */
    private static synchronized String ruleLine(int display) {
        long hour = SystemClock.elapsedRealtime() / HOUR_MS;
        if (hour != sRuleHour) {
            sRuleHour = hour;
            sRuleLines = 0;
        }
        if (display == sRuleDisplay || sRuleLines >= RULE_LINES_AN_HOUR) {
            return null;
        }
        sRuleDisplay = display;
        sRuleLines++;
        return sRuleLines == RULE_LINES_AN_HOUR ? " (the last line this hour)" : "";
    }

    /** What the rule read: the window manager's flag, and ZUI's desktop screen and its state. */
    private static String read(Object callback) {
        Object wm = Reflect.field(callback, SystemNames.M_SERVICE);
        StringBuilder b = new StringBuilder("the window manager's flag ")
                .append(Reflect.field(wm, SystemNames.M_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS));
        try {
            if (sDesktopOn == null || !Boolean.TRUE.equals(sDesktopOn.invoke(null))) {
                return b.append(", ZUI's monitor desktop off").toString();
            }
            Object id = sDesktopDisplay.invoke(null);
            b.append(", ZUI's monitor desktop on screen ").append(id);
            Object root = Reflect.field(wm, SystemNames.M_ROOT);
            Object screen = root == null ? null : root.getClass()
                    .getMethod(SystemNames.GET_DISPLAY_CONTENT, int.class).invoke(root, id);
            Integer state = screen == null ? null : state(screen);
            b.append(state == null ? ", not in the window manager"
                    : state == OFF ? ", off to the window manager" : ", state " + state);
        } catch (Throwable t) {
            b.append(", ZUI's desktop unread (").append(t).append(')');
        }
        return b.toString();
    }
}
