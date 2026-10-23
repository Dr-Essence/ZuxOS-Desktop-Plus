package com.zuxos.desktopplus.logic;

/**
 * How far a controller's stick leans, from where it reads: 0 inside its dead zone, then growing
 * with the square of the way past it, for fine control near the middle and full speed at the edge.
 */
public final class StickCurve {

    /** The least lean that counts, whatever the controller says its own dead zone is. */
    public static final float MIN_FLAT = 0.12f;

    private StickCurve() {
    }

    /**
     * The lean, -1 to 1.
     *
     * @param value the axis as read, -1 to 1
     * @param flat  the controller's own dead zone for the axis, 0 when it gives none
     */
    public static float lean(float value, float flat) {
        float dead = Math.max(MIN_FLAT, flat);
        if (Math.abs(value) <= dead) {
            return 0f;
        }
        float past = Math.min(1f, (Math.abs(value) - dead) / (1f - dead));
        return Math.signum(value) * past * past;
    }
}
