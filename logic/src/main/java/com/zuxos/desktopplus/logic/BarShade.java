package com.zuxos.desktopplus.logic;

import java.util.Arrays;

/**
 * Whether a window's title bar on the monitor is dark, so the buttons on it are drawn light. A
 * floating window's bar is see-through over the app's own top, which the app colours itself:
 * FLOW's is white, and buttons picked by the night mode alone were white on white. ZUI's own
 * light or dark test samples the screen under its pill, and only on the tablet: Android samples
 * only the default display.
 *
 * <p>So, in order: the bar's own colour where it is opaque; else the app's own pixels under the
 * bar, sampled, light above half luma as ZUI's test; else the app's own word that its top is
 * light (a light status bar, or a light caption bar, as Android's desktop reads it); else the
 * app's status bar colour where it is opaque; else the night mode, as before.
 */
public final class BarShade {

    /** {@code WindowInsetsController}'s: dark icons on the app's light status bar. */
    public static final int APPEARANCE_LIGHT_STATUS_BARS = 1 << 3;
    /** {@code WindowInsetsController}'s: dark buttons on the app's light caption bar. */
    public static final int APPEARANCE_LIGHT_CAPTION_BARS = 1 << 8;
    /** ZUI's own line between light and dark under its pill. */
    public static final float LIGHT_LUMA = 0.5f;

    private BarShade() {
    }

    /**
     * @param barColour       the bar's own colour, 0 for none
     * @param sampledLight    the app's pixels under the bar: light, dark, or null when unknown
     * @param appearance      the app's system bars appearance
     * @param statusBarColour the app's status bar colour, 0 for none
     * @param night           the night mode
     */
    public static boolean dark(int barColour, Boolean sampledLight, int appearance,
            int statusBarColour, boolean night) {
        if (opaque(barColour)) {
            return !ToneMath.isLight(barColour);
        }
        if (sampledLight != null) {
            return !sampledLight;
        }
        if ((appearance & (APPEARANCE_LIGHT_STATUS_BARS | APPEARANCE_LIGHT_CAPTION_BARS)) != 0) {
            return false;
        }
        if (opaque(statusBarColour)) {
            return !ToneMath.isLight(statusBarColour);
        }
        return night;
    }

    /** Whether sampled pixels are light, by their median luma; null for none. */
    public static Boolean light(int[] argb) {
        float luma = medianLuma(argb);
        return Float.isNaN(luma) ? null : luma > LIGHT_LUMA;
    }

    /**
     * The median luma of the pixels, 0 to 1, as the screen's own sampling gives it: a median, so
     * the title text or an icon in the strip does not tip it. NaN for none.
     */
    public static float medianLuma(int[] argb) {
        if (argb == null || argb.length == 0) {
            return Float.NaN;
        }
        float[] lumas = new float[argb.length];
        for (int i = 0; i < argb.length; i++) {
            lumas[i] = (float) ToneMath.luminance(argb[i]);
        }
        Arrays.sort(lumas);
        int middle = lumas.length / 2;
        return lumas.length % 2 == 1 ? lumas[middle]
                : (lumas[middle - 1] + lumas[middle]) / 2f;
    }

    private static boolean opaque(int argb) {
        return (argb >>> 24) == 0xFF;
    }
}
