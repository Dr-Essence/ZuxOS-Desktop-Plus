package com.zuxos.desktopplus.hook.drawer;

import android.view.Display;
import android.view.KeyEvent;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Health;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.hook.Mirror;
import com.zuxos.desktopplus.hook.ZuiFocus;
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
 * Its press and release are always taken, whatever is focused, so no click ever follows.
 *
 * <p>In an open menu the arrows are kept to its rows, each marked as focused ({@link ZuiFocus}).
 * A picks a row (the controller's centre key), and B (back) or Triangle again closes the popup.
 */
public final class DrawerKeys {

    private static final String POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";

    private static boolean sSaid;
    private static boolean sSaidFailed;

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
                // Taken whatever it finds: left to Android, Triangle falls back to SPACE and
                // clicks the icon, opening the app or folder whose menu was asked for (owner on
                // 0.0.277).
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    menu(layer);
                }
                return true;
            }
            int direction = ZuiFocus.direction(code);
            return direction != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                    && ZuiFocus.keepIn(layer, direction);
        } catch (Throwable t) {
            L.d("drawer keys: a key left to ZUI (" + t + ")");
            return false;
        }
    }

    /**
     * The menu of the focused icon, opened as a hold opens it, or the open menu closed again:
     * an app, one of our folders, or one of ZUI's own folders on the home screen (its hold menu,
     * {@code FolderIcon.startLongPressAction}: neither ours nor an app, it was clicked open).
     */
    private static void menu(View layer) {
        View focused = layer.findFocus();
        Object activity = ZuiFocus.lookup(layer.getContext());
        Object open = activity == null ? null : ZuiFocus.openPopup(layer, activity);
        if (open != null) {
            // Triangle again: the menu goes, as B takes it away.
            Reflect.call(open, "close", true);
            return;
        }
        if (focused == null || activity == null) {
            return;
        }
        boolean home = ZuiFolders.isLauncher(activity);
        for (View v = focused; v != null && v != layer; v = ZuiFocus.parentOf(v)) {
            boolean anyFolder = ZuiFolders.isZuiFolder(v);
            Item folder = anyFolder ? ZuiFolders.folderOfIcon(v) : null;
            String pkg = anyFolder ? null : IconInfo.packageOf(v.getTag());
            if (!anyFolder && pkg == null) {
                continue;
            }
            String what = folder != null ? folder.label : anyFolder ? "a folder of ZUI's" : pkg;
            try {
                boolean shown = folder != null
                        ? home && showForIcon(v)
                        || ZuiMenu.show(v, DrawerHold.menuEntries(v, folder))
                        : home ? showForIcon(v) : showOnTaskbar(v, activity);
                Object popup = ZuiFocus.openPopup(layer, activity);
                // Opened by a key, the popup may hold the focus itself rather than a row.
                ZuiFocus.focusFirst(popup);
                ZuiFocus.markRows(popup);
                say("the menu of " + what, shown, null);
            } catch (Throwable t) {
                say("the menu of " + what, false, t);
            }
            return;
        }
    }

    /** The first menu opened, and the first that could not be, each told once. */
    private static void say(String what, boolean shown, Throwable why) {
        if (shown ? !sSaid : !sSaidFailed) {
            if (shown) {
                sSaid = true;
            } else {
                sSaidFailed = true;
            }
            L.i("drawer keys: Triangle - " + what + (shown ? " opened" : " not opened"
                    + (why != null ? " (" + why + ")" : "")));
        }
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
}
