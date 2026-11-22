package com.zuxos.desktopplus.logic;

/**
 * Where a taskbar hidden over a full-screen app waits for the pointer, and how it slides away and
 * back.
 *
 * <p>The bar's window stays where ZUI put it. Its content slides off the screen's edge, and the
 * window keeps a thin strip at that edge to feel the pointer: the strip is the only part of the
 * window that still takes it, so the app gets every other pixel. Rectangles are {@code {left,
 * top, right, bottom}} in the window's own pixels, so this runs, and is tested, on a plain JDK.
 */
public final class EdgeMath {

    private EdgeMath() {
    }

    /**
     * The strip at the bar's edge of its window: the bottom rows for a bar at the bottom, the top
     * rows for one at the top. Never thicker than the window.
     */
    public static int[] strip(boolean top, int width, int height, int thickness) {
        int depth = Math.max(0, Math.min(thickness, height));
        int w = Math.max(0, width);
        int h = Math.max(0, height);
        return top ? new int[]{0, 0, w, depth} : new int[]{0, h - depth, w, h};
    }

    /**
     * How far the bar's content moves to leave the screen: its whole height, down for a bar at
     * the bottom and up for one at the top, so nothing of it is left inside the window.
     */
    public static float slide(boolean top, int height) {
        int h = Math.max(0, height);
        return top ? -h : h;
    }

    /**
     * How long a slide takes from where the bar is now: a bar caught halfway, coming back as it
     * leaves, takes the share of the time that is left of the way, so it moves at the same pace
     * and never jumps.
     *
     * @param fullMs the time for the whole way
     * @param now    where the bar is, as its translation
     * @param target where it is going
     * @param whole  the length of the whole way; 0 or less means it has none
     */
    public static long remainingMs(long fullMs, float now, float target, float whole) {
        if (fullMs <= 0 || whole <= 0) {
            return 0L;
        }
        float share = Math.min(1f, Math.abs(target - now) / whole);
        return Math.round(fullMs * share);
    }
}
