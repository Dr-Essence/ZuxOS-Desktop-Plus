package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The screen saver's copy fills the other screen, centred, keeping its shape. */
public class ScreenFitTest {

    @Test
    public void tabletOnTheMonitorFillsItsWidthAndCutsTopAndBottom() {
        // The tablet in landscape (16:10) on a 16:9 monitor.
        float[] fit = ScreenFit.cover(3040, 1904, 2560, 1440);
        assertEquals(2560f / 3040f, fit[0], 1e-6f);
        assertEquals(0f, fit[1], 0.01f);
        // 1904 * 0.842 = 1603 tall: 81.6 px over at the top and at the bottom.
        assertEquals((1440f - 1904f * fit[0]) / 2f, fit[2], 0.01f);
        assertEquals(-81.7f, fit[2], 0.1f);
    }

    @Test
    public void monitorOnTheTabletFillsItsHeightAndCutsTheSides() {
        float[] fit = ScreenFit.cover(2560, 1440, 3040, 1904);
        assertEquals(1904f / 1440f, fit[0], 1e-6f);
        assertEquals(0f, fit[2], 0.01f);
        assertEquals((3040f - 2560f * fit[0]) / 2f, fit[1], 0.01f);
    }

    @Test
    public void filledPictureCoversTheWholeScreen() {
        int[][] cases = {{3040, 1904, 2560, 1440}, {1904, 3040, 2560, 1440},
                {2560, 1440, 1904, 3040}, {1000, 1000, 1920, 1080}};
        for (int[] c : cases) {
            float[] fit = ScreenFit.cover(c[0], c[1], c[2], c[3]);
            float right = fit[1] + c[0] * fit[0];
            float bottom = fit[2] + c[1] * fit[0];
            assertTrue(fit[1] <= 0.001f && fit[2] <= 0.001f);
            assertTrue(right >= c[2] - 0.01f && bottom >= c[3] - 0.01f);
            // Centred: the same overhang on both sides.
            assertEquals(-fit[1], right - c[2], 0.01f);
            assertEquals(-fit[2], bottom - c[3], 0.01f);
        }
    }

    @Test
    public void sameSizeIsUnchanged() {
        float[] fit = ScreenFit.cover(2560, 1440, 2560, 1440);
        assertEquals(1f, fit[0], 0f);
        assertEquals(0f, fit[1], 0f);
        assertEquals(0f, fit[2], 0f);
    }

    @Test
    public void emptySizesGiveNothing() {
        assertNull(ScreenFit.cover(0, 1440, 2560, 1440));
        assertNull(ScreenFit.cover(2560, 1440, 2560, -1));
    }
}
