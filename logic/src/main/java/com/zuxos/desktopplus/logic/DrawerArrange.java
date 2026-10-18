package com.zuxos.desktopplus.logic;

import com.zuxos.desktopplus.model.Item;

import java.util.List;

/**
 * Rearranging a drawer's entries the way ZUI rearranges its home screen: moved to a new place,
 * dropped onto another app to make a folder, dropped onto a folder to go in it, or taken out of
 * a folder to a place of its own.
 *
 * <p>Works on the drawer's saved order (entry keys, {@link Item#key()}, folders as
 * {@code folder:<id>}) and its folders, both changed in place. An app is in the drawer once:
 * either in the order or in one folder. A folder left with one app is no folder: the app takes
 * its place in the order.
 */
public final class DrawerArrange {

    private DrawerArrange() {
    }

    /**
     * Moves {@code key} to {@code index} of {@code order}, the index counted in the order as it
     * stands before the move. Nothing happens for a key not in the order.
     */
    public static boolean move(List<String> order, String key, int index) {
        int from = order.indexOf(key);
        if (from < 0) {
            return false;
        }
        order.remove(from);
        order.add(clamp(index, order.size()), key);
        return true;
    }

    /**
     * Folds {@code dragged} and {@code target} into a new folder named {@code label}, in the
     * place the target had. The dragged app leaves the order, or the folder it was in. Null when
     * the two cannot make a folder: either is a folder, or they are the same app.
     */
    public static Item merge(List<String> order, List<Item> folders, Item dragged, Item target,
            String label) {
        if (dragged == null || target == null || dragged.type == Item.TYPE_FOLDER
                || target.type == Item.TYPE_FOLDER || dragged.key().equals(target.key())) {
            return null;
        }
        leave(order, folders, dragged.key());
        int at = order.indexOf(target.key());
        leave(order, folders, target.key());
        Item folder = Item.folder(label);
        folder.children.add(copyOf(target));
        folder.children.add(copyOf(dragged));
        folders.add(folder);
        order.add(at < 0 ? order.size() : clamp(at, order.size()), folder.key());
        return folder;
    }

    /**
     * Puts {@code item} into {@code folder}, at its end. The item leaves the order, or the other
     * folder it was in. False when it is a folder, or already in this one.
     */
    public static boolean fileInto(List<String> order, List<Item> folders, Item folder,
            Item item) {
        if (folder == null || item == null || item.type == Item.TYPE_FOLDER
                || folder.type != Item.TYPE_FOLDER || indexIn(folder, item.key()) >= 0) {
            return false;
        }
        leave(order, folders, item.key());
        folder.children.add(copyOf(item));
        return true;
    }

    /**
     * The entries {@code others}, in their order, moved to just after {@code lead}: chosen
     * together and dragged by one of them. Keys not in the order, and {@code lead} itself, are
     * left out. False when nothing moves.
     */
    public static boolean carryAfter(List<String> order, String lead, List<String> others) {
        if (!order.contains(lead)) {
            return false;
        }
        List<String> moving = new java.util.ArrayList<>();
        for (String key : others) {
            if (!key.equals(lead) && order.contains(key) && !moving.contains(key)) {
                moving.add(key);
            }
        }
        if (moving.isEmpty()) {
            return false;
        }
        order.removeAll(moving);
        order.addAll(order.indexOf(lead) + 1, moving);
        return true;
    }

    /**
     * Files {@code item} into {@code folder} at {@code rank} of its apps (clamped), leaving the
     * order or any other folder it was in; an app already in the folder moves to {@code rank}.
     * False when it cannot go in.
     */
    public static boolean fileIntoAt(List<String> order, List<Item> folders, Item folder,
            Item item, int rank) {
        if (folder == null || item == null || item.type == Item.TYPE_FOLDER
                || folder.type != Item.TYPE_FOLDER) {
            return false;
        }
        int at = indexIn(folder, item.key());
        Item child;
        if (at >= 0) {
            child = folder.children.remove(at);
        } else {
            leave(order, folders, item.key());
            child = copyOf(item);
        }
        folder.children.add(clamp(rank, folder.children.size()), child);
        return true;
    }

    /**
     * Takes the app {@code key} out of whichever folder holds it to {@code index} of the order,
     * counted as the order stands before it arrives. False when no folder holds it.
     */
    public static boolean takeOut(List<String> order, List<Item> folders, String key, int index) {
        Item folder = folderOf(folders, key);
        if (folder == null) {
            return false;
        }
        // A folder left with one app gives its place to that app: the order's length, and so the
        // drop place, stays as it was.
        leave(order, folders, key);
        order.add(clamp(index, order.size()), key);
        return true;
    }

    /**
     * Every app once: a second copy of an app, in the same folder or another, goes. The first
     * stays where it is. A folder then left with one app or none is no folder: its app takes its
     * place in the order. How many copies and folders went.
     */
    public static int dedupe(List<String> order, List<Item> folders) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        int removed = 0;
        for (Item folder : folders) {
            for (java.util.Iterator<Item> it = folder.children.iterator(); it.hasNext(); ) {
                if (!seen.add(it.next().key())) {
                    it.remove();
                    removed++;
                }
            }
        }
        for (Item folder : new java.util.ArrayList<>(folders)) {
            if (folder.children.size() <= 1) {
                breakUp(order, folders, folder.key());
                removed++;
            }
        }
        return removed;
    }

    /**
     * The folder {@code folderKey} is no folder any more: its apps take its place in the order,
     * in their order (one already in the order stays where it is). False when no folder has the
     * key.
     */
    public static boolean breakUp(List<String> order, List<Item> folders, String folderKey) {
        Item folder = null;
        for (Item f : folders) {
            if (f.key().equals(folderKey)) {
                folder = f;
            }
        }
        if (folder == null) {
            return false;
        }
        folders.remove(folder);
        int at = order.indexOf(folderKey);
        if (at >= 0) {
            order.remove(at);
        } else {
            at = order.size();
        }
        for (Item child : folder.children) {
            if (!order.contains(child.key())) {
                order.add(clamp(at++, order.size()), child.key());
            }
        }
        return true;
    }

    /**
     * The apps {@code gone} (uninstalled) out of the order and out of their folders; a folder
     * left with one app gives its place to it, one left with none goes. How many went.
     */
    public static int forget(List<String> order, List<Item> folders, java.util.Set<String> gone) {
        int removed = 0;
        for (String key : gone) {
            if (folderOf(folders, key) != null || order.contains(key)) {
                leave(order, folders, key);
                removed++;
            }
        }
        return removed;
    }

    /** The folder holding the app {@code key}, or null. */
    public static Item folderOf(List<Item> folders, String key) {
        for (Item folder : folders) {
            if (indexIn(folder, key) >= 0) {
                return folder;
            }
        }
        return null;
    }

    /**
     * {@code key} out of the order and out of any folder; a folder left with one app is replaced
     * in the order by that app.
     */
    private static void leave(List<String> order, List<Item> folders, String key) {
        order.remove(key);
        Item folder = folderOf(folders, key);
        if (folder == null) {
            return;
        }
        folder.children.remove(indexIn(folder, key));
        if (folder.children.size() <= 1) {
            int at = order.indexOf(folder.key());
            folders.remove(folder);
            if (at >= 0) {
                order.remove(at);
            }
            if (folder.children.size() == 1) {
                String last = folder.children.get(0).key();
                if (!order.contains(last)) {
                    order.add(at >= 0 ? at : order.size(), last);
                }
            }
        }
    }

    private static int indexIn(Item folder, String key) {
        for (int i = 0; i < folder.children.size(); i++) {
            if (folder.children.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static int clamp(int index, int size) {
        return Math.max(0, Math.min(index, size));
    }

    /** A copy of an entry, so the folder holds its own and the order keeps nothing shared. */
    public static Item copyOf(Item src) {
        Item copy;
        switch (src.type) {
            case Item.TYPE_SHORTCUT:
                copy = src.intentUri != null
                        ? Item.intentShortcut(src.intentUri, src.label)
                        : Item.shortcut(src.pkg, src.shortcutId, src.userSerial, src.label);
                break;
            case Item.TYPE_FOLDER:
                copy = Item.folder(src.label);
                copy.children.addAll(src.children);
                break;
            default:
                copy = Item.app(src.pkg, src.cls, src.userSerial, src.label);
                break;
        }
        return copy;
    }

    /**
     * The app an entry key names ({@code pkg/cls#serial}), labelled {@code label}; null for a
     * key of anything else.
     */
    public static Item appOf(String key, String label) {
        if (key == null || key.startsWith("folder:") || key.startsWith("intent:")
                || key.startsWith("widget:") || key.contains("//")) {
            return null;
        }
        int slash = key.indexOf('/');
        int hash = key.lastIndexOf('#');
        if (slash <= 0 || hash <= slash + 1) {
            return null;
        }
        long serial;
        try {
            serial = Long.parseLong(key.substring(hash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return Item.app(key.substring(0, slash), key.substring(slash + 1, hash), serial, label);
    }
}
