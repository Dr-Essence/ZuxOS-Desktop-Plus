package com.zuxos.desktopplus.hook.systemui;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A window's title bar that closed while SystemUI was still loading its name and icon: let go,
 * as SystemUI already lets go of its other look-up.
 *
 * <p>SystemUI loads a window's name, header icon and veil icon off the main thread
 * ({@code DesktopModeWindowDecoration$$ExternalSyntheticLambda0}). For the veil icon ZUI checks
 * first whether the title bar has closed ("is closed, return"); for the name and header icon it
 * does not, and {@code WindowDecorTaskResourceLoader} throws for a title bar that is gone
 * ("Attempt to obtain resource for non-existent decoration"); SystemUI crashed on exactly that
 * as a monitor was unplugged and its windows came to the tablet. That one exception, from that
 * one task, ends the task instead: the title bar is gone, so there is nothing to give a name or
 * an icon to - what ZUI's own check does for the veil.
 */
public final class ClosedWindowInfo {

    private static final String LOAD =
            "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration$$ExternalSyntheticLambda0";
    private static final String GONE = "non-existent decoration";

    private ClosedWindowInfo() {
    }

    public static void install(ClassLoader loader) {
        try {
            Class<?> load = Reflect.findClass(LOAD, loader);
            if (load == null) {
                L.w("closed window info: SystemUI's loader task not found, left as it is");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(load, "run", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Throwable t = param.getThrowable();
                        if (t instanceof IllegalStateException && t.getMessage() != null
                                && t.getMessage().contains(GONE)) {
                            param.setResult(null);
                            if (L.once("ClosedWindowInfo")) {
                                L.i("closed window info: a closed title bar's name and icon "
                                        + "not loaded, as ZUI does for its veil");
                            }
                        }
                    } catch (Throwable ignored) {
                        // SystemUI's own outcome stands.
                    }
                }
            }).size();
            L.i("closed window info: watching SystemUI's title-bar loads x" + hooked);
        } catch (Throwable t) {
            L.w("closed window info: not installed (" + t + ")");
        }
    }
}
