package com.zuxos.desktopplus.hook.drawer;

import android.content.Context;
import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * One of our folders in ZUX Home's drawer, dragged onto the home screen: it lands there as ZUI's
 * own folder, with its apps, as one dragged from ZUI's drawer would.
 *
 * <p>A hold on the folder is a hold as ZUI's drawer makes one: ZUI's own menu for a folder first
 * ({@code Workspace.beginDragShared} asks the folder icon for it, {@code startLongPressAction}),
 * with our "Pin to the taskbar" in it ({@code ZuiAppRows}) and without its Enlarge
 * ({@link ZuiFolders}), and the drag once the finger moves. The drag is ZUI's
 * ({@code Workspace.beginDragShared}) of a new ZUI folder named as ours, from ZUI's drawer, so the
 * home screen opens for it ({@code SPRING_LOADED}) and ZUI's drop makes the folder's icon through
 * its own {@code ItemInflater} and writes it. Once ZUI has given the folder its id, its apps go
 * in through ZUI's own {@code Folder.addFolderContent}, which ranks them, writes them under the
 * folder and draws them in its icon. Our folder stays in the drawer: a drawer drag is a copy.
 */
final class ZuiFolderHome {

    private static final String OPTIONS = "com.android.launcher3.dragndrop.DragOptions";
    private static final String PREVIEW = "com.android.launcher3.graphics.DragPreviewProvider";
    private static final String DRAG_SOURCE = "com.android.launcher3.DragSource";
    private static final String DRAGGABLE = "com.android.launcher3.dragndrop.DraggableView";
    private static final String ITEM_INFO = "com.android.launcher3.model.data.ItemInfo";
    private static final String FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo";

    /** The folder on its way to the home screen, and its apps, until ZUI has placed it. */
    private static Object sDragged;
    private static Item sFolder;
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
            Class<?> workspace = Reflect.findClass("com.android.launcher3.Workspace", loader);
            if (workspace != null) {
                hooked += XposedBridge.hookAllMethods(workspace, "onDragEnd",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                notPlaced();
                            }
                        }).size();
            }
        } catch (Throwable t) {
            L.w("zui folder home: not installed (" + t + ")");
        }
        return hooked;
    }

    /**
     * A hold on our folder in ZUX Home's drawer: ZUI's menu for it, and ZUI's drag of it to the
     * home screen once the finger moves. False where it is not for us, so our menu is shown.
     */
    static boolean hold(View icon, Item folder) {
        try {
            Context ctx = icon.getContext();
            if (!Cfg.drawerFolderHome()) {
                return false;
            }
            // The launcher whose drawer holds the icon - ZUX Home's, not the taskbar's.
            Object launcher = ZuiFolders.lookup(ctx);
            if (!ZuiFolders.isLauncher(launcher)) {
                return false;
            }
            ClassLoader loader = ctx.getClassLoader();
            Object drags = Reflect.call(launcher, "getDragController");
            // ZUI's own question (Launcher.isInAllAppsState): its isInState is a default method
            // of an interface, which a search of the classes never finds.
            if (drags == null || Boolean.TRUE.equals(Reflect.call(drags, "isDragging"))
                    || !Boolean.TRUE.equals(Reflect.call(launcher, "isInAllAppsState"))) {
                L.i("zui folder home: " + folder.label + " held, not dragged - "
                        + (drags == null ? "ZUI's drag controller not found"
                        : Boolean.TRUE.equals(Reflect.call(drags, "isDragging"))
                        ? "a drag is under way" : "the drawer is not open"));
                return false;
            }
            List<Object> apps = ZuiFolders.homeApps(ctx, folder);
            if (apps.size() < 2) {
                L.i("zui folder home: " + folder.label + " held, not dragged - " + apps.size()
                        + " of its apps can go on the home screen");
                return false;
            }
            Object info = Class.forName(FOLDER_INFO, false, loader).getConstructor()
                    .newInstance();
            info.getClass().getField("title").set(info,
                    folder.label != null ? folder.label : "Folder");
            // No pre-drag of ours: ZUI sets its folder menu as the drag's pre-drag itself.
            Object options = Class.forName(OPTIONS, false, loader).getConstructor().newInstance();
            Object workspace = Reflect.call(launcher, "getWorkspace");
            Method begin = beginDragShared(workspace, loader);
            if (workspace == null || begin == null) {
                L.i("zui folder home: ZUI's drag not found");
                return false;
            }
            sDragged = info;
            sFolder = folder;
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

    /** Our folder whose drag is the one {@code dragged} names, or null for any other drag. */
    static Item folderDragged(Object dragged) {
        return dragged != null && dragged == sDragged ? sFolder : null;
    }

    /**
     * The drag is over and ZUI placed no folder for it - let go in the drawer, or cancelled: the
     * folder waiting for ZUI's writer goes, said once for the log.
     */
    private static void notPlaced() {
        if (sDragged != null) {
            L.i("zui folder home: " + (sFolder != null ? sFolder.label : "the folder")
                    + " not placed on the home screen");
            sDragged = null;
            sApps = null;
            sMade = null;
        }
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
