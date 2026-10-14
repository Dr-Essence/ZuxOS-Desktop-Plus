package com.zuxos.desktopplus.hook.home;

import android.view.View;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Remove folder, for a folder on ZUI's home screen: its apps go back onto the home screen and the
 * folder goes.
 *
 * <p>ZUI has no such action; it takes a folder away when one app is left in it
 * ({@code FolderIcon.replaceFolderWithFinalItem}, as its own multi-drag does,
 * {@code MultiDragSupport.I}). So the apps leave it as ZUI moves an app out of a folder, each to
 * the first free cell from the folder's page on ({@code CellLayout.findCellForSpan}): out of the
 * folder ({@code Folder.removeFolderContent}), onto the page in ZUI's database
 * ({@code ModelWriter.moveItemInDatabase}) and on screen ({@code Launcher.createShortcut},
 * {@code Workspace.addInScreen}); the last one then takes the folder's own cell, by ZUI. Where
 * the pages are full, what does not fit stays in the folder.
 */
public final class HomeFolderRemove {

    private static final String ITEM_INFO = "com.android.launcher3.model.data.ItemInfo";
    /** ZUI's home screen container ({@code LauncherSettings.Favorites.CONTAINER_DESKTOP}). */
    private static final int DESKTOP = -100;

    private HomeFolderRemove() {
    }

    /** Whether Remove folder belongs in the menu of this folder icon. */
    public static boolean offered(View icon, Object info) {
        Object container = Reflect.field(info, "container");
        return Cfg.homeFolderRemove() && info != null
                && info.getClass().getName().endsWith(".FolderInfo")
                && container instanceof Integer && (Integer) container == DESKTOP
                && icon.getDisplay() != null && icon.getDisplay().getDisplayId() == 0;
    }

    /** The folder's apps onto the home screen, and the folder gone. */
    public static void remove(Object launcher, View icon) {
        try {
            Object info = icon.getTag();
            Object folder = Reflect.call(icon, "getFolder");
            Object workspace = Reflect.call(launcher, "getWorkspace");
            Object writer = Reflect.call(launcher, "getModelWriter");
            Object contents = Reflect.call(info, "getContents");
            Object screen = Reflect.field(info, "screenId");
            if (folder == null || workspace == null || writer == null
                    || !(contents instanceof List) || !(screen instanceof Integer)) {
                L.w("zui home folder: Remove folder - ZUI's folder parts not found");
                return;
            }
            ClassLoader loader = icon.getContext().getClassLoader();
            Class<?> itemInfo = Class.forName(ITEM_INFO, false, loader);
            Method addInScreen = workspace.getClass().getMethod("addInScreen", View.class,
                    itemInfo);
            List<Object> apps = new ArrayList<>((List<?>) contents);
            List<Integer> screens = screensFrom(workspace, (Integer) screen);
            int moved = 0;
            // The first stays for ZUI to put in the folder's own cell.
            for (int i = 1; i < apps.size(); i++) {
                Object app = apps.get(i);
                int[] cell = new int[2];
                int on = freeCell(workspace, screens, cell);
                if (on == Integer.MIN_VALUE) {
                    L.i("zui home folder: Remove folder - the home screen is full, "
                            + (apps.size() - i) + " app(s) stay in the folder");
                    break;
                }
                Object one = single(itemInfo, app);
                folder.getClass().getMethod("removeFolderContent", boolean.class, one.getClass())
                        .invoke(folder, false, one);
                writer.getClass().getMethod("moveItemInDatabase", itemInfo, int.class,
                        int.class, int.class, int.class)
                        .invoke(writer, app, DESKTOP, on, cell[0], cell[1]);
                Object view = Reflect.call(launcher, "createShortcut", app);
                if (view instanceof View) {
                    addInScreen.invoke(workspace, view, app);
                }
                moved++;
            }
            Reflect.call(icon, "replaceFolderWithFinalItem");
            L.i("zui home folder: Remove folder - " + moved + " app(s) back on the home screen");
        } catch (Throwable t) {
            L.w("zui home folder: Remove folder not done (" + t + ")");
        }
    }

    /** One app as ZUI's array of items. */
    private static Object single(Class<?> itemInfo, Object app) {
        Object array = java.lang.reflect.Array.newInstance(itemInfo, 1);
        java.lang.reflect.Array.set(array, 0, app);
        return array;
    }

    /** The home screen's pages, the folder's first and then the rest in order. */
    private static List<Integer> screensFrom(Object workspace, int first) {
        List<Integer> screens = new ArrayList<>();
        screens.add(first);
        Object order = Reflect.call(workspace, "getScreenOrder");
        Object size = Reflect.call(order, "size");
        if (size instanceof Integer) {
            for (int i = 0; i < (Integer) size; i++) {
                Object id = Reflect.call(order, "get", i);
                if (id instanceof Integer && (Integer) id != first) {
                    screens.add((Integer) id);
                }
            }
        }
        return screens;
    }

    /** A free cell on the first page with one, into {@code cell}; its page, or MIN_VALUE. */
    private static int freeCell(Object workspace, List<Integer> screens, int[] cell) {
        for (int id : screens) {
            Object page = Reflect.call(workspace, "getScreenWithId", id);
            try {
                if (page != null && Boolean.TRUE.equals(page.getClass()
                        .getMethod("findCellForSpan", int[].class, int.class, int.class)
                        .invoke(page, cell, 1, 1))) {
                    return id;
                }
            } catch (Throwable t) {
                L.d("zui home folder: page " + id + " not read (" + t + ")");
            }
        }
        return Integer.MIN_VALUE;
    }
}
