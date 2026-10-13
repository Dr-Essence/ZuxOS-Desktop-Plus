package com.zuxos.desktopplus.hook.drawer;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.zuxos.desktopplus.core.Reflect;
import com.zuxos.desktopplus.core.Ui;
import com.zuxos.desktopplus.core.motion.Motion;
import com.zuxos.desktopplus.hook.taskbar.BarEdge;
import com.zuxos.desktopplus.hook.taskbar.TaskbarTray;
import com.zuxos.desktopplus.logic.DrawerArrange;
import com.zuxos.desktopplus.model.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rearranging ZUI's own app drawer by dragging an app within it, the way ZUI rearranges its home
 * screen.
 *
 * <p>While a held app moves over the drawer, the icons between where it was and where it is now
 * slide over by one place on a spring, opening the gap it will drop into; held near the top or
 * the bottom of the grid, the grid scrolls. Held still over the middle of another app it folds
 * into it: that app swells, and let go there the two make a folder; over one of our folders, the
 * app goes in it. An app dragged out of one of our folders opens a gap of its own and, let go,
 * leaves the folder for it. Let go over the grid and the new order is kept - the same order the
 * module's own drawer uses - and ZUI's list re-sorts from where the icons are drawn, so nothing
 * jumps.
 *
 * <p>Only the drawer's views and their geometry are used: the grid's own classes are minified on
 * this firmware, and none of their methods can be counted on by name.
 */
final class DrawerReorder {

    /** How close to the grid's top or bottom edge the drag has to be to scroll it. */
    private static final int EDGE_DP = 56;
    private static final int SCROLL_STEP_DP = 14;
    /** How long a drag rests over the middle of an app before it folds into it, as ZUI's home. */
    private static final long FOLD_MS = 280L;
    /** The middle of a cell that folds, as a part of its icon's size. */
    private static final float FOLD_SPOT = 0.55f;
    /** How much the app folded into swells. */
    private static final float FOLD_SCALE = 1.18f;

    private final ViewGroup mRoot;
    private final ViewGroup mGrid;
    private final View mSheet;
    private final String mKey;
    private final int mDisplay;
    private List<String> mOrder;
    /** Where the dragged entry is in the drawer's order; -1 for an app from one of our folders. */
    private int mFrom;
    private int mTo;
    /** Where each icon was last sent, so an unchanged target sends nothing again. */
    private final Map<View, float[]> mSent = new HashMap<>();
    /** The cell the drag rests over the middle of, and the one it has folded into. */
    private View mResting;
    private View mFolding;
    private final Runnable mFold = this::fold;

    private DrawerReorder(ViewGroup root, ViewGroup grid, View sheet, String key, int display) {
        mRoot = root;
        mGrid = grid;
        mSheet = sheet;
        mKey = key;
        mDisplay = display;
        mOrder = NativeDrawerHooks.shownOrder();
        mFrom = mOrder.indexOf(key);
        mTo = mFrom;
    }

    /** For a drag picked up from {@code icon} in the stock drawer; null when it cannot be done. */
    static DrawerReorder begin(View icon) {
        String key = NativeDrawerHooks.keyOfIcon(icon);
        if (key == null || !(icon.getParent() instanceof ViewGroup)
                || !(icon.getRootView() instanceof ViewGroup)) {
            return null;
        }
        ViewGroup grid = (ViewGroup) icon.getParent();
        ViewGroup root = (ViewGroup) icon.getRootView();
        List<View> sheets = Reflect.findByIdNames(root, "bottom_sheet_background");
        DrawerReorder reorder = new DrawerReorder(root, grid,
                sheets.isEmpty() ? grid : sheets.get(0), key, TaskbarTray.displayIdOf(icon));
        return reorder.mFrom >= 0 ? reorder : null;
    }

    /**
     * For a drag of the entry {@code key} over the drawer's {@code grid}: one of its entries, or
     * an app from one of our folders, which the drawer does not show. Null when it cannot be done.
     */
    static DrawerReorder over(ViewGroup grid, String key) {
        if (key == null || !(grid.getRootView() instanceof ViewGroup)) {
            return null;
        }
        DrawerReorder reorder = new DrawerReorder((ViewGroup) grid.getRootView(), grid, grid, key,
                TaskbarTray.displayIdOf(grid));
        if (reorder.mOrder.isEmpty() || (reorder.mFrom < 0
                && DrawerArrange.appOf(key, null) == null)) {
            return null;
        }
        if (reorder.mFrom < 0) {
            // From a folder: it arrives at the end until it is over a place.
            reorder.mTo = reorder.mOrder.size();
        }
        return reorder;
    }

    /**
     * Whether a point of the drawer's window is over the drawer itself: inside its panel, and
     * above the taskbar - the panel runs on under the bar, and the bar is somewhere to drop.
     */
    boolean over(float x, float y) {
        Rect panel = onScreen(mSheet);
        int barTop = BarEdge.bottomBarTop(mDisplay);
        if (barTop > 0) {
            panel.bottom = Math.min(panel.bottom, barTop);
        }
        int[] root = new int[2];
        mRoot.getLocationOnScreen(root);
        return panel.contains(Math.round(x) + root[0], Math.round(y) + root[1]);
    }

    /** The drag is at ({@code x}, {@code y}) of the drawer's window, over the drawer. */
    void moveTo(float x, float y) {
        int[] root = new int[2];
        mRoot.getLocationOnScreen(root);
        moveToScreen(x + root[0], y + root[1]);
    }

    /** The drag is at ({@code sx}, {@code sy}) on the screen, over the drawer. */
    void moveToScreen(float sx, float sy) {
        Rect grid = onScreen(mGrid);
        int edge = Ui.dp(mGrid.getContext(), EDGE_DP);
        int step = Ui.dp(mGrid.getContext(), SCROLL_STEP_DP);
        if (sy < grid.top + edge) {
            mGrid.scrollBy(0, -step);
        } else if (sy > grid.bottom - edge) {
            mGrid.scrollBy(0, step);
        }
        float x = sx - grid.left;
        float y = sy - grid.top;
        View under = iconAt(x, y);
        View resting = under != null && canFold(under) && inMiddle(under, x, y) ? under : null;
        if (resting != mResting) {
            mGrid.removeCallbacks(mFold);
            mResting = resting;
            unfold();
            if (resting != null) {
                mGrid.postDelayed(mFold, FOLD_MS);
            }
        }
        if (resting != null) {
            // Resting on an app to fold into it: the gap stays where it was.
            return;
        }
        if (under != null) {
            String key = keyOfCell(under);
            int at = key == null ? -1 : mOrder.indexOf(key);
            if (at >= 0) {
                mTo = at;
            }
        }
        preview();
    }

    /** Back to how the drawer was, for a drag that left the drawer. */
    void cancel() {
        mGrid.removeCallbacks(mFold);
        mResting = null;
        unfold();
        mTo = mFrom >= 0 ? mFrom : mOrder.size();
        preview();
    }

    /**
     * Let go over the drawer: the order kept and the drawer re-sorted. The icons are left where
     * the preview drew them, and from there they settle into their new places.
     */
    void drop() {
        drop(null);
    }

    /** Where a dropped entry is drawn once the drawer has laid it out. */
    interface Landing {
        /**
         * @param cell the cell drawing it, or null when it is out of sight; hidden, unless the
         *             entry went into it, for the drag's own icon to land on
         * @param into whether the entry went into {@code cell}, a folder
         */
        void at(View cell, boolean into);
    }

    /** The same, and {@code landed} is told where the dropped entry is drawn now. */
    void drop(Landing landed) {
        mGrid.removeCallbacks(mFold);
        String into = mFolding != null ? keyOfCell(mFolding) : null;
        boolean folded = into != null;
        unfold();
        String shown = mKey;
        if (into != null) {
            shown = fold(into);
        } else if (mFrom < 0) {
            NativeDrawerHooks.rearrange("took " + mKey + " out of its folder to " + mTo,
                    (order, folders) -> DrawerArrange.takeOut(order, folders, mKey, mTo));
        } else if (mTo != mFrom) {
            NativeDrawerHooks.moveInDrawer(mKey, mTo);
        }
        String key = shown;
        // Once the grid has laid out the new order: anything still off its place springs home,
        // and the dropped entry is handed to whoever lands on it.
        mGrid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mGrid.getViewTreeObserver().removeOnPreDrawListener(this);
                View there = landed != null ? cellOf(key) : null;
                settle(mGrid, folded ? null : there);
                if (landed != null) {
                    landed.at(there, folded);
                }
                return true;
            }
        });
        mGrid.invalidate();
    }

    /** The dragged app folded into the entry {@code into}; the key of what it is now in. */
    private String fold(String into) {
        Item dragged = DrawerArrange.appOf(mKey, NativeDrawerHooks.labelOf(mKey));
        if (into.startsWith("folder:")) {
            String id = into.substring("folder:".length());
            NativeDrawerHooks.rearrange("put " + mKey + " into a folder",
                    (order, folders) -> {
                        for (Item folder : folders) {
                            if (folder.id.equals(id)) {
                                return DrawerArrange.fileInto(order, folders, folder, dragged);
                            }
                        }
                        return false;
                    });
            return into;
        }
        Item target = DrawerArrange.appOf(into, NativeDrawerHooks.labelOf(into));
        String[] made = new String[1];
        NativeDrawerHooks.rearrange("made a folder of " + mKey + " and " + into,
                (order, folders) -> {
                    Item folder = DrawerArrange.merge(order, folders, dragged, target, "Folder");
                    made[0] = folder == null ? null : folder.key();
                    return folder != null;
                });
        return made[0] != null ? made[0] : mKey;
    }

    /** Whether the drag can fold into this cell: an app into an app or one of our folders. */
    private boolean canFold(View cell) {
        String key = keyOfCell(cell);
        return key != null && !key.equals(mKey) && !mKey.startsWith("folder:")
                && (key.startsWith("folder:") || DrawerArrange.appOf(key, null) != null);
    }

    /** Whether a point of the grid is over the middle of a cell's icon. */
    private static boolean inMiddle(View cell, float x, float y) {
        float size = Math.min(cell.getWidth(), cell.getHeight()) * FOLD_SPOT;
        float cx = cell.getLeft() + cell.getWidth() / 2f;
        // The icon sits in the upper part of a cell, its name below it.
        float cy = cell.getTop() + Math.min(cell.getHeight(), cell.getWidth()) / 2f;
        return Math.abs(x - cx) < size / 2f && Math.abs(y - cy) < size / 2f;
    }

    /** The drag has rested long enough: the app under it swells to take it. */
    private void fold() {
        View cell = mResting;
        if (cell == null || cell == mFolding) {
            return;
        }
        mFolding = cell;
        cell.animate().scaleX(FOLD_SCALE).scaleY(FOLD_SCALE).setDuration(Motion.SPRING_MS)
                .setInterpolator(Motion.SPRING).start();
    }

    private void unfold() {
        View cell = mFolding;
        mFolding = null;
        if (cell != null) {
            cell.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS)
                    .setInterpolator(Motion.SPRING_FIRM).start();
        }
    }

    /** Opens the gap at the target: every icon between the two places moves over by one. */
    private void preview() {
        List<View> icons = visibleIcons();
        if (icons.size() < 2) {
            return;
        }
        Map<Integer, View> byIndex = new HashMap<>();
        for (View icon : icons) {
            String key = keyOfCell(icon);
            int index = key == null ? -1 : mOrder.indexOf(key);
            if (index >= 0) {
                byIndex.put(index, icon);
            }
        }
        Geometry grid = Geometry.of(icons);
        for (Map.Entry<Integer, View> entry : byIndex.entrySet()) {
            int index = entry.getKey();
            View icon = entry.getValue();
            if (index == mFrom) {
                // Its place is the gap now; the drag shadow is the app.
                icon.setAlpha(0f);
                send(icon, 0f, 0f);
                continue;
            }
            icon.setAlpha(1f);
            int place = index;
            if (mFrom < 0) {
                // Arriving from a folder: everything from the gap on moves up one.
                place = index >= mTo ? index + 1 : index;
            } else if (mFrom < mTo && index > mFrom && index <= mTo) {
                place = index - 1;
            } else if (mTo < mFrom && index >= mTo && index < mFrom) {
                place = index + 1;
            }
            if (place == index) {
                send(icon, 0f, 0f);
                continue;
            }
            View there = byIndex.get(place);
            float[] cell = there != null && mFrom >= 0
                    ? new float[]{there.getLeft(), there.getTop()}
                    : grid.next(icon, place < index);
            send(icon, cell[0] - icon.getLeft(), cell[1] - icon.getTop());
        }
    }

    private void send(View icon, float dx, float dy) {
        float[] last = mSent.get(icon);
        if (last != null && last[0] == dx && last[1] == dy) {
            return;
        }
        mSent.put(icon, new float[]{dx, dy});
        icon.animate().translationX(dx).translationY(dy).setDuration(Motion.SPRING_MS)
                .setInterpolator(Motion.SPRING_FIRM).start();
    }

    /**
     * Every icon home and showing, on a spring from wherever it is - but {@code landing}, which
     * stays hidden for the drag's own icon to land on.
     */
    private static void settle(ViewGroup grid, View landing) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            if (child.getTranslationX() != 0f || child.getTranslationY() != 0f) {
                child.animate().translationX(0f).translationY(0f).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SPRING_FIRM).start();
            }
            if (child == landing) {
                child.setAlpha(0f);
                continue;
            }
            if (child.getAlpha() < 1f) {
                child.setScaleX(0.8f);
                child.setScaleY(0.8f);
                child.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS)
                        .setInterpolator(Motion.SPRING).start();
            }
        }
    }

    /** The grid's cell drawing the entry {@code key}, or null when it is out of sight. */
    private View cellOf(String key) {
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            View child = mGrid.getChildAt(i);
            if (key.equals(keyOfCell(child))) {
                return child;
            }
        }
        return null;
    }

    /** The key a grid cell draws: an app's, or one of our folders' ({@link ZuiFolders}). */
    static String keyOfCell(View cell) {
        String key = NativeDrawerHooks.keyOfIcon(cell);
        if (key == null && cell instanceof ViewGroup && ((ViewGroup) cell).getChildCount() > 0) {
            Item folder = ZuiFolders.folderOfIcon(((ViewGroup) cell).getChildAt(0));
            key = folder != null ? folder.key() : null;
        }
        return key;
    }

    /** The grid's entries on screen, apps and folders, in reading order. */
    private List<View> visibleIcons() {
        List<View> icons = new ArrayList<>();
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            View child = mGrid.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0
                    && keyOfCell(child) != null) {
                icons.add(child);
            }
        }
        Collections.sort(icons, (a, b) -> a.getTop() != b.getTop()
                ? Integer.compare(a.getTop(), b.getTop())
                : Integer.compare(a.getLeft(), b.getLeft()));
        return icons;
    }

    /** The icon whose laid-out cell holds this point of the grid, or null. */
    private View iconAt(float x, float y) {
        for (View icon : visibleIcons()) {
            if (x >= icon.getLeft() && x < icon.getRight()
                    && y >= icon.getTop() && y < icon.getBottom()) {
                return icon;
            }
        }
        return null;
    }

    private static Rect onScreen(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /** The grid's columns and row height, for a cell whose icon is scrolled out of sight. */
    private static final class Geometry {
        final List<Integer> lefts = new ArrayList<>();
        int rowHeight;

        static Geometry of(List<View> icons) {
            Geometry g = new Geometry();
            int firstTop = icons.get(0).getTop();
            for (View icon : icons) {
                if (icon.getTop() == firstTop) {
                    g.lefts.add(icon.getLeft());
                } else if (g.rowHeight == 0) {
                    g.rowHeight = icon.getTop() - firstTop;
                }
            }
            if (g.rowHeight == 0) {
                g.rowHeight = icons.get(0).getHeight();
            }
            return g;
        }

        /** The cell one place on from {@code icon}, forward or back, wrapping at the row ends. */
        float[] next(View icon, boolean back) {
            int column = lefts.indexOf(icon.getLeft());
            if (column < 0) {
                return new float[]{icon.getLeft(), icon.getTop()};
            }
            if (back) {
                return column == 0
                        ? new float[]{lefts.get(lefts.size() - 1), icon.getTop() - rowHeight}
                        : new float[]{lefts.get(column - 1), icon.getTop()};
            }
            return column == lefts.size() - 1
                    ? new float[]{lefts.get(0), icon.getTop() + rowHeight}
                    : new float[]{lefts.get(column + 1), icon.getTop()};
        }
    }
}
