package com.zuxos.desktopplus.hook.home;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The band ZUX Home keeps for its dock above the tablet's bar becomes home screen: the grid runs
 * down to the bar, as it does where ZUI shows no dock.
 *
 * <p>ZUI keeps that band in its device profile ({@code DeviceProfile.hotseatAreaHeight}: its dock
 * pad, {@code hotseat_bar_height_pad}, plus the bar's own space, {@code launcherTaskBarOffset}),
 * and lays the home screen above it ({@code N(Context)}, the workspace's bottom padding), its page
 * dots too. No setting of ZUI's gives it up; the module only stops the empty dock drawing, which
 * left the band empty and still a drop zone for the dock. Here the band is the bar's own space
 * only, through ZUI's own fields: the home screen's padding loses the dock's part and the page dots
 * follow by ZUI's code. ZUI's own checks then keep the dock out of the way: with no bounds view no
 * drop goes to it ({@code Workspace} asks
 * {@code getCustomBoundsView()} first), it takes no touches, as ZUI's desktop mode has its dock
 * stand aside, and it is never drawn ({@link HotseatButton}). Tablet's screen, regular mode
 * only: ZUI's desktop mode keeps its own smaller band, the monitor its own profile.
 */
public final class HomeBand {

    /** A tablet profile of the tablet's screen has given its band to the home screen. */
    private static boolean sApplied;

    private HomeBand() {
    }

    /** Whether the dock's band is home screen now: the dock takes nothing and draws nothing. */
    static boolean active() {
        return sApplied && Cfg.enabled() && Cfg.homeBand();
    }

    public static void install(ClassLoader loader) {
        try {
            if (!Cfg.enabled() || (!Cfg.homeBand() && !Cfg.homeEdges())) {
                return;
            }
            Class<?> profile = Reflect.findClass("com.android.launcher3.DeviceProfile", loader);
            Class<?> utilities = Reflect.findClass("com.android.launcher3.Utilities", loader);
            Class<?> dock = Reflect.findClass("com.zui.launcher.uiextend.ZuiHotseat", loader);
            if (profile == null || utilities == null || dock == null) {
                L.w("home band: ZUI's profile or dock not found - the dock's band stays");
                return;
            }
            int hooked = 0;
            for (java.lang.reflect.Method m : profile.getDeclaredMethods()) {
                if (m.getName().equals("N") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Context.class
                        && m.getReturnType() == void.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            give(param.thisObject, (Context) param.args[0], utilities);
                        }
                    });
                    hooked++;
                }
            }
            if (hooked == 0) {
                L.w("home band: ZUI's workspace padding not found - the dock's band stays");
                return;
            }
            hooked += XposedBridge.hookAllMethods(dock, "getCustomBoundsView",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (active()) {
                                param.setResult(null);
                            }
                        }
                    }).size();
            hooked += XposedBridge.hookAllMethods(dock, "dispatchTouchEvent",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (active() && onTablet(param.thisObject)) {
                                param.setResult(Boolean.FALSE);
                            }
                        }
                    }).size();
            L.i("home band: the dock's band is home screen x" + hooked);
        } catch (Throwable t) {
            L.w("home band: not installed (" + t + ")");
        }
    }

    /** After ZUI laid the home screen out over its profile: the dock's part of the band goes. */
    private static void give(Object dp, Context ctx, Class<?> utilities) {
        try {
            Class<?> c = dp.getClass();
            if (!c.getField("isTablet").getBoolean(dp)
                    || c.getField("isUsedToDpMode").getBoolean(dp)
                    || c.getField("displayId").getInt(dp) != 0
                    || Boolean.TRUE.equals(utilities.getMethod("isUnlimitedWorkMode",
                    Context.class).invoke(null, ctx))) {
                return;
            }
            if (Cfg.homeEdges()) {
                edges(dp, c);
            }
            if (!Cfg.homeBand()) {
                return;
            }
            int band = c.getField("hotseatAreaHeight").getInt(dp);
            int bar = c.getField("launcherTaskBarOffset").getInt(dp);
            int dockPart = band - bar;
            if (dockPart <= 0) {
                // Already given (N runs more than once on one profile).
                return;
            }
            Rect padding = (Rect) c.getField("workspacePadding").get(dp);
            c.getField("hotseatAreaHeight").setInt(dp, bar);
            padding.bottom = Math.max(0, padding.bottom - dockPart);
            sApplied = true;
            if (L.once("HomeBand")) {
                L.i("home band: " + dockPart + " px of the dock's band given to the home screen");
            }
        } catch (Throwable t) {
            L.d("home band: the band kept (" + t + ")");
        }
    }

    /**
     * The bands at the home screen's top and bottom become home screen too: the page dots' band,
     * which ZUI adds under the grid ({@code N}: {@code workspacePageIndicatorHeight - r}, 60 dp
     * here) though its dots show only while the pages are swiped, and the empty band each page
     * keeps at its top ({@code cellLayoutPaddingPx.top}, 60 dp, from
     * {@code Utilities.getCellLayoutPaddingVertical}),
     * which takes ZUI's own side padding instead. The dots then draw over the last row while a
     * swipe lasts. ZUI's {@code N} lays the padding out afresh each time, so the dots' band is
     * taken each time; the page's top is set once.
     */
    private static void edges(Object dp, Class<?> c) throws Exception {
        java.lang.reflect.Field responsive = c.getDeclaredField("f");
        java.lang.reflect.Field overlap = c.getDeclaredField("r");
        if (responsive.getType() != boolean.class || overlap.getType() != int.class) {
            return;
        }
        responsive.setAccessible(true);
        overlap.setAccessible(true);
        if (responsive.getBoolean(dp)) {
            // ZUI's responsive grids have no dots' band of this kind.
            return;
        }
        int dots = Math.max(0, c.getField("workspacePageIndicatorHeight").getInt(dp)
                - overlap.getInt(dp));
        Rect padding = (Rect) c.getField("workspacePadding").get(dp);
        padding.bottom = Math.max(0, padding.bottom - dots);
        Rect cell = (Rect) c.getField("cellLayoutPaddingPx").get(dp);
        int top = cell.top;
        cell.top = Math.min(cell.top, cell.left);
        if (L.once("HomeBand.edges")) {
            L.i("home band: the home screen takes the page dots' " + dots + " px and its top "
                    + (top - cell.top) + " px");
        }
    }

    private static boolean onTablet(Object view) {
        return view instanceof View && ((View) view).getDisplay() != null
                && ((View) view).getDisplay().getDisplayId() == 0;
    }
}
