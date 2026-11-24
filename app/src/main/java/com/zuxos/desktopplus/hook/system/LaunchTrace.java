package com.zuxos.desktopplus.hook.system;

import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.SystemClock;

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
 * landed and the result. Nothing is changed, and there are at most {@link #MAX_LINES} lines an
 * hour, so a whole day stays in the log, not only its first hour.
 *
 * <p>Each line also has the window's size as it opened, and each size ZUI keeps for an app's
 * next window on the monitor is written down as it is kept ({@code LaunchParamsPersister
 * .saveTask}), as is what ZUI hands back at a launch ({@code getLaunchParams}): the owner's
 * windows came back at ZUI's default size after a force stop, and neither log says whether ZUI
 * kept the size, looked it up under another name (an app opened through an alias), or would not
 * hand it back (ZUI resets it when the screen it was kept on is not the one in focus).
 *
 * <p>And from boot, monitor or not, what the system's home resolves to each time that changes:
 * the screen, the reason, and the app - Android's chooser where no home app is the default. The
 * owner is asked to choose a launcher on the tablet at every boot and now and then after, and no
 * log said when or why.
 */
public final class LaunchTrace {

    private static final int MAX_LINES = 60;
    private static final int MAX_SAVES = 40;
    private static final int MAX_ERRORS = 20;
    private static final int MAX_HOMES = 30;
    private static final long HOUR_MS = 3_600_000L;
    /** How much of the background-start verdict is kept: its code and reason come first. */
    private static final int VERDICT_CHARS = 90;

    private static boolean sInstalled;
    private static Method sDesktopOn;
    private static int sLines;
    private static int sSaves;
    /** The last size written down for each app's window, so an unchanged one is not again. */
    private static final Map<String, String> SAVED = new HashMap<>();
    private static int sErrors;
    /** The screen and the reason of the home being started on this thread, for its line. */
    private static final ThreadLocal<String> HOME_FOR = new ThreadLocal<>();
    /** What the home resolved to last, so an unchanged one is not written again. */
    private static String sHome;
    private static int sHomes;
    /** The hour the counts above are for. */
    private static long sHour = -1;

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
                } else if (m.getName().equals(SystemNames.GET_LAUNCH_PARAMS)
                        && m.getParameterCount() == 3) {
                    XposedBridge.hookMethod(m, HANDED_BACK);
                    hooked++;
                }
            }
            L.i("launch trace: launches written down while the monitor's desktop is on x"
                    + hooked);
        } catch (Throwable t) {
            L.w("launch trace: not installed (" + t + ")");
        }
        installHome(loader);
    }

    private static void installHome(ClassLoader loader) {
        try {
            Class<?> root = Class.forName(SystemNames.ROOT_WINDOW_CONTAINER, false, loader);
            int hooked = 0;
            for (Method m : root.getDeclaredMethods()) {
                if (m.getName().equals(SystemNames.START_HOME_ON_TASK_DISPLAY_AREA)) {
                    XposedBridge.hookMethod(m, HOME_STARTED);
                    hooked++;
                } else if (m.getName().equals(SystemNames.RESOLVE_HOME_ACTIVITY)
                        && m.getReturnType() == ActivityInfo.class) {
                    XposedBridge.hookMethod(m, HOME_RESOLVED);
                    hooked++;
                }
            }
            L.i("launch trace: what the home resolves to written down x" + hooked);
        } catch (Throwable t) {
            L.w("launch trace: the home not written down (" + t + ")");
        }
    }

    /** A home being started on a screen: which, and why, for the line its resolving writes. */
    private static final XC_MethodHook HOME_STARTED = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] args = param.args;
                HOME_FOR.set("display " + (args.length > 2 ? displayOf(args[2]) : "?")
                        + ", " + (args.length > 1 ? text(args[1]) : "?"));
            } catch (Throwable t) {
                HOME_FOR.remove();
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            HOME_FOR.remove();
        }
    };

    /** What the home resolved to, when it is not what it was. */
    private static final XC_MethodHook HOME_RESOLVED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            newHour();
            if (sHomes >= MAX_HOMES || sErrors > MAX_ERRORS) {
                return;
            }
            try {
                Object result = param.getResult();
                String to = result instanceof ActivityInfo
                        ? ((ActivityInfo) result).packageName + "/" + ((ActivityInfo) result).name
                        : "nothing";
                synchronized (HOME_FOR) {
                    if (to.equals(sHome)) {
                        return;
                    }
                    sHome = to;
                }
                String why = HOME_FOR.get();
                L.i("launch trace: the home" + (why != null ? " (" + why + ")" : "")
                        + " resolves to " + to
                        + (++sHomes == MAX_HOMES ? " (the last line this hour)" : ""));
            } catch (Throwable t) {
                if (++sErrors == MAX_ERRORS) {
                    L.w("launch trace: stopped for this boot (" + t + ")");
                }
            }
        }
    };

    private static final XC_MethodHook AFTER = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            newHour();
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
                    line += " (the last line this hour)";
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
            newHour();
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
                        + kept + (++sSaves == MAX_SAVES ? " (the last line this hour)" : ""));
            } catch (Throwable t) {
                if (++sErrors == MAX_ERRORS) {
                    L.w("launch trace: stopped for this boot (" + t + ")");
                }
            }
        }
    };

    /** What ZUI hands back for a launch on the monitor, from its memory of the app's window. */
    private static final XC_MethodHook HANDED_BACK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            newHour();
            if (sSaves >= MAX_SAVES || sErrors > MAX_ERRORS) {
                return;
            }
            try {
                if (!Boolean.TRUE.equals(sDesktopOn.invoke(null))) {
                    return;
                }
                Object task = param.args[0];
                // ZUI's own key: the task's activity, or for a new task the launch's component.
                String app = text(task != null
                        ? Reflect.field(task, SystemNames.REAL_ACTIVITY)
                        : Reflect.field(param.args[1], SystemNames.M_ACTIVITY_COMPONENT));
                Object mode = Reflect.field(param.args[2], SystemNames.M_WINDOWING_MODE);
                Object bounds = Reflect.field(param.args[2], SystemNames.M_BOUNDS);
                String back = Integer.valueOf(0).equals(mode)
                        && (!(bounds instanceof Rect) || ((Rect) bounds).isEmpty())
                        ? "nothing" : "mode " + text(mode) + ", size " + text(bounds);
                if (back.equals(SAVED.put("back:" + app, back))) {
                    return;
                }
                L.i("launch trace: ZUI hands " + app + " back " + back
                        + (++sSaves == MAX_SAVES ? " (the last line this hour)" : ""));
            } catch (Throwable t) {
                if (++sErrors == MAX_ERRORS) {
                    L.w("launch trace: stopped for this boot (" + t + ")");
                }
            }
        }
    };

    /** The counts start again each hour, so a long day stays in the log. */
    private static synchronized void newHour() {
        long hour = SystemClock.elapsedRealtime() / HOUR_MS;
        if (hour != sHour) {
            sHour = hour;
            sLines = 0;
            sSaves = 0;
            sHomes = 0;
        }
    }

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
