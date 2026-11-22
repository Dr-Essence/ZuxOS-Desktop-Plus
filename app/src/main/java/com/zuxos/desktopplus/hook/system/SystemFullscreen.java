package com.zuxos.desktopplus.hook.system;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.FullScreenWire;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: a launch the module marks for full screen gets full screen.
 *
 * <p>ZUI's desktop opens every new task on the monitor as a floating window, and Android
 * remembers each app's last window and hands it back - after, and over, anything the launch
 * itself asked for. ZUI's photo editor will not run in a window: it closed itself the moment it
 * opened, "not support split screen" (its log: "getlauncherparams=...FilterShowActivity 5").
 *
 * <p>So for a launch carrying the mark, and only for one, the window the system worked out is
 * replaced by full screen, and nothing turns the task back into a window afterwards. Every other
 * launch, from anywhere, is left exactly as it was.
 *
 * <p>The game launcher chosen in the settings gets the mark on the monitor, for itself and for
 * everything it opens: games came up as small windows, and the launcher too. Once such a launch
 * has its task, ZUX Home is told to make it our Full screen (taskbar and title bar out of the
 * way), as its menus do.
 */
public final class SystemFullscreen {

    /** The mark on a launch that must be full screen. */
    public static final String EXTRA = "com.zuxos.desktopplus.FULLSCREEN";
    /** The mark on a launch of, or from, the game launcher: our Full screen once it is up. */
    private static final String EXTRA_GAME = "com.zuxos.desktopplus.GAME_LAUNCHER";
    private static final int START_SUCCESS = 0;
    private static final int START_TASK_TO_FRONT = 2;
    private static final int START_DELIVERED_TO_TOP = 3;

    private static final int FULLSCREEN = 1;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean sInstalled;
    private static Class<?> sActivityRecord;
    private static Class<?> sTask;
    private static Field sRecordIntent;
    private static Field sTaskIntent;
    private static Field sMode;
    private static Field sBounds;
    private static int sErrors;

    private SystemFullscreen() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        int hooked = 0;
        try {
            sActivityRecord = Class.forName(SystemNames.ACTIVITY_RECORD, false,
                    loader);
            sRecordIntent = field(sActivityRecord, SystemNames.INTENT);
            Class<?> params = Class.forName(
                    SystemNames.LAUNCH_PARAMS, false, loader);
            sMode = field(params, SystemNames.M_WINDOWING_MODE);
            sBounds = field(params, SystemNames.M_BOUNDS);
            Class<?> controller = Class.forName(
                    SystemNames.LAUNCH_PARAMS_CONTROLLER, false, loader);
            for (Method m : controller.getDeclaredMethods()) {
                if (m.getName().equals(SystemNames.CALCULATE)) {
                    XposedBridge.hookMethod(m, CALCULATE);
                    hooked++;
                }
            }
        } catch (Throwable t) {
            L.i("system fullscreen: launch params not reachable (" + t + ")");
        }
        try {
            sTask = Class.forName(SystemNames.TASK, false, loader);
            sTaskIntent = field(sTask, SystemNames.INTENT);
            // Declared on the task or on one of the classes it extends, depending on the build.
            for (Class<?> c = sTask; c != null && c != Object.class; c = c.getSuperclass()) {
                Method m = setter(c);
                if (m != null) {
                    XposedBridge.hookMethod(m, SET_MODE);
                    hooked++;
                    break;
                }
            }
        } catch (Throwable t) {
            L.i("system fullscreen: task mode not reachable (" + t + ")");
        }
        try {
            Class<?> starter = Class.forName(SystemNames.ACTIVITY_STARTER, false, loader);
            for (Method m : starter.getDeclaredMethods()) {
                if (m.getName().equals(SystemNames.START_ACTIVITY_INNER)
                        && m.getReturnType() == int.class) {
                    XposedBridge.hookMethod(m, STARTED);
                    hooked++;
                }
            }
        } catch (Throwable t) {
            L.i("system fullscreen: starts not reachable, the game launcher keeps the taskbar ("
                    + t + ")");
        }
        L.i("system fullscreen: watching marked launches x" + hooked);
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // Further up.
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    private static Method setter(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals(SystemNames.SET_WINDOWING_MODE) && p.length == 1
                    && p[0] == int.class) {
                return m;
            }
        }
        return null;
    }

    /** The launch's own window, as worked out, swapped for full screen when it is marked. */
    private static final XC_MethodHook CALCULATE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (sErrors > 20) {
                return;
            }
            try {
                Object record = null;
                Object result = null;
                for (Object a : param.args) {
                    if (record == null && sActivityRecord.isInstance(a)) {
                        // The first record is the one being launched; the second, its source.
                        record = a;
                    } else if (a != null && a.getClass() == sMode.getDeclaringClass()) {
                        result = a;
                    }
                }
                if (record == null || result == null) {
                    return;
                }
                markGame(record, result);
                Object intent = sRecordIntent.get(record);
                if (!marked(intent) && !(intent instanceof Intent && gameMarked((Intent) intent))) {
                    return;
                }
                if (sMode.getInt(result) != FULLSCREEN) {
                    L.i("system fullscreen: full screen instead of mode " + sMode.getInt(result)
                            + " for " + ((Intent) sRecordIntent.get(record)).getComponent());
                }
                sMode.setInt(result, FULLSCREEN);
                Object bounds = sBounds.get(result);
                if (bounds instanceof Rect) {
                    ((Rect) bounds).setEmpty();
                }
            } catch (Throwable t) {
                sErrors++;
            }
        }
    };

    /**
     * A launch on the monitor of the game launcher, or from it, marked as the game launcher's: it
     * opens full screen. The mark is on the launch's own intent, so its task keeps it; unlike
     * {@link #EXTRA} it does not hold the window there, so it can still be made a window later.
     * A home screen opened from the launcher is left alone.
     */
    private static void markGame(Object record, Object result) throws IllegalAccessException {
        String launcher = Cfg.gameLauncher();
        if (launcher.isEmpty() || !Cfg.gameLauncherFullScreen()) {
            return;
        }
        Object area = Reflect.field(result, SystemNames.M_PREFERRED_TASK_DISPLAY_AREA);
        Object display = Reflect.call(area, SystemNames.GET_DISPLAY_ID);
        Object intent = sRecordIntent.get(record);
        if (!(display instanceof Integer) || (Integer) display == Display.DEFAULT_DISPLAY
                || !(intent instanceof Intent)
                || Boolean.TRUE.equals(Reflect.call(record, SystemNames.IS_ACTIVITY_TYPE_HOME))
                || !(launcher.equals(Reflect.field(record, SystemNames.PACKAGE_NAME))
                        || launcher.equals(Reflect.field(record,
                                SystemNames.LAUNCHED_FROM_PACKAGE)))) {
            return;
        }
        if (!gameMarked((Intent) intent)) {
            ((Intent) intent).putExtra(EXTRA_GAME, true);
            L.i("system fullscreen: " + ((Intent) intent).getComponent()
                    + " is the game launcher's - full screen on display " + display);
        }
    }

    /**
     * After the start: the game launcher's window, now with a task, made our Full screen by ZUX
     * Home. Told from the main thread, never from under the window manager's lock.
     */
    private static final XC_MethodHook STARTED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (sErrors > 20) {
                return;
            }
            try {
                Object result = param.getResult();
                Object intent = param.args.length > 0 && sActivityRecord.isInstance(param.args[0])
                        ? sRecordIntent.get(param.args[0]) : null;
                if (!(result instanceof Integer) || !(intent instanceof Intent)
                        || !gameMarked((Intent) intent)) {
                    return;
                }
                int code = (Integer) result;
                if (code != START_SUCCESS && code != START_TASK_TO_FRONT
                        && code != START_DELIVERED_TO_TOP) {
                    return;
                }
                Object task = Reflect.call(param.args[0], SystemNames.GET_TASK);
                if (task == null) {
                    // Brought to the front: the task the start went to.
                    task = Reflect.field(param.thisObject, SystemNames.M_TARGET_TASK);
                }
                Object id = Reflect.field(task, SystemNames.M_TASK_ID);
                Object display = Reflect.call(task, SystemNames.GET_DISPLAY_ID);
                if (!(id instanceof Integer) || !(display instanceof Integer)
                        || (Integer) display == Display.DEFAULT_DISPLAY) {
                    return;
                }
                Intent enter = new Intent(FullScreenWire.ACTION_ENTER)
                        .putExtra(FullScreenWire.EXTRA_TASK, (Integer) id)
                        .putExtra(FullScreenWire.EXTRA_DISPLAY, (Integer) display);
                MAIN.post(() -> {
                    try {
                        SystemBridge.toZuxHome(enter);
                    } catch (Throwable t) {
                        L.w("system fullscreen: ZUX Home not told of task " + id + " (" + t
                                + ")");
                    }
                });
            } catch (Throwable t) {
                sErrors++;
            }
        }
    };

    /** And no turning a marked task back into a window as it settles on the monitor. */
    private static final XC_MethodHook SET_MODE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (sErrors > 20 || !sTask.isInstance(param.thisObject)) {
                return;
            }
            Object mode = param.args[0];
            // Undefined is no escape either: it would take the monitor's own mode, a window.
            if (!(mode instanceof Integer) || (Integer) mode == FULLSCREEN) {
                return;
            }
            try {
                if (marked(sTaskIntent.get(param.thisObject))) {
                    param.args[0] = FULLSCREEN;
                }
            } catch (Throwable t) {
                sErrors++;
            }
        }
    };

    private static boolean gameMarked(Intent intent) {
        try {
            return intent.hasExtra(EXTRA_GAME);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean marked(Object intent) {
        try {
            return intent instanceof Intent && ((Intent) intent).hasExtra(EXTRA);
        } catch (Throwable t) {
            // Extras that will not unparcel here are not ours.
            return false;
        }
    }
}
