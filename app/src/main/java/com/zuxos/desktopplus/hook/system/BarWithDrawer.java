package com.zuxos.desktopplus.hook.system;

import android.view.WindowManager;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Inside the system: over an app that hides the bars, the taskbar shows while a window of the
 * taskbar's own has the focus - ZUI's drawer, and the folders, menus and panels opened from it.
 *
 * <p>Which window holds the navigation bar - the taskbar - decides whether it shows. While a
 * window that is not a full-screen app has the focus, ZUI gives it to the full-screen app behind
 * when that app hides the bar ({@code InsetsPolicy.getNavControlTargetInner_ov}, its "top app
 * hides system bar" branch); only the notification shade is let off. So ZUI's drawer, opened by
 * the Windows key over a game, came without the bar.
 *
 * <p>In that one branch, and only for ZUX Home's overlay windows, the bar goes to ZUI's own
 * showing holder instead - the one a swipe from the edge, or the pointer resting there, brings
 * the bar back with. ZUI then gives the app a stand-in, so it is not laid out again, and hands
 * the bar back to it as soon as the app has the focus again. The keyboard's, the shade's and
 * ZUI's forced cases are other branches and are left as they are. The switch is read at boot and
 * when the settings change ({@link SystemBridge}), never on this path.
 */
public final class BarWithDrawer {

    private static boolean sInstalled;
    private static volatile boolean sOn = true;
    private static Class<?> sWindowState;
    private static Field sAttrs;
    private static Field sShowing;

    private BarWithDrawer() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        try {
            sWindowState = Class.forName(SystemNames.WINDOW_STATE, false, loader);
            sAttrs = sWindowState.getField(SystemNames.M_ATTRS);
            Class<?> policy = Class.forName(SystemNames.INSETS_POLICY, false, loader);
            sShowing = policy.getDeclaredField(SystemNames.M_SHOWING_TRANSIENT_CONTROL_TARGET);
            sShowing.setAccessible(true);
            int hooked = XposedBridge.hookAllMethods(policy,
                    SystemNames.GET_NAV_CONTROL_TARGET_INNER_OV, SHOW).size();
            reread();
            if (hooked == 0) {
                L.w("bar with drawer: ZUI's bar holder not found - over an app that hides the "
                        + "bars, the drawer comes without the taskbar");
                return;
            }
            L.i("bar with drawer: the taskbar shows with its own windows over full-screen apps x"
                    + hooked + (sOn ? "" : ", switched off"));
        } catch (Throwable t) {
            L.w("bar with drawer: not installed, the drawer comes without the bar over "
                    + "full-screen apps (" + t + ")");
        }
    }

    /** The switch, read off the insets path: at boot, and when the settings change. */
    static void reread() {
        try {
            sOn = Cfg.enabled() && Cfg.barWithDrawer();
        } catch (Throwable t) {
            // The last value stands.
        }
    }

    private static final XC_MethodHook SHOW = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object focused = param.args[0];
                Object holder = param.getResult();
                // A stand-in being worked out, or not ZUI's "top app hides the bar" branch.
                if (!sOn || Boolean.TRUE.equals(param.args[1]) || focused == null
                        || holder == focused || !sWindowState.isInstance(holder)) {
                    return;
                }
                WindowManager.LayoutParams attrs = taskbarsOwn(focused);
                Object showing = attrs == null ? null : sShowing.get(param.thisObject);
                if (showing == null) {
                    return;
                }
                param.setResult(showing);
                if (L.once("BarWithDrawer")) {
                    L.i("bar with drawer: the taskbar shown with " + attrs.getTitle() + " over "
                            + holder);
                }
            } catch (Throwable t) {
                // ZUI decides, as before.
            }
        }
    };

    /** The window's layout if it is ZUX Home's own over the apps (its drawer, a panel of ours). */
    private static WindowManager.LayoutParams taskbarsOwn(Object window) throws Exception {
        Object attrs = sAttrs.get(window);
        if (!(attrs instanceof WindowManager.LayoutParams)) {
            return null;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) attrs;
        return lp.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                && SystemNames.ZUX_HOME.equals(lp.packageName) ? lp : null;
    }
}
