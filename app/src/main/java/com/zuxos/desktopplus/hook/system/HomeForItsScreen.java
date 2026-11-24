package com.zuxos.desktopplus.hook.system;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: the tablet's Home opens the tablet's launcher, never the system's chooser.
 * While ZUI's monitor desktop is on, ZUI's package manager answers every query for a home with the
 * monitor's launcher when ZUI's "focus" is the monitor ({@code ComputerEngine
 * .queryIntentActivitiesInternal}, asking {@code OVDesktopController
 * .getCurrentFocusDislayIdWhenUnderExtendDisplayInternal}: the top focused screen) - also when the
 * home is being started for the tablet. Home pressed on the tablet while the monitor had the focus
 * then resolved to the chooser.
 *
 * <p>So while the system starts a home for a screen ({@code RootWindowContainer
 * .startHomeOnTaskDisplayArea}, given that screen's display area), ZUI's focus check answers with
 * that screen, on that thread only. ZUI's rule then still gives the monitor its launcher, and
 * leaves the tablet's alone. ZUI's other reader of the check (its keyboard shortcuts) is
 * untouched. The switch is read at boot and when the settings change ({@link SystemBridge}).
 */
public final class HomeForItsScreen {

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    /** The screen a home is being started for on this thread; null outside a start. */
    private static final ThreadLocal<Integer> SCREEN = new ThreadLocal<>();

    private HomeForItsScreen() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> root = Class.forName(SystemNames.ROOT_WINDOW_CONTAINER, false, loader);
            Class<?> desktop = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER, false, loader);
            Class<?> door = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER_INTERNAL, false,
                    loader);
            // Both: the door is one call, which may be compiled into its callers.
            int checks = XposedBridge.hookAllMethods(desktop,
                    SystemNames.GET_CURRENT_FOCUS_DISLAY_ID_WHEN_UNDER_EXTEND_DISPLAY_INTERNAL,
                    FOCUS).size()
                    + XposedBridge.hookAllMethods(door,
                    SystemNames.GET_CURRENT_FOCUS_DISLAY_ID_WHEN_UNDER_EXTEND_DISPLAY, FOCUS)
                    .size();
            if (checks == 0) {
                L.w("home for its screen: ZUI's focus check not found - the tablet's Home may "
                        + "still give a chooser");
                return;
            }
            int starts = XposedBridge.hookAllMethods(root,
                    SystemNames.START_HOME_ON_TASK_DISPLAY_AREA, START).size();
            reread();
            L.i("home for its screen: a screen's home is resolved for that screen x"
                    + (checks + starts) + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("home for its screen: not installed, the tablet's Home may still give a "
                    + "chooser (" + t + ")");
        }
    }

    /** The switch, read off the home's path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.noHomeChooser();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    /** A home started for a screen: that screen, held on this thread while it starts. */
    private static final XC_MethodHook START = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object area = param.args.length > 2 ? param.args[2] : null;
                Object screen = sOn && area != null
                        ? Reflect.call(area, SystemNames.GET_DISPLAY_ID) : null;
                if (screen instanceof Integer) {
                    SCREEN.set((Integer) screen);
                } else {
                    SCREEN.remove();
                }
            } catch (Throwable t) {
                SCREEN.remove();
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            SCREEN.remove();
        }
    };

    /** ZUI's focus, asked while a home starts: the screen the home is for. */
    private static final XC_MethodHook FOCUS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Integer screen = SCREEN.get();
                Object focus = param.getResult();
                if (screen == null || !sOn || screen.equals(focus)) {
                    return;
                }
                param.setResult(screen);
                if (L.once("HomeForItsScreen." + screen)) {
                    L.i("home for its screen: screen " + screen + "'s home resolved for it, "
                            + "with ZUI's focus on screen " + focus);
                }
            } catch (Throwable t) {
                if (L.once("HomeForItsScreen.focus")) {
                    L.w("home for its screen: ZUI's focus left as it was (" + t + ")");
                }
            }
        }
    };
}
