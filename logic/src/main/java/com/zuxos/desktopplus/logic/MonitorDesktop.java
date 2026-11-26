package com.zuxos.desktopplus.logic;

/**
 * Whether ZUI's monitor desktop missed a screen that was just added, and so has to be started
 * for it again.
 *
 * <p>ZUI starts its monitor desktop when its setting turns on, for the external screens the window
 * manager has at that moment. When the monitor's screen goes and comes back within a second, the
 * setting can turn on before the window manager has the new screen: ZUI's start finds none, and
 * the new screen is made without ZUI's desktop - no home, no floating windows - for as long as the
 * monitor stays plugged in. This decides, once the window manager has the screen, whether ZUI's
 * own start has to run for it.
 */
public final class MonitorDesktop {

    /** {@code Display.TYPE_EXTERNAL}: a monitor on a cable. */
    public static final int TYPE_EXTERNAL = 2;
    /** {@code Display.TYPE_OVERLAY}: the other kind ZUI's monitor desktop takes. */
    public static final int TYPE_OVERLAY = 4;
    /** {@code zui_dp_display_pc_mode}'s value for the monitor as ZUI's desktop. */
    public static final int DESKTOP = 1;

    private MonitorDesktop() {
    }

    /**
     * @param setting         ZUI's {@code zui_dp_display_pc_mode} now
     * @param type            the added screen's type
     * @param added           the added screen's number
     * @param inWindowManager whether the window manager has the added screen
     * @param workMode        whether ZUI's work mode (its PC mode) is on
     * @param desktop         ZUI's desktop screen ({@code getExternalDisplayDesktopDisplayId}),
     *                        -1 for none
     * @param desktopThere    whether the window manager has that screen
     * @param flag            the window manager's {@code mForceDesktopModeOnExternalDisplays},
     *                        which ZUI's start sets
     * @return true when ZUI's own start has to run for the added screen
     */
    public static boolean shouldStart(int setting, int type, int added, boolean inWindowManager,
            boolean workMode, int desktop, boolean desktopThere, boolean flag) {
        if (setting != DESKTOP || workMode || !inWindowManager || added <= 0) {
            // The monitor as a mirror, work mode, or a screen the window manager does not have.
            return false;
        }
        if (type != TYPE_EXTERNAL && type != TYPE_OVERLAY) {
            // A screen ZUI's desktop never takes: a virtual one, a wireless one.
            return false;
        }
        if (desktop > 0 && desktop != added && desktopThere) {
            // ZUI's desktop is on another screen that is still there: a second monitor is ZUI's.
            return false;
        }
        // Up on this screen when ZUI calls it its desktop and the window manager's flag is set.
        return !(desktop == added && flag);
    }
}
