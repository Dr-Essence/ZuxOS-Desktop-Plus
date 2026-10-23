package com.zuxos.desktopplus.logic;

/**
 * A controller's way around an on-screen keyboard, from one place to the next with the arrows.
 *
 * <p>The places are given by their centres and heights, in one coordinate space, as parallel
 * arrays; a place is its index. Left and right go to the nearest place in the same row. Up and
 * down go to the nearest row that way, and in it to the place nearest the column the walk came
 * down, so a walk through rows of different widths keeps its line, as TV keyboards do. Of two
 * places as near, the first wins.
 */
public final class KeyGrid {

    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int UP = 3;
    public static final int DOWN = 4;
    /** No place: none given, or an edge. */
    public static final int NONE = -1;

    private KeyGrid() {
    }

    /** The place whose centre is nearest ({@code px}, {@code py}), or {@link #NONE}. */
    public static int nearest(int[] x, int[] y, int px, int py) {
        int best = NONE;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < x.length; i++) {
            long dx = x[i] - px;
            long dy = y[i] - py;
            long d = dx * dx + dy * dy;
            if (d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * The next place from {@code at} that way, or {@link #NONE} at an edge.
     *
     * @param x      the places' centres across
     * @param y      their centres down
     * @param h      their heights: a place is in a row when its centre is within half a height
     * @param way    {@link #LEFT}, {@link #RIGHT}, {@link #DOWN}; anything else is up
     * @param column up and down: the x of the column the walk keeps, negative for {@code at}'s own
     */
    public static int next(int[] x, int[] y, int[] h, int at, int way, int column) {
        int cx = x[at];
        int cy = y[at];
        int half = Math.max(1, h[at] / 2);
        int best = NONE;
        if (way == LEFT || way == RIGHT) {
            boolean right = way == RIGHT;
            for (int i = 0; i < x.length; i++) {
                if (i == at || Math.abs(y[i] - cy) >= half || (right ? x[i] <= cx : x[i] >= cx)) {
                    continue;
                }
                if (best == NONE || Math.abs(x[i] - cx) < Math.abs(x[best] - cx)) {
                    best = i;
                }
            }
            return best;
        }
        boolean down = way == DOWN;
        int row = Integer.MIN_VALUE;
        for (int i = 0; i < y.length; i++) {
            if ((down ? y[i] > cy + half : y[i] < cy - half)
                    && (row == Integer.MIN_VALUE || Math.abs(y[i] - cy) < Math.abs(row - cy))) {
                row = y[i];
            }
        }
        if (row == Integer.MIN_VALUE) {
            return NONE;
        }
        int line = column >= 0 ? column : cx;
        for (int i = 0; i < x.length; i++) {
            if (Math.abs(y[i] - row) >= Math.max(1, h[i] / 2)) {
                continue;
            }
            if (best == NONE || Math.abs(x[i] - line) < Math.abs(x[best] - line)) {
                best = i;
            }
        }
        return best;
    }
}
