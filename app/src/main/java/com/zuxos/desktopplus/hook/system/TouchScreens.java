package com.zuxos.desktopplus.hook.system;

import com.zuxos.desktopplus.core.L;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system, read-only: which screens the input system is told it may put touches on.
 *
 * <p>Android ties a touchscreen to a screen through viewports: each display gets one by the kind
 * of touch its device reports - internal, external, or a virtual one by unique id
 * ({@code DisplayManagerService.getViewportType}) - and the list goes to the input system in one
 * call ({@code InputManagerService.setDisplayViewportsInternal}). An external touchscreen is put
 * on the external viewport; without a valid one it falls back to the internal screen. After a
 * replug the monitor's touchscreen acted on the tablet (owner, 2026-10-11), so the list is
 * written each time it changes: what the input system had to work with when it went wrong.
 * Nothing is changed; nothing runs at load time but the hook itself.
 */
public final class TouchScreens {

    private static final String INPUT = "com.android.server.input.InputManagerService";

    private static boolean sInstalled;
    /** The list last written, so an unchanged one is not written again. */
    private static String sLast;

    private TouchScreens() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> input = Class.forName(INPUT, false, loader);
            int hooked = XposedBridge.hookAllMethods(input, "setDisplayViewportsInternal",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            write(param.args);
                        }
                    }).size();
            L.i("touch screens: the input system's screens watched x" + hooked);
        } catch (Throwable t) {
            L.w("touch screens: not watched (" + t + ")");
        }
    }

    private static void write(Object[] args) {
        try {
            if (args.length == 0 || !(args[0] instanceof List)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Object viewport : (List<?>) args[0]) {
                sb.append("\n  ").append(viewport);
            }
            String now = sb.toString();
            if (now.equals(sLast)) {
                return;
            }
            sLast = now;
            L.i("touch screens: the input system's screens are now" + (now.isEmpty()
                    ? " none" : now));
        } catch (Throwable ignored) {
            // A line less; the system's call went through untouched.
        }
    }
}
