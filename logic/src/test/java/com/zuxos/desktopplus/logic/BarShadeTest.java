package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A title bar's buttons follow the bar they sit on, not only the night mode. */
public class BarShadeTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int CLEAR = 0x00000000;

    @Test
    public void theBarsOwnColourComesFirst() {
        assertFalse(BarShade.dark(WHITE, false, 0, BLACK, true));
        assertTrue(BarShade.dark(BLACK, true, BarShade.APPEARANCE_LIGHT_STATUS_BARS, WHITE,
                false));
    }

    @Test
    public void theAppsOwnPixelsComeNext() {
        // FLOW at night: a see-through bar over its white top, the app saying nothing.
        assertFalse(BarShade.dark(CLEAR, true, 0, CLEAR, true));
        assertTrue(BarShade.dark(CLEAR, false, BarShade.APPEARANCE_LIGHT_STATUS_BARS, WHITE,
                false));
    }

    @Test
    public void aLightTopTheAppDeclaresGetsDarkButtons() {
        assertFalse(BarShade.dark(CLEAR, null, BarShade.APPEARANCE_LIGHT_STATUS_BARS, CLEAR,
                true));
        assertFalse(BarShade.dark(CLEAR, null, BarShade.APPEARANCE_LIGHT_CAPTION_BARS, CLEAR,
                true));
    }

    @Test
    public void theStatusBarColourWhenTheAppSaysNothing() {
        assertFalse(BarShade.dark(CLEAR, null, 0, WHITE, true));
        assertTrue(BarShade.dark(CLEAR, null, 0, BLACK, false));
    }

    @Test
    public void halfSeenColoursDoNotCount() {
        assertTrue(BarShade.dark(0x80FFFFFF, null, 0, 0x40FFFFFF, true));
        assertFalse(BarShade.dark(0x80000000, null, 0, CLEAR, false));
    }

    @Test
    public void nothingKnownFollowsTheNightMode() {
        assertTrue(BarShade.dark(CLEAR, null, 0, CLEAR, true));
        assertFalse(BarShade.dark(CLEAR, null, 0, CLEAR, false));
    }

    @Test
    public void aWhiteStripWithDarkTextIsLight() {
        int[] strip = {WHITE, WHITE, WHITE, BLACK, WHITE, 0xFF202020, WHITE};
        assertEquals(Boolean.TRUE, BarShade.light(strip));
    }

    @Test
    public void aDarkStripWithLightTextIsDark() {
        int[] strip = {0xFF121212, WHITE, 0xFF121212, 0xFF1E1E1E, WHITE, 0xFF121212};
        assertEquals(Boolean.FALSE, BarShade.light(strip));
    }

    @Test
    public void halfLumaIsNotYetLight() {
        assertEquals(Boolean.FALSE, BarShade.light(new int[]{0xFF7F7F7F}));
        assertEquals(Boolean.TRUE, BarShade.light(new int[]{0xFF818181}));
    }

    @Test
    public void noPixelsNoAnswer() {
        assertNull(BarShade.light(new int[0]));
        assertNull(BarShade.light(null));
        assertTrue(Float.isNaN(BarShade.medianLuma(new int[0])));
    }
}
