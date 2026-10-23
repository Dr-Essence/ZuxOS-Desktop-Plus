package com.zuxos.desktopplus.hook.drawer;

import android.graphics.Canvas;
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
 * draws its cover over the bar only in the home screen's drawer, so scrolled rows passed under the
 * bar's icons. Clipped to its padding, the list ends at the bar's top edge - what ZUI's other
 * navigation modes get from that margin. Set once as each drawer opens; nothing of ZUI's sets it
 * back. The monitor's drawer is left as it is. ZUI's focus mark is clipped the same way
 * ({@link #clipMark}).
 */
final class DrawerAboveBar {

    private static final String SHEET =
            "com.android.launcher3.taskbar.allapps.TaskbarAllAppsSlideInView";
    private static final String LIST = "com.android.launcher3.allapps.AllAppsRecyclerView";

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
            hooked += clipMark(loader);
            Health.hooked("drawer: list ends above the bar", hooked,
                    Cfg::drawerAboveBar);
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
            if (L.once("DrawerAboveBar")) {
                L.i("drawer above bar: " + clipped + " list(s) clipped to the bar");
            }
        } catch (Throwable t) {
            L.d("drawer above bar: not clipped (" + t + ")");
        }
    }

    /** The canvas's save count while ZUI's focus mark is drawn clipped; -1 when not. */
    private static int sMarkSaved = -1;

    /**
     * ZUI's mark for the icon a keyboard or controller is on is a decoration of the list
     * ({@code FocusedItemDecorator.onDrawOver}), drawn after its rows and not clipped to its
     * padding as the rows are: for a row half under the search bar or the taskbar, the mark was
     * drawn whole over them. Clipped as the list clips its rows, it is cut where the row is.
     */
    private static int clipMark(ClassLoader loader) {
        Class<?> decorator = Reflect.findClass(
                "com.android.launcher3.keyboard.FocusedItemDecorator", loader);
        if (decorator == null) {
            L.w("drawer above bar: ZUI's focus mark not found - drawn as ZUI draws it");
            return 0;
        }
        return XposedBridge.hookAllMethods(decorator, "onDrawOver", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                sMarkSaved = -1;
                if (!Cfg.enabled() || !Cfg.drawerAboveBar() || param.args.length < 2
                        || !(param.args[0] instanceof Canvas)
                        || !(param.args[1] instanceof ViewGroup)) {
                    return;
                }
                ViewGroup list = (ViewGroup) param.args[1];
                if (!list.getClipToPadding()) {
                    return;
                }
                Canvas canvas = (Canvas) param.args[0];
                sMarkSaved = canvas.save();
                canvas.clipRect(list.getPaddingLeft(), list.getPaddingTop(),
                        list.getWidth() - list.getPaddingRight(),
                        list.getHeight() - list.getPaddingBottom());
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sMarkSaved >= 0 && param.args[0] instanceof Canvas) {
                    ((Canvas) param.args[0]).restoreToCount(sMarkSaved);
                }
                sMarkSaved = -1;
            }
        }).size();
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
