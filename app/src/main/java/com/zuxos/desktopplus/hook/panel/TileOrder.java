package com.zuxos.desktopplus.hook.panel;

import android.content.Context;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Storage;
import com.zuxos.desktopplus.logic.TileOrderMath;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Which quick panel tiles show, and in what order: the user's edit (#3), kept in the launcher's
 * own files as the drawer keeps its order - the launcher cannot write the module's settings.
 *
 * <p>Tiles are known by a stable id; what a stale or partial edit means is
 * {@link TileOrderMath}'s. Loaded once per process, written on each change.
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
        return TileOrderMath.shown(ORDER, HIDDEN, known);
    }

    /** The tiles taken off the panel, in the order they were taken off. */
    static synchronized List<String> hidden(Context ctx, List<String> known) {
        load(ctx);
        return TileOrderMath.hidden(HIDDEN, known);
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
