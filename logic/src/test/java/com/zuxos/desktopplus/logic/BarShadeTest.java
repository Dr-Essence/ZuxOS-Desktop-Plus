package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A title bar's buttons follow the bar they sit on, not only the night mode. */
public class BarShadeTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int CLEAR = 0x00000000;

    @Test
    public void theBarsOwnColourComesFirst() {
        assertFalse(BarShade.dark(WHITE, 0, BLACK, true));
        assertTrue(BarShade.dark(BLACK, BarShade.APPEARANCE_LIGHT_STATUS_BARS, WHITE, false));
    }

    @Test
    public void aLightTopTheAppDeclaresGetsDarkButtons() {
        // FLOW at night: a see-through bar over its white top.
        assertFalse(BarShade.dark(CLEAR, BarShade.APPEARANCE_LIGHT_STATUS_BARS, CLEAR, true));
        assertFalse(BarShade.dark(CLEAR, BarShade.APPEARANCE_LIGHT_CAPTION_BARS, CLEAR, true));
    }

    @Test
    public void theStatusBarColourWhenTheAppSaysNothing() {
        assertFalse(BarShade.dark(CLEAR, 0, WHITE, true));
        assertTrue(BarShade.dark(CLEAR, 0, BLACK, false));
    }

    @Test
    public void halfSeenColoursDoNotCount() {
        assertTrue(BarShade.dark(0x80FFFFFF, 0, 0x40FFFFFF, true));
        assertFalse(BarShade.dark(0x80000000, 0, CLEAR, false));
    }

    @Test
    public void nothingKnownFollowsTheNightMode() {
        assertTrue(BarShade.dark(CLEAR, 0, CLEAR, true));
        assertFalse(BarShade.dark(CLEAR, 0, CLEAR, false));
    }
}
