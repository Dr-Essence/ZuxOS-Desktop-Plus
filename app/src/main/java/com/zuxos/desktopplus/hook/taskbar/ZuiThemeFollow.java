package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.view.View;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.drawer.NativeDrawerHooks;
import com.zuxos.desktopplus.hook.drawer.ZuiFolderCells;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Our parts of the bar follow ZUI's own changes as ZUI's bar does, with no force stop.
 *
 * <p>ZUI changes much of its bar without making it again, so what we add to it - our row, the
 * marks, the start button, our folders as ZUI's - kept the old look until ZUX Home was stopped:
 * <ul>
 * <li>The icon shape is a system overlay; ZUI hears it through its {@code ThemeManager}, whose
 * listeners it tells ({@code ThemeChangeListener.onThemeChanged}), and reloads its model. A ZUI
 * icon theme comes through {@code LauncherModel.onZuiThemeChanged}. Either way every icon we made
 * is dropped, and our rows are built again once ZUI's reload hands its new apps over
 * ({@link ZuiFolderCells#forgetIcons}).</li>
 * <li>Night mode, the bar's resources, ZUI's drawer button and recent apps switches reach its bar
 * as calls on its context ({@code onUiModeChange}, {@code onAssetsPathChange},
 * {@code allAppsEnableChange}, {@code onTbRecentUsedAppEnableChange}); after each, our parts of
 * that bar are put on it again, as when ZUI adds the bar.</li>
 * </ul>
 * Nothing runs between those events.
 */
final class ZuiThemeFollow {

    private static final String BAR = "com.android.launcher3.taskbar.TaskbarActivityContext";
    private static boolean sListening;

    private ZuiThemeFollow() {
    }

    static void install(ClassLoader loader) {
        try {
            int hooked = 0;
            Class<?> bar = Reflect.findClass(BAR, loader);
            if (bar != null) {
                XC_MethodHook again = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        reapply(param.thisObject);
                    }
                };
                for (String name : new String[]{"onUiModeChange", "onAssetsPathChange",
                        "allAppsEnableChange", "onTbRecentUsedAppEnableChange"}) {
                    hooked += XposedBridge.hookAllMethods(bar, name, again).size();
                }
            }
            Class<?> model = Reflect.findClass("com.android.launcher3.LauncherModel", loader);
            if (model != null) {
                hooked += XposedBridge.hookAllMethods(model, "onZuiThemeChanged",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                iconsChanged("ZUI's icon theme");
                            }
                        }).size();
            }
            L.i("zui theme follow: our bar follows ZUI's theme and settings x" + hooked);
        } catch (Throwable t) {
            L.w("zui theme follow: not installed, ZUI's changes reach our bar at its next start ("
                    + t + ")");
        }
    }

    /**
     * Listens to ZUI's own icon-shape changes, once, from the first bar: ZUI's
     * {@code ThemeManager} is reached through a context.
     */
    static void listen(Context ctx) {
        if (sListening) {
            return;
        }
        sListening = true;
        try {
            ClassLoader loader = ctx.getClassLoader();
            Class<?> manager = Class.forName("com.android.launcher3.graphics.ThemeManager",
                    false, loader);
            Object singleton = manager.getField("INSTANCE").get(null);
            Object themes = singleton.getClass().getMethod("get", Context.class)
                    .invoke(singleton, ctx);
            Class<?> listener = Class.forName(
                    "com.android.launcher3.graphics.ThemeManager$ThemeChangeListener", false,
                    loader);
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{listener},
                    (self, method, args) -> {
                        if ("onThemeChanged".equals(method.getName())) {
                            iconsChanged("the icon shape");
                            return null;
                        }
                        // equals, hashCode, toString: as any object answers them.
                        return method.getName().equals("equals") ? self == args[0]
                                : method.getName().equals("hashCode")
                                ? System.identityHashCode(self) : "ZuxDesktopPlus theme follow";
                    });
            Method add = themes.getClass().getMethod("addChangeListener", listener);
            add.invoke(themes, proxy);
            L.i("zui theme follow: listening to ZUI's icon shape changes");
        } catch (Throwable t) {
            L.w("zui theme follow: ZUI's icon shape not followed, it reaches our bar at its next"
                    + " start (" + t + ")");
        }
    }

    /** The icons' shape or theme changed: every icon of ours made again, in the new one. */
    private static void iconsChanged(String what) {
        try {
            NativeDrawerHooks.forgetIcons();
            ZuiFolderCells.forgetIcons();
            L.i("zui theme follow: " + what + " changed - our icons are made again");
        } catch (Throwable t) {
            L.d("zui theme follow: icons not made again (" + t + ")");
        }
    }

    /** One of ZUI's bars changed in place: our parts of it put on again. */
    private static void reapply(Object bar) {
        try {
            Object layer = bar.getClass().getMethod("getDragLayer").invoke(bar);
            if (layer instanceof View) {
                TaskbarTray.reapply(((View) layer).getRootView());
            }
        } catch (Throwable t) {
            L.d("zui theme follow: our bar not put on again (" + t + ")");
        }
    }
}
