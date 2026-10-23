package com.zuxos.desktopplus.hook.drawer;

import android.content.ComponentName;
import android.content.Context;
import android.view.View;

import com.zuxos.desktopplus.core.AppCtx;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.hook.IconInfo;
import com.zuxos.desktopplus.logic.DrawerArrange;
import com.zuxos.desktopplus.model.DrawerStore;
import com.zuxos.desktopplus.model.Item;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Our model behind ZUI's folders of ours ({@link ZuiFolders}): which of our folders and apps each
 * of ZUI's ids and items stands for, and what a change made in ZUI's folder does to our drawer -
 * renamed, rearranged, an app dropped in, taken out or uninstalled. Every change goes to our
 * store ({@link DrawerStore}), never ZUI's database ({@link ZuiFolderWrites}).
 */
public final class ZuiFolderModel {

    /** Our folder behind each id handed to ZUI. */
    private static final Map<Integer, Item> BY_ID = new HashMap<>();
    /** The app of ours each of ZUI's items inside our folders stands for. */
    static final Map<Object, Item> CHILDREN = new WeakHashMap<>();

    private ZuiFolderModel() {
    }

    /** Our folder behind an id handed to ZUI, or null. */
    static synchronized Item folderOfId(int id) {
        return BY_ID.get(id);
    }

    /** The id handed to ZUI for one of our folders: the one it was given before, or a new one. */
    static synchronized int idOf(Item folder) {
        for (Map.Entry<Integer, Item> e : BY_ID.entrySet()) {
            if (e.getValue().id.equals(folder.id)) {
                e.setValue(folder);
                return e.getKey();
            }
        }
        int id = ZuiFolders.ID_BASE - BY_ID.size();
        BY_ID.put(id, folder);
        return id;
    }

    /** One of ZUI's items made for an app of ours inside one of our folders. */
    static void noteChild(Object item, Item child) {
        CHILDREN.put(item, child);
    }

    /** The app of ours one of ZUI's items inside our folders stands for, or null. */
    static Item childOf(Object item) {
        return item == null ? null : CHILDREN.get(item);
    }

    /**
     * ZUI's new folder for one of ours on its way to the home screen from the bar: placed by ZUI,
     * filled with its apps as one from the drawer is. Null where it cannot go.
     */
    public static Object forHome(Context home, Item folder) {
        return ZuiFolderHome.forHome(home, folder);
    }

    /** Our folder behind one of ZUI's folder icons of ours, or null. */
    public static Item folderOfIcon(View view) {
        Object tag = view == null ? null : view.getTag();
        Object id = tag == null ? null : Reflect.field(tag, "id");
        return id instanceof Integer && (Integer) id <= ZuiFolders.ID_BASE
                ? folderOfId((Integer) id) : null;
    }

    /**
     * "Remove from folder" for one of ZUI's items inside our folders - for ZUI's app popup - or
     * null for any other item.
     */
    public static Runnable removeAction(Object item) {
        // The app as the menu is built: ZUX Home's drawer hold notes it under the drawer's own
        // entry only while the hold is handled, so a lookup at the tap found nothing.
        Item child = childOf(item);
        return child == null ? null : () -> removeFromFolder(child);
    }

    /**
     * "Uninstall" for one of ZUI's items inside our folders: ZUI's own
     * ({@code Utilities.startUninstallActivity}, as its Uninstall shortcut calls it, with ZUI's
     * Launcher), or null for any other item. ZUI's own Uninstall leaves items outside the home
     * screen out, so it is never offered for ours.
     */
    public static Runnable uninstallAction(Object item) {
        if (childOf(item) == null) {
            return null;
        }
        return () -> {
            try {
                Object home = ZuiFolders.launcher();
                for (Method m : Class.forName("com.android.launcher3.Utilities", false,
                        item.getClass().getClassLoader()).getMethods()) {
                    if (m.getName().equals("startUninstallActivity")
                            && m.getParameterCount() == 2 && home != null) {
                        m.invoke(null, home, item);
                        return;
                    }
                }
                L.w("zui folders: ZUI's uninstall not reachable");
            } catch (Throwable t) {
                L.w("zui folders: uninstall failed (" + t + ")");
            }
        };
    }

    /**
     * Takes an app out of the folder it is in, to the place after the folder, by the drawer's
     * own rule ({@link DrawerArrange#takeOut}: a folder left with one app gives its place to it),
     * and the drawer shows it at once.
     */
    static void removeFromFolder(Item child) {
        String key = child.key();
        boolean done = NativeDrawerHooks.rearrange("took " + key + " out of its folder",
                (order, folders) -> {
                    Item folder = DrawerArrange.folderOf(folders, key);
                    int at = folder == null ? -1 : order.indexOf(folder.key());
                    return folder != null
                            && DrawerArrange.takeOut(order, folders, key, at + 1);
                });
        if (!done) {
            // No drawer list seen yet since ZUX Home started (the bar's folder, held first): the
            // folder alone loses the app, and the drawer sorts it in when it next lays out.
            Context ctx = AppCtx.get();
            DrawerStore store = ctx == null ? null : DrawerStore.shared(ctx);
            Item folder = store == null ? null : store.folderContaining(key);
            if (folder != null) {
                folder.children.remove(child);
                if (folder.children.size() <= 1) {
                    // One app left is not a folder: both go back to the list.
                    folder.children.clear();
                    store.folders().remove(folder);
                }
                store.save();
                NativeDrawerHooks.refreshList();
                L.i("zui folders: took " + key + " out of its folder, before any drawer was seen");
            } else {
                L.i("zui folders: " + key + " not taken out - no folder holds it");
            }
        }
        closeOpenFolders();
    }

    /** Renamed in ZUI's folder: our folder takes the name. */
    static void renamed(int id, CharSequence title) {
        Item folder = folderOfId(id);
        Context ctx = AppCtx.get();
        String name = title == null ? "" : title.toString();
        if (folder == null || ctx == null || name.equals(folder.label)) {
            return;
        }
        folder.label = name;
        saveKeepingIcons(ctx, folder);
        L.i("zui folders: renamed in ZUI's folder - saved to ours");
    }

    /** Rearranged in ZUI's folder: our folder takes the order of the ranks ZUI gave its apps. */
    static void reordered(int id, List<?> items) {
        Item folder = folderOfId(id);
        Context ctx = AppCtx.get();
        if (folder == null || ctx == null) {
            return;
        }
        // The folder's whole contents where they can be had: ZUI saves only the apps whose
        // place changed (Folder.updateItemLocationsInDatabaseBatch), and read alone those left
        // every other app to go after them - a move inside a folder sent the rest to its end.
        List<?> all = contentsOf(id);
        if (all != null) {
            items = all;
        }
        // By key: ZUI's apps map to our apps as they were when its folder was built - the bar's
        // and the drawer's builds hold other copies of the same apps. Matched as objects, every
        // app of a folder pinned to the bar was added again each time ZUI bound it.
        Map<String, Item> ours = new java.util.LinkedHashMap<>();
        for (Item child : folder.children) {
            ours.putIfAbsent(child.key(), child);
        }
        java.util.TreeMap<Integer, Item> byRank = new java.util.TreeMap<>();
        for (Object item : items) {
            Item child = childOf(item);
            Object rank = Reflect.field(item, "rank");
            Item mine = child == null ? null : ours.get(child.key());
            if (mine != null && rank instanceof Integer && !byRank.containsValue(mine)) {
                byRank.put((Integer) rank, mine);
            }
        }
        // ZUI's order for the apps it shows; any of ours it does not (not installed) after them.
        List<Item> now = new java.util.ArrayList<>(byRank.values());
        for (Item child : ours.values()) {
            if (!now.contains(child)) {
                now.add(child);
            }
        }
        if (!now.equals(folder.children)) {
            folder.children.clear();
            folder.children.addAll(now);
            saveKeepingIcons(ctx, folder);
        }
    }

    /** What ZUI's open folder of ours holds, every app, or null where it cannot be read. */
    private static List<?> contentsOf(int id) {
        for (Map<String, ZuiFolders.Built> mine : ZuiFolders.BUILT.values()) {
            for (ZuiFolders.Built built : mine.values()) {
                Object info = built.icon.getTag();
                Object builtId = info == null ? null : Reflect.field(info, "id");
                if (builtId instanceof Integer && (Integer) builtId == id) {
                    Object contents = Reflect.call(info, "getContents");
                    if (contents instanceof List) {
                        return new java.util.ArrayList<>((List<?>) contents);
                    }
                }
            }
        }
        return null;
    }

    /** The app filed into one of our folders by the last drop into ZUI's folder, for the log. */
    static String sLastFiled;
    /** Where in our folder {@link #sLastFiled} went: the apps chosen with it go after it. */
    static int sLastFiledAt;

    /**
     * ZUI's folder of ours took a dragged app ({@code Folder.onDrop}, sprung open under it in the
     * drawer): the app goes into our folder at the place it was let go, out of the drawer's
     * order, and ZUI's folder, which already shows it, is kept as it is. True when it went in.
     */
    static boolean filedByDrop(int id, Object item) {
        try {
            Item folder = folderOfId(id);
            Context ctx = AppCtx.get();
            if (folder == null || ctx == null || item == null || childOf(item) != null) {
                return false;
            }
            Object target = Reflect.call(item, "getTargetComponent");
            Object user = Reflect.field(item, "user");
            if (!(target instanceof ComponentName)) {
                return false;
            }
            ComponentName component = (ComponentName) target;
            Object title = Reflect.field(item, "title");
            Item app = Item.app(component.getPackageName(), component.getClassName(),
                    IconInfo.serialOf(ctx, user instanceof android.os.UserHandle
                            ? (android.os.UserHandle) user : null),
                    title != null ? title.toString() : component.getPackageName());
            Object rankValue = Reflect.field(item, "rank");
            int rank = rankValue instanceof Integer ? (Integer) rankValue
                    : folder.children.size();
            String folderId = folder.id;
            boolean[] filed = new boolean[1];
            NativeDrawerHooks.rearrange("put " + app.key() + " into a folder at " + rank,
                    (order, folders) -> {
                        for (Item f : folders) {
                            if (f.id.equals(folderId)) {
                                filed[0] = DrawerArrange.fileIntoAt(order, folders, f, app, rank);
                                if (filed[0]) {
                                    keepBuilt(f);
                                    for (int i = 0; i < f.children.size(); i++) {
                                        Item child = f.children.get(i);
                                        if (child.key().equals(app.key())) {
                                            noteChild(item, child);
                                            sLastFiledAt = i;
                                        }
                                    }
                                }
                                return filed[0];
                            }
                        }
                        return false;
                    });
            if (filed[0]) {
                sLastFiled = app.key();
            }
            return filed[0];
        } catch (Throwable t) {
            L.w("zui folders: a dropped app not filed (" + t + ")");
            return false;
        }
    }

    /**
     * The apps chosen with a dragged one ({@link DrawerSelect}) into the folder of ours that took
     * it ({@code Folder.onDrop}), after it and in their order. ZUI's own multi-drag files its
     * chosen items after a drop into a folder ({@code MultiDragSupport.onDropFolderSuccess}); ours
     * are not ZUI's, so only the dragged app went in and the others went back to their cells. Into
     * our model as the dropped app went ({@link #filedByDrop}), and shown by ZUI's open folder as
     * ZUI's Add apps shows its picks ({@link ZuiFolderAdd}): the drawer's own entry made into ZUI's
     * item for a folder ({@code makeWorkspaceItem}, as ZUI's drop makes it) and added through ZUI's
     * {@code addFolderContent}, its writes kept out of ZUI's database ({@link ZuiFolderWrites}).
     * How many went in.
     */
    static int fileCarried(Object zuiFolder, List<String> keys) {
        try {
            Object info = Reflect.field(zuiFolder, "mInfo");
            Object id = info == null ? null : Reflect.field(info, "id");
            Item folder = id instanceof Integer ? folderOfId((Integer) id) : null;
            if (folder == null || keys.isEmpty() || !(zuiFolder instanceof View)) {
                return 0;
            }
            String folderId = folder.id;
            int after = sLastFiledAt;
            List<Item> filed = new java.util.ArrayList<>();
            NativeDrawerHooks.rearrange("put " + keys.size() + " chosen into a folder after "
                    + after, (order, folders) -> {
                        for (Item f : folders) {
                            if (!f.id.equals(folderId)) {
                                continue;
                            }
                            for (String key : keys) {
                                Item app = DrawerArrange.appOf(key, NativeDrawerHooks.labelOf(key));
                                if (app != null && DrawerArrange.fileIntoAt(order, folders, f, app,
                                        after + 1 + filed.size())) {
                                    filed.add(app);
                                }
                            }
                            if (!filed.isEmpty()) {
                                keepBuilt(f);
                            }
                            return !filed.isEmpty();
                        }
                        return false;
                    });
            Context ctx = ((View) zuiFolder).getContext();
            Class<?> itemInfo = Class.forName("com.android.launcher3.model.data.ItemInfo", false,
                    zuiFolder.getClass().getClassLoader());
            Method add = zuiFolder.getClass().getMethod("addFolderContent", itemInfo, int.class,
                    boolean.class);
            int shown = 0;
            for (Item app : filed) {
                // From ZUI's app store as our folders' apps are: the drawer's own list leaves the
                // apps in folders out, so once filed they were not found and not shown until the
                // drawer was opened again.
                Object entry = ZuiFolders.sFiled.get(app.key());
                Object made = entry == null ? null : entry.getClass()
                        .getMethod("makeWorkspaceItem", Context.class).invoke(entry, ctx);
                Item child = null;
                for (Item c : folder.children) {
                    if (c.key().equals(app.key())) {
                        child = c;
                    }
                }
                if (made == null || child == null) {
                    continue;
                }
                ZuiFolders.setInt(made, "container", (Integer) id);
                noteChild(made, child);
                add.invoke(zuiFolder, made, folder.children.indexOf(child), true);
                shown++;
            }
            L.i("zui folders: " + filed.size() + " chosen with the dropped app into "
                    + folder.label + ", " + shown + " shown by ZUI's folder");
            return filed.size();
        } catch (Throwable t) {
            L.w("zui folders: the chosen apps not filed with the dropped one (" + t + ")");
            return 0;
        }
    }

    /**
     * Our folder's ZUI icons made again from our model on the drawer's next pass: ZUI's folder
     * took an app off its pages for a drag that left it, and our model says where the app is now.
     */
    static void rebuild(Item folder) {
        for (Map<String, ZuiFolders.Built> mine : ZuiFolders.BUILT.values()) {
            ZuiFolders.Built built = mine.get(folder.id);
            if (built != null) {
                built.content = "";
            }
        }
        NativeDrawerHooks.refreshList();
    }

    /** Our folder's ZUI icons are kept as they are for its new contents: ZUI shows them already. */
    private static void keepBuilt(Item folder) {
        String content = ZuiFolders.contentOf(folder);
        for (Map<String, ZuiFolders.Built> mine : ZuiFolders.BUILT.values()) {
            ZuiFolders.Built built = mine.get(folder.id);
            if (built != null) {
                built.content = content;
            }
        }
    }

    /** Saves a change made in ZUI's folder itself, which already shows it: no rebuild for it. */
    static void saveKeepingIcons(Context ctx, Item folder) {
        DrawerStore.shared(ctx).save();
        keepBuilt(folder);
    }

    static void closeOpenFolders() {
        for (Map<String, ZuiFolders.Built> mine : ZuiFolders.BUILT.values()) {
            for (ZuiFolders.Built built : mine.values()) {
                if (ZuiFolders.isOpen(built)) {
                    try {
                        Object folder = built.icon.getClass().getMethod("getFolder")
                                .invoke(built.icon);
                        folder.getClass().getMethod("close", boolean.class).invoke(folder, true);
                    } catch (Throwable t) {
                        L.d("zui folders: could not close (" + t + ")");
                    }
                }
            }
        }
    }
}
