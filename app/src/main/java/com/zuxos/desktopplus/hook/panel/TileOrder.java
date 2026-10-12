package com.zuxos.desktopplus.hook.panel;

import android.content.Context;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Which quick panel tiles show, and in what order: the user's edit (#3), kept in the launcher's
 * own files as the drawer keeps its order - the launcher cannot write the module's settings.
 *
 * <p>Tiles are known by a stable id. An id saved for a tile that no longer exists is dropped; a
 * tile added in a later build, which no saved edit knows, shows at the end. Loaded once per
 * process, written on each change.
 */
final class TileOrder {

    private static final String FILE = "quick_tiles.json";

    private static boolean sLoaded;
    private static final List<String> ORDER = new ArrayList<>();
    private static final List<String> HIDDEN = new ArrayList<>();

    private TileOrder() {
    }

    /** The tiles to show, in order, from those this build has ({@code known}, default order). */
    static synchronized List<String> shown(Context ctx, List<String> known) {
        load(ctx);
        List<String> shown = new ArrayList<>();
        for (String id : ORDER) {
            if (known.contains(id) && !HIDDEN.contains(id) && !shown.contains(id)) {
                shown.add(id);
            }
        }
        for (String id : known) {
            if (!shown.contains(id) && !HIDDEN.contains(id)) {
                shown.add(id);
            }
        }
        return shown;
    }

    /** The tiles taken off the panel, in the order they were taken off. */
    static synchronized List<String> hidden(Context ctx, List<String> known) {
        load(ctx);
        List<String> hidden = new ArrayList<>();
        for (String id : HIDDEN) {
            if (known.contains(id) && !hidden.contains(id)) {
                hidden.add(id);
            }
        }
        return hidden;
    }

    /** Keeps the user's edit. */
    static synchronized void save(Context ctx, List<String> shown, List<String> hidden) {
        load(ctx);
        ORDER.clear();
        ORDER.addAll(shown);
        HIDDEN.clear();
        HIDDEN.addAll(hidden);
        try {
            JSONObject root = new JSONObject();
            root.put("order", new JSONArray(ORDER));
            root.put("hidden", new JSONArray(HIDDEN));
            Storage.writeAsync(Storage.file(ctx, FILE), root.toString());
        } catch (Throwable t) {
            L.w("quick tiles: could not save the order (" + t + ")");
        }
    }

    private static void load(Context ctx) {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        String raw = Storage.read(Storage.file(ctx, FILE));
        if (raw == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject(raw);
            read(root.optJSONArray("order"), ORDER);
            read(root.optJSONArray("hidden"), HIDDEN);
        } catch (Throwable t) {
            L.w("quick tiles: saved order unreadable, the default one instead (" + t + ")");
            ORDER.clear();
            HIDDEN.clear();
        }
    }

    private static void read(JSONArray from, List<String> into) {
        for (int i = 0; from != null && i < from.length(); i++) {
            String id = from.optString(i, null);
            if (id != null && !id.isEmpty()) {
                into.add(id);
            }
        }
    }
}
