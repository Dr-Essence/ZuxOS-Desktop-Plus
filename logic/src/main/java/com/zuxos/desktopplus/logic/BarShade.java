package com.zuxos.desktopplus.logic;

/**
 * Whether a window's title bar on the monitor is dark, so the buttons on it are drawn light. A
 * floating window's bar is see-through over the app's own top, which the app colours itself:
 * FLOW's is white, and buttons picked by the night mode alone were white on white. ZUI's own
 * light or dark test samples the screen under its pill, and only on the tablet.
 *
 * <p>So, in order: the bar's own colour where it is opaque; else the app's own word that its top
 * is light (a light status bar, or a light caption bar, as Android's desktop reads it); else the
 * app's status bar colour where it is opaque; else the night mode, as before.
 */
public final class BarShade {

    /** {@code WindowInsetsController}'s: dark icons on the app's light status bar. */
    public static final int APPEARANCE_LIGHT_STATUS_BARS = 1 << 3;
    /** {@code WindowInsetsController}'s: dark buttons on the app's light caption bar. */
    public static final int APPEARANCE_LIGHT_CAPTION_BARS = 1 << 8;

    private BarShade() {
    }

    /**
     * @param barColour       the bar's own colour, 0 for none
     * @param appearance      the app's system bars appearance
     * @param statusBarColour the app's status bar colour, 0 for none
     * @param night           the night mode
     */
    public static boolean dark(int barColour, int appearance, int statusBarColour,
            boolean night) {
        if (opaque(barColour)) {
            return !ToneMath.isLight(barColour);
        }
        if ((appearance & (APPEARANCE_LIGHT_STATUS_BARS | APPEARANCE_LIGHT_CAPTION_BARS)) != 0) {
            return false;
        }
        if (opaque(statusBarColour)) {
            return !ToneMath.isLight(statusBarColour);
        }
        return night;
    }

    private static boolean opaque(int argb) {
        return (argb >>> 24) == 0xFF;
    }
}
