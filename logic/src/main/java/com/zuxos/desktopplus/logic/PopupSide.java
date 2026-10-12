package com.zuxos.desktopplus.logic;

/**
 * Directions for the notification pop-ups, at the right-hand corner or the left (#6): they slide
 * in from their own screen edge and are swiped away toward it.
 */
public final class PopupSide {

    private PopupSide() {
    }

    /**
     * A distance toward the stack's edge as a screen offset: as it is at the right, mirrored at
     * the left. Its own inverse, so it also turns a finger's movement into "toward the edge".
     */
    public static float away(float distance, boolean left) {
        return left ? -distance : distance;
    }

    /**
     * Whether a swipe puts the card away: dragged a third of its width toward its edge, or flung
     * that way.
     *
     * @param towardEdge how far the finger went toward the edge ({@link #away} of its movement)
     * @param velocity   its speed toward the edge
     */
    public static boolean closes(float towardEdge, float width, float velocity, float fling) {
        return towardEdge > width / 3f || velocity > fling;
    }

    /** Where a card being dragged sits: following toward its edge, giving a little the other way. */
    public static float follow(float towardEdge) {
        return towardEdge > 0 ? towardEdge : towardEdge * 0.15f;
    }
}
