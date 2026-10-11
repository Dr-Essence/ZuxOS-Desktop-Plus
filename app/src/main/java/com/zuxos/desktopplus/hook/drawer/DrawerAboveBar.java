package com.zuxos.desktopplus.hook.drawer;

import android.view.Display;
import android.view.View;
import android.view.ViewGroup;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The tablet taskbar drawer's app list ends at the bar instead of running on under its icons.
 *
 * <p>ZUI pads the list's bottom by the bar's height ({@code AdapterHolder} in
 * {@code ActivityAllAppsContainerView}), so its last row stops above the bar, but the list draws
 * into that padding: with three-button navigation ZUI also zeroes the list's bottom margin and
 * draws its cover over the bar only in the home screen's drawer, so scrolled rows passed under
 * the bar's icons (14:12). Clipped to its padding, the list ends at the bar's top edge - what
 * ZUI's other navigation modes get from that margin. Set once as each drawer opens; nothing of
 * ZUI's sets it back. The monitor's drawer is left as it is.
 */
final class DrawerAboveBar {

    private static final String SHEET =
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView";
    private static final String LIST = "com.android.launcher3.allapps.AllAppsRecyclerView";

    private static boolean sSaid;

    private DrawerAboveBar() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> sheet = Reflect.findClass(SHEET, loader);
            Class<?> list = Reflect.findClass(LIST, loader);
            if (sheet == null || list == null) {
                L.w("drawer above bar: ZUI's drawer sheet or list not found, left as it is");
                return;
            }
            int hooked = XposedBridge.hookAllMethods(sheet, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            clip((View) param.thisObject, list);
                        }
                    }).size();
            Health.hooked("drawer: list ends above the bar", hooked);
            L.i("drawer above bar: the taskbar drawer's list ends at the bar x" + hooked);
        } catch (Throwable t) {
            L.w("drawer above bar: not installed (" + t + ")");
        }
    }

    private static void clip(View sheet, Class<?> list) {
        try {
            if (!Cfg.enabled() || !Cfg.drawerAboveBar() || sheet.getDisplay() == null
                    || sheet.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) {
                return;
            }
            int clipped = clipIn(sheet, list);
            if (!sSaid) {
                sSaid = true;
                L.i("drawer above bar: " + clipped + " list(s) clipped to the bar");
            }
        } catch (Throwable t) {
            L.d("drawer above bar: not clipped (" + t + ")");
        }
    }

    private static int clipIn(View view, Class<?> list) {
        if (list.isInstance(view)) {
            ((ViewGroup) view).setClipToPadding(true);
            return 1;
        }
        int clipped = 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clipped += clipIn(group.getChildAt(i), list);
            }
        }
        return clipped;
    }
}
