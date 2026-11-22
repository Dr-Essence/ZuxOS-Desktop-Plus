package com.zuxos.desktopplus.hook.system;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: ZUI's "Association Launch App Prompt" left out - "A is attempting to launch
 * B, allow?" each time one app opened another, a game launcher's every game included.
 *
 * <p>Before any app opens another ({@code ActivityTaskManagerService.startActivityAsUser}), ZUI
 * asks {@code ZuiWmAutoRunManager.isAllowRelativeStart}. It lets the home app, system apps and
 * its white list through, and for the rest reads the pair's status from its Security Center
 * ({@code ZuiSecurityManager.getRelativeAppStatus}): 1 allowed, 2 denied, and 0 "ask", which
 * shows the prompt and starts nothing until Allow is pressed.
 *
 * <p>So while ZUI decides an activity start, an "ask" from Security Center is read as "allowed":
 * ZUI takes its own allowed branch, no prompt, the app opens as asked. A pair denied in Security
 * Center stays denied, and the same status read for background starts (services, broadcasts,
 * {@code ZuiAmAutoRunManager}) is left exactly as ZUI has it. The switch is read at boot and
 * again when the settings change ({@link SystemBridge}), never on the start path.
 */
public final class LaunchPromptOff {

    private static final int ASK = 0;
    private static final int ALLOWED = 1;

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    /** Set while ZUI decides an activity start on this thread. */
    private static final ThreadLocal<Boolean> DECIDING = new ThreadLocal<>();

    private LaunchPromptOff() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            Class<?> manager = Class.forName(SystemNames.ZUI_WM_AUTO_RUN_MANAGER, false, loader);
            Class<?> security = Class.forName(SystemNames.ZUI_SECURITY_MANAGER, false, loader);
            int hooked = XposedBridge.hookAllMethods(manager, SystemNames.IS_ALLOW_RELATIVE_START,
                    DECIDE).size();
            if (hooked == 0) {
                L.w("launch prompt: ZUI's check not found, its prompt stays");
                return;
            }
            hooked += XposedBridge.hookAllMethods(security, SystemNames.GET_RELATIVE_APP_STATUS,
                    STATUS).size();
            reread();
            L.i("launch prompt: ZUI's association prompt left out x" + hooked
                    + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("launch prompt: not installed, ZUI's prompt stays (" + t + ")");
        }
    }

    /** The switch, read off the start path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.noLaunchPrompt();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    private static final XC_MethodHook DECIDE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                DECIDING.set(Boolean.TRUE);
            } catch (Throwable t) {
                // Then ZUI decides alone.
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                DECIDING.remove();
            } catch (Throwable t) {
                // Nothing held.
            }
        }
    };

    private static final XC_MethodHook STATUS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sOn || DECIDING.get() == null
                        || !Integer.valueOf(ASK).equals(param.getResult())) {
                    return;
                }
                param.setResult(ALLOWED);
                if (L.once("LaunchPromptOff")) {
                    L.i("launch prompt: " + param.args[0] + " opens " + param.args[1]
                            + " without ZUI's prompt");
                }
            } catch (Throwable t) {
                // ZUI's own answer stands.
            }
        }
    };
}
