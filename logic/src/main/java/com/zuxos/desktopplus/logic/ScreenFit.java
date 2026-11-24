package com.zuxos.desktopplus.logic;

/**
 * A picture of one screen shown on another: scaled to fill it, centred, the overhang cut off by
 * the screen's own edges. The screen saver's copy on the other screen uses it; filling, not
 * fitting, so the copy needs no bars of its own around it.
 */
public final class ScreenFit {

    private ScreenFit() {
    }

    /**
     * The scale and offset that fill a {@code dstW} x {@code dstH} screen with a
     * {@code srcW} x {@code srcH} picture, keeping its shape.
     *
     * @return {@code {scale, dx, dy}}: draw the picture at {@code (dx, dy)}, scaled by
     *         {@code scale}; an offset is negative where the picture overhangs. {@code null} for
     *         a size that is not positive.
     */
    public static float[] cover(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return null;
        }
        float scale = Math.max(dstW / (float) srcW, dstH / (float) srcH);
        float dx = (dstW - srcW * scale) / 2f;
        float dy = (dstH - srcH * scale) / 2f;
        return new float[]{scale, dx, dy};
    }
}
