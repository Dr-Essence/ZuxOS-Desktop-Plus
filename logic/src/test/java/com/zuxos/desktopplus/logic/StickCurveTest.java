package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The right stick's lean: nothing in its dead zone, fine near the middle, full at the edge. */
public class StickCurveTest {

    private static final float EPS = 1e-6f;

    @Test
    public void insideTheDeadZoneIsStill() {
        assertEquals(0f, StickCurve.lean(0.1f, 0f), EPS);
        assertEquals(0f, StickCurve.lean(StickCurve.MIN_FLAT, 0f), EPS);
        assertEquals(0f, StickCurve.lean(-0.1f, 0f), EPS);
    }

    @Test
    public void theWiderOfTheTwoDeadZonesCounts() {
        assertEquals(0f, StickCurve.lean(0.2f, 0.25f), EPS);
        assertEquals(0.0082644625f, StickCurve.lean(0.2f, 0.05f), EPS);
    }

    @Test
    public void growsWithTheSquareOfTheWayPast() {
        // Halfway from the dead zone to the edge: a quarter of the speed.
        assertEquals(0.25f, StickCurve.lean(0.56f, 0f), EPS);
        assertEquals(-0.25f, StickCurve.lean(-0.56f, 0f), EPS);
    }

    @Test
    public void theEdgeIsFullSpeedAndNoMore() {
        assertEquals(1f, StickCurve.lean(1f, 0f), EPS);
        assertEquals(-1f, StickCurve.lean(-1f, 0f), EPS);
        assertEquals(1f, StickCurve.lean(1.2f, 0f), EPS);
    }
}
