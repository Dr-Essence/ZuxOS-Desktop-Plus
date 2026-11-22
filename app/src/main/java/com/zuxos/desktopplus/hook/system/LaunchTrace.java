package com.zuxos.desktopplus.hook.system;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system, read only: where each launch went while ZUI's monitor desktop is on.
 *
 * <p>With that desktop on, an app tapped on the tablet can open nowhere, while the same app
 * opened as a floating window opens. ZUI picks a launch's screen by the screen the system has
 * focused - it removes the app's task from the other screen ({@code resolveReusableTask}) and can
 * abort a start - and says so only in logcat, which keeps a few seconds. So each launch while the
 * desktop is on is written to the module's log, which keeps the whole boot: the app, who
 * launched it, the screen of the window it came from, the screen in focus, ZUI's choice, where it
 * landed and the result. Nothing is changed, and the lines stop after {@link #MAX_LINES}.
 *
 * <p>Each line also has the window's size as it opened, and each size ZUI keeps for an app's
 * next window on the monitor is written down as it is kept ({@code LaunchParamsPersister
 * .saveTask}): the owner's windows came back at ZUI's default size after a force stop, and
 * neither log says whether ZUI kept the size or did not hand it back.
 */
public final class LaunchTrace {

    private static final int MAX_LINES = 60;
    private static final int MAX_SAVES = 40;
    private static final int MAX_ERRORS = 20;
    /** How much of the background-start verdict is kept: its code and reason come first. */
    private static final int VERDICT_CHARS = 90;

    private static boolean sInstalled;
    private static Method sDesktopOn;
    private static int sLines;
    private static int sSaves;
    /** The last size written down for each app's window, so an unchanged one is not again. */
    private static final Map<String, String> SAVED = new HashMap<>();
    private static int sErrors;

    private LaunchTrace() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            sDesktopOn = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER, false, loader)
                    .getMethod(SystemNames.IS_UNDER_EXTERNAL_DISPLAY_DESKTOP_MODE);
            Class<?> starter = Class.forName(SystemNames.ACTIVITY_STARTER, false, loader);
            int hooked = 0;
            for (Method m : starter.getDeclaredMethods()) {
                if (m.getName().equals(SystemNames.START_ACTIVITY_INNER)
                        && m.getReturnType() == int.class) {
                    XposedBridge.hookMethod(m, AFTER);
                    hooked++;
                }
            }
            Class<?> persister = Class.forName(SystemNames.LAUNCH_PARAMS_PERSISTER, false, loader);
            for (Method m : persister.getDeclaredMethods()) {
                if (m.getName().equals(SystemNames.SAVE_TASK) && m.getParameterCount() == 2) {
                    XposedBridge.hookMethod(m, SAVED_SIZE);
                    hooked++;
                }
            }
            L.i("launch trace: launches written down while the monitor's desktop is on x"
                    + hooked);
        } catch (Throwable t) {
            L.w("launch trace: not installed (" + t + ")");
        }
    }

    private static final XC_MethodHook AFTER = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (sLines >= MAX_LINES || sErrors > MAX_ERRORS) {
                return;
            }
            try {
                if (!Boolean.TRUE.equals(sDesktopOn.invoke(null))) {
                    return;
                }
                Object result = param.getResult();
                String line = describe(param.thisObject, param.args, result);
                if (++sLines == MAX_LINES) {
                    line += " (the last line of this boot)";
                }
                L.i(line);
            } catch (Throwable t) {
                if (++sErrors == MAX_ERRORS) {
                    L.w("launch trace: stopped for this boot (" + t + ")");
                }
            }
        }
    };

    /** A size ZUI keeps for an app's next window on the monitor, when it changed. */
    private static final XC_MethodHook SAVED_SIZE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (sSaves >= MAX_SAVES || sErrors > MAX_ERRORS) {
                return;
            }
            try {
                Object task = param.args[0];
                Object display = Reflect.call(param.args[1], SystemNames.GET_DISPLAY_ID);
                if (!Boolean.TRUE.equals(sDesktopOn.invoke(null)) || !(display instanceof Integer)
                        || (Integer) display == 0) {
                    return;
                }
                String app = text(Reflect.field(task, SystemNames.REAL_ACTIVITY));
                String kept = "mode " + text(Reflect.call(task, SystemNames.GET_WINDOWING_MODE))
                        + ", size " + text(Reflect.field(task,
                                SystemNames.M_LAST_NON_FULLSCREEN_BOUNDS));
                if (kept.equals(SAVED.put(app, kept))) {
                    return;
                }
                L.i("launch trace: ZUI keeps " + app + "'s window on display " + display + ": "
                        + kept + (++sSaves == MAX_SAVES ? " (the last line of this boot)" : ""));
            } catch (Throwable t) {
                if (++sErrors == MAX_ERRORS) {
                    L.w("launch trace: stopped for this boot (" + t + ")");
                }
            }
        }
    };

    private static String describe(Object starter, Object[] args, Object result) {
        Object record = args.length > 0 ? args[0] : null;
        Object source = args.length > 1 ? args[1] : null;
        Object task = Reflect.call(record, SystemNames.GET_TASK);
        Object focused = Reflect.call(Reflect.field(starter, SystemNames.M_ROOT_WINDOW_CONTAINER),
                SystemNames.GET_TOP_FOCUSED_DISPLAY_CONTENT);
        return "launch trace: " + text(Reflect.field(record, SystemNames.LAUNCHED_FROM_PACKAGE))
                + " started " + text(Reflect.field(record, SystemNames.SHORT_COMPONENT_NAME))
                + " - from display " + displayOf(source)
                + ", focus on " + displayOf(focused)
                + ", ZUI chose " + displayOf(Reflect.field(starter,
                        SystemNames.M_PREFERRED_TASK_DISPLAY_AREA))
                + ", landed on " + displayOf(record)
                + " (task " + text(Reflect.field(task, SystemNames.M_TASK_ID))
                + ", mode " + text(Reflect.call(task, SystemNames.GET_WINDOWING_MODE))
                + ", size " + text(Reflect.call(task, SystemNames.GET_BOUNDS))
                + "), result " + outcome(result) + verdict(args);
    }

    /** A window container's screen; "-" for none (a launch from no window), "?" if unread. */
    private static String displayOf(Object container) {
        if (container == null) {
            return "-";
        }
        return text(Reflect.call(container, SystemNames.GET_DISPLAY_ID));
    }

    /** The start's result as Android names it ({@code ActivityManager.START_*}). */
    private static String outcome(Object result) {
        if (!(result instanceof Integer)) {
            return "?";
        }
        int code = (Integer) result;
        switch (code) {
            case 0:
                return "0 (started)";
            case 2:
                return "2 (its task to the front)";
            case 3:
                return "3 (delivered to the top)";
            case 102:
                return "102 (aborted)";
            default:
                return code < 0 ? code + " (failed)" : String.valueOf(code);
        }
    }

    /** The background-start verdict the start was given, when the build passes one. */
    private static String verdict(Object[] args) {
        for (Object a : args) {
            if (a != null && a.getClass().getName().endsWith("BalVerdict")) {
                String v = String.valueOf(a);
                return ", " + (v.length() > VERDICT_CHARS ? v.substring(0, VERDICT_CHARS) : v);
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "?" : String.valueOf(value);
    }
}
