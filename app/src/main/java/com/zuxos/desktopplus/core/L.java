package com.zuxos.desktopplus.core;

import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logging that works both in the settings app and inside a hooked process.
 *
 * <p>Xposed classes are absent in our own process, so every call into them is guarded:
 * a module must never take down the launcher just because it wanted to log.
 */
public final class L {

    private static boolean sDebug;
    private static Boolean sXposedAvailable;
    /** The keys of the lines already said once ({@link #once}), from any thread. */
    private static final Set<String> ONCE = ConcurrentHashMap.newKeySet();

    private L() {
    }

    public static void setDebug(boolean debug) {
        sDebug = debug;
    }

    public static void d(String msg) {
        if (!sDebug) {
            return;
        }
        Log.d(Const.TAG, msg);
        xposed("[D] " + msg);
    }

    public static void i(String msg) {
        Log.i(Const.TAG, msg);
        xposed("[I] " + msg);
    }

    public static void w(String msg) {
        Log.w(Const.TAG, msg);
        xposed("[W] " + msg);
    }

    /**
     * True the first time a key is asked for in this process, false every time after: for a line
     * said once, {@code if (L.once("DrawerKeys.windows")) L.i(...)}. The line itself is only built
     * the first time. Keys are the class's name, then what is said.
     */
    public static boolean once(String key) {
        return ONCE.add(key);
    }

    public static void e(String msg, Throwable t) {
        Log.e(Const.TAG, msg, t);
        xposed("[E] " + msg + ": " + Log.getStackTraceString(t));
    }

    private static void xposed(String msg) {
        if (sXposedAvailable == null) {
            try {
                Class.forName("de.robv.android.xposed.XposedBridge");
                sXposedAvailable = Boolean.TRUE;
            } catch (Throwable t) {
                sXposedAvailable = Boolean.FALSE;
            }
        }
        if (!sXposedAvailable) {
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.log(Const.TAG + " " + msg);
        } catch (Throwable ignored) {
            sXposedAvailable = Boolean.FALSE;
        }
    }
}
