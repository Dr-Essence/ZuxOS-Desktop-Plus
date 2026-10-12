package com.zuxos.desktopplus.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One thing living on the desktop (or inside a folder): app, shortcut, folder or widget. */
public class Item {

    public static final int TYPE_APP = 0;
    public static final int TYPE_SHORTCUT = 1;
    public static final int TYPE_FOLDER = 2;
    public static final int TYPE_WIDGET = 3;

    public String id = UUID.randomUUID().toString();
    public int type;
    public int x = -1;
    public int y = -1;
    public int spanX = 1;
    public int spanY = 1;
    /** Which desktop page this item lives on. */
    public int page;

    public String pkg;
    public String cls;
    public long userSerial;

    /** Set for pinned deep shortcuts ({@link #TYPE_SHORTCUT}). */
    public String shortcutId;
    /** Set for legacy/custom shortcuts: an {@code Intent.toUri(URI_INTENT_SCHEME)} string. */
    public String intentUri;

    public String label;
    public int widgetId = -1;
    public final List<Item> children = new ArrayList<>();

    /**
     * Its own place while a grid too small for it - a rotated tablet, a smaller monitor - shows
     * it elsewhere ({@link #showAt}); -1 when it is where it belongs. Never a place of its own:
     * this is what is saved meanwhile, so the room coming back brings the item back.
     */
    private int mHomeX = -1;
    private int mHomeY = -1;
    /** Where it is shown meanwhile. Moved from there, by the user, that is its place from then. */
    private int mShownX = -1;
    private int mShownY = -1;

    /** Shown at another cell for lack of room, keeping its own place to go back to. */
    public void showAt(int cellX, int cellY) {
        if (!displaced()) {
            mHomeX = x;
            mHomeY = y;
        }
        x = cellX;
        y = cellY;
        mShownX = cellX;
        mShownY = cellY;
    }

    /** Shown away from its own place, and not moved since. */
    public boolean displaced() {
        if (mHomeX >= 0 && (x != mShownX || y != mShownY)) {
            // Moved by the user while away: where it went is its place now.
            mHomeX = mHomeY = mShownX = mShownY = -1;
        }
        return mHomeX >= 0;
    }

    /** Its own place while {@link #displaced}. */
    public int homeX() {
        return displaced() ? mHomeX : x;
    }

    public int homeY() {
        return displaced() ? mHomeY : y;
    }

    /** Put where it is now on purpose - tidied up, say: that is its own place from now on. */
    public void stay() {
        mHomeX = mHomeY = mShownX = mShownY = -1;
    }

    /** Back at its own place. */
    public void goHome() {
        if (displaced()) {
            x = mHomeX;
            y = mHomeY;
        }
        mHomeX = mHomeY = mShownX = mShownY = -1;
    }

    public static Item app(String pkg, String cls, long userSerial, String label) {
        Item i = new Item();
        i.type = TYPE_APP;
        i.pkg = pkg;
        i.cls = cls;
        i.userSerial = userSerial;
        i.label = label;
        return i;
    }

    public static Item shortcut(String pkg, String shortcutId, long userSerial, String label) {
        Item i = new Item();
        i.type = TYPE_SHORTCUT;
        i.pkg = pkg;
        i.shortcutId = shortcutId;
        i.userSerial = userSerial;
        i.label = label;
        return i;
    }

    public static Item intentShortcut(String intentUri, String label) {
        Item i = new Item();
        i.type = TYPE_SHORTCUT;
        i.intentUri = intentUri;
        i.label = label;
        return i;
    }

    public static Item folder(String label) {
        Item i = new Item();
        i.type = TYPE_FOLDER;
        i.label = label;
        return i;
    }

    public static Item widget(int widgetId, String pkg, String cls, int spanX, int spanY, String label) {
        Item i = new Item();
        i.type = TYPE_WIDGET;
        i.widgetId = widgetId;
        i.pkg = pkg;
        i.cls = cls;
        i.spanX = Math.max(1, spanX);
        i.spanY = Math.max(1, spanY);
        i.label = label;
        return i;
    }

    /** Stable identity of the launchable behind this item, used for drawer bookkeeping. */
    public String key() {
        switch (type) {
            case TYPE_APP:
                return pkg + "/" + cls + "#" + userSerial;
            case TYPE_SHORTCUT:
                return intentUri != null ? "intent:" + intentUri : pkg + "//" + shortcutId + "#" + userSerial;
            case TYPE_WIDGET:
                return "widget:" + widgetId;
            default:
                return "folder:" + id;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("type", type);
        // Its own place, not where a small grid shows it for now.
        o.put("x", homeX());
        o.put("y", homeY());
        o.put("spanX", spanX);
        o.put("spanY", spanY);
        o.put("page", page);
        putIfSet(o, "pkg", pkg);
        putIfSet(o, "cls", cls);
        o.put("user", userSerial);
        putIfSet(o, "shortcutId", shortcutId);
        putIfSet(o, "intentUri", intentUri);
        putIfSet(o, "label", label);
        o.put("widgetId", widgetId);
        if (!children.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Item c : children) {
                arr.put(c.toJson());
            }
            o.put("children", arr);
        }
        return o;
    }

    private static void putIfSet(JSONObject o, String key, String value) throws JSONException {
        if (value != null) {
            o.put(key, value);
        }
    }

    public static Item fromJson(JSONObject o) throws JSONException {
        Item i = new Item();
        i.id = o.optString("id", UUID.randomUUID().toString());
        i.type = o.optInt("type", TYPE_APP);
        i.x = o.optInt("x", -1);
        i.y = o.optInt("y", -1);
        i.spanX = Math.max(1, o.optInt("spanX", 1));
        i.spanY = Math.max(1, o.optInt("spanY", 1));
        i.page = Math.max(0, o.optInt("page", 0));
        i.pkg = o.has("pkg") ? o.getString("pkg") : null;
        i.cls = o.has("cls") ? o.getString("cls") : null;
        i.userSerial = o.optLong("user", 0);
        i.shortcutId = o.has("shortcutId") ? o.getString("shortcutId") : null;
        i.intentUri = o.has("intentUri") ? o.getString("intentUri") : null;
        i.label = o.has("label") ? o.getString("label") : null;
        i.widgetId = o.optInt("widgetId", -1);
        JSONArray arr = o.optJSONArray("children");
        if (arr != null) {
            for (int n = 0; n < arr.length(); n++) {
                i.children.add(fromJson(arr.getJSONObject(n)));
            }
        }
        return i;
    }
}
