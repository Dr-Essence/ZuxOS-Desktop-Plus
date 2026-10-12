package com.zuxos.desktopplus.logic;

/**
 * Where one of ours goes on the taskbar: by gravity, at the edge of the window ZUI's own row is
 * kept at, never at a top measured once (0.0.215).
 *
 * <p>Gravity values are Android's ({@code android.view.Gravity}), written out so this needs no
 * framework: a frame layout reads a child's vertical gravity as bottom, centre or - for anything
 * else, unspecified included - top.
 */
public final class RowEdge {

    public static final int TOP = 0x30;
    public static final int BOTTOM = 0x50;
    public static final int CENTER_VERTICAL = 0x10;
    private static final int VERTICAL_MASK = 0x70;
    /** A frame layout child's gravity when none was given. */
    public static final int UNSPECIFIED = -1;

    private RowEdge() {
    }

    /**
     * Our vertical gravity and margins, from ZUI's row's.
     *
     * @param rowGravity  the row's layout gravity ({@link #UNSPECIFIED} when it has none)
     * @param rowTop      the row's top margin
     * @param rowBottom   the row's bottom margin
     * @param rowHeight   the row's height, 0 when not laid out yet
     * @param height      our height; centred on the row when it is shorter, not when it is
     *                    wrapped (negative) or unknown (0)
     * @return {vertical gravity, top margin, bottom margin}
     */
    public static int[] place(int rowGravity, int rowTop, int rowBottom, int rowHeight,
            int height) {
        int its = rowGravity == UNSPECIFIED ? TOP : rowGravity & VERTICAL_MASK;
        int centring = height > 0 && rowHeight > height ? (rowHeight - height) / 2 : 0;
        if (its == BOTTOM) {
            return new int[]{BOTTOM, 0, rowBottom + centring};
        }
        if (its == CENTER_VERTICAL) {
            // The frame layout centres it as it centres the row; their margins are the same.
            return new int[]{CENTER_VERTICAL, rowTop, rowBottom};
        }
        return new int[]{TOP, rowTop + centring, 0};
    }

    /** Where the row's gravity cannot be read: the bar is the window's foot. */
    public static int[] fallback() {
        return new int[]{BOTTOM, 0, 0};
    }
}
