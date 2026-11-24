package com.zuxos.desktopplus.logic;

import com.zuxos.desktopplus.model.Item;

import java.util.Collection;

/**
 * An app that moved its launcher entry: an update gave F-Droid a new launcher activity
 * ({@code org.fdroid.IconActivity}), and the folders kept the old one, which Android no longer
 * opens from a launcher and which has no icon of its own. A stored app is a key
 * {@code package/class#user}, as the launcher's app list keys them.
 */
public final class MovedEntry {

    private MovedEntry() {
    }

    /**
     * The key the stored app has now: the first launcher entry of the same package for the same
     * user, when its own is no longer one. Null when it still is one, when the app has none (it
     * is being updated, or is gone - uninstalls are taken off elsewhere), or for a key that is
     * not an app's.
     *
     * @param launchable every launcher entry's key, in the app list's order
     */
    public static String replacement(String key, Collection<String> launchable) {
        if (key == null || launchable == null || launchable.contains(key)) {
            return null;
        }
        int slash = key.indexOf('/');
        int hash = key.lastIndexOf('#');
        if (slash <= 0 || hash <= slash + 1) {
            return null;
        }
        String app = key.substring(0, slash + 1);
        String user = key.substring(hash);
        for (String entry : launchable) {
            if (entry != null && entry.startsWith(app) && entry.endsWith(user)
                    && entry.lastIndexOf('#') == entry.length() - user.length()) {
                return entry;
            }
        }
        return null;
    }

    /** A stored app moved to the entry its app has now; true when it moved. */
    public static boolean follow(Item item, Collection<String> launchable) {
        if (item == null || item.type != Item.TYPE_APP) {
            return false;
        }
        String now = replacement(item.key(), launchable);
        String cls = className(now);
        if (cls == null) {
            return false;
        }
        item.cls = cls;
        return true;
    }

    /** The activity's class in an app's key; null for a key that is not an app's. */
    public static String className(String key) {
        int slash = key == null ? -1 : key.indexOf('/');
        int hash = key == null ? -1 : key.lastIndexOf('#');
        return slash <= 0 || hash <= slash + 1 ? null : key.substring(slash + 1, hash);
    }
}
