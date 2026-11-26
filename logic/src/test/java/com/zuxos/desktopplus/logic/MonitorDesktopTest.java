package com.zuxos.desktopplus.logic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ZUI's monitor desktop is started again only for a screen its own start missed. */
public class MonitorDesktopTest {

    private static final int ON = MonitorDesktop.DESKTOP;
    private static final int CABLE = MonitorDesktop.TYPE_EXTERNAL;

    @Test
    public void startMissedTheNewScreenAndCalledNoneItsDesktop() {
        // ZUI's start ran before the window manager had screen 5: no desktop screen, no flag.
        assertTrue(MonitorDesktop.shouldStart(ON, CABLE, 5, true, false, -1, false, false));
    }

    @Test
    public void screenAddedWithTheSettingOnGotNoFlag() {
        // ZUI's display-added step named screen 5 its desktop but set no flag and no home.
        assertTrue(MonitorDesktop.shouldStart(ON, CABLE, 5, true, false, 5, true, false));
    }

    @Test
    public void desktopStillNamingTheScreenThatWent() {
        assertTrue(MonitorDesktop.shouldStart(ON, CABLE, 5, true, false, 4, false, false));
    }

    @Test
    public void desktopAlreadyUpOnTheScreenIsLeftAlone() {
        assertFalse(MonitorDesktop.shouldStart(ON, CABLE, 5, true, false, 5, true, true));
    }

    @Test
    public void normalPlugInWithTheSettingStillOffIsZuis() {
        // At a plug-in the setting turns on after the screen is there: ZUI's start does it all.
        assertFalse(MonitorDesktop.shouldStart(0, CABLE, 4, true, false, -1, false, false));
    }

    @Test
    public void mirrorModeIsLeftAlone() {
        assertFalse(MonitorDesktop.shouldStart(0, CABLE, 5, true, false, -1, false, false));
    }

    @Test
    public void secondMonitorBesideAWorkingDesktopIsLeftAlone() {
        assertFalse(MonitorDesktop.shouldStart(ON, CABLE, 3, true, false, 2, true, true));
        // Even where that desktop's flag reads off: the screen ZUI chose is still there.
        assertFalse(MonitorDesktop.shouldStart(ON, CABLE, 3, true, false, 2, true, false));
    }

    @Test
    public void workModeIsLeftAlone() {
        assertFalse(MonitorDesktop.shouldStart(ON, CABLE, 5, true, true, -1, false, false));
    }

    @Test
    public void screensZuiNeverTakesAreLeftAlone() {
        // Virtual (5) and wireless (3) screens.
        assertFalse(MonitorDesktop.shouldStart(ON, 5, 6, true, false, -1, false, false));
        assertFalse(MonitorDesktop.shouldStart(ON, 3, 6, true, false, -1, false, false));
        assertTrue(MonitorDesktop.shouldStart(ON, MonitorDesktop.TYPE_OVERLAY, 6, true, false, -1,
                false, false));
    }

    @Test
    public void screenTheWindowManagerDoesNotHaveIsLeftAlone() {
        assertFalse(MonitorDesktop.shouldStart(ON, CABLE, 5, false, false, -1, false, false));
        assertFalse(MonitorDesktop.shouldStart(ON, CABLE, 0, true, false, -1, false, false));
    }
}
