package com.zuxos.desktopplus.hook.taskbar;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.ZuiApps;
import com.zuxos.desktopplus.hook.ZuiFocus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ZUI's own floating window and split screen, in the menus of the apps on the tablet's bar - its
 * pinned apps and its open ones - as ZUI's taskbar menus have them for the apps it shows.
 *
 * <p>ZUI makes them for its taskbar popups ({@code TaskbarPopupController.s}): its
 * {@code FLOAT_OPEN} ({@code ZuiSystemShortcuts.FloatOpen}, only for an app that can float,
 * {@code isPackageSupportZuiFreeform}) and one split shortcut per side
 * ({@code TaskbarUIController.d}: {@code TaskbarSplitShortcut}). Our bar's apps have menus of our
 * own, so they had neither. Here ZUI's own factories make them, for the bar and the app's entry
 * in ZUI's app list; their label and icon are ZUI's, and a tap runs ZUI's own code with the held
 * icon, as ZUI's would.
 */
final class ZuiWindows {

    private static final String CONTEXT = "com.android.launcher3.views.ActivityContext";
    private static final String POPUPS = "com.android.launcher3.taskbar.TaskbarPopupController";

    private ZuiWindows() {
    }

    /**
     * ZUI's floating window and split screen rows for {@code pkg}, held at {@code icon}; none in
     * a menu the controller opened, as ZUI's own menus have none then ({@code DrawerKeys}).
     */
    static List<TaskbarMenu.Entry> entries(View icon, String pkg, UserHandle user) {
        List<TaskbarMenu.Entry> entries = new ArrayList<>();
        try {
            if (ZuiFocus.byKeys() && Cfg.gamepadMenus()) {
                return entries;
            }
            if (icon == null || icon.getDisplay() == null
                    || icon.getDisplay().getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
                return entries;
            }
            Context ctx = icon.getContext();
            ClassLoader loader = ctx.getClassLoader();
            Object bar = Class.forName(CONTEXT, false, loader)
                    .getMethod("lookupContext", Context.class).invoke(null, ctx);
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            ComponentName component = launch == null ? null : launch.getComponent();
            Object launcher = ZuiApps.launcher(loader);
            Object info = component == null || launcher == null ? null
                    : ZuiApps.appInfo(launcher, component, user);
            if (bar == null || !bar.getClass().getName().endsWith("TaskbarActivityContext")
                    || info == null) {
                return entries;
            }
            List<Object> factories = new ArrayList<>();
            factories.add(Class.forName(POPUPS, false, loader).getField("FLOAT_OPEN").get(null));
            Object controllers = Reflect.field(bar, "mControllers");
            Object ui = Reflect.field(controllers, "uiController");
            Object split = ui == null ? null : Reflect.call(ui, "d", controllers);
            if (split instanceof java.util.stream.Stream) {
                for (Object factory : ((java.util.stream.Stream<?>) split).toArray()) {
                    factories.add(factory);
                }
            }
            for (Object factory : factories) {
                Object shortcut = make(factory, bar, info, icon);
                TaskbarMenu.Entry entry = shortcut == null ? null : entryOf(ctx, shortcut, icon);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (Throwable t) {
            L.d("zui windows: ZUI's floating window and split screen not offered (" + t + ")");
        }
        return entries;
    }

    /** ZUI's factory's shortcut for the app; null where ZUI offers none. */
    private static Object make(Object factory, Object bar, Object info, View icon) {
        for (Method m : factory.getClass().getMethods()) {
            if (m.getName().equals("getShortcut") && m.getParameterCount() == 3) {
                try {
                    m.setAccessible(true);
                    return m.invoke(factory, bar, info, icon);
                } catch (Throwable t) {
                    L.d("zui windows: one of ZUI's shortcuts not made (" + t + ")");
                    return null;
                }
            }
        }
        return null;
    }

    /** The shortcut as a row: ZUI's label and glyph, ZUI's own tap. */
    private static TaskbarMenu.Entry entryOf(Context ctx, Object shortcut, View icon) {
        Object label = Reflect.field(shortcut, "mLabelResId");
        Object glyph = Reflect.field(shortcut, "j");
        if (!(label instanceof Integer) || !(shortcut instanceof View.OnClickListener)) {
            return null;
        }
        Drawable drawable = glyph instanceof Integer && (Integer) glyph != 0
                ? ctx.getDrawable((Integer) glyph) : null;
        return new TaskbarMenu.Entry(ctx.getString((Integer) label), drawable, true,
                () -> ((View.OnClickListener) shortcut).onClick(icon));
    }
}
