package com.zuxos.desktopplus.hook.system;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: ZUI's "two high-performance apps" prompt left out, and the floating window
 * that came after it.
 *
 * <p>When an app ZUI counts as high-performance is opened while another one is in sight, ZUI
 * holds the start ({@code ActivityStartInterceptor.interceptGameTipIfNeeded}) and shows "Opening
 * A and B, two high-performance apps, at the same time may cause your device to lag or overheat.
 * Do you want to continue?". Continue then starts the app as a floating window, whatever it was
 * asked to be ({@code createGameIntentSenderForOriginalIntent}: windowing mode 5). ZUI's other
 * prompt, before one app opens another, is {@link LaunchPromptOff}'s.
 *
 * <p>ZUI's own decision is answered no ({@code OvGameTipDialogManager.canShowOvGameDialog}), so
 * the start goes on exactly as asked. The switch is read at boot and again when the settings
 * change ({@link SystemBridge}), never on the start path.
 */
public final class GameTipOff {

    private static boolean sInstalled;
    private static volatile boolean sOn = true;

    private GameTipOff() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> manager = Class.forName(SystemNames.OV_GAME_TIP_DIALOG_MANAGER, false,
                    loader);
            int hooked = XposedBridge.hookAllMethods(manager, SystemNames.CAN_SHOW_OV_GAME_DIALOG,
                    NO).size();
            reread();
            L.i("game tip: ZUI's high-performance prompt left out x" + hooked
                    + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("game tip: not installed, ZUI's prompt stays (" + t + ")");
        }
    }

    /** The switch, read off the start path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.noGameTip();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    private static final XC_MethodHook NO = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!sOn) {
                    return;
                }
                param.setResult(false);
                if (L.once("GameTipOff")) {
                    L.i("game tip: ZUI's prompt left out for " + param.args[0]
                            + " - it opens as asked");
                }
            } catch (Throwable t) {
                // ZUI decides, as before.
            }
        }
    };
}
