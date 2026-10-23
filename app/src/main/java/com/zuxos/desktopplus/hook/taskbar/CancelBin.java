package com.zuxos.desktopplus.hook.taskbar;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.zuxos.desktopplus.core.L;

/**
 * ZUI's Cancel target, at the top of the screen, for a drag out of the taskbar's drawer that has
 * become the system's - the floating window and split screen - while an app is in front.
 *
 * <p>ZUI shows its own ({@code DropTargetBar2} with {@code DeleteDropTarget2}, "Cancel" for a
 * drawer's app) only on ZUX Home's own drag layer: with the home screen behind, the drag is handed
 * there and ZUI's own appears. Over an app there is none, and ZUI's class needs ZUX Home's
 * {@code Launcher} to be made. So this one is made of ZUI's own parts, read from ZUX Home's
 * resources: its background ({@code ic_drop_target_bg}) in its colours, its icon
 * ({@code ic_remove_target}), the system's "Cancel", its size and place
 * ({@code dynamic_grid_drop_target_size_2}, {@code ..._margin_top}) and its 175 ms fade. Let go
 * on it, the drag is taken and nothing opens.
 */
final class CancelBin {

    private CancelBin() {
    }

    /** ZUI's Cancel target added to the window of {@code root}, fading in; null if not made. */
    static TextView show(ViewGroup root) {
        try {
            Context ctx = root.getContext();
            Resources res = ctx.getResources();
            String pkg = ctx.getPackageName();
            int sizeId = res.getIdentifier("dynamic_grid_drop_target_size_2", "dimen", pkg);
            int topId = res.getIdentifier("dynamic_grid_drop_target_margin_top", "dimen", pkg);
            int textId = res.getIdentifier("dynamic_grid_drop_target_textsize", "dimen", pkg);
            int iconId = res.getIdentifier("ic_remove_target", "drawable", pkg);
            int backId = res.getIdentifier("ic_drop_target_bg", "drawable", pkg);
            if (sizeId == 0 || iconId == 0 || backId == 0) {
                if (L.once("CancelBin.missing")) {
                    L.w("cancel bin: ZUI's Cancel target's parts not found - no bin");
                }
                return null;
            }
            int width = res.getDimensionPixelSize(sizeId);
            TextView bin = new TextView(ctx);
            bin.setText(android.R.string.cancel);
            bin.setTextColor(Color.WHITE);
            bin.setGravity(Gravity.CENTER);
            if (textId != 0) {
                bin.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(textId));
            }
            Drawable icon = ctx.getDrawable(iconId);
            if (icon != null) {
                bin.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
                bin.setCompoundDrawablePadding(Math.round(width * 0.04f));
            }
            Drawable back = ctx.getDrawable(backId);
            if (back != null) {
                back = back.mutate();
                bin.setBackground(back);
            }
            colour(bin, false);
            int top = topId != 0 ? res.getDimensionPixelSize(topId) : 0;
            if (root.getRootWindowInsets() != null) {
                top += root.getRootWindowInsets().getStableInsetTop();
            }
            // Placed by translation, top centre: the drawer's layer makes layout params of its
            // own kind, which keep a size but not a gravity or margins.
            bin.setTranslationX((root.getWidth() - width) / 2f);
            bin.setTranslationY(top);
            bin.setAlpha(0f);
            root.addView(bin, new ViewGroup.LayoutParams(width, width / 2));
            bin.bringToFront();
            // ZUI's own fade for its drop target bar (DropTargetBar.animateToVisibility).
            bin.animate().alpha(1f).setDuration(175L).start();
            bin.postDelayed(() -> trace(root, bin), 250L);
            return bin;
        } catch (Throwable t) {
            L.w("cancel bin: not shown (" + t + ")");
            return null;
        }
    }

    /**
     * Read-only: where the bin is once laid out, traced because none showed though the drag
     * reached the drawer's window to its end. Once a drag.
     */
    private static void trace(ViewGroup root, View bin) {
        try {
            int[] at = new int[2];
            bin.getLocationOnScreen(at);
            L.i("cancel bin: trace: in " + root.getClass().getSimpleName() + " "
                    + root.getWidth() + "x" + root.getHeight() + " (window "
                    + (root.getWindowVisibility() == View.VISIBLE ? "visible" : "not visible")
                    + ", shown " + root.isShown() + ", alpha " + root.getAlpha() + "), bin at "
                    + at[0] + "," + at[1] + " " + bin.getWidth() + "x" + bin.getHeight()
                    + ", attached " + bin.isAttachedToWindow() + ", shown " + bin.isShown()
                    + ", alpha " + bin.getAlpha());
        } catch (Throwable t) {
            L.d("cancel bin: not traced (" + t + ")");
        }
    }

    /** Whether a point of the screen is over the bin. */
    static boolean contains(View bin, float sx, float sy) {
        if (bin == null || !bin.isAttachedToWindow()) {
            return false;
        }
        int[] at = new int[2];
        bin.getLocationOnScreen(at);
        return sx >= at[0] && sx <= at[0] + bin.getWidth() && sy >= at[1]
                && sy <= at[1] + bin.getHeight();
    }

    /** The bin in ZUI's colours, lit while the drag is over it ({@code setBackgroundHover}). */
    static void colour(View bin, boolean over) {
        try {
            if (!(bin.getBackground() instanceof GradientDrawable)) {
                return;
            }
            Resources res = bin.getResources();
            int id = res.getIdentifier(over ? "delete_target_background_hover"
                    : "delete_target_background", "color", bin.getContext().getPackageName());
            if (id != 0) {
                ((GradientDrawable) bin.getBackground()).setColor(res.getColor(id, null));
            }
        } catch (Throwable t) {
            L.d("cancel bin: colour left (" + t + ")");
        }
    }

    /** The bin fades out and goes. */
    static void hide(View bin) {
        if (bin == null) {
            return;
        }
        bin.animate().alpha(0f).setDuration(175L).withEndAction(() -> {
            if (bin.getParent() instanceof ViewGroup) {
                ((ViewGroup) bin.getParent()).removeView(bin);
            }
        }).start();
    }
}
