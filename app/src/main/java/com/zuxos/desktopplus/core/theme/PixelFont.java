package com.zuxos.desktopplus.core.theme;

import android.content.Context;
import android.graphics.Typeface;

import com.zuxos.desktopplus.core.Const;
import com.zuxos.desktopplus.core.L;

/**
 * Retro's font, W95FA by Alina Sava (SIL Open Font License; see NOTICE.md): Windows 95's MS Sans
 * Serif redrawn, whose own files may not be given away. Bundled in the module's own APK.
 *
 * <p>Everything else the module shows inside the launcher is drawn in code, because the module's
 * resources are not on the launcher's path. A font cannot be drawn by hand, so it is read from
 * the module's APK through a context of the module's package - once, and kept. If that fails,
 * monospace stands in, and the log says why, once.
 *
 * <p>It is drawn on a grid of 80 units in an em of 1000, so at {@link #PIXEL_PX} each of its
 * pixels is one of the screen's - and unsmoothed, it is as sharp as Windows 95 drew it.
 */
final class PixelFont {

    private static final String ASSET = "fonts/W95FA.otf";
    /** The text size at which one of the font's pixels is one screen pixel. */
    static final float PIXEL_PX = 12.5f;

    private static Typeface sFont;
    private static boolean sTried;

    private PixelFont() {
    }

    static synchronized Typeface get(Context ctx) {
        if (!sTried) {
            sTried = true;
            try {
                Context module = ctx.createPackageContext(Const.MODULE_PKG, 0);
                sFont = Typeface.createFromAsset(module.getAssets(), ASSET);
                L.i("retro: pixel font loaded");
            } catch (Throwable t) {
                L.w("retro: no pixel font (" + t + "), monospace instead");
            }
        }
        return sFont != null ? sFont : Typeface.MONOSPACE;
    }

    /**
     * A text size in the font's whole pixels: the largest multiple of {@link #PIXEL_PX} not over
     * {@code px} - never larger than asked, so nothing laid out for it grows - and one at least.
     */
    static float snap(float px) {
        return Math.max(1, (int) (px / PIXEL_PX + 0.05f)) * PIXEL_PX;
    }
}
