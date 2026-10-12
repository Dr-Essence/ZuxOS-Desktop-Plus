package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pop-ups at either corner slide and swipe toward their own edge. */
public class PopupSideTest {

    @Test
    public void rightCornerKeepsDirections() {
        assertEquals(100f, PopupSide.away(100f, false), 0f);
        assertEquals(-30f, PopupSide.away(-30f, false), 0f);
    }

    @Test
    public void leftCornerMirrorsThem() {
        assertEquals(-100f, PopupSide.away(100f, true), 0f);
        // A finger moving left (-80) is 80 toward the left edge.
        assertEquals(80f, PopupSide.away(-80f, true), 0f);
    }

    @Test
    public void awayIsItsOwnInverse() {
        for (boolean left : new boolean[]{false, true}) {
            assertEquals(42f, PopupSide.away(PopupSide.away(42f, left), left), 0f);
        }
    }

    @Test
    public void swipeClosesPastAThirdOrOnAFling() {
        assertTrue(PopupSide.closes(130f, 360f, 0f, 1000f));
        assertFalse(PopupSide.closes(110f, 360f, 0f, 1000f));
        assertTrue(PopupSide.closes(20f, 360f, 1500f, 1000f));
        // Flung the other way: stays.
        assertFalse(PopupSide.closes(20f, 360f, -1500f, 1000f));
    }

    @Test
    public void cardFollowsTowardItsEdgeAndGivesALittleTheOtherWay() {
        assertEquals(50f, PopupSide.follow(50f), 0f);
        assertEquals(-15f, PopupSide.follow(-100f), 0.001f);
    }
}
