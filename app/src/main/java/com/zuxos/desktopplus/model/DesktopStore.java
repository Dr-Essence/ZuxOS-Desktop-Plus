package com.zuxos.desktopplus.model;

import android.content.Context;
import android.view.Display;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Storage;
import com.zuxos.desktopplus.logic.MonitorKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists a desktop layout as JSON: the tablet's, and one per monitor.
 *
 * <p>Each monitor's file is named by the display's unique id ({@link #monitorKey}). A monitor
 * seen for the first time starts from {@link Const#FILE_DESKTOP}, the one layout every monitor
 * shared before (0.0.216); that file is only read from then on, never written or deleted, so no
 * layout is ever lost.
 */
public final class DesktopStore {

    private final Context mCtx;
    private final String mFileName;
    /** A monitor's own layout, which starts from the shared one when it has none yet. */
    private final boolean mOwnMonitor;
    private final List<Item> mItems = new ArrayList<>();

    /**
     * @param monitor the monitor's key ({@link #monitorKey}), or null for the one shared layout:
     *                the tablet's desktop, or a monitor whose id is not known
     */
    public DesktopStore(Context ctx, boolean external, String monitor) {
        mCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        mOwnMonitor = external && monitor != null;
        mFileName = !external ? "desktop-internal.json"
                : mOwnMonitor ? "desktop-" + monitor + ".json" : Const.FILE_DESKTOP;
    }

    /** The layout file in use, for the probe. */
    public String fileName() {
        return mFileName;
    }

    /**
     * A monitor's key: its display's unique id ({@code Display.getUniqueId()}, hidden, the same
     * for the same monitor at every plug) in filename-safe characters; null when not readable.
     */
    public static String monitorKey(Display display) {
        Object id = Reflect.call(display, "getUniqueId");
        return id instanceof String ? MonitorKey.of((String) id) : null;
    }

    public List<Item> items() {
        return mItems;
    }

    public synchronized void load() {
        mItems.clear();
        File f = Storage.file(mCtx, mFileName);
        String raw = Storage.read(f);
        if (raw == null && mOwnMonitor) {
            // A monitor seen for the first time: the layout all monitors shared until now.
            raw = Storage.read(Storage.file(mCtx, Const.FILE_DESKTOP));
            if (raw != null) {
                L.i("desktop: a new monitor (" + mFileName + ") starts from the shared layout");
            }
        }
        if (raw == null) {
            L.d("no saved desktop layout at " + f);
            return;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    mItems.add(Item.fromJson(arr.getJSONObject(i)));
                }
            }
            L.d("loaded " + mItems.size() + " desktop items");
        } catch (Throwable t) {
            L.e("desktop layout is corrupt, starting empty", t);
            mItems.clear();
        }
    }

    /** Serialises here and writes on the IO thread; safe to call from a drag. */
    public synchronized void save() {
        Storage.writeAsync(Storage.file(mCtx, mFileName), exportJson());
    }

    /** Blocking write, for when the desktop is going away. */
    public synchronized void saveNow() {
        Storage.write(Storage.file(mCtx, mFileName), exportJson());
    }

    public synchronized String exportJson() {
        try {
            JSONArray arr = new JSONArray();
            for (Item i : mItems) {
                arr.put(i.toJson());
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("items", arr);
            return root.toString(2);
        } catch (Throwable t) {
            L.e("export failed", t);
            return "{}";
        }
    }

    public synchronized void add(Item item) {
        mItems.add(item);
    }

    /** Removes a top-level item. Folder contents are managed by the folder itself. */
    public synchronized void remove(Item item) {
        mItems.remove(item);
    }

    /** Pages in use, always at least one. */
    public int pageCount() {
        int max = 0;
        for (Item i : mItems) {
            max = Math.max(max, i.page);
        }
        return max + 1;
    }

    public boolean isPageEmpty(int page) {
        for (Item i : mItems) {
            if (i.page == page) {
                return false;
            }
        }
        return true;
    }

    /** Closes the gap left by an emptied page so pages stay consecutive. */
    public synchronized void removePage(int page) {
        for (Item i : mItems) {
            if (i.page > page) {
                i.page--;
            }
        }
    }
}
