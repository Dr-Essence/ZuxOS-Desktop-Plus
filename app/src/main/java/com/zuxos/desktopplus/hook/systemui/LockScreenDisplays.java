package com.zuxos.desktopplus.hook.systemui;

import android.util.SparseArray;
import android.view.Display;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Read only: what ZUI's SystemUI puts on the monitor while the tablet is locked, before a way to
 * unlock from the monitor is built on it. ZUI chooses each other screen's lock screen in
 * {@code KeyguardDisplayManager.showPresentation}: Motorola's Smart Connect one, with a password,
 * PIN or pattern entry ({@code MotoKeyguardPresentation}, unlocking through
 * {@code RDPUnlockController}), where its feature {@code feature_zui_smart_connect_keyguard} is
 * on and the screen is one of Motorola's desktops
 * ({@code MotoDesktopManager.isDesktopOrMobileUiMode}); else Android's clock alone ({@code ConnectedDisplayKeyguardPresentation}). Each screen's line
 * says what those checks gave and what ZUI put there, when it differs from the last.
 */
public final class LockScreenDisplays {

    /** At most this many lines a run: a lock and an unlock each choose again. */
    private static final int MAX_LINES = 20;

    /** Each screen's last line, so only a change is written. */
    private static final Map<Integer, String> LAST = new HashMap<>();
    private static int sLines;

    private LockScreenDisplays() {
    }

    public static void trace(ClassLoader loader) {
        try {
            Class<?> manager = Reflect.findClass(SysUiNames.KEYGUARD_DISPLAY_MANAGER, loader);
            int hooked = manager == null ? 0 : XposedBridge.hookAllMethods(manager,
                    SysUiNames.SHOW_PRESENTATION, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            shown(loader, param);
                        }
                    }).size();
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
                    + (unlock != null ? "" : "; no Smart Connect unlock in this SystemUI"));
        } catch (Throwable t) {
            L.d("lock screen: SystemUI's lock screens not watched (" + t + ")");
        }
    }

    private static void shown(ClassLoader loader, XC_MethodHook.MethodHookParam param) {
        try {
            if (sLines >= MAX_LINES || param.args.length == 0
                    || !(param.args[0] instanceof Display)) {
                return;
            }
            Display display = (Display) param.args[0];
            int id = display.getDisplayId();
            Object presentations = Reflect.field(param.thisObject, SysUiNames.M_PRESENTATIONS);
            Object there = presentations instanceof SparseArray
                    ? ((SparseArray<?>) presentations).get(id) : null;
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
