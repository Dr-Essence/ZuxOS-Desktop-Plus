package com.zuxos.desktopplus.hook.systemui;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.Display;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The monitor's lock screen unlocks the tablet, with ZUI's own. ZUI chooses each other screen's
 * lock screen in {@code KeyguardDisplayManager.showPresentation}: Motorola's Smart Connect one -
 * its clock, and a click away its password, PIN or pattern entry, checked by Android's own
 * {@code LockPatternChecker} and unlocking through {@code RDPUnlockController}
 * ({@code MotoKeyguardPresentation}) - where its feature {@code feature_zui_smart_connect_keyguard}
 * is on and the screen is one of Motorola's desktops
 * ({@code MotoDesktopManager.isDesktopOrMobileUiMode}); else Android's clock alone
 * ({@code ConnectedDisplayKeyguardPresentation}). ZUI's monitor desktop lacks Motorola's display
 * flags, so the monitor only ever had the clock, and unlocking meant reaching for the tablet.
 *
 * <p>So while ZUI chooses the lock screen of the screen that is its monitor desktop (an external
 * screen, with {@code zui_dp_display_pc_mode} on), and on that thread only, Motorola's check
 * answers yes. ZUI's lock screen, password check and unlock are then its own, untouched. Every
 * method of that lock screen runs in a try/catch: the first failure takes it down, is written
 * once, and the monitor gets Android's clock from ZUI for the rest of SystemUI's run; where ZUI
 * puts no lock screen there after all, it is asked again as before. The switch is read each time
 * ZUI chooses. Each screen's line says what ZUI's checks gave and what it put there, when it
 * differs from the last.
 */
public final class LockScreenDisplays {

    /** At most this many lines a run: a lock and an unlock each choose again. */
    private static final int MAX_LINES = 20;
    /** {@code Display.TYPE_EXTERNAL}: the monitor, whatever number it gets as it comes. */
    private static final int EXTERNAL = 2;

    /** Each screen's last line, so only a change is written. */
    private static final Map<Integer, String> LAST = new HashMap<>();
    private static int sLines;
    /** The screen ZUI is choosing a lock screen for as its monitor desktop, on this thread. */
    private static final ThreadLocal<Display> CHOOSING = new ThreadLocal<>();
    /** Set while ZUI chooses again, as before, after it put no lock screen on the monitor. */
    private static final ThreadLocal<Boolean> AGAIN = new ThreadLocal<>();
    /** ZUI's lock screen failed on the monitor: Android's clock there for the rest of the run. */
    private static volatile boolean sBroken;
    /** ZUI's chooser, to choose again after a failure. */
    private static volatile Object sManager;

    private LockScreenDisplays() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> manager = Reflect.findClass(SysUiNames.KEYGUARD_DISPLAY_MANAGER, loader);
            int hooked = manager == null ? 0 : XposedBridge.hookAllMethods(manager,
                    SysUiNames.SHOW_PRESENTATION, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            choosing(param);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            chosen(loader, param);
                        }
                    }).size();
            String unlocks = hooked == 0 ? "ZUI's chooser not found" : unlockOnMonitor(loader);
            Class<?> unlock = Reflect.findClass(SysUiNames.RDP_UNLOCK_CONTROLLER, loader);
            if (unlock != null) {
                hooked += XposedBridge.hookAllConstructors(unlock, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (L.once("LockScreenDisplays.unlock")) {
                            L.i("lock screen: ZUI's Smart Connect unlock is running");
                        }
                    }
                }).size();
            }
            L.i("lock screen: what ZUI puts on the other screens, written down x" + hooked
                    + (unlock != null ? "" : "; no Smart Connect unlock in this SystemUI")
                    + "; unlock on the monitor: " + unlocks);
        } catch (Throwable t) {
            L.w("lock screen: SystemUI's lock screens left to ZUI (" + t + ")");
        }
    }

    /** Motorola's check, answered for the monitor, and ZUI's lock screen held in a try/catch. */
    private static String unlockOnMonitor(ClassLoader loader) {
        Class<?> moto = Reflect.findClass(SysUiNames.MOTO_DESKTOP_MANAGER, loader);
        Class<?> lock = Reflect.findClass(SysUiNames.MOTO_KEYGUARD_PRESENTATION, loader);
        if (moto == null || lock == null) {
            return "ZUI's Smart Connect lock screen not found";
        }
        try {
            // Held first, so ZUI's lock screen never runs on the monitor uncontained.
            int held = XposedBridge.hookAllConstructors(lock, HELD).size();
            for (Method m : lock.getDeclaredMethods()) {
                int modifiers = m.getModifiers();
                if (!Modifier.isStatic(modifiers) && !Modifier.isAbstract(modifiers)) {
                    XposedBridge.hookMethod(m, HELD);
                    held++;
                }
            }
            XposedBridge.hookMethod(moto.getMethod(SysUiNames.IS_DESKTOP_OR_MOBILE_UI_MODE,
                    Display.class), MONITOR);
            return "ZUI's own, its " + held + " parts held" + (on() ? "" : ", switched off");
        } catch (Throwable t) {
            sBroken = true;
            return "left to ZUI (" + t + ")";
        }
    }

    private static boolean on() {
        return Cfg.enabled() && Cfg.unlockOnMonitor();
    }

    /** ZUI starts choosing a screen's lock screen: the monitor's desktop is held on the thread. */
    private static void choosing(XC_MethodHook.MethodHookParam param) {
        CHOOSING.remove();
        try {
            sManager = param.thisObject;
            if (sBroken || AGAIN.get() != null || param.args.length == 0
                    || !(param.args[0] instanceof Display) || !on()) {
                return;
            }
            Display display = (Display) param.args[0];
            if (display.getDisplayId() == Display.DEFAULT_DISPLAY
                    || !Integer.valueOf(EXTERNAL).equals(Reflect.call(display, SysUiNames.GET_TYPE))
                    || !(Reflect.field(param.thisObject, SysUiNames.M_CONTEXT)
                            instanceof Context)) {
                return;
            }
            Context context = (Context) Reflect.field(param.thisObject, SysUiNames.M_CONTEXT);
            if (Settings.System.getInt(context.getContentResolver(),
                    SysUiNames.DP_DISPLAY_PC_MODE, 0) == 1) {
                CHOOSING.set(display);
            }
        } catch (Throwable t) {
            if (L.once("LockScreenDisplays.choosing")) {
                L.w("lock screen: the monitor's lock screen left to ZUI (" + t + ")");
            }
        }
    }

    /** Motorola's "is this one of our desktops", while ZUI chooses the monitor's lock screen. */
    private static final XC_MethodHook MONITOR = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Display display = CHOOSING.get();
                if (display == null || !(param.args[0] instanceof Display)
                        || ((Display) param.args[0]).getDisplayId() != display.getDisplayId()
                        || Boolean.TRUE.equals(param.getResult())) {
                    return;
                }
                param.setResult(true);
                if (L.once("LockScreenDisplays.monitor." + display.getDisplayId())) {
                    L.i("lock screen: screen " + display.getDisplayId() + " is ZUI's monitor "
                            + "desktop - ZUI's Smart Connect lock screen chosen for it");
                }
            } catch (Throwable t) {
                if (L.once("LockScreenDisplays.monitor")) {
                    L.w("lock screen: Motorola's check left as it was (" + t + ")");
                }
            }
        }
    };

    /** ZUI chose: written down, and asked again as before where it put nothing on the monitor. */
    private static void chosen(ClassLoader loader, XC_MethodHook.MethodHookParam param) {
        Display monitor = CHOOSING.get();
        CHOOSING.remove();
        write(loader, param);
        if (monitor == null || Boolean.TRUE.equals(param.getResult())
                || there(param.thisObject, monitor.getDisplayId()) != null) {
            return;
        }
        AGAIN.set(true);
        try {
            Object again = Reflect.call(param.thisObject, SysUiNames.SHOW_PRESENTATION, monitor);
            if (L.once("LockScreenDisplays.again")) {
                L.i("lock screen: ZUI put no lock screen on screen " + monitor.getDisplayId()
                        + " as its monitor desktop - asked again as before (" + again + ")");
            }
        } finally {
            AGAIN.remove();
        }
    }

    /** The lock screen ZUI has on a screen; null for none. */
    private static Object there(Object manager, int id) {
        Object presentations = Reflect.field(manager, SysUiNames.M_PRESENTATIONS);
        return presentations instanceof SparseArray ? ((SparseArray<?>) presentations).get(id)
                : null;
    }

    /**
     * Every part of ZUI's Smart Connect lock screen: a failure is caught where it happens, the
     * lock screen goes, and ZUI puts Android's clock on the monitor instead.
     */
    private static final XC_MethodHook HELD = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Throwable t = param.getThrowable();
            if (t == null) {
                return;
            }
            try {
                param.setResult(nothing(param.method));
                broke(param.thisObject, param.method.getName(), t);
            } catch (Throwable ignored) {
                // The failure goes on to ZUI as it was.
            }
        }
    };

    /** What a failed part returns instead: nothing, false or zero. */
    private static Object nothing(Member member) {
        if (member instanceof Constructor || !(member instanceof Method)) {
            return null;
        }
        Class<?> type = ((Method) member).getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return null;
    }

    /** ZUI's lock screen failed: down it goes, and ZUI puts Android's clock up, from now on. */
    private static void broke(Object presentation, String where, Throwable t) {
        boolean first = !sBroken;
        sBroken = true;
        if (L.once("LockScreenDisplays.broke")) {
            L.w("lock screen: ZUI's Smart Connect lock screen failed in " + where + " - the "
                    + "monitor has Android's clock until SystemUI restarts (" + t + ")");
        }
        if (!first || !(presentation instanceof Dialog) || "dismiss".equals(where)
                || "onDetachedFromWindow".equals(where)) {
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                ((Dialog) presentation).dismiss();
            } catch (Throwable ignored) {
                // Held: its own dismiss is a part.
            }
            // After its dismiss is handed on, ZUI's own pass over the screens, as the lock is.
            main.post(() -> {
                try {
                    Object manager = sManager;
                    if (manager != null) {
                        Reflect.call(manager, SysUiNames.UPDATE_DISPLAYS,
                                Reflect.field(manager, SysUiNames.M_SHOWING));
                    }
                } catch (Throwable ignored) {
                    // The monitor waits for ZUI's next pass.
                }
            });
        });
    }

    private static void write(ClassLoader loader, XC_MethodHook.MethodHookParam param) {
        try {
            if (sLines >= MAX_LINES || param.args.length == 0
                    || !(param.args[0] instanceof Display)) {
                return;
            }
            Display display = (Display) param.args[0];
            int id = display.getDisplayId();
            Object there = there(param.thisObject, id);
            String line = "type " + Reflect.call(display, SysUiNames.GET_TYPE) + ", flags 0x"
                    + Integer.toHexString(display.getFlags()) + "; Smart Connect's feature "
                    + feature(loader) + ", Motorola's desktop " + moto(loader, display)
                    + "; ZUI shows "
                    + (there != null ? there.getClass().getSimpleName() : "nothing")
                    + " (" + param.getResult() + ")";
            if (line.equals(LAST.put(id, line))) {
                return;
            }
            sLines++;
            L.i("lock screen: screen " + id + " - " + line
                    + (sLines == MAX_LINES ? " (the last line of this run)" : ""));
        } catch (Throwable t) {
            if (L.once("LockScreenDisplays.shown")) {
                L.d("lock screen: a screen's lock screen not read (" + t + ")");
            }
        }
    }

    /** ZUI's switch for Smart Connect's lock screen, as its own check reads it. */
    private static String feature(ClassLoader loader) {
        try {
            Class<?> features = Class.forName(SysUiNames.SYSTEMUI_FEATURES, false, loader);
            Object all = features.getMethod(SysUiNames.GET_INSTANCE).invoke(null);
            return String.valueOf(features.getMethod(SysUiNames.IS_ENABLED, String.class)
                    .invoke(all, SysUiNames.SMART_CONNECT_KEYGUARD));
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Whether the tablet and this screen count as Motorola's desktop, as ZUI's check asks. */
    private static String moto(ClassLoader loader, Display display) {
        try {
            Class<?> moto = Class.forName(SysUiNames.MOTO_DESKTOP_MANAGER, false, loader);
            return moto.getMethod(SysUiNames.IS_DESKTOP_SUPPORTED).invoke(null) + "/"
                    + moto.getMethod(SysUiNames.IS_DESKTOP_OR_MOBILE_UI_MODE, Display.class)
                    .invoke(null, display);
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
