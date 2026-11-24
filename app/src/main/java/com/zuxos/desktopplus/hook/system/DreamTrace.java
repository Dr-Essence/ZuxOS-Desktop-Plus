package com.zuxos.desktopplus.hook.system;

import android.os.SystemClock;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system, read only: why a screen saver does or does not start. The power manager
 * starts one when the screen times out ({@code PowerManagerService.handleSandman}), and ZUI puts a
 * gate there: with its standby mode on ({@code mZuiStandbyMode} and its switch,
 * {@code ZuiStandbyModeManager.getSwitchForStandby()}), only a screen saver asked for as allowed
 * ({@code isDreamingAllowed}, which ZUI's own standby sets) starts, and the user's own never does.
 * Whether that gate is what stops the owner's is not in any log, the system's own lines keeping
 * less than a minute. So each time the power manager looks, what it read and whether a screen
 * saver runs is written, when that differs from the last time, a few lines an hour at most.
 * Nothing is changed.
 *
 * <p>A screen going off without a screen saver is a dream too: SystemUI's always-on display
 * ({@code DozeService}). So the line says which it is, from the group's state (2 a screen saver,
 * 3 dozing), and the power manager's own "when to start" fields: a screen saver only when "while
 * charging" is set, or "while docked" with a dock Android knows ({@code mDockState}, which a USB-C
 * hub never sets), and only on power.
 */
public final class DreamTrace {

    private static final int MAX_LINES = 12;
    private static final long HOUR_MS = 3_600_000L;

    private static boolean sInstalled;
    private static Method sStandbySwitch;
    private static String sLast;
    private static long sHour = -1;
    private static int sLines;

    private DreamTrace() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> power = Class.forName(SystemNames.POWER_MANAGER_SERVICE, false, loader);
            try {
                sStandbySwitch = Class.forName(SystemNames.ZUI_STANDBY_MODE_MANAGER, false, loader)
                        .getMethod(SystemNames.GET_SWITCH_FOR_STANDBY);
            } catch (Throwable t) {
                sStandbySwitch = null;
            }
            int hooked = XposedBridge.hookAllMethods(power, SystemNames.HANDLE_SANDMAN, LOOKED)
                    .size();
            L.i("screen savers: the power manager's start written down x" + hooked
                    + (sStandbySwitch == null ? ", ZUI's standby switch not found" : ""));
        } catch (Throwable t) {
            L.w("screen savers: not written down (" + t + ")");
        }
    }

    private static final XC_MethodHook LOOKED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object dreams = Reflect.field(param.thisObject, SystemNames.M_DREAM_MANAGER);
                Object dreaming = dreams == null ? null
                        : Reflect.call(dreams, SystemNames.IS_DREAMING);
                Object standby = Reflect.field(param.thisObject, SystemNames.M_ZUI_STANDBY_MODE);
                Object allowed = Reflect.field(param.thisObject, SystemNames.IS_DREAMING_ALLOWED);
                Object standbySwitch = sStandbySwitch == null ? null
                        : sStandbySwitch.invoke(null);
                Object state = param.args.length == 0 ? null : Reflect.call(param.thisObject,
                        SystemNames.GET_WAKEFULNESS_LOCKED, param.args[0]);
                Object onSleep = Reflect.field(param.thisObject,
                        SystemNames.M_DREAMS_ACTIVATE_ON_SLEEP_SETTING);
                Object onDock = Reflect.field(param.thisObject,
                        SystemNames.M_DREAMS_ACTIVATE_ON_DOCK_SETTING);
                Object dock = Reflect.field(param.thisObject, SystemNames.M_DOCK_STATE);
                Object powered = Reflect.field(param.thisObject, SystemNames.M_IS_POWERED);
                boolean docked = dock instanceof Integer && (Integer) dock != 0;
                boolean ruledOut = !Boolean.TRUE.equals(onSleep)
                        && !(Boolean.TRUE.equals(onDock) && docked);
                String line = "dreaming " + dreaming + " (" + kind(state) + "); a screen saver "
                        + "starts while charging " + onSleep + ", while docked " + onDock
                        + " (docked " + docked + "), on power " + powered
                        + "; ZUI's standby mode " + standby + ", its switch " + standbySwitch
                        + ", a screen saver allowed " + allowed
                        + (Boolean.TRUE.equals(standby) && Boolean.TRUE.equals(standbySwitch)
                        && !Boolean.TRUE.equals(dreaming)
                        ? " - only ZUI's own standby screen saver can start" : "")
                        + (ruledOut ? " - Android's \"when to start\" rules a screen saver out"
                        : !Boolean.TRUE.equals(powered) ? " - none on battery" : "");
                if (!take(line)) {
                    return;
                }
                L.i("screen savers: the power manager looked (group " + (param.args.length > 0
                        ? param.args[0] : "?") + "): " + line);
            } catch (Throwable ignored) {
                // A line less; the power manager went on untouched.
            }
        }
    };

    /** The power group's state in words. */
    private static String kind(Object state) {
        if (!(state instanceof Integer)) {
            return "state " + state;
        }
        switch ((Integer) state) {
            case 0:
                return "asleep";
            case 1:
                return "awake";
            case 2:
                return "a screen saver";
            case 3:
                return "dozing: the always-on display, not a screen saver";
            default:
                return "state " + state;
        }
    }

    /** Whether this line is written: a change, within the hour's lines. */
    private static synchronized boolean take(String line) {
        long hour = SystemClock.elapsedRealtime() / HOUR_MS;
        if (hour != sHour) {
            sHour = hour;
            sLines = 0;
        }
        if (line.equals(sLast) || sLines >= MAX_LINES) {
            return false;
        }
        sLast = line;
        sLines++;
        return true;
    }
}
