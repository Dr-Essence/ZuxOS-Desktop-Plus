package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GridMathTest {

    @Test
    public void doublingKeepsEveryItemApartAndInside() {
        // 6 columns to 12: an icon per cell lands every other cell.
        for (int x = 0; x < 6; x++) {
            assertEquals(x * 2, GridMath.start(x, 6, 12));
        }
        // A widget four wide becomes eight wide.
        assertEquals(8, GridMath.span(0, 4, 6, 12));
    }

    @Test
    public void unevenGrowthNeverOverlaps() {
        int from = 6;
        for (int to = 6; to <= 12; to++) {
            for (int x = 0; x < from; x++) {
                for (int s = 1; x + s <= from; s++) {
                    int a = GridMath.start(x, from, to);
                    int end = a + GridMath.span(x, s, from, to);
                    assertTrue(end <= to);
                    if (x + s < from) {
                        // The item right after it starts where it ends, or later.
                        assertTrue(GridMath.start(x + s, from, to) >= end);
                    }
                }
            }
        }
    }

    @Test
    public void widgetSpanFollowsTheGrid() {
        assertEquals(8, GridMath.widgetSpan(4, 6, 12));
        assertEquals(4, GridMath.widgetSpan(8, 12, 6));
        assertEquals(1, GridMath.widgetSpan(1, 12, 6));
        assertEquals(10, GridMath.widgetSpan(6, 6, 10));
        assertEquals(5, GridMath.widgetSpan(8, 6, 5));
    }

    @Test
    public void fitShrinksOnlyWhatDoesNotFit() {
        assertEquals(1f, GridMath.fit(150, 100, 300, 200, 0.8f), 0.0001f);
        assertEquals(0.5f, GridMath.fit(400, 100, 300, 200, 0.8f), 0.0001f);
        // Too wide: 152 px of icon in a 150 px cell, 80 % of it allowed.
        assertEquals(120f / 152f, GridMath.fit(100, 152, 150, 400, 0.8f), 0.0001f);
    }
}
