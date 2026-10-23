package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** A controller's walk around a keyboard laid out as HeliBoard lays out its letters. */
public class KeyGridTest {

    // q-p, a-l a little to the right, shift z-m delete, then symbols , space . enter
    private static final int[] X = {
            50, 150, 250, 350, 450, 550, 650, 750, 850, 950,
            90, 190, 290, 390, 490, 590, 690, 790, 890,
            75, 200, 300, 400, 500, 600, 700, 800, 925,
            100, 200, 500, 800, 920};
    private static final int[] Y = {
            50, 50, 50, 50, 50, 50, 50, 50, 50, 50,
            150, 150, 150, 150, 150, 150, 150, 150, 150,
            250, 250, 250, 250, 250, 250, 250, 250, 250,
            350, 350, 350, 350, 350};
    private static final int[] H = new int[X.length];

    static {
        java.util.Arrays.fill(H, 100);
    }

    private static final int Q = 0;
    private static final int W = 1;
    private static final int E = 2;
    private static final int P = 9;
    private static final int D = 12;
    private static final int L = 18;
    private static final int V = 23;
    private static final int B = 24;
    private static final int M = 26;
    private static final int DELETE = 27;
    private static final int SPACE = 30;
    private static final int ENTER = 32;

    private static int next(int at, int way) {
        return KeyGrid.next(X, Y, H, at, way, -1);
    }

    @Test
    public void leftAndRightStayInTheRow() {
        assertEquals(W, next(Q, KeyGrid.RIGHT));
        assertEquals(Q, next(W, KeyGrid.LEFT));
    }

    @Test
    public void everyEdgeStops() {
        assertEquals(KeyGrid.NONE, next(Q, KeyGrid.LEFT));
        assertEquals(KeyGrid.NONE, next(P, KeyGrid.RIGHT));
        assertEquals(KeyGrid.NONE, next(Q, KeyGrid.UP));
        assertEquals(KeyGrid.NONE, next(ENTER, KeyGrid.DOWN));
    }

    @Test
    public void downGoesToTheNearestKeyOfTheNextRow() {
        // e at 250: d at 290 is nearer than s at 190.
        assertEquals(D, next(E, KeyGrid.DOWN));
        assertEquals(SPACE, next(B, KeyGrid.DOWN));
    }

    @Test
    public void upFromTheSpaceBarFindsTheKeyAboveItsMiddle() {
        assertEquals(V, next(SPACE, KeyGrid.UP));
    }

    @Test
    public void theKeptColumnHoldsTheLineThroughRowsOfOtherWidths() {
        // From l, delete is nearest; on a walk down from o (850), m keeps the line.
        assertEquals(DELETE, next(L, KeyGrid.DOWN));
        assertEquals(M, KeyGrid.next(X, Y, H, L, KeyGrid.DOWN, 850));
    }

    @Test
    public void ofTwoAsNearTheFirstWins() {
        int[] x = {100, 50, 150};
        int[] y = {0, 100, 100};
        int[] h = {100, 100, 100};
        assertEquals(1, KeyGrid.next(x, y, h, 0, KeyGrid.DOWN, -1));
        assertEquals(1, KeyGrid.nearest(x, y, 100, 100));
    }

    @Test
    public void nearestIsByTheCentres() {
        assertEquals(Q, KeyGrid.nearest(X, Y, 0, 0));
        assertEquals(SPACE, KeyGrid.nearest(X, Y, 480, 360));
        assertEquals(KeyGrid.NONE, KeyGrid.nearest(new int[0], new int[0], 0, 0));
    }
}
