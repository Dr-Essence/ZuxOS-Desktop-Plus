package com.zuxos.desktopplus.hook.system;

import android.os.SystemClock;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system, read only: which window stopped taking input, and when it took it again.
 *
 * <p>The owner had both screens stop answering, twice in two minutes, and force-stopped the
 * launcher to get them back. The log showed no crash and no "not responding", and a probe cannot
 * be taken from a screen that does not answer. Android's input dispatcher does tell the window
 * manager when a window stops taking input and when it takes it again
 * ({@code InputManagerCallback.notifyWindowUnresponsive}, {@code notifyWindowResponsive}), and
 * when a key has nowhere to go because no window has the focus ({@code notifyNoFocusedWindowAnr}).
 * Each of those is written here, with the dispatcher's own reason, which names the window and
 * what it was waiting on, so the LSPosed log saved after the next freeze says which window held
 * input. Nothing is changed, and there are at most {@link #MAX_LINES} lines an hour.
 */
public final class InputStallTrace {

    private static final int MAX_LINES = 20;
    private static final long HOUR_MS = 3_600_000L;

    private static boolean sInstalled;
    private static long sHour = -1;
    private static int sLines;

    private InputStallTrace() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> callback = Class.forName(SystemNames.INPUT_MANAGER_CALLBACK, false, loader);
            int hooked = XposedBridge.hookAllMethods(callback,
                    SystemNames.NOTIFY_WINDOW_UNRESPONSIVE, STALLED).size();
            hooked += XposedBridge.hookAllMethods(callback,
                    SystemNames.NOTIFY_WINDOW_RESPONSIVE, BACK).size();
            hooked += XposedBridge.hookAllMethods(callback,
                    SystemNames.NOTIFY_NO_FOCUSED_WINDOW_ANR, NO_FOCUS).size();
            L.i("input stalls: a window that stops taking input written down x" + hooked);
        } catch (Throwable t) {
            L.w("input stalls: not written down (" + t + ")");
        }
    }

    /** {@code notifyWindowUnresponsive(IBinder token, OptionalInt pid, String reason)}. */
    private static final XC_MethodHook STALLED = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (take()) {
                    L.i("input stalls: a window stopped taking input - pid " + arg(param, 1)
                            + ": " + arg(param, 2));
                }
            } catch (Throwable ignored) {
                // A line less; the dispatcher's own report goes on untouched.
            }
        }
    };

    /** {@code notifyWindowResponsive(IBinder token, OptionalInt pid)}. */
    private static final XC_MethodHook BACK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (take()) {
                    L.i("input stalls: the window of pid " + arg(param, 1)
                            + " takes input again");
                }
            } catch (Throwable ignored) {
                // A line less; the dispatcher's own report goes on untouched.
            }
        }
    };

    /** {@code notifyNoFocusedWindowAnr(InputApplicationHandle app)}: a key with nowhere to go. */
    private static final XC_MethodHook NO_FOCUS = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object app = param.args.length > 0 ? param.args[0] : null;
                if (take()) {
                    L.i("input stalls: a key had no focused window to go to - the app in front "
                            + "was " + Reflect.field(app, SystemNames.NAME));
                }
            } catch (Throwable ignored) {
                // A line less; the dispatcher's own report goes on untouched.
            }
        }
    };

    private static Object arg(XC_MethodHook.MethodHookParam param, int i) {
        return param.args.length > i ? param.args[i] : "?";
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
