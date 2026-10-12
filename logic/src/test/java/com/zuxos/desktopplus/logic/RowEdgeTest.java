package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** Our bar parts on ZUI's row, whatever size the bar's window is. */
public class RowEdgeTest {

    @Test
    public void bottomRowPutsOursAtTheBottomWithItsMargin() {
        assertArrayEquals(new int[]{RowEdge.BOTTOM, 0, 0},
                RowEdge.place(RowEdge.BOTTOM, 0, 0, 138, 138));
        // The transient bar's margin under its row.
        assertArrayEquals(new int[]{RowEdge.BOTTOM, 0, 24},
                RowEdge.place(RowEdge.BOTTOM, 0, 24, 138, 138));
    }

    @Test
    public void shorterViewIsCentredOnTheRow() {
        // The start button: 116 on a 138 row.
        assertArrayEquals(new int[]{RowEdge.BOTTOM, 0, 11},
                RowEdge.place(RowEdge.BOTTOM, 0, 0, 138, 116));
        assertArrayEquals(new int[]{RowEdge.TOP, 11, 0},
                RowEdge.place(RowEdge.TOP, 0, 0, 138, 116));
    }

    @Test
    public void wrappedOrUnknownHeightIsNotCentred() {
        assertArrayEquals(new int[]{RowEdge.BOTTOM, 0, 0},
                RowEdge.place(RowEdge.BOTTOM, 0, 0, 138, -2));
        assertArrayEquals(new int[]{RowEdge.BOTTOM, 0, 0},
                RowEdge.place(RowEdge.BOTTOM, 0, 0, 0, 116));
    }

    @Test
    public void topRowKeepsOursAtTheTop() {
        // The monitor's bar moved to the top.
        assertArrayEquals(new int[]{RowEdge.TOP, 0, 0},
                RowEdge.place(RowEdge.TOP | 0x07, 0, 0, 138, 138));
    }

    @Test
    public void unspecifiedOrNoGravityIsTheTopAsAFrameLayoutReadsIt() {
        assertArrayEquals(new int[]{RowEdge.TOP, 5, 0},
                RowEdge.place(RowEdge.UNSPECIFIED, 5, 9, 138, 138));
        assertArrayEquals(new int[]{RowEdge.TOP, 5, 0},
                RowEdge.place(0, 5, 9, 138, 138));
    }

    @Test
    public void horizontalPartOfTheRowsGravityIsIgnored() {
        // bottom | centre-horizontal
        assertArrayEquals(new int[]{RowEdge.BOTTOM, 0, 3},
                RowEdge.place(RowEdge.BOTTOM | 0x01, 0, 3, 138, 138));
    }

    @Test
    public void centredRowKeepsBothMargins() {
        assertArrayEquals(new int[]{RowEdge.CENTER_VERTICAL, 4, 6},
                RowEdge.place(RowEdge.CENTER_VERTICAL, 4, 6, 138, 116));
    }

    @Test
    public void fallbackIsTheWindowsFoot() {
        assertArrayEquals(new int[]{RowEdge.BOTTOM, 0, 0}, RowEdge.fallback());
    }
}
