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
 * ZUI's desktop comes on, so the mouse stayed on the tablet. So when ZUI turns its monitor desktop
 * on or off, the input system is handed its own screens again
 * ({@code InputManagerService.setDisplayViewportsInternal}, with the ones it has), the call a
 * change of screen makes, and it asks ZUI's rule again. It runs on the display manager's thread,
 * where those changes are handed over, so it never races one and never runs under the window
 * manager's lock.
 *
 * <p>The rule skips a screen that is off, so each time the screens slept the mouse went to the
 * tablet and back. ZUI's own pointer across screens keeps, for each mouse, the screen it is on,
 * and after such a flip it kept sending the mouse to the tablet, where there was no pointer any
 * more: the input system dropped every move and click ("No mouse pointer controller found for
 * display 0"), with everything else as it should be, until a replug made the mouse a new device.
 * So while ZUI's monitor desktop is on and its screen is there, the rule's tablet becomes that
 * screen, asleep or not: the mouse never leaves the monitor while it sleeps, and moving it still
 * wakes the tablet, the monitor being one of its screens.
 *
 * <p>The focus moved it too. Android hands the focused screen to the input system as both the
 * focus and the mouse's screen ({@code InputManagerService.setFocusedDisplay(i)} calls the native
 * {@code setFocusedDisplay(i, i)}), so the mouse followed every change of focus - at each lock the
 * tablet's lock screen took the focus before the monitor's, and so did a dialog on the tablet -
 * with the same drop after. So while ZUI's monitor desktop is on, the focus moves and the mouse
 * stays on the screen ZUI last reported it on ({@code InputManagerServiceDelegate
 * .notifyPointerDisplayIdChange}, the native code's own report, which follows the mouse across an
 * edge), through that same native call.
 *
 * <p>At the first lock after a boot ZUI's monitor desktop is not up yet - ZUX Home waits for the
 * first unlock - so the rule gave the tablet, and the monitor's lock screen could not be clicked.
 * So while the lock screen is up before ZUI's monitor desktop, with the monitor set to be that
 * desktop ({@code zui_dp_display_pc_mode} = 1, which ZUI turns off at boot until that desktop is
 * up - so also before the first unlock since boot), the rule's tablet becomes that screen, asleep
 * or not. The rule's answer is written as it changes, with what it read. The switch is read at
 * boot and when the settings change ({@link SystemBridge}).
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
    /** Whether the user has unlocked since boot: {@code LocalServices}' user manager. */
    private static Method sLocalService;
    private static Class<?> sUsersClass;
    private static Method sUnlocked;
    /** The native code's own last report of the mouse's screen; -1 before the first. */
    private static volatile int sPointerOn = -1;
    /** The native {@code setFocusedDisplay(focus, mouse's screen)}. */
    private static Method sNativeFocus;
    private static final Hourly FOCUS_LINES = new Hourly(RULE_LINES_AN_HOUR);
    private static final Hourly REPORT_LINES = new Hourly(RULE_LINES_AN_HOUR);
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
            try {
                sUsersClass = Class.forName(SystemNames.USER_MANAGER_INTERNAL, false, loader);
                sUnlocked = sUsersClass.getMethod(SystemNames.IS_USER_UNLOCKED, int.class);
                sLocalService = Class.forName(SystemNames.LOCAL_SERVICES, false, loader)
                        .getMethod(SystemNames.GET_SERVICE, Class.class);
            } catch (Throwable t) {
                sUnlocked = null;
                L.w("mouse screen: the first unlock since boot not readable - the mouse at the "
                        + "first lock goes by ZUI's setting alone (" + t + ")");
            }
            int hooked = XposedBridge.hookAllMethods(desktop,
                    SystemNames.ENTER_EXTERNAL_DISPLAY_DESKTOP_MODE, CHANGED).size()
                    + XposedBridge.hookAllMethods(desktop,
                    SystemNames.EXIT_EXTERNAL_DISPLAY_DESKTOP_MODE, CHANGED).size();
            if (hooked == 0) {
                L.w("mouse screen: ZUI's monitor desktop not found - the mouse's screen is "
                        + "ZUI's alone");
                return;
            }
            reread();
            L.i("mouse screen: asked again as ZUI's monitor desktop turns on and off x" + hooked
                    + ", and kept on the monitor while it sleeps" + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("mouse screen: not installed, the mouse's screen is ZUI's alone (" + t + ")");
        }
        watchRule(loader);
        watchFocus(loader);
    }

    /** The mouse kept on its screen as the focus changes screen; ZUI's reports of where it is. */
    private static void watchFocus(ClassLoader loader) {
        try {
            Class<?> input = Class.forName(SystemNames.INPUT_MANAGER_SERVICE, false, loader);
            sNativeFocus = Class.forName(SystemNames.NATIVE_INPUT_MANAGER_SERVICE, false, loader)
                    .getMethod(SystemNames.SET_FOCUSED_DISPLAY, int.class, int.class);
            Class<?> delegate = Class.forName(SystemNames.INPUT_MANAGER_SERVICE_DELEGATE, false,
                    loader);
            int reports = XposedBridge.hookAllMethods(delegate,
                    SystemNames.NOTIFY_POINTER_DISPLAY_ID_CHANGE, REPORT).size();
            if (reports == 0) {
                L.w("mouse screen: ZUI's reports of the mouse's screen not found - the mouse "
                        + "follows the focus, as ZUI has it");
                return;
            }
            XposedBridge.hookMethod(input.getMethod(SystemNames.SET_FOCUSED_DISPLAY, int.class),
                    FOCUS);
            L.i("mouse screen: kept on its screen as the focus changes screen, by ZUI's reports"
                    + " x" + reports);
        } catch (Throwable t) {
            sNativeFocus = null;
            L.w("mouse screen: the mouse follows the focus, as ZUI has it (" + t + ")");
        }
    }

    /** The native code reporting the mouse's screen: kept, and written as it changes. */
    private static final XC_MethodHook REPORT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int screen = (Integer) param.args[0];
                if (screen != sPointerOn) {
                    sPointerOn = screen;
                    if (REPORT_LINES.take()) {
                        L.i("mouse screen: ZUI has the mouse on screen " + screen);
                    }
                }
            } catch (Throwable ignored) {
                // A report less; the next one is kept.
            }
        }
    };

    /**
     * The focus handed to the input system: while ZUI's monitor desktop is on, the mouse's screen
     * handed with it is the one the mouse is on, not the focus's.
     */
    private static final XC_MethodHook FOCUS = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!sOn || sNativeFocus == null || param.args.length == 0
                        || !(param.args[0] instanceof Integer) || sDesktopOn == null
                        || !Boolean.TRUE.equals(sDesktopOn.invoke(null))) {
                    return;
                }
                int focus = (Integer) param.args[0];
                Object desktop = sDesktopDisplay.invoke(null);
                if (!(desktop instanceof Integer) || (Integer) desktop <= 0) {
                    return;
                }
                // Only the tablet or ZUI's desktop screen: a report of a screen since unplugged
                // would put the mouse nowhere.
                int pointer = sPointerOn == 0 ? 0 : (Integer) desktop;
                if (pointer == focus) {
                    return;
                }
                Object nativeInput = Reflect.field(param.thisObject, SystemNames.M_NATIVE);
                if (nativeInput == null) {
                    return;
                }
                sNativeFocus.invoke(nativeInput, focus, pointer);
                param.setResult(null);
                if (FOCUS_LINES.take()) {
                    L.i("mouse screen: focus to screen " + focus + ", the mouse kept on screen "
                            + pointer);
                }
            } catch (Throwable t) {
                // The original call runs: the mouse follows the focus, as ZUI has it.
                if (L.once("MouseScreen.focus")) {
                    L.w("mouse screen: the mouse follows the focus this time (" + t + ")");
                }
            }
        }
    };

    /** At most so many lines an hour. */
    private static final class Hourly {
        private final int mMax;
        private long mHour = -1;
        private int mLines;

        Hourly(int max) {
            mMax = max;
        }

        synchronized boolean take() {
            long hour = SystemClock.elapsedRealtime() / HOUR_MS;
            if (hour != mHour) {
                mHour = hour;
                mLines = 0;
            }
            return mLines++ < mMax;
        }
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
                    Integer kept = asleep(param.thisObject);
                    if (kept != null) {
                        param.setResult(kept);
                        result = kept;
                        if (L.once("MouseScreen.asleep")) {
                            L.i("mouse screen: the monitor asleep, the mouse kept on it (ZUI's rule"
                                    + " gave the tablet)");
                        }
                    }
                    Integer early = kept != null ? null : beforeDesktop(param.thisObject);
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
     * ZUI's monitor desktop's screen while that desktop is on and the screen is there, whether it
     * is on or off; null otherwise.
     */
    private static Integer asleep(Object callback) throws Exception {
        if (sDesktopOn == null || !Boolean.TRUE.equals(sDesktopOn.invoke(null))) {
            return null;
        }
        Object id = sDesktopDisplay.invoke(null);
        if (!(id instanceof Integer) || (Integer) id <= 0) {
            return null;
        }
        Object root = Reflect.field(Reflect.field(callback, SystemNames.M_SERVICE),
                SystemNames.M_ROOT);
        Object screen = root == null ? null : root.getClass()
                .getMethod(SystemNames.GET_DISPLAY_CONTENT, int.class).invoke(root, id);
        return screen == null ? null : (Integer) id;
    }

    /**
     * The monitor that is to be ZUI's desktop, while the lock screen is up before that desktop
     * is: an external screen, on or asleep, with {@code zui_dp_display_pc_mode} = 1 - or before
     * the first unlock since boot, which ZUI turns that setting off for until its desktop is up.
     * Null otherwise, and once ZUI's desktop is up.
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
        if (!(context instanceof Context) || (Settings.System.getInt(
                ((Context) context).getContentResolver(), SystemNames.ZUI_DP_DISPLAY_PC_MODE, 0)
                != 1 && !firstLock())) {
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
            if (info != null && sType.getInt(info) == EXTERNAL && sDisplayId.getInt(info) != 0) {
                return sDisplayId.getInt(info);
            }
        }
        return null;
    }

    /** Whether the owner has not unlocked since boot: the first lock, before ZUI's desktop. */
    private static boolean firstLock() throws Exception {
        if (sUnlocked == null) {
            return false;
        }
        Object users = sLocalService.invoke(null, sUsersClass);
        if (users == null || !Boolean.FALSE.equals(sUnlocked.invoke(users, 0))) {
            return false;
        }
        if (L.once("MouseScreen.firstLock")) {
            L.i("mouse screen: before the first unlock since boot - the monitor is taken as ZUI's"
                    + " desktop to be");
        }
        return true;
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
