package com.zuxos.desktopplus.core.theme;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.zuxos.desktopplus.core.Cfg;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.logic.BevelMath;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The one place a surface asks how to paint itself, for the display it is on.
 *
 * <p>Glass is the module's own look and the default everywhere. Retro - Windows 98: grey
 * bevelled boxes, navy selection, black text, a pixel font - is for the monitor's desktop only,
 * and it is also the light one: no live glass, no blur, no springs. The tablet always gets Glass,
 * whatever the setting says.
 *
 * <p>Asked by display id where a surface knows it, which is safest: an overlay's window context
 * falls back to the tablet's when it cannot be had. By view otherwise.
 */
public final class Theme {

    /** {@code Const.KEY_THEME}'s values. */
    public static final int GLASS_ID = 0;
    public static final int RETRO_ID = 1;

    public static final Theme GLASS = new Theme(false);
    public static final Theme RETRO = new Theme(true);

    /** Windows 98's selection: navy, with white on it. */
    public static final int NAVY = 0xFF000080;
    /** Retro's text - black, on the grey face - and its quieter text. */
    private static final int RETRO_TEXT = BevelMath.textOn(BevelMath.FACE);
    private static final int RETRO_DIM_TEXT = 0xFF404040;

    /** Each text's own face, size and paint flags while Retro's font stands in. Main thread. */
    private static final Map<TextView, Object[]> OWN = new WeakHashMap<>();

    private final boolean mRetro;

    private Theme(boolean retro) {
        mRetro = retro;
    }

    public static Theme of(int displayId) {
        return displayId > Display.DEFAULT_DISPLAY && Cfg.theme() == RETRO_ID ? RETRO : GLASS;
    }

    public static Theme of(View view) {
        return view == null ? GLASS : of(Ui.displayOf(view));
    }

    public boolean retro() {
        return mRetro;
    }

    public int text() {
        return mRetro ? RETRO_TEXT : Ui.COLOR_TEXT;
    }

    public int dimText() {
        return mRetro ? RETRO_DIM_TEXT : Ui.COLOR_TEXT_DIM;
    }

    /** What a button that acts stands out in: Retro's navy, or Glass's accent. */
    public int accent() {
        return mRetro ? NAVY : Ui.COLOR_ACCENT;
    }

    /** Whether things move: Retro steps rather than springs, and the user's switch holds. */
    public boolean animates() {
        return !mRetro && Cfg.animations();
    }

    /**
     * A tappable button's background: Glass's ripple at {@code radiusPx}, or Retro's raised box,
     * sunken while pressed.
     */
    public Drawable button(Context ctx, int color, int radiusPx) {
        return mRetro ? Bevel.button(ctx) : Ui.ripple(ctx, color, radiusPx);
    }

    /** The row under the pointer in a menu: Retro's navy bar. Null for Glass, which has its own. */
    public Drawable selection() {
        return mRetro ? new ColorDrawable(NAVY) : null;
    }

    /** The pixel font in Retro; null for Glass, which keeps the system's. */
    public Typeface font(Context ctx) {
        return mRetro ? PixelFont.get(ctx) : null;
    }

    /**
     * A size for text drawn in this theme: in Retro, the pixel font's whole pixels, so it is
     * sharp; in Glass, as asked.
     */
    public float textPx(float px) {
        return mRetro ? PixelFont.snap(px) : px;
    }

    /** How many screen pixels one of the pixel font's pixels takes, at this text size. */
    public static int fontPixel(float textPx) {
        return Math.max(1, Math.round(textPx / PixelFont.PIXEL_PX));
    }

    /**
     * One text in this theme's font. Retro: the pixel font at its whole pixels and unsmoothed,
     * so each of its pixels is one of the screen's; the text's own face, size and smoothing are
     * kept. Glass: those put back, for a text that was Retro's.
     */
    public void dress(TextView text) {
        if (text == null) {
            return;
        }
        if (!mRetro) {
            Object[] own = OWN.remove(text);
            if (own != null) {
                text.setTypeface((Typeface) own[0]);
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, (Float) own[1]);
                text.setPaintFlags((Integer) own[2]);
            }
            return;
        }
        if (!OWN.containsKey(text)) {
            OWN.put(text, new Object[]{text.getTypeface(), text.getTextSize(),
                    text.getPaintFlags()});
        }
        text.setTypeface(PixelFont.get(text.getContext()));
        float px = PixelFont.snap(text.getTextSize());
        if (px != text.getTextSize()) {
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        }
        text.setPaintFlags(text.getPaintFlags()
                & ~(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG));
    }

    /** Every text under {@code root} in this theme's font ({@link #dress}). Nothing for Glass. */
    public void applyFont(View root) {
        if (!mRetro || root == null) {
            return;
        }
        apply(root);
    }

    private void apply(View view) {
        if (view instanceof TextView) {
            dress((TextView) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                apply(group.getChildAt(i));
            }
        }
    }
}
