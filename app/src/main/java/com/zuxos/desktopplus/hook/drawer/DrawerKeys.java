package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.Mirror;
import com.zuxos.desktopplus.hook.ZuiMenu;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Field;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * A game controller in the drawers: Triangle opens the menu of the icon it is on - an app, one of
 * our folders, an app inside one - as a hold does, and in ZUI's menus the D-pad moves between the
 * rows and stays among them.
 *
 * <p>Triangle is {@code KEYCODE_BUTTON_Y}. Nothing in ZUX Home takes it, so Android falls back to
 * SPACE ({@code Generic.kcm}), a click on the focused icon. The drawers' layers see every key on
 * its way to the focused view ({@code DragLayer.dispatchKeyEvent} in ZUX Home,
 * {@code TaskbarOverlayDragLayer.dispatchKeyEvent} in the taskbar's drawer). There, the press opens
 * ZUI's own popup for the icon, as ZUI's hold opens it, without the drag:
 * <ul>
 *   <li>{@code PopupContainerWithArrow.showForIcon} in ZUX Home, for an app and, as
 *       {@code FolderIcon.startLongPressAction} does, for a folder;</li>
 *   <li>{@code TaskbarPopupController.showForIcon} for an app in the taskbar's drawer;</li>
 *   <li>our folder menu ({@link DrawerHold#menuEntries}) for a folder there.</li>
 * </ul>
 * Its press and release are both taken, so no click follows.
 *
 * <p>ZUI's popups ask for the focus as they open, which one opened by touch never gets: Android
 * keeps no focus in touch mode. While a popup is open, an arrow pressed with the focus outside it
 * puts the focus on its first row, and an arrow that would leave it is kept. A picks a row (the
 * controller's centre key), and B (back) or Triangle again closes the popup.
 */
public final class DrawerKeys {

    private static final String POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";
    private static final String CONTEXT = "com.android.launcher3.views.ActivityContext";

    /** Triangle's press opened a menu: its release is taken too. */
    private static boolean sTook;
    private static boolean sSaid;

    private DrawerKeys() {
    }

    public static void install(ClassLoader loader) {
        int hooked = 0;
        for (String name : new String[]{"com.android.launcher3.dragndrop.DragLayer",
                "com.android.launcher3.taskbar.overlay.TaskbarOverlayDragLayer"}) {
            try {
                Class<?> layer = Reflect.findClass(name, loader);
                if (layer == null) {
                    continue;
                }
                hooked += XposedBridge.hookAllMethods(layer, "dispatchKeyEvent",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args.length == 1
                                        && param.args[0] instanceof KeyEvent
                                        && key((View) param.thisObject,
                                        (KeyEvent) param.args[0])) {
                                    param.setResult(Boolean.TRUE);
                                }
                            }
                        }).size();
            } catch (Throwable t) {
                L.w("drawer keys: " + name + " not watched (" + t + ")");
            }
        }
        Health.hooked("drawer: controller's Triangle and menus", hooked, Cfg::gamepadMenus);
        L.i("drawer keys: Triangle opens menus, menus follow the controller x" + hooked);
    }

    /** True when the key was ours, and goes no further. */
    private static boolean key(View layer, KeyEvent event) {
        try {
            if (!Cfg.gamepadMenus() || layer.getDisplay() == null
                    || layer.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) {
                return false;
            }
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BUTTON_Y) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        sTook = menu(layer);
                    }
                    return sTook;
                }
                boolean took = sTook;
                sTook = false;
                return took;
            }
            int direction = direction(code);
            return direction != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                    && keepInPopup(layer, direction);
        } catch (Throwable t) {
            L.d("drawer keys: a key left to ZUI (" + t + ")");
            return false;
        }
    }

    private static int direction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return View.FOCUS_RIGHT;
            default:
                return 0;
        }
    }

    /**
     * The menu of the focused icon, opened as a hold opens it, or the open menu closed again;
     * false with neither.
     */
    private static boolean menu(View layer) throws Exception {
        View focused = layer.findFocus();
        Object activity = lookup(layer.getContext());
        Object open = activity == null ? null : openPopup(layer, activity);
        if (open != null) {
            // Triangle again: the menu goes, as B takes it away.
            Reflect.call(open, "close", true);
            return true;
        }
        if (focused == null || activity == null) {
            return false;
        }
        boolean home = ZuiFolders.isLauncher(activity);
        for (View v = focused; v != null && v != layer; v = parentOf(v)) {
            Item folder = ZuiFolders.isZuiFolder(v) ? ZuiFolders.folderOfIcon(v) : null;
            String pkg = folder == null ? IconInfo.packageOf(v.getTag()) : null;
            if (folder == null && pkg == null) {
                continue;
            }
            boolean shown = folder != null
                    ? home && showForIcon(v) || ZuiMenu.show(v, DrawerHold.menuEntries(v, folder))
                    : home ? showForIcon(v) : showOnTaskbar(v, activity);
            // Opened by a key, the popup may hold the focus itself rather than a row.
            focusFirst(openPopup(layer, activity));
            say("the menu of " + (folder != null ? folder.label : pkg), shown);
            return true;
        }
        return false;
    }

    private static void say(String what, boolean shown) {
        if (!sSaid) {
            sSaid = true;
            L.i("drawer keys: Triangle - " + what + (shown ? " opened" : " not opened"));
        }
    }

    private static View parentOf(View v) {
        ViewParent p = v.getParent();
        return p instanceof View ? (View) p : null;
    }

    /** ZUX Home's own popup for an icon ({@code PopupContainerWithArrow.showForIcon}). */
    private static boolean showForIcon(View icon) throws Exception {
        return Class.forName(POPUP, false, icon.getContext().getClassLoader())
                .getMethod("showForIcon", View.class).invoke(null, icon) != null;
    }

    /**
     * The taskbar's own popup for an app of its drawer ({@code TaskbarPopupController}, reached
     * through the drawer's drag controller, which holds the bar's controllers).
     */
    private static boolean showOnTaskbar(View icon, Object activity) throws Exception {
        Object drags = Reflect.call(activity, "getDragController");
        Object controllers = null;
        for (Field f : drags == null ? new ArrayList<Field>() : Mirror.fields(drags.getClass())) {
            if (f.getType().getName().endsWith(".TaskbarControllers")) {
                controllers = Mirror.get(f, drags);
                break;
            }
        }
        Object popups = controllers == null ? null
                : Reflect.field(controllers, "taskbarPopupController");
        if (popups == null) {
            return false;
        }
        return popups.getClass().getMethod("showForIcon", View.class).invoke(popups, icon)
                != null;
    }

    /** The popup's first row takes the focus when the popup kept it for itself. */
    private static void focusFirst(Object popup) {
        if (!(popup instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) popup;
        View focused = group.findFocus();
        if (focused == null || focused == group) {
            View first = firstRow(group);
            if (first != null) {
                first.requestFocus();
            }
        }
    }

    /** The first row of a popup that can take the focus, or null. */
    private static View firstRow(ViewGroup popup) {
        for (View v : popup.getFocusables(View.FOCUS_DOWN)) {
            if (v != popup) {
                return v;
            }
        }
        return null;
    }

    /**
     * While ZUI's popup is open: an arrow with the focus outside it puts the focus on its first
     * row, and one that would leave it is kept. False with no popup open.
     */
    private static boolean keepInPopup(View layer, int direction) {
        Object activity = lookup(layer.getContext());
        Object popup = activity == null ? null : openPopup(layer, activity);
        if (!(popup instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) popup;
        View focused = layer.findFocus();
        if (focused == null || !within(focused, group)) {
            View first = firstRow(group);
            return first != null && first.requestFocus();
        }
        View next = focused.focusSearch(direction);
        return next == null || !within(next, group);
    }

    private static boolean within(View v, ViewGroup group) {
        for (View p = v; p != null; p = parentOf(p)) {
            if (p == group) {
                return true;
            }
        }
        return false;
    }

    /** ZUI's popup open over this layer ({@code PopupContainerWithArrow.getOpen}), or null. */
    private static Object openPopup(View layer, Object activity) {
        try {
            for (java.lang.reflect.Method m : Class.forName(POPUP, false,
                    layer.getContext().getClassLoader()).getMethods()) {
                if (m.getName().equals("getOpen") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(activity)) {
                    return m.invoke(null, activity);
                }
            }
        } catch (Throwable t) {
            L.d("drawer keys: no popup read (" + t + ")");
        }
        return null;
    }

    private static Object lookup(Context ctx) {
        try {
            return Class.forName(CONTEXT, false, ctx.getClassLoader())
                    .getMethod("lookupContext", Context.class).invoke(null, ctx);
        } catch (Throwable t) {
            return null;
        }
    }
}
