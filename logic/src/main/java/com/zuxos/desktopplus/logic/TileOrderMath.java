package com.zuxos.desktopplus.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * The quick panel's tiles from the user's edit (#3): which show, in what order, and which were
 * taken off.
 *
 * <p>A saved id no build knows any more is dropped; a tile a later build adds, which no saved
 * edit knows, shows at the end; an id saved twice counts once.
 */
public final class TileOrderMath {

    private TileOrderMath() {
    }

    /**
     * @param order  the saved order, possibly stale
     * @param hidden the ids taken off the panel
     * @param known  every tile this build has, in its default order
     */
    public static List<String> shown(List<String> order, List<String> hidden, List<String> known) {
        List<String> shown = new ArrayList<>();
        for (String id : order) {
            if (known.contains(id) && !hidden.contains(id) && !shown.contains(id)) {
                shown.add(id);
            }
        }
        for (String id : known) {
            if (!shown.contains(id) && !hidden.contains(id)) {
                shown.add(id);
            }
        }
        return shown;
    }

    /** The tiles taken off, those this build still has, each once, in the order taken off. */
    public static List<String> hidden(List<String> hidden, List<String> known) {
        List<String> out = new ArrayList<>();
        for (String id : hidden) {
            if (known.contains(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
