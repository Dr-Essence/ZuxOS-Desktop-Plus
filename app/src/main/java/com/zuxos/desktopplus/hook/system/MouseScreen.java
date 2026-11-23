package com.zuxos.desktopplus.hook.system;

import android.os.Handler;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: the mouse on the monitor's desktop from boot, by ZUI's own rule. ZUI puts
 * the mouse on the monitor while the window manager's {@code mForceDesktopModeOnExternalDisplays}
 * is set ({@code InputManagerCallback.getPointerDisplayId}), and sets it when its monitor desktop
 * comes on ({@code OVDesktopController.enterExternalDisplayDesktopMode}) - but never tells the
 * input system, which asks that rule only when the screens change. At boot the screens settle
 * before ZUI's desktop comes on, so the mouse stayed on the tablet until the next change of a
 * screen.
 *
 * <p>So when ZUI turns its monitor desktop on or off, the input system is handed its own screens
 * again ({@code InputManagerService.setDisplayViewportsInternal}, with the ones it has), the call a
 * change of screen makes, and it asks ZUI's rule again. It runs on the display manager's thread,
 * where those changes are handed over, so it never races one and never runs under ZUI's lock.
 * The switch is read at boot and when the settings change ({@link SystemBridge}).
 */
public final class MouseScreen {

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    /** The display manager's thread's handler, the input system's screens, and its own setter. */
    private static Method sDisplayHandler;
    private static Field sScreens;
    private static Method sHand;

    private MouseScreen() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> desktop = Class.forName(SystemNames.OV_DESKTOP_CONTROLLER, false, loader);
            Class<?> input = Class.forName(SystemNames.INPUT_MANAGER_SERVICE, false, loader);
            sDisplayHandler = Class.forName(SystemNames.DISPLAY_THREAD, false, loader)
                    .getMethod(SystemNames.GET_HANDLER);
            sScreens = input.getDeclaredField(SystemNames.M_DISPLAY_VIEWPORT_LIST);
            sScreens.setAccessible(true);
            sHand = input.getDeclaredMethod(SystemNames.SET_DISPLAY_VIEWPORTS_INTERNAL,
                    List.class);
            sHand.setAccessible(true);
            int hooked = XposedBridge.hookAllMethods(desktop,
                    SystemNames.ENTER_EXTERNAL_DISPLAY_DESKTOP_MODE, CHANGED).size()
                    + XposedBridge.hookAllMethods(desktop,
                    SystemNames.EXIT_EXTERNAL_DISPLAY_DESKTOP_MODE, CHANGED).size();
            if (hooked == 0) {
                L.w("mouse screen: ZUI's monitor desktop not found - the mouse's screen is "
                        + "ZUI's alone");
                return;
            }
            reread();
            L.i("mouse screen: asked again as ZUI's monitor desktop turns on and off x" + hooked
                    + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("mouse screen: not installed, the mouse's screen is ZUI's alone (" + t + ")");
        }
    }

    /** The switch, read off ZUI's path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.mouseOnDesktop();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    private static final XC_MethodHook CHANGED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sOn) {
                    return;
                }
                Handler display = (Handler) sDisplayHandler.invoke(null);
                boolean on = SystemNames.ENTER_EXTERNAL_DISPLAY_DESKTOP_MODE
                        .equals(param.method.getName());
                display.post(() -> askAgain(on));
            } catch (Throwable t) {
                if (L.once("MouseScreen.post")) {
                    L.w("mouse screen: not asked again, the mouse stays where it was (" + t + ")");
                }
            }
        }
    };

    /** The input system handed the screens it has, so it asks ZUI's rule for the mouse again. */
    private static void askAgain(boolean on) {
        try {
            // In the system the service is the input manager itself.
            Object input = Class.forName(SystemNames.SERVICE_MANAGER)
                    .getMethod(SystemNames.GET_SERVICE, String.class)
                    .invoke(null, SystemNames.INPUT_SERVICE);
            if (!sScreens.getDeclaringClass().isInstance(input)) {
                return;
            }
            Object list = sScreens.get(input);
            if (!(list instanceof List)) {
                return;
            }
            List<?> screens;
            synchronized (list) {
                screens = new ArrayList<>((List<?>) list);
            }
            if (screens.isEmpty()) {
                return;
            }
            sHand.invoke(input, screens);
            L.i("mouse screen: ZUI's monitor desktop " + (on ? "on" : "off")
                    + " - the mouse's screen asked again");
        } catch (Throwable t) {
            if (L.once("MouseScreen.ask")) {
                L.w("mouse screen: not asked again, the mouse stays where it was (" + t + ")");
            }
        }
    }
}
