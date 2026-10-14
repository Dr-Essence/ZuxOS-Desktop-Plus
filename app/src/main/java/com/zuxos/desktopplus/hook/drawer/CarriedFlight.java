package com.zuxos.desktopplus.hook.drawer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import com.zuxos.desktopplus.core.L;
import com.zuxos.desktopplus.core.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The apps chosen with a dragged one ({@link DrawerSelect}) seen going with it, as ZUI's home
 * screen shows its chosen icons ({@code MultiDragSupport}): each gets a picture of its own, the
 * pictures gather under the finger behind the dragged app in 150 ms
 * ({@code initSelectedViewAndDragView}) and follow it, and at the drop each flies onto where its
 * app now is - its new cell, or the folder it went into - and goes ({@code MultiDragSupport.x},
 * 300 ms, linear). Without them the chosen apps just vanished into the folder (owner, 0.0.269).
 * Only the chosen apps on screen get one; the rest move as before.
 */
final class CarriedFlight {

    private static final long GATHER_MS = 150L;
    private static final long FLY_MS = 300L;

    private static final class Picture {
        final String key;
        final View cell;
        final View view;
        final float homeX;
        final float homeY;

        Picture(String key, View cell, View view, float homeX, float homeY) {
            this.key = key;
            this.cell = cell;
            this.view = view;
            this.homeX = homeX;
            this.homeY = homeY;
        }
    }

    private final ViewGroup mLayer;
    private final List<Picture> mPictures = new ArrayList<>();
    private final long mGathered;
    private final float mStep;

    private CarriedFlight(ViewGroup layer) {
        mLayer = layer;
        mGathered = android.os.SystemClock.uptimeMillis() + GATHER_MS;
        mStep = Ui.dp(layer.getContext(), 6);
    }

    /** The chosen apps' pictures, over their cells, under the dragged app's; null for none. */
    static CarriedFlight gather(View dragLayer, View dragView, DrawerReorder reorder,
            List<String> keys) {
        if (!(dragLayer instanceof ViewGroup) || keys.isEmpty()) {
            return null;
        }
        try {
            ViewGroup layer = (ViewGroup) dragLayer;
            CarriedFlight flight = new CarriedFlight(layer);
            int under = dragView != null && dragView.getParent() == layer
                    ? layer.indexOfChild(dragView) : layer.getChildCount();
            int[] origin = new int[2];
            layer.getLocationOnScreen(origin);
            for (String key : keys) {
                View cell = reorder.cellOf(key);
                if (cell == null || cell.getWidth() <= 0 || cell.getHeight() <= 0) {
                    continue;
                }
                Bitmap bitmap = Bitmap.createBitmap(cell.getWidth(), cell.getHeight(),
                        Bitmap.Config.ARGB_8888);
                cell.draw(new Canvas(bitmap));
                View picture = new View(layer.getContext());
                picture.setBackground(new BitmapDrawable(layer.getResources(), bitmap));
                int[] at = new int[2];
                cell.getLocationOnScreen(at);
                float x = at[0] - origin[0];
                float y = at[1] - origin[1];
                picture.setTranslationX(x);
                picture.setTranslationY(y);
                // Placed by translation: the drag layer makes layout params of its own kind.
                layer.addView(picture, Math.min(under, layer.getChildCount()),
                        new ViewGroup.LayoutParams(cell.getWidth(), cell.getHeight()));
                under++;
                DrawerReorder.touched(cell);
                cell.setAlpha(0f);
                flight.mPictures.add(new Picture(key, cell, picture, x, y));
            }
            return flight.mPictures.isEmpty() ? null : flight;
        } catch (Throwable t) {
            L.d("drawer select: the chosen apps not shown going (" + t + ")");
            return null;
        }
    }

    /** The apps whose pictures are away from their cells. */
    List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (Picture picture : mPictures) {
            keys.add(picture.key);
        }
        return keys;
    }

    /** The finger at ({@code sx}, {@code sy}) on the screen: the pictures stacked behind it. */
    void follow(float sx, float sy) {
        int[] origin = new int[2];
        mLayer.getLocationOnScreen(origin);
        boolean gathering = android.os.SystemClock.uptimeMillis() < mGathered;
        for (int i = 0; i < mPictures.size(); i++) {
            View view = mPictures.get(i).view;
            float offset = mStep * (i + 1);
            float x = sx - origin[0] - view.getWidth() / 2f + offset;
            float y = sy - origin[1] - view.getHeight() / 2f + offset;
            if (gathering) {
                view.animate().translationX(x).translationY(y).scaleX(1f).scaleY(1f)
                        .setDuration(Math.max(16L, mGathered
                                - android.os.SystemClock.uptimeMillis()))
                        .setInterpolator(new LinearInterpolator()).start();
            } else {
                view.animate().cancel();
                view.setTranslationX(x);
                view.setTranslationY(y);
            }
        }
    }

    /** Let go in the drawer: each onto its app's cell now, or into the folder {@code into}. */
    void land(Function<String, View> cellOf, View into) {
        for (Picture picture : mPictures) {
            View target = into != null ? into : cellOf.apply(picture.key);
            if (target == null || !target.isShown()) {
                fade(picture);
                continue;
            }
            int[] origin = new int[2];
            int[] at = new int[2];
            mLayer.getLocationOnScreen(origin);
            target.getLocationOnScreen(at);
            float scale = into != null ? 0.35f : 1f;
            float x = at[0] - origin[0] + target.getWidth() * (1f - scale) / 2f
                    - picture.view.getWidth() * (1f - scale) / 2f;
            float y = at[1] - origin[1] + target.getWidth() * (1f - scale) / 2f
                    - picture.view.getHeight() * (1f - scale) / 2f;
            if (into == null) {
                // Its cell stays hidden for the picture to land on it, whatever the drawer's
                // settling was doing to it.
                DrawerReorder.touched(target);
                target.animate().cancel();
                target.setScaleX(1f);
                target.setScaleY(1f);
                target.setAlpha(0f);
            }
            picture.view.animate().translationX(x).translationY(y).scaleX(scale).scaleY(scale)
                    .alpha(into != null ? 0f : 1f).setDuration(FLY_MS)
                    .setInterpolator(new LinearInterpolator())
                    .withEndAction(() -> {
                        remove(picture.view);
                        if (into == null) {
                            target.setAlpha(1f);
                        }
                    }).start();
        }
        mPictures.clear();
    }

    /** Pinned or gone on: the pictures fade where they are, the cells show again. */
    void fadeOut() {
        for (Picture picture : mPictures) {
            fade(picture);
        }
        mPictures.clear();
    }

    /** Nothing moved: each flies back onto its own cell. */
    void back() {
        for (Picture picture : mPictures) {
            picture.view.animate().translationX(picture.homeX).translationY(picture.homeY)
                    .scaleX(1f).scaleY(1f).setDuration(FLY_MS)
                    .setInterpolator(new LinearInterpolator())
                    .withEndAction(() -> {
                        remove(picture.view);
                        picture.cell.setAlpha(1f);
                    }).start();
        }
        mPictures.clear();
    }

    private void fade(Picture picture) {
        picture.cell.setAlpha(1f);
        picture.view.animate().alpha(0f).setDuration(GATHER_MS)
                .withEndAction(() -> remove(picture.view)).start();
    }

    private static void remove(View view) {
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }
}
