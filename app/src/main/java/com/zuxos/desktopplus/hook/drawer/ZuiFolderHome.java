package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconPress;
import com.zuxos.desktopplus.hook.ZuiApps;
import com.zuxos.desktopplus.hook.ZuiMenu;
import com.zuxos.desktopplus.hook.taskbar.TaskbarMenu;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * One of our folders in ZUX Home's drawer, dragged onto the home screen: it lands there as ZUI's
 * own folder, with its apps, as one dragged from ZUI's drawer would.
 *
 * <p>A hold on the folder is a hold as ZUI's drawer makes one on an app: its menu first, and the
 * drag once the finger moves ({@code DragOptions.preDragCondition}, as ZUI's popups do it) - the
 * menu being ours, "Pin to the taskbar" and "Open folder". The drag is ZUI's
 * ({@code Workspace.beginDragShared}) of a new ZUI folder named as ours, from ZUI's drawer, so the
 * home screen opens for it ({@code SPRING_LOADED}) and ZUI's drop makes the folder's icon through
 * its own {@code ItemInflater} and writes it. Once ZUI has given the folder its id, its apps go
 * in through ZUI's own {@code Folder.addFolderContent}, which ranks them, writes them under the
 * folder and draws them in its icon. Our folder stays in the drawer: a drawer drag is a copy.
 */
final class ZuiFolderHome {

    private static final String OPTIONS = "com.android.launcher3.dragndrop.DragOptions";
    private static final String PRE_DRAG = OPTIONS + "$PreDragCondition";
    private static final String PREVIEW = "com.android.launcher3.graphics.DragPreviewProvider";
    private static final String DRAG_SOURCE = "com.android.launcher3.DragSource";
    private static final String DRAGGABLE = "com.android.launcher3.dragndrop.DraggableView";
    private static final String ITEM_INFO = "com.android.launcher3.model.data.ItemInfo";
    private static final String FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo";
    private static final String STATES = "com.android.launcher3.LauncherState";

    /** The folder on its way to the home screen, and its apps, until ZUI has placed it. */
    private static Object sDragged;
    private static List<Object> sApps;
    /** ZUI's icon for it, made by ZUI's drop. */
    private static View sMade;

    private ZuiFolderHome() {
    }

    static int install(ClassLoader loader) {
        int hooked = 0;
        try {
            Class<?> inflater = Reflect.findClass("com.android.launcher3.util.ItemInflater",
                    loader);
            Class<?> writer = Reflect.findClass("com.android.launcher3.model.ModelWriter", loader);
            if (inflater == null || writer == null) {
                L.w("zui folder home: ZUI's inflater or writer not found - folders stay in the "
                        + "drawer");
                return 0;
            }
            hooked += XposedBridge.hookAllMethods(inflater, "inflateItem", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sDragged != null && param.args.length > 0 && param.args[0] == sDragged
                            && param.getResult() instanceof View) {
                        sMade = (View) param.getResult();
                    }
                }
            }).size();
            hooked += XposedBridge.hookAllMethods(writer, "addOrMoveItemInDatabase",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sDragged != null && param.args.length > 0
                                    && param.args[0] == sDragged) {
                                fill();
                            }
                        }
                    }).size();
        } catch (Throwable t) {
            L.w("zui folder home: not installed (" + t + ")");
        }
        return hooked;
    }

    /**
     * A hold on our folder in ZUX Home's drawer: its menu, and ZUI's drag of it to the home
     * screen once the finger moves. False where it is not for us, so the menu alone is shown.
     */
    static boolean hold(View icon, Item folder, List<TaskbarMenu.Entry> menu) {
        try {
            Context ctx = icon.getContext();
            if (!Cfg.drawerFolderHome() || !ZuiFolders.isLauncher(ZuiFolders.lookup(ctx))) {
                return false;
            }
            ClassLoader loader = ctx.getClassLoader();
            Object launcher = ZuiApps.launcher(loader);
            Object drags = launcher == null ? null : Reflect.call(launcher, "getDragController");
            Object allApps = Class.forName(STATES, false, loader).getField("ALL_APPS").get(null);
            if (drags == null || Boolean.TRUE.equals(Reflect.call(drags, "isDragging"))
                    || !Boolean.TRUE.equals(Reflect.call(launcher, "isInState", allApps))) {
                return false;
            }
            List<Object> apps = ZuiFolders.homeApps(ctx, folder);
            if (apps.size() < 2) {
                return false;
            }
            Object info = Class.forName(FOLDER_INFO, false, loader).getConstructor()
                    .newInstance();
            info.getClass().getField("title").set(info,
                    folder.label != null ? folder.label : "Folder");
            Object options = Class.forName(OPTIONS, false, loader).getConstructor().newInstance();
            options.getClass().getField("preDragCondition").set(options,
                    menuFirst(icon, menu, loader));
            Object workspace = Reflect.call(launcher, "getWorkspace");
            Method begin = beginDragShared(workspace, loader);
            if (workspace == null || begin == null) {
                L.i("zui folder home: ZUI's drag not found");
                return false;
            }
            sDragged = info;
            sApps = apps;
            sMade = null;
            Object draggable = Class.forName(DRAGGABLE, false, loader).isInstance(icon)
                    ? icon : null;
            Object preview = Class.forName(PREVIEW, false, loader).getConstructor(View.class)
                    .newInstance(icon);
            begin.invoke(workspace, icon, draggable, Reflect.call(launcher, "getAppsView"), info,
                    preview, options);
            L.i("zui folder home: " + folder.label + " held, " + apps.size()
                    + " apps, for the home screen");
            return true;
        } catch (Throwable t) {
            sDragged = null;
            L.w("zui folder home: not dragged (" + t + ")");
            return false;
        }
    }

    /**
     * {@code Workspace.beginDragShared(View, DraggableView, DragSource, ItemInfo,
     * DragPreviewProvider, DragOptions)}: the one that takes the item dragged as it is given.
     */
    private static Method beginDragShared(Object workspace, ClassLoader loader) throws Exception {
        if (workspace == null) {
            return null;
        }
        Class<?>[] want = {View.class, Class.forName(DRAGGABLE, false, loader),
                Class.forName(DRAG_SOURCE, false, loader), Class.forName(ITEM_INFO, false, loader),
                Class.forName(PREVIEW, false, loader), Class.forName(OPTIONS, false, loader)};
        for (Class<?> c = workspace.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("beginDragShared", want);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // Up the hierarchy.
            }
        }
        return null;
    }

    /**
     * ZUI's pre-drag, as its popups make it: our menu as the hold begins, the drag only once the
     * finger has moved as far as ZUI's own popups let it
     * ({@code deep_shortcuts_start_drag_threshold}), and a hold let go without moving leaves the
     * menu up.
     */
    private static Object menuFirst(View icon, List<TaskbarMenu.Entry> menu, ClassLoader loader)
            throws Exception {
        Context ctx = icon.getContext();
        int id = ctx.getResources().getIdentifier("deep_shortcuts_start_drag_threshold", "dimen",
                ctx.getPackageName());
        double threshold = id != 0 ? ctx.getResources().getDimensionPixelSize(id)
                : 16 * ctx.getResources().getDisplayMetrics().density;
        Class<?> condition = Class.forName(PRE_DRAG, false, loader);
        return Proxy.newProxyInstance(loader, new Class<?>[]{condition}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "shouldStartDrag":
                    return args != null && args[0] instanceof Double
                            && (Double) args[0] > threshold;
                case "onPreDragStart":
                    ZuiMenu.show(icon, menu);
                    return null;
                case "onPreDragEnd":
                    IconPress.release(icon);
                    return null;
                case "getDragOffset":
                    return new android.graphics.Point(0, 0);
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && proxy == args[0];
                case "toString":
                    return "ZuiFolderHome.menuFirst";
                default:
                    return null;
            }
        });
    }

    /** ZUI has given the folder its id: its apps go in through ZUI's own folder. */
    private static void fill() {
        Object info = sDragged;
        List<Object> apps = sApps;
        View made = sMade;
        sDragged = null;
        sApps = null;
        sMade = null;
        try {
            Object folder = made == null ? null : Reflect.call(made, "getFolder");
            Method add = null;
            for (Class<?> c = folder == null ? null : folder.getClass(); c != null && add == null;
                    c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("addFolderContent") && m.getParameterCount() == 3) {
                        add = m;
                        break;
                    }
                }
            }
            if (add == null || apps == null) {
                L.w("zui folder home: the folder placed, but ZUI's folder to fill not found");
                return;
            }
            add.setAccessible(true);
            int rank = 0;
            for (Object app : new ArrayList<>(apps)) {
                add.invoke(folder, app, rank++, false);
            }
            L.i("zui folder home: placed on the home screen with " + rank + " apps (folder "
                    + Reflect.field(info, "id") + ")");
        } catch (Throwable t) {
            L.w("zui folder home: the folder's apps not added (" + t + ")");
        }
    }
}
