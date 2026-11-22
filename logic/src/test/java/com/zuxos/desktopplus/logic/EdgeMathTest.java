package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The hidden bar's edge strip and its slide, at either edge of the monitor. */
public class EdgeMathTest {

    @Test
    public void stripIsTheWindowsBottomRowsForABarAtTheBottom() {
        // The monitor's bar window: 2560 wide, 200 tall with ZUI's room above the row.
        assertArrayEquals(new int[]{0, 198, 2560, 200}, EdgeMath.strip(false, 2560, 200, 2));
    }

    @Test
    public void stripIsTheWindowsTopRowsForABarAtTheTop() {
        assertArrayEquals(new int[]{0, 0, 2560, 2}, EdgeMath.strip(true, 2560, 138, 2));
    }

    @Test
    public void stripNeverOutgrowsTheWindow() {
        assertArrayEquals(new int[]{0, 0, 100, 1}, EdgeMath.strip(false, 100, 1, 2));
        assertArrayEquals(new int[]{0, 0, 0, 0}, EdgeMath.strip(true, -5, 0, 2));
    }

    @Test
    public void slideTakesTheWholeBarOffItsEdge() {
        assertEquals(200f, EdgeMath.slide(false, 200), 0f);
        assertEquals(-138f, EdgeMath.slide(true, 138), 0f);
        assertEquals(0f, EdgeMath.slide(false, -1), 0f);
    }

    @Test
    public void aSlideCaughtHalfwayTakesWhatIsLeftOfItsTime() {
        // Leaving, caught at 150 of 200, and called back to 0: three quarters of the way.
        assertEquals(345L, EdgeMath.remainingMs(460L, 150f, 0f, 200f));
        // Already there: no time at all.
        assertEquals(0L, EdgeMath.remainingMs(460L, 200f, 200f, 200f));
        // A whole way, from a bar at the top.
        assertEquals(460L, EdgeMath.remainingMs(460L, 0f, -138f, 138f));
        // Nothing to measure against.
        assertEquals(0L, EdgeMath.remainingMs(460L, 10f, 0f, 0f));
    }
}
